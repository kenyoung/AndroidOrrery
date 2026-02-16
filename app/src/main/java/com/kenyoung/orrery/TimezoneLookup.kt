package com.kenyoung.orrery

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.*

// ============================================================
// Public API
// ============================================================

@Volatile
private var tzLoaded = false
private var tzBaseRes = 0
private var tzUsedMaxRes = 0
private var tzZones = emptyArray<String>()
private var tzCells = LongArray(0)
private var tzZoneIds = IntArray(0)

/**
 * Load the timezone index from the compressed asset file.
 * Returns null on success, or an error message on failure.
 * Safe to call multiple times; subsequent calls return immediately.
 * Handles both gzipped and raw data (in case AAPT2 decompressed the .gz).
 */
fun loadTimezoneIndex(context: Context): String? {
    if (tzLoaded) return null
    return try {
        val raw = context.assets.open("tz_h3_index.bin").use { it.readBytes() }
        val buf = decompressIfGzip(raw)
        if (parseTimezoneData(buf)) null
        else "parse failed: ${raw.size} raw bytes, ${buf.size} after decompress, magic=${
            if (buf.size >= 4) buf.take(4).map { "%02X".format(it) }.joinToString("") else "short"
        }"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }
}

/**
 * Load the timezone index from a gzip file path (for JVM testing without Android Context).
 */
fun loadTimezoneIndexFromFile(path: String): Boolean {
    if (tzLoaded) return true
    return try {
        val raw = File(path).readBytes()
        val buf = decompressIfGzip(raw)
        parseTimezoneData(buf)
    } catch (_: Exception) {
        false
    }
}

/** If bytes start with gzip magic (0x1f 0x8b), decompress; otherwise return as-is. */
private fun decompressIfGzip(raw: ByteArray): ByteArray {
    if (raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B) {
        return BufferedInputStream(GZIPInputStream(ByteArrayInputStream(raw))).use { it.readBytes() }
    }
    return raw
}

private fun parseTimezoneData(buf: ByteArray): Boolean {
    if (buf.size < 7) return false
    if (buf[0].toInt().and(0xFF).toChar() != 'T' ||
        buf[1].toInt().and(0xFF).toChar() != 'Z' ||
        buf[2].toInt().and(0xFF).toChar() != 'H' ||
        buf[3].toInt().and(0xFF).toChar() != '3') return false
    val version = buf[4].toInt() and 0xFF
    if (version != 1) return false
    tzBaseRes = buf[5].toInt() and 0xFF
    tzUsedMaxRes = buf[6].toInt() and 0xFF

    var idx = 7
    var (numZones, nextIdx) = decodeVarint(buf, idx); idx = nextIdx
    val (numEntries, nextIdx2) = decodeVarint(buf, idx); idx = nextIdx2

    val zones = Array(numZones.toInt()) { "" }
    for (i in 0 until numZones.toInt()) {
        val (n, ni) = decodeVarint(buf, idx); idx = ni
        val len = n.toInt()
        zones[i] = String(buf, idx, len, Charsets.UTF_8)
        idx += len
    }

    val nEntries = numEntries.toInt()
    val cells = LongArray(nEntries)
    val zoneIdArr = IntArray(nEntries)
    var prevCell = 0L
    for (i in 0 until nEntries) {
        val (delta, di) = decodeVarint(buf, idx); idx = di
        val (zid, zi) = decodeVarint(buf, idx); idx = zi
        val cell = prevCell + delta
        prevCell = cell
        cells[i] = cell
        zoneIdArr[i] = zid.toInt()
    }

    tzZones = zones
    tzCells = cells
    tzZoneIds = zoneIdArr
    tzLoaded = true
    return true
}

/**
 * Look up the IANA timezone name for a given latitude and longitude.
 * Returns null if the timezone cannot be determined or data is not loaded.
 */
fun lookupTimezone(latDeg: Double, lonDeg: Double): String? {
    if (!tzLoaded) return null
    if (latDeg < -90.0 || latDeg > 90.0 || lonDeg < -180.0 || lonDeg > 180.0) return null

    var cellId = latLngToCell(latDeg, lonDeg, tzUsedMaxRes)
    if (cellId == 0L) return null

    var res = tzUsedMaxRes
    while (res >= tzBaseRes) {
        val pos = java.util.Arrays.binarySearch(tzCells, cellId)
        if (pos >= 0) {
            val zid = tzZoneIds[pos]
            return if (zid in tzZones.indices) tzZones[zid] else null
        }
        if (res == tzBaseRes) break
        res--
        cellId = cellToParent(cellId, res)
    }
    return null
}

// ============================================================
// Varint decoding
// ============================================================

private fun decodeVarint(buf: ByteArray, startIdx: Int): Pair<Long, Int> {
    var x = 0L
    var shift = 0
    var idx = startIdx
    while (true) {
        if (idx >= buf.size) error("Unexpected EOF in varint")
        val b = buf[idx].toInt() and 0xFF
        idx++
        x = x or ((b and 0x7F).toLong() shl shift)
        if ((b and 0x80) == 0) break
        shift += 7
        if (shift > 63) error("Varint too long")
    }
    return Pair(x, idx)
}

// ============================================================
// H3 core: latLngToCell and cellToParent
// ============================================================

private fun latLngToCell(latDeg: Double, lonDeg: Double, res: Int): Long {
    val latRad = Math.toRadians(latDeg)
    val lonRad = Math.toRadians(lonDeg)

    // Find closest icosahedron face
    val cosLat = cos(latRad)
    val vx = cos(lonRad) * cosLat
    val vy = sin(lonRad) * cosLat
    val vz = sin(latRad)
    var face = 0
    var bestSqd = 5.0
    for (f in 0 until 20) {
        val dx = faceCenterPointX[f] - vx
        val dy = faceCenterPointY[f] - vy
        val dz = faceCenterPointZ[f] - vz
        val sqd = dx * dx + dy * dy + dz * dz
        if (sqd < bestSqd) { face = f; bestSqd = sqd }
    }

    // Gnomonic projection to hex2d coordinates
    var hx = 0.0
    var hy = 0.0
    val dist = acos(1.0 - bestSqd * 0.5)
    if (dist >= H3_EPSILON) {
        var theta = posAngleRads(
            faceAxesAzRadsCII[face * 3] -
                posAngleRads(geoAzimuthRads(
                    faceCenterGeoLat[face], faceCenterGeoLon[face], latRad, lonRad
                ))
        )
        if (res % 2 == 1) theta = posAngleRads(theta - M_AP7_ROT_RADS)
        var r = tan(dist) * INV_RES0_U_GNOMONIC
        for (i in 0 until res) r *= M_SQRT7
        hx = r * cos(theta)
        hy = r * sin(theta)
    }

    // Quantize to IJK and encode
    val ijk = Ijk()
    hex2dToCoordIjk(hx, hy, ijk)
    return faceIjkToH3(face, ijk, res)
}

private fun cellToParent(h: Long, parentRes: Int): Long {
    val childRes = h3GetResolution(h)
    if (parentRes < 0 || parentRes > childRes) return 0L
    if (parentRes == childRes) return h
    var result = h3SetResolution(h, parentRes)
    for (r in parentRes + 1..childRes) {
        result = h3SetIndexDigit(result, r, 7)
    }
    return result
}

// ============================================================
// H3 bit manipulation
// ============================================================

private const val H3_INIT = 0x08001FFFFFFFFFFF

private fun h3GetResolution(h: Long): Int = ((h ushr 52) and 0xFL).toInt()

private fun h3SetResolution(h: Long, res: Int): Long =
    (h and (0xFL shl 52).inv()) or (res.toLong() shl 52)

private fun h3SetBaseCell(h: Long, bc: Int): Long =
    (h and (0x7FL shl 45).inv()) or (bc.toLong() shl 45)

private fun h3GetIndexDigit(h: Long, r: Int): Int {
    val shift = (15 - r) * 3
    return ((h ushr shift) and 7L).toInt()
}

private fun h3SetIndexDigit(h: Long, r: Int, digit: Int): Long {
    val shift = (15 - r) * 3
    return (h and (7L shl shift).inv()) or (digit.toLong() shl shift)
}

// ============================================================
// H3 index rotation
// ============================================================

private fun rotate60ccwDigit(digit: Int): Int = when (digit) {
    1 -> 5; 5 -> 4; 4 -> 6; 6 -> 2; 2 -> 3; 3 -> 1; else -> digit
}

private fun rotate60cwDigit(digit: Int): Int = when (digit) {
    1 -> 3; 3 -> 2; 2 -> 6; 6 -> 4; 4 -> 5; 5 -> 1; else -> digit
}

private fun h3Rotate60ccw(h: Long): Long {
    val res = h3GetResolution(h)
    var result = h
    for (r in 1..res) {
        result = h3SetIndexDigit(result, r, rotate60ccwDigit(h3GetIndexDigit(result, r)))
    }
    return result
}

private fun h3Rotate60cw(h: Long): Long {
    val res = h3GetResolution(h)
    var result = h
    for (r in 1..res) {
        result = h3SetIndexDigit(result, r, rotate60cwDigit(h3GetIndexDigit(result, r)))
    }
    return result
}

private fun h3LeadingNonZeroDigit(h: Long): Int {
    val res = h3GetResolution(h)
    for (r in 1..res) {
        val d = h3GetIndexDigit(h, r)
        if (d != 0) return d
    }
    return 0
}

private fun h3RotatePent60ccw(h: Long): Long {
    val res = h3GetResolution(h)
    var result = h
    var foundFirstNonZero = false
    for (r in 1..res) {
        result = h3SetIndexDigit(result, r, rotate60ccwDigit(h3GetIndexDigit(result, r)))
        if (!foundFirstNonZero && h3GetIndexDigit(result, r) != 0) {
            foundFirstNonZero = true
            if (h3LeadingNonZeroDigit(result) == 1) {
                result = h3Rotate60ccw(result)
            }
        }
    }
    return result
}

// ============================================================
// H3 FaceIJK to H3 encoding
// ============================================================

private fun faceIjkToH3(face: Int, ijk: Ijk, res: Int): Long {
    var h = h3SetResolution(H3_INIT, res)

    if (res == 0) {
        if (ijk.i > 2 || ijk.j > 2 || ijk.k > 2) return 0L
        return h3SetBaseCell(h, faceIjkToBaseCell(face, ijk.i, ijk.j, ijk.k))
    }

    val w = Ijk(ijk.i, ijk.j, ijk.k)
    for (r in res - 1 downTo 0) {
        val lastI = w.i; val lastJ = w.j; val lastK = w.k
        val center = Ijk()
        if ((r + 1) % 2 == 1) {
            upAp7(w)
            center.i = w.i; center.j = w.j; center.k = w.k
            downAp7(center)
        } else {
            upAp7r(w)
            center.i = w.i; center.j = w.j; center.k = w.k
            downAp7r(center)
        }
        val diff = Ijk(lastI - center.i, lastJ - center.j, lastK - center.k)
        ijkNormalize(diff)
        h = h3SetIndexDigit(h, r + 1, unitIjkToDigit(diff))
    }

    if (w.i > 2 || w.j > 2 || w.k > 2) return 0L

    val baseCell = faceIjkToBaseCell(face, w.i, w.j, w.k)
    h = h3SetBaseCell(h, baseCell)

    val numRots = faceIjkToBaseCellCcwRot60(face, w.i, w.j, w.k)
    if (baseCell in pentagonBaseCells) {
        if (h3LeadingNonZeroDigit(h) == 1) {
            h = if (isCwOffset(baseCell, face)) h3Rotate60cw(h) else h3Rotate60ccw(h)
        }
        for (i in 0 until numRots) h = h3RotatePent60ccw(h)
    } else {
        for (i in 0 until numRots) h = h3Rotate60ccw(h)
    }

    return h
}

// ============================================================
// IJK coordinate operations
// ============================================================

private class Ijk(var i: Int = 0, var j: Int = 0, var k: Int = 0)

private fun ijkNormalize(c: Ijk) {
    if (c.i < 0) { c.j -= c.i; c.k -= c.i; c.i = 0 }
    if (c.j < 0) { c.i -= c.j; c.k -= c.j; c.j = 0 }
    if (c.k < 0) { c.i -= c.k; c.j -= c.k; c.k = 0 }
    val min = minOf(c.i, c.j, c.k)
    if (min > 0) { c.i -= min; c.j -= min; c.k -= min }
}

private fun unitIjkToDigit(ijk: Ijk): Int {
    val c = Ijk(ijk.i, ijk.j, ijk.k)
    ijkNormalize(c)
    return when {
        c.i == 0 && c.j == 0 && c.k == 0 -> 0
        c.i == 0 && c.j == 0 && c.k == 1 -> 1
        c.i == 0 && c.j == 1 && c.k == 0 -> 2
        c.i == 0 && c.j == 1 && c.k == 1 -> 3
        c.i == 1 && c.j == 0 && c.k == 0 -> 4
        c.i == 1 && c.j == 0 && c.k == 1 -> 5
        c.i == 1 && c.j == 1 && c.k == 0 -> 6
        else -> 7
    }
}

private const val M_ONESEVENTH = 1.0 / 7.0

private fun upAp7(ijk: Ijk) {
    val i = ijk.i - ijk.k
    val j = ijk.j - ijk.k
    ijk.i = Math.round((3.0 * i - j) * M_ONESEVENTH).toInt()
    ijk.j = Math.round((i + 2.0 * j) * M_ONESEVENTH).toInt()
    ijk.k = 0
    ijkNormalize(ijk)
}

private fun upAp7r(ijk: Ijk) {
    val i = ijk.i - ijk.k
    val j = ijk.j - ijk.k
    ijk.i = Math.round((2.0 * i + j) * M_ONESEVENTH).toInt()
    ijk.j = Math.round((3.0 * j - i) * M_ONESEVENTH).toInt()
    ijk.k = 0
    ijkNormalize(ijk)
}

private fun downAp7(ijk: Ijk) {
    val oi = ijk.i; val oj = ijk.j; val ok = ijk.k
    ijk.i = 3 * oi + oj
    ijk.j = 3 * oj + ok
    ijk.k = oi + 3 * ok
    ijkNormalize(ijk)
}

private fun downAp7r(ijk: Ijk) {
    val oi = ijk.i; val oj = ijk.j; val ok = ijk.k
    ijk.i = 3 * oi + ok
    ijk.j = oi + 3 * oj
    ijk.k = oj + 3 * ok
    ijkNormalize(ijk)
}

private fun hex2dToCoordIjk(x: Double, y: Double, h: Ijk) {
    h.k = 0
    val a1 = abs(x)
    val a2 = abs(y)
    val x2 = a2 * M_RSIN60
    val x1 = a1 + x2 / 2.0
    val m1 = x1.toInt()
    val m2 = x2.toInt()
    val r1 = x1 - m1
    val r2 = x2 - m2

    if (r1 < 0.5) {
        if (r1 < 1.0 / 3.0) {
            if (r2 < (1.0 + r1) / 2.0) {
                h.i = m1; h.j = m2
            } else {
                h.i = m1; h.j = m2 + 1
            }
        } else {
            h.j = if (r2 < 1.0 - r1) m2 else m2 + 1
            h.i = if (1.0 - r1 <= r2 && r2 < 2.0 * r1) m1 + 1 else m1
        }
    } else {
        if (r1 < 2.0 / 3.0) {
            h.j = if (r2 < 1.0 - r1) m2 else m2 + 1
            h.i = if (2.0 * r1 - 1.0 < r2 && r2 < 1.0 - r1) m1 else m1 + 1
        } else {
            if (r2 < r1 / 2.0) {
                h.i = m1 + 1; h.j = m2
            } else {
                h.i = m1 + 1; h.j = m2 + 1
            }
        }
    }

    if (x < 0.0) {
        if (h.j % 2 == 0) {
            val axisi = h.j / 2
            val diff = h.i - axisi
            h.i = h.i - 2 * diff
        } else {
            val axisi = (h.j + 1) / 2
            val diff = h.i - axisi
            h.i = h.i - (2 * diff + 1)
        }
    }

    if (y < 0.0) {
        h.i = h.i - (2 * h.j + 1) / 2
        h.j = -h.j
    }

    ijkNormalize(h)
}

// ============================================================
// Geo operations
// ============================================================

private const val H3_EPSILON = 1e-16
private const val M_2PI = 2.0 * Math.PI
private const val M_SQRT7 = 2.6457513110645905905016157536392604257102
private const val M_RSIN60 = 1.1547005383792515
private const val M_AP7_ROT_RADS = 0.3334731722518321
private const val INV_RES0_U_GNOMONIC = 2.6180339887498948482

private fun posAngleRads(rads: Double): Double {
    val tmp = if (rads < 0.0) rads + M_2PI else rads
    return if (rads >= M_2PI) tmp - M_2PI else tmp
}

private fun geoAzimuthRads(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    return atan2(
        cos(lat2) * sin(lon2 - lon1),
        cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
    )
}

// ============================================================
// Pentagon data
// ============================================================

private val pentagonBaseCells = setOf(4, 14, 24, 38, 49, 58, 63, 72, 83, 97, 107, 117)

private fun isCwOffset(baseCell: Int, face: Int): Boolean = when (baseCell) {
    14 -> face == 2 || face == 6
    24 -> face == 1 || face == 5
    38 -> face == 3 || face == 7
    49 -> face == 0 || face == 9
    58 -> face == 4 || face == 8
    63 -> face == 11 || face == 15
    72 -> face == 12 || face == 16
    83 -> face == 10 || face == 19
    97 -> face == 13 || face == 17
    107 -> face == 14 || face == 18
    else -> false
}

// ============================================================
// Base cell lookup
// ============================================================

private fun faceIjkBaseCellIdx(face: Int, i: Int, j: Int, k: Int) = face * 27 + i * 9 + j * 3 + k

private fun faceIjkToBaseCell(face: Int, i: Int, j: Int, k: Int): Int =
    faceIjkBaseCellsData[faceIjkBaseCellIdx(face, i, j, k)] ushr 3

private fun faceIjkToBaseCellCcwRot60(face: Int, i: Int, j: Int, k: Int): Int =
    faceIjkBaseCellsData[faceIjkBaseCellIdx(face, i, j, k)] and 7

// ============================================================
// H3 Data Tables
// ============================================================

private val faceCenterGeoLat = doubleArrayOf(
    0.803582649718989942, 1.307747883455638156, 1.054751253523952054, 0.600191595538186799,
    0.491715428198773866, 0.172745327415618701, 0.605929321571350690, 0.427370518328979641,
    -0.079066118549212831, -0.230961644455383637, 0.079066118549212831, 0.230961644455383637,
    -0.172745327415618701, -0.605929321571350690, -0.427370518328979641, -0.600191595538186799,
    -0.491715428198773866, -0.803582649718989942, -1.307747883455638156, -1.054751253523952054
)

private val faceCenterGeoLon = doubleArrayOf(
    1.248397419617396099, 2.536945009877921159, -1.347517358900396623, -0.450603909469755746,
    0.401988202911306943, 1.678146885280433686, 2.953923329812411617, -1.888876200336285401,
    -0.733429513380867741, 0.506495587332349035, 2.408163140208925497, -2.635097066257444203,
    -1.463445768309359553, -0.187669323777381622, 1.252716453253507838, 2.690988744120037492,
    -2.739604450678486295, -1.893195233972397139, -0.604647643711872080, 1.794075294689396615
)

private val faceCenterPointX = doubleArrayOf(
    0.2199307791404606, -0.2139234834501421, 0.1092625278784797, 0.7428567301586791,
    0.8112534709140969, -0.1055498149613921, -0.8075407579970092, -0.2846148069787907,
    0.7405621473854482, 0.8512303986474293, -0.7405621473854481, -0.8512303986474292,
    0.1055498149613919, 0.8075407579970092, 0.2846148069787908, -0.7428567301586791,
    -0.8112534709140971, -0.2199307791404607, 0.2139234834501420, -0.1092625278784796
)

private val faceCenterPointY = doubleArrayOf(
    0.6583691780274996, 0.1478171829550703, -0.4811951572873210, -0.3593941678278028,
    0.3448953237639384, 0.9794457296411413, 0.1533552485898818, -0.8644080972654206,
    -0.6673299564565524, 0.4722343788582681, 0.6673299564565524, -0.4722343788582682,
    -0.9794457296411413, -0.1533552485898819, 0.8644080972654204, 0.3593941678278027,
    -0.3448953237639382, -0.6583691780274996, -0.1478171829550704, 0.4811951572873210
)

private val faceCenterPointZ = doubleArrayOf(
    0.7198475378926182, 0.9656017935214205, 0.8697775121287253, 0.5648005936517033,
    0.4721387736413930, 0.1718874610009365, 0.5695261994882688, 0.4144792552473539,
    -0.0789837646326737, -0.2289137388687808, 0.0789837646326737, 0.2289137388687808,
    -0.1718874610009365, -0.5695261994882688, -0.4144792552473539, -0.5648005936517033,
    -0.4721387736413930, -0.7198475378926182, -0.9656017935214205, -0.8697775121287253
)

private val faceAxesAzRadsCII = doubleArrayOf(
    5.619958268523939882, 3.525563166130744542, 1.431168063737548730,
    5.760339081714187279, 3.665943979320991689, 1.571548876927796127,
    0.780213654393430055, 4.969003859179821079, 2.874608756786625655,
    0.430469363979999913, 4.619259568766391033, 2.524864466373195467,
    6.130269123335111400, 4.035874020941915804, 1.941478918548720291,
    2.692877706530642877, 0.598482604137447119, 4.787272808923838195,
    2.982963003477243874, 0.888567901084048369, 5.077358105870439581,
    3.532912002790141181, 1.438516900396945656, 5.627307105183336758,
    3.494305004259568154, 1.399909901866372864, 5.588700106652763840,
    3.003214169499538391, 0.908819067106342928, 5.097609271892733906,
    5.930472956509811562, 3.836077854116615875, 1.741682751723420374,
    0.138378484090254847, 4.327168688876645809, 2.232773586483450311,
    0.448714947059150361, 4.637505151845541521, 2.543110049452346120,
    0.158629650112549365, 4.347419854898940135, 2.253024752505744869,
    5.891865957979238535, 3.797470855586042958, 1.703075753192847583,
    2.711123289609793325, 0.616728187216597771, 4.805518392002988683,
    3.294508837434268316, 1.200113735041072948, 5.388903939827463911,
    3.804819692245439833, 1.710424589852244509, 5.899214794638635174,
    3.664438879055192436, 1.570043776661997111, 5.758833981448388027,
    2.361378999196363184, 0.266983896803167583, 4.455774101589558636
)

// Packed as baseCell*8 + ccwRot60. Index: face*27 + i*9 + j*3 + k
private val faceIjkBaseCellsData = intArrayOf(
    // face 0
    128, 144, 192, 264, 240, 259, 393, 387, 403,
    64, 45, 85, 176, 128, 144, 329, 264, 240,
    32, 5, 21, 121, 64, 45, 249, 176, 128,
    // face 1
    16, 48, 112, 80, 88, 139, 193, 187, 203,
    0, 13, 77, 40, 16, 48, 145, 80, 88,
    33, 29, 61, 65, 0, 13, 129, 40, 16,
    // face 2
    56, 168, 304, 72, 152, 275, 113, 163, 291,
    24, 109, 237, 8, 56, 168, 49, 72, 152,
    34, 101, 213, 1, 24, 109, 17, 8, 56,
    // face 3
    208, 336, 464, 232, 344, 499, 305, 379, 515,
    96, 229, 357, 104, 208, 336, 169, 232, 344,
    35, 125, 253, 25, 96, 229, 57, 104, 208,
    // face 4
    248, 328, 392, 352, 424, 491, 465, 523, 603,
    120, 181, 269, 224, 248, 328, 337, 352, 424,
    36, 69, 133, 97, 120, 181, 209, 224, 248,
    // face 5
    400, 384, 395, 256, 243, 267, 195, 147, 131,
    560, 536, 531, 419, 400, 384, 299, 256, 243,
    664, 699, 683, 595, 560, 536, 457, 419, 400,
    // face 6
    200, 184, 195, 136, 91, 83, 115, 51, 19,
    360, 312, 299, 283, 200, 184, 219, 136, 91,
    504, 475, 459, 451, 360, 312, 371, 283, 200,
    // face 7
    288, 160, 115, 272, 155, 75, 307, 171, 59,
    440, 320, 219, 435, 288, 160, 411, 272, 155,
    576, 483, 371, 587, 440, 320, 571, 435, 288,
    // face 8
    512, 376, 307, 496, 347, 235, 467, 339, 211,
    672, 552, 411, 659, 512, 376, 611, 496, 347,
    776, 715, 571, 787, 672, 552, 771, 659, 512,
    // face 9
    600, 520, 467, 488, 427, 355, 395, 331, 251,
    752, 688, 611, 651, 600, 520, 531, 488, 427,
    856, 835, 771, 811, 752, 688, 683, 651, 600,
    // face 10
    456, 472, 507, 592, 627, 635, 667, 739, 763,
    296, 315, 363, 416, 456, 472, 563, 592, 627,
    192, 187, 203, 259, 296, 315, 403, 416, 456,
    // face 11
    368, 480, 579, 448, 547, 643, 507, 619, 723,
    216, 323, 443, 280, 368, 480, 363, 448, 547,
    112, 163, 291, 139, 216, 323, 203, 280, 368,
    // face 12
    568, 712, 779, 584, 731, 827, 579, 707, 843,
    408, 555, 675, 432, 568, 712, 443, 584, 731,
    304, 379, 515, 275, 408, 555, 291, 432, 568,
    // face 13
    768, 832, 859, 784, 883, 923, 779, 891, 955,
    608, 691, 755, 656, 768, 832, 675, 784, 883,
    464, 523, 603, 499, 608, 691, 515, 656, 768,
    // face 14
    680, 696, 667, 808, 819, 803, 859, 899, 915,
    528, 539, 563, 648, 680, 696, 755, 808, 819,
    392, 387, 403, 491, 528, 539, 603, 648, 680,
    // face 15
    760, 736, 664, 632, 624, 595, 505, 475, 459,
    872, 864, 805, 745, 760, 736, 617, 632, 624,
    940, 949, 917, 849, 872, 864, 721, 745, 760,
    // face 16
    720, 616, 504, 640, 544, 451, 577, 483, 371,
    848, 744, 637, 793, 720, 616, 705, 640, 544,
    939, 877, 765, 905, 848, 744, 841, 793, 720,
    // face 17
    840, 704, 576, 824, 728, 587, 777, 715, 571,
    904, 792, 645, 929, 840, 704, 889, 824, 728,
    938, 853, 725, 969, 904, 792, 953, 929, 840,
    // face 18
    952, 888, 776, 920, 880, 787, 857, 835, 771,
    968, 928, 829, 961, 952, 888, 897, 920, 880,
    937, 909, 845, 945, 968, 928, 913, 961, 952,
    // face 19
    912, 896, 856, 800, 816, 811, 665, 699, 683,
    944, 960, 925, 865, 912, 896, 737, 800, 816,
    936, 973, 957, 873, 944, 960, 761, 865, 912
)
