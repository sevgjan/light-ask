package io.github.sevgjan.ask

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeApiTest {
    @Test
    fun requestBodyForOpus() {
        val body = ClaudeApi.requestBody(ClaudeModel.OPUS, listOf(ClaudeApi.userMessage("Why is the sky blue?")))
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        // No sampling parameters or thinking config: both are rejected or unnecessary on current models.
        assertFalse("temperature" in body || "thinking" in body)
        assertEquals("user", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun haikuHasNoServerFallback() {
        val body = ClaudeApi.requestBody(ClaudeModel.HAIKU, listOf(ClaudeApi.userMessage("hi")))
        assertFalse("fallbacks" in body)
    }

    @Test
    fun parsesTextAndKeepsThinkingBlocksForFollowUp() {
        val reply = ClaudeApi.parseReply(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"Rayleigh scattering."}],
             "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}
            """,
        )
        assertIs<Reply.Answer>(reply)
        assertEquals("Rayleigh scattering.", reply.text)
        assertEquals(2, reply.assistantContent.size)
        assertFalse(reply.truncated)
    }

    @Test
    fun refusalIsCheckedBeforeContent() {
        val reply = ClaudeApi.parseReply("""{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":null}}""")
        assertIs<Reply.Refused>(reply)
        assertNull(reply.category)
    }

    @Test
    fun truncatedAnswer() {
        val reply = ClaudeApi.parseReply("""{"content":[{"type":"text","text":"Part"}],"stop_reason":"max_tokens"}""")
        assertTrue((reply as Reply.Answer).truncated)
    }

    @Test
    fun errorMessages() {
        assertEquals(
            "The API key was rejected. Set a new one in settings.",
            ClaudeApi.errorMessage(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        assertEquals("bad thing", ClaudeApi.errorMessage(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad thing"}}"""))
    }

    @Test
    fun dailyLimitResetsEachDay() {
        assertEquals(2, DailyLimit.remaining(limit = 5, usedOn = 100, used = 3, today = 100))
        assertEquals(5, DailyLimit.remaining(limit = 5, usedOn = 99, used = 5, today = 100))
        assertEquals(0, DailyLimit.remaining(limit = 3, usedOn = 100, used = 4, today = 100))
    }
}
