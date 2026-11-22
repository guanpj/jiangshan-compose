package com.jiangshan.atlas

import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseTest {
    private val zhanguo = Dynasty(
        key = "zhanguo",
        title = "战国",
        from = -403,
        to = -221,
        focus = -403,
        tint = 0,
        untils = intArrayOf(-340, -316),
        snaps = listOf("a", "b"),
        milestones = intArrayOf(),
        events = emptyList(),
    )

    @Test
    fun captionRangeIsNormalized() {
        assertEquals(
            "前 403 — 前 341",
            phaseLabel(zhanguo, "前403—前341年 · 三家分晋", -350).period,
        )
        assertEquals(
            "三家分晋",
            phaseLabel(zhanguo, "前403—前341年 · 三家分晋", -350).caption,
        )
    }

    @Test
    fun captionRangeSupportsGongyuanPrefix() {
        assertEquals(
            "前 60 — 公元 8",
            phaseLabel(zhanguo, "前 60 — 公元 8 · 设西域都护府", -20).period,
        )
    }

    @Test
    fun captionWithoutRangeFallsBackToPhaseBoundaries() {
        val phase = phaseLabel(zhanguo, "1926 · 北伐出师", -350)
        assertEquals("前 403 — 前 341", phase.period)
        assertEquals("1926 · 北伐出师", phase.caption)
    }

    @Test
    fun bceToCeCaptionKeepsItsMeaning() {
        assertEquals(
            "公元前 403 — 公元前 341",
            phaseLabel(zhanguo, "公元前 403 年 — 公元前 341 年", -350).period,
        )
    }
}
