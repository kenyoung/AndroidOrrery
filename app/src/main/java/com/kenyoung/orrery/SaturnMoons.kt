package com.kenyoung.orrery

import kotlin.math.*

// ===========================================================================
// Meeus "Astronomical Algorithms" Chapter 46 — Saturn satellite positions
// Translated from Python (originally from Go: github.com/soniakeys/meeus,
// Sonia Keys, MIT License)
// ===========================================================================

data class SaturnMoonPosition(
    val name: String,
    val x: Double,    // apparent X in Saturn radii (+ = west)
    val y: Double     // apparent Y in Saturn radii (+ = north)
)

data class SaturnSystemData(
    val moons: List<SaturnMoonPosition>,
    val ringTiltB: Double,         // degrees, + = north pole visible
    val ringTiltBSun: Double,      // degrees, ring tilt as seen from Sun (B')
    val positionAngleP: Double,    // degrees
    val angularRadiusRad: Double,  // angular radius of Saturn disk in radians
    val sunDir: Vector3,           // unit vector from Saturn toward Sun, in Saturn equatorial frame
    val earthDir: Vector3          // unit vector from Saturn toward Earth, in Saturn equatorial frame
)

object SaturnMoonEngine {

    private const val D = Math.PI / 180.0      // degrees to radians
    private const val B1950_JDE = 2433282.4235  // B1950.0 in JDE

    // Differential-refraction constants (index 0 unused, 1=Mimas..8=Iapetus)
    private val kConst = intArrayOf(0, 20947, 23715, 26382, 29876, 35313, 53800, 59222, 91820)

    // Ring dimensions in Saturn equatorial radii
    const val A_RING_INNER = 2.02
    const val A_RING_OUTER = 2.27
    const val B_RING_INNER = 1.525
    const val B_RING_OUTER = 1.95

    private const val SATURN_RADIUS_KM = 60268.0
    private const val AU_KM = 149597870.7

    // --- Helper functions ---

    private fun horner(t: Double, vararg c: Double): Double {
        var v = 0.0
        for (i in c.indices.reversed()) {
            v = v * t + c[i]
        }
        return v
    }

    private fun pmod(x: Double, y: Double): Double {
        return x - y * floor(x / y)
    }

    // --- Internal Sun position (simplified VSOP87) ---
    // Returns (sunLon_rad, 0.0, r_AU)
    private fun solarTrueVsop87(jde: Double): Triple<Double, Double, Double> {
        val t = (jde - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val l0 = pmod(280.46646 + 36000.76983 * t + 0.0003032 * t * t, 360.0)
        val m = pmod(357.52911 + 35999.05029 * t - 0.0001537 * t * t, 360.0) * D
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
                (0.019993 - 0.000101 * t) * sin(2 * m) +
                0.000289 * sin(3 * m)
        val sunLon = pmod(l0 + c, 360.0) * D
        val r = 1.000001018 * (1 - e * e) / (1 + e * cos(m + c * D))
        return Triple(sunLon, 0.0, r)
    }

    // --- Internal Saturn heliocentric position (Keplerian) ---
    // Returns (l_rad, latHel_rad, r_AU)
    private fun saturnPosition(jde: Double): Triple<Double, Double, Double> {
        val t = (jde - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val bigL = pmod(horner(t, 50.077444, 1223.5110686, 0.00051908, -3e-8), 360.0)
        val a = 9.554909192
        val e = horner(t, 0.05554814, -0.000346641, -6.436e-7, 3.4e-9)
        val i = horner(t, 2.488879, -0.0037362, -1.519e-5, 8.7e-8)
        val om = horner(t, 113.665503, 0.877088, -0.00012176, -2.249e-6)
        val wb = horner(t, 93.057237, 1.9637613, 0.00083753, 4.928e-6)
        val m = pmod(bigL - wb, 360.0) * D

        // Solve Kepler's equation
        var bigE = m
        for (iter in 0 until 15) {
            bigE += (m - bigE + e * sin(bigE)) / (1 - e * cos(bigE))
        }

        val nu = 2 * atan2(sqrt(1 + e) * sin(bigE / 2), sqrt(1 - e) * cos(bigE / 2))
        val r = a * (1 - e * cos(bigE))
        val lonHel = nu + wb * D
        val latHel = asin(sin(lonHel - om * D) * sin(i * D))
        val l = atan2(sin(lonHel - om * D) * cos(i * D), cos(lonHel - om * D)) + om * D
        return Triple(l, latHel, r)
    }

    // --- FK5 correction ---
    private fun toFk5(l: Double, b: Double, jde: Double): Pair<Double, Double> {
        val t = (jde - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val lp = l - 1.397 * D * t - 0.00031 * D * t * t
        val dl = (-0.09033 + 0.03916 * (cos(lp) + sin(lp)) * tan(b)) / 3600.0 * D
        val db = 0.03916 * (cos(lp) - sin(lp)) / 3600.0 * D
        return Pair(l + dl, b + db)
    }

    private fun lightTime(distAu: Double): Double = 0.0057755183 * distAu

    private fun jdeToJy(jde: Double): Double = 2000.0 + (jde - J2000_JD) / 365.25

    // --- Precess ecliptic coordinates between epochs (radians in/out) ---
    private fun precessEcliptic(lon: Double, lat: Double, jdeFrom: Double, jdeTo: Double): Pair<Double, Double> {
        val jyF = jdeToJy(jdeFrom)
        val jyT = jdeToJy(jdeTo)
        val bigT = (jyF - 2000.0) / 100.0
        val t = (jyT - jyF) / 100.0

        val eta = ((47.0029 - 0.06603 * bigT + 0.000598 * bigT * bigT) * t +
                (-0.03302 + 0.000598 * bigT) * t * t + 0.00006 * t * t * t) * D / 3600.0
        val piA = 174.876383889 * D + ((3289.4789 + 0.60622 * bigT) * t +
                (-869.8089 - 0.50491 * bigT) * t * t + (-0.01559) * t * t * t) * D / 3600.0
        val p = ((5029.0966 + 2.22226 * bigT - 0.000042 * bigT * bigT) * t +
                (1.11113 - 0.000042 * bigT) * t * t - 0.000006 * t * t * t) * D / 3600.0

        val se = sin(eta); val ce = cos(eta)
        val sl = sin(lat); val cl = cos(lat)
        val sd = sin(piA - lon); val cd = cos(piA - lon)
        val bigA = ce * cl * sd - se * sl
        val bigB = cl * cd
        val cv = ce * sl + se * cl * sd
        val lon2 = p + piA - atan2(bigA, bigB)
        val lat2 = asin(cv.coerceIn(-1.0, 1.0))
        return Pair(lon2, lat2)
    }

    // --- Precomputed time-dependent quantities (Meeus Table 46.a) ---
    private class Qs(jde: Double) {
        val t1 = jde - 2411093.0
        val t2 = t1 / 365.25
        val t3 = (jde - 2433282.423) / 365.25 + 1950.0
        val t4 = jde - 2411368.0
        val t5 = t4 / 365.25
        val t6 = jde - 2415020.0
        val t7 = t6 / 36525.0
        val t8 = t6 / 365.25
        val t9 = (jde - 2442000.5) / 365.25
        val t10 = jde - 2409786.0
        val t11 = t10 / 36525.0

        val w0 = 5.095 * D * (t3 - 1866.39)
        val w1 = 74.4 * D + 32.39 * D * t2
        val w2 = 134.3 * D + 92.62 * D * t2
        val w3 = 42.0 * D - 0.5118 * D * t5
        val w4 = 276.59 * D + 0.5118 * D * t5
        val w5 = 267.2635 * D + 1222.1136 * D * t7
        val w6 = 175.4762 * D + 1221.5515 * D * t7
        val w7 = 2.4891 * D + 0.002435 * D * t7
        val w8 = 113.35 * D - 0.2597 * D * t7

        val s1 = sin(28.0817 * D); val c1 = cos(28.0817 * D)
        val s2 = sin(168.8112 * D); val c2 = cos(168.8112 * D)
        val e1 = 0.05589 - 0.000346 * t7

        val sW0 = sin(w0)
        val s3W0 = sin(3 * w0)
        val s5W0 = sin(5 * w0)
        val sW1 = sin(w1)
        val sW2 = sin(w2)
        val sW3 = sin(w3); val cW3 = cos(w3)
        val sW4 = sin(w4); val cW4 = cos(w4)
        val sW7 = sin(w7); val cW7 = cos(w7)

        // Returns (lambda, r, gamma, omega) — all in radians except r in Saturn radii
        fun mimas(): DoubleArray {
            val bigL = 127.64 * D + 381.994497 * D * t1 -
                    43.57 * D * sW0 - 0.72 * D * s3W0 - 0.02144 * D * s5W0
            val p = 106.1 * D + 365.549 * D * t2
            val m = bigL - p
            val c = 2.18287 * D * sin(m) + 0.025988 * D * sin(2 * m) + 0.00043 * D * sin(3 * m)
            return doubleArrayOf(
                bigL + c,
                3.06879 / (1 + 0.01905 * cos(m + c)),
                1.563 * D,
                54.5 * D - 365.072 * D * t2
            )
        }

        fun enceladus(): DoubleArray {
            val bigL = 200.317 * D + 262.7319002 * D * t1 + 0.25667 * D * sW1 + 0.20883 * D * sW2
            val p = 309.107 * D + 123.44121 * D * t2
            val m = bigL - p
            val c = 0.55577 * D * sin(m) + 0.00168 * D * sin(2 * m)
            return doubleArrayOf(
                bigL + c,
                3.94118 / (1 + 0.00485 * cos(m + c)),
                0.0262 * D,
                348.0 * D - 151.95 * D * t2
            )
        }

        fun tethys(): DoubleArray {
            val lam = 285.306 * D + 190.69791226 * D * t1 +
                    2.063 * D * sW0 + 0.03409 * D * s3W0 + 0.001015 * D * s5W0
            return doubleArrayOf(lam, 4.880998, 1.0976 * D, 111.33 * D - 72.2441 * D * t2)
        }

        fun dione(): DoubleArray {
            val bigL = 254.712 * D + 131.53493193 * D * t1 - 0.0215 * D * sW1 - 0.01733 * D * sW2
            val p = 174.8 * D + 30.82 * D * t2
            val m = bigL - p
            val c = 0.24717 * D * sin(m) + 0.00033 * D * sin(2 * m)
            return doubleArrayOf(
                bigL + c,
                6.24871 / (1 + 0.002157 * cos(m + c)),
                0.0139 * D,
                232.0 * D - 30.27 * D * t2
            )
        }

        fun rhea(): DoubleArray {
            val ppPrime = 342.7 * D + 10.057 * D * t2
            val spp = sin(ppPrime); val cpp = cos(ppPrime)
            val a1 = 0.000265 * spp + 0.001 * sW4
            val a2 = 0.000265 * cpp + 0.001 * cW4
            val e = hypot(a1, a2)
            val p = atan2(a1, a2)
            val bigN = 345.0 * D - 10.057 * D * t2
            val sN = sin(bigN); val cN = cos(bigN)
            val lp = 359.244 * D + 79.6900472 * D * t1 + 0.086754 * D * sN
            val i = 28.0362 * D + 0.346898 * D * cN + 0.0193 * D * cW3
            val om = 168.8034 * D + 0.736936 * D * sN + 0.041 * D * sW3
            return subr(lp, p, e, 8.725924, om, i)
        }

        fun titan(): DoubleArray {
            val bigL = 261.1582 * D + 22.57697855 * D * t4 + 0.074025 * D * sW3
            val ip = 27.45141 * D + 0.295999 * D * cW3
            val op = 168.66925 * D + 0.628808 * D * sW3
            val sip = sin(ip); val cip = cos(ip)
            val sOpW8 = sin(op - w8); val cOpW8 = cos(op - w8)
            val a1 = sW7 * sOpW8
            val a2 = cW7 * sip - sW7 * cip * cOpW8
            val g0 = 102.8623 * D
            val psi = atan2(a1, a2)
            val s = hypot(a1, a2)
            var g = w4 - op - psi
            val s2g0 = sin(2 * g0); val c2g0 = cos(2 * g0)
            var varpi = 0.0
            for (iter in 0 until 3) {
                varpi = w4 + 0.37515 * D * (sin(2 * g) - s2g0)
                g = varpi - op - psi
            }
            val ep = 0.029092 + 0.00019048 * (cos(2 * g) - c2g0)
            val qq = 2 * (w5 - varpi)
            val b1 = sip * sOpW8
            val b2 = cW7 * sip * cOpW8 - sW7 * cip
            val theta = atan2(b1, b2) + w8
            val sq = sin(qq); val cq = cos(qq)
            val e = ep + 0.002778797 * ep * cq
            val p = varpi + 0.159215 * D * sq
            val u = 2 * w5 - 2 * theta + psi
            val su = sin(u); val cu = cos(u)
            val h = 0.9375 * ep * ep * sq + 0.1875 * s * s * sin(2 * (w5 - theta))
            val lp = bigL - 0.254744 * D * (e1 * sin(w6) +
                    0.75 * e1 * e1 * sin(2 * w6) + h)
            val i = ip + 0.031843 * D * s * cu
            val om = op + 0.031843 * D * s * su / sip
            return subr(lp, p, e, 20.216193, om, i)
        }

        fun hyperion(): DoubleArray {
            val eta = 92.39 * D + 0.5621071 * D * t6
            val zeta = 148.19 * D - 19.18 * D * t8
            val theta = 184.8 * D - 35.41 * D * t9
            val thetaP = theta - 7.5 * D
            val asSat = 176.0 * D + 12.22 * D * t8
            val bs = 8.0 * D + 24.44 * D * t8
            val csSat = bs + 5.0 * D
            val varpi = 69.898 * D - 18.67088 * D * t8
            val phi = 2 * (varpi - w5)
            val chi = 94.9 * D - 2.292 * D * t8

            val se = sin(eta); val ce = cos(eta)
            val sz = sin(zeta); val cz = cos(zeta)
            val s2z = sin(2 * zeta); val c2z = cos(2 * zeta)
            val s3z = sin(3 * zeta); val c3z = cos(3 * zeta)
            val szpe = sin(zeta + eta); val czpe = cos(zeta + eta)
            val szme = sin(zeta - eta); val czme = cos(zeta - eta)
            val sp = sin(phi); val cp = cos(phi)
            val sx = sin(chi); val cx = cos(chi)
            val scs = sin(csSat); val ccs = cos(csSat)

            val a = 24.50601 - 0.08686 * ce - 0.00166 * czpe + 0.00175 * czme
            val e = 0.103458 - 0.004099 * ce - 0.000167 * czpe + 0.000235 * czme +
                    0.02303 * cz - 0.00212 * c2z + 0.000151 * c3z + 0.00013 * cp
            val p = varpi + 0.15648 * D * sx - 0.4457 * D * se - 0.2657 * D * szpe -
                    0.3573 * D * szme - 12.872 * D * sz + 1.668 * D * s2z -
                    0.2419 * D * s3z - 0.07 * D * sp
            val lp = 177.047 * D + 16.91993829 * D * t6 + 0.15648 * D * sx +
                    9.142 * D * se + 0.007 * D * sin(2 * eta) - 0.014 * D * sin(3 * eta) +
                    0.2275 * D * szpe + 0.2112 * D * szme - 0.26 * D * sz - 0.0098 * D * s2z -
                    0.013 * D * sin(asSat) + 0.017 * D * sin(bs) - 0.0303 * D * sp
            val i = 27.3347 * D + 0.6434886 * D * cx + 0.315 * D * cW3 +
                    0.018 * D * cos(theta) - 0.018 * D * ccs
            val om = 168.6812 * D + 1.40136 * D * cx + 0.68599 * D * sW3 -
                    0.0392 * D * scs + 0.0366 * D * sin(thetaP)
            return subr(lp, p, e, a, om, i)
        }

        fun iapetus(): DoubleArray {
            val bigL = 261.1582 * D + 22.57697855 * D * t4
            val vpP = 91.796 * D + 0.562 * D * t7
            val psi = 4.367 * D - 0.195 * D * t7
            val theta = 146.819 * D - 3.198 * D * t7
            val phi = 60.47 * D + 1.521 * D * t7
            val bigPhi = 205.055 * D - 2.091 * D * t7
            val ep = 0.028298 + 0.001156 * t11
            val vp0 = 352.91 * D + 11.71 * D * t11
            val mu = 76.3852 * D + 4.53795125 * D * t10
            val ip = horner(t11, 18.4602 * D, -0.9518 * D, -0.072 * D, 0.0054 * D)
            val op = horner(t11, 143.198 * D, -3.919 * D, 0.116 * D, 0.008 * D)

            val l = mu - vp0
            val g = vp0 - op - psi
            val g1 = vp0 - op - phi
            val ls = w5 - vpP
            val gs = vpP - theta
            val lT = bigL - w4
            val gT = w4 - bigPhi

            val u1 = 2 * (l + g - ls - gs)
            val u2 = l + g1 - lT - gT
            val u3 = l + 2 * (g - ls - gs)
            val u4 = lT + gT - g1
            val u5 = 2 * (ls + gs)

            val sl = sin(l); val cl = cos(l)
            val su1 = sin(u1); val cu1 = cos(u1)
            val su2 = sin(u2); val cu2 = cos(u2)
            val su3 = sin(u3); val cu3 = cos(u3)
            val su4 = sin(u4); val cu4 = cos(u4)
            val slu2 = sin(l + u2); val clu2 = cos(l + u2)
            val sg1gT = sin(g1 - gT); val cg1gT = cos(g1 - gT)
            val su52g = sin(u5 - 2 * g); val cu52g = cos(u5 - 2 * g)
            val su5psi = sin(u5 + psi); val cu5psi = cos(u5 + psi)
            val su2phi = sin(u2 + phi); val cu2phi = cos(u2 + phi)
            val s5 = sin(l + g1 + lT + gT + phi)
            val c5 = cos(l + g1 + lT + gT + phi)

            val a = 58.935028 + 0.004638 * cu1 + 0.058222 * cu2
            val e = ep - 0.0014097 * cg1gT + 0.0003733 * cu52g + 0.000118 * cu3 +
                    0.0002408 * cl + 0.0002849 * clu2 + 0.000619 * cu4
            val w = 0.08077 * D * sg1gT + 0.02139 * D * su52g - 0.00676 * D * su3 +
                    0.0138 * D * sl + 0.01632 * D * slu2 + 0.03547 * D * su4
            val p = vp0 + w / ep
            val lp = mu - 0.04299 * D * su2 - 0.00789 * D * su1 - 0.06312 * D * sin(ls) -
                    0.00295 * D * sin(2 * ls) - 0.02231 * D * sin(u5) +
                    0.0065 * D * su5psi
            val i = ip + 0.04204 * D * cu5psi + 0.00235 * D * c5 + 0.0036 * D * cu2phi
            val wp = 0.04204 * D * su5psi + 0.00235 * D * s5 + 0.00358 * D * su2phi
            val om = op + wp / sin(ip)
            return subr(lp, p, e, a, om, i)
        }

        // Shared orbit equation solver (Meeus eq. 46.x)
        private fun subr(lamP: Double, p: Double, e: Double, a: Double, omega: Double, i: Double): DoubleArray {
            val m = lamP - p
            val e2 = e * e; val e3 = e2 * e; val e4 = e2 * e2; val e5 = e3 * e2
            val c = (2 * e - 0.25 * e3 + 0.0520833333 * e5) * sin(m) +
                    (1.25 * e2 - 0.458333333 * e4) * sin(2 * m) +
                    (1.083333333 * e3 - 0.671875 * e5) * sin(3 * m) +
                    1.072917 * e4 * sin(4 * m) + 1.142708 * e5 * sin(5 * m)
            val r = a * (1 - e2) / (1 + e * cos(m + c))
            val g = omega - 168.8112 * D
            val si = sin(i); val ci = cos(i)
            val sg = sin(g); val cg = cos(g)
            val a1 = si * sg; val a2 = c1 * si * cg - s1 * ci
            val gamma = asin(hypot(a1, a2))
            val u = atan2(a1, a2)
            val omegaOut = 168.8112 * D + u
            val h = c1 * si - s1 * ci * cg
            val psiA = atan2(s1 * sg, h)
            val lamOut = lamP + c + u - g - psiA
            return doubleArrayOf(lamOut, r, gamma, omegaOut)
        }
    }

    // --- Main satellite position calculation (Meeus Ch. 46) ---
    // Returns list of 8 (x, y) tuples in Saturn radii. X+ = west, Y+ = north.
    private fun saturnMoonPositions(jde: Double): List<Pair<Double, Double>> {
        val (s, beta, bigR) = solarTrueVsop87(jde)
        val ss = sin(s); val cs = cos(s)
        val sb = sin(beta)

        var delta = 9.0
        var jdeLt = jde
        var x = 0.0; var y = 0.0; var z = 0.0
        for (iter in 0 until 2) {
            val tau = lightTime(delta)
            jdeLt = jde - tau
            val (lRaw, bRaw, r) = saturnPosition(jdeLt)
            val (l, b) = toFk5(lRaw, bRaw, jdeLt)
            val sl = sin(l); val cl = cos(l)
            val sbb = sin(b); val cb = cos(b)
            x = r * cb * cl + bigR * cs
            y = r * cb * sl + bigR * ss
            z = r * sbb + bigR * sb
            delta = sqrt(x * x + y * y + z * z)
        }

        var lam0 = atan2(y, x)
        var bet0 = atan(z / hypot(x, y))
        val (lam0p, bet0p) = precessEcliptic(lam0, bet0, jde, B1950_JDE)
        lam0 = lam0p
        bet0 = bet0p

        val q = Qs(jdeLt)

        // Compute all 8 moons (index 1..8)
        val s4 = arrayOfNulls<DoubleArray>(9)
        s4[1] = q.mimas()
        s4[2] = q.enceladus()
        s4[3] = q.tethys()
        s4[4] = q.dione()
        s4[5] = q.rhea()
        s4[6] = q.titan()
        s4[7] = q.hyperion()
        s4[8] = q.iapetus()

        val bigX = DoubleArray(9)
        val bigY = DoubleArray(9)
        val bigZ = DoubleArray(9)
        for (j in 1..8) {
            val data = s4[j]!!
            val lamJ = data[0]; val rJ = data[1]; val gamJ = data[2]; val omgJ = data[3]
            val u = lamJ - omgJ
            val w = omgJ - 168.8112 * D
            val su = sin(u); val cu = cos(u)
            val sw = sin(w); val cw = cos(w)
            val sg = sin(gamJ); val cg = cos(gamJ)
            bigX[j] = rJ * (cu * cw - su * cg * sw)
            bigY[j] = rJ * (su * cw * cg + cu * sw)
            bigZ[j] = rJ * su * sg
        }

        bigZ[0] = 1.0
        val sl0 = sin(lam0); val cl0 = cos(lam0)
        val sb0 = sin(bet0); val cb0 = cos(bet0)

        val bigA = DoubleArray(9)
        val bigBv = DoubleArray(9)
        val bigCv = DoubleArray(9)
        for (j in 0..8) {
            var a = bigX[j]
            var b = q.c1 * bigY[j] - q.s1 * bigZ[j]
            val c = q.s1 * bigY[j] + q.c1 * bigZ[j]
            val aNew = q.c2 * a - q.s2 * b
            val bNew = q.s2 * a + q.c2 * b
            a = aNew; b = bNew
            bigA[j] = a * sl0 - b * cl0
            b = a * cl0 + b * sl0
            bigBv[j] = b * cb0 + c * sb0
            bigCv[j] = c * cb0 - b * sb0
        }

        val dAng = atan2(bigA[0], bigCv[0])
        val sD = sin(dAng); val cD = cos(dAng)

        val pos = mutableListOf<Pair<Double, Double>>()
        for (j in 1..8) {
            val rJ = s4[j]!![1]
            var xj = bigA[j] * cD - bigCv[j] * sD
            val yj = bigA[j] * sD + bigCv[j] * cD
            val zj = bigBv[j]
            val dd = (xj / rJ).coerceIn(-1.0, 1.0)
            xj += abs(zj) / kConst[j] * sqrt(1 - dd * dd)
            val bigW = delta / (delta + zj / 2475.0)
            pos.add(Pair(xj * bigW, yj * bigW))
        }

        return pos
    }

    // --- Ring tilt B (Meeus Ch. 45) ---
    // eclipticLon, eclipticLat in degrees; returns B in degrees
    fun calculateRingTiltB(eclipticLon: Double, eclipticLat: Double, jd: Double): Double {
        val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val iRing = Math.toRadians(28.075216 - 0.012998 * t + 0.000004 * t * t)
        val omegaRing = Math.toRadians(169.508470 + 1.394681 * t + 0.000412 * t * t)
        val lambda = Math.toRadians(eclipticLon)
        val beta = Math.toRadians(eclipticLat)
        val sinB = sin(iRing) * cos(beta) * sin(lambda - omegaRing) - cos(iRing) * sin(beta)
        return Math.toDegrees(asin(sinB.coerceIn(-1.0, 1.0)))
    }

    // --- Position angle P ---
    // apparentRaDeg, apparentDecDeg in degrees; returns P in degrees
    fun calculatePositionAngleP(apparentRaDeg: Double, apparentDecDeg: Double, jd: Double): Double {
        val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val alpha0 = Math.toRadians(40.66 - 0.036 * t)
        val delta0 = Math.toRadians(83.52 - 0.004 * t)
        val alpha = Math.toRadians(apparentRaDeg)
        val delta = Math.toRadians(apparentDecDeg)
        val sinP = cos(delta0) * sin(alpha0 - alpha)
        val cosP = sin(delta0) * cos(delta) - cos(delta0) * sin(delta) * cos(alpha0 - alpha)
        return Math.toDegrees(atan2(sinP, cosP))
    }

    // --- Angular disk radius ---
    // Returns angular radius in radians
    fun calculateAngularRadius(distGeoAU: Double): Double {
        return atan(SATURN_RADIUS_KM / (distGeoAU * AU_KM))
    }

    // Convert ecliptic direction (lon, lat in radians) to Saturn equatorial frame.
    // Saturn equatorial frame: X,Z in ring plane, Y = Saturn's north pole.
    // iRing, omegaRing in radians.
    private fun eclipticToSaturnFrame(
        eclLonRad: Double, eclLatRad: Double,
        iRing: Double, omegaRing: Double
    ): Vector3 {
        // Ecliptic unit vector
        val cb = cos(eclLatRad); val sb = sin(eclLatRad)
        val cl = cos(eclLonRad); val sl = sin(eclLonRad)
        val vx = cb * cl; val vy = cb * sl; val vz = sb

        // Basis vectors of Saturn equatorial frame expressed in ecliptic coords:
        // e_X = (cosΩ, sinΩ, 0)
        // e_Y = (sini·sinΩ, -sini·cosΩ, cosi)   — Saturn's north pole
        // e_Z = (-cosi·sinΩ, cosi·cosΩ, sini)    — note sign for right-handed frame
        val si = sin(iRing); val ci = cos(iRing)
        val so = sin(omegaRing); val co = cos(omegaRing)

        val sx = vx * co + vy * so                        // dot(v, e_X)
        val sy = vx * si * so - vy * si * co + vz * ci    // dot(v, e_Y)
        val sz = -vx * ci * so + vy * ci * co + vz * si   // dot(v, e_Z)
        return Vector3(sx, sy, sz)
    }

    // --- Public entry point: get complete Saturn system data ---
    fun getSaturnSystemData(jd: Double): SaturnSystemData {
        val satBody = AstroEngine.getBodyState("Saturn", jd)

        val ringTiltB = calculateRingTiltB(satBody.eclipticLon, satBody.eclipticLat, jd)

        val (appRa, appDec) = j2000ToApparent(satBody.ra, satBody.dec, jd)
        val posAngleP = calculatePositionAngleP(appRa, appDec, jd)

        val angularRadius = calculateAngularRadius(satBody.distGeo)

        // Ring orientation at current epoch (Meeus Ch. 45)
        val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        val iRing = Math.toRadians(28.075216 - 0.012998 * t + 0.000004 * t * t)
        val omegaRing = Math.toRadians(169.508470 + 1.394681 * t + 0.000412 * t * t)

        // Heliocentric ecliptic direction of Saturn (for Sun-related geometry)
        val helioDistXY = sqrt(satBody.helioPos.x * satBody.helioPos.x +
                satBody.helioPos.y * satBody.helioPos.y)
        val helioEclLonRad = atan2(satBody.helioPos.y, satBody.helioPos.x)
        val helioEclLatRad = atan2(satBody.helioPos.z, helioDistXY)

        // Sun direction from Saturn = opposite of Saturn's heliocentric position
        val sunEclLon = helioEclLonRad + PI
        val sunEclLat = -helioEclLatRad
        val sunDir = eclipticToSaturnFrame(sunEclLon, sunEclLat, iRing, omegaRing)
        val sunLen = sqrt(sunDir.x * sunDir.x + sunDir.y * sunDir.y + sunDir.z * sunDir.z)
        val sunDirNorm = Vector3(sunDir.x / sunLen, sunDir.y / sunLen, sunDir.z / sunLen)

        // Ring tilt as seen from Sun (B')
        val ringTiltBSun = calculateRingTiltB(
            normalizeDegrees(Math.toDegrees(helioEclLonRad)),
            Math.toDegrees(helioEclLatRad), jd
        )

        // Geocentric ecliptic direction of Earth as seen from Saturn
        // Saturn's geocentric ecliptic coords point from Earth to Saturn;
        // reverse that direction for Earth as seen from Saturn
        val earthBody = AstroEngine.getBodyState("Earth", jd)
        // Earth's ecliptic lon/lat as seen from Saturn: use Saturn→Earth vector
        val dx = earthBody.helioPos.x - satBody.helioPos.x
        val dy = earthBody.helioPos.y - satBody.helioPos.y
        val dz = earthBody.helioPos.z - satBody.helioPos.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val earthEclLat = asin((dz / dist).coerceIn(-1.0, 1.0))
        val earthEclLon = atan2(dy, dx)
        val earthDir = eclipticToSaturnFrame(earthEclLon, earthEclLat, iRing, omegaRing)
        val earthLen = sqrt(earthDir.x * earthDir.x + earthDir.y * earthDir.y + earthDir.z * earthDir.z)
        val earthDirNorm = Vector3(earthDir.x / earthLen, earthDir.y / earthLen, earthDir.z / earthLen)

        // Moon positions from Meeus Ch. 46 (all 8 moons, 0-indexed)
        val allMoons = saturnMoonPositions(jd)

        // Extract the 5 wanted moons (indices 1..5 = Enceladus, Tethys, Dione, Rhea, Titan)
        val wantedMoons = listOf(
            1 to "Enceladus",
            2 to "Tethys",
            3 to "Dione",
            4 to "Rhea",
            5 to "Titan"
        )
        val moons = wantedMoons.map { (idx, name) ->
            val (mx, my) = allMoons[idx]
            SaturnMoonPosition(name, mx, my)
        }

        return SaturnSystemData(
            moons = moons,
            ringTiltB = ringTiltB,
            ringTiltBSun = ringTiltBSun,
            positionAngleP = posAngleP,
            angularRadiusRad = angularRadius,
            sunDir = sunDirNorm,
            earthDir = earthDirNorm
        )
    }
}
