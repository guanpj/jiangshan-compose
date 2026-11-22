package com.jiangshan.atlas

/**
 * Chronology encoding used by the atlas: years are integers, positive years are
 * shifted by one so that year 0 never exists. `yearIndex(-1) == -1` and
 * `yearIndex(1) == 0`.
 */
internal fun yearIndex(year: Int): Int = if (year > 0) year - 1 else year

internal fun timelineYear(index: Int): Int = if (index >= 0) index + 1 else index

internal fun axisYear(year: Int): String = if (year < 0) "前 ${-year}" else year.toString()
