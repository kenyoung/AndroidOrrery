package com.kenyoung.orrery

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

private const val RAD_TO_DEG_OV = 180.0 / Math.PI

private val elevationContours = listOf(
    10.0 to Color(0xFF8B4513),  // brown
    20.0 to Color(0xFFFF0000),  // red
    30.0 to Color(0xFFFF8C00),  // orange
    40.0 to Color(0xFFFFDD00),  // yellow
    50.0 to Color(0xFF00CC00),  // green
    60.0 to Color(0xFF00CCAA),  // blue-green
    70.0 to Color(0xFF0088FF),  // blue
    80.0 to Color(0xFF9944FF),  // violet
)

// ============================================================================
// SHORELINE DATA
// ============================================================================

private data class ShoreSegmentOV(
    val nVertices: Int,
    val lat: ShortArray,
    val lon: ShortArray
)

@Volatile
private var shorelineSegmentsOV: List<ShoreSegmentOV>? = null

private suspend fun loadShorelineDataOV(context: Context): List<ShoreSegmentOV> {
    shorelineSegmentsOV?.let { return it }
    return withContext(Dispatchers.IO) {
        val segments = mutableListOf<ShoreSegmentOV>()
        context.assets.open("shoreline").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) {
                val nPairs = buffer.short.toInt()
                if (nPairs <= 0 || buffer.remaining() < nPairs * 4) break
                val lats = ShortArray(nPairs)
                val lons = ShortArray(nPairs)
                for (j in 0 until nPairs) {
                    lons[j] = buffer.short
                    lats[j] = buffer.short
                }
                segments.add(ShoreSegmentOV(nPairs, lats, lons))
            }
        }
        shorelineSegmentsOV = segments
        segments
    }
}

// ============================================================================
// VISIBILITY COMPUTATION
// ============================================================================

private data class VisibilityRange(
    val lon1: Double,       // western boundary (degrees, -180 to 180)
    val lon2: Double,       // eastern boundary (degrees, -180 to 180)
    val alwaysUp: Boolean,
    val neverUp: Boolean
)

private fun normLon(lon: Double): Double {
    var l = lon % 360.0
    if (l > 180.0) l -= 360.0
    if (l < -180.0) l += 360.0
    return l
}

/**
 * Compute the longitude range where an object is above a given altitude threshold.
 * Accepts pre-computed trig values so callers can evaluate multiple thresholds
 * without redundant sin/cos on latitude and declination.
 */
private fun computeRangeFromTrig(
    sinLat: Double, cosLat: Double,
    sinDec: Double, cosDec: Double,
    sinH0: Double,
    subObjLon: Double
): VisibilityRange {
    if (abs(cosLat) < 1e-10 || abs(cosDec) < 1e-10) {
        val sinAlt = sinLat * sinDec
        return if (sinAlt > sinH0) VisibilityRange(0.0, 0.0, alwaysUp = true, neverUp = false)
        else VisibilityRange(0.0, 0.0, alwaysUp = false, neverUp = true)
    }

    val cosHA = (sinH0 - sinLat * sinDec) / (cosLat * cosDec)

    if (cosHA <= -1.0) return VisibilityRange(0.0, 0.0, alwaysUp = true, neverUp = false)
    if (cosHA >= 1.0) return VisibilityRange(0.0, 0.0, alwaysUp = false, neverUp = true)

    val haDeg = Math.toDegrees(acos(cosHA))
    return VisibilityRange(
        normLon(subObjLon - haDeg),
        normLon(subObjLon + haDeg),
        alwaysUp = false, neverUp = false
    )
}

/**
 * Prepare dec trig for a given latitude, applying Moon topocentric correction if needed.
 * Returns (sinDec, cosDec).
 */
private fun getDecTrig(
    bodyName: String, latDeg: Double,
    appRaDeg: Double, appDecDeg: Double,
    gmstHours: Double, bodyState: BodyState, jd: Double
): Pair<Double, Double> {
    var decDeg = appDecDeg
    if (bodyName == "Moon") {
        val subLon = normLon(appRaDeg - gmstHours * 15.0)
        val refLst = calculateLSTHours(jd, subLon)
        val topo = toTopocentric(appRaDeg, appDecDeg, bodyState.distGeo, latDeg, subLon, refLst)
        decDeg = topo.dec
    }
    val decRad = Math.toRadians(decDeg)
    return Pair(sin(decRad), cos(decRad))
}

// ============================================================================
// DRAWING
// ============================================================================

private fun drawVisibilityMap(
    drawScope: DrawScope,
    shoreline: List<ShoreSegmentOV>,
    bodyName: String,
    obs: ObserverState,
    topCities: List<CityVisibility>,
    w: Float,
    h: Float
) {
    val jd = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val bodyState = AstroEngine.getBodyState(bodyName, jd)
    val apparent = j2000ToApparent(bodyState.ra, bodyState.dec, jd)
    val appRaDeg = apparent.ra
    val appDecDeg = apparent.dec
    val gmstHours = calculateGMST(jd)

    // For non-Sun objects, compute Sun position for daylight shading
    val isSun = bodyName == "Sun"
    val sunSinDec: Double
    val sunCosDec: Double
    val sunSinH0: Double
    val sunSubLon: Double
    if (!isSun) {
        val sunState = AstroEngine.getBodyState("Sun", jd)
        val sunApparent = j2000ToApparent(sunState.ra, sunState.dec, jd)
        val sunDecRad = Math.toRadians(sunApparent.dec)
        sunSinDec = sin(sunDecRad)
        sunCosDec = cos(sunDecRad)
        sunSinH0 = sin(Math.toRadians(HORIZON_REFRACTED))
        sunSubLon = normLon(sunApparent.ra - gmstHours * 15.0)
    } else {
        sunSinDec = 0.0; sunCosDec = 1.0; sunSinH0 = 0.0; sunSubLon = 0.0
    }

    val horizonAlt = when (bodyName) {
        "Sun", "Moon" -> HORIZON_REFRACTED
        else -> PLANET_HORIZON_ALT
    }
    val sinH0 = sin(Math.toRadians(horizonAlt))

    // Sub-object point
    val subLat = appDecDeg
    val subLon = normLon(appRaDeg - gmstHours * 15.0)

    // Map dimensions: 2:1 aspect ratio, reserving space for key and city table below
    val titleHeight = 96f
    val margin = 8f
    val keyLabelHeight = 36f
    val keyHeight = 50f
    val keyGap = 6f
    val tableRowHeight = 42f
    val tableHeaderHeight = 46f
    val tableGap = 30f
    val tableHeight = if (topCities.isNotEmpty()) tableHeaderHeight + (topCities.size + 1) * tableRowHeight + tableGap else 0f
    val availableW = w - 2 * margin
    val availableH = h - titleHeight - margin - keyLabelHeight - keyHeight - keyGap - tableHeight
    val mh = minOf(availableH, availableW / 2f)
    val mw = mh * 2f
    val mLeft = (w - mw) / 2f
    val mTop = titleHeight

    fun lonToX(lon: Double): Float = ((lon + 180.0) / 360.0 * mw + mLeft).toFloat()
    fun latToY(lat: Double): Float = ((90.0 - lat) / 180.0 * mh + mTop).toFloat()

    // 1. Background: solid gray, then paint visible region white and draw contours in one pass
    val pixelHeight = mh.toInt()
    val shadeColor = Color(0xFF383838)
    val lightShadeColor = Color(0xFF909090)

    drawScope.drawRect(
        if (isSun) shadeColor else Color.White,
        topLeft = Offset(mLeft, mTop),
        size = Size(mw, mh)
    )

    // Pre-compute contour sin(threshold) values
    val contourCount = elevationContours.size
    val contourSinH = DoubleArray(contourCount) { sin(Math.toRadians(elevationContours[it].first)) }
    val subObjLon = normLon(appRaDeg - gmstHours * 15.0)

    // Per-contour tracking state for line drawing
    val maxLineLen = mw / 4f
    val prevWestX = arrayOfNulls<Float>(contourCount)
    val prevEastX = arrayOfNulls<Float>(contourCount)
    val prevY = FloatArray(contourCount)
    val firstWestX = arrayOfNulls<Float>(contourCount)
    val firstEastX = arrayOfNulls<Float>(contourCount)
    val firstY = FloatArray(contourCount)
    val lastWestX = arrayOfNulls<Float>(contourCount)
    val lastEastX = arrayOfNulls<Float>(contourCount)
    val lastY = FloatArray(contourCount)

    // 2. Single pass over latitude rows at half-pixel resolution
    val contourSteps = pixelHeight * 2
    for (step in 0..contourSteps) {
        val lat = 90.0 - (step.toDouble() / contourSteps) * 180.0
        val screenY = mTop + step.toFloat() / contourSteps * mh

        val latRad = Math.toRadians(lat)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val (sinDec, cosDec) = getDecTrig(bodyName, lat, appRaDeg, appDecDeg, gmstHours, bodyState, jd)

        // Horizon shading (only on integer pixel rows)
        if (step % 2 == 0) {
            val horizRange = computeRangeFromTrig(sinLat, cosLat, sinDec, cosDec, sinH0, subObjLon)
            if (isSun) {
                // Sun: white where above horizon on dark gray background
                if (!horizRange.neverUp) {
                    if (horizRange.alwaysUp) {
                        drawScope.drawRect(Color.White, Offset(mLeft, screenY), Size(mw, 2f))
                    } else {
                        val x1 = lonToX(horizRange.lon1)
                        val x2 = lonToX(horizRange.lon2)
                        if (x1 <= x2) {
                            drawScope.drawRect(Color.White, Offset(x1, screenY), Size(x2 - x1, 2f))
                        } else {
                            drawScope.drawRect(Color.White, Offset(mLeft, screenY), Size(x2 - mLeft, 2f))
                            drawScope.drawRect(Color.White, Offset(x1, screenY), Size(mLeft + mw - x1, 2f))
                        }
                    }
                }
            } else {
                // Non-Sun: light gray where Sun is up, then dark gray where object is down
                val sunRange = computeRangeFromTrig(sinLat, cosLat, sunSinDec, sunCosDec, sunSinH0, sunSubLon)
                if (!sunRange.neverUp) {
                    if (sunRange.alwaysUp) {
                        drawScope.drawRect(lightShadeColor, Offset(mLeft, screenY), Size(mw, 2f))
                    } else {
                        val sx1 = lonToX(sunRange.lon1)
                        val sx2 = lonToX(sunRange.lon2)
                        if (sx1 <= sx2) {
                            drawScope.drawRect(lightShadeColor, Offset(sx1, screenY), Size(sx2 - sx1, 2f))
                        } else {
                            drawScope.drawRect(lightShadeColor, Offset(mLeft, screenY), Size(sx2 - mLeft, 2f))
                            drawScope.drawRect(lightShadeColor, Offset(sx1, screenY), Size(mLeft + mw - sx1, 2f))
                        }
                    }
                }
                // Dark gray where object is below horizon (overwrites light gray in overlap)
                if (horizRange.neverUp) {
                    drawScope.drawRect(shadeColor, Offset(mLeft, screenY), Size(mw, 2f))
                } else if (!horizRange.alwaysUp) {
                    val x1 = lonToX(horizRange.lon1)
                    val x2 = lonToX(horizRange.lon2)
                    if (x1 <= x2) {
                        drawScope.drawRect(shadeColor, Offset(mLeft, screenY), Size(x1 - mLeft, 2f))
                        drawScope.drawRect(shadeColor, Offset(x2, screenY), Size(mLeft + mw - x2, 2f))
                    } else {
                        drawScope.drawRect(shadeColor, Offset(x2, screenY), Size(x1 - x2, 2f))
                    }
                }
            }
        }

        // Elevation contours (all thresholds share the same lat/dec trig)
        for (c in 0 until contourCount) {
            val range = computeRangeFromTrig(sinLat, cosLat, sinDec, cosDec, contourSinH[c], subObjLon)
            val contourColor = elevationContours[c].second

            if (range.alwaysUp || range.neverUp) {
                prevWestX[c] = null
                prevEastX[c] = null
                prevY[c] = screenY
                continue
            }

            val curWestX = lonToX(range.lon1)
            val curEastX = lonToX(range.lon2)

            if (firstWestX[c] == null) {
                firstWestX[c] = curWestX; firstEastX[c] = curEastX; firstY[c] = screenY
            }
            lastWestX[c] = curWestX; lastEastX[c] = curEastX; lastY[c] = screenY

            if (prevWestX[c] != null && abs(curWestX - prevWestX[c]!!) < maxLineLen) {
                drawScope.drawLine(contourColor, Offset(prevWestX[c]!!, prevY[c]), Offset(curWestX, screenY), 3f)
            }
            if (prevEastX[c] != null && abs(curEastX - prevEastX[c]!!) < maxLineLen) {
                drawScope.drawLine(contourColor, Offset(prevEastX[c]!!, prevY[c]), Offset(curEastX, screenY), 3f)
            }

            prevWestX[c] = curWestX
            prevEastX[c] = curEastX
            prevY[c] = screenY
        }
    }

    // Close top and bottom gaps for each contour
    for (c in 0 until contourCount) {
        val contourColor = elevationContours[c].second
        if (firstWestX[c] != null && firstEastX[c] != null && abs(firstWestX[c]!! - firstEastX[c]!!) < maxLineLen) {
            drawScope.drawLine(contourColor, Offset(firstWestX[c]!!, firstY[c]), Offset(firstEastX[c]!!, firstY[c]), 3f)
        }
        if (lastWestX[c] != null && lastEastX[c] != null && abs(lastWestX[c]!! - lastEastX[c]!!) < maxLineLen) {
            drawScope.drawLine(contourColor, Offset(lastWestX[c]!!, lastY[c]), Offset(lastEastX[c]!!, lastY[c]), 3f)
        }
    }

    // 4. Shoreline
    for (seg in shoreline) {
        for (j in 0 until seg.nVertices - 1) {
            val lat1 = seg.lat[j].toDouble() * Math.PI / 65535.0 * RAD_TO_DEG_OV
            val lon1 = seg.lon[j].toDouble() * Math.PI / 32767.0 * RAD_TO_DEG_OV
            val lat2 = seg.lat[j + 1].toDouble() * Math.PI / 65535.0 * RAD_TO_DEG_OV
            val lon2 = seg.lon[j + 1].toDouble() * Math.PI / 32767.0 * RAD_TO_DEG_OV

            val sx1 = lonToX(lon1); val sy1 = latToY(lat1)
            val sx2 = lonToX(lon2); val sy2 = latToY(lat2)

            if (abs(sx2 - sx1) < mw * 0.5f) {
                drawScope.drawLine(Color.Black, Offset(sx1, sy1), Offset(sx2, sy2), 1f)
            }
        }
    }

    // 5. Sub-object point (black star)
    val subX = lonToX(subLon)
    val subY = latToY(subLat)
    val starRadius = 6f
    val starInner = starRadius * 0.4f
    val starPath = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) starRadius else starInner
        val angle = Math.PI / 2.0 + i * Math.PI / 5.0
        val px = subX + (r * cos(angle)).toFloat()
        val py = subY - (r * sin(angle)).toFloat()
        if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
    }
    starPath.close()
    drawScope.drawPath(starPath, Color.Black)

    // 6. Observer location (red circle + crosshair)
    val obsX = lonToX(obs.lon)
    val obsY = latToY(obs.lat)
    drawScope.drawCircle(Color.Red, radius = 5f, center = Offset(obsX, obsY))
    drawScope.drawLine(Color.Red, Offset(obsX - 8f, obsY), Offset(obsX + 8f, obsY), 1f)
    drawScope.drawLine(Color.Red, Offset(obsX, obsY - 8f), Offset(obsX, obsY + 8f), 1f)

    // 7. Title with date/time
    val displayOffsetSeconds = if (obs.useStandardTime) (obs.stdOffsetHours * 3600).toLong() else 0L
    val displayZone = ZoneId.ofOffset("", ZoneOffset.ofTotalSeconds(displayOffsetSeconds.toInt()))
    val timeLabel = if (obs.useStandardTime) obs.stdTimeLabel else "UT"
    val dateTimeStr = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(displayZone).format(obs.now) + " $timeLabel"

    drawScope.drawIntoCanvas { canvas ->
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = 0xFF4488FF.toInt()
            textSize = 60f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleText = "$bodyName Visibility  "
        val titleWidth = titlePaint.measureText(titleText)
        val dateTimePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 60f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val totalWidth = titleWidth + dateTimePaint.measureText(dateTimeStr)
        val startX = (w - totalWidth) / 2f
        val baselineY = titleHeight - 24f
        canvas.nativeCanvas.drawText(titleText, startX, baselineY, titlePaint)
        canvas.nativeCanvas.drawText(dateTimeStr, startX + titleWidth, baselineY, dateTimePaint)


        // 8. Elevation label between map and color key
        val keyLabelPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 32f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.nativeCanvas.drawText(
            "$bodyName Elevation",
            w / 2f, mTop + mh + keyGap + keyLabelHeight - 6f, keyLabelPaint
        )

        // 9. Color key directly below label, spanning full width
        val keyTop = mTop + mh + keyGap + keyLabelHeight
        val keyMargin = margin
        val keyAvailableW = w - 2 * keyMargin
        val entryCount = elevationContours.size
        val entryWidth = keyAvailableW / entryCount
        val swatchSize = minOf(entryWidth * 0.7f, keyHeight * 0.8f)
        val keyTextSize = minOf(entryWidth * 0.9f, keyHeight * 0.8f)
        val keyPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = keyTextSize
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT
        }
        val ky = keyTop + (keyHeight - swatchSize) / 2f
        val swatchPaint = Paint().apply { style = Paint.Style.FILL }
        for (i in elevationContours.indices) {
            val (elevDeg, color) = elevationContours[i]
            val kx = keyMargin + i * entryWidth
            swatchPaint.color = color.toArgb()
            canvas.nativeCanvas.drawRect(kx, ky, kx + swatchSize, ky + swatchSize, swatchPaint)
            canvas.nativeCanvas.drawText("${elevDeg.toInt()}\u00B0",
                kx + swatchSize + 3f, ky + swatchSize - 1f, keyPaint)
        }

        // 10. City table below color key
        if (topCities.isNotEmpty()) {
            val tableTop = keyTop + keyHeight + tableGap
            val col1 = margin
            val col2 = w * 0.30f
            val col3 = w * 0.65f
            val col4 = w * 0.82f

            val headerPaint = Paint().apply {
                isAntiAlias = true
                color = 0xFF88AAFF.toInt()
                textSize = 42f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headerRightPaint = Paint().apply {
                isAntiAlias = true
                color = 0xFF88AAFF.toInt()
                textSize = 42f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cityPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                textSize = 38f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.DEFAULT
            }
            val cityRightPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                textSize = 38f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.DEFAULT
            }

            val headerY = tableTop + 22f
            canvas.nativeCanvas.drawText("City", col1, headerY, headerPaint)
            canvas.nativeCanvas.drawText("Country", col2, headerY, headerPaint)
            canvas.nativeCanvas.drawText("Elev", col4 - margin, headerY, headerRightPaint)
            canvas.nativeCanvas.drawText("Az", w - margin, headerY, headerRightPaint)

            // Observer's own location as first row
            val obsLst = calculateLSTHours(jd, obs.lon)
            val obsRaDeg: Double
            val obsDecDeg: Double
            if (bodyName == "Moon") {
                val topo = toTopocentric(appRaDeg, appDecDeg, bodyState.distGeo, obs.lat, obs.lon, obsLst)
                obsRaDeg = topo.ra
                obsDecDeg = topo.dec
            } else {
                obsRaDeg = appRaDeg
                obsDecDeg = appDecDeg
            }
            val obsAzAlt = calculateAzAlt(obsLst, obs.lat, obsRaDeg / 15.0, obsDecDeg)
            val obsUp = obsAzAlt.alt > 0.0
            val obsColor = if (obsUp) android.graphics.Color.GREEN else android.graphics.Color.RED
            cityPaint.color = obsColor
            cityRightPaint.color = obsColor
            val obsRowY = tableTop + tableHeaderHeight + tableRowHeight
            canvas.nativeCanvas.drawText("Your Location", col1, obsRowY, cityPaint)
            if (obsUp) {
                val obsHa = normalizeHourAngle(obsLst - obsRaDeg / 15.0)
                val obsArrow = if (obsHa < 0.0) "\u2191" else "\u2193"
                canvas.nativeCanvas.drawText("%.0f\u00B0 %s".format(obsAzAlt.alt, obsArrow), col4 - margin, obsRowY, cityRightPaint)
            } else {
                canvas.nativeCanvas.drawText("%.0f\u00B0".format(obsAzAlt.alt), col4 - margin, obsRowY, cityRightPaint)
            }
            canvas.nativeCanvas.drawText("%.0f\u00B0".format(obsAzAlt.az), w - margin, obsRowY, cityRightPaint)
            cityPaint.color = android.graphics.Color.WHITE
            cityRightPaint.color = android.graphics.Color.WHITE

            for (i in topCities.indices) {
                val city = topCities[i]
                val rowColor = if (city.sunIsUp) 0xFF909090.toInt() else android.graphics.Color.WHITE
                cityPaint.color = rowColor
                cityRightPaint.color = rowColor
                val rowY = tableTop + tableHeaderHeight + (i + 2) * tableRowHeight
                canvas.nativeCanvas.drawText(city.name, col1, rowY, cityPaint)
                canvas.nativeCanvas.drawText(city.country, col2, rowY, cityPaint)
                val arrow = if (city.isRising) "\u2191" else "\u2193"
                canvas.nativeCanvas.drawText("%.0f\u00B0 %s".format(city.elevation, arrow), col4 - margin, rowY, cityRightPaint)
                canvas.nativeCanvas.drawText("%.0f\u00B0".format(city.azimuth), w - margin, rowY, cityRightPaint)
            }
        }
    }
}

// ============================================================================
// COMPOSABLES
// ============================================================================

@Composable
private fun ObjectSelector(
    bodyNames: List<String>,
    selectedBody: String,
    onBodySelected: (String) -> Unit
) {
    val rows = bodyNames.chunked(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (name in row) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = (name == selectedBody),
                            onClick = { onBodySelected(name) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color.White,
                                unselectedColor = Color.Gray
                            )
                        )
                        Text(name, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private data class CityVisibility(
    val name: String,
    val country: String,
    val elevation: Double,
    val azimuth: Double,
    val isRising: Boolean,
    val sunIsUp: Boolean
)

private fun computeTopCities(
    bodyName: String,
    obs: ObserverState,
    cities: List<CityEntry>
): List<CityVisibility> {
    val jd = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val bodyState = AstroEngine.getBodyState(bodyName, jd)
    val apparent = j2000ToApparent(bodyState.ra, bodyState.dec, jd)
    val appRaDeg = apparent.ra
    val appDecDeg = apparent.dec

    // Compute Sun position for daylight check (non-Sun objects only)
    val sunRaHours: Double
    val sunDecDeg: Double
    if (bodyName != "Sun") {
        val sunState = AstroEngine.getBodyState("Sun", jd)
        val sunApparent = j2000ToApparent(sunState.ra, sunState.dec, jd)
        sunRaHours = sunApparent.ra / 15.0
        sunDecDeg = sunApparent.dec
    } else {
        sunRaHours = 0.0; sunDecDeg = 0.0
    }

    val result = mutableListOf<CityVisibility>()
    val countriesSeen = mutableSetOf<String>()

    for (city in cities) {
        if (city.countryCode in countriesSeen) continue

        val lst = calculateLSTHours(jd, city.lon)

        val raDeg: Double
        val decDeg: Double
        if (bodyName == "Moon") {
            val topo = toTopocentric(appRaDeg, appDecDeg, bodyState.distGeo, city.lat, city.lon, lst)
            raDeg = topo.ra
            decDeg = topo.dec
        } else {
            raDeg = appRaDeg
            decDeg = appDecDeg
        }

        val azAlt = calculateAzAlt(lst, city.lat, raDeg / 15.0, decDeg)

        if (azAlt.alt > 0.0) {
            val ha = normalizeHourAngle(lst - raDeg / 15.0)
            val sunUp = if (bodyName != "Sun") {
                val sunAzAlt = calculateAzAlt(lst, city.lat, sunRaHours, sunDecDeg)
                sunAzAlt.alt > 0.0
            } else false
            countriesSeen.add(city.countryCode)
            result.add(CityVisibility(
                city.name,
                CityData.countryName(city.countryCode),
                azAlt.alt, azAlt.az,
                isRising = ha < 0.0,
                sunIsUp = sunUp
            ))
            if (result.size >= 15) break
        }
    }
    return result
}

@Composable
fun ObjectVisibilityScreen(obs: ObserverState) {
    val context = LocalContext.current
    var shoreline by remember { mutableStateOf<List<ShoreSegmentOV>?>(null) }
    var selectedBody by remember { mutableStateOf("Sun") }

    var citiesLoaded by remember { mutableStateOf(CityData.cities != null) }

    LaunchedEffect(Unit) {
        shoreline = loadShorelineDataOV(context)
        CityData.load(context)
        citiesLoaded = true
    }

    val bodyNames = listOf("Sun", "Moon", "Mercury", "Venus", "Mars",
        "Jupiter", "Saturn", "Uranus", "Neptune")

    val topCities = remember(selectedBody, obs.now, citiesLoaded) {
        val cities = CityData.cities
        if (cities != null) computeTopCities(selectedBody, obs, cities)
        else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (shoreline != null) {
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                withDensityScaling { w, h ->
                    drawVisibilityMap(this@Canvas, shoreline!!, selectedBody, obs, topCities, w, h)
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading...", color = Color.White)
            }
        }

        ObjectSelector(
            bodyNames = bodyNames,
            selectedBody = selectedBody,
            onBodySelected = { selectedBody = it }
        )
    }
}
