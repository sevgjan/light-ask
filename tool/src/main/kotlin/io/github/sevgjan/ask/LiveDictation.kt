package io.github.sevgjan.ask

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Live speech-to-text with OpenAI's Realtime transcription: microphone PCM goes up as you speak and
 * words come back while you are still talking. The whole recording is one turn, committed on stop.
 */
object LiveDictation {
    private const val URL = "wss://api.openai.com/v1/realtime?intent=transcription"
    const val MODEL = "gpt-live-transcribe"
    /** The Realtime API takes 24 kHz mono PCM16. */
    const val SAMPLE_RATE = 24_000

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Event {
        data class Delta(val text: String) : Event
        data class Completed(val transcript: String) : Event
        data class Error(val message: String) : Event
        data object Other : Event
    }

    fun sessionUpdate(): JsonObject = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", SAMPLE_RATE)
                    })
                    put("transcription", buildJsonObject { put("model", MODEL) })
                    // This model has no server-side turn detection; the user ends the turn with DONE.
                    put("turn_detection", JsonNull)
                })
            })
        })
    }

    /** PCM16 little-endian, base64, as `input_audio_buffer.append` expects. */
    fun append(pcm: ShortArray): JsonObject {
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, sample ->
            bytes[2 * i] = sample.toInt().toByte()
            bytes[2 * i + 1] = (sample.toInt() shr 8).toByte()
        }
        return buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.getEncoder().encodeToString(bytes))
        }
    }

    val commit: JsonObject = buildJsonObject { put("type", "input_audio_buffer.commit") }

    fun parseEvent(text: String): Event {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Event.Other
        fun field(name: String) = root[name]?.jsonPrimitive?.contentOrNull
        return when (field("type")) {
            "conversation.item.input_audio_transcription.delta" -> Event.Delta(field("delta").orEmpty())
            "conversation.item.input_audio_transcription.completed" -> Event.Completed(field("transcript").orEmpty())
            "error" -> {
                val error = root["error"] as? JsonObject
                Event.Error(
                    if (error?.get("code")?.jsonPrimitive?.contentOrNull == "invalid_api_key") "The OpenAI API key was rejected. Set a new one in settings."
                    else error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Dictation failed.",
                )
            }
            else -> Event.Other
        }
    }

    /** Why OpenAI closed the connection, for the screen. 3000 is how it rejects a request (a bad key, most often). */
    fun closedMessage(message: String?): String = when {
        message == null -> "Dictation stopped unexpectedly."
        "invalid_api_key" in message || "code 3000" in message -> "OpenAI refused to transcribe. Check the OpenAI key in settings."
        else -> "Dictation stopped unexpectedly."
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun CompletableDeferred<String>.failure(): ApiException? =
        if (isCompleted) getCompletionExceptionOrNull() as? ApiException else null

    /**
     * Streams [audio] until [stop] completes, reporting the text so far through [onText].
     * Returns the final transcript, or throws [ApiException] with a message for the screen.
     */
    suspend fun transcribe(apiKey: String, audio: Flow<ShortArray>, stop: Deferred<Unit>, onText: (String) -> Unit): String {
        val result = CompletableDeferred<String>()
        // The microphone runs outside the connection: a capture that's slow to stop (or a mic that never delivers)
        // must not hold up the words, or an error, from coming back.
        val micScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            HttpClient(OkHttp) { install(WebSockets) }.use { client ->
                client.webSocket(urlString = URL, request = { header("Authorization", "Bearer $apiKey") }) {
                    send(Frame.Text(sessionUpdate().toString()))
                    // The capture flow drops buffers when its collector lags, so queue them without ever suspending.
                    val queue = Channel<ShortArray>(Channel.UNLIMITED)
                    val mic = micScope.launch { audio.collect { queue.trySend(it) } }
                    val sender = launch { for (pcm in queue) send(Frame.Text(append(pcm).toString())) }
                    val reader = launch {
                        val text = StringBuilder()
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            when (val event = parseEvent(frame.readText())) {
                                is Event.Delta -> onText(text.append(event.text).toString())
                                is Event.Completed -> result.complete(event.transcript.trim())
                                is Event.Error -> result.completeExceptionally(ApiException(event.message))
                                Event.Other -> Unit
                            }
                        }
                        if (!result.isCompleted) result.completeExceptionally(ApiException("Dictation stopped unexpectedly."))
                    }
                    try {
                        // Done speaking, or the server finished (or failed) first.
                        select {
                            stop.onAwait { }
                            result.onAwait { }
                        }
                    } finally {
                        mic.cancel()
                        queue.close()
                    }
                    sender.join()
                    if (!result.isCompleted) send(Frame.Text(commit.toString()))
                    withTimeout(10_000) { result.await() }
                    reader.cancel()
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ApiException("No text came back. Try again.")
        } catch (e: CancellationException) {
            // OpenAI closing the connection (a rejected key, say) cancels the session too, sometimes before its error
            // event is read. Only a cancellation of the caller is passed on as one.
            if (!currentCoroutineContext().isActive) throw e
            throw result.failure() ?: ApiException(closedMessage(e.message))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(
                if ("401" in e.message.orEmpty()) "The OpenAI API key was rejected. Set a new one in settings."
                else "Couldn't connect for dictation. Check your connection.",
            )
        } finally {
            micScope.cancel()
        }
        return result.await()
    }
}
