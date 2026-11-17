package com.jiangshan.atlas

import androidx.compose.ui.graphics.ImageBitmap
import com.jiangshan.atlas.res.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.decodeToImageBitmap

class AtlasEvent(
    val year: Int,
    val headline: String,
    val site: String,
)

class Dynasty(
    val key: String,
    val title: String,
    val from: Int,
    val to: Int,
    val focus: Int,
    val tint: Int,
    val untils: IntArray,
    val snaps: List<String>,
    val milestones: IntArray,
    val events: List<AtlasEvent>,
) {
    fun snapFor(year: Int): String? {
        for (i in untils.indices) if (year < untils[i]) return snaps.getOrNull(i)
        return snaps.lastOrNull()
    }
}

class Feature(
    val title: String,
    val role: String,
    val tint: Int,
    val anchorLon: Float?,
    val anchorLat: Float?,
    val polys: List<IntArray>,
)

class HydroRiver(
    val name: String,
    val rank: Int,
    val lines: List<FloatArray>,
    val appear: Int?,
    val vanish: Int?,
)

class HydroLake(
    val name: String,
    val rank: Int,
    val rings: List<FloatArray>,
    val appear: Int?,
    val vanish: Int?,
)

class HydroLabel(
    val text: String,
    val kind: String,
    val lon: Float,
    val lat: Float,
    val rot: Float,
)

class Snapshot(
    val polities: IntArray,
    val regions: IntArray,
    val caption: String,
)

class City(
    val name: String,
    val modern: String,
    val lon: Float,
    val lat: Float,
    val capital: Boolean,
    val tier: Int,
    val appear: Int?,
    val vanish: Int?,
    val capitalPeriods: List<IntArray> = emptyList(),
)

class AtlasData(
    val dynasties: List<Dynasty>,
    val snapshots: Map<String, Snapshot>,
    val features: List<Feature>,
    val rings: Array<FloatArray>,
    val cities: Map<String, List<City>>,
    val relief: ImageBitmap?,
    val terrainLevels: Map<Int, Set<String>>,
    val terrainTileSize: Int,
    val hydroRivers: List<HydroRiver>,
    val hydroLakes: List<HydroLake>,
    val hydroLabels: List<HydroLabel>,
) {
    fun dynastyFor(year: Int): Dynasty = dynasties.last { year >= it.from }

    fun snapshotKey(dynasty: Dynasty, year: Int): String? = dynasty.snapFor(year)

    companion object {
        suspend fun load(): AtlasData {
            val atlas = Json.parseToJsonElement(
                Res.readBytes("files/atlas/atlas.json").decodeToString()
            ).jsonObject
            val rings = readRings(Res.readBytes("files/atlas/rings.bin"))
            val settlements = Json.parseToJsonElement(
                Res.readBytes("files/atlas/settlements.json").decodeToString()
            ).jsonObject

            val dynasties = atlas["dynasties"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                val phases = o["phases"]?.jsonArray ?: JsonArray(emptyList())
                Dynasty(
                    key = o.str("k"),
                    title = o.str("t"),
                    from = o.int("from"),
                    to = o.int("to"),
                    focus = o.int("focus"),
                    tint = parseColor(o.str("tint"), 0xFF7C5338.toInt()),
                    untils = IntArray(phases.size) { phases[it].jsonObject.int("u") },
                    snaps = phases.map { it.jsonObject.str("s") },
                    milestones = (o["ms"]?.jsonArray ?: JsonArray(emptyList()))
                        .map { it.jsonPrimitive.int }.toIntArray(),
                    events = (o["ev"]?.jsonArray ?: JsonArray(emptyList())).map { row ->
                        val a = row.jsonArray
                        AtlasEvent(
                            year = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                            headline = a.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "",
                            site = a.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    },
                )
            }

            val snapshots = atlas["snapshots"]!!.jsonObject.mapValues { (_, el) ->
                val o = el.jsonObject
                Snapshot(
                    polities = intArray(o["p"]),
                    regions = intArray(o["r"]),

                    caption = o.str("cap"),
                )
            }

            val features = atlas["features"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                val anchor = o["a"]?.jsonArray
                val polys = buildList {
                    o["p"]?.let { add(intArray(it)) }
                    o["m"]?.jsonArray?.forEach { add(intArray(it)) }
                }
                Feature(
                    title = o.str("t"),
                    role = o.str("r"),
                    tint = parseColor(o.str("c"), 0xFF7C5338.toInt()),
                    anchorLon = anchor?.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toFloat(),
                    anchorLat = anchor?.getOrNull(1)?.jsonPrimitive?.doubleOrNull?.toFloat(),
                    polys = polys,
                )
            }

            val cities = settlements.mapValues { (_, el) ->
                el.jsonArray.map { c ->
                    val o = c.jsonObject
                    City(
                        name = o.str("a"),
                        modern = o.str("m"),
                        lon = o["x"]!!.jsonPrimitive.double.toFloat(),
                        lat = o["y"]!!.jsonPrimitive.double.toFloat(),
                        capital = o.int("c") == 1,
                        tier = o["t"]?.jsonPrimitive?.intOrNull ?: 1,
                        appear = o["f"]?.jsonPrimitive?.intOrNull,
                        vanish = o["v"]?.jsonPrimitive?.intOrNull,
                        capitalPeriods = o["cp"]?.jsonArray?.map { intArray(it) } ?: emptyList(),
                    )
                }
            }

            val relief = runCatching {
                Res.readBytes("files/atlas/terrain_relief.webp").decodeToImageBitmap()
            }.getOrNull()

            val terrainIndex = Json.parseToJsonElement(
                Res.readBytes("files/atlas/terrain/index.json").decodeToString()
            ).jsonObject
            val terrainLevels = terrainIndex["levels"]!!.jsonObject.entries.associate { (z, v) ->
                z.toInt() to v.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }
            val tileSize = terrainIndex["tileSize"]?.jsonPrimitive?.intOrNull ?: 512

            val hydro = runCatching {
                Json.parseToJsonElement(
                    Res.readBytes("files/atlas/hydro.json").decodeToString()
                ).jsonObject
            }.getOrNull()

            fun lines(el: kotlinx.serialization.json.JsonArray?): List<FloatArray> =
                el?.map { line ->
                    val out = FloatArray(line.jsonArray.size * 2)
                    line.jsonArray.forEachIndexed { i, pt ->
                        out[i * 2] = pt.jsonArray[0].jsonPrimitive.double.toFloat()
                        out[i * 2 + 1] = pt.jsonArray[1].jsonPrimitive.double.toFloat()
                    }
                    out
                } ?: emptyList()

            val hydroRivers = hydro?.get("rivers")?.jsonArray?.map { el ->
                val o = el.jsonObject
                HydroRiver(
                    name = o.str("n"),
                    rank = o["r"]?.jsonPrimitive?.intOrNull ?: 3,
                    lines = lines(o["l"]?.jsonArray),
                    appear = o["a"]?.jsonPrimitive?.intOrNull,
                    vanish = o["v"]?.jsonPrimitive?.intOrNull,
                )
            } ?: emptyList()
            val hydroLakes = hydro?.get("lakes")?.jsonArray?.map { el ->
                val o = el.jsonObject
                HydroLake(
                    name = o.str("n"),
                    rank = o["r"]?.jsonPrimitive?.intOrNull ?: 3,
                    rings = lines(o["g"]?.jsonArray),
                    appear = o["a"]?.jsonPrimitive?.intOrNull,
                    vanish = o["v"]?.jsonPrimitive?.intOrNull,
                )
            } ?: emptyList()
            val hydroLabels = hydro?.get("labels")?.jsonArray?.map { el ->
                val o = el.jsonObject
                val c = o["c"]?.jsonArray
                HydroLabel(
                    text = o.str("z"),
                    kind = o.str("k"),
                    lon = c?.getOrNull(0)?.jsonPrimitive?.double?.toFloat() ?: 0f,
                    lat = c?.getOrNull(1)?.jsonPrimitive?.double?.toFloat() ?: 0f,
                    rot = o["rot"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
                )
            } ?: emptyList()

            return AtlasData(dynasties, snapshots, features, rings, cities, relief,
                terrainLevels, tileSize, hydroRivers, hydroLakes, hydroLabels)
        }

        private fun JsonObject.str(name: String): String =
            this[name]?.jsonPrimitive?.contentOrNull ?: ""

        private fun JsonObject.int(name: String): Int =
            this[name]?.jsonPrimitive?.intOrNull ?: 0

        private fun intArray(el: kotlinx.serialization.json.JsonElement?): IntArray =
            el?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray() ?: IntArray(0)

        private fun parseColor(hex: String, fallback: Int): Int {
            val h = hex.removePrefix("#")
            if (h.length != 6) return fallback
            return h.toIntOrNull(16)?.let { 0xFF000000.toInt() or it } ?: fallback
        }

        private fun readRings(bytes: ByteArray): Array<FloatArray> {
            require(bytes.size >= 8) { "rings.bin too small" }
            require(bytes[0] == 'J'.code.toByte() && bytes[1] == 'S'.code.toByte() &&
                bytes[2] == 'R'.code.toByte() && bytes[3] == '1'.code.toByte()) { "bad rings magic" }
            var pos = 4
            val count = i32(bytes, pos); pos += 4
            val out = Array(count) { FloatArray(0) }
            for (i in 0 until count) {
                val n = i32(bytes, pos); pos += 4
                var x = i32(bytes, pos); pos += 4
                var y = i32(bytes, pos); pos += 4
                val pts = FloatArray(n * 2)
                if (n > 0) {
                    pts[0] = x / 100f
                    pts[1] = y / 100f
                }
                for (k in 1 until n) {
                    x += i16(bytes, pos); pos += 2
                    y += i16(bytes, pos); pos += 2
                    pts[k * 2] = x / 100f
                    pts[k * 2 + 1] = y / 100f
                }
                out[i] = pts
            }
            return out
        }

        private fun i32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun i16(b: ByteArray, o: Int): Int {
            val v = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            return if (v >= 0x8000) v - 0x10000 else v
        }
    }
}
