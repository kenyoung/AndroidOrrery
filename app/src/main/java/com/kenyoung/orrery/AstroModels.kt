package com.kenyoung.orrery

import androidx.compose.ui.graphics.Color

// --- DATA CLASSES ---

data class PlanetElements(
    val name: String,
    val symbol: String,
    val color: Color,
    val L_0: Double, val L_rate: Double,
    val a: Double, val e: Double,
    val i: Double, val w_bar: Double, val N: Double
)

data class PlanetEvents(val rise: Double, val transit: Double, val set: Double)
data class LabelPosition(var x: Float = 0f, var y: Float = 0f, var minDistToCenter: Float = Float.MAX_VALUE, var found: Boolean = false)
data class RaDec(val ra: Double, val dec: Double)

// NEW: Universal State Object
data class BodyState(
    val name: String,
    val jd: Double,
    // Vectors (AU)
    val helioPos: Vector3,
    val geoPos: Vector3,
    // Spherical
    val ra: Double,      // Degrees
    val dec: Double,     // Degrees
    val distSun: Double, // AU
    val distGeo: Double, // AU
    // Ecliptic (Degrees)
    val eclipticLon: Double,
    val eclipticLat: Double
)

data class Vector3(val x: Double, val y: Double, val z: Double)

data class JovianMoonState(
    val x: Double,
    val y: Double,
    val z: Double,
    val shadowX: Double = 0.0,
    val shadowY: Double = 0.0,
    val shadowOnDisk: Boolean = false,
    val eclipsed: Boolean = false
)

// --- CACHE CLASS ---
class AstroCache(
    val startEpochDay: Double,
    val size: Int,
    val sunRise: DoubleArray,
    val sunSet: DoubleArray,
    val astroRise: DoubleArray,
    val astroSet: DoubleArray,
    val planetMap: Map<String, Triple<DoubleArray, DoubleArray, DoubleArray>>
) {
    private fun interp(v1: Double, v2: Double, frac: Double): Double {
        if (v1.isNaN() || v2.isNaN()) return Double.NaN
        var t1 = v1; var t2 = v2
        if (kotlin.math.abs(t1 - t2) > 12.0) { if (t1 > t2) t2 += 24.0 else t1 += 24.0 }
        var res = t1 + (t2 - t1) * frac
        if (res >= 24.0) res -= 24.0
        return res
    }
    fun getSunTimes(epochDay: Double, astro: Boolean): Pair<Double, Double> {
        val offset = epochDay - startEpochDay
        val idx = kotlin.math.floor(offset).toInt()
        val frac = offset - idx
        if (idx < 0 || idx >= size - 1) return Pair(Double.NaN, Double.NaN)
        val riseArr = if (astro) astroRise else sunRise
        val setArr = if (astro) astroSet else sunSet
        return Pair(interp(riseArr[idx], riseArr[idx+1], frac), interp(setArr[idx], setArr[idx+1], frac))
    }
    fun getPlanetEvents(epochDay: Double, name: String): PlanetEvents {
        val arrays = planetMap[name] ?: return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
        val offset = epochDay - startEpochDay
        val idx = kotlin.math.floor(offset).toInt()
        val frac = offset - idx
        if (idx < 0 || idx >= size - 1) return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
        return PlanetEvents(interp(arrays.first[idx], arrays.first[idx+1], frac), interp(arrays.second[idx], arrays.second[idx+1], frac), interp(arrays.third[idx], arrays.third[idx+1], frac))
    }
}

// Global Planet Definitions
fun getOrreryPlanets(): List<PlanetElements> {
    return listOf(
        PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
        PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
        PlanetElements("Earth",   "⊕", Color(0xFF87CEFA), 100.46, 0.985647, 1.00000, 0.01671, 0.000, 102.94, 0.0),
        PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
        PlanetElements("Jupiter", "♃", Color(0xFFFFA500), 34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
        PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
        PlanetElements("Uranus",  "⛢", Color(0xFF20B2AA), 313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
        PlanetElements("Neptune", "♆", Color(0xFF4D4DFF), 304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
    )
}

// Halley Definition (Fallback)
fun getHalleyElement(): PlanetElements {
    return PlanetElements(
        "Halley", "☄", Color(0xFF800080),
        236.35, 0.013126, 17.834, 0.96714,
        162.26, 169.75, 58.42
    )
}
