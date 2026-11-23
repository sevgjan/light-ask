package io.github.sevgjan.ask

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Anthropic's Messages API, streamed. Earlier turns go back as plain text (what the user saw), so no thinking block
 * is ever replayed into a conversation it didn't come from; within one reply, the assistant's content goes back
 * unchanged for a tool result or a paused search.
 */
object ClaudeApi : ChatApi {
    private const val URL = "https://api.anthropic.com/v1/messages"
    const val VERSION = "2026-10-21"
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    const val IMAGE_TOOL = "generate_image"
    /** Requests in one reply: tool results and paused searches each take another. */
    private const val MAX_STEPS = 8

    private val json = Json { ignoreUnknownKeys = true }

    fun tools(options: Options): JsonArray = buildJsonArray {
        if (options.webSearch) add(buildJsonObject {
            // The newer search (with filtering) is for Opus and Sonnet; Haiku keeps the basic one.
            put("type", if (options.model == Model.HAIKU) "web_search_20250305" else "web_search_20260209")
            put("name", "web_search")
            put("max_uses", 5)
        })
        if (options.openAiKey != null) add(buildJsonObject {
            put("name", IMAGE_TOOL)
            put(
                "description",
                "Makes a picture from a detailed description and shows it to the user. Use it whenever the user asks for " +
                    "a picture, drawing, illustration, logo or image. The prompt should say what to show and in what style.",
            )
            put("input_schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "What the picture should show, in detail, and its style.")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
            })
        })
    }

    /** The conversation in Claude's format: photos before the words, same-role turns merged, photos only for the newest few. */
    fun messages(history: List<Turn>): List<JsonObject> {
        val keepPictures = pictureBudget(history)
        val merged = mutableListOf<Pair<Role, MutableList<JsonObject>>>()
        history.forEachIndexed { index, turn ->
            val blocks = mutableListOf<JsonObject>()
            when (turn.role) {
                Role.USER -> {
                    var mentioned = 0
                    turn.images.forEachIndexed { i, picture ->
                        val bytes = if (keepPictures(index, i)) picture.load() else null
                        if (bytes != null) blocks += buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", picture.mediaType)
                                put("data", Base64.getEncoder().encodeToString(bytes))
                            })
                        } else mentioned++
                    }
                    val words = listOfNotNull(
                        if (mentioned > 0) "[${if (mentioned == 1) "A photo" else "$mentioned photos"} I sent earlier]" else null,
                        turn.text.ifBlank { null },
                    ).joinToString("\n")
                    if (words.isNotEmpty() || blocks.isEmpty()) blocks += text(words.ifEmpty { "(photo)" })
                }
                Role.ASSISTANT -> {
                    val made = if (turn.made.isEmpty()) null else "[You made ${if (turn.made.size == 1) "a picture" else "${turn.made.size} pictures"} here.]"
                    blocks += text(listOfNotNull(turn.text.ifBlank { null }, made).joinToString("\n").ifEmpty { "(no answer)" })
                }
            }
            val last = merged.lastOrNull()
            if (last != null && last.first == turn.role) last.second += blocks else merged += turn.role to blocks
        }
        return merged.map { (role, blocks) ->
            buildJsonObject {
                put("role", if (role == Role.USER) "user" else "assistant")
                put("content", JsonArray(blocks))
            }
        }
    }

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    fun requestBody(options: Options, messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", options.model.id)
        put("max_tokens", 32000)
        put("stream", true)
        put("system", systemPrompt(options, pictures = options.openAiKey != null))
        put("output_config", buildJsonObject { put("effort", options.depth.effort) })
        if (options.model.serverFallback) put("fallbacks", "default")
        // Caches the conversation so far: long chats with photos cost much less on the next turn.
        put("cache_control", buildJsonObject { put("type", "ephemeral") })
        val tools = tools(options)
        if (tools.isNotEmpty()) put("tools", tools)
        put("messages", JsonArray(messages))
    }

    fun headers(apiKey: String, model: Model) = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", VERSION)
        if (model.serverFallback) put("anthropic-beta", FALLBACK_BETA)
    }

    fun errorMessage(status: Int, body: String): String {
        val apiMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (status) {
            401 -> "The Anthropic API key was rejected. Set a new one in settings."
            402 -> "Your Anthropic account has a billing problem."
            403 -> "This API key isn't allowed to use that model."
            404 -> "Model not available to this API key. Try another one."
            413 -> "That's too much to send at once. Try fewer photos, or a new chat."
            429 -> "Too many requests right now. Try again in a minute."
            500, 529 -> "Claude is busy or having trouble. Try again shortly."
            else -> apiMessage ?: "Request failed ($status)."
        }
    }

    override suspend fun stream(apiKey: String, options: Options, history: List<Turn>, sink: Sink): Finish {
        var messages = messages(history)
        repeat(MAX_STEPS) {
            val reply = ClaudeStream(sink)
            try {
                Http.streamEvents(URL, headers(apiKey, options.model), requestBody(options, messages)) { reply.event(it.data) }
            } catch (e: HttpError) {
                throw ApiException(errorMessage(e.status, e.body))
            } finally {
                sink.cost(reply.cost(options.model))
            }
            when (reply.stopReason) {
                "refusal" -> return Finish(refused = true)
                "tool_use" -> {
                    val content = reply.content()
                    val results = reply.toolUses().map { use -> runTool(use, options, sink) }
                    messages = messages + assistant(content) + buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(results))
                    }
                }
                // The server paused a long search: send its turn back as it is and it carries on.
                "pause_turn" -> messages = messages + assistant(reply.content())
                else -> return Finish(truncated = reply.stopReason == "max_tokens")
            }
            sink.status("Thinking…")
        }
        return Finish()
    }

    private fun assistant(content: JsonArray) = buildJsonObject {
        put("role", "assistant")
        put("content", content)
    }

    /** Runs one of Ask's own tools (drawing a picture) and returns its tool_result block. */
    private suspend fun runTool(use: JsonObject, options: Options, sink: Sink): JsonObject {
        val id = use["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = use["name"]?.jsonPrimitive?.contentOrNull
        val prompt = (use["input"] as? JsonObject)?.get("prompt")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val key = options.openAiKey
        val outcome: Pair<String, Boolean> = when {
            name != IMAGE_TOOL -> "Unknown tool." to true
            key == null -> "Pictures need an OpenAI API key, and none is set in Ask." to true
            prompt.isEmpty() -> "The prompt was empty." to true
            else -> try {
                sink.status("Drawing…")
                val image = OpenAiImages.generate(key, options.imageModel.imagesId, prompt)
                sink.cost(image.cost)
                sink.image(image.bytes, image.revisedPrompt ?: prompt)
                "The picture was made and is now on the user's screen. Don't describe it unless they ask." to false
            } catch (e: ApiException) {
                (e.message ?: "The picture couldn't be made.") to true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't reach OpenAI to make the picture." to true
            }
        }
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            put("content", outcome.first)
            if (outcome.second) put("is_error", true)
        }
    }
}

/** Builds Claude's reply from its stream: the blocks as the API would have returned them, plus what the screen shows. */
class ClaudeStream(private val sink: Sink) {
    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
    private val partialInput = HashMap<Int, StringBuilder>()
    private val citations = HashMap<Int, MutableList<JsonElement>>()
    private var lastKind: String? = null
    private var wrote = false
    private var usage = JsonObject(emptyMap())
    private var responseModel: String? = null
    private var finalUsage = false
    var stopReason: String? = null
        private set

    fun event(data: String) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull
        when (root.str("type")) {
            "message_start" -> root.obj("message")?.let { message ->
                responseModel = message.str("model")
                message.obj("usage")?.let { usage = it }
            }
            "content_block_start" -> {
                val index = root["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val block = root["content_block"] as? JsonObject ?: return
                blocks[index] = block.toMutableMap()
                val kind = block.str("type")
                when (kind) {
                    "thinking", "redacted_thinking" -> sink.status("Thinking…")
                    "server_tool_use" -> sink.status(if (block.str("name") == "web_search") "Searching the web…" else "Working…")
                    "tool_use" -> sink.status(if (block.str("name") == ClaudeApi.IMAGE_TOOL) "Drawing…" else "Working…")
                    "text" -> {
                        sink.status(null)
                        // Text after a search or a tool is a new paragraph; text split by citations is one.
                        if (wrote && lastKind != "text") sink.text("\n\n")
                        wrote = true
                    }
                }
                // Thinking between paragraphs doesn't split them.
                if (kind != "thinking" && kind != "redacted_thinking") lastKind = kind
            }
            "content_block_delta" -> {
                val index = root["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val block = blocks[index] ?: return
                val delta = root["delta"] as? JsonObject ?: return
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")?.let {
                        block["text"] = JsonPrimitive((block["text"]?.jsonPrimitive?.contentOrNull ?: "") + it)
                        sink.text(it)
                    }
                    "thinking_delta" -> delta.str("thinking")?.let {
                        block["thinking"] = JsonPrimitive((block["thinking"]?.jsonPrimitive?.contentOrNull ?: "") + it)
                    }
                    "signature_delta" -> delta.str("signature")?.let { block["signature"] = JsonPrimitive(it) }
                    "input_json_delta" -> delta.str("partial_json")?.let { partialInput.getOrPut(index) { StringBuilder() }.append(it) }
                    "citations_delta" -> (delta["citation"] as? JsonObject)?.let { citation ->
                        citations.getOrPut(index) { mutableListOf() } += citation
                        val url = citation.str("url")
                        if (url != null) sink.source(citation.str("title") ?: url, url)
                    }
                }
            }
            "content_block_stop" -> {
                val index = root["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
                val block = blocks[index] ?: return
                partialInput.remove(index)?.toString()?.takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { block["input"] = it }
                }
                citations.remove(index)?.let { block["citations"] = JsonArray(it) }
            }
            "message_delta" -> {
                val reason = root.obj("delta")?.str("stop_reason")
                if (reason != null) stopReason = reason
                root.obj("usage")?.let {
                    usage = JsonObject(usage + it)
                    if (reason != null && it["output_tokens"] is JsonPrimitive) finalUsage = true
                }
            }
            "error" -> {
                val error = root["error"] as? JsonObject
                val message = error?.str("message")
                throw ApiException(
                    when (error?.str("type")) {
                        "overloaded_error" -> "Claude is busy right now. Try again shortly."
                        "rate_limit_error" -> "Too many requests right now. Try again in a minute."
                        else -> message ?: "Claude stopped with an error."
                    },
                )
            }
        }
    }

    fun cost(requested: Model): ChatCost {
        val actual = responseModel?.let { id -> Model.entries.firstOrNull { id == it.id || id.startsWith(it.id + "-") } }
        return ApiPricing.claude(actual ?: requested, usage, partial = !finalUsage || (responseModel != null && actual == null))
    }

    /** The reply's content blocks, in order, as the API would return them. */
    fun content(): JsonArray = JsonArray(blocks.values.map { JsonObject(it) })

    /** Tool calls Ask has to run itself (the search runs on Anthropic's side). */
    fun toolUses(): List<JsonObject> =
        blocks.values.map { JsonObject(it) }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
}
