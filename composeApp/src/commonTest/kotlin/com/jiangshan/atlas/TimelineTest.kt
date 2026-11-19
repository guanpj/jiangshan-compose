package com.jiangshan.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelineTest {
    private val dynasties = listOf(
        dynasty(-403, -221),
        dynasty(-221, 25),
        dynasty(25, 316),
        dynasty(317, 618),
        dynasty(618, 907),
        dynasty(907, 2027),
    )
    private val top = 14f
    private val bottom = 438f
    private val fit = (bottom - top) / (yearIndex(2027) - yearIndex(-403))

    @Test
    fun timelineRoundTripsEveryYearWithoutYearZero() {
        for (zoom in listOf(1f, 2f, 24f)) {
            val layout = layout(zoom)
            for (year in (-403..2027).filter { it != 0 }) {
                assertEquals(year, layout.yearAt(layout.yOf(year)), "year $year at zoom $zoom")
            }
            val gap = layout.yOf(1) - layout.yOf(-1)
            assertEquals(fit * zoom, gap, 0.001f)
            for (step in 0..10) {
                val year = layout.yearAt(layout.yOf(-1) + gap * step / 10)
                assertTrue(year == -1 || year == 1)
            }
        }
    }

    @Test
    fun zoomAndPanLimitsPreserveTheYearUnderTheCursor() {
        val state = TimelineState()
        val overview = layout(1f)
        val zoomed = layout(2f)
        val year = 741
        val anchor = overview.yOf(year)
        state.placeYear(zoomed, year, anchor, top, bottom)
        assertEquals(anchor, state.cursorY(top, bottom), 0.001f)
        assertEquals(year, zoomed.yearAt(state.scrollOffset + state.cursorY(top, bottom)))

        state.placeYear(overview, year, top, top, bottom)
        assertEquals(0f, state.scrollOffset, 0.001f)
        assertEquals(year, overview.yearAt(state.scrollOffset + state.cursorY(top, bottom)))
    }

    @Test
    fun invalidInitialYearFallsBackToTheFirstDynastyFocus() {
        val data = atlas(dynasties)
        for (year in listOf(Int.MIN_VALUE, -404, 0, 2028, Int.MAX_VALUE)) {
            assertEquals(-403, data.initialYear(year))
        }
        for (year in listOf(-403, -1, 1, 1644, 2027)) {
            assertEquals(year, data.initialYear(year))
        }
    }

    @Test
    fun eventSelectionUsesTheExactYearOrLatestPreviousYear() {
        val earlier = AtlasEvent(-400, "Earlier", "")
        val later = AtlasEvent(-390, "Later", "")
        val data = atlas(listOf(dynasty(-403, -221, listOf(earlier, later))))

        assertNull(data.eventAtOrBefore(-401))
        assertSame(earlier, data.eventAtOrBefore(-391)?.event)
        assertSame(later, data.eventAtOrBefore(-390)?.event)
    }

    @Test
    fun eventSelectionCrossesDynastyBoundariesAndUsesTheNewDynastyForTies() {
        val lastMingEvent = AtlasEvent(1644, "End of Ming", "")
        val firstQingEvent = AtlasEvent(1644, "Start of Qing", "")
        val ming = dynasty(1368, 1644, listOf(lastMingEvent))
        val qingWithLaterEvent = dynasty(1644, 1912, listOf(AtlasEvent(1645, "Later", "")))
        assertSame(ming, atlas(listOf(ming, qingWithLaterEvent)).eventAtOrBefore(1644)?.dynasty)

        val qing = dynasty(1644, 1912, listOf(firstQingEvent))
        assertSame(firstQingEvent, atlas(listOf(ming, qing)).eventAtOrBefore(1644)?.event)
    }

    private fun layout(zoom: Float): TimelineLayout = buildTimelineLayout(dynasties, fit * zoom, top, 1f)

    private fun atlas(dynasties: List<Dynasty>) = AtlasData(
        dynasties, emptyMap(), emptyList(), emptyArray(), emptyMap(), null,
        emptyMap(), 512, emptyList(), emptyList(), emptyList(),
    )

    private fun dynasty(from: Int, to: Int, events: List<AtlasEvent> = emptyList()): Dynasty = Dynasty(
        key = from.toString(), title = "", from = from, to = to, focus = from, tint = 0,
        untils = intArrayOf(), snaps = emptyList(), milestones = intArrayOf(), events = events,
    )
}
