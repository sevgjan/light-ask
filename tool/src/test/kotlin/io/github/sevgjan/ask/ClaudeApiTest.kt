package io.github.sevgjan.ask

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records what a streamed reply did. */
class RecordingSink : Sink {
    val text = StringBuilder()
    val statuses = mutableListOf<String?>()
    val images = mutableListOf<Pair<ByteArray, String?>>()
    val previews = mutableListOf<ByteArray>()
    val sources = mutableListOf<Pair<String, String>>()
    override fun text(delta: String) { text.append(delta) }
    override fun status(text: String?) { statuses += text }
    override fun preview(bytes: ByteArray) { previews += bytes }
    override suspend fun image(bytes: ByteArray, prompt: String?) { images += bytes to prompt }
    override fun source(title: String, url: String) { sources += title to url }
}

private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

class ClaudeApiTest {
    private val photo = Picture("image/jpeg") { byteArrayOf(1, 2, 3) }

    @Test
    fun requestStreamsWithSearchPicturesAndCaching() {
        val options = Options(Model.OPUS, openAiKey = "sk-test", today = "Monday, 23 November 2026")
        val body = ClaudeApi.requestBody(options, ClaudeApi.messages(listOf(Turn(Role.USER, "Why is the sky blue?"))))
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals("true", body.str("stream"))
        assertEquals("low", body["output_config"]!!.jsonObject.str("effort"))
        assertEquals("default", body.str("fallbacks"))
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject.str("type"))
        assertTrue(body.str("system").endsWith("Today is Monday, 23 November 2026."))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals("web_search_20260209", tools[0].str("type"))
        assertEquals(ClaudeApi.IMAGE_TOOL, tools[1].str("name"))
        // Sampling parameters and thinking settings are rejected or unnecessary on current models.
        assertFalse("temperature" in body || "thinking" in body)
    }

    @Test
    fun haikuUsesTheBasicSearchAndNoFallback() {
        val body = ClaudeApi.requestBody(Options(Model.HAIKU), ClaudeApi.messages(listOf(Turn(Role.USER, "hi"))))
        assertFalse("fallbacks" in body)
        assertEquals("web_search_20250305", body["tools"]!!.jsonArray[0].jsonObject.str("type"))
        assertEquals(1, body["tools"]!!.jsonArray.size) // no OpenAI key: no pictures
    }

    @Test
    fun noToolsAtAllLeavesThemOut() {
        val body = ClaudeApi.requestBody(Options(Model.SONNET, webSearch = false), ClaudeApi.messages(listOf(Turn(Role.USER, "hi"))))
        assertFalse("tools" in body)
        assertFalse(systemPrompt(Options(Model.SONNET), pictures = false).contains("image tool"))
    }

    @Test
    fun photosGoBeforeTheWordsAndSameRoleTurnsMerge() {
        val messages = ClaudeApi.messages(
            listOf(
                Turn(Role.USER, "What is this?", images = listOf(photo)),
                Turn(Role.USER, "And this?"), // the answer between failed, so it isn't sent
                Turn(Role.ASSISTANT, "", made = listOf(photo)),
                Turn(Role.USER, "Thanks"),
            ),
        )
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.str("role") })
        val first = messages[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "text", "text"), first.map { it.str("type") })
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), first[0]["source"]!!.jsonObject.str("data"))
        // A made picture goes back as words: Claude can't see it, but knows it happened.
        assertEquals("[You made a picture here.]", messages[1]["content"]!!.jsonArray[0].jsonObject.str("text"))
    }

    @Test
    fun onlyTheNewestPhotosAreSent() {
        val history = (1..8).map { Turn(Role.USER, "photo $it", images = listOf(photo)) }
        val blocks = ClaudeApi.messages(history).single()["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(MAX_PICTURES, blocks.count { it.str("type") == "image" })
        assertTrue(blocks.first().str("text").startsWith("[A photo I sent earlier]"))
    }

    @Test
    fun streamBuildsTheReplyAndTheScreenText() = runBlocking {
        val sink = RecordingSink()
        val stream = ClaudeStream(sink)
        listOf(
            """{"type":"message_start","message":{"id":"msg_1","content":[]}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Let me look."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"weather\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"content_block_start","index":4,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":4,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com/w","title":"Weather","cited_text":"Sunny"}}}""",
            """{"type":"content_block_delta","index":4,"delta":{"type":"text_delta","text":"It's sunny."}}""",
            """{"type":"content_block_stop","index":4}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}""",
            """{"type":"message_stop"}""",
        ).forEach(stream::event)

        assertEquals("Let me look.\n\nIt's sunny.", sink.text.toString())
        assertTrue("Searching the web…" in sink.statuses)
        assertEquals(listOf("Weather" to "https://example.com/w"), sink.sources)
        assertEquals("end_turn", stream.stopReason)
        val content = stream.content().map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "server_tool_use", "web_search_tool_result", "text"), content.map { it.str("type") })
        // Sent back unchanged within a reply: the signature and the parsed search input must survive.
        assertEquals("sig", content[0].str("signature"))
        assertEquals("weather", content[2]["input"]!!.jsonObject.str("query"))
        assertEquals(1, content[4]["citations"]!!.jsonArray.size)
    }

    @Test
    fun toolCallsAreCollected() {
        val stream = ClaudeStream(RecordingSink())
        stream.event("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"generate_image","input":{}}}""")
        stream.event("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"prompt\":\"a red fox\"}"}}""")
        stream.event("""{"type":"content_block_stop","index":0}""")
        stream.event("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        val use = stream.toolUses().single()
        assertEquals("toolu_1", use.str("id"))
        assertEquals("a red fox", use["input"]!!.jsonObject.str("prompt"))
    }

    @Test
    fun costCombinesInitialAndFinalUsageAtTheResponseModelRate() {
        val stream = ClaudeStream(RecordingSink())
        stream.event("""{"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":400,"cache_creation_input_tokens":500,"cache_creation":{"ephemeral_1h_input_tokens":200},"cache_read_input_tokens":1000,"output_tokens":5}}}""")
        assertEquals(ChatCost(2_500, partial = true), stream.cost(Model.OPUS))
        stream.event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":100,"server_tool_use":{"web_search_requests":2}}}""")
        assertEquals(ChatCost(23_450), stream.cost(Model.OPUS))
    }

    @Test
    fun streamErrorsBecomeMessages() {
        val e = assertFailsWith<ApiException> {
            ClaudeStream(RecordingSink()).event("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals("Claude is busy right now. Try again shortly.", e.message)
    }

    @Test
    fun errorMessages() {
        assertEquals(
            "The Anthropic API key was rejected. Set a new one in settings.",
            ClaudeApi.errorMessage(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        assertEquals("bad thing", ClaudeApi.errorMessage(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad thing"}}"""))
    }

    @Test
    fun dailyLimitResetsEachDay() {
        assertEquals(2, DailyLimit.remaining(limit = 5, usedOn = 100, used = 3, today = 100))
        assertEquals(5, DailyLimit.remaining(limit = 5, usedOn = 99, used = 5, today = 100))
        assertEquals(0, DailyLimit.remaining(limit = 3, usedOn = 100, used = 4, today = 100))
        assertEquals(Int.MAX_VALUE, DailyLimit.remaining(limit = DailyLimit.UNLIMITED, usedOn = 100, used = 50, today = 100))
        assertEquals("∞", DailyLimit.label(DailyLimit.UNLIMITED))
    }
}
