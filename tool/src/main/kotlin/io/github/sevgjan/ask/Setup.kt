package io.github.sevgjan.ask

import com.thelightphone.sdk.LightFileShare
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Setup from a computer: the Tool Manager shows a form in the browser and uploads the answers as
 * a JSON file into `files/shared/setup`. The tool reads the newest one into [Secrets], then deletes
 * the files so no plaintext token stays on the phone.
 */
object Setup {
    private const val PATH = "setup"

    /** Bumped by the entry point whenever the Tool Manager changes the tool's files. */
    val updates = MutableStateFlow(0L)

    fun manifest(title: String, header: String, items: List<FormItem>) = ClientToolManifest(
        title = title,
        roots = listOf(ClientLeafNode(FormSpec(label = "Setup", path = PATH, items = items, headerText = header, buttonText = "Save to phone"))),
    )

    /** Values of the newest submission keyed by item id, or null if there is none. Consumes all submissions. */
    fun consume(fileShare: LightFileShare): Map<String, String>? {
        val files = fileShare.list(PATH).filter { it.isFile && it.name.endsWith(".json") }
        val newest = files.maxByOrNull { it.lastModified } ?: return null
        val values = fileShare.read("$PATH/${newest.name}") { parse(it.readText()) }
        files.forEach { fileShare.delete("$PATH/${it.name}") }
        return values
    }

    fun parse(json: String): Map<String, String>? = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(Submission.serializer(), json)
            .responses.associate { it.item.id to it.response.trim() }
            .filterValues { it.isNotEmpty() }
    }.getOrNull()

    @Serializable
    private data class Submission(val responses: List<Response>)

    @Serializable
    private data class Response(val item: Item, val response: String)

    @Serializable
    private data class Item(val id: String)
}
