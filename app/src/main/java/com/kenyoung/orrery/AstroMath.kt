package com.kenyoung.orrery

import kotlin.math.*
import java.time.Instant

// --- CONSTANTS ---
const val AU_METERS = 149597870700.0
const val EARTH_RADIUS_EQ_METERS = 6378137.0
const val EARTH_FLATTENING = 1.0 / 298.257223563

// Time and angle conversion constants
const val HOURS_TO_DEGREES = 15.0
const val DEGREES_TO_HOURS = 1.0 / 15.0
const val HOURS_PER_DAY = 24.0
const val DEGREES_PER_CIRCLE = 360.0

// Horizon and twilight altitude thresholds (degrees)
const val HORIZON_REFRACTED = -0.833      // Sun/Moon apparent rise/set
const val CIVIL_TWILIGHT = -6.0
const val NAUTICAL_TWILIGHT = -12.0
const val ASTRONOMICAL_TWILIGHT = -18.0

// Julian Date epoch constants
const val J2000_JD = 2451545.0                  // J2000.0 epoch (2000 Jan 1.5 UT)
const val UNIX_EPOCH_JD = 2440587.5              // Unix epoch (1970 Jan 1.0 UT) in JD
const val DAYS_PER_JULIAN_CENTURY = 36525.0
const val SECONDS_PER_DAY = 86400.0
const val MILLIS_PER_DAY = 86400000.0

// --- RAW MATH FUNCTIONS ---

fun solveKepler(M: Double, e: Double): Double {
    var E = M + e * sin(M)
    for (k in 0..5) {
        val dE = (M - (E - e * sin(E))) / (1 - e * cos(E))
        E += dE
    }
    return E
}

fun normalizeDegrees(deg: Double): Double {
    var v = deg % 360.0
    if (v < 0) v += 360.0
    return v
}

// Calculate Greenwich Mean Sidereal Time from Julian Date
// Returns GMST in hours (0-24)
fun calculateGMST(jd: Double): Double {
    val d = jd - J2000_JD
    var gmst = 18.697374558 + 24.06570982441908 * d
    gmst %= 24.0
    if (gmst < 0) gmst += 24.0
    return gmst
}

// Calculate Local Sidereal Time from Julian Date and longitude
// Returns LST in hours (0-24)
fun calculateLSTHours(jd: Double, lonDeg: Double): Double {
    var lst = calculateGMST(jd) + lonDeg / 15.0
    lst %= 24.0
    if (lst < 0) lst += 24.0
    return lst
}

// Convert Spherical (Helio) to Cartesian (Vector3)
fun sphericalToCartesian(dist: Double, lonDeg: Double, latDeg: Double): Vector3 {
    val lonRad = Math.toRadians(lonDeg)
    val latRad = Math.toRadians(latDeg)
    val x = dist * cos(latRad) * cos(lonRad)
    val y = dist * cos(latRad) * sin(lonRad)
    val z = dist * sin(latRad)
    return Vector3(x, y, z)
}

// Calculate Obliquity of Ecliptic for a given JD
private fun calculateObliquity(jd: Double): Double {
    val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    return 23.439291 - 0.0130042 * t
}

// Convert Equatorial (RA/Dec) to Ecliptic (Lon/Lat) in ecliptic of date.
// RA/Dec are J2000 (ICRF); obliquity is of-date; general precession in
// ecliptic longitude shifts the result from J2000 equinox to equinox of date.
fun equatorialToEcliptic(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
    val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    val eps = Math.toRadians(calculateObliquity(jd))
    val alpha = Math.toRadians(raDeg)
    val delta = Math.toRadians(decDeg)

    val sinBeta = sin(delta) * cos(eps) - cos(delta) * sin(eps) * sin(alpha)
    val cbCl = cos(delta) * cos(alpha)
    val cbSl = sin(delta) * sin(eps) + cos(delta) * cos(eps) * sin(alpha)

    val beta = asin(sinBeta)
    val lambda = atan2(cbSl, cbCl)

    // General precession in ecliptic longitude (Meeus eq. 21.6)
    val precessionDeg = 1.396971 * t + 0.0003086 * t * t

    return Pair(normalizeDegrees(Math.toDegrees(lambda) + precessionDeg), Math.toDegrees(beta))
}

// --- NUTATION (IAU 1980, 63-term series) ---

data class NutationResult(val deltaPhi: Double, val deltaEps: Double, val eps: Double)

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

// Cache: nutation changes negligibly over ~1 day, so reuse the last result
// when T is within 1 day (1/36525 century). Eliminates >99% of 63-term
// evaluations during the Transits cache build.
@Volatile private var cachedNutT = Double.NaN
@Volatile private var cachedNutResult: NutationResult? = null
private const val NUT_CACHE_TOL = 1.0 / DAYS_PER_JULIAN_CENTURY // ~1 day in Julian centuries

fun calculateNutation(T: Double): NutationResult {
    val cached = cachedNutResult
    if (cached != null && abs(T - cachedNutT) < NUT_CACHE_TOL) return cached

    val D = normalizeDegrees(297.85036 + 445267.111480 * T - 0.0019142 * T * T + T * T * T / 189474.0)
    val M = normalizeDegrees(357.52772 + 35999.050340 * T - 0.0001603 * T * T - T * T * T / 300000.0)
    val Mprime = normalizeDegrees(134.96298 + 477198.867398 * T + 0.0086972 * T * T + T * T * T / 56250.0)
    val F = normalizeDegrees(93.27191 + 483202.017538 * T - 0.0036825 * T * T + T * T * T / 327270.0)
    val omega = normalizeDegrees(125.04452 - 1934.136261 * T + 0.0020708 * T * T + T * T * T / 450000.0)

    var dP = 0.0
    var dE = 0.0
    for (i in 0 until 63) {
        val arg = Math.toRadians(nutMults[i][0] * D + nutMults[i][1] * M + nutMults[i][2] * Mprime +
                nutMults[i][3] * F + nutMults[i][4] * omega)
        dP += (nutSinCoef[i][0] + nutSinCoef[i][1] * T) * sin(arg)
        dE += (nutCosCoef[i][0] + nutCosCoef[i][1] * T) * cos(arg)
    }
    val deltaPhi = dP * 0.0001  // arcseconds
    val deltaEps = dE * 0.0001  // arcseconds

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

    val result = NutationResult(deltaPhi, deltaEps, eps)
    cachedNutT = T
    cachedNutResult = result
    return result
}

// --- PRECESSION (Meeus 21.3) ---
// Converts J2000 (ICRF) equatorial coordinates to mean-of-date equatorial coordinates
private fun precessJ2000ToDate(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
    val T = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    val T2 = T * T
    val T3 = T2 * T

    // Precession angles in degrees (Meeus 21.3, Lieske 1979 — arcsec constants / 3600)
    val zetaA = (0.6406161 + 0.0003879 * T - 0.0000001 * T2) * T +
            (0.0000839 - 0.0000001 * T) * T2 + 0.0000050 * T3
    val zA = (0.6406161 + 0.0003879 * T - 0.0000001 * T2) * T +
            (0.0003041 + 0.0000001 * T) * T2 + 0.0000051 * T3
    val thetaA = (0.5567530 - 0.0002370 * T - 0.0000001 * T2) * T -
            (0.0001185 + 0.0000001 * T) * T2 - 0.0000116 * T3

    // Convert to radians
    val zetaRad = Math.toRadians(zetaA)
    val zRad = Math.toRadians(zA)
    val thetaRad = Math.toRadians(thetaA)

    val alphaRad = Math.toRadians(raDeg)
    val deltaRad = Math.toRadians(decDeg)

    val cosD = cos(deltaRad)
    val sinD = sin(deltaRad)
    val cosT = cos(thetaRad)
    val sinT = sin(thetaRad)
    val cosAZ = cos(alphaRad + zetaRad)
    val sinAZ = sin(alphaRad + zetaRad)

    val A = cosD * sinAZ
    val B = cosT * cosD * cosAZ - sinT * sinD
    val C = sinT * cosD * cosAZ + cosT * sinD

    val raNew = normalizeDegrees(Math.toDegrees(atan2(A, B)) + Math.toDegrees(zRad))
    val decNew = Math.toDegrees(asin(C.coerceIn(-1.0, 1.0)))

    return Pair(raNew, decNew)
}

// --- J2000 TO APPARENT (precession + nutation) ---
// Converts J2000 equatorial coordinates to apparent (true) equatorial coordinates of date.
// Use for display Az/Alt only — rise/set algorithms are calibrated with HORIZON_REFRACTED.
fun j2000ToApparent(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
    // Step 1: precess J2000 → mean of date
    val (meanRa, meanDec) = precessJ2000ToDate(raDeg, decDeg, jd)

    // Step 2: apply nutation (mean → true of date)
    val T = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    val nut = calculateNutation(T)
    val dPsiDeg = nut.deltaPhi / 3600.0  // arcsec → degrees
    val dEpsDeg = nut.deltaEps / 3600.0

    val epsRad = Math.toRadians(nut.eps)
    val alphaRad = Math.toRadians(meanRa)
    val deltaRad = Math.toRadians(meanDec)

    // Nutation corrections in RA and Dec (Meeus 23.1)
    val dAlpha = (cos(epsRad) + sin(epsRad) * sin(alphaRad) * tan(deltaRad)) * dPsiDeg -
            (cos(alphaRad) * tan(deltaRad)) * dEpsDeg
    val dDelta = (sin(epsRad) * cos(alphaRad)) * dPsiDeg + sin(alphaRad) * dEpsDeg

    return Pair(normalizeDegrees(meanRa + dAlpha), meanDec + dDelta)
}

// --- PARALLAX CORRECTION ---
fun toTopocentric(
    raDeg: Double, decDeg: Double, distAU: Double,
    latDeg: Double, lonDeg: Double, lstHours: Double, elevationM: Double = 0.0
): RaDec {
    val latRad = Math.toRadians(latDeg)
    val lstRad = Math.toRadians(lstHours * 15.0)

    val f = EARTH_FLATTENING
    val u = atan((1 - f) * tan(latRad))
    val sinU = sin(u)
    val cosU = cos(u)

    val rhoSinPhiP = (1 - f) * sinU + (elevationM / EARTH_RADIUS_EQ_METERS) * sin(latRad)
    val rhoCosPhiP = cosU + (elevationM / EARTH_RADIUS_EQ_METERS) * cos(latRad)

    val eqRadInAU = EARTH_RADIUS_EQ_METERS / AU_METERS

    val xo = eqRadInAU * rhoCosPhiP * cos(lstRad)
    val yo = eqRadInAU * rhoCosPhiP * sin(lstRad)
    val zo = eqRadInAU * rhoSinPhiP

    val raRad = Math.toRadians(raDeg)
    val decRad = Math.toRadians(decDeg)
    val xg = distAU * cos(decRad) * cos(raRad)
    val yg = distAU * cos(decRad) * sin(raRad)
    val zg = distAU * sin(decRad)

    val xt = xg - xo
    val yt = yg - yo
    val zt = zg - zo

    val rt = sqrt(xt*xt + yt*yt + zt*zt)
    val raTopRad = atan2(yt, xt)
    val decTopRad = asin(zt / rt)

    val raTopDeg = normalizeDegrees(Math.toDegrees(raTopRad))
    val decTopDeg = Math.toDegrees(decTopRad)

    return RaDec(raTopDeg, decTopDeg)
}

// --- SHARED UTILS ---

fun calculateAzAlt(lstHours: Double, latDeg: Double, raHours: Double, decDeg: Double): Pair<Double, Double> {
    val haRad = Math.toRadians((lstHours - raHours) * 15.0)
    val latRad = Math.toRadians(latDeg)
    val decRad = Math.toRadians(decDeg)
    val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
    val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
    val denom = cos(altRad) * cos(latRad)
    val azDeg = if (abs(denom) < 1e-15) {
        0.0 // Azimuth is undefined at poles or for objects at zenith/nadir
    } else {
        val cosAz = (sin(decRad) - sin(altRad) * sin(latRad)) / denom
        val azRadAbs = acos(cosAz.coerceIn(-1.0, 1.0))
        if (sin(haRad) > 0) 360.0 - Math.toDegrees(azRadAbs) else Math.toDegrees(azRadAbs)
    }
    return Pair(azDeg, Math.toDegrees(altRad))
}

// Calculate altitude only (when azimuth is not needed)
// haHours: hour angle in hours, latDeg: latitude in degrees, decDeg: declination in degrees
// Returns altitude in degrees
fun calculateAltitude(haHours: Double, latDeg: Double, decDeg: Double): Double {
    val haRad = Math.toRadians(haHours * 15.0)
    val latRad = Math.toRadians(latDeg)
    val decRad = Math.toRadians(decDeg)
    val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
    return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
}

// Saemundsson's formula: geometric (true) altitude -> apparent altitude
// Adds atmospheric refraction so objects appear at their observed position
fun applyRefraction(trueAltDeg: Double): Double {
    if (trueAltDeg < -2.0) return trueAltDeg
    val h = trueAltDeg.coerceAtLeast(-1.5)
    val rArcmin = 1.02 / tan(Math.toRadians(h + 10.3 / (h + 5.11)))
    val correction = (rArcmin / 60.0).coerceAtLeast(0.0)
    return trueAltDeg + correction
}

fun normalizeTime(t: Double): Double {
    if (!t.isFinite()) return Double.NaN
    var v = t
    while (v < 0) v += 24.0
    while (v >= 24) v -= 24.0
    return v
}

// Convert a Julian day fraction to local hours using the given timezone offset
fun jdFracToLocalHours(jd: Double, timezoneOffset: Double): Double =
    normalizeTime((jd - floor(jd)) * 24.0 + timezoneOffset)

// Normalize hour angle to -12 to +12 range
fun normalizeHourAngle(ha: Double): Double {
    if (!ha.isFinite()) return Double.NaN
    var v = ha
    while (v < -12.0) v += 24.0
    while (v > 12.0) v -= 24.0
    return v
}

fun formatTimeMM(t: Double, isSigned: Boolean): String {
    if (t.isNaN()) return "--:--"
    val absT = abs(t)
    val h = floor(absT).toInt()
    val m = floor((absT - h) * 60.0).toInt()
    val sign = if (isSigned && t < 0) "-" else ""
    return "%s%02d:%02d".format(sign, h, m)
}

// --- KEPLERIAN CALCULATORS ---

fun calculateSunPositionKepler(jd: Double): BodyState {
    val n = jd - J2000_JD
    val L = normalizeDegrees(280.460 + 0.9856474 * n)
    val g = normalizeDegrees(357.528 + 0.9856003 * n)
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val r = 1.00014 - 0.01671 * cos(Math.toRadians(g)) - 0.00014 * cos(2 * Math.toRadians(g))

    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))

    val raDeg = normalizeDegrees(Math.toDegrees(alpha))
    val decDeg = Math.toDegrees(delta)

    return BodyState(Vector3(0.0, 0.0, 0.0), raDeg, decDeg, 0.0, r, Math.toDegrees(lambdaRad), 0.0)
}

fun calculatePlanetStateKeplerian(jd: Double, p: PlanetElements): BodyState {
    val d = jd - J2000_JD
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le_earth = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
    val Re = 1.00014 - 0.01671 * cos(Me)
    val xe = Re * cos(Le_earth); val ye = Re * sin(Le_earth); val ze = 0.0

    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0); val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar); val Mp = Lp - w_bar_p
    val Ep = solveKepler(Mp, p.e)
    val xv = p.a * (cos(Ep) - p.e); val yv = p.a * sqrt(1 - p.e*p.e) * sin(Ep)
    val v = atan2(yv, xv); val u = v + w_bar_p - Np
    val rp = sqrt(xv*xv + yv*yv)
    val xh = rp * (cos(u) * cos(Np) - sin(u) * sin(Np) * cos(ip))
    val yh = rp * (cos(u) * sin(Np) + sin(u) * cos(Np) * cos(ip))
    val zh = rp * (sin(u) * sin(ip))

    val helioPos = Vector3(xh, yh, zh)
    val xg = xh - xe; val yg = yh - ye; val zg = zh - ze
    val distGeo = sqrt(xg*xg + yg*yg + zg*zg)

    val ecl = Math.toRadians(23.439 - 0.0000004 * d)
    val xeq = xg; val yeq = yg * cos(ecl) - zg * sin(ecl); val zeq = yg * sin(ecl) + zg * cos(ecl)
    val raHours = Math.toDegrees(atan2(yeq, xeq)) / 15.0
    val raDeg = normalizeDegrees(raHours * 15.0)
    val decDeg = Math.toDegrees(atan2(zeq, sqrt(xeq*xeq + yeq*yeq)))

    val (eclLon, eclLat) = equatorialToEcliptic(raDeg, decDeg, jd)

    return BodyState(helioPos, raDeg, decDeg, rp, distGeo, eclLon, eclLat)
}

// --- JOVIAN MOONS (Meeus Chapter 44 - Accurate Implementation with Shadows) ---

fun calculateJovianMoons(jd: Double): Map<String, JovianMoonState> {
    val d = jd - J2000_JD
    val deg2rad = Math.PI / 180.0

    // Local trigonometric helpers working in RADIANS
    fun sinR(rad: Double) = kotlin.math.sin(rad)
    fun cosR(rad: Double) = kotlin.math.cos(rad)

    // --- PHASE ANGLE & SHADOW GEOMETRY ---
    val earthState = AstroEngine.getBodyState("Earth", jd)
    val jupState = AstroEngine.getBodyState("Jupiter", jd)

    val r = earthState.distSun
    val R = jupState.distSun
    val Delta = jupState.distGeo

    // Meeus (48.2) Phase Angle
    // Better: use law of cosines on distances
    val cosAlpha = (R*R + Delta*Delta - r*r) / (2*R*Delta)
    val alpha = acos(cosAlpha.coerceIn(-1.0, 1.0))

    // Shadow displacement factor (tan alpha)
    val shadowFactor = tan(alpha)

    val sunRA = AstroEngine.getBodyState("Sun", jd).ra
    val jupRA = jupState.ra

    var raDiff = sunRA - jupRA
    while (raDiff < -180) raDiff += 360
    while (raDiff > 180) raDiff -= 360

    // If Sun RA > Jup RA, Sun is "East" in sky (Left). Shadow goes West (+X).
    // UPDATED: shadowSign must be -1.0 when Sun is East to produce positive X shift (West)
    // because X displacement is (tanAlpha * -z). For transit, -z is negative.
    // Negative * Negative = Positive (Right/West).
    val shadowSign = if (raDiff > 0) -1.0 else 1.0
    val xShiftPerZ = shadowSign * shadowFactor

    // --- MOON POSITIONS ---

    val V = (172.74 + 0.00111588 * d) * deg2rad
    val M = (357.529 + 0.9856003 * d) * deg2rad
    val N = (20.020 + 0.0830853 * d + 0.329 * sinR(V)) * deg2rad
    val J = (66.115 + 0.9025179 * d - 0.329 * sinR(V)) * deg2rad

    val A = 1.915 * sinR(M) + 0.020 * sinR(2.0 * M)
    val B = 5.555 * sinR(N) + 0.168 * sinR(2.0 * N)

    val K = J + (A - B) * deg2rad
    val R_orb = 1.00014 - 0.01671 * cosR(M) - 0.00014 * cosR(2.0 * M)
    val r_orb = 5.20872 - 0.25208 * cosR(N) - 0.00611 * cosR(2.0 * N)

    val Delta_dist = sqrt(r_orb * r_orb + R_orb * R_orb - 2.0 * r_orb * R_orb * cosR(K))
    val psi = asin((R_orb / Delta_dist) * sinR(K))

    val lam = (34.35 + 0.083091 * d + 0.329 * sinR(V) + B) * deg2rad

    val DS = (3.12 * sinR(lam + 42.8 * deg2rad)) * deg2rad
    val DE = DS - (2.22 * sinR(psi) * cosR(lam + 22.0 * deg2rad) +
            1.3 * (r_orb - Delta_dist) / Delta_dist * sinR(lam - 100.5 * deg2rad)) * deg2rad

    val dd = d - Delta_dist / 173.0

    var u1 = (163.8069 + 203.4058646 * dd) * deg2rad + psi - B * deg2rad
    var u2 = (358.4140 + 101.2916335 * dd) * deg2rad + psi - B * deg2rad
    var u3 = (5.7176 + 50.2345180 * dd) * deg2rad + psi - B * deg2rad
    var u4 = (224.8092 + 21.4879800 * dd) * deg2rad + psi - B * deg2rad

    val G = (331.18 + 50.310482 * dd) * deg2rad
    val H = (87.45 + 21.569231 * dd) * deg2rad

    u1 += (0.472 * sinR(2.0 * (u1 - u2))) * deg2rad
    u2 += (1.073 * sinR(2.0 * (u2 - u3))) * deg2rad
    u3 += (0.174 * sinR(G) + 0.035 * sinR(2.0 * G)) * deg2rad
    u4 += (0.845 * sinR(H) + 0.034 * sinR(2.0 * H)) * deg2rad

    val r1 = 5.9057 - 0.0244 * cosR(2.0 * (u1 - u2))
    val r2 = 9.3966 - 0.0882 * cosR(2.0 * (u2 - u3))
    val r3 = 14.9883 - 0.0216 * cosR(G)
    val r4 = 26.3627 - 0.1939 * cosR(H)

    val sDE = sinR(DE)
    val cDE = cosR(DE)

    fun makeState(rm: Double, u: Double): JovianMoonState {
        val su = sinR(u)
        val cu = cosR(u)
        val x = rm * su
        val y = -rm * cu * sDE
        val z = rm * cu * cDE

        // --- SHADOW & ECLIPSE CALCULATION ---

        // 1. Shadow Position (Project Moon onto Jupiter Plane at Z=0)
        val sX = x + (xShiftPerZ * -z)
        val sY = y

        // Check if Shadow is on Disk
        // FIX: Moon must be in FRONT (z > 0) to cast a shadow on the visible face.
        val isOnDisk = (z > 0) && ((sX*sX + sY*sY) < 0.95)

        // 2. Eclipse (Moon inside Jupiter Shadow)
        // Shadow center at distance Z is shifted by (xShiftPerZ * Z).
        val shadowCenterX = xShiftPerZ * z
        val shadowCenterY = 0.0

        // Check distance of Moon (x,y) from Shadow Center
        // FIX: Moon must be BEHIND (z < 0) to be in Jupiter's shadow.
        val distSq = (x - shadowCenterX).pow(2) + (y - shadowCenterY).pow(2)
        val isEclipsed = (z < 0) && (distSq < 0.95)

        return JovianMoonState(x, y, z, sX, sY, isOnDisk, isEclipsed)
    }

    return mapOf(
        "Io" to makeState(r1, u1),
        "Europa" to makeState(r2, u2),
        "Ganymede" to makeState(r3, u3),
        "Callisto" to makeState(r4, u4)
    )
}

// --- RISE/SET/TRANSIT CALCULATIONS ---

fun calculateSunTimes(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double = HORIZON_REFRACTED): Pair<Double, Double> {
    // Use integer date — fractional epoch days from manual time entry shift the
    // scan start forward, causing events between now and now+|offset| to be missed.
    val epochDayInt = floor(epochDay)
    // Iteratively find Sun transit (recomputes position each step)
    var tGuess = epochDayInt + 0.5 - (timezoneOffset / 24.0)
    for (i in 0..4) {
        val jd = tGuess + UNIX_EPOCH_JD
        val state = AstroEngine.getBodyState("Sun", jd)
        val (appRa, _) = j2000ToApparent(state.ra, state.dec, jd)
        val raHours = appRa / 15.0
        val lst = calculateLSTHours(jd, lon)
        val ha = normalizeHourAngle(lst - raHours)
        tGuess -= (ha / 24.0) * 0.99727
    }
    val tTransit = tGuess

    fun getAlt(t: Double): Double {
        val jd = t + UNIX_EPOCH_JD
        val state = AstroEngine.getBodyState("Sun", jd)
        val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
        val lst = calculateLSTHours(jd, lon)
        val haHours = lst - appRa / 15.0
        return calculateAltitude(haHours, lat, appDec)
    }

    // Polar day/night check
    val transitAlt = getAlt(tTransit)
    if (transitAlt < altitude) return Pair(Double.NaN, Double.NaN)
    val nadirAlt = getAlt(tTransit + 0.5)
    if (nadirAlt > altitude) return Pair(Double.NaN, Double.NaN)

    // Iteratively refine rise time
    var tRise = tTransit - 0.25
    for (i in 0..9) {
        val alt = getAlt(tRise); val diff = alt - altitude; val rate = 360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tRise -= (diff / rate)
    }

    // Iteratively refine set time
    var tSet = tTransit + 0.25
    for (i in 0..9) {
        val alt = getAlt(tSet); val diff = alt - altitude; val rate = -360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tSet -= (diff / rate)
    }

    return Pair(jdFracToLocalHours(tRise, timezoneOffset), jdFracToLocalHours(tSet, timezoneOffset))
}

fun calculateSunTransit(epochDay: Double, lon: Double, timezoneOffset: Double): Pair<Double, Double> {
    val jd = floor(epochDay) + UNIX_EPOCH_JD + 0.5
    val sunNoon = AstroEngine.getBodyState("Sun", jd)
    val (appRa, appDec) = j2000ToApparent(sunNoon.ra, sunNoon.dec, jd)
    val n = jd - J2000_JD
    val gmst = (6.697374558 + 0.06570982441908 * n) % 24.0
    val gmstFixed = if (gmst < 0) gmst + 24.0 else gmst
    val transitUT = normalizeTime(appRa / 15.0 - lon / 15.0 - gmstFixed)
    return Pair(normalizeTime(transitUT + timezoneOffset), appDec)
}

fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    // Use integer date — fractional epoch days from manual time entry shift the
    // scan start forward, causing events between now and now+|offset| to be missed.
    val epochDayInt = floor(epochDay)
    var tGuess = epochDayInt + 0.5 - (timezoneOffset / 24.0)
    for (i in 0..4) {
        val jd = tGuess + UNIX_EPOCH_JD
        val state = AstroEngine.getBodyState(p.name, jd)
        val (appRa, _) = j2000ToApparent(state.ra, state.dec, jd)
        val raHours = appRa / 15.0
        val lst = calculateLSTHours(jd, lon)
        val ha = normalizeHourAngle(lst - raHours)
        tGuess -= (ha / 24.0) * 0.99727
    }
    val tTransit = tGuess
    fun getAlt(t: Double): Double {
        val jd = t + UNIX_EPOCH_JD
        val state = AstroEngine.getBodyState(p.name, jd)
        val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
        val lst = calculateLSTHours(jd, lon)
        val haHours = lst - appRa / 15.0
        return calculateAltitude(haHours, lat, appDec)
    }
    val targetAlt = -0.5667
    var tRise = tTransit - 0.25
    for (i in 0..9) {
        val alt = getAlt(tRise); val diff = alt - targetAlt; val rate = 360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tRise -= (diff / rate)
    }
    var tSet = tTransit + 0.25
    for (i in 0..9) {
        val alt = getAlt(tSet); val diff = alt - targetAlt; val rate = -360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tSet -= (diff / rate)
    }
    return PlanetEvents(jdFracToLocalHours(tRise, timezoneOffset), jdFracToLocalHours(tTransit, timezoneOffset), jdFracToLocalHours(tSet, timezoneOffset))
}

// Estimate the epoch day (fractional) when the Moon transits at a given longitude.
// epochDay should be an integer (start of day at 0h UT). Accuracy ~±1 hour.
fun estimateMoonTransitEpochDay(epochDay: Double, lonDeg: Double): Double {
    val jd0 = epochDay + UNIX_EPOCH_JD
    val moonRaHours = AstroEngine.getBodyState("Moon", jd0).ra / 15.0
    val lst0 = calculateLSTHours(jd0, lonDeg)
    var wait = moonRaHours - lst0
    if (wait < 0) wait += 24.0
    if (wait >= 24.0) wait -= 24.0
    return epochDay + wait / 24.0
}

fun calculateMoonPhaseAngle(epochDay: Double): Double {
    val jd = epochDay + UNIX_EPOCH_JD
    val moonLon = AstroEngine.getBodyState("Moon", jd).eclipticLon
    val sunLon = AstroEngine.getBodyState("Sun", jd).eclipticLon
    var diff = (moonLon - sunLon) % 360.0; if (diff < 0) diff += 360.0
    return diff
}

fun calculateMoonEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, pairedRiseSet: Boolean = false): PlanetEvents {
    // Use integer date — fractional epoch days from manual time entry shift the
    // scan start forward, causing events between now and now+|offset| to be missed.
    val epochDayInt = floor(epochDay)
    // Start of local day in UT (midnight local time)
    val dayStartUT = epochDayInt - timezoneOffset / 24.0
    val step = 1.0 / 144.0 // 10-minute steps
    val moonRadiusM = 1737400.0

    // Combined moon altitude and hour angle at time t (topocentric)
    fun getMoonState(t: Double): Pair<Double, Double> {
        val jd = t + UNIX_EPOCH_JD
        val state = AstroEngine.getBodyState("Moon", jd)
        val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
        val lst = calculateLSTHours(jd, lon)
        val topo = toTopocentric(appRa, appDec, state.distGeo, lat, lon, lst)
        val haHours = lst - topo.ra / 15.0
        val alt = calculateAltitude(haHours, lat, topo.dec)
        val sdDeg = Math.toDegrees(asin(moonRadiusM / (state.distGeo * AU_METERS)))
        val targetAlt = -(0.5667 + sdDeg)
        return Pair(alt - targetAlt, normalizeHourAngle(haHours))
    }

    fun refineAltCrossing(tLo: Double, tHi: Double, rising: Boolean): Double {
        var lo = tLo; var hi = tHi
        for (i in 0..15) {
            val mid = (lo + hi) / 2.0
            if ((getMoonState(mid).first < 0) == rising) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    fun refineHACrossing(tLo: Double, tHi: Double): Double {
        var lo = tLo; var hi = tHi
        for (i in 0..15) {
            val mid = (lo + hi) / 2.0
            if (getMoonState(mid).second < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    // Scan forward from startUT for up to maxDays, returning the first event of the given type
    fun findNext(startUT: Double, maxDays: Double, type: String): Double {
        var (prevAlt, prevHA) = getMoonState(startUT)
        var t = startUT + step
        val endUT = startUT + maxDays
        while (t <= endUT + step / 2) {
            val (currAlt, currHA) = getMoonState(t)
            when (type) {
                "rise" -> if (prevAlt < 0 && currAlt >= 0)
                    return refineAltCrossing(t - step, t, true)
                "set" -> if (prevAlt > 0 && currAlt <= 0)
                    return refineAltCrossing(t - step, t, false)
                "transit" -> if (prevHA < 0 && currHA >= 0 && abs(currHA - prevHA) < 6)
                    return refineHACrossing(t - step, t)
            }
            prevAlt = currAlt; prevHA = currHA
            t += step
        }
        return Double.NaN
    }

    val rise: Double
    val transit: Double
    val set: Double

    if (pairedRiseSet) {
        // Find first rise, then the next set and transit after that rise
        rise = findNext(dayStartUT, 2.0, "rise")
        set = if (!rise.isNaN()) findNext(rise, 1.5, "set") else Double.NaN
        transit = if (!rise.isNaN() && !set.isNaN()) findNext(rise, set - rise, "transit") else Double.NaN
    } else {
        // Find each event independently within today, falling back to tomorrow
        rise = findNext(dayStartUT, 2.0, "rise")
        set = findNext(dayStartUT, 2.0, "set")
        transit = findNext(dayStartUT, 2.0, "transit")
    }

    return PlanetEvents(jdFracToLocalHours(rise, timezoneOffset), jdFracToLocalHours(transit, timezoneOffset), jdFracToLocalHours(set, timezoneOffset))
}

fun calculateEquationOfTimeMinutes(epochDay: Double): Double {
    val jd = epochDay + UNIX_EPOCH_JD; val n = jd - J2000_JD
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    val alphaDeg = AstroEngine.getBodyState("Sun", jd).ra
    var eDeg = L - alphaDeg
    while (eDeg > 180) eDeg -= 360.0; while (eDeg <= -180) eDeg += 360.0
    return eDeg * 4.0
}
fun calculateSunDeclination(epochDay: Double): Double {
    val jd = epochDay + UNIX_EPOCH_JD + 0.5
    return Math.toRadians(AstroEngine.getBodyState("Sun", jd).dec)
}
fun calculateMoonPosition(epochDay: Double): RaDec {
    val T = (epochDay + UNIX_EPOCH_JD - J2000_JD) / DAYS_PER_JULIAN_CENTURY
    val L_prime = Math.toRadians(218.3164477 + 481267.88123421 * T)
    val M_prime = Math.toRadians(134.9633964 + 477198.8675055 * T)
    val F = Math.toRadians(93.2720950 + 483202.0175233 * T)
    val lambda = L_prime + Math.toRadians(6.289 * sin(M_prime))
    val beta = Math.toRadians(5.128 * sin(F))
    val epsilon = Math.toRadians(23.439291 - 0.0130042 * T)
    val x = cos(beta) * cos(lambda)
    val y = cos(epsilon) * cos(beta) * sin(lambda) - sin(epsilon) * sin(beta)
    val z = sin(epsilon) * cos(beta) * sin(lambda) + cos(epsilon) * sin(beta)
    val ra = normalizeDegrees(Math.toDegrees(atan2(y, x))) / 15.0
    val dec = Math.toDegrees(asin(z))
    return RaDec(ra, dec)
}
fun calculateLST(instant: Instant, lon: Double): String {
    val jd = instant.epochSecond / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val lst = calculateLSTHours(jd, lon)
    return "%02d:%02d".format(floor(lst).toInt(), floor((lst - floor(lst)) * 60).toInt())
}