package com.jiangshan.atlas

internal data class PhaseLabel(val period: String, val caption: String)

private val CaptionRange = Regex(
    """(公元前|公元|前)?\s*(\d+)\s*年?\s*[—–-]\s*(公元前|公元|前)?\s*(\d+)\s*年?"""
)

private fun formatRangeEnd(prefix: String?, number: String): String = when (prefix) {
    "公元前" -> "公元前 $number"
    "前" -> "前 $number"
    "公元" -> "公元 $number"
    null, "" -> number
    else -> "$prefix $number"
}

/**
 * Captions are prose, not a stable data format. Prefer the range printed in the
 * caption (it may say 公元 or 公元前), then fall back to the dynasty's phase
 * boundaries when the caption only mentions a single year.
 */
internal fun phaseLabel(dynasty: Dynasty, caption: String, year: Int): PhaseLabel {
    val range = CaptionRange.find(caption)
    if (range != null) {
        val values = range.groupValues
        val period = formatRangeEnd(values[1], values[2]) + " — " +
            formatRangeEnd(values[3], values[4])
        val before = caption.substring(0, range.range.first).trimEnd(' ', '（')
        val after = caption.substring(range.range.last + 1).trim(' ', '·', '）')
        return PhaseLabel(period, listOf(before, after).filter { it.isNotBlank() }.joinToString(" · "))
    }
    val index = dynasty.untils.indexOfFirst { year < it }
        .let { if (it < 0) dynasty.untils.lastIndex else it }
    val start = dynasty.untils.getOrNull(index - 1) ?: dynasty.from
    val end = if (index == dynasty.untils.lastIndex) dynasty.to
    else timelineYear(yearIndex(dynasty.untils[index]) - 1)
    val period = if (start == end) axisYear(start) else "${axisYear(start)} — ${axisYear(end)}"
    return PhaseLabel(period, caption)
}
