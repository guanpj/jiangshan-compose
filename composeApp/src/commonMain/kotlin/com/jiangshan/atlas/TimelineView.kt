package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Continuous coordinates: -1 is 1 BCE and 0 is 1 CE.
internal fun yearIndex(year: Int): Int = if (year > 0) year - 1 else year

private fun historicalYear(index: Int): Int = if (index >= 0) index + 1 else index

internal class TimelineBand(
    val index: Int,
    val from: Float,
    val to: Float,
    val y0: Float,
    val h: Float,
)

internal class TimelineLayout(
    val bands: List<TimelineBand>,
    val topPad: Float,
) {
    val contentH: Float = (bands.lastOrNull()?.let { it.y0 + it.h } ?: topPad)

    fun yearAt(y: Float): Int {
        for (b in bands) {
            if (y < b.y0 + b.h) {
                val local = ((y - b.y0) / b.h).coerceIn(0f, 1f)
                return historicalYear((b.from + local * (b.to - b.from)).roundToInt())
            }
        }
        return historicalYear(bands.last().to.roundToInt())
    }

    fun yOf(year: Int): Float {
        val target = yearIndex(year).toFloat()
        for (b in bands) {
            if (target <= b.to) {
                val local = if (b.to > b.from) {
                    ((target - b.from) / (b.to - b.from)).coerceIn(0f, 1f)
                } else 0f
                return b.y0 + local * b.h
            }
        }
        return contentH
    }
}

internal fun buildTimelineLayout(
    dynasties: List<Dynasty>,
    pxPerYear: Float,
    topPad: Float,
    minBandPx: Float,
): TimelineLayout {
    var y = topPad
    val bands = ArrayList<TimelineBand>(dynasties.size)
    dynasties.forEachIndexed { i, d ->
        val from = yearIndex(d.from).toFloat()
        val to = yearIndex(dynasties.getOrNull(i + 1)?.from ?: d.to).toFloat()
        val h = max((to - from) * pxPerYear, minBandPx)
        bands += TimelineBand(i, from, to, y, h)
        y += h
    }
    return TimelineLayout(bands, topPad)
}

internal class TimelineState {
    var zoom by mutableFloatStateOf(1f)
    var cursorFrac by mutableFloatStateOf(0f)
        private set
    var scrollOffset by mutableFloatStateOf(0f)
    var lastYear: Int = Int.MIN_VALUE
    var lastCardH: Float = 0f
    var handlingGesture: Boolean = false
    var debugApplied: Boolean = false

    fun cursorY(top: Float, bottom: Float): Float = top + cursorFrac * (bottom - top)

    fun moveCursor(y: Float, top: Float, bottom: Float) {
        cursorFrac = if (bottom > top) ((y - top) / (bottom - top)).coerceIn(0f, 1f) else 0f
    }

    fun placeYear(layout: TimelineLayout, year: Int, preferredY: Float, top: Float, bottom: Float) {
        val contentY = layout.yOf(year)
        scrollOffset = (contentY - preferredY.coerceIn(top, bottom))
            .coerceIn(0f, (layout.contentH - bottom).coerceAtLeast(0f))
        moveCursor(contentY - scrollOffset, top, bottom)
    }
}

/**
 * Scrollable dynasty ruler: the list scrolls under a fixed cursor (near the top),
 * the year under the cursor drives the map and the event list.
 */
@Composable
internal fun Timeline(
    data: AtlasData,
    year: Int,
    debugSpan: Int = 0,
    modifier: Modifier = Modifier,
    state: TimelineState,
    onYear: (Int) -> Unit,
) {
    val totalSpan = remember(data) {
        (yearIndex(data.dynasties.last().to) - yearIndex(data.dynasties.first().from))
            .toFloat().coerceAtLeast(1f)
    }
    BoxWithConstraints(modifier.fillMaxHeight().width(54.dp)) {
        val density = LocalDensity.current
        val cardH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val topPad = with(density) { 14.dp.toPx() }
        val endPad = with(density) { 12.dp.toPx() }
        val minBandPx = 1f
        val fitPx = ((cardH - topPad - endPad) / totalSpan).coerceAtLeast(0.01f)
        if (!state.debugApplied && cardH > 100f) {
            state.debugApplied = true
            if (debugSpan > 0) {
                state.zoom = ((cardH / debugSpan) / fitPx).coerceIn(1f, 24f)
            }
        }
        val layoutState = remember(data, state, fitPx, topPad) {
            derivedStateOf { buildTimelineLayout(data.dynasties, fitPx * state.zoom, topPad, minBandPx) }
        }
        val minCursor = topPad.coerceAtMost(cardH)
        val maxCursor = (cardH - endPad).coerceAtLeast(minCursor)
        fun scrollMax() = (layoutState.value.contentH - maxCursor).coerceAtLeast(0f)
        val scroll = rememberScrollableState { delta ->
            if (state.handlingGesture) {
                0f
            } else {
                val previous = state.scrollOffset
                val next = (previous + delta).coerceIn(0f, scrollMax())
                state.scrollOffset = next
                next - previous
            }
        }
        val scope = rememberCoroutineScope()
        val onYearNow = rememberUpdatedState(onYear)

        fun yearUnderCursor(): Int {
            return layoutState.value.yearAt(state.scrollOffset + state.cursorY(minCursor, maxCursor))
        }

        fun publishYear(value: Int, reselect: Boolean = false) {
            if (state.lastYear != value || reselect) {
                state.lastYear = value
                onYearNow.value(value)
            }
        }

        // Place the cursor for an event jump or resize without feeding the year back to the app.
        LaunchedEffect(state, year, cardH) {
            val resized = state.lastCardH != cardH
            if (resized || state.lastYear != year) {
                if (scroll.isScrollInProgress) scroll.stopScroll(MutatePriority.UserInput)
                val currentLayout = layoutState.value
                val preferredY = if (resized && state.lastCardH != 0f) {
                    state.cursorY(minCursor, maxCursor)
                } else {
                    currentLayout.yOf(year) - state.scrollOffset
                }
                state.placeYear(currentLayout, year, preferredY, minCursor, maxCursor)
                state.lastYear = year
                state.lastCardH = cardH
            }
        }

        // Throttle changes during native scrolling, including a fling, and publish the final year.
        LaunchedEffect(state, cardH) {
            var mark = TimeSource.Monotonic.markNow()
            snapshotFlow {
                state.scrollOffset to scroll.isScrollInProgress
            }.collect { (_, moving) ->
                if (state.handlingGesture || state.lastCardH != cardH) {
                    return@collect
                }
                if (!moving || mark.elapsedNow() >= 80.milliseconds) {
                    mark = TimeSource.Monotonic.markNow()
                    publishYear(yearUnderCursor())
                }
            }
        }

        val shape = RoundedCornerShape(10.dp)
        val measurer = rememberTextMeasurer()
        val labelStyle = remember { TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Serif) }

        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xF2F4EFE5))
                .pointerInput(state, layoutState, cardH) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val draggingCursor = abs(down.position.y - state.cursorY(minCursor, maxCursor)) <= 22.dp.toPx()
                        var pinchDistance = 0f
                        var pinchZoom = state.zoom
                        var anchorYear = 0
                        var anchorY = 0f
                        var lastEmitMark = TimeSource.Monotonic.markNow()
                        var moved = 0f

                        fun takeOverScroll() {
                            if (!state.handlingGesture) {
                                state.handlingGesture = true
                                scope.launch { scroll.stopScroll(MutatePriority.PreventUserInput) }
                            }
                        }

                        try {
                            if (draggingCursor) {
                                takeOverScroll()
                                down.consume()
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                val first = pressed.first()
                                if (pressed.size >= 2) {
                                    val distance = abs(first.position.y - pressed[1].position.y).coerceAtLeast(1f)
                                    if (pinchDistance == 0f) {
                                        takeOverScroll()
                                        pinchDistance = distance
                                        pinchZoom = state.zoom
                                        anchorYear = yearUnderCursor()
                                        anchorY = state.cursorY(minCursor, maxCursor)
                                    } else {
                                        val nextZoom = (pinchZoom * distance / pinchDistance).coerceIn(1f, 24f)
                                        if (nextZoom != state.zoom) {
                                            state.zoom = nextZoom
                                            state.placeYear(layoutState.value, anchorYear, anchorY, minCursor, maxCursor)
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (pinchDistance > 0f) {
                                    event.changes.forEach { it.consume() }
                                } else {
                                    moved = max(moved, (first.position - down.position).getDistance())
                                    if (draggingCursor) {
                                        if (first.position.y != first.previousPosition.y) {
                                            state.moveCursor(first.position.y, minCursor, maxCursor)
                                            if (lastEmitMark.elapsedNow() >= 60.milliseconds) {
                                                lastEmitMark = TimeSource.Monotonic.markNow()
                                                publishYear(yearUnderCursor())
                                            }
                                        }
                                        first.consume()
                                    }
                                }
                            }

                            if (pinchDistance > 0f) {
                                publishYear(anchorYear)
                            } else if (draggingCursor || moved <= 8.dp.toPx()) {
                                if (!draggingCursor || moved == 0f) {
                                    state.moveCursor(down.position.y, minCursor, maxCursor)
                                }
                                publishYear(yearUnderCursor(), reselect = moved <= 8.dp.toPx())
                            }
                        } finally {
                            state.handlingGesture = false
                        }
                    }
                }
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .scrollableArea(scroll, Orientation.Vertical)
                    .semantics {
                        verticalScrollAxisRange = ScrollAxisRange({ state.scrollOffset }, { scrollMax() })
                    }
            ) {
                translate(top = -state.scrollOffset) {
                    drawTimelineBands(layoutState.value, data, measurer, labelStyle)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = Color(0x1F2B2622),
                    topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                    size = Size(
                        (size.width - 1.dp.toPx()).coerceAtLeast(0f),
                        (size.height - 1.dp.toPx()).coerceAtLeast(0f)
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
                val p = state.cursorY(minCursor, maxCursor).coerceIn(0f, size.height)
                drawRoundRect(
                    color = Color(0xE6FFF8E7.toInt()),
                    topLeft = Offset(2.dp.toPx(), p - 2.6.dp.toPx()),
                    size = Size((size.width - 4.dp.toPx()).coerceAtLeast(0f), 5.2.dp.toPx()),
                    cornerRadius = CornerRadius(2.6.dp.toPx(), 2.6.dp.toPx()),
                )
                drawRoundRect(
                    color = Vermilion,
                    topLeft = Offset(2.dp.toPx(), p - 1.2.dp.toPx()),
                    size = Size((size.width - 4.dp.toPx()).coerceAtLeast(0f), 2.4.dp.toPx()),
                    cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx()),
                )
                drawCircle(Color(0xE6FFF8E7.toInt()), 5.1.dp.toPx(), Offset(7.dp.toPx(), p))
                drawCircle(Vermilion, 3.4.dp.toPx(), Offset(7.dp.toPx(), p))
            }
        }

    }
}

private fun DrawScope.drawTimelineBands(
    layout: TimelineLayout,
    data: AtlasData,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    val w = size.width
    val crossStart = 6.dp.toPx()
    val crossEnd = w - 6.dp.toPx()
    val lineH = 10.6.sp.toPx()

    layout.bands.forEach { b ->
        val d = data.dynasties[b.index]
        val tint = Color(d.tint)
        val bh = b.h

        drawRoundRect(
            color = tint.copy(alpha = 0.4f),
            topLeft = Offset(crossStart, b.y0 + 0.5.dp.toPx()),
            size = Size((crossEnd - crossStart).coerceAtLeast(0f), (bh - 1.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )

        val dotX = crossStart + 4.6.dp.toPx()
        var last = -1e5f
        val minGap = 1.2.dp.toPx()
        d.milestones.forEach { my ->
            val index = yearIndex(my)
            if (index < b.from || index > b.to) return@forEach
            val f = if (b.to > b.from) (index - b.from) / (b.to - b.from) else 0f
            val pos = b.y0 + f * bh
            if (pos - last < minGap) return@forEach
            last = pos
            drawCircle(Color(0x40000000), 1.9.dp.toPx(), Offset(dotX, pos))
            drawCircle(Color(0xE6FFF8E7.toInt()), 1.15.dp.toPx(), Offset(dotX, pos))
        }

        val chars = d.title.map { it.toString() }
        val totalLabelH = chars.size * lineH
        if (bh >= totalLabelH + 2.dp.toPx()) {
            val a = 0.4f
            val tr = (d.tint shr 16) and 0xFF
            val tg = (d.tint shr 8) and 0xFF
            val tb = d.tint and 0xFF
            val rr = 233 * (1 - a) + tr * a
            val gg = 225 * (1 - a) + tg * a
            val bb = 210 * (1 - a) + tb * a
            val lum = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
            val paint = style.copy(
                color = if (lum < 125f) Color(0xE6FBF6EC.toInt()) else Color(0xD92B2622)
            )
            val startTop = b.y0 + (bh - totalLabelH) / 2f
            chars.forEachIndexed { i, ch ->
                val text = measurer.measure(AnnotatedString(ch), paint)
                val cy = startTop + i * lineH + (lineH - text.size.height) / 2f
                drawText(text, topLeft = Offset((w - text.size.width) / 2f, cy))
            }
        }
    }
}
