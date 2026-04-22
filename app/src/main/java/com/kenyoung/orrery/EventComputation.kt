package com.kenyoung.orrery

import kotlin.math.*

// --- EVENT CACHE ---

data class EventCache(
    val events: PlanetEvents,
    val anchorEpochDay: Double,
    val transitTomorrow: Boolean = false,
    val setTomorrow: Boolean = false,
    val riseDec: Double = Double.NaN,
    val transitDec: Double = Double.NaN,
    val setDec: Double = Double.NaN,
    val isCircumpolar: Boolean = false,
    val calcJd: Double = Double.NaN      // used by Compass caching layer only
)

// UT epoch day at which the cached set occurs (NaN if circumpolar / never rises).
// Used by cache-validity checks and by the Compass set-watcher.
fun EventCache.setUtEpochDay(offset: Double): Double {
    val setHAbs = events.set + if (setTomorrow) 24.0 else 0.0
    return floor(anchorEpochDay) - offset / 24.0 + setHAbs / 24.0
}

// Safety bound for the auto-advance loop in computeMoonEventData. In practice 1
// or 2 advances suffice (the lunar cycle guarantees a rise every ~24h50m); this
// just prevents runaway scans at extreme latitudes.
private const val MAX_MOON_ANCHOR_ADVANCES = 5

// --- SHARED EVENT COMPUTATION FUNCTIONS ---

// Computes Sun rise/transit/set for the observing night, advancing to the next day if
// sunset has already passed.
fun computeSunEventData(
    epochDay: Double, lat: Double, lon: Double,
    offset: Double, currentUtEpochDay: Double
): EventCache {
    var sunAnchor = epochDay
    var (sunRise, sunSet) = calculateSunTimes(epochDay, lat, lon, offset)
    var (sunTransit, _) = calculateSunTransit(epochDay, lon, offset)

    val baseMidnightUT = floor(epochDay) - offset / 24.0
    if (baseMidnightUT + sunSet / 24.0 < currentUtEpochDay) {
        sunAnchor = epochDay + 1.0
        val (r, s) = calculateSunTimes(epochDay + 1.0, lat, lon, offset)
        val (t, _) = calculateSunTransit(epochDay + 1.0, lon, offset)
        sunRise = r; sunSet = s; sunTransit = t
    }

    val isCircumpolar = sunRise.isNaN() && sunSet.isNaN() &&
            calculateAltitude(12.0, lat, calculateSunTransit(sunAnchor, lon, offset).dec) > HORIZON_REFRACTED

    return EventCache(
        events = PlanetEvents(sunRise, sunTransit, sunSet),
        anchorEpochDay = sunAnchor,
        isCircumpolar = isCircumpolar
    )
}

// Computes Moon rise/transit/set for the observing night, advancing to the next day if
// the Moon has already set. Handles two-day ordering (pairedRiseSet=false for Compass)
// and simpler single-fetch (pairedRiseSet=true for Elevations).
fun computeMoonEventData(
    eventEpochDay: Double, lat: Double, lon: Double,
    offset: Double, currentUtEpochDay: Double,
    fallbackDec: Double,
    pairedRiseSet: Boolean = false
): EventCache {
    val moonEvents: PlanetEvents
    val moonAnchor: Double
    val moonTransitTomorrow: Boolean
    val moonSetTomorrow: Boolean

    if (pairedRiseSet) {
        // Simpler path: calculateMoonEvents guarantees set follows rise
        var ev = calculateMoonEvents(eventEpochDay, lat, lon, offset, pairedRiseSet = true)
        var anchor = eventEpochDay

        if (!ev.rise.isNaN() && !ev.set.isNaN()) {
            val dayStartUT = floor(eventEpochDay) - offset / 24.0
            val setAbsLocal = if (ev.set >= ev.rise) ev.set else ev.set + 24.0
            if (dayStartUT + setAbsLocal / 24.0 < currentUtEpochDay) {
                ev = calculateMoonEvents(eventEpochDay + 1.0, lat, lon, offset, pairedRiseSet = true)
                anchor = eventEpochDay + 1.0
            }
        }

        moonEvents = ev
        moonAnchor = anchor
        moonTransitTomorrow = false
        moonSetTomorrow = false
    } else {
        // Auto-advance the anchor day until the resulting track's set is in the
        // future. Multiple advances are needed at extreme latitudes when a
        // calendar day has no moonrise (the previous rose just before midnight
        // and the next rises just after) — a single advance leaves the cache
        // anchored to the previous track's already-past set.
        var evBase = calculateMoonEvents(eventEpochDay, lat, lon, offset)
        var evNext = calculateMoonEvents(eventEpochDay + 1.0, lat, lon, offset)
        var anchor = eventEpochDay
        var transAbs = 0.0
        var setAbs = 0.0
        var advances = 0
        while (true) {
            transAbs = if (evBase.transit >= evBase.rise) evBase.transit else evNext.transit + 24.0
            setAbs = if (evBase.set >= transAbs) evBase.set else evNext.set + 24.0
            val baseMidnightUT = floor(anchor) - offset / 24.0
            if (baseMidnightUT + setAbs / 24.0 >= currentUtEpochDay) break
            if (advances == MAX_MOON_ANCHOR_ADVANCES) break
            evBase = evNext
            anchor += 1.0
            evNext = calculateMoonEvents(anchor + 1.0, lat, lon, offset)
            advances++
        }

        val transTomorrow = transAbs >= 24.0
        val setTom = setAbs >= 24.0

        moonEvents = PlanetEvents(evBase.rise,
            if (transTomorrow) transAbs - 24.0 else transAbs,
            if (setTom) setAbs - 24.0 else setAbs)
        moonAnchor = anchor
        moonTransitTomorrow = transTomorrow
        moonSetTomorrow = setTom
    }

    // Compute topocentric declination at each event JD
    val anchorMidnightJD = floor(moonAnchor) + UNIX_EPOCH_JD - offset / 24.0
    val moonDecAt = { jd: Double ->
        val st = AstroEngine.getBodyState("Moon", jd)
        val (appRa, appDec) = j2000ToApparent(st.ra, st.dec, jd)
        val lst = calculateLSTHours(jd, lon)
        toTopocentric(appRa, appDec, st.distGeo, lat, lon, lst).dec
    }
    val riseDec = if (!moonEvents.rise.isNaN()) moonDecAt(anchorMidnightJD + moonEvents.rise / 24.0) else fallbackDec
    val transitDec = if (!moonEvents.transit.isNaN()) {
        moonDecAt(anchorMidnightJD + (moonEvents.transit + if (moonTransitTomorrow) 24.0 else 0.0) / 24.0)
    } else fallbackDec
    val setDec = if (!moonEvents.set.isNaN()) {
        moonDecAt(anchorMidnightJD + (moonEvents.set + if (moonSetTomorrow) 24.0 else 0.0) / 24.0)
    } else fallbackDec

    // Circumpolar detection: NaN rise+set means either always up or never up
    val isCircumpolar = moonEvents.rise.isNaN() && moonEvents.set.isNaN() && run {
        val checkJD = anchorMidnightJD + 12.0 / 24.0 // noon UT
        val st = AstroEngine.getBodyState("Moon", checkJD)
        val (appRa, appDec) = j2000ToApparent(st.ra, st.dec, checkJD)
        val lst = calculateLSTHours(checkJD, lon)
        val topo = toTopocentric(appRa, appDec, st.distGeo, lat, lon, lst)
        val moonSdDeg = Math.toDegrees(asin(MOON_RADIUS_M / (st.distGeo * AU_METERS)))
        calculateAltitude(lst - topo.ra / 15.0, lat, topo.dec) > PLANET_HORIZON_ALT - moonSdDeg
    }

    return EventCache(
        events = moonEvents,
        anchorEpochDay = moonAnchor,
        transitTomorrow = moonTransitTomorrow,
        setTomorrow = moonSetTomorrow,
        riseDec = riseDec,
        transitDec = transitDec,
        setDec = setDec,
        isCircumpolar = isCircumpolar
    )
}

// Computes the Moon's current track (if above horizon) or next track (if below).
// A "track" is the span of a single rise→transit→set cycle. Once computed, the rise,
// transit, and set times stay constant until the Moon sets; at that point the next
// call (after the cached set time has passed) returns the next track.
//
// We anchor to (todayLocal - 1.0) so that computeMoonEventData's auto-advance handles
// every case uniformly:
//   • Track started yesterday evening, now in progress     → no advance, returns current
//   • Track starts/started today after sunrise             → auto-advance, returns current
//   • Current track already ended, now waiting for next    → auto-advance, returns next
fun computeMoonTrack(
    lat: Double, lon: Double, offset: Double,
    currentUtEpochDay: Double,
    fallbackDec: Double
): EventCache {
    val todayLocal = floor(currentUtEpochDay + offset / 24.0)
    return computeMoonEventData(todayLocal - 1.0, lat, lon, offset, currentUtEpochDay, fallbackDec)
}

// Computes planet rise/transit/set for the observing night, advancing to the next day if
// the planet has already set. Uses two-day fetch with explicit ordering.
fun computePlanetEventData(
    eventEpochDay: Double, lat: Double, lon: Double,
    offset: Double, currentUtEpochDay: Double,
    planet: PlanetElements, fallbackDec: Double
): EventCache {
    var pAnchor = eventEpochDay
    var evD = calculatePlanetEvents(eventEpochDay, lat, lon, offset, planet)
    var evD1 = calculatePlanetEvents(eventEpochDay + 1.0, lat, lon, offset, planet)

    var pTransitAbs = if (evD.transit >= evD.rise) evD.transit else evD1.transit + 24.0
    var pSetAbs = if (evD.set >= pTransitAbs) evD.set else evD1.set + 24.0

    val baseMidnightUT = floor(eventEpochDay) - offset / 24.0
    if (baseMidnightUT + pSetAbs / 24.0 < currentUtEpochDay) {
        pAnchor = eventEpochDay + 1.0
        evD = evD1
        evD1 = calculatePlanetEvents(eventEpochDay + 2.0, lat, lon, offset, planet)
        pTransitAbs = if (evD.transit >= evD.rise) evD.transit else evD1.transit + 24.0
        pSetAbs = if (evD.set >= pTransitAbs) evD.set else evD1.set + 24.0
    }

    val pTransitTomorrow = pTransitAbs >= 24.0
    val pSetTomorrow = pSetAbs >= 24.0
    val events = PlanetEvents(evD.rise,
        if (pTransitTomorrow) pTransitAbs - 24.0 else pTransitAbs,
        if (pSetTomorrow) pSetAbs - 24.0 else pSetAbs)

    // Compute apparent declination at each event JD
    val pAnchorMidnightJD = floor(pAnchor) + UNIX_EPOCH_JD - offset / 24.0
    val pRiseJD = pAnchorMidnightJD + evD.rise / 24.0
    val pTransitJD = pAnchorMidnightJD + (events.transit + if (pTransitTomorrow) 24.0 else 0.0) / 24.0
    val pSetJD = pAnchorMidnightJD + (events.set + if (pSetTomorrow) 24.0 else 0.0) / 24.0
    val riseDec = if (!evD.rise.isNaN()) { val s = AstroEngine.getBodyState(planet.name, pRiseJD); j2000ToApparent(s.ra, s.dec, pRiseJD).dec } else fallbackDec
    val transitDec = if (!events.transit.isNaN()) { val s = AstroEngine.getBodyState(planet.name, pTransitJD); j2000ToApparent(s.ra, s.dec, pTransitJD).dec } else fallbackDec
    val setDec = if (!events.set.isNaN()) { val s = AstroEngine.getBodyState(planet.name, pSetJD); j2000ToApparent(s.ra, s.dec, pSetJD).dec } else fallbackDec

    // Circumpolar detection
    val isCircumpolar = events.rise.isNaN() && events.set.isNaN() && run {
        val checkJD = pAnchorMidnightJD + 12.0 / 24.0
        val st = AstroEngine.getBodyState(planet.name, checkJD)
        val (appRa, appDec) = j2000ToApparent(st.ra, st.dec, checkJD)
        val lst = calculateLSTHours(checkJD, lon)
        calculateAltitude(lst - appRa / 15.0, lat, appDec) > PLANET_HORIZON_ALT
    }

    return EventCache(
        events = events,
        anchorEpochDay = pAnchor,
        transitTomorrow = pTransitTomorrow,
        setTomorrow = pSetTomorrow,
        riseDec = riseDec,
        transitDec = transitDec,
        setDec = setDec,
        isCircumpolar = isCircumpolar
    )
}

// --- TWILIGHT DATA ---

data class TwilightTimes(
    val dusk: List<Double>,     // 5 SET times in local solar hours (golden, sunset, civil, nautical, astro)
    val dawn: List<Double>,     // 5 RISE times in local solar hours (golden, sunrise, civil, nautical, astro)
    val duskAnchor: Double,     // epoch day for dusk times
    val dawnAnchor: Double      // epoch day for dawn times (duskAnchor + 1)
)

val TWILIGHT_ALTITUDES = doubleArrayOf(
    GOLDEN_HOUR_ALT, HORIZON_REFRACTED, CIVIL_TWILIGHT, NAUTICAL_TWILIGHT, ASTRONOMICAL_TWILIGHT
)
val TWILIGHT_LABELS = arrayOf(
    "\"Golden Hour\"", "Civil Twilight", "Nautical Twilight", "Astronomical Twilight", "Darkness"
)

fun computeTwilightTimes(eventEpochDay: Double, lat: Double, lon: Double, offset: Double): TwilightTimes {
    val dusk = TWILIGHT_ALTITUDES.map { alt -> calculateSunTimes(eventEpochDay, lat, lon, offset, alt).set }
    val dawn = TWILIGHT_ALTITUDES.map { alt -> calculateSunTimes(eventEpochDay + 1.0, lat, lon, offset, alt).rise }
    return TwilightTimes(dusk, dawn, eventEpochDay, eventEpochDay + 1.0)
}
