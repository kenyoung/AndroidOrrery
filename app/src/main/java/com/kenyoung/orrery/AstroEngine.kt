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
            // Unpack
            val ra = csvData[EphemerisManager.IDX_RA]
            val dec = csvData[EphemerisManager.IDX_DEC]
            val geoDist = csvData[EphemerisManager.IDX_GEO_DIST]
            val helioDist = csvData[EphemerisManager.IDX_HELIO_DIST]
            val helioLon = csvData[EphemerisManager.IDX_HELIO_LON]
            val helioLat = csvData[EphemerisManager.IDX_HELIO_LAT]

            // Calculate Vectors
            // Note: HelioPos here is derived from Ecliptic Longitude/Latitude
            // This places the vector in the Ecliptic system, which matches OrreryScreens.
            val helioPos = sphericalToCartesian(helioDist, helioLon, helioLat)

            // Geo Pos (from RA/Dec/GeoDist)
            // Note: RA is in degrees in CSV
            val raRad = Math.toRadians(ra)
            val decRad = Math.toRadians(dec)
            val gx = geoDist * cos(decRad) * cos(raRad)
            val gy = geoDist * cos(decRad) * sin(raRad)
            val gz = geoDist * sin(decRad)
            val geoPos = Vector3(gx, gy, gz)

            // Ecliptic Coordinates (Derived)
            // Essential for Phenomena Screen
            val (eclLon, eclLat) = equatorialToEcliptic(ra, dec, jd)

            return BodyState(
                name, jd, helioPos, geoPos, ra, dec, helioDist, geoDist, eclLon, eclLat
            )
        }

        // 3. Fallback to Keplerian
        if (name == "Sun") return calculateSunPositionKepler(jd)
        if (name == "Moon") {
            // Fallback Moon: Use simple low-precision logic and wrap in BodyState
            val radec = calculateMoonPosition(jd - 2440587.5) // function expects epochDay
            val (eLon, eLat) = equatorialToEcliptic(radec.ra * 15.0, radec.dec, jd)
            // Fake distance for fallback
            return BodyState("Moon", jd, Vector3(0.0,0.0,0.0), Vector3(0.0,0.0,0.0), radec.ra * 15.0, radec.dec, 1.0, 0.00257, eLon, eLat)
        }

        // Planet Fallback
        val elements = getOrreryPlanets().find { it.name == name }
            ?: if(name == "Halley") getHalleyElement() else null

        if (elements != null) {
            return calculatePlanetStateKeplerian(jd, elements)
        }

        // Error / Unknown
        return BodyState(name, jd, Vector3(0.0,0.0,0.0), Vector3(0.0,0.0,0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    private fun getEarthState(jd: Double): BodyState {
        // Get Sun State first (likely from Keplerian fallback in AstroMath)
        val sunState = getBodyState("Sun", jd)

        // BUG FIX:
        // Originally, we inverted sunState.geoPos (which is Equatorial).
        // ScaleOrrery expects helioPos to be Ecliptic (consistent with other planets).
        // We must construct Earth's helioPos from the Ecliptic coordinates provided by the Sun calculation.

        // Earth Helio Longitude = Sun Ecliptic Longitude + 180 degrees
        val helioLon = normalizeDegrees(sunState.eclipticLon + 180.0)

        // Earth Helio Latitude = -Sun Ecliptic Latitude (approx 0)
        val helioLat = -sunState.eclipticLat

        // Earth Helio Dist = Sun Geo Distance
        val helioDist = sunState.distGeo

        // Construct Ecliptic Vector
        val helioPos = sphericalToCartesian(helioDist, helioLon, helioLat)

        // Earth Geo Pos is origin (0,0,0)
        return BodyState(
            "Earth", jd, helioPos, Vector3(0.0,0.0,0.0),
            0.0, 0.0, helioDist, 0.0, helioLon, helioLat
        )
    }
}