package io.github.sevgjan.ask

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

enum class ClaudeModel(val id: String, val label: String, val serverFallback: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", serverFallback = true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", serverFallback = true),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", serverFallback = false),
}

sealed interface Reply {
    /** [assistantContent] is the response's content array, kept unchanged to send back on a follow-up. */
    data class Answer(val text: String, val assistantContent: JsonArray, val truncated: Boolean) : Reply
    data class Refused(val category: String?) : Reply
}

class ClaudeException(message: String) : Exception(message)

object ClaudeApi {
    const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    private const val VERSION = "2026-10-21"
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    val SYSTEM = """
        You are answering on a Light Phone, a minimal phone with a small black-and-white screen and no web browser.
        Answer in plain text without Markdown: no headings, bullet symbols, bold, tables or code fences.
        Keep it short, usually a few sentences and at most about 150 words, unless the question truly needs more.
        If you are not sure, say so.
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    fun userMessage(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    fun assistantMessage(content: JsonArray) = buildJsonObject {
        put("role", "assistant")
        put("content", content)
    }

    /** Short answers: low effort, thinking left to the model's default, no sampling parameters. */
    fun requestBody(model: ClaudeModel, messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", model.id)
        put("max_tokens", 16000)
        put("system", SYSTEM)
        put("output_config", buildJsonObject { put("effort", "low") })
        if (model.serverFallback) put("fallbacks", "default")
        put("messages", buildJsonArray { messages.forEach { add(it) } })
    }

    fun parseReply(body: String): Reply {
        val root = json.parseToJsonElement(body).jsonObject
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stopReason == "refusal") {
            val category = (root["stop_details"] as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull
            return Reply.Refused(category)
        }
        val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = content
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("\n") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .trim()
        return Reply.Answer(text, content, truncated = stopReason == "max_tokens")
    }

    fun errorMessage(status: Int, body: String): String {
        val apiMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (status) {
            401 -> "The API key was rejected. Set a new one in settings."
            402 -> "Your Anthropic account has a billing problem."
            403 -> "This API key isn't allowed to use that model."
            404 -> "Model not available to this API key. Try another in settings."
            429 -> "Too many requests right now. Try again in a minute."
            500, 529 -> "Claude is busy or having trouble. Try again shortly."
            else -> apiMessage ?: "Request failed ($status)."
        }
    }

    suspend fun send(apiKey: String, baseUrl: String, model: ClaudeModel, messages: List<JsonObject>): Reply {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
            expectSuccess = false
        }.use { client ->
            val response = client.post("${baseUrl.trimEnd('/')}/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", VERSION)
                if (model.serverFallback) header("anthropic-beta", FALLBACK_BETA)
                contentType(ContentType.Application.Json)
                setBody(requestBody(model, messages).toString())
            }
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) throw ClaudeException(errorMessage(response.status.value, body))
            return parseReply(body)
        }
    }
}
