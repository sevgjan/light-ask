package io.github.sevgjan.ask

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LiveDictationTest {
    @Test
    fun sessionIsLiveTranscriptionAt24kHzWithoutServerTurnDetection() {
        val input = LiveDictation.sessionUpdate()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals("gpt-live-transcribe", input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, input["turn_detection"])
    }

    @Test
    fun appendIsLittleEndianPcm16() {
        val event = LiveDictation.append(shortArrayOf(1, -2, 0x1234))
        assertEquals("input_audio_buffer.append", event["type"]!!.jsonPrimitive.content)
        val bytes = Base64.getDecoder().decode(event["audio"]!!.jsonPrimitive.content)
        assertContentEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), bytes)
    }

    @Test
    fun parsesServerEvents() {
        assertEquals(
            LiveDictation.Event.Delta("Hello,"),
            LiveDictation.parseEvent("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_003","content_index":0,"delta":"Hello,"}"""),
        )
        assertEquals(
            LiveDictation.Event.Completed("Hello, how are you?"),
            LiveDictation.parseEvent("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_003","content_index":0,"transcript":"Hello, how are you?"}"""),
        )
        assertEquals(LiveDictation.Event.Error("bad"), LiveDictation.parseEvent("""{"type":"error","error":{"message":"bad"}}"""))
        assertEquals(
            LiveDictation.Event.Error("The OpenAI API key was rejected. Set a new one in settings."),
            LiveDictation.parseEvent("""{"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key","message":"Incorrect API key provided: sk-x"}}"""),
        )
        // On Android the close often arrives before the error event is read.
        assertEquals("OpenAI refused to transcribe. Check the OpenAI key in settings.", LiveDictation.closedMessage("WebSocket session closed with code 3000."))
        assertEquals("Dictation stopped unexpectedly.", LiveDictation.closedMessage("WebSocket session closed with code 1011."))
        assertEquals(LiveDictation.Event.Other, LiveDictation.parseEvent("""{"type":"session.created"}"""))
        assertEquals(LiveDictation.Event.Other, LiveDictation.parseEvent("not json"))
    }
}
