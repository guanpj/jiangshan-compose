package com.jiangshan.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.jiangshan.atlas.res.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class MapLayers(
    val polityPaths: List<Path>,
    val polityFills: List<Color>,
    val polityStrokes: List<Color>,
    val regionPaths: List<Path>,
)

private class TileStore(private val scope: CoroutineScope) {
    val images = mutableStateMapOf<String, ImageBitmap>()
    private val pending = mutableSetOf<String>()
    private val lru = LinkedHashSet<String>()
    private var visible: Set<String> = emptySet()

    fun beginFrame(keys: Set<String>) {
        visible = keys
    }

    fun get(level: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$level/$x/$y"
        val hit = images[key]
        if (hit != null) {
            touch(key)
            return hit
        }
        if (pending.add(key)) {
            scope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    runCatching {
                        Res.readBytes("files/atlas/terrain/$level/$x/$y.webp").decodeToImageBitmap()
                    }.getOrNull()
                }
                if (bmp != null) {
                    images[key] = bmp
                    touch(key)
                    trim()
                }
                pending.remove(key)
            }
        }
        return null
    }

    private fun touch(key: String) {
        lru.remove(key)
        lru.add(key)
    }

    private fun trim() {
        if (images.size <= 48) return
        val it = lru.iterator()
        while (images.size > 48 && it.hasNext()) {
            val k = it.next()
            if (k !in visible) {
                it.remove()
                images.remove(k)
            }
        }
    }
}

@Composable
fun AtlasMap(
    data: AtlasData,
    snap: Snapshot?,
    dynasty: Dynasty,
    year: Int,
    modifier: Modifier = Modifier,
    debugSweep: Boolean = false,
    debugFit: Boolean = false,
) {
    val projector = remember { Projector() }
    val layers = remember(snap) { buildLayers(data, snap, projector) }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val tileStore = remember { TileStore(scope) }
    val tileLevel = remember { IntArray(1) { -1 } }
    val tilesOn = remember { BooleanArray(1) { false } }
    val hydro = remember(data) { buildHydroLayers(data, projector) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // atlas: min = fit whole map (letterboxed), initial = cover, max = 20x fit
        val contain = min(w / Projector.W, h / Projector.H)
        val cover = max(w / Projector.W, h / Projector.H)
        val minScale = contain
        val maxScale = max(cover, contain * 20f)
        // camera survives size changes (year switches alter nothing, rotation just re-clamps)
        var scale by remember { mutableFloatStateOf(0f) }
        var camX by remember { mutableFloatStateOf(0f) }
        var camY by remember { mutableFloatStateOf(0f) }
        if (scale == 0f) {
            scale = if (debugFit) minScale else cover
            camX = projector.x(111.0)
            camY = Projector.H / 2f
        }

        fun clamp() {
            val halfW = w / (2f * scale)
            val halfH = h / (2f * scale)
            camX = if (halfW >= Projector.W / 2f) Projector.W / 2f
            else camX.coerceIn(halfW, Projector.W - halfW)
            camY = if (halfH >= Projector.H / 2f) Projector.H / 2f
            else camY.coerceIn(halfH, Projector.H - halfH)
        }

        if (debugSweep) {
            androidx.compose.runtime.LaunchedEffect(w, h) {
                val lo = cover * 1.25f
                val hi = cover * 2.4f
                var t = 0f
                while (true) {
                    kotlinx.coroutines.delay(16)
                    t += 0.016f
                    val target = lo + (kotlin.math.sin(t * 1.4f) * 0.5f + 0.5f) * (hi - lo)
                    scale = target.coerceIn(minScale, maxScale)
                    clamp()
                }
            }
        }

        fun zoomAt(px: Float, py: Float, factor: Float) {
            val old = scale
            val ns = (scale * factor).coerceIn(minScale, maxScale)
            if (ns == old) return
            val dx = px - w / 2f
            val dy = py - h / 2f
            val wx = dx / old + camX
            val wy = dy / old + camY
            camX = wx - dx / ns
            camY = wy - dy / ns
            scale = ns
            clamp()
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(w, h, minScale, maxScale) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = scale
                        val ns = (scale * zoom).coerceIn(minScale, maxScale)
                        val dx = centroid.x - w / 2f
                        val dy = centroid.y - h / 2f
                        val wx = dx / old + camX
                        val wy = dy / old + camY
                        camX = wx - dx / ns - pan.x / ns
                        camY = wy - dy / ns - pan.y / ns
                        scale = ns
                        clamp()
                    }
                }
                .pointerInput(w, h, minScale, maxScale) {
                    detectTapGestures(onDoubleTap = { offset ->
                        zoomAt(offset.x, offset.y, 1.8f)
                    })
                }
        ) {
            drawRect(Paper)

            data.relief?.let { img ->
                val left = (-80f - camX) * scale + size.width / 2f
                val top = (-40f - camY) * scale + size.height / 2f
                val right = (1080f - camX) * scale + size.width / 2f
                val bottom = (740f - camY) * scale + size.height / 2f
                drawImage(
                    image = img,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((right - left).roundToInt(), (bottom - top).roundToInt()),
                    alpha = 0.62f,
                    filterQuality = FilterQuality.Medium,
                )
            }

            drawTerrainTiles(data, tileStore, projector, scale, camX, camY, tileLevel, tilesOn)

            val cities = data.cities[dynasty.key].orEmpty().filter {
                (it.appear == null || year >= it.appear) && (it.vanish == null || year < it.vanish)
            }

            withTransform({
                translate(size.width / 2f - camX * scale, size.height / 2f - camY * scale)
                scale(scale, scale, Offset.Zero)
            }) {
                layers.polityPaths.forEachIndexed { i, path ->
                    drawPath(path, layers.polityFills[i])
                    drawPath(
                        path,
                        layers.polityStrokes[i],
                        style = Stroke(width = 1.1.dp.toPx() / scale),
                    )
                }
                val regionColor = Color(0x332B2622)
                layers.regionPaths.forEach {
                    drawPath(it, regionColor, style = Stroke(width = 0.8.dp.toPx() / scale))
                }
                drawHydro(hydro, scale, year)
            }

            // polity captions (main / rival / protectorate / neighbor), web typography
            val zoomFactor = sqrt((scale / cover).coerceAtLeast(0.25f)).coerceIn(0.85f, 2.8f)
            val placed = ArrayList<Rect>(24)
            snap?.polities?.forEach { idx ->
                val f = data.features.getOrNull(idx) ?: return@forEach
                val lon = f.anchorLon ?: return@forEach
                val lat = f.anchorLat ?: return@forEach
                val wx = projector.x(lon.toDouble())
                val wy = projector.y(lat.toDouble())
                val sx = (wx - camX) * scale + size.width / 2f
                val sy = (wy - camY) * scale + size.height / 2f
                if (sx < -120f || sx > size.width + 120f || sy < -80f || sy > size.height + 80f) {
                    return@forEach
                }
                val baseSp = when (f.role) {
                    "rival" -> 15f
                    "protectorate" -> 12f
                    "neighbor" -> 14f
                    else -> 19f
                } * zoomFactor
                val style = TextStyle(
                    fontSize = baseSp.sp,
                    color = if (f.role == "neighbor") Color(0xFF5A2D18) else Color(0xEB1C1612),
                    fontWeight = if (f.role == "protectorate") FontWeight.SemiBold else FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = (baseSp * 0.18f).sp,
                )
                val layout = measurer.measure(AnnotatedString(f.title), style)
                val tw = layout.size.width.toFloat()
                val th = layout.size.height.toFloat()
                val rect = Rect(sx - tw / 2f, sy - th / 2f, sx + tw / 2f, sy + th / 2f)
                if (placed.any { it.overlaps(rect) }) return@forEach
                placed.add(rect)
                drawText(layout, topLeft = Offset(rect.left, rect.top))
            }

            // prefecture / commandery captions (郡/州/府/路)
            snap?.regions?.forEach { idx ->
                val f = data.features.getOrNull(idx) ?: return@forEach
                if (f.title.isBlank()) return@forEach
                val lon = f.anchorLon ?: return@forEach
                val lat = f.anchorLat ?: return@forEach
                val wx = projector.x(lon.toDouble())
                val wy = projector.y(lat.toDouble())
                val sx = (wx - camX) * scale + size.width / 2f
                val sy = (wy - camY) * scale + size.height / 2f
                if (sx < -120f || sx > size.width + 120f || sy < -80f || sy > size.height + 80f) {
                    return@forEach
                }
                val sizeSp = 9f * zoomFactor
                val style = TextStyle(
                    fontSize = sizeSp.sp,
                    color = Color(0xED522212),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = (sizeSp * 0.08f).sp,
                )
                val layout = measurer.measure(AnnotatedString(f.title), style)
                val tw = layout.size.width.toFloat()
                val th = layout.size.height.toFloat()
                val rect = Rect(sx - tw / 2f, sy - th / 2f, sx + tw / 2f, sy + th / 2f)
                if (placed.any { it.overlaps(rect) }) return@forEach
                placed.add(rect)
                drawText(layout, topLeft = Offset(rect.left, rect.top))
            }

            // water / mountain captions (lowest priority: cities never yield to them)
            val hydroPlaced = ArrayList<Rect>(placed)
            hydro.labels.forEach { lb ->
                val wx = projector.x(lb.lon.toDouble())
                val wy = projector.y(lb.lat.toDouble())
                val sx = (wx - camX) * scale + size.width / 2f
                val sy = (wy - camY) * scale + size.height / 2f
                if (sx < -80f || sx > size.width + 80f || sy < -60f || sy > size.height + 60f) {
                    return@forEach
                }
                val sizeSp = when (lb.kind) {
                    "trunk" -> 8.2f
                    "lake" -> 7.0f
                    "range" -> 8.0f
                    "desert" -> 9.5f
                    else -> 7.2f
                } * zoomFactor
                val color = when (lb.kind) {
                    "range", "desert" -> Color(0xD1604628)
                    "lake" -> Color(0xEB12486A)
                    "trunk" -> Color(0xF20E4462)
                    else -> Color(0xE0164E6E)
                }
                val spacing = when (lb.kind) {
                    "range" -> 0.5f
                    "desert" -> 1.1f
                    "lake" -> 0.06f
                    else -> 0.14f
                }
                val style = TextStyle(
                    fontSize = sizeSp.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                    fontStyle = if (lb.kind == "trunk" || lb.kind == "river")
                        androidx.compose.ui.text.font.FontStyle.Italic
                    else androidx.compose.ui.text.font.FontStyle.Normal,
                    letterSpacing = (sizeSp * spacing).sp,
                )
                val layout = measurer.measure(AnnotatedString(lb.text), style)
                val tw = layout.size.width.toFloat()
                val th = layout.size.height.toFloat()
                val rect = Rect(sx - tw / 2f, sy - th / 2f, sx + tw / 2f, sy + th / 2f)
                if (hydroPlaced.any { it.overlaps(rect) }) return@forEach
                hydroPlaced.add(rect)
                withTransform({ rotate(lb.rot, Offset(sx, sy)) }) {
                    drawText(layout, topLeft = Offset(sx - tw / 2f, sy - th / 2f))
                }
            }

            // settlements: web-style tiered markers, capitals on top, simple label collision
            val showTier2 = scale >= cover * 1.04f
            val onScreen = cities.mapNotNull { c ->
                if (c.tier == 2 && !showTier2) return@mapNotNull null
                val isCapNow = c.capital && (
                    c.capitalPeriods.isEmpty() ||
                        c.capitalPeriods.any { year >= it[0] && year < it[1] }
                    )
                val wx = projector.x(c.lon.toDouble())
                val wy = projector.y(c.lat.toDouble())
                val sx = (wx - camX) * scale + size.width / 2f
                val sy = (wy - camY) * scale + size.height / 2f
                if (sx < -80f || sx > size.width + 80f || sy < -40f || sy > size.height + 40f) {
                    return@mapNotNull null
                }
                CityOnMap(c, sx, sy, isCapNow)
            }.sortedBy {
                when {
                    it.isCap -> 0
                    it.city.tier == 2 -> 2
                    else -> 1
                }
            }

            onScreen.forEach { drawCityMarker(it.isCap, it.city.tier, it.x, it.y, zoomFactor) }

            onScreen.forEach { item ->
                val c = item.city
                val isCap = item.isCap
                val tier2 = c.tier == 2 && !isCap
                val baseSp = when {
                    isCap -> 10.6f
                    tier2 -> 8.6f
                    else -> 9.6f
                } * zoomFactor
                val style = TextStyle(
                    fontSize = baseSp.sp,
                    color = when {
                        isCap -> Color(0xFF72190B)
                        tier2 -> Color(0xFF2C251F)
                        else -> Color(0xFF1C1713)
                    },
                    fontWeight = if (tier2) FontWeight.SemiBold else FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                )
                val layout = measurer.measure(AnnotatedString(c.name), style)
                val tw = layout.size.width.toFloat()
                val th = layout.size.height.toFloat()
                val gap = 5.5.dp.toPx()
                val cands = listOf(
                    Offset(item.x + gap, item.y - th / 2f),
                    Offset(item.x - gap - tw, item.y - th / 2f),
                    Offset(item.x - tw / 2f, item.y - gap - th),
                    Offset(item.x - tw / 2f, item.y + gap),
                )
                val rects = cands.map { Rect(it.x - 1f, it.y - 1f, it.x + tw + 1f, it.y + th + 1f) }
                val idx = rects.indexOfFirst { r -> placed.none { it.overlaps(r) } }
                if (idx < 0) return@forEach
                placed.add(rects[idx])
                drawText(layout, topLeft = cands[idx])
            }
        }
    }
}

private fun DrawScope.drawTerrainTiles(
    data: AtlasData,
    store: TileStore,
    projector: Projector,
    scale: Float,
    camX: Float,
    camY: Float,
    levelState: IntArray,
    onState: BooleanArray,
) {
    val relief = data.relief ?: return
    val needPpd = scale * projector.pxPerUnit()
    val reliefPpd = relief.width / 1160.0 * projector.pxPerUnit()

    // base-relief <-> tiles boundary gets its own hysteresis band
    if (!onState[0] && needPpd > reliefPpd * 1.4) onState[0] = true
    if (onState[0] && needPpd < reliefPpd * 1.05) onState[0] = false
    if (!onState[0]) return

    val levels = data.terrainLevels.keys.sorted()
    if (levels.isEmpty()) return
    fun levelPpd(z: Int): Double = data.terrainTileSize * (1 shl z) / 360.0

    // level hysteresis: only switch when the current level's density is off by 1.5x
    val desired = levels.firstOrNull { levelPpd(it) >= needPpd * 0.85 } ?: levels.last()
    var level = levelState[0]
    if (level < 0) {
        level = desired
    } else {
        val cur = levelPpd(level)
        if (needPpd > cur * 1.5 || needPpd < cur / 1.5) level = desired
    }
    if (levelState[0] != level) {
        println("CMP_TILE_LEVEL $level needPpd=" + needPpd.roundToInt())
    }
    levelState[0] = level
    val set = data.terrainLevels[level] ?: return

    val halfW = size.width / (2f * scale)
    val halfH = size.height / (2f * scale)
    val lonW = projector.lon(camX - halfW)
    val lonE = projector.lon(camX + halfW)
    val latN = projector.lat(camY - halfH)
    val latS = projector.lat(camY + halfH)

    val n = 1 shl level
    val x0 = floor((lonW + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    val x1 = floor((lonE + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    val y0 = floor(tileY(latN, n)).toInt().coerceIn(0, n - 1)
    val y1 = floor(tileY(latS, n)).toInt().coerceIn(0, n - 1)

    val wanted = mutableSetOf<String>()
    for (x in x0..x1) {
        for (y in y0..y1) {
            if ("${x}_$y" in set) wanted += "$level/$x/$y"
        }
    }
    store.beginFrame(wanted)

    for (x in x0..x1) {
        for (y in y0..y1) {
            if ("${x}_$y" !in set) continue
            val bmp = store.get(level, x, y) ?: continue
            val lonA = x.toDouble() / n * 360.0 - 180.0
            val lonB = (x + 1).toDouble() / n * 360.0 - 180.0
            val latA = tileLat(y, n)
            val latB = tileLat(y + 1, n)
            val left = (projector.x(lonA) - camX) * scale + size.width / 2f
            val top = (projector.y(latA) - camY) * scale + size.height / 2f
            val right = (projector.x(lonB) - camX) * scale + size.width / 2f
            val bottom = (projector.y(latB) - camY) * scale + size.height / 2f
            drawImage(
                image = bmp,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(
                    (right - left).roundToInt() + 1,
                    (bottom - top).roundToInt() + 1,
                ),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private fun tileY(lat: Double, n: Int): Double {
    val rad = lat * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
}

private fun tileLat(y: Int, n: Int): Double {
    val f = PI * (1.0 - 2.0 * y / n)
    return atan(sinh(f)) * 180.0 / PI
}

private fun buildLayers(data: AtlasData, snap: Snapshot?, projector: Projector): MapLayers {
    val paths = mutableListOf<Path>()
    val fills = mutableListOf<Color>()
    val strokes = mutableListOf<Color>()
    snap?.polities?.forEach { id ->
        val f = data.features.getOrNull(id) ?: return@forEach
        val path = buildPath(data, f, projector) ?: return@forEach
        val neighbor = f.role == "neighbor"
        val tint = Color(f.tint)
        paths += path
        fills += tint.copy(alpha = if (neighbor) 0.15f else 0.36f)
        strokes += tint.copy(alpha = if (neighbor) 0.33f else 0.78f)
    }
    val regions = mutableListOf<Path>()
    snap?.regions?.forEach { id ->
        data.features.getOrNull(id)?.let { f ->
            buildPath(data, f, projector)?.let { regions += it }
        }
    }
    return MapLayers(paths, fills, strokes, regions)
}

private fun buildPath(data: AtlasData, f: Feature, projector: Projector): Path? {
    val path = Path()
    path.fillType = PathFillType.EvenOdd
    var any = false
    for (poly in f.polys) {
        for (rid in poly) {
            val ring = data.rings.getOrNull(rid) ?: continue
            if (ring.size < 4) continue
            path.moveTo(projector.x(ring[0].toDouble()), projector.y(ring[1].toDouble()))
            var i = 2
            while (i + 1 < ring.size) {
                path.lineTo(projector.x(ring[i].toDouble()), projector.y(ring[i + 1].toDouble()))
                i += 2
            }
            path.close()
            any = true
        }
    }
    return if (any) path else null
}


private data class CityOnMap(val city: City, val x: Float, val y: Float, val isCap: Boolean)

private fun DrawScope.drawCityMarker(isCap: Boolean, tier: Int, x: Float, y: Float, zoom: Float) {
    val center = Offset(x, y)
    when {
        isCap -> {
            drawCircle(
                Color(0xFFB5452F),
                radius = 4.6.dp.toPx() * zoom,
                center = center,
                style = Stroke(width = 1.05.dp.toPx() * zoom),
            )
            val d = 3.2.dp.toPx() * zoom
            val diamond = Path().apply {
                moveTo(x, y - d)
                lineTo(x + d, y)
                lineTo(x, y + d)
                lineTo(x - d, y)
                close()
            }
            drawPath(diamond, Color(0xFFB5452F))
            drawPath(
                diamond,
                Color(0xFFFFFDF8),
                style = Stroke(width = 1.1.dp.toPx() * zoom),
            )
        }
        tier == 2 -> {
            drawCircle(Color(0xFFF6EFE2), 1.7.dp.toPx() * zoom, center)
            drawCircle(
                Color(0xFF4E453D),
                1.7.dp.toPx() * zoom,
                center,
                style = Stroke(width = 0.95.dp.toPx() * zoom),
            )
        }
        else -> {
            drawCircle(Color(0xFFFFFDF8), 2.2.dp.toPx() * zoom, center)
            drawCircle(
                Color(0xFF2B2622),
                2.2.dp.toPx() * zoom,
                center,
                style = Stroke(width = 1.1.dp.toPx() * zoom),
            )
        }
    }
}


private class GatedRiver(val path: Path, val rank: Int, val appear: Int?, val vanish: Int?)
private class GatedLake(val path: Path, val major: Boolean, val appear: Int?, val vanish: Int?)

private class HydroLayers(
    val riverStatic: Map<Int, Path>,
    val riverGated: List<GatedRiver>,
    val lakeMajor: Path,
    val lakeMinor: Path,
    val lakeGated: List<GatedLake>,
    val labels: List<HydroLabel>,
)

private fun buildHydroLayers(data: AtlasData, projector: Projector): HydroLayers {
    val riverStatic = HashMap<Int, Path>()
    val riverGated = ArrayList<GatedRiver>()
    data.hydroRivers.forEach { r ->
        val p = Path()
        r.lines.forEach { line ->
            var i = 0
            var first = true
            while (i + 1 < line.size) {
                val x = projector.x(line[i].toDouble())
                val y = projector.y(line[i + 1].toDouble())
                if (first) p.moveTo(x, y) else p.lineTo(x, y)
                first = false
                i += 2
            }
        }
        if (r.appear == null && r.vanish == null) {
            riverStatic.getOrPut(r.rank) { Path() }.addPath(p)
        } else {
            riverGated.add(GatedRiver(p, r.rank, r.appear, r.vanish))
        }
    }
    val lakeMajor = Path()
    val lakeMinor = Path()
    val lakeGated = ArrayList<GatedLake>()
    data.hydroLakes.forEach { lk ->
        val p = Path()
        lk.rings.forEach { ring ->
            var i = 0
            var first = true
            while (i + 1 < ring.size) {
                val x = projector.x(ring[i].toDouble())
                val y = projector.y(ring[i + 1].toDouble())
                if (first) p.moveTo(x, y) else p.lineTo(x, y)
                first = false
                i += 2
            }
            p.close()
        }
        if (lk.appear == null && lk.vanish == null) {
            (if (lk.rank <= 2) lakeMajor else lakeMinor).addPath(p)
        } else {
            lakeGated.add(GatedLake(p, lk.rank <= 2, lk.appear, lk.vanish))
        }
    }
    return HydroLayers(riverStatic, riverGated, lakeMajor, lakeMinor, lakeGated, data.hydroLabels)
}

private fun DrawScope.drawHydro(h: HydroLayers, scale: Float, year: Int) {
    fun vis(a: Int?, v: Int?): Boolean =
        (a == null || year >= a) && (v == null || year < v)

    fun riverWidth(rank: Int): Float = when (rank) {
        1 -> 1.75f
        2 -> 1.25f
        in 3..4 -> 0.9f
        else -> 0.58f
    }
    fun riverColor(rank: Int): Color = when (rank) {
        1 -> Color(0xE6246688)
        2 -> Color(0xD1307292)
        in 3..4 -> Color(0xAD42809E)
        else -> Color(0x705E90A8)
    }

    fun drawLake(path: Path, major: Boolean) {
        drawPath(path, if (major) Color(0xB85296B8) else Color(0x9E609EBC))
        drawPath(
            path,
            if (major) Color(0xE0226282) else Color(0xC72C6C8A),
            style = Stroke(width = (if (major) 0.8f else 0.65f).dp.toPx() / scale),
        )
    }

    if (!h.lakeMajor.isEmpty) drawLake(h.lakeMajor, true)
    if (!h.lakeMinor.isEmpty) drawLake(h.lakeMinor, false)
    h.lakeGated.forEach { if (vis(it.appear, it.vanish)) drawLake(it.path, it.major) }

    // parchment casing under trunk rivers, then strokes from minor to trunk
    h.riverStatic[1]?.let {
        drawPath(it, Color(0x7AF6EFE1), style = Stroke(width = 2.9.dp.toPx() / scale))
    }
    h.riverGated.filter { it.rank == 1 && vis(it.appear, it.vanish) }.forEach {
        drawPath(it.path, Color(0x7AF6EFE1), style = Stroke(width = 2.9.dp.toPx() / scale))
    }
    for (rank in listOf(7, 6, 5, 4, 3, 2, 1)) {
        h.riverStatic[rank]?.let {
            drawPath(it, riverColor(rank), style = Stroke(width = riverWidth(rank).dp.toPx() / scale))
        }
        h.riverGated.filter { it.rank == rank && vis(it.appear, it.vanish) }.forEach {
            drawPath(it.path, riverColor(rank), style = Stroke(width = riverWidth(rank).dp.toPx() / scale))
        }
    }
}
