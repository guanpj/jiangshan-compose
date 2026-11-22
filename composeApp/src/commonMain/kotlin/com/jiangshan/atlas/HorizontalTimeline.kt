package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val EventArrowWidth = 36.dp

internal class HorizontalTimelineState(
    firstYear: Int,
    lastYear: Int,
    focusYear: Int = firstYear,
    initialSpan: Int = 0,
) {
    val first = yearIndex(firstYear)
    val last = yearIndex(lastYear)
    var from by mutableIntStateOf(first)
        private set
    var to by mutableIntStateOf(last)
        private set

    init {
        if (initialSpan in 2 until (last - first)) {
            val center = yearIndex(focusYear).coerceIn(first + 1, last - 1)
            val half = (initialSpan - 1) / 2
            var start = center - half
            var end = start + initialSpan - 1
            if (start < first) {
                start = first
                end = first + initialSpan - 1
            }
            if (end > last) {
                end = last
                start = last - initialSpan + 1
            }
            from = start
            to = end
        }
    }

    fun applyRange(start: Int, end: Int, year: Int): Int {
        from = start.coerceIn(first, last - 1)
        to = end.coerceIn(from + 1, last)
        return timelineYear(yearIndex(year).coerceIn(from, to))
    }

    fun reset() { from = first; to = last }

    fun include(year: Int) {
        if (yearIndex(year) !in from..to) reset()
    }
}

@Composable
internal fun HorizontalTimeline(
    data: AtlasData,
    year: Int,
    dynastyTitle: String,
    state: HorizontalTimelineState,
    onYear: (Int) -> Unit,
    onEventYear: (Int) -> Unit,
    portrait: Boolean,
    era: EraLabel?,
    period: String,
    caption: String,
    event: AtlasEvent?,
    onFocusEvent: (() -> Unit)?,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val currentYear by rememberUpdatedState(year)
    val onYearChanged by rememberUpdatedState(onYear)
    val onEventYearChanged by rememberUpdatedState(onEventYear)
    val textMeasurer = rememberTextMeasurer()
    val dynastyLabels = remember(data, textMeasurer) {
        data.dynasties.associateWith { dynasty ->
            textMeasurer.measure(
                AnnotatedString(dynasty.title),
                TextStyle(fontSize = 9.sp, color = Color.White, fontFamily = FontFamily.Serif),
            )
        }
    }
    data class EventMark(val index: Int, val major: Boolean)

    val eventMarks = remember(data) {
        val byYear = mutableMapOf<Int, Boolean>()
        data.dynasties.forEach { dynasty ->
            dynasty.events.forEach { event ->
                val index = yearIndex(event.year)
                byYear[index] = (byYear[index] ?: false) || event.siteCoords.isNotEmpty()
            }
        }
        byYear.entries.sortedBy { it.key }.map { EventMark(it.key, it.value) }
    }
    val eventYears = remember(eventMarks) { eventMarks.map { it.index } }
    var selectionStart by remember(state) { mutableIntStateOf(state.from) }
    var selectionEnd by remember(state) { mutableIntStateOf(state.to) }
    var selecting by remember(state) { mutableStateOf(false) }

    val zoomed = state.from != state.first || state.to != state.last
    val panelShape = if (portrait) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        else RoundedCornerShape(14.dp)
    Column(
        modifier.clip(panelShape).background(
            Brush.verticalGradient(listOf(Paper.copy(alpha = 0.92f), Paper), endY = 96f)
        ).padding(
            top = if (portrait) 6.dp else 2.dp,
            bottom = (if (portrait) 6.dp else 2.dp) + bottomInset,
        ),
    ) {
        if (portrait) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = EventArrowWidth, end = EventArrowWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        axisYear(year),
                        style = YearStyle,
                        softWrap = false,
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .height(with(LocalDensity.current) { (DetailStyle.lineHeight.value * 3 + 2).sp.toDp() }),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            BasicText(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = Vermilion, fontWeight = FontWeight.Medium)) {
                                        append(dynastyTitle)
                                    }
                                    withStyle(SpanStyle(color = Muted)) { append(" · $period") }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = DetailStyle,
                            )
                            BasicText(
                                caption,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = DetailStyle,
                            )
                        }
                    }
                }
                if (zoomed) {
                    ResetTimeline(Modifier.align(Alignment.CenterEnd)) { state.reset() }
                }
            }
        }
        val selectedIndex = yearIndex(year)
        val previousEvent = eventYears.lastOrNull { it < selectedIndex }
        val nextEvent = eventYears.firstOrNull { it > selectedIndex }
        fun jumpToEvent(index: Int) {
            val targetYear = timelineYear(index)
            state.include(targetYear)
            onEventYearChanged(targetYear)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (portrait) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EventArrow("上一个事件", pointsRight = false, enabled = previousEvent != null) {
                previousEvent?.let(::jumpToEvent)
            }
            Canvas(
                Modifier.weight(1f).height(44.dp)
                    .semantics { contentDescription = "历史时间轴，左右滑块选择时间范围" }
                    .pointerInput(state, data) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val inset = 4.dp.toPx()
                            val width = (size.width - inset * 2).coerceAtLeast(1f)
                            val start = state.from
                            val end = state.to
                            fun indexAt(x: Float): Int = (start + ((x - inset) / width).coerceIn(0f, 1f) * (end - start)).roundToInt()
                            val hitWidth = 18.dp.toPx()
                            fun xOf(index: Int): Float =
                                inset + (index - start).toFloat() / (end - start) * width
                            val cursorX = xOf(yearIndex(currentYear).coerceIn(start, end))
                            val cursorGrab = abs(down.position.x - cursorX) <= 9.dp.toPx()
                            val handle = when {
                                // The cursor sits on a handle at the edge of a selected
                                // range. Grabbing the red cursor should scrub, not resize.
                                cursorGrab -> 0
                                down.position.x <= inset + hitWidth -> -1
                                down.position.x >= size.width - inset - hitWidth -> 1
                                else -> 0
                            }
                            val anchor = if (handle < 0) inset else inset + width
                            val offset = down.position.x - anchor
                            selectionStart = start
                            selectionEnd = end
                            var changed = false
                            if (handle == 0 && !cursorGrab) {
                                onYearChanged(timelineYear(indexAt(down.position.x)))
                            }
                            down.consume()
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (handle != 0 && (changed ||
                                            abs(change.position.x - down.position.x) >= viewConfiguration.touchSlop)
                                    ) {
                                        val index = indexAt(change.position.x - offset)
                                        if (handle < 0) selectionStart = index.coerceAtMost(end - 1)
                                        else selectionEnd = index.coerceAtLeast(start + 1)
                                        selecting = true
                                        changed = true
                                    } else if (handle == 0 && change.pressed) {
                                        onYearChanged(timelineYear(indexAt(change.position.x)))
                                    }
                                    change.consume()
                                    if (!change.pressed) {
                                        if (changed) {
                                            val next = state.applyRange(selectionStart, selectionEnd, currentYear)
                                            if (next != currentYear) onYearChanged(next)
                                        }
                                        break
                                    }
                                } while (true)
                            } finally { selecting = false }
                        }
                    }
            ) {
                val inset = 4.dp.toPx()
                val trackW = (size.width - inset * 2).coerceAtLeast(1f)
                val trackH = 24.dp.toPx()
                val top = (size.height - trackH) / 2
                val from = state.from
                val to = state.to
                fun x(index: Int) = inset + (index - from).toFloat() / (to - from) * trackW
                data.dynasties.forEachIndexed { i, d ->
                    val begin = max(from, yearIndex(d.from))
                    val end = min(to, yearIndex(data.dynasties.getOrNull(i + 1)?.from ?: d.to))
                    if (end <= begin) return@forEachIndexed
                    val left = x(begin)
                    val right = x(end)
                    drawRect(Color(d.tint).copy(alpha = 0.8f), Offset(left, top), Size(right - left, trackH))
                }
                // Every tick is an actual event year. Events that also have a
                // route (battles, migrations) get a taller, brighter tick.
                for (mark in eventMarks) {
                    if (mark.index < from) continue
                    if (mark.index > to) break
                    val marker = x(mark.index)
                    val height = if (mark.major) 4.dp.toPx() else 2.dp.toPx()
                    val tickTop = top + trackH - height
                    drawLine(
                        Color.White.copy(alpha = if (mark.major) 0.8f else 0.55f),
                        Offset(marker, tickTop),
                        Offset(marker, top + trackH),
                        if (mark.major) 1.1.dp.toPx() else 0.8.dp.toPx(),
                    )
                }
                // Draw labels after the event ticks so the ticks read as a
                // background rhythm and never cut through the dynasty names.
                data.dynasties.forEachIndexed { i, d ->
                    val begin = max(from, yearIndex(d.from))
                    val end = min(to, yearIndex(data.dynasties.getOrNull(i + 1)?.from ?: d.to))
                    if (end <= begin) return@forEachIndexed
                    val left = x(begin)
                    val right = x(end)
                    val label = dynastyLabels[d] ?: return@forEachIndexed
                    if (right - left >= label.size.width + 6.dp.toPx()) {
                        drawText(
                            label,
                            topLeft = Offset(
                                (left + right - label.size.width) / 2,
                                top + (trackH - label.size.height) / 2,
                            ),
                        )
                    }
                }
                if (selecting) {
                    drawRect(Paper.copy(alpha = 0.6f), Offset(inset, top), Size((x(selectionStart) - inset).coerceAtLeast(0f), trackH))
                    drawRect(Paper.copy(alpha = 0.6f), Offset(x(selectionEnd), top), Size((inset + trackW - x(selectionEnd)).coerceAtLeast(0f), trackH))
                }
                val leftHandle = if (selecting) x(selectionStart) else inset
                val rightHandle = if (selecting) x(selectionEnd) else inset + trackW
                listOf(leftHandle, rightHandle).forEach { handleX ->
                    drawRoundRect(Color(0xFFFFFCF5), Offset(handleX - 3.dp.toPx(), top - 2.dp.toPx()), Size(6.dp.toPx(), trackH + 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawRoundRect(Ink.copy(alpha = 0.5f), Offset(handleX - 3.dp.toPx(), top - 2.dp.toPx()), Size(6.dp.toPx(), trackH + 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(0.7.dp.toPx()))
                }
                val cursor = x(yearIndex(year).coerceIn(from, to))
                drawLine(Color(0xFFFFF8E7), Offset(cursor, top - 5.dp.toPx()), Offset(cursor, top + trackH + 9.dp.toPx()), 5.dp.toPx())
                drawLine(Vermilion, Offset(cursor, top - 5.dp.toPx()), Offset(cursor, top + trackH + 9.dp.toPx()), 2.dp.toPx())
            }
            EventArrow("下一个事件", pointsRight = true, enabled = nextEvent != null) {
                nextEvent?.let(::jumpToEvent)
            }
            if (!portrait) {
                Box(Modifier.width(EventArrowWidth), contentAlignment = Alignment.Center) {
                    if (zoomed) ResetTimeline { state.reset() }
                }
            }
        }
        if (portrait) {
            Row(
                Modifier.fillMaxWidth().padding(start = EventArrowWidth, end = EventArrowWidth, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (era != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (era.ruler.isNotEmpty()) {
                            BasicText(era.ruler, style = DetailStyle.copy(color = Ink, textAlign = TextAlign.Center), maxLines = 1)
                        }
                        BasicText(era.date, style = DetailStyle.copy(textAlign = TextAlign.Center), maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).height(28.dp).background(PanelRule))
                }
                EventHeadline(event, onFocusEvent, Modifier.weight(1f), minLines = 2)
            }
        }
    }
}

@Composable
private fun ResetTimeline(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = tween(110),
        label = "resetAlpha",
    )
    val scope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }
    Box(
        modifier.sizeIn(minWidth = EventArrowWidth, minHeight = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    scope.launch {
                        bounce.snapTo(0.9f)
                        bounce.animateTo(1f, tween(140))
                    }
                    onClick()
                },
            )
            .semantics { contentDescription = "恢复全轴" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .graphicsLayer {
                    alpha = pressAlpha
                    scaleX = bounce.value * 0.9f
                    scaleY = bounce.value * 0.9f
                }
        ) {
            val stroke = 1.5.dp.toPx()
            // Lucide rotate-cw geometry (24-unit viewbox, scaled to the canvas):
            // right-centre -> bottom -> left -> top, then a short curve into the
            // right-angle arrowhead at the upper right.
            val unit = size.minDimension / 24f
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            val loop = Path().apply {
                val start = point(21f, 12f)
                moveTo(start.x, start.y)
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        point(3f, 3f),
                        point(21f, 21f),
                    ),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 270f,
                    forceMoveTo = false,
                )
                cubicTo(
                    point(14.52f, 3f).x, point(14.52f, 3f).y,
                    point(16.93f, 4f).x, point(16.93f, 4f).y,
                    point(18.74f, 5.74f).x, point(18.74f, 5.74f).y,
                )
                lineTo(point(21f, 8f).x, point(21f, 8f).y)
            }
            drawPath(loop, Ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val head = Path().apply {
                moveTo(point(21f, 3f).x, point(21f, 3f).y)
                lineTo(point(21f, 8f).x, point(21f, 8f).y)
                lineTo(point(16f, 8f).x, point(16f, 8f).y)
            }
            drawPath(head, Ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun EventArrow(label: String, pointsRight: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = tween(110),
        label = "arrowAlpha",
    )
    val scope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }
    Box(
        Modifier.width(EventArrowWidth).height(44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    scope.launch {
                        bounce.snapTo(0.9f)
                        bounce.animateTo(1f, tween(140))
                    }
                    onClick()
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .graphicsLayer {
                    alpha = pressAlpha
                    scaleX = bounce.value
                    scaleY = bounce.value
                }
        ) {
            val stroke = 1.5.dp.toPx()
            val halfWidth = 3.dp.toPx()
            val halfHeight = 5.5.dp.toPx()
            val start = if (pointsRight) center.x - halfWidth else center.x + halfWidth
            val end = if (pointsRight) center.x + halfWidth else center.x - halfWidth
            val color = Ink.copy(alpha = if (enabled) 1f else 0.3f)
            drawLine(
                color,
                Offset(start, center.y - halfHeight),
                Offset(end, center.y),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(end, center.y),
                Offset(start, center.y + halfHeight),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}
