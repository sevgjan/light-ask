package io.github.sevgjan.ask

/** Who answers. Each provider has its own key and its own models. */
enum class Provider(val label: String, val keyLabel: String, val console: String) {
    ANTHROPIC("Anthropic", "Anthropic API key", "console.anthropic.com"),
    OPENAI("OpenAI", "OpenAI API key", "platform.openai.com"),
    ;

    val models: List<Model> get() = Model.entries.filter { it.provider == this }
    val api: ChatApi get() = if (this == ANTHROPIC) ClaudeApi else OpenAiApi
}

enum class Model(val provider: Provider, val id: String, val label: String, val serverFallback: Boolean = false) {
    OPUS(Provider.ANTHROPIC, "claude-opus-5-5", "Claude Opus 5.5", serverFallback = true),
    SONNET(Provider.ANTHROPIC, "claude-sonnet-5-5", "Claude Sonnet 5.5", serverFallback = true),
    HAIKU(Provider.ANTHROPIC, "claude-haiku-5-5", "Claude Haiku 5.5"),
    ASTRA(Provider.OPENAI, "gpt-6.2-astra", "GPT-6.2 Astra"),
    SOL(Provider.OPENAI, "gpt-6.2-sol", "GPT-6.2 Sol"),
    TERRA(Provider.OPENAI, "gpt-6.1-terra", "GPT-6.1 Terra"),
    LUNA(Provider.OPENAI, "gpt-6-luna", "GPT-6 Luna"),
    ;

    /** "Opus 5.5", "GPT-6.2 Astra": short enough for a chat's subtitle. */
    val short: String get() = label.removePrefix("Claude ")

    companion object {
        fun of(name: String?): Model? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How pictures are drawn: GPT draws through the Responses API's image tool (which takes the 2.5 models), and
 * Claude through the Images API (which also has 2.6), with an OpenAI key either way.
 */
enum class ImageModel(val label: String, val responsesId: String, val imagesId: String) {
    FLARE("Flare (faster)", "gpt-image-2.5-flare", "gpt-image-2.6-flare"),
    SUNBURST("Sunburst (more detail)", "gpt-image-2.5-sunburst", "gpt-image-2.6-sunburst"),
}

/** How hard the model thinks before answering. */
enum class Depth(val label: String, val effort: String) {
    QUICK("Quick", "low"),
    DEEP("Deep", "high"),
}

enum class Role { USER, ASSISTANT }

/** A picture in the conversation: its bytes are only read when a request is built. */
class Picture(val mediaType: String, val load: () -> ByteArray?)

/** One message as the providers see it: what was said, the photos the user attached, the pictures that were made. */
data class Turn(
    val role: Role,
    val text: String,
    val images: List<Picture> = emptyList(),
    val made: List<Picture> = emptyList(),
)

/** Everything about a request apart from the conversation. */
data class Options(
    val model: Model,
    val depth: Depth = Depth.QUICK,
    val webSearch: Boolean = true,
    val imageModel: ImageModel = ImageModel.FLARE,
    /** For pictures: GPT draws with its own key, Claude borrows the OpenAI key when there is one. */
    val openAiKey: String? = null,
    /** "Monday, 23 November 2026", so answers know what today is. */
    val today: String = "",
)

/** Where a streamed reply goes while it's being written. */
interface Sink {
    /** Cost reported or estimated for a single completed provider request. */
    fun cost(value: ChatCost) {}

    /** More of the answer's text. */
    fun text(delta: String)
    /** What's happening besides writing ("Searching the web…"), or null when it's writing. */
    fun status(text: String?)
    /** A rough early look at a picture being drawn. */
    fun preview(bytes: ByteArray) {}
    /** A finished picture. */
    suspend fun image(bytes: ByteArray, prompt: String?)
    /** A web page the answer used. */
    fun source(title: String, url: String) {}
}

data class Finish(val truncated: Boolean = false, val refused: Boolean = false)

class ApiException(message: String) : Exception(message)

interface ChatApi {
    /** Streams the reply to the last user turn of [history] into [sink]. Throws [ApiException] with a message for the screen. */
    suspend fun stream(apiKey: String, options: Options, history: List<Turn>, sink: Sink): Finish
}

/** How Ask should answer on the phone, the same for both providers. */
fun systemPrompt(options: Options, pictures: Boolean): String = buildString {
    appendLine("You are Ask, an assistant on a Light Phone III: a minimal phone with a small black-and-white screen and no web browser.")
    appendLine("Write plain text, without Markdown: no headings, tables, bold, italics or code fences. Use short paragraphs. For steps or a list, put each item on its own line starting with \"1.\", \"2.\" and so on.")
    appendLine("Be concise. A few sentences answer most questions; go longer only when the question needs it or the user asks for detail.")
    appendLine("The user can attach photos taken with the phone. When they do, look at them closely.")
    if (pictures) appendLine("When the user asks for a picture, drawing, illustration or image, make one with your image tool, then say at most a sentence about it.")
    appendLine("If you are not sure, say so.")
    if (options.today.isNotEmpty()) append("Today is ${options.today}.")
}.trim()

/** How many photos go along with a request, newest first; older ones are mentioned in words only. */
const val MAX_PICTURES = 6

/** Which of the user's photos still go along as pictures: the newest [MAX_PICTURES]. Called as (turn index, photo index). */
fun pictureBudget(history: List<Turn>): (Int, Int) -> Boolean {
    val keep = HashSet<Pair<Int, Int>>()
    outer@ for (t in history.indices.reversed()) {
        val turn = history[t]
        if (turn.role != Role.USER) continue
        for (i in turn.images.indices.reversed()) {
            if (keep.size >= MAX_PICTURES) break@outer
            keep += t to i
        }
    }
    return { t, i -> (t to i) in keep }
}
