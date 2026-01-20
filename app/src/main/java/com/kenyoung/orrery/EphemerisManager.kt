package com.kenyoung.orrery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object EphemerisManager {

    // Map Key: BodyName -> (JD Array, Data Array (interleaved))
    private val dataMap = mutableMapOf<String, Pair<DoubleArray, DoubleArray>>()
    private val strideMap = mutableMapOf<String, Int>()

    var isLoaded = false
        private set

    // Data Stride per body (RA, Dec, GeoDist, HelioDist, HelioLon, HelioLat)
    private const val STRIDE = 6

    // Indices for internal array storage
    const val IDX_RA = 0
    const val IDX_DEC = 1
    const val IDX_GEO_DIST = 2
    const val IDX_HELIO_DIST = 3
    const val IDX_HELIO_LON = 4
    const val IDX_HELIO_LAT = 5

    suspend fun loadEphemeris(context: Context) {
        if (isLoaded) return
        withContext(Dispatchers.IO) {
            try {
                loadPlanetFile(context)
                loadMoonFile(context)
                isLoaded = true
            } catch (e: Exception) {
                e.printStackTrace()
                isLoaded = false
            }
        }
    }

    private fun loadPlanetFile(context: Context) {
        // Bodies in strict column order matches the Python generation logic
        val bodies = listOf("Sun", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Halley")

        // 1. Open Stream & Read All Bytes
        val inputStream = context.assets.open("ephemeris_planets.bin")
        val bytes = inputStream.readBytes()
        inputStream.close()

        // 2. Setup Buffer (Little Endian to match Python script)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()

        // 3. Calculate Dimensions
        // Row = 1 JD + (9 bodies * 6 doubles) = 55 doubles
        val doublesPerRow = 1 + (bodies.size * STRIDE)
        val numRows = buffer.capacity() / doublesPerRow

        // 4. Pre-allocate Arrays
        val jdArray = DoubleArray(numRows)
        val bodyArrays = Array(bodies.size) { DoubleArray(numRows * STRIDE) }

        // 5. Bulk Parse
        for (i in 0 until numRows) {
            // First double in row is JD
            jdArray[i] = buffer.get()

            // Following doubles are body data
            for (b in bodies.indices) {
                // Read 6 doubles for this body
                val baseIdx = i * STRIDE
                bodyArrays[b][baseIdx + 0] = buffer.get() // RA
                bodyArrays[b][baseIdx + 1] = buffer.get() // Dec
                bodyArrays[b][baseIdx + 2] = buffer.get() // GeoDist
                bodyArrays[b][baseIdx + 3] = buffer.get() // HelioDist
                bodyArrays[b][baseIdx + 4] = buffer.get() // HelioLon
                bodyArrays[b][baseIdx + 5] = buffer.get() // HelioLat
            }
        }

        // 6. Store in Map
        for (i in bodies.indices) {
            dataMap[bodies[i]] = Pair(jdArray, bodyArrays[i])
            strideMap[bodies[i]] = STRIDE
        }
    }

    private fun loadMoonFile(context: Context) {
        val inputStream = context.assets.open("ephemeris_moon.bin")
        val bytes = inputStream.readBytes()
        inputStream.close()

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()

        // Row = 1 JD + 6 data doubles = 7 doubles
        val doublesPerRow = 1 + STRIDE
        val numRows = buffer.capacity() / doublesPerRow

        val jdArray = DoubleArray(numRows)
        val dataArray = DoubleArray(numRows * STRIDE)

        for (i in 0 until numRows) {
            jdArray[i] = buffer.get()
            // Read 6 doubles
            val baseIdx = i * STRIDE
            for (k in 0 until STRIDE) {
                dataArray[baseIdx + k] = buffer.get()
            }
        }

        dataMap["Moon"] = Pair(jdArray, dataArray)
        strideMap["Moon"] = STRIDE
    }

    // Cubic Hermite Spline Interpolation (Catmull-Rom)
    fun getInterpolatedData(body: String, targetJD: Double): DoubleArray? {
        if (!isLoaded) return null
        val (times, values) = dataMap[body] ?: return null
        val stride = strideMap[body] ?: return null

        // Binary Search
        var low = 0
        var high = times.size - 1

        if (targetJD < times[0] || targetJD > times[high]) return null

        while (low <= high) {
            val mid = (low + high) / 2
            if (times[mid] < targetJD) low = mid + 1 else high = mid - 1
        }

        // i1 is the index <= targetJD
        val i1 = high
        val i2 = i1 + 1

        if (i1 < 0 || i2 >= times.size) return null

        // Indices for Cubic Spline (p0, p1, p2, p3)
        val i0 = if (i1 > 0) i1 - 1 else i1
        val i3 = if (i2 < times.size - 1) i2 + 1 else i2

        val t1 = times[i1]
        val t2 = times[i2]

        val t = (targetJD - t1) / (t2 - t1)
        val tSq = t * t
        val tCub = tSq * t

        val result = DoubleArray(stride)
        for (k in 0 until stride) {
            val p0 = values[(i0 * stride) + k]
            val p1 = values[(i1 * stride) + k]
            val p2 = values[(i2 * stride) + k]
            val p3 = values[(i3 * stride) + k]

            if (k == IDX_RA || k == IDX_HELIO_LON) {
                val up0 = unwrap(p0, p1)
                val up1 = p1
                val up2 = unwrap(p2, p1)
                val up3 = unwrap(p3, p1)
                val up3_better = unwrap(p3, up2)

                result[k] = normalizeDegrees(cubicInterp(up0, up1, up2, up3_better, t, tSq, tCub))
            } else {
                result[k] = cubicInterp(p0, p1, p2, p3, t, tSq, tCub)
            }
        }
        return result
    }

    private fun cubicInterp(p0: Double, p1: Double, p2: Double, p3: Double, t: Double, tSq: Double, tCub: Double): Double {
        val m0 = p0; val m1 = p1; val m2 = p2; val m3 = p3

        val c0 = 2.0 * m1
        val c1 = -m0 + m2
        val c2 = 2.0 * m0 - 5.0 * m1 + 4.0 * m2 - m3
        val c3 = -m0 + 3.0 * m1 - 3.0 * m2 + m3

        return 0.5 * (c0 + c1 * t + c2 * tSq + c3 * tCub)
    }

    private fun unwrap(angle: Double, reference: Double): Double {
        var d = angle - reference
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return reference + d
    }

    private fun normalizeDegrees(deg: Double): Double {
        var v = deg % 360.0
        if (v < 0) v += 360.0
        return v
    }
}