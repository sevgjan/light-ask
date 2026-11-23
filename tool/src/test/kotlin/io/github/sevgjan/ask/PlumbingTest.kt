package io.github.sevgjan.ask

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlumbingTest {
    @Test
    fun serverSentEvents() {
        val sse = Sse()
        val events = listOf(": keep-alive", "event: ping", "data: {\"a\":1}", "", "data: line one", "data: line two", "", "data: tail")
            .mapNotNull { sse.line(it) } + listOfNotNull(sse.end())
        assertEquals(listOf(Sse.Event("ping", "{\"a\":1}"), Sse.Event(null, "line one\nline two"), Sse.Event(null, "tail")), events)
        assertNull(Sse().end())
    }

    @Test
    fun markdownBecomesPlainText() {
        val text = "# Title\n\n**Bold** and *soft* and `code`\n- one\n* two\n---\n```\nx = 1\n```\nSee [the site](https://example.com)."
        assertEquals("Title\n\nBold and soft and code\n• one\n• two\nx = 1\nSee the site.", Markdown.plain(text))
        assertEquals("2 * 3 = 6", Markdown.plain("2 * 3 = 6"))
    }

    @Test
    fun titles() {
        assertEquals("Why is the sky blue?", Chats.titleFor("  Why is the sky blue?\nAnd more"))
        assertEquals("Photo", Chats.titleFor(""))
        assertTrue(Chats.titleFor("x".repeat(80)).endsWith("…"))
    }

    @Test
    fun usbSetupFindsTheToolsFormsAndOnlyAcceptsThose() {
        val manifest = ToolEntryPoint.getToolManagerManifest().encode()
        assertEquals(setOf("setup"), UsbSetup.formPaths(Json.parseToJsonElement(manifest)))
        val answers = UsbSetup.answers(
            """{"forms":{"setup":{"responses":[{"item":{"id":"openai_api_key","label":"OpenAI API key","hidden":true},"response":"sk-x"}]},"../evil":{"responses":[]}}}""",
            setOf("setup"),
        )
        assertEquals(setOf("setup"), answers.keys)
        // What comes back is what the Tool Manager would have written, so Setup reads it as usual.
        assertEquals(mapOf("openai_api_key" to "sk-x"), Setup.parse(answers.getValue("setup")))
        assertEquals(emptyMap(), UsbSetup.answers("{}", setOf("setup")))
    }

    @Test
    fun picturesBudgetCountsFromTheNewest() {
        val p = Picture("image/jpeg") { null }
        val history = listOf(Turn(Role.USER, "a", images = List(4) { p }), Turn(Role.ASSISTANT, "b"), Turn(Role.USER, "c", images = List(4) { p }))
        val keep = pictureBudget(history)
        assertEquals(listOf(false, false, true, true), (0..3).map { keep(0, it) })
        assertEquals(listOf(true, true, true, true), (0..3).map { keep(2, it) })
    }
}
