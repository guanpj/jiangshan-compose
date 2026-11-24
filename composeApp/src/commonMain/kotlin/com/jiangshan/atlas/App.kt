package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

    var layersOpen by rememberSaveable { mutableStateOf(false) }
    var showPolities by rememberSaveable { mutableStateOf(true) }
    var showRegions by rememberSaveable { mutableStateOf(true) }
    var showSettlements by rememberSaveable { mutableStateOf(true) }
    var showHydro by rememberSaveable { mutableStateOf(true) }
    var showTerrain by rememberSaveable { mutableStateOf(true) }
    val mapVisibility = remember(showPolities, showRegions, showSettlements, showHydro, showTerrain) {
        MapLayerVisibility(
            polities = showPolities,
            regions = showRegions,
            settlements = showSettlements,
            hydro = showHydro,
            terrain = showTerrain,
        )
    }
    val mapModifier = remember { Modifier.fillMaxSize() }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchIndex = remember(data) { data.buildSearchIndex() }
    fun closeSearch() {
        searchFocused = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val searchHits = remember(searchQuery, searchIndex, scrubYear) {
        data.searchHits(searchQuery, searchIndex)
    }
    fun selectSearchHit(hit: SearchHit) {
        searchQuery = ""
        closeSearch()
        scrubYear = hit.year
        timelineState.include(hit.year)
        if (hit.coords.isNotEmpty()) {
            focusToken++
            mapFocus = MapFocus(focusToken, hit.coords)
        } else {
            mapFocus = null
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
            modifier = mapModifier,
            debugSweep = zoomSweep,
            debugFit = zoomFit,
            focus = mapFocus,
            visibility = mapVisibility,
        )

        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .zIndex(1f)
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
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { value ->
                        searchQuery = value
                        searchFocused = true
                        layersOpen = false
                    },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                        if (state.isFocused) {
                            searchFocused = true
                            layersOpen = false
                        } else if (!state.isFocused) {
                            searchFocused = false
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 12.sp),
                    cursorBrush = SolidColor(Vermilion),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val first = searchHits.firstOrNull()
                        if (first != null) selectSearchHit(first) else closeSearch()
                    }),
                    decorationBox = { inner ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Canvas(Modifier.size(18.dp)) {
                                val stroke = 1.4.dp.toPx()
                                val center = Offset(size.width * 0.42f, size.height * 0.42f)
                                val radius = size.minDimension * 0.29f
                                drawCircle(
                                    color = Muted,
                                    radius = radius,
                                    center = center,
                                    style = Stroke(stroke),
                                )
                                drawLine(
                                    color = Muted,
                                    start = Offset(
                                        center.x + radius * 0.72f,
                                        center.y + radius * 0.72f,
                                    ),
                                    end = Offset(size.width * 0.83f, size.height * 0.83f),
                                    strokeWidth = stroke * 1.05f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    BasicText(
                                        "搜地名 · 年号 · 史事",
                                        style = TextStyle(color = Muted, fontSize = 12.sp),
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                            if (searchQuery.isNotEmpty()) {
                                BasicText(
                                    "×",
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        searchQuery = ""
                                        closeSearch()
                                    },
                                    style = TextStyle(color = Muted, fontSize = 14.sp),
                                )
                            }
                        }
                    },
                )
            }
            Box(
                Modifier.size(44.dp)
                    .background(CardPaper, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = androidx.compose.ui.semantics.Role.Button,
                    ) {
                        closeSearch()
                        layersOpen = !layersOpen
                    }
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

        if (searchFocused) {
            Box(
                Modifier.fillMaxSize()
                    .zIndex(0.5f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        closeSearch()
                    }
            )
        }
        if (searchFocused && searchQuery.isNotBlank()) {
            val resultWidth = (
                if (portrait) maxWidth - leftInset - rightInset - 40.dp - 52.dp
                else 268.dp
            ).coerceAtLeast(160.dp)
            Column(
                Modifier.align(Alignment.TopEnd)
                    .zIndex(2f)
                    .padding(end = rightInset + 20.dp + 52.dp, top = statusTop + 64.dp)
                    .width(resultWidth)
                    .heightIn(max = 320.dp)
                    .background(CardPaper, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState()),
            ) {
                if (searchHits.isEmpty()) {
                    BasicText(
                        "未找到匹配的地名、年号或史事",
                        modifier = Modifier.padding(14.dp),
                        style = TextStyle(color = Muted, fontSize = 11.sp),
                    )
                } else {
                    searchHits.forEachIndexed { index, hit ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { selectSearchHit(hit) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            BasicText(
                                hit.title,
                                style = TextStyle(color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                hit.subtitle,
                                style = TextStyle(color = Muted, fontSize = 10.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index < searchHits.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(PanelRule))
                        }
                    }
                }
            }
        }

        if (layersOpen) {
            Box(
                Modifier.fillMaxSize()
                    .zIndex(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { layersOpen = false }
            )
            Column(
                Modifier.align(Alignment.TopEnd)
                    .zIndex(2f)
                    .padding(end = rightInset + 20.dp, top = statusTop + 64.dp)
                    .width(246.dp)
                    .background(CardPaper, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "图层",
                    style = TextStyle(
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LayerChip("历史疆域", showPolities, Modifier.weight(1f)) { showPolities = it }
                    LayerChip("州道政区", showRegions, Modifier.weight(1f)) { showRegions = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LayerChip("古地名", showSettlements, Modifier.weight(1f)) { showSettlements = it }
                    LayerChip("水系", showHydro, Modifier.weight(1f)) { showHydro = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LayerChip("地形", showTerrain, Modifier.weight(1f)) { showTerrain = it }
                    Box(Modifier.weight(1f))
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

@Composable
private fun LayerChip(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier
            .height(30.dp)
            .background(
                if (checked) Ink.copy(alpha = 0.07f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = if (checked) Vermilion else Muted.copy(alpha = 0.55f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = if (checked) androidx.compose.ui.graphics.drawscope.Fill
                else Stroke(stroke),
            )
            if (checked) {
                drawLine(
                    Color.White,
                    Offset(size.width * 0.24f, size.height * 0.52f),
                    Offset(size.width * 0.44f, size.height * 0.72f),
                    1.3.dp.toPx(),
                    androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    Color.White,
                    Offset(size.width * 0.44f, size.height * 0.72f),
                    Offset(size.width * 0.78f, size.height * 0.30f),
                    1.3.dp.toPx(),
                    androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        BasicText(
            label,
            style = TextStyle(color = if (checked) Ink else Muted, fontSize = 11.sp),
            maxLines = 1,
        )
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

