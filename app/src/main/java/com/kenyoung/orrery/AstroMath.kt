package com.kenyoung.orrery

import kotlin.math.*
import java.time.Instant

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
// lon/lat in degrees, dist in AU
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
// Used for phenomena checks when using CSV data
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

// --- KEPLERIAN CALCULATORS (Moved from MainActivity) ---

// Calculates Sun Position (Low Precision Keplerian)
fun calculateSunPositionKepler(jd: Double): BodyState {
    val n = jd - 2451545.0
    var L = normalizeDegrees(280.460 + 0.9856474 * n)
    var g = normalizeDegrees(357.528 + 0.9856003 * n)
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val r = 1.00014 - 0.01671 * cos(Math.toRadians(g)) - 0.00014 * cos(2 * Math.toRadians(g))

    // Geo Cartesian (Sun from Earth)
    // Note: Keplerian model usually treats Sun at (0,0,0) Helio, but here we want Geocentric coords
    // Convert Ecliptic to Equatorial
    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))

    val raDeg = normalizeDegrees(Math.toDegrees(alpha))
    val decDeg = Math.toDegrees(delta)

    // Helio Pos for Sun is origin
    val helioPos = Vector3(0.0, 0.0, 0.0)

    // Geo Pos (Earth relative to Sun is -Sun relative to Earth)
    // But for "Sun" BodyState, we want Sun's position relative to Earth.
    // X = r * cos(dec) * cos(ra) ...
    val geoPos = sphericalToCartesian(r, raDeg, decDeg)

    return BodyState(
        "Sun", jd, helioPos, geoPos, raDeg, decDeg, 0.0, r, Math.toDegrees(lambdaRad), 0.0
    )
}

// Calculates Planet Position (Keplerian)
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
    
    // Heliocentric State
    val helioPos = Vector3(xh, yh, zh)
    val helioLon = normalizeDegrees(Math.toDegrees(atan2(yh, xh)))
    val helioLat = Math.toDegrees(asin(zh / rp))

    // Geocentric
    val xg = xh - xe; val yg = yh - ye; val zg = zh - ze
    val distGeo = sqrt(xg*xg + yg*yg + zg*zg)
    
    // Equatorial
    val ecl = Math.toRadians(23.439 - 0.0000004 * d)
    val xeq = xg; val yeq = yg * cos(ecl) - zg * sin(ecl); val zeq = yg * sin(ecl) + zg * cos(ecl)
    val raHours = Math.toDegrees(atan2(yeq, xeq)) / 15.0
    val raDeg = normalizeDegrees(raHours * 15.0)
    val decDeg = Math.toDegrees(atan2(zeq, sqrt(xeq*xeq + yeq*yeq)))

    // Ecliptic Geocentric (for phenomena)
    val (eclLon, eclLat) = equatorialToEcliptic(raDeg, decDeg, jd)

    return BodyState(p.name, jd, helioPos, Vector3(xg, yg, zg), raDeg, decDeg, rp, distGeo, eclLon, eclLat)
}

// --- LEGACY/COMPATIBILITY HELPERS ---

fun calculateSunTimes(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double = -0.833): Pair<Double, Double> {
    val state = calculateSunPositionKepler(epochDay + 2440587.5 + 0.5) // approx noon
    return calculateRiseSet(state.ra, state.dec, lat, lon, timezoneOffset, altitude, epochDay)
}

// Universal Rise/Set calculator from RA/Dec
fun calculateRiseSet(raDeg: Double, decDeg: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double, epochDay: Double): Pair<Double, Double> {
    val jd = epochDay + 2440587.5 + 0.5
    val n = jd - 2451545.0
    val GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0
    val gmstFixed = if (GMST0 < 0) GMST0 + 24.0 else GMST0
    
    val raHours = raDeg / 15.0
    
    var transitUT = raHours - (lon / 15.0) - gmstFixed
    while (transitUT < 0) transitUT += 24.0
    while (transitUT >= 24) transitUT -= 24.0
    
    val transitStandard = transitUT + timezoneOffset
    
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(decDeg)
    val altRad = Math.toRadians(altitude)
    
    val cosH = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))
    if (cosH < -1.0 || cosH > 1.0) return Pair(Double.NaN, Double.NaN)
    
    val hHours = Math.toDegrees(acos(cosH)) / 15.0
    
    var rise = transitStandard - hHours
    var set = transitStandard + hHours
    
    while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
    while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
    
    return Pair(rise, set)
}

// Compatible with TransitsScreen calling structure
fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    // We defer to AstroEngine if strictly necessary, but for now we keep this purely math-based
    // to avoid circular deps or complex refactors of legacy screens.
    // Ideally TransitsScreen should call AstroEngine, but this file satisfies the "raw math" requirement.
    // We will use the Keplerian calculation here for speed in the cache generator.
    val state = calculatePlanetStateKeplerian(epochDay + 2440587.5 + 0.5, p)
    val (rise, set) = calculateRiseSet(state.ra, state.dec, lat, lon, timezoneOffset, -0.5667, epochDay)
    
    // Recalculate transit
    val n = (epochDay + 2440587.5 + 0.5) - 2451545.0
    val GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0
    val gmstFixed = if (GMST0 < 0) GMST0 + 24.0 else GMST0
    var transitUT = (state.ra / 15.0) - (lon / 15.0) - gmstFixed
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transit = transitUT + timezoneOffset
    var tr = transit; while(tr < 0) tr+=24.0; while(tr>=24.0) tr-=24.0

    return PlanetEvents(rise, tr, set)
}

// Moon Functions (Simplified for compatibility)
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
    // Low precision logic for display
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
    val raRad = atan2(y, x)
    val decRad = asin(z)
    val raDeg = normalizeDegrees(Math.toDegrees(raRad))
    val decDeg = Math.toDegrees(decRad)
    
    val (rise, set) = calculateRiseSet(raDeg, decDeg, lat, lon, timezoneOffset, 0.125, epochDay) // Parallax approx
    
    // Recalc transit
    val n = (epochDay + 2440587.5 + 0.5) - 2451545.0
    val GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0
    val gmstFixed = if (GMST0 < 0) GMST0 + 24.0 else GMST0
    var transitUT = (raDeg / 15.0) - (lon / 15.0) - gmstFixed
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val tr = transitUT + timezoneOffset
    var trFin = tr; while(trFin < 0) trFin+=24.0; while(trFin>=24.0) trFin-=24.0
    
    return PlanetEvents(rise, trFin, set)
}
// Shared EOT
fun calculateEquationOfTimeMinutes(epochDay: Double): Double {
    val jd = epochDay + 2440587.5
    val n = jd - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0
    if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambda = L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(Math.toRadians(2 * g))
    val epsilon = 23.439 - 0.0000004 * n
    val alphaRad = atan2(cos(Math.toRadians(epsilon)) * sin(Math.toRadians(lambda)), cos(Math.toRadians(lambda)))
    var alphaDeg = Math.toDegrees(alphaRad)
    if (alphaDeg < 0) alphaDeg += 360.0
    var E_deg = L - alphaDeg
    while (E_deg > 180) E_deg -= 360.0
    while (E_deg <= -180) E_deg += 360.0
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
   val ev = calculateMoonEvents(epochDay, 0.0, 0.0, 0.0) // Dummy call to reuse logic?
   // Actually better to duplicate the lightweight low-prec logic for now:
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
