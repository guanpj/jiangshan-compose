package com.jiangshan.atlas

import com.jiangshan.atlas.res.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException

data class EraSpan(
    val start: Int,
    val base: Int,
    val ruler: String,
    val era: String,
    val dynastyKey: String = "",
)

internal data class EraLabel(val ruler: String, val date: String) {
    val inline: String get() = listOf(ruler, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

// Reference chronology from DeanChensj/jiangshan-map (GPL-3.0).
// https://github.com/DeanChensj/jiangshan-map/blob/adff5be959c3ca184d78eafd618a5b6b7089b3ed/js/lore_core.js
internal suspend fun loadEraSpans(): List<EraSpan> =
    runCatching {
        Json.parseToJsonElement(
            Res.readBytes("files/atlas/era_spans.json").decodeToString()
        ).jsonArray.map { element ->
            val row = element.jsonArray
            EraSpan(
                start = row[0].jsonPrimitive.int,
                base = row[1].jsonPrimitive.int,
                ruler = row[2].jsonPrimitive.content,
                era = row[3].jsonPrimitive.content,
                dynastyKey = row.getOrNull(4)?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        // The atlas itself is still usable without era labels; never fail startup.
        println("Era spans unavailable: ${error.message}")
        emptyList()
    }

internal fun AtlasData.eraFor(year: Int, dynasty: Dynasty): EraLabel? {
    val span = eraSpans.lastOrNull {
        it.dynastyKey == dynasty.key && it.start <= year
    } ?: eraSpans.lastOrNull {
        it.dynastyKey.isEmpty() && it.start <= year
    } ?: return null
    val number = yearIndex(year) - yearIndex(span.start) + span.base
    val digits = "〇一二三四五六七八九"
    val text = when (number) {
        1 -> "元"
        in 2..9 -> digits[number].toString()
        in 10..99 -> buildString {
            if (number >= 20) append(digits[number / 10])
            append('十')
            if (number % 10 != 0) append(digits[number % 10])
        }
        else -> number.toString()
    }
    return EraLabel(span.ruler, "${span.era}${text}年")
}
