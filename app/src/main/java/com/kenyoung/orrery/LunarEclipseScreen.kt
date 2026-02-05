package com.kenyoung.orrery

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import kotlin.math.*

// ============================================================================
// CONSTANTS
// ============================================================================

private const val N_LUNAR_ECLIPSES = 12064

private const val PENUMBRAL_LUNAR_ECLIPSE = 0
private const val PARTIAL_LUNAR_ECLIPSE = 1
private const val TOTAL_LUNAR_ECLIPSE = 2

private const val M_2PI = 2.0 * Math.PI
private const val M_HALF_PI = 0.5 * Math.PI
private const val DEGREES_TO_RADIANS = Math.PI / 180.0
// HOURS_TO_DEGREES now in AstroMath.kt

// Physical constants (CGS units)
private const val AU_KM = 149597870.691
private const val SOLAR_RADIUS = 6.9599e10        // cm
private const val EARTH_EQUATORIAL_RADIUS = 6.378164e8  // cm
private const val EARTH_POLAR_RADIUS = 6.356779e8       // cm
private const val MOON_RADIUS = 1.7382e8                // cm
private const val ATMOSPHERIC_UMBRA_EXPANSION = 1.05

// ============================================================================
// DATA CLASSES
// ============================================================================

data class LunarEclipse(
    val date: Int,           // YYYYMMDD packed
    val tDGE: Float,         // TD of Greatest Eclipse (seconds from midnight)
    val dTMinusUT: Int,      // Dynamical Time - UT (seconds)
    val sarosNum: Short,     // Saros Number
    val type1: Byte,         // Eclipse type (low nibble: 0=pen, 1=partial, 2=total)
    val penMag: Float,       // Penumbral magnitude
    val umbMag: Float,       // Umbral magnitude
    val penDur: Float,       // Penumbral phase duration (minutes)
    val parDur: Float,       // Partial phase duration (minutes)
    val totDur: Float,       // Total phase duration (minutes)
    val zenithLat: Short,    // Latitude of sub-lunar point
    val zenithLon: Short     // Longitude of sub-lunar point
) {
    val eclipseType: Int get() = (type1.toInt() and 0x0f)

    val year: Int get() = date / 0x10000
    val month: Int get() = (date and 0xff00) / 0x100
    val day: Int get() = date and 0xff

    val typeString: String get() = when (eclipseType) {
        TOTAL_LUNAR_ECLIPSE -> "Total"
        PARTIAL_LUNAR_ECLIPSE -> "Partial"
        else -> "Penumbral"
    }

    fun formatDate(): String = "%02d-%02d-%04d".format(day, month, abs(year)) +
            if (year < 0) " BC" else ""

    fun formatListEntry(): String = "${formatDate()} $typeString"
}

data class ShoreSegment(
    val nVertices: Int,
    val lat: ShortArray,
    val lon: ShortArray
)

// Cached Moon positions for key eclipse times (optimization to avoid recalculating)
data class CachedMoonPosition(
    val raHours: Double,   // Right ascension in hours
    val decDeg: Double,    // Declination in degrees
    val sinDec: Double,    // Precomputed sin(declination)
    val cosDec: Double     // Precomputed cos(declination)
)

data class EclipseGMSTCache(
    val penStart: Double,
    val penEnd: Double,
    val parStart: Double,
    val parEnd: Double,
    val totStart: Double,
    val totMid: Double,
    val totEnd: Double
)

data class EclipseMoonCache(
    val penStart: CachedMoonPosition,
    val penEnd: CachedMoonPosition,
    val parStart: CachedMoonPosition?,
    val parEnd: CachedMoonPosition?,
    val totStart: CachedMoonPosition?,
    val totMid: CachedMoonPosition?,
    val totEnd: CachedMoonPosition?
)

// ============================================================================
// GLOBAL DATA STORAGE
// ============================================================================

private var lunarEclipses: List<LunarEclipse>? = null
private var shorelineSegments: List<ShoreSegment>? = null

// ============================================================================
// DATA LOADING
// ============================================================================

private suspend fun loadEclipseData(context: Context): List<LunarEclipse> {
    lunarEclipses?.let { return it }

    return withContext(Dispatchers.IO) {
        val eclipses = mutableListOf<LunarEclipse>()
        context.assets.open("lunarEclipseCannon").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until N_LUNAR_ECLIPSES) {
                val date = buffer.int
                val tDGE = buffer.float
                val dTMinusUT = buffer.int
                val sarosNum = buffer.short
                val type1 = buffer.get()
                val penMag = buffer.float
                val umbMag = buffer.float
                val penDur = buffer.float
                val parDur = buffer.float
                val totDur = buffer.float
                val zenithLat = buffer.short
                val zenithLon = buffer.short

                eclipses.add(
                    LunarEclipse(
                        date, tDGE, dTMinusUT, sarosNum, type1,
                        penMag, umbMag, penDur, parDur, totDur,
                        zenithLat, zenithLon
                    )
                )
            }
        }
        lunarEclipses = eclipses
        eclipses
    }
}

private suspend fun loadShorelineData(context: Context): List<ShoreSegment> {
    shorelineSegments?.let { return it }

    return withContext(Dispatchers.IO) {
        val segments = mutableListOf<ShoreSegment>()
        context.assets.open("shoreline").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.hasRemaining()) {
                val nPairs = buffer.short.toInt()
                if (nPairs <= 0 || buffer.remaining() < nPairs * 4) break

                val lats = ShortArray(nPairs)
                val lons = ShortArray(nPairs)

                for (j in 0 until nPairs) {
                    lons[j] = buffer.short
                    lats[j] = buffer.short
                }
                segments.add(ShoreSegment(nPairs, lats, lons))
            }
        }
        shorelineSegments = segments
        segments
    }
}

// ============================================================================
// ASTRONOMICAL CALCULATIONS (Ported from C)
// ============================================================================

private fun buildTJD(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Double {
    var y = year
    var m = month
    val d = day + hour / 24.0 + minute / 1440.0 + second / 86400.0

    if (m < 3) {
        y -= 1
        m += 12
    }
    val a = y / 100
    val b = 2 - a + a / 4
    val c = (365.25 * y).toInt()
    val dd = (30.6001 * (m + 1)).toInt()
    return (b + c + dd).toDouble() + d + 1720994.5
}

private fun tJDToHHMMSS(tJD: Double, offsetHours: Double = 0.0): Triple<Int, Int, Int> {
    var dayFrac = tJD - tJD.toInt() + 0.5 + offsetHours / 24.0
    while (dayFrac < 0.0) dayFrac += 1.0
    while (dayFrac > 1.0) dayFrac -= 1.0

    val hours = dayFrac * 24.0
    var hH = hours.toInt()
    var mM = ((hours - hH) * 60.0).toInt()
    var sS = ((hours - hH - mM / 60.0) * 3600.0 + 0.5).toInt()

    if (sS >= 60) { sS -= 60; mM++ }
    if (mM >= 60) { mM -= 60; hH++ }
    if (hH >= 24) { hH -= 24 }

    return Triple(hH, mM, sS)
}

private fun doubleNormalize0to2pi(angle: Double): Double {
    var a = angle
    if (abs(a) < 2.0e9) {
        val quotient = (a / M_2PI).toInt()
        a -= quotient * M_2PI
        if (a < 0.0) a += M_2PI
    } else {
        while (a > M_2PI) a -= M_2PI
        while (a < 0.0) a += M_2PI
    }
    return a
}

private fun doubleNormalize0to360(a: Double): Double {
    var temp = a * DEGREES_TO_RADIANS
    temp = doubleNormalize0to2pi(temp)
    return temp / DEGREES_TO_RADIANS
}

private fun doubleNormalizeMinusPiToPi(angle: Double): Double {
    var a = doubleNormalize0to2pi(angle)
    while (a > Math.PI) a -= M_2PI
    return a
}

// Nutation coefficients
private val nutSinCoef = arrayOf(
    floatArrayOf(-171996.0f, -174.2f), floatArrayOf(-13187.0f, -1.6f), floatArrayOf(-2274.0f, -0.2f),
    floatArrayOf(2062.0f, 0.2f), floatArrayOf(1426.0f, -3.4f), floatArrayOf(712.0f, 0.1f),
    floatArrayOf(-517.0f, 1.2f), floatArrayOf(-386.0f, -0.4f), floatArrayOf(-301.0f, 0.0f),
    floatArrayOf(217.0f, -0.5f), floatArrayOf(-158.0f, 0.0f), floatArrayOf(129.0f, 0.1f),
    floatArrayOf(123.0f, 0.0f), floatArrayOf(63.0f, 0.0f), floatArrayOf(63.0f, 0.1f),
    floatArrayOf(-59.0f, 0.0f), floatArrayOf(-58.0f, -0.1f), floatArrayOf(-51.0f, 0.0f),
    floatArrayOf(48.0f, 0.0f), floatArrayOf(46.0f, 0.0f), floatArrayOf(-38.0f, 0.0f),
    floatArrayOf(-31.0f, 0.0f), floatArrayOf(29.0f, 0.0f), floatArrayOf(29.0f, 0.0f),
    floatArrayOf(26.0f, 0.0f), floatArrayOf(-22.0f, 0.0f), floatArrayOf(21.0f, 0.0f),
    floatArrayOf(17.0f, -0.1f), floatArrayOf(16.0f, 0.0f), floatArrayOf(-16.0f, 0.1f),
    floatArrayOf(-15.0f, 0.0f), floatArrayOf(-13.0f, 0.0f), floatArrayOf(-12.0f, 0.0f),
    floatArrayOf(11.0f, 0.0f), floatArrayOf(-10.0f, 0.0f), floatArrayOf(-8.0f, 0.0f),
    floatArrayOf(7.0f, 0.0f), floatArrayOf(-7.0f, 0.0f), floatArrayOf(-7.0f, 0.0f),
    floatArrayOf(-7.0f, 0.0f), floatArrayOf(6.0f, 0.0f), floatArrayOf(6.0f, 0.0f),
    floatArrayOf(6.0f, 0.0f), floatArrayOf(-6.0f, 0.0f), floatArrayOf(-6.0f, 0.0f),
    floatArrayOf(5.0f, 0.0f), floatArrayOf(-5.0f, 0.0f), floatArrayOf(-5.0f, 0.0f),
    floatArrayOf(-5.0f, 0.0f), floatArrayOf(4.0f, 0.0f), floatArrayOf(4.0f, 0.0f),
    floatArrayOf(4.0f, 0.0f), floatArrayOf(-4.0f, 0.0f), floatArrayOf(-4.0f, 0.0f),
    floatArrayOf(-4.0f, 0.0f), floatArrayOf(3.0f, 0.0f), floatArrayOf(-3.0f, 0.0f),
    floatArrayOf(-3.0f, 0.0f), floatArrayOf(-3.0f, 0.0f), floatArrayOf(-3.0f, 0.0f),
    floatArrayOf(-3.0f, 0.0f), floatArrayOf(-3.0f, 0.0f), floatArrayOf(-3.0f, 0.0f)
)

private val nutCosCoef = arrayOf(
    floatArrayOf(92025.0f, 8.9f), floatArrayOf(5736.0f, -3.1f), floatArrayOf(977.0f, -0.5f),
    floatArrayOf(-895.0f, 0.5f), floatArrayOf(54.0f, -0.1f), floatArrayOf(-7.0f, 0.0f),
    floatArrayOf(224.0f, -0.6f), floatArrayOf(200.0f, 0.0f), floatArrayOf(129.0f, -0.1f),
    floatArrayOf(-95.0f, 0.3f), floatArrayOf(0.0f, 0.0f), floatArrayOf(-70.0f, 0.0f),
    floatArrayOf(-53.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(-33.0f, 0.0f),
    floatArrayOf(26.0f, 0.0f), floatArrayOf(32.0f, 0.0f), floatArrayOf(27.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(-24.0f, 0.0f), floatArrayOf(16.0f, 0.0f),
    floatArrayOf(13.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(-12.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(-10.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(-8.0f, 0.0f), floatArrayOf(7.0f, 0.0f),
    floatArrayOf(9.0f, 0.0f), floatArrayOf(7.0f, 0.0f), floatArrayOf(6.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(5.0f, 0.0f), floatArrayOf(3.0f, 0.0f),
    floatArrayOf(-3.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(3.0f, 0.0f),
    floatArrayOf(3.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(-3.0f, 0.0f),
    floatArrayOf(-3.0f, 0.0f), floatArrayOf(3.0f, 0.0f), floatArrayOf(3.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(3.0f, 0.0f), floatArrayOf(3.0f, 0.0f),
    floatArrayOf(3.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f),
    floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f)
)

private val nutMults = arrayOf(
    intArrayOf(0, 0, 0, 0, 1), intArrayOf(-2, 0, 0, 2, 2), intArrayOf(0, 0, 0, 2, 2),
    intArrayOf(0, 0, 0, 0, 2), intArrayOf(0, 1, 0, 0, 0), intArrayOf(0, 0, 1, 0, 0),
    intArrayOf(-2, 1, 0, 2, 2), intArrayOf(0, 0, 0, 2, 1), intArrayOf(0, 0, 1, 2, 2),
    intArrayOf(-2, -1, 0, 2, 2), intArrayOf(-2, 0, 1, 0, 0), intArrayOf(-2, 0, 0, 2, 1),
    intArrayOf(0, 0, -1, 2, 2), intArrayOf(2, 0, 0, 0, 0), intArrayOf(0, 0, 1, 0, 1),
    intArrayOf(2, 0, -1, 2, 2), intArrayOf(0, 0, -1, 0, 1), intArrayOf(0, 0, 1, 2, 1),
    intArrayOf(-2, 0, 2, 0, 0), intArrayOf(0, 0, -2, 2, 1), intArrayOf(2, 0, 0, 2, 2),
    intArrayOf(0, 0, 2, 2, 2), intArrayOf(0, 0, 2, 0, 0), intArrayOf(-2, 0, 1, 2, 2),
    intArrayOf(0, 0, 0, 2, 0), intArrayOf(-2, 0, 0, 2, 0), intArrayOf(0, 0, -1, 2, 1),
    intArrayOf(0, 2, 0, 0, 0), intArrayOf(2, 0, -1, 0, 1), intArrayOf(-2, 2, 0, 2, 2),
    intArrayOf(0, 1, 0, 0, 1), intArrayOf(-2, 0, 1, 0, 1), intArrayOf(0, -1, 0, 0, 1),
    intArrayOf(0, 0, 2, -2, 0), intArrayOf(2, 0, -1, 2, 1), intArrayOf(2, 0, 1, 2, 2),
    intArrayOf(0, 1, 0, 2, 2), intArrayOf(-2, 1, 1, 0, 0), intArrayOf(0, -1, 0, 2, 2),
    intArrayOf(2, 0, 0, 2, 1), intArrayOf(2, 0, 1, 0, 0), intArrayOf(-2, 0, 2, 2, 2),
    intArrayOf(-2, 0, 1, 2, 1), intArrayOf(2, 0, -2, 0, 1), intArrayOf(2, 0, 0, 0, 1),
    intArrayOf(0, -1, 1, 0, 0), intArrayOf(-2, -1, 0, 2, 1), intArrayOf(-2, 0, 0, 0, 1),
    intArrayOf(0, 0, 2, 2, 1), intArrayOf(-2, 0, 2, 0, 1), intArrayOf(-2, 1, 0, 2, 1),
    intArrayOf(0, 0, 1, -2, 0), intArrayOf(-1, 0, 1, 0, 0), intArrayOf(-2, 1, 0, 0, 0),
    intArrayOf(1, 0, 0, 0, 0), intArrayOf(0, 0, 1, 2, 0), intArrayOf(0, 0, -2, 2, 2),
    intArrayOf(-1, -1, 1, 0, 0), intArrayOf(0, 1, 1, 0, 0), intArrayOf(0, -1, 1, 2, 2),
    intArrayOf(2, -1, -1, 2, 2), intArrayOf(0, 0, 3, 2, 2), intArrayOf(2, -1, 0, 2, 2)
)

private data class NutationResult(val deltaPhi: Double, val deltaEps: Double, val eps: Double)

private fun nutation(T: Double): NutationResult {
    var D = 297.85036 + 445267.111480 * T - 0.0019142 * T * T + T * T * T / 189474.0
    D = doubleNormalize0to360(D)
    var M = 357.52772 + 35999.050340 * T - 0.0001603 * T * T - T * T * T / 300000.0
    M = doubleNormalize0to360(M)
    var Mprime = 134.96298 + 477198.867398 * T + 0.0086972 * T * T + T * T * T / 56250.0
    Mprime = doubleNormalize0to360(Mprime)
    var F = 93.27191 + 483202.017538 * T - 0.0036825 * T * T + T * T * T / 327270.0
    F = doubleNormalize0to360(F)
    var omega = 125.04452 - 1934.136261 * T + 0.0020708 * T * T + T * T * T / 450000.0
    omega = doubleNormalize0to360(omega)

    var dP = 0.0
    var dE = 0.0
    for (i in 0 until 63) {
        val arg = (nutMults[i][0] * D + nutMults[i][1] * M + nutMults[i][2] * Mprime +
                nutMults[i][3] * F + nutMults[i][4] * omega) * DEGREES_TO_RADIANS
        dP += (nutSinCoef[i][0] + nutSinCoef[i][1] * T) * sin(arg)
        dE += (nutCosCoef[i][0] + nutCosCoef[i][1] * T) * cos(arg)
    }
    val deltaPhi = dP * 0.0001
    val deltaEps = dE * 0.0001

    val U = T / 100.0
    var eps = -(4680.93 / 3600.0) * U -
            (1.55 / 3600.0) * U * U +
            (1999.25 / 3600.0) * U * U * U -
            (51.38 / 3600.0) * U.pow(4) -
            (249.67 / 3600.0) * U.pow(5) -
            (39.05 / 3600.0) * U.pow(6) +
            (7.12 / 3600.0) * U.pow(7) +
            (27.87 / 3600.0) * U.pow(8) +
            (5.79 / 3600.0) * U.pow(9) +
            (2.45 / 3600.0) * U.pow(10)
    eps += 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 + deltaEps / 3600.0

    return NutationResult(deltaPhi, deltaEps, eps)
}

// Moon position coefficients (Meeus Chapter 47)
private val argMultlr = arrayOf(
    intArrayOf(0, 0, 1, 0), intArrayOf(2, 0, -1, 0), intArrayOf(2, 0, 0, 0), intArrayOf(0, 0, 2, 0),
    intArrayOf(0, 1, 0, 0), intArrayOf(0, 0, 0, 2), intArrayOf(2, 0, -2, 0), intArrayOf(2, -1, -1, 0),
    intArrayOf(2, 0, 1, 0), intArrayOf(2, -1, 0, 0), intArrayOf(0, 1, -1, 0), intArrayOf(1, 0, 0, 0),
    intArrayOf(0, 1, 1, 0), intArrayOf(2, 0, 0, -2), intArrayOf(0, 0, 1, 2), intArrayOf(0, 0, 1, -2),
    intArrayOf(4, 0, -1, 0), intArrayOf(0, 0, 3, 0), intArrayOf(4, 0, -2, 0), intArrayOf(2, 1, -1, 0),
    intArrayOf(2, 1, 0, 0), intArrayOf(1, 0, -1, 0), intArrayOf(1, 1, 0, 0), intArrayOf(2, -1, 1, 0),
    intArrayOf(2, 0, 2, 0), intArrayOf(4, 0, 0, 0), intArrayOf(2, 0, -3, 0), intArrayOf(0, 1, -2, 0),
    intArrayOf(2, 0, -1, 2), intArrayOf(2, -1, -2, 0), intArrayOf(1, 0, 1, 0), intArrayOf(2, -2, 0, 0),
    intArrayOf(0, 1, 2, 0), intArrayOf(0, 2, 0, 0), intArrayOf(2, -2, -1, 0), intArrayOf(2, 0, 1, -2),
    intArrayOf(2, 0, 0, 2), intArrayOf(4, -1, -1, 0), intArrayOf(0, 0, 2, 2), intArrayOf(3, 0, -1, 0),
    intArrayOf(2, 1, 1, 0), intArrayOf(4, -1, -2, 0), intArrayOf(0, 2, -1, 0), intArrayOf(2, 2, -1, 0),
    intArrayOf(2, 1, -2, 0), intArrayOf(2, -1, 0, -2), intArrayOf(4, 0, 1, 0), intArrayOf(0, 0, 4, 0),
    intArrayOf(4, -1, 0, 0), intArrayOf(1, 0, -2, 0), intArrayOf(2, 1, 0, -2), intArrayOf(0, 0, 2, -2),
    intArrayOf(1, 1, 1, 0), intArrayOf(3, 0, -2, 0), intArrayOf(4, 0, -3, 0), intArrayOf(2, -1, 2, 0),
    intArrayOf(0, 2, 1, 0), intArrayOf(1, 1, -1, 0), intArrayOf(2, 0, 3, 0), intArrayOf(2, 0, -1, -2)
)

private val sigmalCoef = intArrayOf(
    6288774, 1274027, 658314, 213618, -185116, -114332, 58793, 57066,
    53322, 45758, -40923, -34720, -30383, 15327, -12528, 10980,
    10675, 10034, 8548, -7888, -6766, -5163, 4987, 4036,
    3994, 3861, 3665, -2689, -2602, 2390, -2348, 2236,
    -2120, -2069, 2048, -1773, -1595, 1215, -1110, -892,
    -810, 759, -713, -700, 691, 596, 549, 537,
    520, -487, -399, -381, 351, -340, 330, 327,
    -323, 299, 294, 0
)

private val sigmarCoef = intArrayOf(
    -20905355, -3699111, -2955968, -569925, 48888, -3149, 246158, -152138,
    -170733, -204586, -129620, 108743, 104755, 10321, 0, 79661,
    -34782, -23210, -21636, 24208, 30824, -8379, -16675, -12831,
    -10445, -11650, 14403, -7003, 0, 10056, 6322, -9884,
    5751, 0, -4950, 4130, 0, -3958, 0, 3258,
    2616, -1897, -2117, 2354, 0, 0, -1423, -1117,
    -1571, -1739, 0, -4421, 0, 0, 0, 0,
    1165, 0, 0, 8752
)

private val argMultb = arrayOf(
    intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, 1), intArrayOf(0, 0, 1, -1), intArrayOf(2, 0, 0, -1),
    intArrayOf(2, 0, -1, 1), intArrayOf(2, 0, -1, -1), intArrayOf(2, 0, 0, 1), intArrayOf(0, 0, 2, 1),
    intArrayOf(2, 0, 1, -1), intArrayOf(0, 0, 2, -1), intArrayOf(2, -1, 0, -1), intArrayOf(2, 0, -2, -1),
    intArrayOf(2, 0, 1, 1), intArrayOf(2, 1, 0, -1), intArrayOf(2, -1, -1, 1), intArrayOf(2, -1, 0, 1),
    intArrayOf(2, -1, -1, -1), intArrayOf(0, 1, -1, -1), intArrayOf(4, 0, -1, -1), intArrayOf(0, 1, 0, 1),
    intArrayOf(0, 0, 0, 3), intArrayOf(0, 1, -1, 1), intArrayOf(1, 0, 0, 1), intArrayOf(0, 1, 1, 1),
    intArrayOf(0, 1, 1, -1), intArrayOf(0, 1, 0, -1), intArrayOf(1, 0, 0, -1), intArrayOf(0, 0, 3, 1),
    intArrayOf(4, 0, 0, -1), intArrayOf(4, 0, -1, 1), intArrayOf(0, 0, 1, -3), intArrayOf(4, 0, -2, 1),
    intArrayOf(2, 0, 0, -3), intArrayOf(2, 0, 2, -1), intArrayOf(2, -1, 1, -1), intArrayOf(2, 0, -2, 1),
    intArrayOf(0, 0, 3, -1), intArrayOf(2, 0, 2, 1), intArrayOf(2, 0, -3, -1), intArrayOf(2, 1, -1, 1),
    intArrayOf(2, 1, 0, 1), intArrayOf(4, 0, 0, 1), intArrayOf(2, -1, 1, 1), intArrayOf(2, -2, 0, -1),
    intArrayOf(0, 0, 1, 3), intArrayOf(2, 1, 1, -1), intArrayOf(1, 1, 0, -1), intArrayOf(1, 1, 0, 1),
    intArrayOf(0, 1, -2, -1), intArrayOf(2, 1, -1, -1), intArrayOf(1, 0, 1, 1), intArrayOf(2, -1, -2, -1),
    intArrayOf(0, 1, 2, 1), intArrayOf(4, 0, -2, -1), intArrayOf(4, -1, -1, -1), intArrayOf(1, 0, 1, -1),
    intArrayOf(4, 0, 1, -1), intArrayOf(1, 0, -1, -1), intArrayOf(4, -1, 0, -1), intArrayOf(2, -2, 0, 1)
)

private val sigmabCoef = intArrayOf(
    5128122, 280602, 277693, 173237, 55413, 46271, 32573, 17198,
    9266, 8822, 8216, 4324, 4200, -3359, 2463, 2211,
    2065, -1870, 1828, -1794, -1749, -1565, -1491, -1475,
    -1410, -1344, -1335, 1107, 1021, 833, 777, 671,
    607, 596, 491, -451, 439, 422, 421, -366,
    -351, 331, 315, 302, -283, -229, 223, 223,
    -220, -220, -185, 181, -177, 176, 166, -164,
    132, -119, 115, 107
)

private data class MoonPositionResult(
    val ra: Double,      // Right ascension in radians
    val dec: Double,     // Declination in radians
    val eLong: Double,   // Ecliptic longitude in radians
    val eLat: Double,    // Ecliptic latitude in radians
    val distanceKM: Double
)

private fun moonPosition(jDE: Double): MoonPositionResult {
    val T = (jDE - 2451545.0) / 36525.0
    val nut = nutation(T)

    var Lprime = 218.3164477 + 481267.88123421 * T - 0.00157860 * T * T +
            T * T * T / 538841.0 - T * T * T * T / 65194000.0
    Lprime = doubleNormalize0to360(Lprime)

    var D = 297.8501921 + 445267.1114034 * T - 0.0018819 * T * T +
            T * T * T / 545868.0 - T * T * T * T / 113065000.0
    D = doubleNormalize0to360(D)

    var M = 357.5291092 + 35999.0502909 * T - 0.0001536 * T * T + T * T * T / 24490000.0
    M = doubleNormalize0to360(M)

    var Mprime = 134.9633964 + 477198.8675055 * T + 0.0087414 * T * T +
            T * T * T / 69699.0 - T * T * T * T / 14712000.0
    Mprime = doubleNormalize0to360(Mprime)

    var F = 93.2720950 + 483202.0175233 * T - 0.0036539 * T * T -
            T * T * T / 3526000.0 - T * T * T * T / 863310000.0
    F = doubleNormalize0to360(F)

    var A1 = 119.75 + 131.849 * T
    A1 = doubleNormalize0to360(A1)
    var A2 = 53.09 + 479264.290 * T
    A2 = doubleNormalize0to360(A2)
    var A3 = 313.45 + 481266.484 * T
    A3 = doubleNormalize0to360(A3)

    var sigmal = 0.0
    var sigmar = 0.0
    var sigmab = 0.0
    val E = 1.0 - 0.002516 * T - 0.0000074 * T * T

    val dD = D * DEGREES_TO_RADIANS
    val dM = M * DEGREES_TO_RADIANS
    val dMprime = Mprime * DEGREES_TO_RADIANS
    val dF = F * DEGREES_TO_RADIANS

    for (i in 0 until 60) {
        val alpha = when {
            argMultlr[i][1] == 1 || argMultlr[i][1] == -1 -> E
            argMultlr[i][1] == 2 || argMultlr[i][1] == -2 -> E * E
            else -> 1.0
        }

        sigmal += alpha * sigmalCoef[i] * sin(
            argMultlr[i][0] * dD + argMultlr[i][1] * dM +
                    argMultlr[i][2] * dMprime + argMultlr[i][3] * dF
        )
        sigmar += alpha * sigmarCoef[i] * cos(
            argMultlr[i][0] * dD + argMultlr[i][1] * dM +
                    argMultlr[i][2] * dMprime + argMultlr[i][3] * dF
        )

        val alphaB = when {
            argMultb[i][1] == 1 || argMultb[i][1] == -1 -> E
            argMultb[i][1] == 2 || argMultb[i][1] == -2 -> E * E
            else -> 1.0
        }

        sigmab += alphaB * sigmabCoef[i] * sin(
            argMultb[i][0] * dD + argMultb[i][1] * dM +
                    argMultb[i][2] * dMprime + argMultb[i][3] * dF
        )
    }

    sigmal += 3958.0 * sin(A1 * DEGREES_TO_RADIANS) +
            1962.0 * sin((Lprime - F) * DEGREES_TO_RADIANS) +
            318.0 * sin(A2 * DEGREES_TO_RADIANS)

    sigmab += -2235.0 * sin(Lprime * DEGREES_TO_RADIANS) +
            382.0 * sin(A3 * DEGREES_TO_RADIANS) +
            175.0 * sin((A1 - F) * DEGREES_TO_RADIANS) +
            175.0 * sin((A1 + F) * DEGREES_TO_RADIANS) +
            127.0 * sin((Lprime - Mprime) * DEGREES_TO_RADIANS) -
            115.0 * sin((Lprime + Mprime) * DEGREES_TO_RADIANS)

    var lambda = Lprime + sigmal * 1.0e-6 + nut.deltaPhi / 3600.0
    lambda = doubleNormalize0to360(lambda)
    var beta = sigmab * 1.0e-6
    beta = doubleNormalize0to360(beta)
    val delta = 385000.56 + sigmar * 1.0e-3

    val epsRad = nut.eps * DEGREES_TO_RADIANS
    val lambdaRad = lambda * DEGREES_TO_RADIANS
    val betaRad = beta * DEGREES_TO_RADIANS

    var ra = atan2(sin(lambdaRad) * cos(epsRad) - tan(betaRad) * sin(epsRad), cos(lambdaRad))
    ra = doubleNormalize0to2pi(ra)
    val dec = asin(sin(betaRad) * cos(epsRad) + cos(betaRad) * sin(epsRad) * sin(lambdaRad))

    return MoonPositionResult(ra, dec, lambdaRad, betaRad, delta)
}

private data class SunPositionResult(
    val ra: Double,      // Right ascension in radians
    val dec: Double,     // Declination in radians
    val distanceKM: Double
)

private fun sunPosition(tJD: Double): SunPositionResult {
    val T = (tJD - 2451545.0) / 36525.0
    val T2 = T * T
    val T3 = T2 * T

    var L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T2
    L0 = doubleNormalize0to360(L0)

    var M = 357.52911 + 35999.05029 * T - 0.0001537 * T2
    M = doubleNormalize0to360(M)

    val e = 0.016708634 - 0.000042037 * T - 0.0000001267 * T2

    val C = (1.914602 - 0.004817 * T - 0.000014 * T2) * sin(M * DEGREES_TO_RADIANS) +
            (0.019993 - 0.000101 * T) * sin(2.0 * M * DEGREES_TO_RADIANS) +
            0.000289 * sin(3.0 * M * DEGREES_TO_RADIANS)

    val sunLong = L0 + C
    val sunAnomaly = M + C

    val sunR = (1.000001018 * (1.0 - e * e)) /
            (1.0 + e * cos(sunAnomaly * DEGREES_TO_RADIANS))

    val omega = 125.04 - 1934.136 * T
    val lambda = sunLong - 0.00569 - 0.00478 * sin(omega * DEGREES_TO_RADIANS)

    val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
            (46.8150 / 3600.0) * T -
            (0.00059 / 3600.0) * T2 +
            (0.001813 / 3600.0) * T3
    val eps = eps0 + 0.00256 * cos(omega * DEGREES_TO_RADIANS)

    val lambdaRad = lambda * DEGREES_TO_RADIANS
    val epsRad = eps * DEGREES_TO_RADIANS

    var ra = atan2(cos(epsRad) * sin(lambdaRad), cos(lambdaRad))
    ra = doubleNormalize0to2pi(ra)
    val dec = asin(sin(epsRad) * sin(lambdaRad))

    return SunPositionResult(ra, dec, sunR * AU_KM)
}

// Check if Moon is above horizon - uses shared calculateAltitude from AstroMath.kt
// Parameters: tJD = Julian Date, latDeg/lonDeg = observer position in DEGREES
private fun moonAboveHorizon(tJD: Double, latDeg: Double, lonDeg: Double): Boolean {
    val moon = moonPosition(tJD)
    val moonRaHours = Math.toDegrees(moon.ra) / 15.0
    val moonDecDeg = Math.toDegrees(moon.dec)
    val lstHours = calculateLSTHours(tJD, lonDeg)
    val haHours = lstHours - moonRaHours
    val altDeg = calculateAltitude(haHours, latDeg, moonDecDeg)
    return altDeg > 0.125
}

// Fast version using pre-cached Moon position (avoids expensive moonPosition call)
private fun moonAboveHorizonCached(
    tJD: Double,
    latDeg: Double,
    lonDeg: Double,
    moonRaHours: Double,
    moonDecDeg: Double
): Boolean {
    val lstHours = calculateLSTHours(tJD, lonDeg)
    val haHours = lstHours - moonRaHours
    val altDeg = calculateAltitude(haHours, latDeg, moonDecDeg)
    return altDeg > 0.125
}

// Fast version using linearly interpolated Moon position between two cached endpoints
// This avoids expensive moonPosition calls - moon RA/Dec change slowly during an eclipse
private fun moonAboveHorizonInterpolated(
    tJD: Double,
    latDeg: Double,
    lonDeg: Double,
    startTJD: Double,
    endTJD: Double,
    startRaHours: Double,
    startDecDeg: Double,
    endRaHours: Double,
    endDecDeg: Double
): Boolean {
    val t = ((tJD - startTJD) / (endTJD - startTJD)).coerceIn(0.0, 1.0)
    val moonRaHours = startRaHours + t * (endRaHours - startRaHours)
    val moonDecDeg = startDecDeg + t * (endDecDeg - startDecDeg)
    val lstHours = calculateLSTHours(tJD, lonDeg)
    val haHours = lstHours - moonRaHours
    val altDeg = calculateAltitude(haHours, latDeg, moonDecDeg)
    return altDeg > 0.125
}

// Build cache of Moon positions for key eclipse times
private fun buildMoonCache(
    penStartTJD: Double,
    penEndTJD: Double,
    parStartTJD: Double,
    parEndTJD: Double,
    totStartTJD: Double,
    totEndTJD: Double,
    eclipseType: Int
): EclipseMoonCache {
    fun cacheMoon(tJD: Double): CachedMoonPosition {
        val moon = moonPosition(tJD)
        val decDeg = Math.toDegrees(moon.dec)
        val decRad = moon.dec
        return CachedMoonPosition(
            raHours = Math.toDegrees(moon.ra) / 15.0,
            decDeg = decDeg,
            sinDec = sin(decRad),
            cosDec = cos(decRad)
        )
    }

    return EclipseMoonCache(
        penStart = cacheMoon(penStartTJD),
        penEnd = cacheMoon(penEndTJD),
        parStart = if (eclipseType >= PARTIAL_LUNAR_ECLIPSE) cacheMoon(parStartTJD) else null,
        parEnd = if (eclipseType >= PARTIAL_LUNAR_ECLIPSE) cacheMoon(parEndTJD) else null,
        totStart = if (eclipseType == TOTAL_LUNAR_ECLIPSE) cacheMoon(totStartTJD) else null,
        totMid = if (eclipseType == TOTAL_LUNAR_ECLIPSE) cacheMoon((totStartTJD + totEndTJD) / 2.0) else null,
        totEnd = if (eclipseType == TOTAL_LUNAR_ECLIPSE) cacheMoon(totEndTJD) else null
    )
}

// Build cache of GMST values for key eclipse times
private fun buildGMSTCache(
    penStartTJD: Double,
    penEndTJD: Double,
    parStartTJD: Double,
    parEndTJD: Double,
    totStartTJD: Double,
    totEndTJD: Double
): EclipseGMSTCache {
    return EclipseGMSTCache(
        penStart = calculateGMST(penStartTJD),
        penEnd = calculateGMST(penEndTJD),
        parStart = calculateGMST(parStartTJD),
        parEnd = calculateGMST(parEndTJD),
        totStart = calculateGMST(totStartTJD),
        totMid = calculateGMST((totStartTJD + totEndTJD) / 2.0),
        totEnd = calculateGMST(totEndTJD)
    )
}

// Optimized horizon check using precomputed values
private fun moonAboveHorizonOptimized(
    gmst: Double,
    lonDeg: Double,
    sinLat: Double,
    cosLat: Double,
    moonRaHours: Double,
    sinDec: Double,
    cosDec: Double
): Boolean {
    var lst = gmst + lonDeg / 15.0
    if (lst >= 24.0) lst -= 24.0
    if (lst < 0.0) lst += 24.0
    val haRad = Math.toRadians((lst - moonRaHours) * 15.0)
    val sinAlt = sinLat * sinDec + cosLat * cosDec * cos(haRad)
    return sinAlt > 0.00218  // sin(0.125°) ≈ 0.00218
}

// Optimized visibility level check using all precomputed values
private fun getVisibilityLevelOptimized(
    lonDeg: Double,
    sinLat: Double,
    cosLat: Double,
    eclipseType: Int,
    moonCache: EclipseMoonCache,
    gmstCache: EclipseGMSTCache
): Int {
    if (eclipseType == TOTAL_LUNAR_ECLIPSE && moonCache.totStart != null && moonCache.totMid != null && moonCache.totEnd != null) {
        if (moonAboveHorizonOptimized(gmstCache.totStart, lonDeg, sinLat, cosLat, moonCache.totStart.raHours, moonCache.totStart.sinDec, moonCache.totStart.cosDec) ||
            moonAboveHorizonOptimized(gmstCache.totEnd, lonDeg, sinLat, cosLat, moonCache.totEnd.raHours, moonCache.totEnd.sinDec, moonCache.totEnd.cosDec) ||
            moonAboveHorizonOptimized(gmstCache.totMid, lonDeg, sinLat, cosLat, moonCache.totMid.raHours, moonCache.totMid.sinDec, moonCache.totMid.cosDec)) {
            return 3
        }
    }

    if (eclipseType >= PARTIAL_LUNAR_ECLIPSE && moonCache.parStart != null && moonCache.parEnd != null) {
        if (moonAboveHorizonOptimized(gmstCache.parStart, lonDeg, sinLat, cosLat, moonCache.parStart.raHours, moonCache.parStart.sinDec, moonCache.parStart.cosDec) ||
            moonAboveHorizonOptimized(gmstCache.parEnd, lonDeg, sinLat, cosLat, moonCache.parEnd.raHours, moonCache.parEnd.sinDec, moonCache.parEnd.cosDec)) {
            return 2
        }
    }

    if (moonAboveHorizonOptimized(gmstCache.penStart, lonDeg, sinLat, cosLat, moonCache.penStart.raHours, moonCache.penStart.sinDec, moonCache.penStart.cosDec) ||
        moonAboveHorizonOptimized(gmstCache.penEnd, lonDeg, sinLat, cosLat, moonCache.penEnd.raHours, moonCache.penEnd.sinDec, moonCache.penEnd.cosDec)) {
        return 1
    }

    return 0
}

// Represents a longitude range where moon is visible (can wrap around ±180°)
private data class LonRange(val lon1: Double, val lon2: Double, val alwaysUp: Boolean, val neverUp: Boolean)

// Calculate longitude range where moon is above horizon for given latitude and eclipse time
private fun getMoonVisibleLonRange(
    sinLat: Double,
    cosLat: Double,
    gmst: Double,
    moonRaHours: Double,
    sinDec: Double,
    cosDec: Double
): LonRange {
    // cos(HA) = -tan(lat) * tan(dec) = -sinLat/cosLat * sinDec/cosDec
    // Avoid division by zero
    if (abs(cosLat) < 1e-10 || abs(cosDec) < 1e-10) {
        // At poles or moon at celestial pole - check if moon is up
        val sinAlt = sinLat * sinDec
        return if (sinAlt > 0) LonRange(0.0, 0.0, alwaysUp = true, neverUp = false)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)
    }

    val cosHA = -(sinLat * sinDec) / (cosLat * cosDec)

    if (cosHA <= -1.0) {
        // Moon is always above horizon at this latitude (circumpolar)
        return LonRange(0.0, 0.0, alwaysUp = true, neverUp = false)
    }
    if (cosHA >= 1.0) {
        // Moon never rises at this latitude
        return LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)
    }

    // Two horizon crossings
    val ha = Math.toDegrees(acos(cosHA)) / 15.0  // HA in hours

    // HA = LST - RA = (GMST + lon/15) - RA
    // lon = (HA + RA - GMST) * 15
    // Rising: HA = -ha (moon moving up), Setting: HA = +ha (moon moving down)
    // Moon is UP when -ha < HA < +ha

    val lonRising = ((-ha + moonRaHours - gmst) * 15.0)
    val lonSetting = ((ha + moonRaHours - gmst) * 15.0)

    // Normalize to -180 to 180
    fun normLon(lon: Double): Double {
        var l = lon
        while (l > 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    return LonRange(normLon(lonRising), normLon(lonSetting), alwaysUp = false, neverUp = false)
}

// Check if a longitude is within the visible range
private fun lonInRange(lonDeg: Double, range: LonRange): Boolean {
    if (range.alwaysUp) return true
    if (range.neverUp) return false

    // Range goes from lon1 (rising) to lon2 (setting)
    // Moon is up when longitude is between rising and setting
    val lon1 = range.lon1
    val lon2 = range.lon2

    return if (lon1 <= lon2) {
        lonDeg >= lon1 && lonDeg <= lon2
    } else {
        // Range wraps around ±180°
        lonDeg >= lon1 || lonDeg <= lon2
    }
}

// Fast visibility check using cached Moon positions
private fun getVisibilityLevelCached(
    latRad: Double, lonRad: Double,
    penStartTJD: Double, penEndTJD: Double,
    parStartTJD: Double, parEndTJD: Double,
    totStartTJD: Double, totEndTJD: Double,
    eclipseType: Int,
    cache: EclipseMoonCache
): Int {
    val latDeg = Math.toDegrees(latRad)
    val lonDeg = Math.toDegrees(lonRad)

    if (eclipseType == TOTAL_LUNAR_ECLIPSE && cache.totStart != null && cache.totMid != null && cache.totEnd != null) {
        if (moonAboveHorizonCached(totStartTJD, latDeg, lonDeg, cache.totStart.raHours, cache.totStart.decDeg) ||
            moonAboveHorizonCached(totEndTJD, latDeg, lonDeg, cache.totEnd.raHours, cache.totEnd.decDeg) ||
            moonAboveHorizonCached((totStartTJD + totEndTJD) / 2.0, latDeg, lonDeg, cache.totMid.raHours, cache.totMid.decDeg)) {
            return 3
        }
    }

    if (eclipseType >= PARTIAL_LUNAR_ECLIPSE && cache.parStart != null && cache.parEnd != null) {
        if (moonAboveHorizonCached(parStartTJD, latDeg, lonDeg, cache.parStart.raHours, cache.parStart.decDeg) ||
            moonAboveHorizonCached(parEndTJD, latDeg, lonDeg, cache.parEnd.raHours, cache.parEnd.decDeg)) {
            return 2
        }
    }

    if (moonAboveHorizonCached(penStartTJD, latDeg, lonDeg, cache.penStart.raHours, cache.penStart.decDeg) ||
        moonAboveHorizonCached(penEndTJD, latDeg, lonDeg, cache.penEnd.raHours, cache.penEnd.decDeg)) {
        return 1
    }

    return 0
}

// ============================================================================
// ECLIPSE FILTERING AND VISIBILITY
// ============================================================================

private fun isEclipseVisible(eclipse: LunarEclipse, latitude: Double, longitude: Double): Boolean {
    val eclipseUTHours = (eclipse.tDGE - eclipse.dTMinusUT) / 3600.0
    var hH = eclipseUTHours.toInt()
    var mM = ((eclipseUTHours - hH) * 60.0).toInt()
    var sS = ((eclipseUTHours - hH - mM / 60.0) * 3600.0 + 0.5).toInt()
    if (sS >= 60) { sS -= 60; mM++ }
    if (mM >= 60) { mM -= 60; hH++ }

    val eclipseTJD = buildTJD(eclipse.year, eclipse.month, eclipse.day, hH, mM, sS)
    val penEclipseStartTJD = eclipseTJD - eclipse.penDur / 2880.0
    val penEclipseEndTJD = eclipseTJD + eclipse.penDur / 2880.0

    // Check if moon is above horizon at key points during eclipse
    // If up at start or end, it's visible
    if (moonAboveHorizon(penEclipseStartTJD, latitude, longitude)) return true
    if (moonAboveHorizon(penEclipseEndTJD, latitude, longitude)) return true

    // If down at both start and end, check intermediate points to catch
    // the case where moon rises and sets during the eclipse
    val duration = penEclipseEndTJD - penEclipseStartTJD
    if (moonAboveHorizon(penEclipseStartTJD + duration * 0.25, latitude, longitude)) return true
    if (moonAboveHorizon(penEclipseStartTJD + duration * 0.50, latitude, longitude)) return true
    if (moonAboveHorizon(penEclipseStartTJD + duration * 0.75, latitude, longitude)) return true

    return false
}

private fun filterEclipses(
    eclipses: List<LunarEclipse>,
    startYear: Int,
    endYear: Int,
    showPenumbral: Boolean,
    showPartial: Boolean,
    showTotal: Boolean,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double
): List<LunarEclipse> {
    return eclipses.filter { eclipse ->
        val year = eclipse.year
        if (year < startYear || year > endYear) return@filter false

        val typeOk = when (eclipse.eclipseType) {
            PENUMBRAL_LUNAR_ECLIPSE -> showPenumbral
            PARTIAL_LUNAR_ECLIPSE -> showPartial
            TOTAL_LUNAR_ECLIPSE -> showTotal
            else -> false
        }
        if (!typeOk) return@filter false

        if (localOnly && !isEclipseVisible(eclipse, latitude, longitude)) {
            return@filter false
        }

        true
    }
}

// ============================================================================
// COMPOSABLES
// ============================================================================

@Composable
fun LunarEclipseScreen(
    latitude: Double,
    longitude: Double
) {
    val context = LocalContext.current

    var eclipses by remember { mutableStateOf<List<LunarEclipse>?>(null) }
    var shoreline by remember { mutableStateOf<List<ShoreSegment>?>(null) }
    var selectedEclipse by remember { mutableStateOf<LunarEclipse?>(null) }

    // Decade range state
    var decadeStart by remember { mutableStateOf(2020) }

    // Filter toggles
    var showPenumbral by remember { mutableStateOf(false) }
    var showPartial by remember { mutableStateOf(true) }
    var showTotal by remember { mutableStateOf(true) }
    var localOnly by remember { mutableStateOf(false) }

    // Load data
    LaunchedEffect(Unit) {
        eclipses = loadEclipseData(context)
        shoreline = loadShorelineData(context)
    }

    if (eclipses == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    if (selectedEclipse != null) {
        EclipseDetailView(
            eclipse = selectedEclipse!!,
            shoreline = shoreline ?: emptyList(),
            latitude = latitude,
            longitude = longitude,
            onBack = { selectedEclipse = null }
        )
    } else {
        EclipseSelectionView(
            eclipses = eclipses!!,
            decadeStart = decadeStart,
            showPenumbral = showPenumbral,
            showPartial = showPartial,
            showTotal = showTotal,
            localOnly = localOnly,
            latitude = latitude,
            longitude = longitude,
            onDecadeChange = { decadeStart = it },
            onPenumbralToggle = { showPenumbral = it },
            onPartialToggle = { showPartial = it },
            onTotalToggle = { showTotal = it },
            onLocalOnlyToggle = { localOnly = it },
            onEclipseSelected = { selectedEclipse = it }
        )
    }
}

@Composable
private fun EclipseSelectionView(
    eclipses: List<LunarEclipse>,
    decadeStart: Int,
    showPenumbral: Boolean,
    showPartial: Boolean,
    showTotal: Boolean,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double,
    onDecadeChange: (Int) -> Unit,
    onPenumbralToggle: (Boolean) -> Unit,
    onPartialToggle: (Boolean) -> Unit,
    onTotalToggle: (Boolean) -> Unit,
    onLocalOnlyToggle: (Boolean) -> Unit,
    onEclipseSelected: (LunarEclipse) -> Unit
) {
    val decadeEnd = decadeStart + 10
    val minDecade = -2000
    val maxDecade = 2990

    val canGoBack = decadeStart > minDecade
    val canGoForward = decadeStart < maxDecade

    val filteredEclipses = remember(eclipses, decadeStart, showPenumbral, showPartial, showTotal, localOnly, latitude, longitude) {
        filterEclipses(eclipses, decadeStart, decadeEnd, showPenumbral, showPartial, showTotal, localOnly, latitude, longitude)
    }

    fun formatYear(year: Int): String {
        return if (year <= 0) "${abs(year - 1)} BC" else "$year"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Title
        Text(
            text = "Lunar Eclipses",
            color = Color.Yellow,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Decade selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onDecadeChange(decadeStart - 10) },
                enabled = canGoBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    disabledContainerColor = Color(0xFF333333)
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("-", fontSize = 40.sp, color = if (canGoBack) Color.White else Color.Gray)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row {
                Text(
                    text = formatYear(decadeStart),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = " → ",
                    color = Color.Yellow,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatYear(decadeEnd),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = { onDecadeChange(decadeStart + 10) },
                enabled = canGoForward,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    disabledContainerColor = Color(0xFF333333)
                ),
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 40.sp, color = if (canGoForward) Color.White else Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = showPenumbral,
                onClick = { onPenumbralToggle(!showPenumbral) },
                label = { Text("Penumbral", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A4A4A),
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.LightGray,
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                selected = showPartial,
                onClick = { onPartialToggle(!showPartial) },
                label = { Text("Partial", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A4A4A),
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.LightGray,
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                selected = showTotal,
                onClick = { onTotalToggle(!showTotal) },
                label = { Text("Total", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A4A4A),
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.LightGray,
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                selected = localOnly,
                onClick = { onLocalOnlyToggle(!localOnly) },
                label = { Text("Local only", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A4A4A),
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.LightGray,
                    selectedLabelColor = Color.White
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Eclipse count
        Text(
            text = "${filteredEclipses.size} eclipses found",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Eclipse list
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredEclipses) { eclipse ->
                EclipseListItem(
                    eclipse = eclipse,
                    localOnly = localOnly,
                    latitude = latitude,
                    longitude = longitude,
                    onClick = { onEclipseSelected(eclipse) }
                )
            }
        }
    }
}

@Composable
private fun EclipseListItem(
    eclipse: LunarEclipse,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double,
    onClick: () -> Unit
) {
    val typeColor = when (eclipse.eclipseType) {
        TOTAL_LUNAR_ECLIPSE -> Color(0xFF00FF00)  // Green - most desirable to observe
        PARTIAL_LUNAR_ECLIPSE -> Color(0xFFFFD93D)
        else -> Color(0xFFAAAAAA)
    }

    // Check visibility only when localOnly filter is off
    val isVisible = localOnly || isEclipseVisible(eclipse, latitude, longitude)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = eclipse.formatListEntry(),
            color = typeColor,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        )
        if (!isVisible) {
            Text(
                text = " (not visible)",
                color = Color.Red,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
}

@Composable
private fun EclipseDetailView(
    eclipse: LunarEclipse,
    shoreline: List<ShoreSegment>,
    latitude: Double,
    longitude: Double,
    onBack: () -> Unit
) {
    var showCanvas by remember { mutableStateOf(false) }
    var useStandardTime by remember { mutableStateOf(false) }

    // Calculate standard timezone offset from longitude (15° per hour)
    val standardTimeOffsetHours = kotlin.math.round(longitude / 15.0)

    // Get timezone abbreviation (e.g., "CST", "EST")
    val timeZone = TimeZone.getDefault()
    val timeZoneAbbreviation = timeZone.getDisplayName(false, TimeZone.SHORT)

    // Delay showing canvas to allow loading indicator to render first
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        showCanvas = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (showCanvas) {
            // Eclipse visualization canvas (full screen)
            Canvas(modifier = Modifier.fillMaxSize()) {
                renderEclipse(this, eclipse, shoreline, latitude, longitude, useStandardTime, standardTimeOffsetHours, timeZoneAbbreviation)
            }

            // Bottom row with Back button and time zone radio buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color(0xFF00BFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(16.dp))

                // UT radio button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { useStandardTime = false }
                ) {
                    RadioButton(
                        selected = !useStandardTime,
                        onClick = { useStandardTime = false },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF00BFFF),
                            unselectedColor = Color.Gray
                        )
                    )
                    Text("UT", color = Color.White, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Standard Time radio button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { useStandardTime = true }
                ) {
                    RadioButton(
                        selected = useStandardTime,
                        onClick = { useStandardTime = true },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF00BFFF),
                            unselectedColor = Color.Gray
                        )
                    )
                    Text("Standard Time", color = Color.White, fontSize = 14.sp)
                }
            }
        } else {
            // "Generating Display" message while preparing to render
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Generating Display...", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

// ============================================================================
// ECLIPSE RENDERING
// ============================================================================

private fun renderEclipse(
    drawScope: DrawScope,
    eclipse: LunarEclipse,
    shoreline: List<ShoreSegment>,
    userLatitude: Double,
    userLongitude: Double,
    useStandardTime: Boolean,
    standardTimeOffsetHours: Double,
    timeZoneAbbreviation: String
) {
    // Time offset: 0 for UT, standardTimeOffsetHours for standard time
    val timeOffset = if (useStandardTime) standardTimeOffsetHours else 0.0
    val timeSuffix = if (useStandardTime) timeZoneAbbreviation else "UT"
    val width = drawScope.size.width
    val height = drawScope.size.height

    // Colors
    val colorBlack = Color.Black
    val colorWhite = Color.White
    val colorGrey = Color(0xFF808080)
    val colorDarkGrey = Color(0xFF404040)
    val colorLightGrey = Color(0xFFC0C0C0)
    val colorRed = Color.Red
    val colorGreen = Color.Green
    val colorMediumGrey = Color(0xFF757575)  // For connecting lines
    val colorBlue = Color(0xFF0080FF)
    val colorYellow = Color.Yellow
    val colorCream = Color(0xFFFFFDD0)
    val colorLightBlue = Color(0xFF87CEEB)
    val colorDarkBlueGreen = Color(0xFF006464)

    val monthNames = arrayOf("January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December")

    // Layout calculations - optimize for phone display
    val margin = 15f
    val scaleFactor = (width / 800f).coerceIn(0.5f, 1.5f)
    val textScale = scaleFactor.coerceIn(0.7f, 1.2f) * 1.925f  // 92.5% larger fonts (75% + 10%)

    // Header section heights - increased spacing for larger fonts (65% more line spacing)
    val lineSpacing = 36.3f * scaleFactor
    val titleY = 28f * scaleFactor
    val visibilityY = titleY + lineSpacing
    val durationY = visibilityY + lineSpacing
    val sarosY = durationY + lineSpacing
    val maxEclipseY = sarosY + lineSpacing

    // Phase times section
    val phaseTimesStartY = maxEclipseY + lineSpacing
    val phaseLineHeight = lineSpacing

    // Calculate how many phase time lines we need
    val numPhaseLines = when (eclipse.eclipseType) {
        TOTAL_LUNAR_ECLIPSE -> 3
        PARTIAL_LUNAR_ECLIPSE -> 2
        else -> 1
    }
    val phaseTimesEndY = phaseTimesStartY + numPhaseLines * phaseLineHeight

    // Moon path diagram
    val umbraMapTop = phaseTimesEndY + 5f * scaleFactor
    val umbraMapHeight = minOf(220f * scaleFactor, (height - umbraMapTop) * 0.30f)
    val umbraMapOffsetY = umbraMapTop + umbraMapHeight / 2

    // Earth map - use remaining space with proper aspect ratio (2:1 for equirectangular)
    val earthMapTop = umbraMapTop + umbraMapHeight + 10f * scaleFactor
    val mapWidth = width - 2 * margin
    // Earth map should have 2:1 aspect ratio (width:height) for proper equirectangular projection
    // Increase by 15% to use more vertical space
    val earthMapHeight = minOf(mapWidth / 2f * 1.15f, height - earthMapTop - margin)

    val umbraMapScale = umbraMapHeight / (14.0 * MOON_RADIUS)
    val eta = 23.44 * DEGREES_TO_RADIANS

    // Calculate eclipse times in UT (for astronomical calculations)
    val eclipseUTHours = (eclipse.tDGE - eclipse.dTMinusUT) / 3600.0
    var utHH = eclipseUTHours.toInt()
    var utMM = ((eclipseUTHours - utHH) * 60.0).toInt()
    var utSS = ((eclipseUTHours - utHH - utMM / 60.0) * 3600.0 + 0.5).toInt()
    if (utSS >= 60) { utSS -= 60; utMM++ }
    if (utMM >= 60) { utMM -= 60; utHH++ }

    val eclipseTJD = buildTJD(eclipse.year, eclipse.month, eclipse.day, utHH, utMM, utSS)

    // Calculate display time (applying time offset)
    var displayHours = eclipseUTHours + timeOffset
    while (displayHours < 0) displayHours += 24.0
    while (displayHours >= 24) displayHours -= 24.0
    var maxHH = displayHours.toInt()
    var maxMM = ((displayHours - maxHH) * 60.0).toInt()
    var maxSS = ((displayHours - maxHH - maxMM / 60.0) * 3600.0 + 0.5).toInt()
    if (maxSS >= 60) { maxSS -= 60; maxMM++ }
    if (maxMM >= 60) { maxMM -= 60; maxHH++ }
    if (maxHH >= 24) { maxHH -= 24 }

    // Get Sun and Moon positions
    val sun = sunPosition(eclipseTJD)
    val moon = moonPosition(eclipseTJD)

    val moonDistanceCM = moon.distanceKM * 1.0e5
    val sunDistanceCM = sun.distanceKM * 1.0e5

    val moonRadiusPixels = (1 + atan(MOON_RADIUS / moonDistanceCM) * moonDistanceCM * umbraMapScale).toInt()

    // Shadow plane position
    val shadowPlaneRA = sun.ra - Math.PI - moon.ra
    val shadowPlaneDec = -sun.dec - moon.dec
    val shadowPlaneX = sin(shadowPlaneRA) * moonDistanceCM
    val shadowPlaneY = -sin(shadowPlaneDec) * moonDistanceCM

    // Calculate umbra and penumbra radii
    val lEEquatorial = sunDistanceCM * (1.0 + EARTH_EQUATORIAL_RADIUS / (SOLAR_RADIUS - EARTH_EQUATORIAL_RADIUS))
    val lEPolar = sunDistanceCM * (1.0 + EARTH_POLAR_RADIUS / (SOLAR_RADIUS - EARTH_POLAR_RADIUS))
    val rUmbraEquatorial = SOLAR_RADIUS * (lEEquatorial - sunDistanceCM - moonDistanceCM) / lEEquatorial * ATMOSPHERIC_UMBRA_EXPANSION
    val rUmbraPolar = SOLAR_RADIUS * (lEPolar - sunDistanceCM - moonDistanceCM) / lEPolar * ATMOSPHERIC_UMBRA_EXPANSION

    val lPEquatorial = SOLAR_RADIUS * sunDistanceCM / (SOLAR_RADIUS + EARTH_EQUATORIAL_RADIUS)
    val lPPolar = SOLAR_RADIUS * sunDistanceCM / (SOLAR_RADIUS + EARTH_POLAR_RADIUS)
    val rPenumbraEquatorial = SOLAR_RADIUS * (sunDistanceCM - lPEquatorial + moonDistanceCM) / lPEquatorial * ATMOSPHERIC_UMBRA_EXPANSION
    val rPenumbraPolar = SOLAR_RADIUS * (sunDistanceCM - lPPolar + moonDistanceCM) / lPPolar * ATMOSPHERIC_UMBRA_EXPANSION

    // Eclipse phase times
    val penEclipseStartTJD = eclipseTJD - eclipse.penDur / 2880.0
    val penEclipseEndTJD = eclipseTJD + eclipse.penDur / 2880.0
    val parEclipseStartTJD = eclipseTJD - eclipse.parDur / 2880.0
    val parEclipseEndTJD = eclipseTJD + eclipse.parDur / 2880.0
    val totEclipseStartTJD = eclipseTJD - eclipse.totDur / 2880.0
    val totEclipseEndTJD = eclipseTJD + eclipse.totDur / 2880.0

    // Helper functions
    fun cmToPixels(x: Double, y: Double): Pair<Float, Float> {
        val px = (x * umbraMapScale + mapWidth * 0.5).toFloat() + margin
        val py = (-y * umbraMapScale + umbraMapOffsetY).toFloat()
        return Pair(px, py)
    }

    fun latLonToPixels(lat: Float, lon: Float): Pair<Float, Float> {
        val py = (-(lat / Math.PI.toFloat() + 0.5f) * earthMapHeight) + earthMapTop + earthMapHeight
        val px = ((lon / M_2PI.toFloat() + 0.5f) * mapWidth) + margin
        return Pair(px, py)
    }

    // Calculate visibility using interpolated moon positions (major optimization)
    // Moon RA/Dec change slowly during an eclipse, so we compute positions at the
    // search boundaries and interpolate for all intermediate times
    val searchStart = penEclipseStartTJD - 0.01
    val searchEnd = penEclipseEndTJD + 0.01

    // Compute moon positions at search boundaries (only 2 expensive moonPosition calls)
    val moonAtStart = moonPosition(searchStart)
    val moonAtEnd = moonPosition(searchEnd)
    val interpStartRaHours = Math.toDegrees(moonAtStart.ra) / 15.0
    val interpStartDecDeg = Math.toDegrees(moonAtStart.dec)
    val interpEndRaHours = Math.toDegrees(moonAtEnd.ra) / 15.0
    val interpEndDecDeg = Math.toDegrees(moonAtEnd.dec)

    // Determine initial moon state at eclipse start
    val moonUpAtStart = moonAboveHorizonInterpolated(
        penEclipseStartTJD, userLatitude, userLongitude,
        searchStart, searchEnd,
        interpStartRaHours, interpStartDecDeg,
        interpEndRaHours, interpEndDecDeg
    )

    // Find moonrise/moonset during eclipse using interpolated positions
    var moonsetTJD: Double? = null
    var moonriseTJD: Double? = null
    val searchStep = 1.0 / 1440.0
    var prevUp = moonAboveHorizonInterpolated(
        searchStart, userLatitude, userLongitude,
        searchStart, searchEnd,
        interpStartRaHours, interpStartDecDeg,
        interpEndRaHours, interpEndDecDeg
    )

    var tJD = searchStart + searchStep
    while (tJD <= searchEnd) {
        val currUp = moonAboveHorizonInterpolated(
            tJD, userLatitude, userLongitude,
            searchStart, searchEnd,
            interpStartRaHours, interpStartDecDeg,
            interpEndRaHours, interpEndDecDeg
        )

        if (prevUp && !currUp && moonsetTJD == null) {
            // Moon setting - refine
            var lo = tJD - searchStep
            var hi = tJD
            for (i in 0 until 10) {
                val mid = (lo + hi) / 2.0
                if (moonAboveHorizonInterpolated(
                        mid, userLatitude, userLongitude,
                        searchStart, searchEnd,
                        interpStartRaHours, interpStartDecDeg,
                        interpEndRaHours, interpEndDecDeg
                    )) lo = mid else hi = mid
            }
            val refinedTime = (lo + hi) / 2.0
            if (refinedTime >= penEclipseStartTJD && refinedTime <= penEclipseEndTJD) {
                moonsetTJD = refinedTime
            }
        }

        if (!prevUp && currUp && moonriseTJD == null) {
            // Moon rising - refine
            var lo = tJD - searchStep
            var hi = tJD
            for (i in 0 until 10) {
                val mid = (lo + hi) / 2.0
                if (moonAboveHorizonInterpolated(
                        mid, userLatitude, userLongitude,
                        searchStart, searchEnd,
                        interpStartRaHours, interpStartDecDeg,
                        interpEndRaHours, interpEndDecDeg
                    )) hi = mid else lo = mid
            }
            val refinedTime = (lo + hi) / 2.0
            if (refinedTime >= penEclipseStartTJD && refinedTime <= penEclipseEndTJD) {
                moonriseTJD = refinedTime
            }
        }

        prevUp = currUp
        tJD += searchStep
    }

    // Analytical calculation of visible eclipse durations
    // Build list of moon-up intervals during the eclipse
    val upIntervals = mutableListOf<Pair<Double, Double>>()
    if (moonUpAtStart) {
        val intervalEnd = moonsetTJD ?: penEclipseEndTJD
        if (intervalEnd > penEclipseStartTJD) {
            upIntervals.add(Pair(penEclipseStartTJD, minOf(intervalEnd, penEclipseEndTJD)))
        }
        // Check for moonrise after moonset (moon sets then rises again)
        if (moonsetTJD != null && moonriseTJD != null && moonriseTJD!! > moonsetTJD!!) {
            upIntervals.add(Pair(moonriseTJD!!, penEclipseEndTJD))
        }
    } else {
        if (moonriseTJD != null) {
            val intervalEnd = if (moonsetTJD != null && moonsetTJD!! > moonriseTJD!!) moonsetTJD!! else penEclipseEndTJD
            upIntervals.add(Pair(moonriseTJD!!, minOf(intervalEnd, penEclipseEndTJD)))
        }
    }

    // Helper to calculate visible duration within a time window
    fun calcVisibleDuration(windowStart: Double, windowEnd: Double): Double {
        if (windowEnd <= windowStart) return 0.0
        var totalVisible = 0.0
        for ((intStart, intEnd) in upIntervals) {
            val overlapStart = maxOf(intStart, windowStart)
            val overlapEnd = minOf(intEnd, windowEnd)
            if (overlapEnd > overlapStart) {
                totalVisible += overlapEnd - overlapStart
            }
        }
        return totalVisible
    }

    // Calculate visible durations for each phase (mutually exclusive)
    // Total phase
    val totVisibleDays = if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE) {
        calcVisibleDuration(totEclipseStartTJD, totEclipseEndTJD)
    } else 0.0

    // Partial phase (exclusive of total)
    val parVisibleDays = if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
        if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE) {
            // Two segments: before total and after total
            calcVisibleDuration(parEclipseStartTJD, totEclipseStartTJD) +
            calcVisibleDuration(totEclipseEndTJD, parEclipseEndTJD)
        } else {
            // Partial-only eclipse: entire partial phase
            calcVisibleDuration(parEclipseStartTJD, parEclipseEndTJD)
        }
    } else 0.0

    // Penumbral phase (exclusive of partial)
    val penVisibleDays = if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
        // Two segments: before partial and after partial
        calcVisibleDuration(penEclipseStartTJD, parEclipseStartTJD) +
        calcVisibleDuration(parEclipseEndTJD, penEclipseEndTJD)
    } else {
        // Penumbral-only eclipse: entire penumbral phase
        calcVisibleDuration(penEclipseStartTJD, penEclipseEndTJD)
    }

    // Derive the flags and counts used by the rest of the code
    val willSeeTot = totVisibleDays > 0
    val willSeePar = parVisibleDays > 0
    val totalVisibleDays = totVisibleDays + parVisibleDays + penVisibleDays
    val neverWasUp = totalVisibleDays == 0.0
    val alwaysWasUp = moonsetTJD == null && moonriseTJD == null && moonUpAtStart

    // Convert to minutes (these replace the old nPenSeen/nParSeen/nTotSeen * trackStep * 1440)
    val minutesTot = (totVisibleDays * 1440.0 + 0.5).toInt()
    val minutesPar = (parVisibleDays * 1440.0 + 0.5).toInt()
    val minutesPen = (penVisibleDays * 1440.0 + 0.5).toInt()

    // Calculate Moon positions at phase times
    val phaseTimes = doubleArrayOf(
        penEclipseStartTJD, penEclipseEndTJD,
        parEclipseStartTJD, parEclipseEndTJD,
        totEclipseStartTJD, totEclipseEndTJD
    )
    val moonPosX = FloatArray(6)
    val moonPosY = FloatArray(6)

    for (p in 0 until 6) {
        val tSun = sunPosition(phaseTimes[p])
        val tMoon = moonPosition(phaseTimes[p])
        val tDistCM = tMoon.distanceKM * 1.0e5

        val spRA = tSun.ra - Math.PI - tMoon.ra
        val spDec = -tSun.dec - tMoon.dec
        val spX = sin(spRA) * tDistCM
        val spY = -sin(spDec) * tDistCM
        val (px, py) = cmToPixels(spX, spY)
        moonPosX[p] = px
        moonPosY[p] = py
    }

    val (shadowPx, shadowPy) = cmToPixels(shadowPlaneX, shadowPlaneY)

    // ========== DRAW UMBRA MAP ==========

    // Background
    drawScope.drawRect(colorWhite, Offset(margin, umbraMapTop),
        androidx.compose.ui.geometry.Size(mapWidth, umbraMapHeight))

    // Center crosshairs
    val cx = margin + mapWidth / 2
    val cy = umbraMapOffsetY
    drawScope.drawLine(colorBlack, Offset(cx - 5, cy), Offset(cx + 5, cy), 2f)
    drawScope.drawLine(colorBlack, Offset(cx, cy - 5), Offset(cx, cy + 5), 2f)

    // Penumbra (light grey ellipse)
    val penRx = (rPenumbraEquatorial * umbraMapScale).toFloat()
    val penRy = (rPenumbraPolar * umbraMapScale).toFloat()
    drawScope.drawOval(colorLightGrey, Offset(cx - penRx, cy - penRy),
        androidx.compose.ui.geometry.Size(penRx * 2, penRy * 2))

    // Umbra (dark grey ellipse)
    val umbRx = (rUmbraEquatorial * umbraMapScale).toFloat()
    val umbRy = (rUmbraPolar * umbraMapScale).toFloat()
    drawScope.drawOval(colorDarkGrey, Offset(cx - umbRx, cy - umbRy),
        androidx.compose.ui.geometry.Size(umbRx * 2, umbRy * 2))

    // Ecliptic line
    val eclipticSlope = tan(eta * cos(sun.ra)).toFloat()
    var eclipticX = mapWidth * 0.5f
    var eclipticY = eclipticX * eclipticSlope
    if (abs(eclipticY) > umbraMapHeight * 0.5f) {
        eclipticY = umbraMapHeight * 0.5f * if (eclipticY > 0) 1f else -1f
        eclipticX = eclipticY / eclipticSlope
    }
    drawScope.drawLine(colorRed,
        Offset(-eclipticX + cx, eclipticY + cy),
        Offset(eclipticX + cx, -eclipticY + cy), 2f)

    // Moon path line
    val pathStartTJD = eclipseTJD - 0.17
    val pathEndTJD = eclipseTJD + 0.25

    val startSun = sunPosition(pathStartTJD)
    val startMoon = moonPosition(pathStartTJD)
    val startDistCM = startMoon.distanceKM * 1.0e5
    val startSpX = sin(startSun.ra - Math.PI - startMoon.ra) * startDistCM
    val startSpY = -sin(-startSun.dec - startMoon.dec) * startDistCM
    val (pathPx1, pathPy1) = cmToPixels(startSpX, startSpY)

    val endSun = sunPosition(pathEndTJD)
    val endMoon = moonPosition(pathEndTJD)
    val endDistCM = endMoon.distanceKM * 1.0e5
    val endSpX = sin(endSun.ra - Math.PI - endMoon.ra) * endDistCM
    val endSpY = -sin(-endSun.dec - endMoon.dec) * endDistCM
    val (pathPx2, pathPy2) = cmToPixels(endSpX, endSpY)

    drawScope.drawLine(colorDarkBlueGreen, Offset(pathPx1, pathPy1), Offset(pathPx2, pathPy2), 2f)

    // Arrowhead at end
    val pathAngle = atan2(pathPy2 - pathPy1, pathPx2 - pathPx1)
    val arrowSize = 12f * scaleFactor
    drawScope.drawLine(colorDarkBlueGreen,
        Offset(pathPx2, pathPy2),
        Offset(pathPx2 - arrowSize * cos(pathAngle + 0.4f), pathPy2 - arrowSize * sin(pathAngle + 0.4f)), 2f)
    drawScope.drawLine(colorDarkBlueGreen,
        Offset(pathPx2, pathPy2),
        Offset(pathPx2 - arrowSize * cos(pathAngle - 0.4f), pathPy2 - arrowSize * sin(pathAngle - 0.4f)), 2f)

    // Moon circles at phase times
    drawScope.drawCircle(colorBlack, moonRadiusPixels.toFloat(), Offset(moonPosX[0], moonPosY[0]), style = Stroke(2f))
    drawScope.drawCircle(colorBlack, moonRadiusPixels.toFloat(), Offset(moonPosX[1], moonPosY[1]), style = Stroke(2f))

    if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
        drawScope.drawCircle(colorBlack, moonRadiusPixels.toFloat(), Offset(moonPosX[2], moonPosY[2]), style = Stroke(2f))
        drawScope.drawCircle(colorBlack, moonRadiusPixels.toFloat(), Offset(moonPosX[3], moonPosY[3]), style = Stroke(2f))
    }

    if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE) {
        drawScope.drawCircle(colorYellow, moonRadiusPixels.toFloat(), Offset(moonPosX[4], moonPosY[4]), style = Stroke(2f))
        drawScope.drawCircle(colorYellow, moonRadiusPixels.toFloat(), Offset(moonPosX[5], moonPosY[5]), style = Stroke(2f))
    }

    // Moon at maximum eclipse
    drawScope.drawCircle(colorYellow, moonRadiusPixels.toFloat(), Offset(shadowPx, shadowPy), style = Stroke(2f))

    // Moonset indicator
    var moonsetPx = 0f
    var moonsetPy = 0f
    if (moonsetTJD != null) {
        val msSun = sunPosition(moonsetTJD)
        val msMoon = moonPosition(moonsetTJD)
        val msDistCM = msMoon.distanceKM * 1.0e5
        val msSpX = sin(msSun.ra - Math.PI - msMoon.ra) * msDistCM
        val msSpY = -sin(-msSun.dec - msMoon.dec) * msDistCM
        val (px, py) = cmToPixels(msSpX, msSpY)
        moonsetPx = px
        moonsetPy = py
        drawScope.drawCircle(colorRed, moonRadiusPixels.toFloat(), Offset(px, py), style = Stroke(2f))
        // Arrow pointing up from Moon
        val labelY = umbraMapTop + umbraMapHeight - 15f * scaleFactor
        val arrowTopY = py + moonRadiusPixels + 2
        drawScope.drawLine(colorRed, Offset(px, arrowTopY), Offset(px, labelY - 5), 2f)
        drawScope.drawLine(colorRed, Offset(px, arrowTopY),
            Offset(px - 6f * scaleFactor, arrowTopY + 10f * scaleFactor), 2f)
        drawScope.drawLine(colorRed, Offset(px, arrowTopY),
            Offset(px + 6f * scaleFactor, arrowTopY + 10f * scaleFactor), 2f)
    }

    // Moonrise indicator
    var moonrisePx = 0f
    var moonrisePy = 0f
    if (moonriseTJD != null) {
        val mrSun = sunPosition(moonriseTJD)
        val mrMoon = moonPosition(moonriseTJD)
        val mrDistCM = mrMoon.distanceKM * 1.0e5
        val mrSpX = sin(mrSun.ra - Math.PI - mrMoon.ra) * mrDistCM
        val mrSpY = -sin(-mrSun.dec - mrMoon.dec) * mrDistCM
        val (px, py) = cmToPixels(mrSpX, mrSpY)
        moonrisePx = px
        moonrisePy = py
        drawScope.drawCircle(colorRed, moonRadiusPixels.toFloat(), Offset(px, py), style = Stroke(2f))
        // Arrow pointing down from Moon
        val labelY = umbraMapTop + 15f * scaleFactor
        val arrowBottomY = py - moonRadiusPixels - 2
        drawScope.drawLine(colorRed, Offset(px, arrowBottomY), Offset(px, labelY + 15f * scaleFactor), 2f)
        drawScope.drawLine(colorRed, Offset(px, arrowBottomY),
            Offset(px - 6f * scaleFactor, arrowBottomY - 10f * scaleFactor), 2f)
        drawScope.drawLine(colorRed, Offset(px, arrowBottomY),
            Offset(px + 6f * scaleFactor, arrowBottomY - 10f * scaleFactor), 2f)
    }

    // ========== DRAW EARTH MAP ==========

    // Background
    drawScope.drawRect(colorDarkGrey, Offset(margin, earthMapTop),
        androidx.compose.ui.geometry.Size(mapWidth, earthMapHeight))

    // Analytical visibility shading - compute longitude ranges per row
    // Pre-compute Moon positions and GMST for all key times
    val moonCache = buildMoonCache(
        penEclipseStartTJD, penEclipseEndTJD,
        parEclipseStartTJD, parEclipseEndTJD,
        totEclipseStartTJD, totEclipseEndTJD,
        eclipse.eclipseType
    )
    val gmstCache = buildGMSTCache(
        penEclipseStartTJD, penEclipseEndTJD,
        parEclipseStartTJD, parEclipseEndTJD,
        totEclipseStartTJD, totEclipseEndTJD
    )

    // Process each row analytically
    val pixelHeight = earthMapHeight.toInt()
    val pixelWidth = mapWidth.toInt()

    for (py in 0 until pixelHeight) {
        val lat = -(((py.toFloat() / earthMapHeight) - 0.5f) * Math.PI)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val screenY = earthMapTop + py

        // Calculate longitude ranges for each eclipse time
        val penStartRange = getMoonVisibleLonRange(sinLat, cosLat, gmstCache.penStart,
            moonCache.penStart.raHours, moonCache.penStart.sinDec, moonCache.penStart.cosDec)
        val penEndRange = getMoonVisibleLonRange(sinLat, cosLat, gmstCache.penEnd,
            moonCache.penEnd.raHours, moonCache.penEnd.sinDec, moonCache.penEnd.cosDec)

        val parStartRange = if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE && moonCache.parStart != null)
            getMoonVisibleLonRange(sinLat, cosLat, gmstCache.parStart,
                moonCache.parStart.raHours, moonCache.parStart.sinDec, moonCache.parStart.cosDec)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)

        val parEndRange = if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE && moonCache.parEnd != null)
            getMoonVisibleLonRange(sinLat, cosLat, gmstCache.parEnd,
                moonCache.parEnd.raHours, moonCache.parEnd.sinDec, moonCache.parEnd.cosDec)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)

        val totStartRange = if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE && moonCache.totStart != null)
            getMoonVisibleLonRange(sinLat, cosLat, gmstCache.totStart,
                moonCache.totStart.raHours, moonCache.totStart.sinDec, moonCache.totStart.cosDec)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)

        val totMidRange = if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE && moonCache.totMid != null)
            getMoonVisibleLonRange(sinLat, cosLat, gmstCache.totMid,
                moonCache.totMid.raHours, moonCache.totMid.sinDec, moonCache.totMid.cosDec)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)

        val totEndRange = if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE && moonCache.totEnd != null)
            getMoonVisibleLonRange(sinLat, cosLat, gmstCache.totEnd,
                moonCache.totEnd.raHours, moonCache.totEnd.sinDec, moonCache.totEnd.cosDec)
        else LonRange(0.0, 0.0, alwaysUp = false, neverUp = true)

        // For each x position, determine highest visibility level and draw segments
        var currentLevel = -1
        var segmentStart = 0

        for (px in 0..pixelWidth) {
            val lonDeg = ((px.toFloat() / mapWidth) - 0.5f) * 360.0

            // Determine visibility level: highest applicable level wins
            val level = when {
                lonInRange(lonDeg, totStartRange) || lonInRange(lonDeg, totMidRange) || lonInRange(lonDeg, totEndRange) -> 3
                lonInRange(lonDeg, parStartRange) || lonInRange(lonDeg, parEndRange) -> 2
                lonInRange(lonDeg, penStartRange) || lonInRange(lonDeg, penEndRange) -> 1
                else -> 0
            }

            // Draw segment when level changes or at end of row
            if (level != currentLevel || px == pixelWidth) {
                if (currentLevel > 0 && px > segmentStart) {
                    val color = when (currentLevel) {
                        1 -> colorGrey
                        2 -> colorLightGrey
                        else -> colorWhite
                    }
                    drawScope.drawRect(color,
                        Offset(margin + segmentStart, screenY),
                        androidx.compose.ui.geometry.Size((px - segmentStart).toFloat(), 1f))
                }
                currentLevel = level
                segmentStart = px
            }
        }

        // Draw black pixels at Moon rise/set longitudes for eclipse phase boundaries
        fun drawBlackPixelAtLon(lonDeg: Double) {
            val px = ((lonDeg / 360.0 + 0.5) * mapWidth).toInt()
            if (px in 0 until pixelWidth) {
                drawScope.drawRect(colorBlack,
                    Offset(margin + px, screenY),
                    androidx.compose.ui.geometry.Size(1f, 1f))
            }
        }

        // Moon sets when total phase ends
        if (!totEndRange.neverUp && !totEndRange.alwaysUp) {
            drawBlackPixelAtLon(totEndRange.lon2)
        }
        // Moon sets when partial eclipse ends
        if (!parEndRange.neverUp && !parEndRange.alwaysUp) {
            drawBlackPixelAtLon(parEndRange.lon2)
        }
        // Moon sets when penumbral eclipse ends
        if (!penEndRange.neverUp && !penEndRange.alwaysUp) {
            drawBlackPixelAtLon(penEndRange.lon2)
        }
        // Moon rises when penumbral eclipse starts
        if (!penStartRange.neverUp && !penStartRange.alwaysUp) {
            drawBlackPixelAtLon(penStartRange.lon1)
        }
        // Moon rises when partial eclipse starts
        if (!parStartRange.neverUp && !parStartRange.alwaysUp) {
            drawBlackPixelAtLon(parStartRange.lon1)
        }
        // Moon rises when total eclipse starts
        if (!totStartRange.neverUp && !totStartRange.alwaysUp) {
            drawBlackPixelAtLon(totStartRange.lon1)
        }
    }

    // Shorelines
    for (seg in shoreline) {
        if (seg.nVertices > 1) {
            for (i in 0 until seg.nVertices - 1) {
                val lat1 = seg.lat[i] * Math.PI.toFloat() / 65535.0f
                val lon1 = seg.lon[i] * Math.PI.toFloat() / 32767.0f
                val lat2 = seg.lat[i + 1] * Math.PI.toFloat() / 65535.0f
                val lon2 = seg.lon[i + 1] * Math.PI.toFloat() / 32767.0f

                val (px1s, py1s) = latLonToPixels(lat1, lon1)
                val (px2s, py2s) = latLonToPixels(lat2, lon2)

                if (abs(px2s - px1s) < mapWidth / 2) {
                    drawScope.drawLine(colorBlack, Offset(px1s, py1s), Offset(px2s, py2s), 1f)
                }
            }
        }
    }

    // Sub-lunar point line
    val myLST = calculateLSTHours(eclipseTJD, userLongitude) * (Math.PI / 12.0)  // hours to radians
    val sublunarLat = moon.dec
    var sublunarLon = userLongitude * DEGREES_TO_RADIANS - myLST + moon.ra
    sublunarLon = doubleNormalizeMinusPiToPi(sublunarLon)

    val (subPx, subPy) = latLonToPixels(sublunarLat.toFloat(), sublunarLon.toFloat())
    val (_, topY) = latLonToPixels((Math.PI / 2).toFloat(), sublunarLon.toFloat())
    val (_, botY) = latLonToPixels((-Math.PI / 2).toFloat(), sublunarLon.toFloat())
    drawScope.drawLine(colorBlue, Offset(subPx, topY), Offset(subPx, botY), 2f)
    drawScope.drawCircle(colorBlue, 6f, Offset(subPx, subPy))

    // User location
    val (userPx, userPy) = latLonToPixels(
        (userLatitude * DEGREES_TO_RADIANS).toFloat(),
        (userLongitude * DEGREES_TO_RADIANS).toFloat()
    )
    drawScope.drawCircle(colorRed, 5f, Offset(userPx, userPy))

    // Variables to store "Maximum Eclipse" label bounding box for green line positioning
    var maxEclipseLabelLeft = 0f
    var maxEclipseLabelRight = 0f

    // ========== DRAW ALL TEXT LABELS (after graphics) ==========
    drawScope.drawIntoCanvas { canvas ->
        val smallTextSize = 12f * textScale
        val mediumTextSize = 14f * textScale
        val largeTextSize = 16f * textScale

        val textPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val leftTextPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val rightTextPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        // Title - eclipse type in yellow, date in white
        textPaint.textSize = largeTextSize
        textPaint.textAlign = Paint.Align.LEFT
        val yearStr = if (eclipse.year <= 0) "${abs(eclipse.year - 1)} BC" else "${eclipse.year}"
        val titlePrefix = "${eclipse.typeString} Lunar Eclipse "
        val titleDate = "${monthNames[eclipse.month - 1]} ${eclipse.day}, $yearStr"
        val prefixWidth = textPaint.measureText(titlePrefix)
        val dateWidth = textPaint.measureText(titleDate)
        val titleStartX = (width - prefixWidth - dateWidth) / 2
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(titlePrefix, titleStartX, titleY, textPaint)
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(titleDate, titleStartX + prefixWidth, titleY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Visibility message
        val (visibilityMsg, visibilityColor) = when {
            neverWasUp -> "This eclipse is not visible from your location." to colorRed
            alwaysWasUp -> "All of this eclipse is visible from your location." to colorGreen
            eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE && willSeeTot -> "You can see the total eclipse." to colorGreen
            eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE && willSeePar -> "You can only see a partial eclipse." to colorYellow
            eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE -> "You can only see the penumbral phase." to colorYellow
            eclipse.eclipseType == PARTIAL_LUNAR_ECLIPSE && willSeePar -> "You can see the partial eclipse." to colorGreen
            eclipse.eclipseType == PARTIAL_LUNAR_ECLIPSE -> "You can only see the penumbral phase." to colorYellow
            else -> "You can see the penumbral eclipse." to colorGreen
        }
        textPaint.color = visibilityColor.toArgb()
        textPaint.textSize = smallTextSize
        canvas.nativeCanvas.drawText(visibilityMsg, width / 2, visibilityY, textPaint)

        // Duration info - labels in yellow, numbers in white
        textPaint.textAlign = Paint.Align.LEFT
        val durPart1 = "Visible for penumbral: "
        val durNum1 = "$minutesPen"
        val durPart2 = " partial: "
        val durNum2 = "$minutesPar"
        val durPart3 = " total: "
        val durNum3 = "$minutesTot"
        val durPart4 = " minutes."
        val durWidth1 = textPaint.measureText(durPart1)
        val durWidthN1 = textPaint.measureText(durNum1)
        val durWidth2 = textPaint.measureText(durPart2)
        val durWidthN2 = textPaint.measureText(durNum2)
        val durWidth3 = textPaint.measureText(durPart3)
        val durWidthN3 = textPaint.measureText(durNum3)
        val durWidth4 = textPaint.measureText(durPart4)
        val durTotalWidth = durWidth1 + durWidthN1 + durWidth2 + durWidthN2 + durWidth3 + durWidthN3 + durWidth4
        var durX = (width - durTotalWidth) / 2
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(durPart1, durX, durationY, textPaint)
        durX += durWidth1
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(durNum1, durX, durationY, textPaint)
        durX += durWidthN1
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(durPart2, durX, durationY, textPaint)
        durX += durWidth2
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(durNum2, durX, durationY, textPaint)
        durX += durWidthN2
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(durPart3, durX, durationY, textPaint)
        durX += durWidth3
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(durNum3, durX, durationY, textPaint)
        durX += durWidthN3
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(durPart4, durX, durationY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Saros and magnitude - labels in yellow, numbers in white
        textPaint.textAlign = Paint.Align.LEFT
        val mag = if (eclipse.eclipseType == PENUMBRAL_LUNAR_ECLIPSE) eclipse.penMag else eclipse.umbMag
        val sarosLabel = "Saros Number "
        val sarosNum = "${eclipse.sarosNum}"
        val magLabel = " Umbral Magnitude: "
        val magValue = "%.3f".format(mag)
        val sarosLabelWidth = textPaint.measureText(sarosLabel)
        val sarosNumWidth = textPaint.measureText(sarosNum)
        val magLabelWidth = textPaint.measureText(magLabel)
        val magValueWidth = textPaint.measureText(magValue)
        val sarosTotalWidth = sarosLabelWidth + sarosNumWidth + magLabelWidth + magValueWidth
        val sarosStartX = (width - sarosTotalWidth) / 2
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(sarosLabel, sarosStartX, sarosY, textPaint)
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(sarosNum, sarosStartX + sarosLabelWidth, sarosY, textPaint)
        textPaint.color = colorYellow.toArgb()
        canvas.nativeCanvas.drawText(magLabel, sarosStartX + sarosLabelWidth + sarosNumWidth, sarosY, textPaint)
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(magValue, sarosStartX + sarosLabelWidth + sarosNumWidth + magLabelWidth, sarosY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Maximum eclipse time - label in light blue, time in yellow
        // Use left-aligned paint to precisely control positioning and measure bounding box
        textPaint.textSize = mediumTextSize
        textPaint.textAlign = Paint.Align.LEFT
        val maxEclipseLabel = "Maximum Eclipse at "
        val maxEclipseTime = "%02d:%02d:%02d $timeSuffix".format(maxHH, maxMM, maxSS)
        val labelWidth = textPaint.measureText(maxEclipseLabel)
        val timeWidth = textPaint.measureText(maxEclipseTime)
        val totalWidth = labelWidth + timeWidth
        val maxLabelStartX = (width - totalWidth) / 2  // Center the combined text

        textPaint.color = colorLightBlue.toArgb()
        canvas.nativeCanvas.drawText(maxEclipseLabel, maxLabelStartX, maxEclipseY, textPaint)
        textPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText(maxEclipseTime, maxLabelStartX + labelWidth, maxEclipseY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER  // Restore center alignment

        // Store bounding box for the green line (will be drawn later)
        maxEclipseLabelLeft = maxLabelStartX
        maxEclipseLabelRight = maxLabelStartX + totalWidth

        // Phase time labels
        leftTextPaint.textSize = smallTextSize
        rightTextPaint.textSize = smallTextSize
        var currentY = phaseTimesStartY

        // Total phase times
        if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE) {
            val (totEndH, totEndM, _) = tJDToHHMMSS(totEclipseEndTJD, timeOffset)
            val (totStartH, totStartM, _) = tJDToHHMMSS(totEclipseStartTJD, timeOffset)

            leftTextPaint.color = colorLightBlue.toArgb()
            canvas.nativeCanvas.drawText("Total Eclipse Ends", margin, currentY, leftTextPaint)
            leftTextPaint.color = colorWhite.toArgb()
            canvas.nativeCanvas.drawText("%02d:%02d".format(totEndH, totEndM), margin + 135f * textScale, currentY, leftTextPaint)

            rightTextPaint.color = colorLightBlue.toArgb()
            canvas.nativeCanvas.drawText("Total Eclipse Starts", width - margin - 45f * textScale, currentY, rightTextPaint)
            rightTextPaint.color = colorWhite.toArgb()
            canvas.nativeCanvas.drawText("%02d:%02d".format(totStartH, totStartM), width - margin, currentY, rightTextPaint)

            currentY += phaseLineHeight
        }

        // Partial phase times
        if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
            val (parEndH, parEndM, _) = tJDToHHMMSS(parEclipseEndTJD, timeOffset)
            val (parStartH, parStartM, _) = tJDToHHMMSS(parEclipseStartTJD, timeOffset)

            leftTextPaint.color = colorLightBlue.toArgb()
            canvas.nativeCanvas.drawText("Partial Ends", margin, currentY, leftTextPaint)
            leftTextPaint.color = colorWhite.toArgb()
            canvas.nativeCanvas.drawText("%02d:%02d".format(parEndH, parEndM), margin + 90f * textScale, currentY, leftTextPaint)

            rightTextPaint.color = colorLightBlue.toArgb()
            canvas.nativeCanvas.drawText("Partial Starts", width - margin - 45f * textScale, currentY, rightTextPaint)
            rightTextPaint.color = colorWhite.toArgb()
            canvas.nativeCanvas.drawText("%02d:%02d".format(parStartH, parStartM), width - margin, currentY, rightTextPaint)

            currentY += phaseLineHeight
        }

        // Penumbral phase times
        val (penEndH, penEndM, _) = tJDToHHMMSS(penEclipseEndTJD, timeOffset)
        val (penStartH, penStartM, _) = tJDToHHMMSS(penEclipseStartTJD, timeOffset)

        leftTextPaint.color = colorLightBlue.toArgb()
        canvas.nativeCanvas.drawText("Penumbral Ends", margin, currentY, leftTextPaint)
        leftTextPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText("%02d:%02d".format(penEndH, penEndM), margin + 105f * textScale, currentY, leftTextPaint)

        rightTextPaint.color = colorLightBlue.toArgb()
        canvas.nativeCanvas.drawText("Penumbral Starts", width - margin - 60f * textScale, currentY, rightTextPaint)
        rightTextPaint.color = colorWhite.toArgb()
        canvas.nativeCanvas.drawText("%02d:%02d".format(penStartH, penStartM), width - margin, currentY, rightTextPaint)

        // East label (doesn't conflict with lines)
        leftTextPaint.color = colorBlack.toArgb()
        canvas.nativeCanvas.drawText("East", margin + 5f, cy + 4f, leftTextPaint)

        // Moonset label
        if (moonsetTJD != null) {
            textPaint.color = colorRed.toArgb()
            textPaint.textSize = smallTextSize
            canvas.nativeCanvas.drawText("Moon sets here", moonsetPx, umbraMapTop + umbraMapHeight - 3f * scaleFactor, textPaint)
        }

        // Moonrise label
        if (moonriseTJD != null) {
            textPaint.color = colorRed.toArgb()
            textPaint.textSize = smallTextSize
            canvas.nativeCanvas.drawText("Moon rises here", moonrisePx, umbraMapTop + 12f * scaleFactor + smallTextSize / 2f, textPaint)
        }
    }

    // Draw connecting lines from phase labels to Moon positions (drawn after text)
    // Collect vertical line X positions in umbra map area for label collision avoidance
    val verticalLineXPositions = mutableListOf<Float>()

    drawScope.apply {
        val lineOffsetY = 5f * scaleFactor
        var lineY = phaseTimesStartY + lineOffsetY
        val arrowSize = 8f * scaleFactor

        // Helper to draw arrowhead pointing down
        fun drawDownArrow(x: Float, y: Float) {
            drawLine(colorMediumGrey, Offset(x, y), Offset(x - arrowSize * 0.5f, y - arrowSize), 1.5f)
            drawLine(colorMediumGrey, Offset(x, y), Offset(x + arrowSize * 0.5f, y - arrowSize), 1.5f)
        }

        // Connecting lines (medium grey for visibility against white background)
        // Total phase lines
        if (eclipse.eclipseType == TOTAL_LUNAR_ECLIPSE) {
            // Tot end (left) to Moon position
            val totEndY = moonPosY[5] - moonRadiusPixels - 2
            drawLine(colorMediumGrey, Offset(margin, lineY), Offset(moonPosX[5], lineY), 1f)
            drawLine(colorMediumGrey, Offset(moonPosX[5], lineY), Offset(moonPosX[5], totEndY), 1f)
            verticalLineXPositions.add(moonPosX[5])
            drawDownArrow(moonPosX[5], totEndY)
            // Tot start (right) to Moon position
            val totStartY = moonPosY[4] - moonRadiusPixels - 2
            drawLine(colorMediumGrey, Offset(moonPosX[4], lineY), Offset(width - margin, lineY), 1f)
            drawLine(colorMediumGrey, Offset(moonPosX[4], lineY), Offset(moonPosX[4], totStartY), 1f)
            verticalLineXPositions.add(moonPosX[4])
            drawDownArrow(moonPosX[4], totStartY)
            lineY += phaseLineHeight
        }

        // Partial phase lines
        if (eclipse.eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
            val parEndY = moonPosY[3] - moonRadiusPixels - 2
            drawLine(colorMediumGrey, Offset(margin, lineY), Offset(moonPosX[3], lineY), 1f)
            drawLine(colorMediumGrey, Offset(moonPosX[3], lineY), Offset(moonPosX[3], parEndY), 1f)
            verticalLineXPositions.add(moonPosX[3])
            drawDownArrow(moonPosX[3], parEndY)
            val parStartY = moonPosY[2] - moonRadiusPixels - 2
            drawLine(colorMediumGrey, Offset(moonPosX[2], lineY), Offset(width - margin, lineY), 1f)
            drawLine(colorMediumGrey, Offset(moonPosX[2], lineY), Offset(moonPosX[2], parStartY), 1f)
            verticalLineXPositions.add(moonPosX[2])
            drawDownArrow(moonPosX[2], parStartY)
            lineY += phaseLineHeight
        }

        // Penumbral phase lines
        val penEndY = moonPosY[1] - moonRadiusPixels - 2
        drawLine(colorMediumGrey, Offset(margin, lineY), Offset(moonPosX[1], lineY), 1f)
        drawLine(colorMediumGrey, Offset(moonPosX[1], lineY), Offset(moonPosX[1], penEndY), 1f)
        verticalLineXPositions.add(moonPosX[1])
        drawDownArrow(moonPosX[1], penEndY)
        val penStartY = moonPosY[0] - moonRadiusPixels - 2
        drawLine(colorMediumGrey, Offset(moonPosX[0], lineY), Offset(width - margin, lineY), 1f)
        drawLine(colorMediumGrey, Offset(moonPosX[0], lineY), Offset(moonPosX[0], penStartY), 1f)
        verticalLineXPositions.add(moonPosX[0])
        drawDownArrow(moonPosX[0], penStartY)

        // Maximum eclipse connecting line - spans full "Maximum Eclipse at HH:MM:SS UT" label
        val maxLineY = maxEclipseY + 5f * scaleFactor
        val maxArrowY = shadowPy - moonRadiusPixels - 2
        drawLine(colorMediumGrey, Offset(maxEclipseLabelLeft, maxLineY),
            Offset(maxEclipseLabelRight, maxLineY), 1f)
        drawLine(colorMediumGrey, Offset(shadowPx, maxLineY), Offset(shadowPx, maxArrowY), 1f)
        verticalLineXPositions.add(shadowPx)
        drawDownArrow(shadowPx, maxArrowY)
    }

    // Draw "North" and "Ecliptic" labels after lines, avoiding collisions
    drawScope.drawIntoCanvas { canvas ->
        val smallTextSize = 12f * textScale
        val labelPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = smallTextSize
        }

        // Helper to check if a label bounding box overlaps any vertical line
        fun labelOverlapsLine(labelX: Float, labelWidth: Float, lineXPositions: List<Float>, buffer: Float = 5f): Boolean {
            val labelLeft = labelX - buffer
            val labelRight = labelX + labelWidth + buffer
            return lineXPositions.any { lineX -> lineX >= labelLeft && lineX <= labelRight }
        }

        // "North" label - find position near top of umbra map that doesn't overlap lines
        labelPaint.color = colorBlack.toArgb()
        val northText = "North"
        val northWidth = labelPaint.measureText(northText)
        val northY = umbraMapTop + 20f * scaleFactor

        // Try positions from center-right, moving right, then left
        var northX = cx + 20f * scaleFactor
        val step = 15f * scaleFactor
        var found = false

        // Search rightward from center
        for (i in 0..10) {
            val testX = cx + (20f + i * 15f) * scaleFactor
            if (testX + northWidth < width - margin &&
                !labelOverlapsLine(testX, northWidth, verticalLineXPositions)) {
                northX = testX
                found = true
                break
            }
        }

        // If not found, search leftward from center
        if (!found) {
            for (i in 1..10) {
                val testX = cx - (i * 15f + northWidth) * scaleFactor
                if (testX > margin &&
                    !labelOverlapsLine(testX, northWidth, verticalLineXPositions)) {
                    northX = testX
                    found = true
                    break
                }
            }
        }

        canvas.nativeCanvas.drawText(northText, northX, northY, labelPaint)

        // Save North label bounding box for collision detection
        val northLabelLeft = northX
        val northLabelTop = northY - smallTextSize
        val northLabelRight = northX + northWidth
        val northLabelBottom = northY + 2f

        // Helper to check if two rectangles overlap
        fun rectsOverlap(
            r1Left: Float, r1Top: Float, r1Right: Float, r1Bottom: Float,
            r2Left: Float, r2Top: Float, r2Right: Float, r2Bottom: Float
        ): Boolean {
            return r1Left < r2Right && r1Right > r2Left && r1Top < r2Bottom && r1Bottom > r2Top
        }

        // "Ecliptic" label - find position that doesn't overlap the ecliptic line, green lines, or North label
        labelPaint.color = colorRed.toArgb()
        val eclipticText = "Ecliptic"
        val eclipticWidth = labelPaint.measureText(eclipticText)
        val labelHeight = smallTextSize  // Approximate height

        // Ecliptic line endpoints
        val ecLineX1 = -eclipticX + cx
        val ecLineY1 = eclipticY + cy
        val ecLineX2 = eclipticX + cx
        val ecLineY2 = -eclipticY + cy

        // Helper to check if a rectangle intersects the ecliptic line segment
        fun rectIntersectsEclipticLine(rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float): Boolean {
            // Check if line segment intersects rectangle using line-clipping algorithm
            // First check if both endpoints are on the same side of the rectangle
            val minX = minOf(ecLineX1, ecLineX2)
            val maxX = maxOf(ecLineX1, ecLineX2)
            val minY = minOf(ecLineY1, ecLineY2)
            val maxY = maxOf(ecLineY1, ecLineY2)

            // Quick rejection: line bounding box doesn't overlap rectangle
            if (maxX < rectLeft || minX > rectRight || maxY < rectTop || minY > rectBottom) {
                return false
            }

            // Check if line passes through rectangle by testing if the line crosses any edge
            // or if an endpoint is inside the rectangle
            fun pointInRect(px: Float, py: Float) = px >= rectLeft && px <= rectRight && py >= rectTop && py <= rectBottom
            if (pointInRect(ecLineX1, ecLineY1) || pointInRect(ecLineX2, ecLineY2)) return true

            // Check intersection with each edge of rectangle
            fun linesCross(ax1: Float, ay1: Float, ax2: Float, ay2: Float,
                           bx1: Float, by1: Float, bx2: Float, by2: Float): Boolean {
                val d1 = (bx2 - bx1) * (ay1 - by1) - (by2 - by1) * (ax1 - bx1)
                val d2 = (bx2 - bx1) * (ay2 - by1) - (by2 - by1) * (ax2 - bx1)
                val d3 = (ax2 - ax1) * (by1 - ay1) - (ay2 - ay1) * (bx1 - ax1)
                val d4 = (ax2 - ax1) * (by2 - ay1) - (ay2 - ay1) * (bx2 - ax1)
                return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
            }

            // Test against all four edges
            return linesCross(ecLineX1, ecLineY1, ecLineX2, ecLineY2, rectLeft, rectTop, rectRight, rectTop) ||
                   linesCross(ecLineX1, ecLineY1, ecLineX2, ecLineY2, rectRight, rectTop, rectRight, rectBottom) ||
                   linesCross(ecLineX1, ecLineY1, ecLineX2, ecLineY2, rectLeft, rectBottom, rectRight, rectBottom) ||
                   linesCross(ecLineX1, ecLineY1, ecLineX2, ecLineY2, rectLeft, rectTop, rectLeft, rectBottom)
        }

        // Determine whether to place label below or above the ecliptic line
        // Default to below, but if that would extend past the diagram bottom, use above
        val umbraMapBottom = umbraMapTop + umbraMapHeight
        val minYBelowLine = -eclipticY + cy + labelHeight + 5f * scaleFactor  // Minimum Y if placing below
        val placeAbove = minYBelowLine > umbraMapBottom - 5f  // If below would clip, place above

        var eclipticLabelX = cx - eclipticWidth / 2
        var eclipticLabelY = cy
        found = false

        if (!placeAbove) {
            // Try positions below the ecliptic line
            for (yOffset in 1..20) {
                val testY = -eclipticY + cy + (yOffset * 5f) * scaleFactor
                // Check if this Y would extend below the diagram
                if (testY > umbraMapBottom - 5f) break  // Stop searching below

                // Try X positions from right to left
                for (xOffset in 0..15) {
                    val testX = eclipticX + cx - (xOffset * 20f) * scaleFactor - eclipticWidth / 2
                    val ecLabelTop = testY - labelHeight
                    val ecLabelBottom = testY + 2f

                    if (testX > margin && testX + eclipticWidth < width - margin &&
                        testY > umbraMapTop + labelHeight &&
                        !labelOverlapsLine(testX, eclipticWidth, verticalLineXPositions) &&
                        !rectIntersectsEclipticLine(testX, ecLabelTop, testX + eclipticWidth, ecLabelBottom) &&
                        !rectsOverlap(testX, ecLabelTop, testX + eclipticWidth, ecLabelBottom,
                                      northLabelLeft, northLabelTop, northLabelRight, northLabelBottom)) {
                        eclipticLabelX = testX
                        eclipticLabelY = testY
                        found = true
                        break
                    }
                }
                if (found) break
            }
        }

        // If placing below didn't work or we decided to place above, try above the line
        if (!found) {
            for (yOffset in 1..20) {
                val testY = -eclipticY + cy - (yOffset * 5f) * scaleFactor
                // Check if this Y would extend above the diagram
                if (testY - labelHeight < umbraMapTop + 5f) break  // Stop searching above

                for (xOffset in 0..15) {
                    val testX = eclipticX + cx - (xOffset * 20f) * scaleFactor - eclipticWidth / 2
                    val ecLabelTop = testY - labelHeight
                    val ecLabelBottom = testY + 2f

                    if (testX > margin && testX + eclipticWidth < width - margin &&
                        testY < umbraMapBottom &&
                        !labelOverlapsLine(testX, eclipticWidth, verticalLineXPositions) &&
                        !rectIntersectsEclipticLine(testX, ecLabelTop, testX + eclipticWidth, ecLabelBottom) &&
                        !rectsOverlap(testX, ecLabelTop, testX + eclipticWidth, ecLabelBottom,
                                      northLabelLeft, northLabelTop, northLabelRight, northLabelBottom)) {
                        eclipticLabelX = testX
                        eclipticLabelY = testY
                        found = true
                        break
                    }
                }
                if (found) break
            }
        }

        canvas.nativeCanvas.drawText(eclipticText, eclipticLabelX, eclipticLabelY, labelPaint)
    }
}

private fun getVisibilityLevel(
    latRad: Double, lonRad: Double,
    penStartTJD: Double, penEndTJD: Double,
    parStartTJD: Double, parEndTJD: Double,
    totStartTJD: Double, totEndTJD: Double,
    eclipseType: Int
): Int {
    // Convert radians to degrees for moonAboveHorizon
    val latDeg = Math.toDegrees(latRad)
    val lonDeg = Math.toDegrees(lonRad)

    // Check at key times for each phase
    if (eclipseType == TOTAL_LUNAR_ECLIPSE) {
        if (moonAboveHorizon(totStartTJD, latDeg, lonDeg) ||
            moonAboveHorizon(totEndTJD, latDeg, lonDeg) ||
            moonAboveHorizon((totStartTJD + totEndTJD) / 2.0, latDeg, lonDeg)) {
            return 3
        }
    }

    if (eclipseType >= PARTIAL_LUNAR_ECLIPSE) {
        if (moonAboveHorizon(parStartTJD, latDeg, lonDeg) ||
            moonAboveHorizon(parEndTJD, latDeg, lonDeg)) {
            return 2
        }
    }

    if (moonAboveHorizon(penStartTJD, latDeg, lonDeg) ||
        moonAboveHorizon(penEndTJD, latDeg, lonDeg)) {
        return 1
    }

    return 0
}
