package com.kenyoung.orrery

import kotlin.math.*

// ===========================================================================
// Uranus system: moon positions ported from /home/rtm/uranus.py
// Uses prograde Uranus Mean Equatorial pole (RA=77.311°, Dec=15.175°)
// and mean Keplerian elements with apsidal/nodal precession.
// ===========================================================================

data class UranusApparentMoon(
    val name: String,
    val eastArcsec: Double,   // east offset from Uranus center (arcsec, + = east)
    val northArcsec: Double,  // north offset from Uranus center (arcsec, + = north)
    val behindDisk: Boolean   // true if behind Uranus disk
)

data class UranusSystemData(
    val moons: List<UranusApparentMoon>,
    val ringTiltB: Double,          // degrees, sub-Earth latitude (prograde convention)
    val positionAngleP: Double,     // degrees, PA of prograde pole on sky
    val distGeo: Double,            // AU from Earth
    val distSun: Double,            // AU from Sun
    val angularRadiusArcsec: Double // angular radius of Uranus disk in arcseconds
)

object UranusMoonEngine {

    // Prograde Uranus Mean Equatorial pole (J2000)
    private const val POLE_RA_DEG = 77.311
    private const val POLE_DEC_DEG = 15.175

    private const val URANUS_RADIUS_KM = 25559.0
    private const val AU_KM = 149597870.7
    private const val C_LIGHT_KM_PER_SEC = 299792.458
    private const val DAYS_PER_YEAR = 365.25
    private const val RAD_TO_ARCSEC = 180.0 / PI * 3600.0

    // Ring radii in Uranus equatorial radii
    val RING_6 = 1.637f
    val RING_5 = 1.652f
    val RING_ALPHA = 1.740f
    val RING_BETA = 1.776f
    val RING_ETA = 1.834f
    val RING_GAMMA = 1.862f
    val RING_DELTA = 1.900f
    val RING_EPSILON = 1.968f

    // Moon orbital elements — copied exactly from uranus.py
    private data class MoonElements(
        val name: String,
        val aKm: Double,
        val e: Double,
        val omegaDeg0: Double,
        val meanAnomalyDeg0: Double,
        val iDeg0: Double,
        val nodeDeg0: Double,
        val siderealPeriodDays: Double,
        val apsisPeriodYears: Double,
        val nodePeriodYears: Double
    )

    private val moonElements = listOf(
        MoonElements("Miranda",  129846.0, 0.0010, 154.8,  73.0, 4.4, 100.9,  1.413479,   8.939,  17.787),
        MoonElements("Ariel",    190929.0, 0.0010,   9.6, 193.5, 0.0,   0.0,  2.520379,  28.901,   0.0),
        MoonElements("Umbriel",  265986.0, 0.0040, 183.4, 253.0, 0.1, 174.8,  4.144177,  64.126, 129.745),
        MoonElements("Titania",  436298.0, 0.0020, 184.0,  68.1, 0.1,  29.5,  8.705869, 579.928, 1644.649),
        MoonElements("Oberon",   583511.0, 0.0020, 132.2, 143.6, 0.1,  76.8, 13.463237, 158.604, 192.798)
    )

    // UME frame axes (J2000 equatorial) — built once
    // Z = prograde pole, X = ICRF_north × Z, Y = Z × X
    private val umeX: DoubleArray
    private val umeY: DoubleArray
    private val umeZ: DoubleArray

    init {
        val poleRaRad = Math.toRadians(POLE_RA_DEG)
        val poleDecRad = Math.toRadians(POLE_DEC_DEG)
        val zx = cos(poleDecRad) * cos(poleRaRad)
        val zy = cos(poleDecRad) * sin(poleRaRad)
        val zz = sin(poleDecRad)
        umeZ = doubleArrayOf(zx, zy, zz)

        // X = ICRF_north(0,0,1) × Z
        var xx = 0.0 * zz - 1.0 * zy   // = -zy
        var xy = 1.0 * zx - 0.0 * zz   // = zx... wait
        // cross(north, z) = (0,0,1) × (zx,zy,zz) = (0*zz - 1*zy, 1*zx - 0*zz, 0*zy - 0*zx)
        //                 = (-zy, zx, 0)
        xx = -zy
        xy = zx
        var xz = 0.0
        val xLen = sqrt(xx * xx + xy * xy + xz * xz)
        xx /= xLen; xy /= xLen; xz /= xLen
        umeX = doubleArrayOf(xx, xy, xz)

        // Y = Z × X
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        val yLen = sqrt(yx * yx + yy * yy + yz * yz)
        umeY = doubleArrayOf(yx / yLen, yy / yLen, yz / yLen)
    }

    private fun wrap360(angle: Double): Double {
        var v = angle % 360.0
        if (v < 0.0) v += 360.0
        return v
    }

    // Compute moon's ICRF offset from Uranus in km — direct port of Python's algorithmicMoonRelativeVector
    private fun moonRelativeVectorKm(jd: Double, elem: MoonElements): DoubleArray {
        val dtDays = jd - J2000_JD

        // Mean longitude at epoch
        val meanLongDeg0 = wrap360(elem.nodeDeg0 + elem.omegaDeg0 + elem.meanAnomalyDeg0)
        // Mean longitude at time
        val meanLongDeg = wrap360(meanLongDeg0 + 360.0 * dtDays / elem.siderealPeriodDays)

        // Precessing argument of pericenter
        var omegaDeg = elem.omegaDeg0
        if (elem.apsisPeriodYears > 0.0) {
            omegaDeg = wrap360(omegaDeg + 360.0 * dtDays / (elem.apsisPeriodYears * DAYS_PER_YEAR))
        }

        // Precessing node
        var nodeDeg = elem.nodeDeg0
        if (elem.nodePeriodYears > 0.0 && elem.iDeg0 != 0.0) {
            nodeDeg = wrap360(nodeDeg - 360.0 * dtDays / (elem.nodePeriodYears * DAYS_PER_YEAR))
        }

        val meanAnomalyDeg = wrap360(meanLongDeg - omegaDeg - nodeDeg)

        val incRad = Math.toRadians(elem.iDeg0)
        val nodeRad = Math.toRadians(nodeDeg)
        val omegaRad = Math.toRadians(omegaDeg)
        val meanAnomalyRad = Math.toRadians(meanAnomalyDeg)

        val eccAnomRad = solveKepler(meanAnomalyRad, elem.e)

        val xOrb = elem.aKm * (cos(eccAnomRad) - elem.e)
        val yOrb = elem.aKm * sqrt(1.0 - elem.e * elem.e) * sin(eccAnomRad)

        val cosNode = cos(nodeRad); val sinNode = sin(nodeRad)
        val cosInc = cos(incRad); val sinInc = sin(incRad)
        val cosOmega = cos(omegaRad); val sinOmega = sin(omegaRad)

        // Rotate to UME frame (matches Python exactly)
        val xEq = (cosNode * cosOmega - sinNode * sinOmega * cosInc) * xOrb +
                  (-cosNode * sinOmega - sinNode * cosOmega * cosInc) * yOrb
        val yEq = (sinNode * cosOmega + cosNode * sinOmega * cosInc) * xOrb +
                  (-sinNode * sinOmega + cosNode * cosOmega * cosInc) * yOrb
        val zEq = (sinOmega * sinInc) * xOrb + (cosOmega * sinInc) * yOrb

        // Convert from UME to ICRF using frame axes
        return doubleArrayOf(
            umeX[0] * xEq + umeY[0] * yEq + umeZ[0] * zEq,
            umeX[1] * xEq + umeY[1] * yEq + umeZ[1] * zEq,
            umeX[2] * xEq + umeY[2] * yEq + umeZ[2] * zEq
        )
    }

    // Project geocentric vectors onto tangent plane — port of Python's tangentPlaneOffsets
    private fun tangentPlaneOffsetsArcsec(
        moonGeoVecs: List<DoubleArray>,
        centerVec: DoubleArray
    ): List<Pair<Double, Double>> {
        val centerLen = sqrt(centerVec[0] * centerVec[0] + centerVec[1] * centerVec[1] + centerVec[2] * centerVec[2])
        val centerRa = atan2(centerVec[1], centerVec[0])
        val centerDec = asin((centerVec[2] / centerLen).coerceIn(-1.0, 1.0))
        val centerUnit = doubleArrayOf(centerVec[0] / centerLen, centerVec[1] / centerLen, centerVec[2] / centerLen)

        val east = doubleArrayOf(-sin(centerRa), cos(centerRa), 0.0)
        val north = doubleArrayOf(
            -sin(centerDec) * cos(centerRa),
            -sin(centerDec) * sin(centerRa),
            cos(centerDec)
        )

        return moonGeoVecs.map { vec ->
            val len = sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2])
            val uv = doubleArrayOf(vec[0] / len, vec[1] / len, vec[2] / len)
            val denom = uv[0] * centerUnit[0] + uv[1] * centerUnit[1] + uv[2] * centerUnit[2]
            val x = (uv[0] * east[0] + uv[1] * east[1] + uv[2] * east[2]) / denom
            val y = (uv[0] * north[0] + uv[1] * north[1] + uv[2] * north[2]) / denom
            Pair(x * RAD_TO_ARCSEC, y * RAD_TO_ARCSEC)
        }
    }

    // --- Public entry point ---
    fun getUranusSystemData(jd: Double): UranusSystemData {
        val uranusBody = AstroEngine.getBodyState("Uranus", jd)
        val earthBody = AstroEngine.getBodyState("Earth", jd)

        // Uranus geocentric vector (AU, ICRF) — from ephemeris heliocentric positions
        // Note: helioPos is in ecliptic; convert to equatorial
        val oblRad = Math.toRadians(23.4392911)
        fun eclToEq(v: Vector3): DoubleArray {
            return doubleArrayOf(
                v.x,
                v.y * cos(oblRad) - v.z * sin(oblRad),
                v.y * sin(oblRad) + v.z * cos(oblRad)
            )
        }
        val uranusHelioEq = eclToEq(uranusBody.helioPos)
        val earthHelioEq = eclToEq(earthBody.helioPos)
        val uranusGeoVec = doubleArrayOf(
            uranusHelioEq[0] - earthHelioEq[0],
            uranusHelioEq[1] - earthHelioEq[1],
            uranusHelioEq[2] - earthHelioEq[2]
        )
        val distGeoAu = sqrt(uranusGeoVec[0] * uranusGeoVec[0] + uranusGeoVec[1] * uranusGeoVec[1] + uranusGeoVec[2] * uranusGeoVec[2])

        // Light-time for Uranus
        val uranusLightTimeSec = distGeoAu * AU_KM / C_LIGHT_KM_PER_SEC

        // Moon geocentric vectors with light-time iteration
        val moonGeoVecs = mutableListOf<DoubleArray>()
        val moonBehindDisk = mutableListOf<Boolean>()

        val angRadiusRad = atan(URANUS_RADIUS_KM / (distGeoAu * AU_KM))
        val angRadiusArcsec = angRadiusRad * RAD_TO_ARCSEC

        for (elem in moonElements) {
            var lightTimeSec = uranusLightTimeSec
            var moonGeoVec = uranusGeoVec.copyOf()
            var moonRelKm = doubleArrayOf(0.0, 0.0, 0.0)

            for (iter in 0..11) {
                val jdEmit = jd - lightTimeSec / SECONDS_PER_DAY
                moonRelKm = moonRelativeVectorKm(jdEmit, elem)
                moonGeoVec = doubleArrayOf(
                    uranusGeoVec[0] + moonRelKm[0] / AU_KM,
                    uranusGeoVec[1] + moonRelKm[1] / AU_KM,
                    uranusGeoVec[2] + moonRelKm[2] / AU_KM
                )
                val newLightTimeSec = sqrt(moonGeoVec[0] * moonGeoVec[0] + moonGeoVec[1] * moonGeoVec[1] + moonGeoVec[2] * moonGeoVec[2]) * AU_KM / C_LIGHT_KM_PER_SEC
                if (abs(newLightTimeSec - lightTimeSec) < 1.0e-10) {
                    lightTimeSec = newLightTimeSec
                    break
                }
                lightTimeSec = newLightTimeSec
            }
            moonGeoVecs.add(moonGeoVec)

            // Check if moon is behind Uranus disk
            val losUnit = doubleArrayOf(uranusGeoVec[0] / distGeoAu, uranusGeoVec[1] / distGeoAu, uranusGeoVec[2] / distGeoAu)
            val relAu = doubleArrayOf(moonRelKm[0] / AU_KM, moonRelKm[1] / AU_KM, moonRelKm[2] / AU_KM)
            val depthAu = relAu[0] * losUnit[0] + relAu[1] * losUnit[1] + relAu[2] * losUnit[2]
            // Perpendicular offset
            val perpX = relAu[0] - depthAu * losUnit[0]
            val perpY = relAu[1] - depthAu * losUnit[1]
            val perpZ = relAu[2] - depthAu * losUnit[2]
            val perpDistKm = sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ) * AU_KM
            val behind = depthAu > 0 && perpDistKm < URANUS_RADIUS_KM
            moonBehindDisk.add(behind)
        }

        // Tangent plane projection to get arcsecond offsets
        val allVecs = listOf(uranusGeoVec) + moonGeoVecs
        val allOffsets = tangentPlaneOffsetsArcsec(allVecs, uranusGeoVec)
        val uranusOffset = allOffsets[0]

        val moons = moonElements.mapIndexed { i, elem ->
            val offset = allOffsets[i + 1]
            UranusApparentMoon(
                name = elem.name,
                eastArcsec = offset.first - uranusOffset.first,
                northArcsec = offset.second - uranusOffset.second,
                behindDisk = moonBehindDisk[i]
            )
        }

        // Ring tilt B: dot product of Earth direction (from Uranus) with pole
        val earthDirFromUranus = doubleArrayOf(-uranusGeoVec[0] / distGeoAu, -uranusGeoVec[1] / distGeoAu, -uranusGeoVec[2] / distGeoAu)
        val sinB = earthDirFromUranus[0] * umeZ[0] + earthDirFromUranus[1] * umeZ[1] + earthDirFromUranus[2] * umeZ[2]
        val ringTiltB = Math.toDegrees(asin(sinB.coerceIn(-1.0, 1.0)))

        // Position angle P of prograde pole
        val (appRa, appDec) = j2000ToApparent(uranusBody.ra, uranusBody.dec, jd)
        val alpha0 = Math.toRadians(POLE_RA_DEG)
        val delta0 = Math.toRadians(POLE_DEC_DEG)
        val alpha = Math.toRadians(appRa)
        val delta = Math.toRadians(appDec)
        val sinP = cos(delta0) * sin(alpha0 - alpha)
        val cosP = sin(delta0) * cos(delta) - cos(delta0) * sin(delta) * cos(alpha0 - alpha)
        val posAngleP = Math.toDegrees(atan2(sinP, cosP))

        return UranusSystemData(
            moons = moons,
            ringTiltB = ringTiltB,
            positionAngleP = posAngleP,
            distGeo = uranusBody.distGeo,
            distSun = uranusBody.distSun,
            angularRadiusArcsec = angRadiusArcsec
        )
    }
}
