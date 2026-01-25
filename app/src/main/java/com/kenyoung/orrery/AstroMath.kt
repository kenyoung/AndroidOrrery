package com.kenyoung.orrery

import kotlin.math.*
import java.time.Instant

// --- CONSTANTS ---
const val AU_METERS = 149597870700.0
const val EARTH_RADIUS_EQ_METERS = 6378137.0
const val EARTH_FLATTENING = 1.0 / 298.257223563

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
fun calculateObliquity(jd: Double): Double {
    val t = (jd - 2451545.0) / 36525.0
    return 23.439291 - 0.0130042 * t
}

// Convert Equatorial (RA/Dec) to Ecliptic (Lon/Lat)
fun equatorialToEcliptic(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
    val eps = Math.toRadians(calculateObliquity(jd))
    val alpha = Math.toRadians(raDeg)
    val delta = Math.toRadians(decDeg)

    val sinBeta = sin(delta) * cos(eps) - cos(delta) * sin(eps) * sin(alpha)
    val cbCl = cos(delta) * cos(alpha)
    val cbSl = sin(delta) * sin(eps) + cos(delta) * cos(eps) * sin(alpha)

    val beta = asin(sinBeta)
    val lambda = atan2(cbSl, cbCl)

    return Pair(normalizeDegrees(Math.toDegrees(lambda)), Math.toDegrees(beta))
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
    val cosAz = (sin(decRad) - sin(altRad) * sin(latRad)) / (cos(altRad) * cos(latRad))
    val azRadAbs = acos(cosAz.coerceIn(-1.0, 1.0))
    val azDeg = if (sin(haRad) > 0) 360.0 - Math.toDegrees(azRadAbs) else Math.toDegrees(azRadAbs)
    return Pair(azDeg, Math.toDegrees(altRad))
}

fun normalizeTime(t: Double): Double {
    var v = t
    while (v < 0) v += 24.0
    while (v >= 24) v -= 24.0
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
    val n = jd - 2451545.0
    var L = normalizeDegrees(280.460 + 0.9856474 * n)
    var g = normalizeDegrees(357.528 + 0.9856003 * n)
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val r = 1.00014 - 0.01671 * cos(Math.toRadians(g)) - 0.00014 * cos(2 * Math.toRadians(g))

    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))

    val raDeg = normalizeDegrees(Math.toDegrees(alpha))
    val decDeg = Math.toDegrees(delta)
    val geoPos = sphericalToCartesian(r, raDeg, decDeg)

    return BodyState(
        "Sun", jd, Vector3(0.0, 0.0, 0.0), geoPos, raDeg, decDeg, 0.0, r, Math.toDegrees(lambdaRad), 0.0
    )
}

fun calculatePlanetStateKeplerian(jd: Double, p: PlanetElements): BodyState {
    val d = jd - 2451545.0
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le_earth = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
    val Re = 1.00014 - 0.01671 * cos(Me)
    val xe = Re * cos(Le_earth); val ye = Re * sin(Le_earth); val ze = 0.0

    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0); val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar); var Mp = Lp - w_bar_p
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

    return BodyState(p.name, jd, helioPos, Vector3(xg, yg, zg), raDeg, decDeg, rp, distGeo, eclLon, eclLat)
}

// --- LEGACY HELPERS ---

fun calculateSunTimes(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double = -0.833): Pair<Double, Double> {
    val state = calculateSunPositionKepler(epochDay + 2440587.5 + 0.5)
    return calculateRiseSet(state.ra, state.dec, lat, lon, timezoneOffset, altitude, epochDay)
}

fun calculateRiseSet(raDeg: Double, decDeg: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double, epochDay: Double): Pair<Double, Double> {
    val jd = epochDay + 2440587.5 + 0.5
    val n = jd - 2451545.0
    val GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0
    val gmstFixed = if (GMST0 < 0) GMST0 + 24.0 else GMST0
    val raHours = raDeg / 15.0
    var transitUT = raHours - (lon / 15.0) - gmstFixed
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transitStandard = transitUT + timezoneOffset
    val latRad = Math.toRadians(lat); val decRad = Math.toRadians(decDeg); val altRad = Math.toRadians(altitude)
    val cosH = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))
    if (cosH < -1.0 || cosH > 1.0) return Pair(Double.NaN, Double.NaN)
    val hHours = Math.toDegrees(acos(cosH)) / 15.0
    var rise = transitStandard - hHours; var set = transitStandard + hHours
    while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
    while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
    return Pair(rise, set)
}

fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    var tGuess = epochDay + 0.5 - (timezoneOffset / 24.0)
    for (i in 0..4) {
        val state = calculatePlanetStateKeplerian(tGuess + 2440587.5, p)
        val raHours = state.ra / 15.0
        val jd = tGuess + 2440587.5; val n = jd - 2451545.0
        val GMST = (18.697374558 + 24.06570982441908 * n) % 24.0
        val gmstFixed = if (GMST < 0) GMST + 24.0 else GMST
        var lst = gmstFixed + (lon / 15.0)
        while(lst < 0) lst += 24.0; while(lst >= 24.0) lst -= 24.0
        var ha = lst - raHours
        while (ha < -12) ha += 24.0; while (ha > 12) ha -= 24.0
        tGuess -= (ha / 24.0) * 0.99727
    }
    val tTransit = tGuess
    fun getAlt(t: Double): Double {
        val state = calculatePlanetStateKeplerian(t + 2440587.5, p)
        val jd = t + 2440587.5; val n = jd - 2451545.0
        val GMST = (18.697374558 + 24.06570982441908 * n) % 24.0
        val gmstFixed = if (GMST < 0) GMST + 24.0 else GMST
        val lst = (gmstFixed + lon/15.0 + 24.0) % 24.0
        val raHours = state.ra / 15.0; val haHours = lst - raHours
        val haRad = Math.toRadians(haHours * 15.0); val latRad = Math.toRadians(lat); val decRad = Math.toRadians(state.dec)
        val sinAlt = sin(latRad)*sin(decRad) + cos(latRad)*cos(decRad)*cos(haRad)
        return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
    }
    val targetAlt = -0.5667
    var tRise = tTransit - 0.25
    for(i in 0..4) {
        val alt = getAlt(tRise); val diff = alt - targetAlt; val rate = 360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tRise -= (diff / rate)
    }
    var tSet = tTransit + 0.25
    for(i in 0..4) {
        val alt = getAlt(tSet); val diff = alt - targetAlt; val rate = -360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tSet -= (diff / rate)
    }
    fun toLocal(t: Double): Double {
        var h = (t - floor(t)) * 24.0 + timezoneOffset
        while(h < 0) h += 24.0; while(h >= 24) h -= 24.0
        return h
    }
    return PlanetEvents(toLocal(tRise), toLocal(tTransit), toLocal(tSet))
}

fun calculateMoonPhaseAngle(epochDay: Double): Double {
    val d = (2440587.5 + epochDay) - 2451545.0
    val Ms = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Ls = Math.toRadians((280.466 + 0.98564736 * d) % 360.0)
    val trueLongSun = Math.toDegrees(Ls) + (1.915 * sin(Ms) + 0.020 * sin(2 * Ms))
    val L = (218.316 + 13.176396 * d) % 360.0
    val M = Math.toRadians((134.963 + 13.064993 * d) % 360.0)
    val lambda = L + 6.289 * sin(M)
    var diff = (lambda - trueLongSun) % 360.0; if (diff < 0) diff += 360.0
    return diff
}

fun calculateMoonEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double): PlanetEvents {
    var tGuess = epochDay + 0.5 - (timezoneOffset / 24.0)
    for (i in 0..4) {
        val pos = calculateMoonPosition(tGuess); val raHours = pos.ra
        val jd = tGuess + 2440587.5; val n = jd - 2451545.0
        val GMST = (18.697374558 + 24.06570982441908 * n) % 24.0
        val gmstFixed = if (GMST < 0) GMST + 24.0 else GMST
        var lst = gmstFixed + (lon / 15.0)
        while(lst < 0) lst += 24.0; while(lst >= 24.0) lst -= 24.0
        var ha = lst - raHours
        while (ha < -12) ha += 24.0; while (ha > 12) ha -= 24.0
        tGuess -= (ha / 24.0) * 1.035
    }
    val tTransit = tGuess
    fun getAlt(t: Double): Double {
        val pos = calculateMoonPosition(t)
        val jd = t + 2440587.5; val n = jd - 2451545.0
        val GMST = (18.697374558 + 24.06570982441908 * n) % 24.0
        val gmstFixed = if (GMST < 0) GMST + 24.0 else GMST
        val lst = (gmstFixed + lon/15.0 + 24.0) % 24.0
        val haHours = lst - pos.ra
        val haRad = Math.toRadians(haHours * 15.0); val latRad = Math.toRadians(lat); val decRad = Math.toRadians(pos.dec)
        val sinAlt = sin(latRad)*sin(decRad) + cos(latRad)*cos(decRad)*cos(haRad)
        return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
    }
    val targetAlt = 0.125
    var tRise = tTransit - 0.25
    for(i in 0..4) {
        val alt = getAlt(tRise); val diff = alt - targetAlt; val rate = 360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tRise -= (diff / rate)
    }
    var tSet = tTransit + 0.25
    for(i in 0..4) {
        val alt = getAlt(tSet); val diff = alt - targetAlt; val rate = -360.0 * cos(Math.toRadians(lat))
        if (abs(rate) < 1.0) break; tSet -= (diff / rate)
    }
    fun toLocal(t: Double): Double {
        var h = (t - floor(t)) * 24.0 + timezoneOffset
        while(h < 0) h += 24.0; while(h >= 24) h -= 24.0
        return h
    }
    return PlanetEvents(toLocal(tRise), toLocal(tTransit), toLocal(tSet))
}

fun calculateEquationOfTimeMinutes(epochDay: Double): Double {
    val jd = epochDay + 2440587.5; val n = jd - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambda = L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(Math.toRadians(2 * g))
    val epsilon = 23.439 - 0.0000004 * n
    val alphaRad = atan2(cos(Math.toRadians(epsilon)) * sin(Math.toRadians(lambda)), cos(Math.toRadians(lambda)))
    var alphaDeg = Math.toDegrees(alphaRad)
    if (alphaDeg < 0) alphaDeg += 360.0
    var E_deg = L - alphaDeg
    while (E_deg > 180) E_deg -= 360.0; while (E_deg <= -180) E_deg += 360.0
    return E_deg * 4.0
}
fun calculateSunDeclination(epochDay: Double): Double {
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    return asin(sin(epsilonRad) * sin(lambdaRad))
}
fun calculateMoonPosition(epochDay: Double): RaDec {
    val T = (epochDay + 2440587.5 - 2451545.0) / 36525.0
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
    val epochSeconds = instant.epochSecond
    val jd = epochSeconds / 86400.0 + 2440587.5
    val d = jd - 2451545.0
    var gmst = 18.697374558 + 24.06570982441908 * d
    gmst %= 24.0; if (gmst < 0) gmst += 24.0
    var lst = gmst + (lon / 15.0)
    lst %= 24.0; if (lst < 0) lst += 24.0
    return "%02d:%02d".format(floor(lst).toInt(), floor((lst - floor(lst)) * 60).toInt())
}

// --- JOVIAN MOONS MATH (Meeus Chapter 44 Implementation) ---

data class JovianMoonState(val x: Double, val y: Double, val z: Double)

fun calculateJovianMoons(jd: Double): Map<String, JovianMoonState> {
    // 1. Light Time Correction
    // Calculate distance Earth-Jupiter for Delta correction.
    val jupElem = getOrreryPlanets().find { it.name == "Jupiter" }!!
    val jupState = calculatePlanetStateKeplerian(jd, jupElem)
    val distAU = jupState.distGeo
    val deltaC = distAU / 173.1446 // Light time in days
    val tJD = jd - deltaC // True time of event at Jupiter

    val d = tJD - 2433282.5

    // Mean Longitudes (Meeus 44.1)
    var l1 = normalizeDegrees(106.07719 + 203.488955790 * d)
    var l2 = normalizeDegrees(175.73161 + 101.374724735 * d)
    var l3 = normalizeDegrees(120.55883 + 50.317609207 * d)
    var l4 = normalizeDegrees(84.44459 + 21.571071177 * d)

    val pi = normalizeDegrees(66.1 + 0.9025179 * d / 365.25) // Perihelion Jup
    val omega = normalizeDegrees(100.5 + 1.0286295 * d / 365.25) // Node Jup

    // Periodic Terms (simplified major terms from Meeus 44)
    // We assume Earth Heliocentric Coords approx needed for Phase.
    // Meeus method uses Longitude/Latitudes.

    // Instead of full trigonometric expansion of G, H, etc., we use simplified perturbations for 1:2:4 resonance
    // and solve geometric projection.

    // Mean Anomaly of Jup
    val G = normalizeDegrees(357.529 + 0.9856003 * (tJD - 2451545.0)) // Earth
    val J = normalizeDegrees(273.8777 + 0.083091052 * d) // Jupiter Mean Anomaly approx

    // Corrected Longitudes (u)
    // Major inequalities (Meeus 44.2 - 44.4)
    val phi = l1 - 2*l2
    val u1 = l1 + 0.472 * sin(Math.toRadians(2*(l1-l2)))
    val u2 = l2 + 1.065 * sin(Math.toRadians(2*(l2-l3)))
    val u3 = l3 + 0.165 * sin(Math.toRadians(l3 - pi))
    val u4 = l4 + 0.843 * sin(Math.toRadians(l4 - pi))

    // Use Geometric Phase Projection
    // Angle visible from Earth (Lambda)
    // L_Earth - L_Jup.
    val sunState = calculateSunPositionKepler(tJD)
    val earthLon = normalizeDegrees(sunState.eclipticLon + 180.0)
    val jupLon = jupState.eclipticLon

    // Angle Earth-Jupiter-Sun (Phase)
    // Approx by (L_Jup - L_Earth).
    val alpha = normalizeDegrees(jupLon - earthLon) // Phase angle (approx)

    // Correction K (Meeus 44.5)
    val K = alpha + 180.0

    // Radii
    val r1 = 5.9; val r2 = 9.4; val r3 = 15.0; val r4 = 26.4

    // Declination of Earth (De)
    // Reuse pole calculation from previous fix
    val alpha0 = Math.toRadians(268.05); val delta0 = Math.toRadians(64.49)
    val jRA = Math.toRadians(jupState.ra); val jDec = Math.toRadians(jupState.dec)
    val sinDe = -sin(delta0)*sin(jDec) - cos(delta0)*cos(jDec)*cos(alpha0 - jRA)
    val De = asin(sinDe)

    fun calc(u: Double, r: Double): JovianMoonState {
        // Apparent distance X, Y
        // X = r * sin(u - L_Jup + Phase) = r * sin(u - L_Earth)
        // Correct approach is u - JupLon + (JupLon - EarthLon) ?
        // Meeus formula: X = r * sin(u - L + correction).
        // Let's use simple geometric:
        // Argument theta = u - EarthLon.
        // At opposition (EarthLon = JupLon + 180), theta = u - JupLon - 180.
        // If Moon at conjunction (u=JupLon), theta = -180. sin(-180)=0. Correct.
        // If Moon at max elong East (u=JupLon+90), theta = -90. sin=-1. (Screen left). Correct.

        val theta = Math.toRadians(u - earthLon)
        val x = -r * sin(theta) // Negate so East is Right (Astronomical) or Left (Map)?
        // Astronomical convention: East is Left. X positive.
        // We want simple map: "East Left" option handles flip.
        // Let's standard: X = r sin(theta).

        // Z depth
        val z = r * cos(theta) * cos(De)
        val y = r * cos(theta) * sin(De)

        return JovianMoonState(x, y, z)
    }

    return mapOf(
        "Io" to calc(u1, r1),
        "Europa" to calc(u2, r2),
        "Ganymede" to calc(u3, r3),
        "Callisto" to calc(u4, r4)
    )
}