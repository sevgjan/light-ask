#!/usr/bin/env python3
"""Fill in a Light Phone tool's setup form from this computer, over the USB cable.

    python3 tools/light-setup.py ask                      # opens the form in your browser
    python3 tools/light-setup.py ask --clipboard openai   # takes the OpenAI key from the clipboard, no typing
    op read op://Private/OpenAI/key | python3 tools/light-setup.py ask --stdin openai   # from a password manager

It's the Tool Manager's form, for a LightOS that doesn't offer the Tool Manager. The tool (it needs
UsbSetup.kt) sends its own Tool Manager form when it opens; this script shows that form, and hands your
answers to the tool once, over USB (`adb reverse`), in the same format the Tool Manager uses. The tool
then reads them as usual (Ask encrypts keys with a key in the phone's Android Keystore and deletes the file).

What you type or paste stays in this script's memory only: never written to disk, never printed or logged,
never in a command line. The page is served on 127.0.0.1 only, and refuses other web pages' requests.

Needs adb, and the phone plugged in with USB debugging allowed (or the LP3 simulator running).
"""

import argparse
import html
import http.server
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PORT = 8767
TOOLS = Path(__file__).resolve().parent
ACTIVITY = "com.thelightphone.sdk.LightActivity"


# --- adb ---------------------------------------------------------------------------------------------

def find_adb() -> str:
    found = shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")
    if not Path(found).exists():
        sys.exit("adb not found. Install Android platform-tools, or put adb on your PATH.")
    return found


ADB = find_adb()


def adb(serial: str, *args: str, check: bool = False) -> str:
    # stdin closed: `adb shell` would otherwise forward this script's input (a piped key) to the phone.
    out = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if check and out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def pick_serial(wanted: str | None) -> str:
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, check=True, stdin=subprocess.DEVNULL).stdout
    devices = [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]
    if wanted:
        if wanted not in devices:
            sys.exit(f"{wanted} isn't attached. Attached: {', '.join(devices) or 'none'}")
        return wanted
    phones = [d for d in devices if not d.startswith("emulator-")]
    if len(phones) == 1:
        return phones[0]
    if len(phones) > 1:
        sys.exit(f"Several phones attached ({', '.join(phones)}); choose one with --serial.")
    if devices:
        return devices[0]  # only the simulator
    sys.exit("No phone found. Plug it in with USB and allow USB debugging (adb devices should list it).")


def package_for(tool: str | None) -> str:
    """A tool folder name (ask) or a package name (io.github.sevgjan.ask). Left out, the tool of the repo this
    script sits in (scripts/light-setup.py next to tool/lighttool.toml)."""
    if tool is None:
        toml = TOOLS.parent / "tool" / "lighttool.toml"
        found = re.search(r'^id\s*=\s*"([^"]+)"', toml.read_text(), re.M) if toml.exists() else None
        if found:
            return found.group(1)
        sys.exit("Which tool? e.g. python3 light-setup.py ask")
    toml = TOOLS / tool / "tool" / "lighttool.toml"
    if toml.exists():
        found = re.search(r'^id\s*=\s*"([^"]+)"', toml.read_text(), re.M)
        if found:
            return found.group(1)
    if "." in tool:
        return tool
    sys.exit(f"No tool called {tool!r} in {TOOLS} (and it isn't a package name).")


def relaunch(serial: str, package: str):
    """Opens the tool afresh, so it offers its form (it does so when its first screen shows)."""
    adb(serial, "shell", "am", "force-stop", package)
    adb(serial, "shell", "am", "start", "-n", f"{package}/{ACTIVITY}")


def uid_of_package(serial: str, package: str) -> str | None:
    found = re.search(r"uid:(\d+)", adb(serial, "shell", "pm", "list", "packages", "-U", package))
    return found.group(1) if found else None


def uids_connected_to_us(serial: str) -> set[str] | None:
    """Phone apps with a connection open to our port right now, by uid (None if the phone won't say)."""
    out = adb(serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
    if "local_address" not in out:
        return None
    port = f":{PORT:04X}"
    uids = set()
    for line in out.splitlines()[1:]:
        cols = line.split()
        # sl local remote st tx:rx tr:tm retrnsmt uid ...; state 01 = established.
        if len(cols) > 7 and cols[2].endswith(port) and cols[3] == "01":
            uids.add(cols[7])
    return uids


# --- the form ----------------------------------------------------------------------------------------

def forms_in(node) -> list[dict]:
    """Every form (an object with items and a path) in a Tool Manager manifest."""
    found = []
    if isinstance(node, dict):
        if isinstance(node.get("items"), list) and "path" in node:
            found.append(node)
        for value in node.values():
            found += forms_in(value)
    elif isinstance(node, list):
        for value in node:
            found += forms_in(value)
    return found


def submission(form: dict, values: dict[str, str]) -> dict:
    """What the Tool Manager uploads for a filled-in form."""
    return {"responses": [{"item": item, "response": values.get(item["id"], "")} for item in form["items"]]}


class State:
    def __init__(self, package: str, token: str):
        self.package = package
        self.token = token
        self.lock = threading.Lock()
        self.title: str | None = None
        self.forms: list[dict] = []
        self.pending: dict[str, dict] | None = None  # path -> submission, waiting for the phone
        self.delivered = False
        self.problem: str | None = None
        self.clipboard_field: str | None = None
        self.clipboard_value: str | None = None


PAGE = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>{title}: set up</title>
<style>
 :root {{ color-scheme: light dark; --bg:#fff; --fg:#111; --muted:#666; --line:#ccc; }}
 @media (prefers-color-scheme: dark) {{ :root {{ --bg:#111; --fg:#eee; --muted:#999; --line:#444; }} }}
 body {{ background:var(--bg); color:var(--fg); font:16px/1.5 -apple-system, system-ui, sans-serif; max-width:36rem; margin:3rem auto; padding:0 16px; }}
 label {{ display:block; margin:1rem 0 .25rem; }}
 input {{ width:100%; box-sizing:border-box; font:14px ui-monospace, monospace; background:var(--bg); color:var(--fg); border:1px solid var(--line); padding:.5rem; }}
 button {{ font-size:1rem; padding:.5rem 1.2rem; margin-top:1rem; }}
 p.muted {{ color:var(--muted); }}
 .msg {{ border-left:3px solid var(--fg); padding-left:.75rem; }}
</style>
<h1>{title}</h1>
<div id="body">{body}</div>
<p class="muted">Goes to the phone once, over the USB cable. Nothing is saved on this computer.</p>
<script>
 const token = {token_js}, shown = {phase_js};
 async function poll() {{
   try {{
     const r = await fetch("/state.json?t=" + token, {{cache: "no-store"}});
     const s = await r.json();
     const status = document.getElementById("status");
     if (status && s.status) status.textContent = s.status;
     if (s.phase !== shown) return location.reload();
     if (s.phase !== "done") setTimeout(poll, 1000);
   }} catch (e) {{ setTimeout(poll, 2000); }}
 }}
 poll();
</script>
"""


def phase(state: State) -> str:
    """Where things are: waiting for the tool, the form to fill in, sending it, or done. Call with the lock held."""
    if state.delivered:
        return "done"
    if state.pending is not None:
        return "sending"
    return "form" if state.forms else "waiting"


def render(state: State) -> str:
    with state.lock:
        if state.delivered:
            body = '<p class="msg">Saved on the phone. You can close this page.</p>'
        elif state.pending is not None:
            body = '<p class="msg" id="status">Sending to the phone…</p>'
        elif not state.forms:
            body = '<p id="status">Waiting for the tool to open on the phone…</p>'
        else:
            parts = []
            for form in state.forms:
                fields = []
                for item in form["items"]:
                    kind = "password" if item.get("hidden") else "text"
                    required = " required" if item.get("required") else ""
                    fields.append(
                        f'<label for="f-{html.escape(item["id"])}">{html.escape(item.get("label") or item["id"])}</label>'
                        f'<input id="f-{html.escape(item["id"])}" name="v:{html.escape(item["id"])}" type="{kind}" '
                        f'autocomplete="off" spellcheck="false" autocapitalize="off"{required}>'
                    )
                header = f'<p>{html.escape(form["headerText"])}</p>' if form.get("headerText") else ""
                parts.append(
                    f'<form method="post" action="/submit"><h2>{html.escape(form.get("label") or "Setup")}</h2>{header}'
                    f'<input type="hidden" name="t" value="{state.token}"><input type="hidden" name="path" value="{html.escape(form["path"])}">'
                    + "".join(fields)
                    + f'<button>{html.escape(form.get("buttonText") or "Save to phone")}</button></form>'
                )
            body = "".join(parts) + '<p class="muted" id="status"></p>'
        title = html.escape(state.title or state.package)
        shown = phase(state)
    return PAGE.format(title=title, body=body, token_js=json.dumps(state.token), phase_js=json.dumps(shown))


def make_handler(state: State, serial: str):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # never log requests: they can carry what was typed

        def reply(self, status: int, body: str, kind: str = "text/html; charset=utf-8"):
            data = body.encode()
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.end_headers()
            self.wfile.write(data)

        def local(self) -> bool:
            # Refuse requests from web pages that point another name at 127.0.0.1 (DNS rebinding).
            if self.headers.get("Host") in (f"127.0.0.1:{PORT}", f"localhost:{PORT}"):
                return True
            self.reply(403, "Forbidden", "text/plain")
            return False

        def do_GET(self):
            if not self.local():
                return
            url = urlparse(self.path)
            if url.path == "/":
                self.reply(200, render(state))
            elif url.path == "/state.json":
                if parse_qs(url.query).get("t", [""])[0] != state.token:
                    return self.reply(403, "{}", "application/json")
                with state.lock:
                    data = {"phase": phase(state), "status": state.problem or ""}
                self.reply(200, json.dumps(data), "application/json")
            else:
                self.reply(404, "Not found", "text/plain")

        def body(self) -> bytes:
            length = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(min(length, 1_000_000))

        def do_POST(self):
            if not self.local():
                return
            if self.path == "/tool":
                return self.tool()
            if self.path == "/submit":
                return self.submit()
            self.reply(404, "Not found", "text/plain")

        def submit(self):
            form = parse_qs(self.body().decode(), keep_blank_values=True)
            if form.get("t", [""])[0] != state.token:
                return self.reply(403, "Forbidden", "text/plain")
            path = form.get("path", [""])[0]
            values = {key[2:]: v[0].strip() for key, v in form.items() if key.startswith("v:")}
            with state.lock:
                spec = next((f for f in state.forms if f["path"] == path), None)
                if spec is None:
                    return self.reply(400, "Unknown form", "text/plain")
                state.pending = {path: submission(spec, values)}
                state.problem = None
            values.clear()
            print("Sending to the phone…")
            relaunch(serial, state.package)
            self.send_response(303)
            self.send_header("Location", "/")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def tool(self):
            # Only the phone (through adb reverse) talks here: never a web page in this computer's browser.
            if self.headers.get("X-Light-Setup") != "1" or self.headers.get("Origin") or self.headers.get("Sec-Fetch-Site"):
                return self.reply(403, "Forbidden", "text/plain")
            try:
                hello = json.loads(self.body())
            except ValueError:
                return self.reply(400, "Bad request", "text/plain")
            package = hello.get("package")
            if package != state.package:
                return self.reply(200, '{"forms":{}}', "application/json")
            forms = forms_in(hello.get("manifest", {}))
            with state.lock:
                state.title = (hello.get("manifest") or {}).get("title") or state.title
                state.forms = forms
                if state.pending is None and state.clipboard_value is not None:
                    state.pending = clipboard_submission(state, forms)
                answer = state.pending
            if answer is None:
                return self.reply(200, '{"forms":{}}', "application/json")
            # Check it really is that tool on the phone asking, not another app claiming its name.
            want = uid_of_package(serial, package)
            seen = uids_connected_to_us(serial)
            if want and seen is not None and seen and want not in seen:
                with state.lock:
                    state.problem = "Another app on the phone asked for these answers; they weren't given out."
                print("Refused: the app asking isn't", package)
                return self.reply(403, "Forbidden", "text/plain")
            body = json.dumps({"forms": answer})
            with state.lock:
                state.pending = None
                state.delivered = True
            self.reply(200, body, "application/json")
            del body, answer

    return Handler


def clipboard_submission(state: State, forms: list[dict]) -> dict | None:
    """The form with one field filled from the clipboard (matched by id, or by a word of its label)."""
    want = (state.clipboard_field or "").lower()
    for form in forms:
        for item in form["items"]:
            if want == item["id"].lower() or want in (item.get("label") or "").lower():
                values = {item["id"]: state.clipboard_value or ""}
                state.clipboard_value = None
                return {form["path"]: submission(form, values)}
    state.problem = f"No field called {state.clipboard_field!r} in this tool's form."
    names = ", ".join(i["id"] for f in forms for i in f["items"])
    print(f"No field called {state.clipboard_field!r}. Fields: {names}")
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tool", nargs="?", help="the tool's folder name in tools/ (ask, mail, …) or its package name; "
                    "left out inside a tool's repo, that tool")
    ap.add_argument("--clipboard", metavar="FIELD", help="fill this field (its id, or a word of its label) from the clipboard, without a browser")
    ap.add_argument("--stdin", metavar="FIELD", help="fill this field from standard input (a pipe), without a browser")
    ap.add_argument("--keep-clipboard", action="store_true", help="with --clipboard: don't clear the clipboard afterwards")
    ap.add_argument("--serial", help="adb serial of the phone, when several devices are attached")
    ap.add_argument("--timeout", type=int, default=300, help="seconds to wait before giving up (default 300)")
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    field = value = None
    if args.clipboard and args.stdin:
        sys.exit("Use --clipboard or --stdin, not both.")
    if args.clipboard:
        if sys.platform != "darwin":
            sys.exit("--clipboard reads the macOS clipboard (pbpaste); paste into the browser form instead.")
        value = subprocess.run(["pbpaste"], capture_output=True, text=True).stdout.strip()
        if not value:
            sys.exit("The clipboard is empty. Copy the value first.")
        field = args.clipboard
    elif args.stdin:
        if sys.stdin.isatty():
            sys.exit("--stdin reads a pipe, e.g.: op read op://… | python3 tools/light-setup.py ask --stdin openai")
        value = sys.stdin.read().strip()
        if not value:
            sys.exit("Nothing came in on standard input.")
        field = args.stdin
    direct = field is not None

    # Only now talk to adb (with its stdin closed).
    serial = pick_serial(args.serial)
    package = package_for(args.tool)
    if f"package:{package}" not in adb(serial, "shell", "pm", "list", "packages", package):
        sys.exit(f"{package} isn't installed on {serial}.")
    state = State(package, secrets.token_urlsafe(24))
    state.clipboard_field, state.clipboard_value = field, value
    del value

    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), make_handler(state, serial))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    adb(serial, "reverse", f"tcp:{PORT}", f"tcp:{PORT}", check=True)
    print(f"Connected to {serial} over USB. Opening {package} on the phone…")
    relaunch(serial, package)
    if not direct:
        url = f"http://127.0.0.1:{PORT}/"
        print(f"Fill in the form on {url} (opening it now). Ctrl-C to stop.")
        webbrowser.open(url)

    try:
        deadline = time.time() + args.timeout
        while time.time() < deadline:
            with state.lock:
                done, problem = state.delivered, state.problem
            if done:
                print("Saved on the phone.")
                if args.clipboard and not args.keep_clipboard:
                    subprocess.run(["pbcopy"], input="", text=True)
                    print("Cleared the clipboard.")
                time.sleep(1.5)  # let the browser page show it
                break
            if problem and direct:
                break
            time.sleep(0.3)
        else:
            print("The phone didn't pick it up. Is the tool updated (it needs UsbSetup), and did it open?")
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        adb(serial, "reverse", "--remove", f"tcp:{PORT}")
        with state.lock:
            state.pending = None
            state.clipboard_value = None
        print("Stopped; nothing was kept.")


if __name__ == "__main__":
    main()
