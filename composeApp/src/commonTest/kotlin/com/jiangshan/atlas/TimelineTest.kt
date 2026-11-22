package com.jiangshan.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelineTest {
    @Test
    fun invalidInitialYearFallsBackToTheFirstDynastyFocus() {
        val data = atlas(listOf(dynasty(-403, 2027)))
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

    @Test
    fun rangeSelectionKeepsOrClampsTheCurrentYearAndCanReset() {
        val state = HorizontalTimelineState(-403, 2027)
        assertEquals(741, state.applyRange(yearIndex(618), yearIndex(907), 741))
        assertEquals(618, state.applyRange(yearIndex(618), yearIndex(907), -200))
        assertEquals(907, state.applyRange(yearIndex(618), yearIndex(907), 1644))
        assertEquals(618, timelineYear(state.from))
        assertEquals(907, timelineYear(state.to))
        state.reset()
        assertEquals(-403, timelineYear(state.from))
        assertEquals(2027, timelineYear(state.to))
    }

    @Test
    fun selectedRangeSupportsCrossingFromBceToCeWithoutYearZero() {
        val state = HorizontalTimelineState(-403, 2027)
        assertEquals(-1, state.applyRange(yearIndex(-1), yearIndex(1), -1))
        assertEquals(1, timelineYear(state.to))
        assertEquals(1, state.to - state.from)
    }

    @Test
    fun includeKeepsARangeThatContainsTheYearAndResetsOtherwise() {
        val state = HorizontalTimelineState(-403, 2027)
        state.applyRange(yearIndex(618), yearIndex(907), 741)
        state.include(800)
        assertEquals(618, timelineYear(state.from))
        assertEquals(907, timelineYear(state.to))
        state.include(1644)
        assertEquals(-403, timelineYear(state.from))
        assertEquals(2027, timelineYear(state.to))
    }

    @Test
    fun initialSpanZoomsAroundTheStartingYear() {
        val state = HorizontalTimelineState(-403, 2027, 741, 150)
        assertTrue(yearIndex(741) in state.from..state.to)
        assertEquals(150, state.to - state.from + 1, "a 150-year window includes both endpoints")
    }

    private fun atlas(dynasties: List<Dynasty>) = AtlasData(
        dynasties, emptyMap(), emptyList(), emptyArray(), emptyMap(), null,
        emptyMap(), 512, emptyList(), emptyList(), emptyList(),
    )

    private fun dynasty(from: Int, to: Int, events: List<AtlasEvent> = emptyList()): Dynasty = Dynasty(
        key = from.toString(), title = "", from = from, to = to, focus = from, tint = 0,
        untils = intArrayOf(), snaps = emptyList(), milestones = intArrayOf(), events = events,
    )
}
