# Ask

Claude and GPT on the Light Phone III: chats you can come back to, photos you can ask about, pictures drawn on request, answers that search the web, and speaking instead of typing.

- **Chats.** Every conversation is kept on the phone, newest first. Tap a chat's title to switch models mid-chat (Claude Opus 5.5, Sonnet 5.5, Haiku 5.5, GPT-6.2 Astra, GPT-6.2 Sol, GPT-6.1 Terra, GPT-6 Luna); hold a chat in the list to rename or delete it. Answers stream in as they're written; the stop button keeps what's there so far. Tap an answer to copy it, ask again, or open its sources; tap your last question to change it and ask again.
- **Chat cost.** Swipe left on a chat in the list to see its estimated API cost in USD, and right to close it. Ask adds up the usage reported since this feature was installed, including retries and web searches. Earlier replies and missing provider usage can leave an estimate partial. Dictation is billed separately.
- **Photos.** The picture icon adds photos from the phone (or a new one from the camera) to ask about. Up to the six newest photos in a chat go along with each question.
- **Pictures.** Ask for a picture and one is drawn: GPT with OpenAI's image tool (it can also change a picture it made, or one you sent), Claude through OpenAI's Images API when an OpenAI key is set. Open a picture full screen to zoom, show it in colour (COLORS ON), or SAVE it to the phone's gallery (Pictures/Ask).
- **Web search.** When a question needs it, the model searches the web; the pages it used are listed under the answer and open in the browser.
- **Speak.** The microphone dictates into the question line, with the words appearing as you talk (OpenAI's live transcription, `gpt-live-transcribe`); DONE to fix them first, SEND to send at once. SPEAK on the chat list starts a new chat by voice.
- **LightsApp's keyboard.** Swipe typing, accents and digits on long press, emoji, an optional suggestions row, double space for a full stop, space-bar cursor dragging (Settings › Keyboard).
- **Colours.** Settings › Colors shows all of Ask in colour while it's open, on an otherwise grayscale LP3; the picture viewer has its own color switch.
- An optional daily limit on questions (Settings › Questions per day).

## Setup

Ask needs an Anthropic or an OpenAI API key, or both; the OpenAI key also draws pictures and turns speech into text whichever model answers. Ways to get them onto the phone, none of which involve typing them on it:

- **From a computer over USB** (works on every LightOS): plug in the phone and run, from the `light-phone` folder,

  ```sh
  python3 tools/light-setup.py ask                         # a page opens in the browser: paste the keys there
  python3 tools/light-setup.py ask --clipboard openai      # or: copy the key first, no browser (then clears the clipboard)
  op read op://Private/OpenAI/key | python3 tools/light-setup.py ask --stdin openai   # or from a password manager
  ```

  The key stays in the script's memory only (never on disk, in the terminal, or in a command line) and is handed to Ask once over the cable. The script checks that it really is Ask asking for it, by the app's uid on the phone (where the phone lists its connections to adb, as the LP3 simulator does).
- **The Tool Manager**, on a LightOS that has it: choose Ask and paste the keys into its form.
- Settings › API keys, to type or paste one on the phone.

On the phone, keys are encrypted with an AES key that lives in the Android Keystore, and the form's file is deleted as soon as it's read.

Some LightOS builds don't ask for permissions properly. If photos, the microphone or the camera say they aren't allowed, grant them once from the computer (and WRITE_SECURE_SETTINGS for the colour switch):

```sh
for p in READ_MEDIA_IMAGES RECORD_AUDIO CAMERA WRITE_SECURE_SETTINGS; do adb shell pm grant io.github.sevgjan.ask android.permission.$p; done
```

## Privacy

Questions, photos and the chat so far go to the provider of the chat's model, with your key and billed to your account (OpenAI requests are sent with `store: false`). Pictures you ask Claude for are drawn by OpenAI. Speech is streamed to OpenAI as you talk and isn't stored. Chats and pictures are kept only on the phone, in Ask's private storage, until you delete them. Nothing else leaves the phone: no analytics.

## Build

This version is a sideload: besides `tool/` (a Light SDK tool), it has `engine/`, a small Android library for what the SDK doesn't allow (colour on a grayscale screen, saving to the gallery, the camera, opening links). From `tools/`:

```sh
./dev.sh ask check     # unit tests, lint, release build
./dev.sh ask run       # install on the LP3 simulator
./dev.sh ask apk       # release APK: .sdk/tool/build/outputs/apk/release/tool-release.apk
adb -s <phone serial> install -r .sdk/tool/build/outputs/apk/release/tool-release.apk
```

`tools/light-setup.py` and `UsbSetup.kt` deliver any tool's Tool Manager form over USB; another tool can use them by copying `UsbSetup.kt` and calling `UsbSetup.check(...)` when it opens.
