package io.github.sevgjan.ask

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * OpenAI's Responses API, streamed and stateless: nothing is stored on OpenAI's side (`store: false`), so every
 * request carries the conversation. GPT searches the web and draws pictures with OpenAI's own tools.
 */
object OpenAiApi : ChatApi {
    private const val URL = "https://api.openai.com/v1/responses"

    private val json = Json { ignoreUnknownKeys = true }

    fun tools(options: Options): JsonArray = buildJsonArray {
        if (options.webSearch) add(buildJsonObject { put("type", "web_search") })
        add(buildJsonObject {
            put("type", "image_generation")
            put("model", options.imageModel.responsesId)
            // One rough look while it draws, so the phone shows something during the wait.
            put("partial_images", 1)
        })
    }

    /**
     * The conversation as Responses input: photos with the words, and the newest picture GPT made sent back as an
     * image (in a user message, which works without stored state) so it can change it when asked.
     */
    fun input(history: List<Turn>): JsonArray {
        val keepPictures = pictureBudget(history)
        val lastMade = history.indexOfLast { it.role == Role.ASSISTANT && it.made.isNotEmpty() }
        return buildJsonArray {
            history.forEachIndexed { index, turn ->
                when (turn.role) {
                    Role.USER -> {
                        val parts = mutableListOf<JsonObject>()
                        var mentioned = 0
                        turn.images.forEachIndexed { i, picture ->
                            val bytes = if (keepPictures(index, i)) picture.load() else null
                            if (bytes != null) parts += image(picture.mediaType, bytes) else mentioned++
                        }
                        val words = listOfNotNull(
                            if (mentioned > 0) "[${if (mentioned == 1) "A photo" else "$mentioned photos"} I sent earlier]" else null,
                            turn.text.ifBlank { null },
                        ).joinToString("\n")
                        if (words.isNotEmpty() || parts.isEmpty()) parts.add(0, inputText(words.ifEmpty { "(photo)" }))
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(parts))
                        })
                    }
                    Role.ASSISTANT -> {
                        val made = if (turn.made.isEmpty()) null else "[I made ${if (turn.made.size == 1) "a picture" else "${turn.made.size} pictures"} here.]"
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", listOfNotNull(turn.text.ifBlank { null }, made).joinToString("\n").ifEmpty { "(no answer)" })
                        })
                        val picture = turn.made.lastOrNull()?.takeIf { index == lastMade }
                        val bytes = picture?.load()
                        if (picture != null && bytes != null) add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(inputText("(This is the picture you just made, in case I ask you to change it.)"))
                                add(image(picture.mediaType, bytes))
                            })
                        })
                    }
                }
            }
        }
    }

    private fun inputText(text: String) = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    private fun image(mediaType: String, bytes: ByteArray) = buildJsonObject {
        put("type", "input_image")
        put("image_url", "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}")
        put("detail", "auto")
    }

    fun requestBody(options: Options, input: JsonArray): JsonObject = buildJsonObject {
        put("model", options.model.id)
        put("instructions", systemPrompt(options, pictures = true))
        put("input", input)
        put("reasoning", buildJsonObject { put("effort", options.depth.effort) })
        put("max_output_tokens", 32000)
        put("store", false)
        put("stream", true)
        put("tools", tools(options))
    }

    fun errorMessage(status: Int, body: String): String {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        return when {
            status == 401 -> "The OpenAI API key was rejected. Set a new one in settings."
            code == "insufficient_quota" -> "Your OpenAI account is out of credit or has a billing problem."
            code == "moderation_blocked" || code == "content_policy_violation" -> "OpenAI wouldn't make that one."
            status == 403 -> "This API key isn't allowed to use that model. Pictures may need a verified OpenAI organization."
            status == 404 -> "Model not available to this API key. Try another one."
            status == 413 -> "That's too much to send at once. Try fewer photos, or a new chat."
            status == 429 -> "Too many requests right now. Try again in a minute."
            status >= 500 -> "OpenAI is busy or having trouble. Try again shortly."
            else -> error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed ($status)."
        }
    }

    override suspend fun stream(apiKey: String, options: Options, history: List<Turn>, sink: Sink): Finish {
        val reply = OpenAiStream(sink)
        try {
            Http.streamEvents(URL, mapOf("Authorization" to "Bearer $apiKey"), requestBody(options, input(history))) { reply.event(it.data) }
        } catch (e: HttpError) {
            throw ApiException(errorMessage(e.status, e.body))
        } finally {
            sink.cost(reply.cost(options.model))
        }
        return Finish(truncated = reply.truncated, refused = reply.refused)
    }
}

/** Reads GPT's streamed reply: text as it's written, what it's doing, the pictures it made and the pages it used. */
class OpenAiStream(private val sink: Sink) {
    private val json = Json { ignoreUnknownKeys = true }
    private val delivered = HashSet<String>()
    private val searches = HashSet<String>()
    private var usedImageTool = false
    private var usage: JsonObject? = null
    private var lastItem: String? = null
    private var wrote = false
    private val refusal = StringBuilder()
    var truncated = false
        private set
    var refused = false
        private set

    suspend fun event(data: String) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull
        when (root.str("type")) {
            "response.output_item.added" -> {
                val item = root["item"] as? JsonObject ?: return
                when (val kind = item.str("type")) {
                    "reasoning" -> sink.status("Thinking…")
                    "web_search_call" -> sink.status("Searching the web…")
                    "image_generation_call" -> {
                        usedImageTool = true
                        sink.status("Drawing…")
                    }
                    "message" -> {
                        sink.status(null)
                        if (wrote && lastItem != "message") sink.text("\n\n")
                    }
                    else -> if (kind != null) sink.status("Working…")
                }
                if (item.str("type") != "reasoning") lastItem = item.str("type")
            }
            "response.output_text.delta" -> root.str("delta")?.let {
                sink.text(it)
                wrote = true
            }
            "response.refusal.delta" -> root.str("delta")?.let { refusal.append(it) }
            "response.output_text.annotation.added" -> (root["annotation"] as? JsonObject)?.let(::citation)
            "response.image_generation_call.partial_image" -> root.str("partial_image_b64")?.let { b64 ->
                decode(b64)?.let(sink::preview)
            }
            "response.output_item.done" -> (root["item"] as? JsonObject)?.let { item(it) }
            "response.completed", "response.incomplete" -> {
                val response = root["response"] as? JsonObject ?: return
                usage = response.obj("usage")
                // Pictures the per-item events didn't bring (they normally do).
                response["output"]?.jsonArray?.forEach { (it as? JsonObject)?.let { item -> item(item) } }
                val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
                if (reason == "max_output_tokens") truncated = true
                if (!wrote && refusal.isNotEmpty()) {
                    refused = true
                    sink.text(refusal.toString())
                }
            }
            "response.failed" -> {
                usage = root.obj("response")?.obj("usage")
                val error = root.obj("response")?.obj("error")
                throw ApiException(failure(error))
            }
            "error" -> throw ApiException(failure(root["error"] as? JsonObject ?: root))
        }
    }

    private suspend fun item(item: JsonObject) {
        fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull
        when (item.str("type")) {
            "web_search_call" -> if (item.str("status") == "completed") {
                item.str("id")?.let { searches.add(it) }
            }
            "image_generation_call" -> {
                usedImageTool = true
                val id = item.str("id") ?: return
                val result = item.str("result") ?: return
                if (!delivered.add(id)) return
                decode(result)?.let { sink.image(it, item.str("revised_prompt")) }
                sink.status(null)
            }
            "message" -> item["content"]?.jsonArray?.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                p["annotations"]?.jsonArray?.forEach { (it as? JsonObject)?.let(::citation) }
            }
        }
    }

    fun cost(model: Model): ChatCost = ApiPricing.openAi(model, usage, searches.size, usedImageTool)

    private fun citation(annotation: JsonObject) {
        if (annotation["type"]?.jsonPrimitive?.contentOrNull != "url_citation") return
        val url = annotation["url"]?.jsonPrimitive?.contentOrNull ?: return
        sink.source(annotation["title"]?.jsonPrimitive?.contentOrNull ?: url, url)
    }

    private fun failure(error: JsonObject?): String {
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        return when (code) {
            "moderation_blocked", "content_policy_violation" -> "OpenAI wouldn't make that one."
            "rate_limit_exceeded" -> "Too many requests right now. Try again in a minute."
            "server_error" -> "OpenAI had trouble with this one. Try again shortly."
            else -> error?.get("message")?.jsonPrimitive?.contentOrNull ?: "OpenAI stopped with an error."
        }
    }

    private fun decode(b64: String): ByteArray? = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
}

/** OpenAI's Images API, for pictures Claude asks for. */
object OpenAiImages {
    private const val URL = "https://api.openai.com/v1/images/generations"
    private val json = Json { ignoreUnknownKeys = true }

    class Image(val bytes: ByteArray, val revisedPrompt: String?, val cost: ChatCost)

    suspend fun generate(apiKey: String, model: String, prompt: String): Image {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
        }
        val text = try {
            Http.postJson(URL, mapOf("Authorization" to "Bearer $apiKey"), body)
        } catch (e: HttpError) {
            throw ApiException(OpenAiApi.errorMessage(e.status, e.body))
        }
        return parse(text) ?: throw ApiException("OpenAI sent no picture back.")
    }

    fun parse(text: String): Image? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull ?: return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull() ?: return null
        return Image(bytes, first["revised_prompt"]?.jsonPrimitive?.contentOrNull, ApiPricing.image(root.obj("usage")))
    }
}
