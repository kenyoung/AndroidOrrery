package com.kenyoung.orrery

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.*

// ============================================================================
// CONSTANTS
// ============================================================================

private const val RECORD_SIZE = 110
private const val PARTIAL_SOLAR_ECLIPSE = 0
private const val ANNULAR_SOLAR_ECLIPSE = 1
private const val TOTAL_SOLAR_ECLIPSE = 2
private const val HYBRID_SOLAR_ECLIPSE = 3

private const val EARTH_FLATTENING_SE = 1.0 / 298.257223563
private const val DEG_TO_RAD = Math.PI / 180.0
private const val RAD_TO_DEG = 180.0 / Math.PI
private const val SECONDS_PER_DAY_SE = 86400.0
private const val UNIX_EPOCH_JD_SE = 2440587.5

// Colors for eclipse types
private val TotalColor = Color(0xFF00FF00)     // Green
private val AnnularColor = Color(0xFFFFAA00)   // Orange
private val HybridColor = Color(0xFF00DDDD)    // Cyan
private val PartialColor = Color(0xFFAAAAAA)   // Gray

// ============================================================================
// DATA CLASSES
// ============================================================================

data class SolarEclipse(
    val date: Int,              // YYYYMMDD packed (year*0x10000 + month*0x100 + day)
    val t0: Float,              // Besselian element reference epoch T0 (seconds from 0h TD)
    val deltaT: Int,            // TT - UT (seconds)
    val sarosNum: Short,
    val eclipseType: Int,       // 0=Partial, 1=Annular, 2=Total, 3=Hybrid
    val gamma: Float,           // Shadow axis distance from Earth center
    val magnitude: Float,       // Greatest eclipse magnitude
    val pathWidthKm: Short,     // Central path width (km), 0 for partial
    val centralDuration: Float, // Central line duration (seconds)
    val x: FloatArray,          // Shadow x polynomial [3] (Earth radii)
    val y: FloatArray,          // Shadow y polynomial [3] (Earth radii)
    val d: FloatArray,          // Sun declination polynomial [3] (degrees)
    val mu: FloatArray,         // GHA polynomial [3] (degrees)
    val l1: FloatArray,         // Penumbral radius polynomial [3]
    val l2: FloatArray,         // Umbral radius polynomial [3]
    val tanF1: Float,           // Penumbral cone half-angle tangent
    val tanF2: Float            // Umbral cone half-angle tangent
) {
    val year: Int get() = date / 0x10000
    val month: Int get() = (date and 0xff00) / 0x100
    val day: Int get() = date and 0xff

    val typeString: String get() = when (eclipseType) {
        TOTAL_SOLAR_ECLIPSE -> "Total"
        ANNULAR_SOLAR_ECLIPSE -> "Annular"
        HYBRID_SOLAR_ECLIPSE -> "Hybrid"
        PARTIAL_SOLAR_ECLIPSE -> "Partial"
        else -> "Unknown"
    }

    val typeColor: Color get() = when (eclipseType) {
        TOTAL_SOLAR_ECLIPSE -> TotalColor
        ANNULAR_SOLAR_ECLIPSE -> AnnularColor
        HYBRID_SOLAR_ECLIPSE -> HybridColor
        else -> PartialColor
    }

    fun formatDate(): String = "%02d-%02d-%04d".format(day, month, year)

    fun formatListEntry(): String = "${formatDate()} $typeString"

    /** T0 in hours from midnight (TD) */
    fun t0Hours(): Double = t0.toDouble() / 3600.0

    /** Evaluate polynomial at t hours from T0 */
    fun evalPoly(coeffs: FloatArray, t: Double): Double =
        coeffs[0] + coeffs[1] * t + coeffs[2] * t * t
}

/**
 * Local circumstances of a solar eclipse for a specific observer.
 * Contact times are Julian Dates (UT). NaN means contact doesn't occur.
 */
data class LocalCircumstances(
    val c1: Double,                 // First contact (penumbral start)
    val c2: Double,                 // Second contact (umbral start) — NaN for partial
    val mid: Double,                // Maximum eclipse
    val c3: Double,                 // Third contact (umbral end) — NaN for partial
    val c4: Double,                 // Fourth contact (penumbral end)
    val maxMagnitude: Double,       // Maximum magnitude at observer (0 = no eclipse)
    val maxObscuration: Double,     // Fraction of Sun disk area obscured (0–1)
    val duration: Double,           // Totality/annularity duration (seconds), 0 for partial
    val sunAltC1: Double,           // Sun altitude at C1
    val sunAltC2: Double,           // Sun altitude at C2
    val sunAltMid: Double,          // Sun altitude at max
    val sunAltC3: Double,           // Sun altitude at C3
    val sunAltC4: Double,           // Sun altitude at C4
    val sunAzMid: Double,           // Sun azimuth at max
    val isVisible: Boolean          // At least partial eclipse visible (Sun above horizon)
)

/** Cached shoreline data for world map (same as LunarEclipseScreen) */
data class ShoreSegmentSE(
    val nVertices: Int,
    val lat: ShortArray,
    val lon: ShortArray
)

/** Pre-computed data for rendering the selected eclipse */
private class SolarEclipseRenderData(
    val circumstances: LocalCircumstances,
    val centralPath: List<Pair<Float, Float>>,   // (lat, lon) in degrees
    val eclipseJD: Double,                        // JD of greatest eclipse (UT)
    val eclipseUTHours: Double                    // UT hours of greatest eclipse
)

// ============================================================================
// MODULE STATE (cached data)
// ============================================================================

private var solarEclipses: List<SolarEclipse>? = null
private var shorelineSegmentsSE: List<ShoreSegmentSE>? = null

// ============================================================================
// DATA LOADING
// ============================================================================

private suspend fun loadSolarEclipseData(context: Context): List<SolarEclipse> {
    solarEclipses?.let { return it }

    return withContext(Dispatchers.IO) {
        val eclipses = mutableListOf<SolarEclipse>()
        context.assets.open("solarEclipseCannon").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val nEclipses = bytes.size / RECORD_SIZE

            for (i in 0 until nEclipses) {
                val date = buffer.int
                val t0 = buffer.float
                val deltaT = buffer.int
                val sarosNum = buffer.short
                val eclipseType = buffer.get().toInt()
                val gamma = buffer.float
                val magnitude = buffer.float
                val pathWidthKm = buffer.short
                val centralDuration = buffer.float
                buffer.get() // padding

                val x = FloatArray(3) { buffer.float }
                val y = FloatArray(3) { buffer.float }
                val d = FloatArray(3) { buffer.float }
                val mu = FloatArray(3) { buffer.float }
                val l1 = FloatArray(3) { buffer.float }
                val l2 = FloatArray(3) { buffer.float }
                val tanF1 = buffer.float
                val tanF2 = buffer.float

                eclipses.add(
                    SolarEclipse(
                        date, t0, deltaT, sarosNum, eclipseType,
                        gamma, magnitude, pathWidthKm, centralDuration,
                        x, y, d, mu, l1, l2, tanF1, tanF2
                    )
                )
            }
        }
        solarEclipses = eclipses
        eclipses
    }
}

private suspend fun loadShorelineDataSE(context: Context): List<ShoreSegmentSE> {
    shorelineSegmentsSE?.let { return it }

    return withContext(Dispatchers.IO) {
        val segments = mutableListOf<ShoreSegmentSE>()
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
                segments.add(ShoreSegmentSE(nPairs, lats, lons))
            }
        }
        shorelineSegmentsSE = segments
        segments
    }
}

// ============================================================================
// BESSELIAN ELEMENT COMPUTATION
// ============================================================================

/**
 * Observer's geocentric coordinates used on the fundamental plane.
 * Pre-computed from geodetic latitude using Earth oblateness.
 */
private class GeocentricObserver(latDeg: Double) {
    val rhoCosPhi: Double
    val rhoSinPhi: Double

    init {
        val latRad = latDeg * DEG_TO_RAD
        val u = atan((1.0 - EARTH_FLATTENING_SE) * tan(latRad))
        rhoCosPhi = cos(u)
        rhoSinPhi = (1.0 - EARTH_FLATTENING_SE) * sin(u)
    }
}

/**
 * Compute the shadow distance and related quantities at time t hours from T0.
 *
 * Returns: (delta, L1, L2, sunAlt, sunAz, xi, eta)
 *   delta = distance between shadow axis and observer on fundamental plane
 *   L1 = adjusted penumbral radius at observer
 *   L2 = adjusted umbral radius at observer
 *   sunAlt, sunAz = Sun's altitude/azimuth at observer
 */
private fun shadowDistance(
    eclipse: SolarEclipse,
    t: Double,
    obs: GeocentricObserver,
    lonDeg: Double,
    latDeg: Double
): DoubleArray {
    val xVal = eclipse.evalPoly(eclipse.x, t)
    val yVal = eclipse.evalPoly(eclipse.y, t)
    val dVal = eclipse.evalPoly(eclipse.d, t)  // degrees
    val muVal = eclipse.evalPoly(eclipse.mu, t) // degrees

    val l1Val = eclipse.evalPoly(eclipse.l1, t)
    val l2Val = eclipse.evalPoly(eclipse.l2, t)

    val dRad = dVal * DEG_TO_RAD
    val hDeg = muVal + lonDeg  // Local hour angle of shadow axis (degrees)
    val hRad = hDeg * DEG_TO_RAD

    val sinD = sin(dRad)
    val cosD = cos(dRad)
    val sinH = sin(hRad)
    val cosH = cos(hRad)

    // Observer's fundamental plane coordinates
    val xi = obs.rhoCosPhi * sinH
    val eta = obs.rhoSinPhi * cosD - obs.rhoCosPhi * sinD * cosH
    val zeta = obs.rhoSinPhi * sinD + obs.rhoCosPhi * cosD * cosH

    // Adjusted shadow radii at observer's distance from fundamental plane
    val capL1 = l1Val - zeta * eclipse.tanF1
    val capL2 = l2Val - zeta * eclipse.tanF2

    // Shadow distance
    val dx = xVal - xi
    val dy = yVal - eta
    val delta = sqrt(dx * dx + dy * dy)

    // Sun altitude and azimuth (Sun is approximately in the shadow axis direction)
    val sinAlt = sin(latDeg * DEG_TO_RAD) * sinD +
            cos(latDeg * DEG_TO_RAD) * cosD * cosH
    val alt = asin(sinAlt.coerceIn(-1.0, 1.0)) * RAD_TO_DEG

    val cosAlt = cos(asin(sinAlt.coerceIn(-1.0, 1.0)))
    val az = if (abs(cosAlt) < 1e-10) {
        0.0
    } else {
        val cosAz = (sinD - sinAlt * sin(latDeg * DEG_TO_RAD)) /
                (cosAlt * cos(latDeg * DEG_TO_RAD))
        val azAbs = acos(cosAz.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
        if (sinH > 0) 360.0 - azAbs else azAbs
    }

    return doubleArrayOf(delta, capL1, capL2, alt, az, xi, eta)
}

/**
 * Find the time of maximum eclipse for the observer.
 * Scans t in small steps and finds minimum shadow distance.
 * Returns t (hours from T0).
 */
private fun findMaxEclipse(
    eclipse: SolarEclipse,
    obs: GeocentricObserver,
    lonDeg: Double,
    latDeg: Double
): Double {
    // Coarse scan: 30-second steps from -4 to +4 hours
    var bestT = 0.0
    var bestDelta = Double.MAX_VALUE
    var t = -4.0
    while (t <= 4.0) {
        val result = shadowDistance(eclipse, t, obs, lonDeg, latDeg)
        if (result[0] < bestDelta) {
            bestDelta = result[0]
            bestT = t
        }
        t += 30.0 / 3600.0
    }

    // Fine scan: 1-second steps around best
    val tLo = bestT - 60.0 / 3600.0
    val tHi = bestT + 60.0 / 3600.0
    t = tLo
    while (t <= tHi) {
        val result = shadowDistance(eclipse, t, obs, lonDeg, latDeg)
        if (result[0] < bestDelta) {
            bestDelta = result[0]
            bestT = t
        }
        t += 1.0 / 3600.0
    }

    return bestT
}

/**
 * Find a contact time by bisection.
 * contactRadius: L1 for penumbral contacts (C1/C4), |L2| for umbral contacts (C2/C3).
 * searchBefore: true for C1/C2 (before max), false for C3/C4 (after max).
 * Returns t (hours from T0), or NaN if contact not found.
 */
private fun findContact(
    eclipse: SolarEclipse,
    obs: GeocentricObserver,
    lonDeg: Double,
    latDeg: Double,
    tMax: Double,
    usePenumbral: Boolean,
    searchBefore: Boolean
): Double {
    // Scan range
    val scanStart = if (searchBefore) tMax - 4.0 else tMax
    val scanEnd = if (searchBefore) tMax else tMax + 4.0
    val step = 1.0 / 60.0  // 1 minute

    var prevT = scanStart
    var prevResult = shadowDistance(eclipse, prevT, obs, lonDeg, latDeg)
    var prevDiff = prevResult[0] - if (usePenumbral) prevResult[1] else abs(prevResult[2])

    var t = scanStart + step
    while (t <= scanEnd) {
        val result = shadowDistance(eclipse, t, obs, lonDeg, latDeg)
        val radius = if (usePenumbral) result[1] else abs(result[2])
        val diff = result[0] - radius

        if (prevDiff * diff < 0) {
            // Sign change: bisect to find exact crossing
            var lo = prevT
            var hi = t
            for (iter in 0 until 30) {
                val mid = (lo + hi) / 2.0
                val midResult = shadowDistance(eclipse, mid, obs, lonDeg, latDeg)
                val midRadius = if (usePenumbral) midResult[1] else abs(midResult[2])
                val midDiff = midResult[0] - midRadius

                if (prevDiff * midDiff < 0) {
                    hi = mid
                } else {
                    lo = mid
                    prevDiff = midDiff
                }
            }
            return (lo + hi) / 2.0
        }

        prevT = t
        prevDiff = diff
        t += step
    }

    return Double.NaN
}

/**
 * Convert time t (hours from T0) to Julian Date (UT).
 */
private fun tToJD(eclipse: SolarEclipse, t: Double): Double {
    val t0Hours = eclipse.t0Hours()
    val tdHours = t0Hours + t
    // T0 is in Terrestrial Dynamical Time; convert to UT
    val utHours = tdHours - eclipse.deltaT.toDouble() / 3600.0
    // Build JD from the eclipse date
    val y = eclipse.year
    val m = eclipse.month
    val day = eclipse.day
    // Julian Day Number for the date at 0h UT
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    val jdn = day + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    return jdn.toDouble() - 0.5 + utHours / 24.0
}

/**
 * Compute Sun altitude at time t (hours from T0) for the observer.
 */
private fun sunAltAtT(
    eclipse: SolarEclipse,
    t: Double,
    obs: GeocentricObserver,
    lonDeg: Double,
    latDeg: Double
): Double {
    val result = shadowDistance(eclipse, t, obs, lonDeg, latDeg)
    return result[3]  // altitude
}

/**
 * Compute complete local circumstances of a solar eclipse for an observer.
 */
private fun computeLocalCircumstances(
    eclipse: SolarEclipse,
    latDeg: Double,
    lonDeg: Double
): LocalCircumstances {
    val obs = GeocentricObserver(latDeg)

    // Find time of maximum eclipse
    val tMax = findMaxEclipse(eclipse, obs, lonDeg, latDeg)
    val maxResult = shadowDistance(eclipse, tMax, obs, lonDeg, latDeg)
    val maxDelta = maxResult[0]
    val maxL1 = maxResult[1]
    val maxL2 = maxResult[2]
    val sunAltMax = maxResult[3]
    val sunAzMax = maxResult[4]

    // Maximum magnitude: (L1 - Delta) / (L1 + L2)
    val maxMag = if (maxL1 + maxL2 != 0.0) {
        ((maxL1 - maxDelta) / (maxL1 + maxL2)).coerceAtLeast(0.0)
    } else 0.0

    if (maxMag <= 0.0) {
        // No eclipse at this location
        return LocalCircumstances(
            Double.NaN, Double.NaN, tToJD(eclipse, tMax),
            Double.NaN, Double.NaN,
            0.0, 0.0, 0.0,
            Double.NaN, Double.NaN, sunAltMax, Double.NaN, Double.NaN,
            sunAzMax, false
        )
    }

    // Compute obscuration from magnitude
    // For uniform disk: obscuration = 1 - cos(a)/pi where a relates to magnitude
    // Simplified formula using magnitude and size ratio
    val k = abs(maxL1 - maxL2) / (maxL1 + maxL2)  // Moon/Sun angular size ratio
    val obscuration = computeObscuration(maxMag, k)

    // Find contact times
    val tC1 = findContact(eclipse, obs, lonDeg, latDeg, tMax, usePenumbral = true, searchBefore = true)
    val tC4 = findContact(eclipse, obs, lonDeg, latDeg, tMax, usePenumbral = true, searchBefore = false)

    var tC2 = Double.NaN
    var tC3 = Double.NaN
    var duration = 0.0

    // Umbral contacts only if observer is within umbral shadow
    if (maxDelta <= abs(maxL2)) {
        tC2 = findContact(eclipse, obs, lonDeg, latDeg, tMax, usePenumbral = false, searchBefore = true)
        tC3 = findContact(eclipse, obs, lonDeg, latDeg, tMax, usePenumbral = false, searchBefore = false)
        if (!tC2.isNaN() && !tC3.isNaN()) {
            duration = (tC3 - tC2) * 3600.0  // seconds
        }
    }

    // Sun altitude at contacts
    val altC1 = if (!tC1.isNaN()) sunAltAtT(eclipse, tC1, obs, lonDeg, latDeg) else Double.NaN
    val altC2 = if (!tC2.isNaN()) sunAltAtT(eclipse, tC2, obs, lonDeg, latDeg) else Double.NaN
    val altC3 = if (!tC3.isNaN()) sunAltAtT(eclipse, tC3, obs, lonDeg, latDeg) else Double.NaN
    val altC4 = if (!tC4.isNaN()) sunAltAtT(eclipse, tC4, obs, lonDeg, latDeg) else Double.NaN

    // Eclipse is visible if Sun is above horizon during at least part of the eclipse
    val isVisible = sunAltMax > 0.0 ||
            (!altC1.isNaN() && altC1 > 0.0) ||
            (!altC4.isNaN() && altC4 > 0.0)

    return LocalCircumstances(
        c1 = if (!tC1.isNaN()) tToJD(eclipse, tC1) else Double.NaN,
        c2 = if (!tC2.isNaN()) tToJD(eclipse, tC2) else Double.NaN,
        mid = tToJD(eclipse, tMax),
        c3 = if (!tC3.isNaN()) tToJD(eclipse, tC3) else Double.NaN,
        c4 = if (!tC4.isNaN()) tToJD(eclipse, tC4) else Double.NaN,
        maxMagnitude = maxMag,
        maxObscuration = obscuration,
        duration = duration,
        sunAltC1 = altC1,
        sunAltC2 = altC2,
        sunAltMid = sunAltMax,
        sunAltC3 = altC3,
        sunAltC4 = altC4,
        sunAzMid = sunAzMax,
        isVisible = isVisible
    )
}

/**
 * Compute the fraction of Sun's disk obscured given eclipse magnitude and size ratio.
 * k = Moon angular radius / Sun angular radius.
 * mag = eclipse magnitude (fraction of Sun diameter covered along the central line).
 */
private fun computeObscuration(mag: Double, k: Double): Double {
    if (mag <= 0.0) return 0.0
    if (mag >= 1.0 && k >= 1.0) return 1.0  // total

    // For annular (k < 1): when fully inside, obscuration = k^2
    if (mag >= 1.0 && k < 1.0) return k * k

    // Partial: use the geometric intersection of two circles
    // Sun radius = 1, Moon radius = k, center distance = 1 + k - 2*mag*1
    // Actually: distance between centers d = (1 + k) * (1 - mag) when mag = (1+k-d)/(1+k)... no
    // mag = (s + m - d) / (2*s) where s = Sun radius, m = Moon radius, d = center distance
    // So d = s + m - 2*s*mag = 1 + k - 2*mag
    val d = (1.0 + k - 2.0 * mag).coerceAtLeast(0.0)
    if (d >= 1.0 + k) return 0.0

    // Area of intersection of two circles with radii 1 and k, separated by d
    val r1 = 1.0
    val r2 = k
    if (d <= abs(r1 - r2)) {
        return if (r2 <= r1) r2 * r2 else 1.0
    }

    val part1 = r1 * r1 * acos(((d * d + r1 * r1 - r2 * r2) / (2.0 * d * r1)).coerceIn(-1.0, 1.0))
    val part2 = r2 * r2 * acos(((d * d + r2 * r2 - r1 * r1) / (2.0 * d * r2)).coerceIn(-1.0, 1.0))
    val part3 = 0.5 * sqrt(
        ((-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2)).coerceAtLeast(0.0)
    )

    val intersectionArea = part1 + part2 - part3
    return (intersectionArea / (Math.PI * r1 * r1)).coerceIn(0.0, 1.0)
}

/**
 * Quick check: is this eclipse potentially visible from the given location?
 * Uses a simplified check on Sun altitude at greatest eclipse time.
 */
private fun isEclipseLocallyVisible(
    eclipse: SolarEclipse,
    latDeg: Double,
    lonDeg: Double
): Boolean {
    val obs = GeocentricObserver(latDeg)

    // Check at T0 (close to greatest eclipse) and ±1 hour
    for (dt in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
        val result = shadowDistance(eclipse, dt, obs, lonDeg, latDeg)
        val delta = result[0]
        val l1 = result[1]
        val sunAlt = result[3]

        // Eclipse visible if observer is within penumbral shadow and Sun is up
        if (delta < l1 && sunAlt > 0.0) return true
    }
    return false
}

// ============================================================================
// ECLIPSE PATH COMPUTATION
// ============================================================================

/**
 * Compute the central path of the eclipse as a list of (lat, lon) points.
 * Only meaningful for non-partial eclipses.
 */
private fun computeCentralPath(eclipse: SolarEclipse): List<Pair<Float, Float>> {
    if (eclipse.eclipseType == PARTIAL_SOLAR_ECLIPSE) return emptyList()

    val path = mutableListOf<Pair<Float, Float>>()

    // Scan from -3 to +3 hours in 2-minute steps
    var t = -3.0
    while (t <= 3.0) {
        val xVal = eclipse.evalPoly(eclipse.x, t)
        val yVal = eclipse.evalPoly(eclipse.y, t)
        val dVal = eclipse.evalPoly(eclipse.d, t)

        // Check if shadow touches Earth (spherical approximation)
        val r2 = xVal * xVal + yVal * yVal
        if (r2 < 1.0) {
            val zeta = sqrt(1.0 - r2)
            val dRad = dVal * DEG_TO_RAD
            val sinD = sin(dRad)
            val cosD = cos(dRad)

            // Sub-shadow latitude
            val sinPhi = yVal * cosD + zeta * sinD
            val phi = asin(sinPhi.coerceIn(-1.0, 1.0)) * RAD_TO_DEG

            // Sub-shadow longitude
            val muVal = eclipse.evalPoly(eclipse.mu, t)
            val hRad = atan2(xVal, zeta * cosD - yVal * sinD)
            var lon = hRad * RAD_TO_DEG - muVal
            // Normalize longitude to [-180, 180]
            while (lon > 180.0) lon -= 360.0
            while (lon < -180.0) lon += 360.0

            path.add(Pair(phi.toFloat(), lon.toFloat()))
        }
        t += 2.0 / 60.0
    }

    return path
}

/**
 * Compute render data for the selected eclipse.
 */
private fun computeRenderData(
    eclipse: SolarEclipse,
    latDeg: Double,
    lonDeg: Double
): SolarEclipseRenderData {
    val circumstances = computeLocalCircumstances(eclipse, latDeg, lonDeg)
    val centralPath = computeCentralPath(eclipse)
    val eclipseUTHours = (eclipse.t0Hours() - eclipse.deltaT.toDouble() / 3600.0).let {
        if (it < 0) it + 24.0 else if (it >= 24.0) it - 24.0 else it
    }
    val eclipseJD = tToJD(eclipse, 0.0)

    return SolarEclipseRenderData(circumstances, centralPath, eclipseJD, eclipseUTHours)
}

// ============================================================================
// FILTERING
// ============================================================================

private fun filterEclipses(
    eclipses: List<SolarEclipse>,
    startYear: Int,
    endYear: Int,
    showPartial: Boolean,
    showAnnular: Boolean,
    showTotal: Boolean,
    showHybrid: Boolean,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double
): List<SolarEclipse> {
    return eclipses.filter { e ->
        e.year in startYear until endYear &&
                when (e.eclipseType) {
                    PARTIAL_SOLAR_ECLIPSE -> showPartial
                    ANNULAR_SOLAR_ECLIPSE -> showAnnular
                    TOTAL_SOLAR_ECLIPSE -> showTotal
                    HYBRID_SOLAR_ECLIPSE -> showHybrid
                    else -> false
                } &&
                (!localOnly || isEclipseLocallyVisible(e, latitude, longitude))
    }
}

// ============================================================================
// COMPOSABLES
// ============================================================================

@Composable
fun SolarEclipseScreen(
    latitude: Double,
    longitude: Double,
    now: Instant,
    stdOffsetHours: Double,
    stdTimeLabel: String,
    useStandardTime: Boolean,
    onTimeDisplayChange: (Boolean) -> Unit
) {
    val context = LocalContext.current

    var eclipses by remember { mutableStateOf<List<SolarEclipse>?>(null) }
    var shoreline by remember { mutableStateOf<List<ShoreSegmentSE>?>(null) }
    var selectedEclipse by remember { mutableStateOf<SolarEclipse?>(null) }

    // Decade range state
    var decadeStart by remember { mutableStateOf(2020) }

    // Filter toggles
    var showPartial by remember { mutableStateOf(false) }
    var showAnnular by remember { mutableStateOf(true) }
    var showTotal by remember { mutableStateOf(true) }
    var showHybrid by remember { mutableStateOf(true) }
    var localOnly by remember { mutableStateOf(false) }

    // Load data
    LaunchedEffect(Unit) {
        eclipses = loadSolarEclipseData(context)
        shoreline = loadShorelineDataSE(context)
    }

    if (eclipses == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    if (selectedEclipse != null) {
        SolarEclipseDetailView(
            eclipse = selectedEclipse!!,
            shoreline = shoreline ?: emptyList(),
            latitude = latitude,
            longitude = longitude,
            stdOffsetHours = stdOffsetHours,
            stdTimeLabel = stdTimeLabel,
            useStandardTime = useStandardTime,
            onTimeDisplayChange = onTimeDisplayChange,
            onBack = { selectedEclipse = null }
        )
    } else {
        SolarEclipseSelectionView(
            eclipses = eclipses!!,
            decadeStart = decadeStart,
            showPartial = showPartial,
            showAnnular = showAnnular,
            showTotal = showTotal,
            showHybrid = showHybrid,
            localOnly = localOnly,
            latitude = latitude,
            longitude = longitude,
            onDecadeChange = { decadeStart = it },
            onPartialToggle = { showPartial = it },
            onAnnularToggle = { showAnnular = it },
            onTotalToggle = { showTotal = it },
            onHybridToggle = { showHybrid = it },
            onLocalOnlyToggle = { localOnly = it },
            onEclipseSelected = { selectedEclipse = it }
        )
    }
}

@Composable
private fun SolarEclipseSelectionView(
    eclipses: List<SolarEclipse>,
    decadeStart: Int,
    showPartial: Boolean,
    showAnnular: Boolean,
    showTotal: Boolean,
    showHybrid: Boolean,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double,
    onDecadeChange: (Int) -> Unit,
    onPartialToggle: (Boolean) -> Unit,
    onAnnularToggle: (Boolean) -> Unit,
    onTotalToggle: (Boolean) -> Unit,
    onHybridToggle: (Boolean) -> Unit,
    onLocalOnlyToggle: (Boolean) -> Unit,
    onEclipseSelected: (SolarEclipse) -> Unit
) {
    val decadeEnd = decadeStart + 10
    val minDecade = 1900
    val maxDecade = 2090

    val canGoBack = decadeStart > minDecade
    val canGoForward = decadeStart < maxDecade

    val filteredEclipses = remember(
        eclipses, decadeStart, showPartial, showAnnular, showTotal, showHybrid,
        localOnly, latitude, longitude
    ) {
        filterEclipses(
            eclipses, decadeStart, decadeEnd,
            showPartial, showAnnular, showTotal, showHybrid,
            localOnly, latitude, longitude
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Title
        Text(
            text = "Solar Eclipses",
            color = LabelColor,
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

            Text(
                text = "$decadeStart → $decadeEnd",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

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

        // Filter toggles - row 1: type filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
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
                selected = showAnnular,
                onClick = { onAnnularToggle(!showAnnular) },
                label = { Text("Annular", fontSize = 11.sp) },
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
                selected = showHybrid,
                onClick = { onHybridToggle(!showHybrid) },
                label = { Text("Hybrid", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A4A4A),
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.LightGray,
                    selectedLabelColor = Color.White
                )
            )
        }

        // Row 2: local only toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
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

        Text(
            text = "${filteredEclipses.size} eclipses found - tap date to select one",
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Eclipse list
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredEclipses) { eclipse ->
                SolarEclipseListItem(
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
private fun SolarEclipseListItem(
    eclipse: SolarEclipse,
    localOnly: Boolean,
    latitude: Double,
    longitude: Double,
    onClick: () -> Unit
) {
    val isVisible = localOnly || isEclipseLocallyVisible(eclipse, latitude, longitude)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = eclipse.formatListEntry(),
            color = eclipse.typeColor,
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
private fun SolarEclipseDetailView(
    eclipse: SolarEclipse,
    shoreline: List<ShoreSegmentSE>,
    latitude: Double,
    longitude: Double,
    stdOffsetHours: Double,
    stdTimeLabel: String,
    useStandardTime: Boolean,
    onTimeDisplayChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showCanvas by remember { mutableStateOf(false) }

    val renderData = remember(eclipse, latitude, longitude) {
        computeRenderData(eclipse, latitude, longitude)
    }

    val totalEclipseBitmap = remember {
        context.assets.open("TotalSolarEclipse.png").use { inputStream ->
            BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
        }
    }

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
            Column(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    renderSolarEclipse(
                        this, renderData, eclipse, shoreline,
                        latitude, longitude,
                        useStandardTime, stdOffsetHours, stdTimeLabel,
                        totalEclipseBitmap
                    )
                }

                // Bottom row: Back button + time zone radio buttons
                Row(
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(
                            "← Back", color = Color(0xFF00BFFF),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onTimeDisplayChange(false) }
                    ) {
                        RadioButton(
                            selected = !useStandardTime,
                            onClick = { onTimeDisplayChange(false) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF00BFFF),
                                unselectedColor = Color.Gray
                            )
                        )
                        Text("UT", color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onTimeDisplayChange(true) }
                    ) {
                        RadioButton(
                            selected = useStandardTime,
                            onClick = { onTimeDisplayChange(true) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF00BFFF),
                                unselectedColor = Color.Gray
                            )
                        )
                        Text("Standard Time", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Computing Eclipse...", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

// ============================================================================
// ECLIPSE RENDERING
// ============================================================================

private fun renderSolarEclipse(
    drawScope: DrawScope,
    data: SolarEclipseRenderData,
    eclipse: SolarEclipse,
    shoreline: List<ShoreSegmentSE>,
    userLat: Double,
    userLon: Double,
    useStandardTime: Boolean,
    stdOffsetHours: Double,
    stdTimeLabel: String,
    totalEclipseBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val timeOffset = if (useStandardTime) stdOffsetHours else 0.0
    val timeSuffix = if (useStandardTime) stdTimeLabel else "UT"
    val w = drawScope.size.width
    val h = drawScope.size.height
    val circ = data.circumstances

    // Paint setup
    val textPaint = Paint().apply {
        color = Color.White.toArgb()
        textSize = (w / 30f).coerceIn(12f, 18f)
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    val smallTextPaint = Paint().apply {
        color = Color.White.toArgb()
        textSize = (w / 38f).coerceIn(10f, 14f)
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    val labelPaint = Paint().apply {
        color = LabelColor.toArgb()
        textSize = (w / 30f).coerceIn(12f, 18f)
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    val lineH = textPaint.descent() - textPaint.ascent()
    val smallLineH = smallTextPaint.descent() - smallTextPaint.ascent()

    // Layout: top section = eclipse info, middle = geometry diagram, bottom = world map
    val infoHeight = lineH * 12f
    val geomTop = infoHeight
    val geomHeight = (h - infoHeight) * 0.35f
    val mapTop = geomTop + geomHeight + lineH
    val mapHeight = h - mapTop

    drawScope.drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        var yPos = -textPaint.ascent()

        // === ECLIPSE INFO TEXT ===
        val typeColor = eclipse.typeColor.toArgb()

        // Title line
        labelPaint.color = typeColor
        nativeCanvas.drawText(
            "${eclipse.typeString} Solar Eclipse", 10f, yPos, labelPaint
        )
        labelPaint.color = LabelColor.toArgb()
        yPos += lineH

        // Date and time
        nativeCanvas.drawText(
            "Date: ${eclipse.formatDate()}", 10f, yPos, textPaint
        )
        yPos += lineH

        nativeCanvas.drawText(
            "Greatest Eclipse: ${formatHHMMSS(data.eclipseUTHours + timeOffset)} $timeSuffix",
            10f, yPos, textPaint
        )
        yPos += lineH

        // Gamma and Saros
        nativeCanvas.drawText(
            "Gamma: ${"%.4f".format(eclipse.gamma.toDouble())}   Saros: ${eclipse.sarosNum}",
            10f, yPos, textPaint
        )
        yPos += lineH

        // Global magnitude and path width
        val widthStr = if (eclipse.pathWidthKm > 0) "${eclipse.pathWidthKm} km" else "N/A"
        nativeCanvas.drawText(
            "Magnitude: ${"%.4f".format(eclipse.magnitude.toDouble())}   Path Width: $widthStr",
            10f, yPos, textPaint
        )
        yPos += lineH

        // Central duration
        if (eclipse.centralDuration > 0) {
            val durMin = (eclipse.centralDuration / 60).toInt()
            val durSec = (eclipse.centralDuration % 60).toInt()
            nativeCanvas.drawText(
                "Central Duration: ${durMin}m ${durSec}s", 10f, yPos, textPaint
            )
        }
        yPos += lineH

        // --- Local circumstances ---
        labelPaint.color = LabelColor.toArgb()
        nativeCanvas.drawText("--- Local Circumstances ---", 10f, yPos, labelPaint)
        yPos += lineH

        if (circ.maxMagnitude <= 0.0) {
            textPaint.color = Color.Red.toArgb()
            nativeCanvas.drawText("Eclipse not visible from this location", 10f, yPos, textPaint)
            textPaint.color = Color.White.toArgb()
            yPos += lineH
        } else {
            // Magnitude and obscuration
            nativeCanvas.drawText(
                "Max Magnitude: ${"%.3f".format(circ.maxMagnitude)}   " +
                        "Obscuration: ${"%.1f".format(circ.maxObscuration * 100)}%%",
                10f, yPos, textPaint
            )
            yPos += lineH

            // Contact times
            if (!circ.c1.isNaN()) {
                val c1Str = formatJDTime(circ.c1, timeOffset)
                val altStr = if (!circ.sunAltC1.isNaN()) " (alt ${"%.0f".format(circ.sunAltC1)}°)" else ""
                nativeCanvas.drawText("C1 (partial begins): $c1Str $timeSuffix$altStr", 10f, yPos, smallTextPaint)
                yPos += smallLineH
            }
            if (!circ.c2.isNaN()) {
                val c2Str = formatJDTime(circ.c2, timeOffset)
                val altStr = if (!circ.sunAltC2.isNaN()) " (alt ${"%.0f".format(circ.sunAltC2)}°)" else ""
                nativeCanvas.drawText("C2 (total/annular begins): $c2Str $timeSuffix$altStr", 10f, yPos, smallTextPaint)
                yPos += smallLineH
            }
            val midStr = formatJDTime(circ.mid, timeOffset)
            val midAltStr = " (alt ${"%.0f".format(circ.sunAltMid)}°)"
            nativeCanvas.drawText("Max eclipse: $midStr $timeSuffix$midAltStr", 10f, yPos, smallTextPaint)
            yPos += smallLineH

            if (!circ.c3.isNaN()) {
                val c3Str = formatJDTime(circ.c3, timeOffset)
                val altStr = if (!circ.sunAltC3.isNaN()) " (alt ${"%.0f".format(circ.sunAltC3)}°)" else ""
                nativeCanvas.drawText("C3 (total/annular ends): $c3Str $timeSuffix$altStr", 10f, yPos, smallTextPaint)
                yPos += smallLineH
            }
            if (!circ.c4.isNaN()) {
                val c4Str = formatJDTime(circ.c4, timeOffset)
                val altStr = if (!circ.sunAltC4.isNaN()) " (alt ${"%.0f".format(circ.sunAltC4)}°)" else ""
                nativeCanvas.drawText("C4 (partial ends): $c4Str $timeSuffix$altStr", 10f, yPos, smallTextPaint)
                yPos += smallLineH
            }

            // Duration of totality/annularity
            if (circ.duration > 0) {
                val durMin = (circ.duration / 60).toInt()
                val durSec = (circ.duration % 60).toInt()
                nativeCanvas.drawText(
                    "Duration: ${durMin}m ${durSec}s", 10f, yPos, textPaint
                )
                yPos += lineH
            }
        }
    }

    // === ECLIPSE GEOMETRY DIAGRAM ===
    drawEclipseGeometry(drawScope, eclipse, data, geomTop, geomHeight, w, totalEclipseBitmap)

    // === WORLD MAP ===
    drawWorldMap(drawScope, eclipse, data, shoreline, userLat, userLon, mapTop, mapHeight, w)
}

// ============================================================================
// ECLIPSE GEOMETRY DIAGRAM
// ============================================================================

/**
 * Draw Sun/Moon disk geometry at maximum eclipse for the observer.
 */
private fun drawEclipseGeometry(
    drawScope: DrawScope,
    eclipse: SolarEclipse,
    data: SolarEclipseRenderData,
    top: Float,
    height: Float,
    width: Float,
    totalEclipseBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val circ = data.circumstances
    val cx = width / 2f
    val cy = top + height / 2f

    // Sun disk radius (pixels)
    val sunR = (height * 0.35f).coerceAtMost(width * 0.2f)

    // Moon/Sun angular size ratio from Besselian elements at maximum eclipse
    // L1 ≈ semi_sun + semi_moon, L2 ≈ semi_sun - semi_moon (in Earth radii on fundamental plane)
    val l1Mid = eclipse.evalPoly(eclipse.l1, 0.0)
    val l2Mid = eclipse.evalPoly(eclipse.l2, 0.0)
    val k = if (l1Mid + l2Mid != 0.0) abs(l1Mid - l2Mid) / (l1Mid + l2Mid) else 1.0
    val moonR = (sunR * k).toFloat()

    // Distance between centers (proportional to magnitude)
    // At maximum, distance = sunR + moonR - 2 * sunR * magnitude (for Sun radius = 1)
    val mag = circ.maxMagnitude

    // Background
    drawScope.drawRect(
        Color(0xFF0A0A20),
        topLeft = Offset(0f, top),
        size = androidx.compose.ui.geometry.Size(width, height)
    )

    // If total at observer's location, show the photograph instead of the drawing
    if (circ.maxObscuration >= 0.999 && totalEclipseBitmap != null) {
        // Scale the image to fit the same area the drawing would occupy (2 * sunR * 1.3)
        val displayDiameter = (sunR * 2.6f).toInt()
        val bmpW = totalEclipseBitmap.width
        val bmpH = totalEclipseBitmap.height
        val scale = displayDiameter.toFloat() / maxOf(bmpW, bmpH)
        val dstW = (bmpW * scale).toInt()
        val dstH = (bmpH * scale).toInt()

        drawScope.drawImage(
            totalEclipseBitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bmpW, bmpH),
            dstOffset = IntOffset(
                (cx - dstW / 2f).toInt(),
                (cy - dstH / 2f).toInt()
            ),
            dstSize = IntSize(dstW, dstH)
        )
    } else {
        val separation = if (mag > 0) {
            ((1.0 + k - 2.0 * mag) * sunR).toFloat()
        } else {
            (sunR + moonR + 20f)
        }

        // Sun disk (yellow-white)
        drawScope.drawCircle(
            Color(0xFFFFF8B0),
            radius = sunR,
            center = Offset(cx, cy)
        )

        // Moon disk position: approach from the position angle
        // Simplified: Moon comes from the west (left) side
        val moonCx = cx - separation
        val moonCy = cy

        // Moon disk (dark)
        drawScope.drawCircle(
            Color(0xFF1A1A1A),
            radius = moonR,
            center = Offset(moonCx, moonCy)
        )
        // Moon edge
        drawScope.drawCircle(
            Color(0xFF555555),
            radius = moonR,
            center = Offset(moonCx, moonCy),
            style = Stroke(width = 1f)
        )
    }

    // Label
    drawScope.drawIntoCanvas { canvas ->
        val labelPaint = Paint().apply {
            color = eclipse.typeColor.toArgb()
            textSize = (width / 28f).coerceIn(12f, 18f)
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val label = if (mag > 0) {
            "Magnitude: ${"%.3f".format(mag)}  Obscuration: ${"%.1f".format(circ.maxObscuration * 100)}%%"
        } else {
            "Not visible from observer location"
        }

        canvas.nativeCanvas.drawText(
            label, cx, top + height - 8f, labelPaint
        )
    }
}

// ============================================================================
// WORLD MAP
// ============================================================================

/**
 * Draw equirectangular world map with eclipse central path and observer location.
 */
private fun drawWorldMap(
    drawScope: DrawScope,
    eclipse: SolarEclipse,
    data: SolarEclipseRenderData,
    shoreline: List<ShoreSegmentSE>,
    userLat: Double,
    userLon: Double,
    mapTop: Float,
    mapHeight: Float,
    mapWidth: Float
) {
    // Map dimensions (2:1 aspect ratio for equirectangular)
    val mw = mapWidth
    val mh = mapHeight.coerceAtMost(mw / 2f)
    val mTop = mapTop + (mapHeight - mh) / 2f

    // Background
    drawScope.drawRect(
        Color(0xFF000040),
        topLeft = Offset(0f, mTop),
        size = androidx.compose.ui.geometry.Size(mw, mh)
    )

    // Coordinate conversion helpers
    fun lonToX(lon: Double): Float = ((lon + 180.0) / 360.0 * mw).toFloat()
    fun latToY(lat: Double): Float = ((90.0 - lat) / 180.0 * mh + mTop).toFloat()

    // Draw shoreline
    val shoreColor = Color.White
    for (seg in shoreline) {
        for (j in 0 until seg.nVertices - 1) {
            val lat1 = seg.lat[j].toDouble() * Math.PI / 65535.0 * RAD_TO_DEG
            val lon1 = seg.lon[j].toDouble() * Math.PI / 32767.0 * RAD_TO_DEG
            val lat2 = seg.lat[j + 1].toDouble() * Math.PI / 65535.0 * RAD_TO_DEG
            val lon2 = seg.lon[j + 1].toDouble() * Math.PI / 32767.0 * RAD_TO_DEG

            val x1 = lonToX(lon1)
            val y1 = latToY(lat1)
            val x2 = lonToX(lon2)
            val y2 = latToY(lat2)

            // Skip segments that wrap around the map
            if (abs(x2 - x1) < mw * 0.5f) {
                drawScope.drawLine(
                    shoreColor,
                    Offset(x1, y1),
                    Offset(x2, y2),
                    strokeWidth = 1f
                )
            }
        }
    }

    // Draw central path
    if (data.centralPath.size >= 2) {
        val pathColor = when (eclipse.eclipseType) {
            TOTAL_SOLAR_ECLIPSE -> TotalColor
            ANNULAR_SOLAR_ECLIPSE -> AnnularColor
            HYBRID_SOLAR_ECLIPSE -> HybridColor
            else -> PartialColor
        }

        for (i in 0 until data.centralPath.size - 1) {
            val (lat1, lon1) = data.centralPath[i]
            val (lat2, lon2) = data.centralPath[i + 1]

            val x1 = lonToX(lon1.toDouble())
            val y1 = latToY(lat1.toDouble())
            val x2 = lonToX(lon2.toDouble())
            val y2 = latToY(lat2.toDouble())

            // Skip wrap-around segments
            if (abs(x2 - x1) < mw * 0.4f) {
                drawScope.drawLine(
                    pathColor,
                    Offset(x1, y1),
                    Offset(x2, y2),
                    strokeWidth = 3f
                )
            }
        }

        // Draw path width band (approximate)
        if (eclipse.pathWidthKm > 0) {
            // Approximate: 1 degree latitude ≈ 111 km
            val halfWidthDeg = eclipse.pathWidthKm.toDouble() / 111.0 / 2.0
            val bandColor = pathColor.copy(alpha = 0.2f)

            for (i in 0 until data.centralPath.size - 1) {
                val (lat1, lon1) = data.centralPath[i]
                val (lat2, lon2) = data.centralPath[i + 1]

                val x1 = lonToX(lon1.toDouble())
                val x2 = lonToX(lon2.toDouble())
                val yTop1 = latToY(lat1.toDouble() + halfWidthDeg)
                val yBot1 = latToY(lat1.toDouble() - halfWidthDeg)

                if (abs(x2 - x1) < mw * 0.4f) {
                    drawScope.drawRect(
                        bandColor,
                        topLeft = Offset(minOf(x1, x2), yTop1),
                        size = androidx.compose.ui.geometry.Size(
                            abs(x2 - x1).coerceAtLeast(2f),
                            (yBot1 - yTop1).coerceAtLeast(2f)
                        )
                    )
                }
            }
        }
    }

    // Draw equator and prime meridian (subtle guides)
    drawScope.drawLine(
        Color(0xFF333333),
        Offset(0f, latToY(0.0)),
        Offset(mw, latToY(0.0)),
        strokeWidth = 0.5f
    )
    drawScope.drawLine(
        Color(0xFF333333),
        Offset(lonToX(0.0), mTop),
        Offset(lonToX(0.0), mTop + mh),
        strokeWidth = 0.5f
    )

    // Observer location (red dot)
    val obsX = lonToX(userLon)
    val obsY = latToY(userLat)
    drawScope.drawCircle(
        Color.Red,
        radius = 5f,
        center = Offset(obsX, obsY)
    )
    // Red crosshair
    drawScope.drawLine(Color.Red, Offset(obsX - 8f, obsY), Offset(obsX + 8f, obsY), strokeWidth = 1f)
    drawScope.drawLine(Color.Red, Offset(obsX, obsY - 8f), Offset(obsX, obsY + 8f), strokeWidth = 1f)

    // Map border
    drawScope.drawRect(
        Color(0xFF555555),
        topLeft = Offset(0f, mTop),
        size = androidx.compose.ui.geometry.Size(mw, mh),
        style = Stroke(width = 1f)
    )
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Format decimal hours as HH:MM:SS.
 */
private fun formatHHMMSS(hours: Double): String {
    var h = hours
    while (h < 0) h += 24.0
    while (h >= 24.0) h -= 24.0
    val hh = h.toInt()
    val mm = ((h - hh) * 60).toInt()
    val ss = ((h - hh - mm / 60.0) * 3600).toInt().coerceIn(0, 59)
    return "%02d:%02d:%02d".format(hh, mm, ss)
}

/**
 * Format a Julian Date as HH:MM:SS with time offset.
 */
private fun formatJDTime(jd: Double, offsetHours: Double): String {
    val utHours = (jd - floor(jd)) * 24.0 + 12.0  // JD starts at noon
    return formatHHMMSS(utHours + offsetHours)
}
