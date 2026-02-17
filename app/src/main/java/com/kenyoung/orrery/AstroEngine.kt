package com.kenyoung.orrery

import kotlin.math.*

object AstroEngine {

    fun getBodyState(name: String, jd: Double): BodyState {
        // 1. Special Case: Earth
        // The CSV doesn't have "Earth". We derive it from "Sun".
        // Earth Helio Pos = -1 * Sun Geo Pos (approx)
        if (name == "Earth") {
            return getEarthState(jd)
        }

        // 2. Try CSV
        val csvData = EphemerisManager.getInterpolatedData(name, jd)

        if (csvData != null) {
            val ra = csvData[EphemerisManager.IDX_RA]
            val dec = csvData[EphemerisManager.IDX_DEC]
            val geoDist = csvData[EphemerisManager.IDX_GEO_DIST]
            val helioDist = csvData[EphemerisManager.IDX_HELIO_DIST]
            val helioLon = csvData[EphemerisManager.IDX_HELIO_LON]
            val helioLat = csvData[EphemerisManager.IDX_HELIO_LAT]

            val helioPos = sphericalToCartesian(helioDist, helioLon, helioLat)
            val (eclLon, eclLat) = equatorialToEcliptic(ra, dec, jd)

            return BodyState(helioPos, ra, dec, helioDist, geoDist, eclLon, eclLat)
        }

        // 3. Fallback to Keplerian
        if (name == "Sun") return calculateSunPositionKepler(jd)
        if (name == "Moon") {
            // Fallback Moon: Use simple low-precision logic and wrap in BodyState
            val radec = calculateMoonPosition(jd - UNIX_EPOCH_JD) // function expects epochDay
            val (eLon, eLat) = equatorialToEcliptic(radec.ra * 15.0, radec.dec, jd)
            // Fake distance for fallback
            return BodyState(Vector3(0.0,0.0,0.0), radec.ra * 15.0, radec.dec, 1.0, 0.00257, eLon, eLat)
        }

        // Planet Fallback
        val elements = getOrreryPlanets().find { it.name == name }
            ?: if(name == "Halley") getHalleyElement() else null

        if (elements != null) {
            return calculatePlanetStateKeplerian(jd, elements)
        }

        // Error / Unknown
        return BodyState(Vector3(0.0,0.0,0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    private fun getEarthState(jd: Double): BodyState {
        val sunState = getBodyState("Sun", jd)

        // Earth's helioPos must be Ecliptic (consistent with other planets for OrreryScreens)
        val helioLon = normalizeDegrees(sunState.eclipticLon + 180.0)
        val helioLat = -sunState.eclipticLat
        val helioDist = sunState.distGeo
        val helioPos = sphericalToCartesian(helioDist, helioLon, helioLat)

        return BodyState(helioPos, 0.0, 0.0, helioDist, 0.0, helioLon, helioLat)
    }
}