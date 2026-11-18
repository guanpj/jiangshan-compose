package com.jiangshan.atlas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
    val startYear =
        if (initialYear != Int.MIN_VALUE) initialYear else data.dynasties.first().focus
    // committed year drives the map/event list; scrubYear drives the header while
    // the timeline is moving, so the map isn't rebuilt on every drag frame
    var year by remember { mutableIntStateOf(startYear) }
    var scrubYear by remember { mutableIntStateOf(startYear) }
    // live-ish linkage while scrubbing: commit the map/event year on a fixed
    // cadence (not every frame), so dragging the cursor updates the map without
    // rebuilding it 60 times a second
    LaunchedEffect(Unit) {
        while (true) {
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
    var eventsOpen by remember { mutableStateOf(true) }
    var selectedEvent by remember(dynasty.key) { mutableIntStateOf(-1) }
    var focusToken by remember { mutableIntStateOf(0) }
    var mapFocus by remember { mutableStateOf<MapFocus?>(null) }

    // hoisted so portrait <-> landscape keeps the same ruler and event list state
    val timelineState = remember { TimelineState() }
    val panelListState = rememberLazyListState()
    val drawerListState = rememberLazyListState()
    var eventsDynastyKey by remember { mutableStateOf(dynasty.key) }
    LaunchedEffect(dynasty.key) {
        if (dynasty.key != eventsDynastyKey) {
            eventsDynastyKey = dynasty.key
            panelListState.scrollToItem(0)
            drawerListState.scrollToItem(0)
        }
    }

    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val leftInset = safe.calculateLeftPadding(direction)
    val rightInset = safe.calculateRightPadding(direction)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight >= maxWidth
        val headerH = if (portrait) 76.dp else 44.dp

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

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Paper)
                .statusBarsPadding()
                .padding(start = 16.dp + leftInset, end = 16.dp + rightInset)
                .height(headerH)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            val titleStyle = TextStyle(
                color = Ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            )
            val captionStyle = TextStyle(
                color = Muted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Serif,
                lineHeight = 14.sp,
            )
            val caption = scrubSnap?.caption.orEmpty()
            if (portrait) {
                BasicText(
                    "${scrubDynasty.title} · ${formatYear(scrubYear)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = titleStyle,
                )
                if (caption.isNotEmpty()) {
                    BasicText(
                        caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = captionStyle,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "${scrubDynasty.title} · ${formatYear(scrubYear)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = titleStyle,
                    )
                    if (caption.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        BasicText(
                            caption,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = captionStyle.copy(fontSize = 11.sp),
                        )
                    }
                }
            }
        }

        if (portrait) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = leftInset + 6.dp,
                        top = statusTop + headerH + 6.dp,
                        bottom = navBottom + if (eventsOpen) 132.dp else 46.dp,
                    ),
            ) {
                Timeline(
                    data = data,
                    year = scrubYear,
                    debugSpan = timelineSpan,
                    modifier = Modifier.fillMaxHeight().shadow(10.dp, RoundedCornerShape(18.dp)),
                    state = timelineState,
                    onYear = { scrubYear = it; mapFocus = null },
                )
            }

            if (eventsOpen) {
                EventPanel(
                    dynasty = dynasty,
                    selected = selectedEvent,
                    listState = panelListState,
                    onToggle = { eventsOpen = false },
                    onPick = { index, y ->
                        selectedEvent = index
                        val ev = dynasty.events.getOrNull(index)
                        if (ev != null && ev.hasCoord) {
                            focusToken += 1
                            mapFocus = MapFocus(focusToken, ev.allCoords)
                        }
                        scrubYear = y
                        year = y
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = leftInset + 6.dp,
                            end = rightInset + 6.dp,
                            bottom = navBottom + 4.dp,
                        ),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBottom + 4.dp),
                ) {
                    EventPill(onOpen = { eventsOpen = true })
                }
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        end = rightInset + 6.dp,
                        top = statusTop + headerH + 6.dp,
                        bottom = navBottom + 8.dp,
                    ),
            ) {
                Timeline(
                    data = data,
                    year = scrubYear,
                    debugSpan = timelineSpan,
                    modifier = Modifier.fillMaxHeight().shadow(10.dp, RoundedCornerShape(18.dp)),
                    state = timelineState,
                    onYear = { scrubYear = it; mapFocus = null },
                )
            }

            AnimatedVisibility(
                visible = eventsOpen,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                EventDrawer(
                    dynasty = dynasty,
                    selected = selectedEvent,
                    listState = drawerListState,
                    onToggle = { eventsOpen = false },
                    onPick = { index, y ->
                        selectedEvent = index
                        val ev = dynasty.events.getOrNull(index)
                        if (ev != null && ev.hasCoord) {
                            focusToken += 1
                            mapFocus = MapFocus(focusToken, ev.allCoords)
                        }
                        scrubYear = y
                        year = y
                    },
                    modifier = Modifier
                        .padding(
                            start = leftInset + 6.dp,
                            top = statusTop + headerH + 8.dp,
                            bottom = navBottom + 8.dp,
                        )
                        .fillMaxHeight()
                        .width(260.dp),
                )
            }

            AnimatedVisibility(
                visible = !eventsOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                EventCornerTab(
                    onOpen = { eventsOpen = true },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = statusTop + headerH + 4.dp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EventPanel(
    dynasty: Dynasty,
    selected: Int,
    listState: LazyListState,
    onToggle: () -> Unit,
    onPick: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Column(
        modifier
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .background(Color(0xF2F3EEE4), RoundedCornerShape(12.dp))
            .border(0.8.dp, Color(0x2E2B2622), RoundedCornerShape(12.dp))
            .fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).clickable { onToggle() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "史事  ▾",
                style = TextStyle(fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Serif),
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(dynasty.events) { index, e ->
                val isSelected = index == selected
                Column(
                    Modifier
                        .width(212.dp)
                        .background(
                            if (isSelected) Color(0xFFFAEBD0) else Color(0xFFF7F2E7),
                            RoundedCornerShape(10.dp),
                        )
                        .border(
                            if (isSelected) 1.5.dp else 0.8.dp,
                            if (isSelected) Color(0xB3B5452F) else Color(0x222B2622),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            onPick(index, e.year)
                            scope.launch {
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                val itemPx = info?.size?.toFloat()
                                    ?: with(density) { 212.dp.toPx() }
                                val viewport = listState.layoutInfo.viewportSize.width
                                val center = ((viewport - itemPx) / 2f).roundToInt()
                                listState.animateScrollToItem(index, -center)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BasicText(
                        "${formatYear(e.year)} · ${e.site}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Serif),
                    )
                    BasicText(
                        e.headline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            color = Ink,
                            fontFamily = FontFamily.Serif,
                            lineHeight = 16.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventPill(onOpen: () -> Unit) {
    Row(
        Modifier
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .background(Color(0xF2F3EEE4), RoundedCornerShape(12.dp))
            .border(0.8.dp, Color(0x2E2B2622), RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicText(
            "史事  ▴",
            style = TextStyle(fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Serif),
        )
    }
}

@Composable
private fun EventDrawer(
    dynasty: Dynasty,
    selected: Int,
    listState: LazyListState,
    onToggle: () -> Unit,
    onPick: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Column(
        modifier
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .background(Color(0xF7F3EEE4), RoundedCornerShape(16.dp))
            .border(0.8.dp, Color(0x1F2B2622), RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(30.dp).clickable { onToggle() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "史事  ◂",
                style = TextStyle(fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Serif),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(dynasty.events) { index, e ->
                val isSelected = index == selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) Color(0xFFFAEBD0) else Color(0xFFF7F2E7),
                            RoundedCornerShape(10.dp),
                        )
                        .border(
                            if (isSelected) 1.5.dp else 0.8.dp,
                            if (isSelected) Color(0xB3B5452F) else Color(0x222B2622),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            onPick(index, e.year)
                            scope.launch {
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                val itemPx = info?.size?.toFloat()
                                    ?: with(density) { 64.dp.toPx() }
                                val viewport = listState.layoutInfo.viewportSize.height
                                val center = ((viewport - itemPx) / 2f).roundToInt()
                                listState.animateScrollToItem(index, -center)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BasicText(
                        "${formatYear(e.year)} · ${e.site}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Serif),
                    )
                    BasicText(
                        e.headline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = Ink,
                            fontFamily = FontFamily.Serif,
                            lineHeight = 15.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCornerTab(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .background(Color(0xF2F3EEE4), RoundedCornerShape(12.dp))
            .border(0.8.dp, Color(0x1F2B2622), RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        listOf("史", "事").forEach { ch ->
            BasicText(
                ch,
                style = TextStyle(fontSize = 10.sp, color = Ink, fontFamily = FontFamily.Serif),
            )
        }
        BasicText(
            "▸",
            style = TextStyle(fontSize = 9.sp, color = Muted, fontFamily = FontFamily.Serif),
        )
    }
}

internal fun formatYear(year: Int): String =
    if (year < 0) "公元前${-year}年" else "${year}年"
