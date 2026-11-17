package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableFloatState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private class TimelineBand(
    val index: Int,
    val from: Float,
    val to: Float,
    val y0: Float,
    val h: Float,
)

private class TimelineLayout(
    val bands: List<TimelineBand>,
    val topPad: Float,
) {
    val contentH: Float = (bands.lastOrNull()?.let { it.y0 + it.h } ?: topPad)

    fun yearAt(y: Float): Int {
        if (bands.isEmpty()) return 0
        if (y <= bands.first().y0) return bands.first().from.roundToInt()
        for (b in bands) {
            if (y <= b.y0 + b.h) {
                val local = ((y - b.y0) / b.h).coerceIn(0f, 1f)
                return (b.from + local * (b.to - b.from)).roundToInt()
            }
        }
        return bands.last().to.roundToInt()
    }

    fun yOf(year: Int): Float {
        if (bands.isEmpty()) return topPad
        val y = year.toFloat()
        for (b in bands) {
            if (y >= b.from && y <= b.to) {
                val local = if (b.to > b.from) (y - b.from) / (b.to - b.from) else 0f
                return b.y0 + local * b.h
            }
        }
        return if (y < bands.first().from) bands.first().y0 else bands.last().let { it.y0 + it.h }
    }
}

private fun buildLayout(
    data: AtlasData,
    pxPerYear: Float,
    topPad: Float,
    minBandPx: Float,
): TimelineLayout {
    var y = topPad
    val bands = ArrayList<TimelineBand>(data.dynasties.size)
    data.dynasties.forEachIndexed { i, d ->
        val from = d.from.toFloat()
        val to = d.to.toFloat()
        val h = max((to - from) * pxPerYear, minBandPx)
        bands += TimelineBand(i, from, to, y, h)
        y += h
    }
    return TimelineLayout(bands, topPad)
}

class TimelineState {
    val zoom: MutableFloatState = mutableFloatStateOf(1f)
    val cursorFrac: MutableFloatState = mutableFloatStateOf(Float.NaN)
    val scroll: ScrollState = ScrollState(0)
    var lastYear: Int = Int.MIN_VALUE
    var lastCardH: Float = 0f
    var dragging: Boolean = false
    var pinching: Boolean = false
    var debugApplied: Boolean = false
}

/**
 * Scrollable dynasty ruler: the list scrolls under a fixed cursor (near the top),
 * the year under the cursor drives the map and the event list.
 */
@Composable
fun Timeline(
    data: AtlasData,
    year: Int,
    debugSpan: Int = 0,
    modifier: Modifier = Modifier,
    state: TimelineState,
    onYear: (Int) -> Unit,
) {
    val totalYears = remember(data) {
        (data.dynasties.last().to - data.dynasties.first().from).toFloat()
    }
    val totalSpan = remember(data) {
        data.dynasties.sumOf { it.to - it.from }.toFloat().coerceAtLeast(1f)
    }
    BoxWithConstraints(modifier.fillMaxHeight().width(54.dp)) {
        val density = LocalDensity.current
        val cardH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val topPad = with(density) { 14.dp.toPx() }
        val endPad = with(density) { 12.dp.toPx() }
        val minBandPx = 1f
        val fitPx = ((cardH - topPad - endPad) / totalSpan).coerceAtLeast(0.01f)
        val minPx = fitPx
        val maxPx = fitPx * 24f

        // zoom is relative to "fit all dynasties"; the card height can change
        // (event panel open/close), so the absolute px must follow it
        var zoom by state.zoom
        if (!state.debugApplied && cardH > 100f) {
            state.debugApplied = true
            if (debugSpan > 0) {
                zoom = ((cardH / debugSpan) / fitPx).coerceIn(1f, 24f)
            }
        }
        val pxPerYear = (fitPx * zoom).coerceIn(minPx, maxPx)
        val layout = remember(data, pxPerYear, cardH) {
            buildLayout(data, pxPerYear, topPad, minBandPx)
        }
        val scroll = state.scroll
        val scope = rememberCoroutineScope()
        val contentH = layout.contentH + endPad
        val scrollMax = (contentH - cardH).coerceAtLeast(0f)
        var cursorFrac by state.cursorFrac
        if (cursorFrac.isNaN()) cursorFrac = 0f
        var scrollBlock by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }
        val minCursor = topPad
        val maxCursor = (cardH - endPad).coerceAtLeast(minCursor)
        val cursorY = minCursor + cursorFrac.coerceIn(0f, 1f) * (maxCursor - minCursor)

        val layoutNow = rememberUpdatedState(layout)
        val yearNow = rememberUpdatedState(year)
        val pxNow = rememberUpdatedState(pxPerYear)
        val fitNow = rememberUpdatedState(fitPx)
        val minNow = rememberUpdatedState(minPx)
        val maxNow = rememberUpdatedState(maxPx)
        val cardHNow = rememberUpdatedState(cardH)
        fun yearUnderCursor(): Int {
            val y = topPad + state.cursorFrac.value.coerceIn(0f, 1f) *
                (cardHNow.value - endPad - topPad).coerceAtLeast(0f)
            return layoutNow.value.yearAt(scroll.value + y)
        }

        // The year is always "the year under the cursor". Scroll/cursor changes
        // are throttled while moving, then flushed once the motion stops, so the
        // map isn't recomposed on every pixel (which made the drag stutter).
        LaunchedEffect(zoom, cardH) {
            var mark = TimeSource.Monotonic.markNow()
            snapshotFlow {
                Triple(scroll.value, state.cursorFrac.value, scroll.isScrollInProgress)
            }.collect { (sv, frac, moving) ->
                if (state.dragging || state.pinching || state.lastCardH != cardH) {
                    return@collect
                }
                val now = TimeSource.Monotonic.markNow()
                if (!moving || mark.elapsedNow() >= 80.milliseconds) {
                    mark = now
                    val y = topPad + frac.coerceIn(0f, 1f) *
                        (cardH - endPad - topPad).coerceAtLeast(0f)
                    val yv = layoutNow.value.yearAt(sv + y)
                    if (yv != yearNow.value) {
                        state.lastYear = yv
                        onYear(yv)
                    }
                }
            }
        }

        // external year change (event card / milestone): move the cursor onto that year
        LaunchedEffect(year) {
            if (state.dragging || state.pinching) return@LaunchedEffect
            if (year != state.lastYear) {
                val yForYear = layoutNow.value.yOf(year)
                var targetCursor = (yForYear - scroll.value).coerceIn(minCursor, maxCursor)
                if (abs(targetCursor - (yForYear - scroll.value)) > 1f) {
                    val ts = (yForYear - targetCursor)
                        .coerceIn(0f, (layoutNow.value.contentH + endPad - cardH).coerceAtLeast(0f))
                    scroll.scrollTo(ts.toInt())
                    targetCursor = (yForYear - scroll.value).coerceIn(minCursor, maxCursor)
                }
                cursorFrac = ((targetCursor - minCursor) / (maxCursor - minCursor))
                    .coerceIn(0f, 1f)
            }
            state.lastYear = year
        }

        // when the card is resized (portrait <-> landscape), keep the same year
        // under the cursor and the same cursor fraction
        LaunchedEffect(cardH) {
            if (state.lastCardH != 0f && state.lastCardH != cardH &&
                state.lastYear != Int.MIN_VALUE
            ) {
                val y = minCursor + cursorFrac.coerceIn(0f, 1f) * (maxCursor - minCursor)
                val tgt = (layoutNow.value.yOf(state.lastYear) - y)
                    .coerceIn(0f, (layoutNow.value.contentH + endPad - cardH).coerceAtLeast(0f))
                scroll.scrollTo(tgt.toInt())
            }
            state.lastCardH = cardH
            val yv = layoutNow.value.yearAt(scroll.value + cursorY)
            if (yv != yearNow.value) {
                state.lastYear = yv
                onYear(yv)
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
                .pointerInput(data) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                        )
                        val liveCursorY = topPad + state.cursorFrac.value.coerceIn(0f, 1f) *
                            (cardHNow.value - endPad - topPad).coerceAtLeast(0f)
                        val hit = abs(down.position.y - liveCursorY) <= 22.dp.toPx()
                        var dragging = hit
                        var baseDist = 0f
                        var basePx = 0f
                        var lastEmitMark = TimeSource.Monotonic.markNow()
                        var pinchYear = 0
                        var pinchFactor = 1f
                        var tapCandidate = !hit
                        var moved = 0f
                        if (dragging) {
                            state.dragging = true
                            scrollBlock = scope.launch {
                                scroll.scroll(MutatePriority.PreventUserInput) { awaitCancellation() }
                            }
                            down.consume()
                            val y0 = down.position.y.coerceIn(minCursor, maxCursor)
                            cursorFrac = ((y0 - minCursor) / (maxCursor - minCursor))
                                .coerceIn(0f, 1f)
                            val yv = yearUnderCursor()
                            state.lastYear = yv
                            onYear(yv)
                        }
                        while (true) {
                            val event = awaitPointerEvent(
                                androidx.compose.ui.input.pointer.PointerEventPass.Initial
                            )
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                dragging = false
                                tapCandidate = false
                                val d = abs(pressed[0].position.y - pressed[1].position.y)
                                    .coerceAtLeast(1f)
                                if (baseDist == 0f) {
                                    baseDist = d
                                    basePx = pxNow.value
                                    pinchYear = layoutNow.value.yearAt(scroll.value + cursorY)
                                    state.pinching = true
                                    scrollBlock?.cancel()
                                    scrollBlock = scope.launch {
                                        scroll.scroll(MutatePriority.PreventUserInput) {
                                            awaitCancellation()
                                        }
                                    }
                                } else {
                                    pinchFactor = (d / baseDist)
                                        .coerceIn(minNow.value / basePx, maxNow.value / basePx)
                                    val np = (basePx * pinchFactor)
                                        .coerceIn(minNow.value, maxNow.value)
                                    if (abs(np - pxNow.value) > 0.001f) {
                                        zoom = (np / fitNow.value).coerceIn(1f, 24f)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } else if (dragging) {
                                val c = pressed.first()
                                val cy = c.position.y.coerceIn(minCursor, maxCursor)
                                cursorFrac = ((cy - minCursor) / (maxCursor - minCursor))
                                    .coerceIn(0f, 1f)
                                val yv = yearUnderCursor()
                                state.lastYear = yv
                                // throttle map/event updates while the finger moves;
                                // the final value is emitted on release
                                if (lastEmitMark.elapsedNow().inWholeMilliseconds >= 60) {
                                    lastEmitMark = TimeSource.Monotonic.markNow()
                                    onYear(yv)
                                }
                                c.consume()
                            } else if (pressed.size == 1) {
                                val c = pressed.first()
                                moved = max(
                                    moved,
                                    (c.position - down.position).getDistance()
                                )
                            }
                        }
                        if (dragging) {
                            state.dragging = false
                            scrollBlock?.cancel()
                            scrollBlock = null
                            val yv = yearUnderCursor()
                            state.lastYear = yv
                            onYear(yv)
                        }
                        if (baseDist > 0f) {
                            scrollBlock?.cancel()
                            scrollBlock = null
                            if (pinchYear != Int.MIN_VALUE) {
                                scope.launch {
                                    try {
                                        val nl = buildLayout(data, pxNow.value, topPad, minBandPx)
                                        val tgt = (nl.yOf(pinchYear) - cursorY).coerceIn(
                                            0f,
                                            (nl.contentH + endPad - cardHNow.value)
                                                .coerceAtLeast(0f),
                                        )
                                        scroll.scrollTo(tgt.toInt())
                                        val yv = nl.yearAt(scroll.value + cursorY)
                                        state.lastYear = yv
                                        if (yv != yearNow.value) onYear(yv)
                                    } finally {
                                        state.pinching = false
                                    }
                                }
                            } else {
                                state.pinching = false
                            }
                        }
                        if (tapCandidate && baseDist == 0f && moved <= 8.dp.toPx()) {
                            val ty = down.position.y.coerceIn(minCursor, maxCursor)
                            cursorFrac = ((ty - minCursor) / (maxCursor - minCursor))
                                .coerceIn(0f, 1f)
                            val yv = yearUnderCursor()
                            state.lastYear = yv
                            onYear(yv)
                        }
                        scrollBlock?.cancel()
                        scrollBlock = null
                    }
                }
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { contentH.toDp() })
                ) {
                    drawTimelineBands(layout, data, measurer, labelStyle)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                // same padding as the list start/end, kept visible while zoomed
                val paper = Color(0xFFF4EFE5)
                drawRect(paper, topLeft = Offset.Zero, size = Size(size.width, topPad))
                drawRect(
                    paper,
                    topLeft = Offset(0f, (size.height - endPad).coerceAtLeast(0f)),
                    size = Size(size.width, endPad),
                )
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
                val p = cursorY.coerceIn(topPad * 0.5f, size.height - 5.dp.toPx())
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
            if (my < b.from || my > b.to) return@forEach
            val f = (my - b.from) / (b.to - b.from)
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
