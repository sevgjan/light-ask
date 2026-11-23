package io.github.sevgjan.ask

import com.thelightphone.sdk.LightFileShare
import com.thelightphone.toolmanager.ClientToolManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Tool Manager's forms over the USB cable, for a LightOS that doesn't offer the Tool Manager.
 *
 * On the computer, `tools/light-setup.py <tool>` runs `adb reverse`, so 127.0.0.1:[PORT] on the phone reaches it.
 * When the tool opens, [check] offers it the tool's own Tool Manager manifest; the script shows those forms in the
 * computer's browser, and once one is filled in, the answers come back here and are written to
 * `files/shared/<form path>/` exactly as the Tool Manager writes them, so the tool reads them the usual way.
 * Nothing happens unless that script is running: without it, the connection is refused at once.
 *
 * The same file works in any tool: copy it and change only the package line.
 */
object UsbSetup {
    const val PORT = 8767
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Offers [manifest]'s forms to the computer and writes any filled-in ones into [fileShare]. Returns how many
     * came back (0 when the script isn't running or nothing is filled in yet). Blocking: call it off the main thread.
     */
    fun check(packageName: String, manifest: ClientToolManifest, fileShare: LightFileShare, connectTimeoutMs: Int = 300): Int {
        val manifestJson = manifest.encode()
        val forms = formPaths(json.parseToJsonElement(manifestJson))
        if (forms.isEmpty()) return 0
        val body = """{"package":${JsonPrimitive(packageName)},"manifest":$manifestJson}"""
        val response = post("/tool", body, connectTimeoutMs) ?: return 0
        val answers = answers(response, forms)
        answers.forEach { (path, submission) ->
            fileShare.write("$path/usb-${System.currentTimeMillis()}.json") { it.write(submission) }
        }
        return answers.size
    }

    /** The paths of every form in a manifest (any object with `items` and a `path`), wherever it sits. */
    fun formPaths(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> {
            val own = if (element["items"] is JsonArray) element["path"]?.jsonPrimitive?.contentOrNull else null
            setOfNotNull(own) + element.values.flatMap { formPaths(it) }
        }
        is JsonArray -> element.flatMap { formPaths(it) }.toSet()
        else -> emptySet()
    }

    /** The filled-in forms in the computer's answer, keyed by path; only paths this tool's own forms have. */
    fun answers(body: String, allowed: Set<String>): Map<String, String> {
        val forms = runCatching { json.parseToJsonElement(body).jsonObject["forms"]?.jsonObject }.getOrNull() ?: return emptyMap()
        return forms.filterKeys { it in allowed }.mapValues { (_, submission) -> submission.toString() }
            .filterValues { it.contains("\"responses\"") }
    }

    /** A plain HTTP/1.0 POST to the computer; null when nothing is listening or it didn't answer 200. */
    private fun post(path: String, body: String, connectTimeoutMs: Int): String? = Socket().use { s ->
        try {
            s.connect(InetSocketAddress("127.0.0.1", PORT), connectTimeoutMs)
            s.soTimeout = 10_000
            val bytes = body.encodeToByteArray()
            s.getOutputStream().apply {
                write(
                    ("POST $path HTTP/1.0\r\nHost: 127.0.0.1:$PORT\r\nX-Light-Setup: 1\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n").encodeToByteArray(),
                )
                write(bytes)
                flush()
            }
            val response = s.getInputStream().readBytes().decodeToString()
            if (" 200 " !in response.substringBefore("\r\n") + " ") return null
            response.substringAfter("\r\n\r\n")
        } catch (e: IOException) {
            null
        }
    }
}
