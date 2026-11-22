package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal val Paper = Color(0xFFF3EEE4)
internal val Ink = Color(0xFF2B2622)
internal val Muted = Color(0xFF7A7166)
internal val Vermilion = Color(0xFFB5452F)

@Composable
fun App(zoomSweep: Boolean = false, zoomFit: Boolean = false, timelineSpan: Int = 0, timelineYear: Int = Int.MIN_VALUE) {
    var data by remember { mutableStateOf<AtlasData?>(null) }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.Default) { AtlasData.load() }
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        val loaded = data
        if (loaded == null) {
            BasicText(
                "载入中…",
                modifier = Modifier.align(Alignment.Center),
                style = TextStyle(color = Muted, fontSize = 14.sp),
            )
        } else {
            MapScreen(loaded, zoomSweep, zoomFit, timelineSpan, timelineYear)
        }
    }
}

@Composable
private fun MapScreen(data: AtlasData, zoomSweep: Boolean = false, zoomFit: Boolean = false, timelineSpan: Int = 0, initialYear: Int = Int.MIN_VALUE) {
    val startYear = data.initialYear(initialYear)
    // The header and event selection follow the cursor. The map updates at a lower rate.
    var year by remember { mutableIntStateOf(startYear) }
    var scrubYear by remember { mutableIntStateOf(startYear) }
    // live-ish linkage while scrubbing: commit the map year on a fixed
    // cadence (not every frame), so dragging the cursor updates the map without
    // rebuilding it 60 times a second
    LaunchedEffect(Unit) {
        snapshotFlow { scrubYear }
            .conflate()
            .collect {
                delay(200)
                if (year != scrubYear) year = scrubYear
            }
    }
    val dynasty = remember(year) { data.dynastyFor(year) }
    val snapKey = remember(dynasty, year) { data.snapshotKey(dynasty, year) }
    val snap = snapKey?.let { data.snapshots[it] }
    val scrubDynasty = remember(scrubYear) { data.dynastyFor(scrubYear) }
    val scrubSnapKey = remember(scrubDynasty, scrubYear) {
        data.snapshotKey(scrubDynasty, scrubYear)
    }
    val scrubSnap = scrubSnapKey?.let { data.snapshots[it] }
    val phase = remember(scrubDynasty, scrubSnap, scrubYear) {
        phaseLabel(scrubDynasty, scrubSnap?.caption.orEmpty(), scrubYear)
    }
    val selection = remember(scrubYear) { data.eventAtOrBefore(scrubYear) }
    var focusToken by remember { mutableIntStateOf(0) }
    var mapFocus by remember { mutableStateOf<MapFocus?>(null) }
    val onTimelineYear: (Int) -> Unit = { pickedYear ->
        scrubYear = pickedYear
        mapFocus = null
    }
    val timelineState = remember(data, startYear, timelineSpan) {
        HorizontalTimelineState(
            data.dynasties.first().from,
            data.dynasties.last().to,
            focusYear = startYear,
            initialSpan = timelineSpan,
        )
    }
    val selectEventYear: (Int) -> Unit = { targetYear ->
        scrubYear = targetYear
        timelineState.include(targetYear)
        val picked = data.eventAtOrBefore(targetYear)?.event
        if (picked != null && picked.allCoords.isNotEmpty()) {
            focusToken++
            mapFocus = MapFocus(focusToken, picked.allCoords)
        }
    }

    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val leftInset = safe.calculateLeftPadding(direction)
    val rightInset = safe.calculateRightPadding(direction)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = safe.calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight >= maxWidth
        AtlasMap(
            data = data,
            snap = snap,
            dynasty = dynasty,
            year = year,
            modifier = Modifier.fillMaxSize(),
            debugSweep = zoomSweep,
            debugFit = zoomFit,
            focus = mapFocus,
        )

        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .padding(start = leftInset + 20.dp, end = rightInset + 20.dp, top = 12.dp)
                .then(if (portrait) Modifier.fillMaxWidth() else Modifier.width(320.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).height(44.dp).background(CardPaper, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText("⌕  搜人物 · 地名 · 年号", style = TextStyle(color = Muted, fontSize = 12.sp))
            }
            Box(
                Modifier.size(44.dp).background(CardPaper, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = "图层" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    for (layer in 0..2) {
                        val y = size.height * (0.26f + layer * 0.17f)
                        val path = Path().apply {
                            moveTo(size.width * .12f, y)
                            lineTo(size.width * .50f, y + size.height * .21f)
                            lineTo(size.width * .88f, y)
                            if (layer == 0) {
                                lineTo(size.width * .50f, y - size.height * .21f)
                                close()
                            }
                        }
                        drawPath(path, Ink, style = Stroke(1.2.dp.toPx()))
                    }
                }
            }
        }

        val era = remember(scrubYear) { data.eraFor(scrubYear, scrubDynasty) }
        val event = selection?.event
        val onFocusEvent = event?.takeIf { it.allCoords.isNotEmpty() }?.let { selected ->
            {
                if (scrubYear != selected.year) {
                    scrubYear = selected.year
                    timelineState.include(selected.year)
                }
                focusToken++
                mapFocus = MapFocus(focusToken, selected.allCoords)
            }
        }

        if (!portrait) {
            Column(
                Modifier.align(Alignment.TopStart)
                    .padding(start = leftInset + 20.dp, top = statusTop + 12.dp)
                    .width(300.dp)
                    .background(CardPaper, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BasicText(
                            scrubDynasty.title,
                            modifier = Modifier.weight(1f, fill = false),
                            style = YearStyle.copy(color = Vermilion, fontSize = 26.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            "·",
                            style = YearStyle.copy(color = Muted, fontSize = 26.sp),
                        )
                        BasicText(axisYear(scrubYear), style = YearStyle.copy(fontSize = 26.sp))
                    }
                    BasicText(era?.inline.orEmpty(), style = DetailStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PanelRule))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(phase.period, style = DetailStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(phase.caption, style = DetailStyle, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PanelRule))
                EventHeadline(event, onFocusEvent, minLines = 2)
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(if (portrait) Color.Transparent else Paper),
        ) {
            HorizontalTimeline(
                data = data,
                year = scrubYear,
                dynastyTitle = scrubDynasty.title,
                state = timelineState,
                onYear = onTimelineYear,
                onEventYear = selectEventYear,
                portrait = portrait,
                era = era,
                period = phase.period,
                caption = phase.caption,
                event = event,
                onFocusEvent = onFocusEvent,
                bottomInset = bottomInset,
                modifier = Modifier
                    .padding(start = leftInset, end = rightInset)
                    .fillMaxWidth(),
            )
        }
    }
}

internal val PanelRule = Ink.copy(alpha = 0.14f)
internal val CardPaper = Color(0xFFFBF7EE)
internal val YearStyle = TextStyle(color = Ink, fontSize = 23.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
internal val DetailStyle = TextStyle(color = Muted, fontSize = 11.sp, lineHeight = 15.sp)

@Composable
internal fun EventHeadline(
    event: AtlasEvent?,
    onFocus: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    val headline = event?.headline.orEmpty()
    if (headline.isBlank() && minLines == 1) return
    val minHeight = with(LocalDensity.current) {
        (DetailStyle.lineHeight.value * minLines).sp.toDp()
    }
    Box(
        modifier
            .heightIn(min = minHeight)
            .then(if (onFocus != null) Modifier.clickable(onClick = onFocus) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (headline.isNotBlank()) {
            BasicText(
                headline,
                style = DetailStyle.copy(color = Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

