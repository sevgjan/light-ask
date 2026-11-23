package io.github.sevgjan.ask

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/** Millionths of a US dollar, using standard API rates. */
data class ChatCost(val micros: Long = 0, val partial: Boolean = false) {
    operator fun plus(other: ChatCost) = ChatCost(micros + other.micros, partial || other.partial)

    fun amount(): String = when {
        partial && micros == 0L -> "NA"
        else -> "~\$" + String.format(java.util.Locale.US, "%.3f", micros / 1_000_000.0)
    }
}

internal object ApiPricing {
    // USD per million tokens. https://developers.openai.com/api/docs/pricing
    fun openAi(model: Model, usage: JsonObject?, searches: Int, unpricedImage: Boolean): ChatCost {
        val input = usage?.count("input_tokens")
        val output = usage?.count("output_tokens")
        val searchCost = searches * 10_000L
        if (input == null || output == null) return ChatCost(searchCost, partial = true)
        val details = usage.obj("input_tokens_details")
        val cached = (details?.count("cached_tokens") ?: 0).coerceAtMost(input)
        val cacheWrite = (details?.count("cache_write_tokens") ?: 0).coerceAtMost(input - cached)
        val rates = rates(model)
        val longContext = input > 272_000
        val inputCost = (input - cached - cacheWrite) * rates.input + cached * rates.cached + cacheWrite * rates.input * 1.25
        val tokenCost = inputCost * (if (longContext) 2.0 else 1.0) + output * rates.output * (if (longContext) 1.5 else 1.0)
        return ChatCost(tokenCost.roundToLong() + searchCost, partial = unpricedImage)
    }

    // https://platform.claude.com/docs/en/about-claude/pricing
    fun claude(model: Model, usage: JsonObject, partial: Boolean): ChatCost {
        val input = usage.count("input_tokens") ?: 0
        val output = usage.count("output_tokens") ?: 0
        val cached = usage.count("cache_read_input_tokens") ?: 0
        val cacheWrite = usage.count("cache_creation_input_tokens") ?: 0
        val hourly = (usage.obj("cache_creation")?.count("ephemeral_1h_input_tokens") ?: 0).coerceAtMost(cacheWrite)
        val searches = usage.obj("server_tool_use")?.count("web_search_requests") ?: 0
        val rates = rates(model)
        val multiplier = if (model == Model.HAIKU && input + cached + cacheWrite > 100_000) 5 else 1
        val tokenCost = input * rates.input + output * rates.output + cached * rates.cached +
            ((cacheWrite - hourly) * 1.25 + hourly * 2.0) * rates.input
        return ChatCost((tokenCost * multiplier).roundToLong() + searches * 10_000, partial)
    }

    fun image(usage: JsonObject?): ChatCost {
        val output = usage?.count("output_tokens") ?: return ChatCost(partial = true)
        val input = usage.obj("input_tokens_details") ?: return ChatCost(output * 30, partial = true)
        val text = input.count("text_tokens") ?: 0
        val image = input.count("image_tokens") ?: 0
        return ChatCost(text * 5 + image * 8 + output * 30)
    }

    private data class Rates(val input: Double, val cached: Double, val output: Double)

    private fun rates(model: Model) = when (model) {
        Model.OPUS -> Rates(4.0, 0.20, 20.0)
        Model.SONNET -> Rates(2.0, 0.10, 10.0)
        Model.HAIKU -> Rates(0.10, 0.01, 0.50)
        Model.ASTRA -> Rates(8.0, 0.40, 40.0)
        Model.SOL -> Rates(2.0, 0.10, 10.0)
        Model.TERRA -> Rates(1.0, 0.05, 5.0)
        Model.LUNA -> Rates(0.10, 0.01, 0.50)
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
