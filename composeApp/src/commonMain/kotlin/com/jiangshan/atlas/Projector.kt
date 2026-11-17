package com.jiangshan.atlas

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/** Web-Mercator projection fitted into the 1000x700 canonical viewport. */
class Projector {
    private val west = 66.0
    private val east = 142.0
    private val south = 14.0
    private val north = 55.0
    private val rad = 180.0 / PI
    private val deg = PI / 180.0

    private val scale: Double
    private val padX: Double
    private val padY: Double
    private val northMerc: Double

    init {
        val spanLng = east - west
        val spanLat = (merc(north) - merc(south)) * rad
        scale = min(W.toDouble() / spanLng, H.toDouble() / spanLat)
        padX = (W - spanLng * scale) * 0.5
        padY = (H - spanLat * scale) * 0.5
        northMerc = merc(north)
    }

    fun x(lon: Double): Float = ((lon - west) * scale + padX).toFloat()

    fun y(lat: Double): Float = ((northMerc - merc(lat)) * rad * scale + padY).toFloat()

    fun lon(x: Float): Double = (x - padX) / scale + west

    fun lat(y: Float): Double {
        val m = northMerc - (y - padY) / (scale * rad)
        return (2.0 * atan(exp(m)) - PI * 0.5) * rad
    }

    fun pxPerUnit(): Double = scale

    companion object {
        const val W = 1000f
        const val H = 700f

        private fun merc(lat: Double): Double = ln(tan(PI * 0.25 + lat * (PI / 180.0) * 0.5))
    }
}
