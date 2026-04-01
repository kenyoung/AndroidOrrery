package com.kenyoung.orrery

import kotlin.math.*

// ===========================================================================
// Neptune system: Triton position from Emelyanov (2015) analytical theory.
// Ported from /home/rtm/neptune.py which was verified against JPL Horizons.
// Pole: RA=299.090°, Dec=42.930° (Emelyanov model).
// Includes 7 solar perturbation terms for inclination, argument of latitude,
// and node longitude.
// ===========================================================================

data class NeptuneApparentMoon(
    val name: String,
    val eastArcsec: Double,
    val northArcsec: Double,
    val behindDisk: Boolean
)

data class NeptuneSystemData(
    val moons: List<NeptuneApparentMoon>,
    val subEarthLat: Double,
    val positionAngleP: Double,
    val distGeo: Double,
    val distSun: Double,
    val angularRadiusArcsec: Double
)

object NeptuneMoonEngine {

    private const val NEPTUNE_RADIUS_KM = 24764.0
    private const val AU_KM = 149597870.7
    private const val C_LIGHT_KM_PER_SEC = 299792.458
    private const val RAD_TO_ARCSEC = 180.0 / PI * 3600.0

    // --- Emelyanov (2015) Triton theory constants ---

    // Pole of Triton's orbital/rotational frame (ICRF J2000)
    private const val ALPHA0_DEG = 299.090
    private const val DELTA0_DEG = 42.930

    // Triton orbital theory
    private const val TRITON_OMEGA0_DEG = 73.395781
    private const val TRITON_OMEGA_DOT_DEG_PER_DAY = 0.001452458
    private const val TRITON_T0_JD = 2378520.5  // ~1846 epoch

    // Perturbation coefficients: kI, kU, kOmega, k1, k2
    // argument = k1 * uPrime + k2 * (omegaPrime - omegaBar)
    private val pertTermsKI     = doubleArrayOf( 0.0,          0.00096486,  0.00664662,  0.00004687,  0.00095975, -0.00037627, -0.00000225)
    private val pertTermsKU     = doubleArrayOf(-0.00012327,  -0.00279453, -0.04335625, -0.00017215, -0.00233686,  0.00170605,  0.00000730)
    private val pertTermsKOmega = doubleArrayOf( 0.00063339,  -0.00178908, -0.01560110, -0.00009186, -0.00218071,  0.00096231,  0.00000536)
    private val pertTermsK1     = intArrayOf(2, 2, 0, -2, 2, 0, -2)
    private val pertTermsK2     = intArrayOf(0, 1, 1,  1, 2, 2,  2)

    // Frame axes: X = (-sinα₀, cosα₀, 0), Y = (-cosα₀ sinδ₀, -sinα₀ sinδ₀, cosδ₀), Z = pole
    private val frameX: DoubleArray
    private val frameY: DoubleArray
    private val frameZ: DoubleArray

    init {
        val aRad = Math.toRadians(ALPHA0_DEG)
        val dRad = Math.toRadians(DELTA0_DEG)
        frameX = doubleArrayOf(-sin(aRad), cos(aRad), 0.0)
        frameY = doubleArrayOf(-cos(aRad) * sin(dRad), -sin(aRad) * sin(dRad), cos(dRad))
        frameZ = doubleArrayOf(cos(aRad) * cos(dRad), sin(aRad) * cos(dRad), sin(dRad))
    }

    private fun wrap360(angle: Double): Double {
        var v = angle % 360.0
        if (v < 0.0) v += 360.0
        return v
    }

    // Compute solar perturbation corrections to i, u, and Omega
    private fun tritonPerturbations(jd: Double): Triple<Double, Double, Double> {
        val SOLAR_OMEGA_PRIME_DEG = 200.788181
        val SOLAR_U_PRIME0_DEG = 258.727508
        val SOLAR_U_PRIME_DOT_DEG_PER_DAY = 0.00598084154
        val SOLAR_T0_JD = 2451545.0  // J2000
        val uPrimeDeg = SOLAR_U_PRIME0_DEG + SOLAR_U_PRIME_DOT_DEG_PER_DAY * (jd - SOLAR_T0_JD)
        val omegaBarDeg = TRITON_OMEGA0_DEG + TRITON_OMEGA_DOT_DEG_PER_DAY * (jd - TRITON_T0_JD)

        var deltaI = 0.0
        var deltaU = 0.0
        var deltaOmega = 0.0

        for (j in pertTermsK1.indices) {
            val argDeg = pertTermsK1[j] * uPrimeDeg + pertTermsK2[j] * (SOLAR_OMEGA_PRIME_DEG - omegaBarDeg)
            val argRad = Math.toRadians(argDeg)
            deltaI += pertTermsKI[j] * cos(argRad)
            deltaU += pertTermsKU[j] * sin(argRad)
            deltaOmega += pertTermsKOmega[j] * sin(argRad)
        }

        return Triple(deltaI, deltaU, deltaOmega)
    }

    // Compute Triton's planetocentric vector in ICRF (km)
    private fun tritonPlanetocentricVectorKm(jd: Double): DoubleArray {
        val TRITON_A_KM = 354696.76
        val TRITON_I0_DEG = 157.268439
        val TRITON_U0_DEG = 31.791760
        val TRITON_U_DOT_DEG_PER_DAY = 61.25871809
        val (deltaIDeg, deltaUDeg, deltaOmegaDeg) = tritonPerturbations(jd)

        val dtDays = jd - TRITON_T0_JD

        val iDeg = TRITON_I0_DEG + deltaIDeg
        val uDeg = TRITON_U0_DEG + TRITON_U_DOT_DEG_PER_DAY * dtDays + deltaUDeg
        val omegaDeg = TRITON_OMEGA0_DEG + TRITON_OMEGA_DOT_DEG_PER_DAY * dtDays + deltaOmegaDeg

        val iRad = Math.toRadians(iDeg)
        val uRad = Math.toRadians(wrap360(uDeg))
        val omegaRad = Math.toRadians(wrap360(omegaDeg))

        val a = TRITON_A_KM
        val cosU = cos(uRad); val sinU = sin(uRad)
        val cosOm = cos(omegaRad); val sinOm = sin(omegaRad)
        val cosI = cos(iRad); val sinI = sin(iRad)

        // Position in the orbital reference frame
        val x = a * (cosU * cosOm - sinU * sinOm * cosI)
        val y = a * (cosU * sinOm + sinU * cosOm * cosI)
        val z = a * sinU * sinI

        // Rotate to ICRF using frame axes (equivalent to the Python's explicit matrix)
        return doubleArrayOf(
            frameX[0] * x + frameY[0] * y + frameZ[0] * z,
            frameX[1] * x + frameY[1] * y + frameZ[1] * z,
            frameX[2] * x + frameY[2] * y + frameZ[2] * z
        )
    }

    // Project geocentric vectors onto tangent plane
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
    fun getNeptuneSystemData(jd: Double): NeptuneSystemData {
        val neptuneBody = AstroEngine.getBodyState("Neptune", jd)
        val earthBody = AstroEngine.getBodyState("Earth", jd)

        val oblRad = Math.toRadians(23.4392911)
        fun eclToEq(v: Vector3): DoubleArray {
            return doubleArrayOf(
                v.x,
                v.y * cos(oblRad) - v.z * sin(oblRad),
                v.y * sin(oblRad) + v.z * cos(oblRad)
            )
        }
        val neptuneHelioEq = eclToEq(neptuneBody.helioPos)
        val earthHelioEq = eclToEq(earthBody.helioPos)
        val neptuneGeoVec = doubleArrayOf(
            neptuneHelioEq[0] - earthHelioEq[0],
            neptuneHelioEq[1] - earthHelioEq[1],
            neptuneHelioEq[2] - earthHelioEq[2]
        )
        val distGeoAu = sqrt(neptuneGeoVec[0] * neptuneGeoVec[0] + neptuneGeoVec[1] * neptuneGeoVec[1] + neptuneGeoVec[2] * neptuneGeoVec[2])

        val neptuneLightTimeSec = distGeoAu * AU_KM / C_LIGHT_KM_PER_SEC

        val angRadiusRad = atan(NEPTUNE_RADIUS_KM / (distGeoAu * AU_KM))
        val angRadiusArcsec = angRadiusRad * RAD_TO_ARCSEC

        // Triton light-time iteration (matches Python approach)
        var tritonLightTimeSec = neptuneLightTimeSec
        var tritonGeoVec = neptuneGeoVec.copyOf()
        var tritonRelKm = doubleArrayOf(0.0, 0.0, 0.0)

        for (iter in 0..11) {
            val jdEmit = jd - tritonLightTimeSec / SECONDS_PER_DAY
            tritonRelKm = tritonPlanetocentricVectorKm(jdEmit)
            tritonGeoVec = doubleArrayOf(
                neptuneGeoVec[0] + tritonRelKm[0] / AU_KM,
                neptuneGeoVec[1] + tritonRelKm[1] / AU_KM,
                neptuneGeoVec[2] + tritonRelKm[2] / AU_KM
            )
            val newLightTimeSec = sqrt(tritonGeoVec[0] * tritonGeoVec[0] + tritonGeoVec[1] * tritonGeoVec[1] + tritonGeoVec[2] * tritonGeoVec[2]) * AU_KM / C_LIGHT_KM_PER_SEC
            if (abs(newLightTimeSec - tritonLightTimeSec) < 1.0e-10) {
                tritonLightTimeSec = newLightTimeSec
                break
            }
            tritonLightTimeSec = newLightTimeSec
        }

        // Behind-disk check
        val losUnit = doubleArrayOf(neptuneGeoVec[0] / distGeoAu, neptuneGeoVec[1] / distGeoAu, neptuneGeoVec[2] / distGeoAu)
        val relAu = doubleArrayOf(tritonRelKm[0] / AU_KM, tritonRelKm[1] / AU_KM, tritonRelKm[2] / AU_KM)
        val depthAu = relAu[0] * losUnit[0] + relAu[1] * losUnit[1] + relAu[2] * losUnit[2]
        val perpX = relAu[0] - depthAu * losUnit[0]
        val perpY = relAu[1] - depthAu * losUnit[1]
        val perpZ = relAu[2] - depthAu * losUnit[2]
        val perpDistKm = sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ) * AU_KM
        val behindDisk = depthAu > 0 && perpDistKm < NEPTUNE_RADIUS_KM

        // Tangent plane projection
        val allVecs = listOf(neptuneGeoVec, tritonGeoVec)
        val allOffsets = tangentPlaneOffsetsArcsec(allVecs, neptuneGeoVec)
        val neptuneOffset = allOffsets[0]
        val tritonOffset = allOffsets[1]

        val moons = listOf(
            NeptuneApparentMoon(
                name = "Triton",
                eastArcsec = tritonOffset.first - neptuneOffset.first,
                northArcsec = tritonOffset.second - neptuneOffset.second,
                behindDisk = behindDisk
            )
        )

        // Sub-Earth latitude
        val earthDirFromNeptune = doubleArrayOf(-neptuneGeoVec[0] / distGeoAu, -neptuneGeoVec[1] / distGeoAu, -neptuneGeoVec[2] / distGeoAu)
        val sinB = earthDirFromNeptune[0] * frameZ[0] + earthDirFromNeptune[1] * frameZ[1] + earthDirFromNeptune[2] * frameZ[2]
        val subEarthLat = Math.toDegrees(asin(sinB.coerceIn(-1.0, 1.0)))

        // Position angle P of north pole
        val (appRa, appDec) = j2000ToApparent(neptuneBody.ra, neptuneBody.dec, jd)
        val alpha0 = Math.toRadians(ALPHA0_DEG)
        val delta0 = Math.toRadians(DELTA0_DEG)
        val alpha = Math.toRadians(appRa)
        val delta = Math.toRadians(appDec)
        val sinP = cos(delta0) * sin(alpha0 - alpha)
        val cosP = sin(delta0) * cos(delta) - cos(delta0) * sin(delta) * cos(alpha0 - alpha)
        val posAngleP = Math.toDegrees(atan2(sinP, cosP))

        return NeptuneSystemData(
            moons = moons,
            subEarthLat = subEarthLat,
            positionAngleP = posAngleP,
            distGeo = neptuneBody.distGeo,
            distSun = neptuneBody.distSun,
            angularRadiusArcsec = angRadiusArcsec
        )
    }
}
