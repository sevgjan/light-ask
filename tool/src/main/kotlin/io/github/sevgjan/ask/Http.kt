package io.github.sevgjan.ask

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.JsonObject

/** A response that wasn't a success: its status and body, for the provider to turn into words. */
class HttpError(val status: Int, val body: String) : Exception("HTTP $status")

object Http {
    /**
     * One client for every request, so connections are reused. Replies can take minutes (searching, drawing),
     * so the whole request may run long; a gap of three minutes with no bytes at all means it's stuck.
     */
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 15 * 60_000
                socketTimeoutMillis = 180_000
            }
            expectSuccess = false
        }
    }

    /** POSTs [body] and returns the response text, or throws [HttpError]. */
    suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): String {
        val response = client.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw HttpError(response.status.value, text)
        return text
    }

    /** POSTs [body] and hands each server-sent event to [onEvent] as it arrives, or throws [HttpError]. */
    suspend fun streamEvents(url: String, headers: Map<String, String>, body: JsonObject, onEvent: suspend (Sse.Event) -> Unit) {
        client.preparePost(url) {
            headers.forEach { (name, value) -> header(name, value) }
            header("Accept", "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (response.status.value !in 200..299) throw HttpError(response.status.value, response.bodyAsText())
            val channel = response.bodyAsChannel()
            val parser = Sse()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                parser.line(line)?.let { onEvent(it) }
            }
            parser.end()?.let { onEvent(it) }
        }
    }
}

/** Server-sent events, a line at a time: `event:` names it, `data:` lines carry it, a blank line ends it. */
class Sse {
    data class Event(val name: String?, val data: String)

    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line; returns the event it completes, if any. */
    fun line(line: String): Event? {
        when {
            line.isEmpty() -> return end()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> name = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
                hasData = true
            }
        }
        return null
    }

    /** The event still being read when the stream ends. */
    fun end(): Event? {
        val event = if (hasData) Event(name, data.toString()) else null
        name = null
        data.setLength(0)
        hasData = false
        return event
    }
}
