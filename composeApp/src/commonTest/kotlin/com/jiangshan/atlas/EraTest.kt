package com.jiangshan.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EraTest {
    private val tang = dynasty("tang", "唐", 618, 907)
    private val beisong = dynasty("beisong", "北宋", 960, 1127)
    private val xihan = dynasty("xihan", "西汉", -202, 25)
    private val prc = dynasty("prc", "中华人民共和国", 1949, 2027)

    private val spans = listOf(
        EraSpan(-1, 1, "西汉哀帝", "元寿", "xihan"),
        EraSpan(1, 1, "西汉平帝", "元始", "xihan"),
        EraSpan(627, 1, "唐太宗", "贞观", "tang"),
        EraSpan(960, 1, "北宋太祖", "建隆", "beisong"),
    )

    @Test
    fun eraUsesTheDynastyWhoseKeyMatches() {
        val data = atlas(spans)
        assertEquals("唐太宗 · 贞观四年", data.eraFor(630, tang)?.inline)
        assertEquals("北宋太祖 · 建隆元年", data.eraFor(960, beisong)?.inline)
        assertNull(data.eraFor(630, beisong), "a Tang year must not pick a Song era")
    }

    @Test
    fun eraNumberingCrossesBceToCeWithoutYearZero() {
        val data = atlas(spans)
        assertEquals("西汉哀帝 · 元寿元年", data.eraFor(-1, xihan)?.inline)
        assertEquals("西汉平帝 · 元始元年", data.eraFor(1, xihan)?.inline)
        assertNull(data.eraFor(-2, xihan), "before the first era span")
    }

    @Test
    fun eraIsAbsentWhenADynastyHasNoSpans() {
        assertNull(atlas(spans).eraFor(1950, prc))
    }

    private fun atlas(eraSpans: List<EraSpan>) = AtlasData(
        dynasties = listOf(tang, beisong, xihan, prc),
        snapshots = emptyMap(),
        features = emptyList(),
        rings = emptyArray(),
        cities = emptyMap(),
        relief = null,
        terrainLevels = emptyMap(),
        terrainTileSize = 512,
        hydroRivers = emptyList(),
        hydroLakes = emptyList(),
        hydroLabels = emptyList(),
        eraSpans = eraSpans,
    )

    private fun dynasty(key: String, title: String, from: Int, to: Int) = Dynasty(
        key = key,
        title = title,
        from = from,
        to = to,
        focus = from,
        tint = 0,
        untils = intArrayOf(),
        snaps = emptyList(),
        milestones = intArrayOf(),
        events = emptyList(),
    )
}
