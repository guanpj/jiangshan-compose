package com.jiangshan.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    private val tang = Dynasty(
        key = "tang",
        title = "唐",
        from = 618,
        to = 907,
        focus = 618,
        tint = 0,
        untils = intArrayOf(650, 907),
        snaps = listOf("a", "b"),
        milestones = intArrayOf(),
        events = listOf(AtlasEvent(895, "李克用入关", "晋阳", 112.55f, 37.87f)),
    )

    private val data = AtlasData(
        dynasties = listOf(tang),
        snapshots = emptyMap(),
        features = emptyList(),
        rings = emptyArray(),
        cities = mapOf(
            "tang" to listOf(
                City(
                    name = "长安",
                    modern = "西安",
                    lon = 108.94f,
                    lat = 34.34f,
                    capital = true,
                    tier = 1,
                    appear = 600,
                    vanish = null,
                ),
            ),
        ),
        relief = null,
        terrainLevels = emptyMap(),
        terrainTileSize = 512,
        hydroRivers = emptyList(),
        hydroLakes = emptyList(),
        hydroLabels = emptyList(),
        eraSpans = listOf(EraSpan(627, 1, "唐太宗", "贞观", "tang")),
    )

    @Test
    fun numericQueryProducesAJump() {
        val hits = data.searchHits("895", data.buildSearchIndex())
        assertEquals(SearchKind.Year, hits.first().kind)
        assertEquals(895, hits.first().year)
    }

    @Test
    fun placeMatchesNameAndModernName() {
        val hits = data.searchHits("长安", data.buildSearchIndex())
        assertEquals(SearchKind.Place, hits.first().kind)
        assertEquals(1, hits.first().coords.size)
        assertTrue(hits.first().title.contains("西安"))
    }

    @Test
    fun eraCanBeFoundByEraOrRulerName() {
        val byEra = data.searchHits("贞观", data.buildSearchIndex())
        assertEquals(SearchKind.Era, byEra.first().kind)
        assertEquals(627, byEra.first().year)
        val byRuler = data.searchHits("唐太宗", data.buildSearchIndex())
        assertEquals(SearchKind.Era, byRuler.first().kind)
    }
}
