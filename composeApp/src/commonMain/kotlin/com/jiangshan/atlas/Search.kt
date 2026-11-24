package com.jiangshan.atlas

internal enum class SearchKind(val label: String) {
    Place("地名"),
    Event("史事"),
    Era("年号"),
    Dynasty("朝代"),
    Year("年份"),
}

internal class SearchHit(
    val title: String,
    val subtitle: String,
    val year: Int,
    val coords: List<FloatArray>,
    val kind: SearchKind,
    val keywords: String,
)

/** A lightweight, local index of everything the current atlas can locate. */
internal fun AtlasData.buildSearchIndex(): List<SearchHit> {
    val hits = ArrayList<SearchHit>(900)

    dynasties.forEach { dynasty ->
        hits += SearchHit(
            title = dynasty.title,
            subtitle = "${axisYear(dynasty.from)} — ${axisYear(dynasty.to)} · 朝代",
            year = dynasty.focus,
            coords = emptyList(),
            kind = SearchKind.Dynasty,
            keywords = "${dynasty.title} ${dynasty.title}朝 ${dynasty.key}",
        )
    }

    eraSpans.forEach { era ->
        val dynasty = dynasties.firstOrNull { it.key == era.dynastyKey }
        val title = if (era.era.isBlank()) era.ruler else "${era.era} · ${era.ruler}"
        hits += SearchHit(
            title = title,
            subtitle = "${axisYear(era.start)} 年起${dynasty?.let { " · ${it.title}" }.orEmpty()} · 年号",
            year = maxOf(era.start, dynasty?.from ?: era.start),
            coords = emptyList(),
            kind = SearchKind.Era,
            keywords = "${era.era} ${era.ruler} ${dynasty?.title.orEmpty()} 年号",
        )
    }

    dynasties.forEach { dynasty ->
        dynasty.events.forEach { event ->
            hits += SearchHit(
                title = event.headline,
                subtitle = "${axisYear(event.year)} · ${event.site} · 史事",
                year = event.year,
                coords = event.allCoords,
                kind = SearchKind.Event,
                keywords = "${event.headline} ${event.site} ${dynasty.title}",
            )
        }
    }

    val seenCities = HashSet<String>()
    cities.forEach { (dynastyKey, list) ->
        val dynasty = dynasties.firstOrNull { it.key == dynastyKey } ?: return@forEach
        list.forEach { city ->
            val key = "${city.name}|${city.lon}|${city.lat}"
            if (!seenCities.add(key)) return@forEach
            val year = (city.appear ?: dynasty.focus).coerceIn(dynasty.from, dynasty.to)
            val name = if (city.modern.isNotBlank() && city.modern != city.name) {
                "${city.name} · ${city.modern}"
            } else city.name
            hits += SearchHit(
                title = name,
                subtitle = "${axisYear(year)} · ${dynasty.title}${if (city.capital) " · 都城" else ""} · 地名",
                year = year,
                coords = listOf(floatArrayOf(city.lon, city.lat)),
                kind = SearchKind.Place,
                keywords = "${city.name} ${city.modern} ${dynasty.title} 地名 城市",
            )
        }
    }

    return hits
}

private fun parseYearQuery(query: String): Int? {
    val cleaned = query.trim().removeSuffix("年").trim()
    if (cleaned.isEmpty()) return null
    val normalized = when {
        cleaned.startsWith("公元前") -> "-" + cleaned.removePrefix("公元前")
        cleaned.startsWith("前") -> "-" + cleaned.removePrefix("前")
        else -> cleaned
    }
    val year = normalized.toIntOrNull() ?: return null
    return year.takeIf { it != 0 }
}

private fun score(query: String, hit: SearchHit): Int {
    val q = query.lowercase()
    val title = hit.title.lowercase()
    return when {
        title == q -> 1000
        title.startsWith(q) -> 900
        title.contains(q) -> 800
        hit.keywords.lowercase().contains(q) -> 700
        hit.subtitle.lowercase().contains(q) -> 300
        else -> 0
    }
}

internal fun AtlasData.searchHits(
    query: String,
    index: List<SearchHit>,
    minYear: Int = dynasties.first().from,
    maxYear: Int = dynasties.last().to,
): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<SearchHit>()
    parseYearQuery(q)?.takeIf { it in minYear..maxYear }?.let { year ->
        out += SearchHit(
            title = "跳到 ${axisYear(year)}",
            subtitle = "按年份定位 · 年份",
            year = year,
            coords = emptyList(),
            kind = SearchKind.Year,
            keywords = "$year ${axisYear(year)}",
        )
    }
    index.asSequence()
        .map { hit -> hit to score(q, hit) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<SearchHit, Int>> { it.second }.thenBy { it.first.title.length })
        .take(14)
        .forEach { out += it.first }
    return out.distinctBy { "${it.kind}|${it.title}|${it.year}" }.take(14)
}
