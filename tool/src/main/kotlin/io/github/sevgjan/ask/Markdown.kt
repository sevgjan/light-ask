package io.github.sevgjan.ask

/** Answers are asked for in plain text, but Markdown slips through now and then: this takes the marks away. */
object Markdown {
    private val heading = Regex("""^\s{0,3}#{1,6}\s+""")
    private val bullet = Regex("""^(\s*)[-*+]\s+""")
    private val bold = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val italic = Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])""")
    private val code = Regex("""`([^`\n]+)`""")
    private val link = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun plain(text: String): String = text.lines()
        .filterNot { it.trim().startsWith("```") || rule.matches(it) }
        .joinToString("\n") { line ->
            line.replace(heading, "")
                .replace(bullet, "$1• ")
                .replace(bold) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .replace(italic, "$1")
                .replace(code, "$1")
                .replace(link, "$1")
        }
        .replace(Regex("\n{3,}"), "\n\n")
}
