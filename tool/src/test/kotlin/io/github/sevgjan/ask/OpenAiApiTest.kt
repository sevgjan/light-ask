package io.github.sevgjan.ask

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

class OpenAiApiTest {
    private val photo = Picture("image/jpeg") { byteArrayOf(9, 9) }

    @Test
    fun requestIsStatelessStreamedAndCanSearchAndDraw() {
        val body = OpenAiApi.requestBody(Options(Model.ASTRA, depth = Depth.DEEP), OpenAiApi.input(listOf(Turn(Role.USER, "Draw a fox"))))
        assertEquals("gpt-6.2-astra", body.str("model"))
        assertEquals("false", body.str("store"))
        assertEquals("true", body.str("stream"))
        assertEquals("high", body["reasoning"]!!.jsonObject.str("effort"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("web_search", "image_generation"), tools.map { it.str("type") })
        assertEquals("gpt-image-2.5-flare", tools[1].str("model"))
        assertEquals("1", tools[1].str("partial_images"))
        assertTrue(body.str("instructions").contains("image tool"))
    }

    @Test
    fun photosAndTheLastMadePictureGoAsImages() {
        val input = OpenAiApi.input(
            listOf(
                Turn(Role.USER, "What is this?", images = listOf(photo)),
                Turn(Role.ASSISTANT, "A fox.", made = listOf(photo)),
                Turn(Role.USER, "Make it blue"),
            ),
        ).map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user", "user"), input.map { it.str("role") })
        val parts = input[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image"), parts.map { it.str("type") })
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(9, 9)), parts[1].str("image_url"))
        assertEquals("A fox.\n[I made a picture here.]", input[1].str("content"))
        // The picture it made comes back as an image, so "make it blue" can change it.
        assertEquals("input_image", input[2]["content"]!!.jsonArray[1].jsonObject.str("type"))
    }

    @Test
    fun streamShowsTextStatusPicturesAndSources() = runBlocking {
        val sink = RecordingSink()
        val stream = OpenAiStream(sink)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1)
        val b64 = Base64.getEncoder().encodeToString(png)
        listOf(
            """{"type":"response.created","response":{"id":"resp_1"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"image_generation_call","id":"ig_1","status":"in_progress"}}""",
            """{"type":"response.image_generation_call.partial_image","item_id":"ig_1","partial_image_index":0,"partial_image_b64":"$b64"}""",
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"image_generation_call","id":"ig_1","status":"completed","revised_prompt":"A red fox","result":"$b64"}}""",
            """{"type":"response.output_item.added","output_index":2,"item":{"type":"message","id":"msg_1","role":"assistant","content":[]}}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"Here's "}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"your fox."}""",
            """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://example.com","title":"Example"}}""",
            // The final response repeats the picture: it must not be added twice.
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"image_generation_call","id":"ig_1","result":"$b64"}]}}""",
        ).forEach { stream.event(it) }

        assertEquals("Here's your fox.", sink.text.toString())
        assertEquals(listOf("Thinking…", "Drawing…"), sink.statuses.filterNotNull().take(2))
        assertEquals(1, sink.previews.size)
        assertEquals(1, sink.images.size)
        assertContentEquals(png, sink.images[0].first)
        assertEquals("A red fox", sink.images[0].second)
        assertEquals(listOf("Example" to "https://example.com"), sink.sources)
    }

    @Test
    fun cutShortAndFailures() = runBlocking {
        val stream = OpenAiStream(RecordingSink())
        stream.event("""{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}""")
        assertTrue(stream.truncated)
        val e = assertFailsWith<ApiException> {
            OpenAiStream(RecordingSink()).event("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"x"}}}""")
        }
        assertEquals("OpenAI had trouble with this one. Try again shortly.", e.message)
    }

    @Test
    fun costCountsCachedTokensAndEachSearchOnce() = runBlocking {
        val stream = OpenAiStream(RecordingSink())
        val search = """{"type":"web_search_call","id":"ws_1","status":"completed"}"""
        stream.event("""{"type":"response.output_item.done","item":$search}""")
        stream.event("""{"type":"response.completed","response":{"output":[$search],"usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":600,"cache_write_tokens":100},"output_tokens":200}}}""")
        assertEquals(ChatCost(12_910), stream.cost(Model.SOL))
        stream.event("""{"type":"response.output_item.added","item":{"type":"image_generation_call","id":"ig_1"}}""")
        assertEquals(ChatCost(12_910, partial = true), stream.cost(Model.SOL))
    }

    @Test
    fun imagesApiResult() {
        val image = OpenAiImages.parse("""{"created":1,"data":[{"b64_json":"${Base64.getEncoder().encodeToString(byteArrayOf(5))}","revised_prompt":"fox"}],"usage":{"input_tokens_details":{"text_tokens":10,"image_tokens":20},"output_tokens":50}}""")!!
        assertContentEquals(byteArrayOf(5), image.bytes)
        assertEquals("fox", image.revisedPrompt)
        assertEquals(ChatCost(1_710), image.cost)
    }

    @Test
    fun errorMessages() {
        assertEquals("The OpenAI API key was rejected. Set a new one in settings.", OpenAiApi.errorMessage(401, """{"error":{"message":"Incorrect API key"}}"""))
        assertEquals(
            "Your OpenAI account is out of credit or has a billing problem.",
            OpenAiApi.errorMessage(429, """{"error":{"code":"insufficient_quota","message":"quota"}}"""),
        )
        assertEquals("OpenAI wouldn't make that one.", OpenAiApi.errorMessage(400, """{"error":{"code":"moderation_blocked","message":"no"}}"""))
    }
}
