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

private fun computeVisibleLonRange(
    bodyName: String,
    latDeg: Double,
    appRaDeg: Double,
    appDecDeg: Double,
    sinH0: Double,
    gmstHours: Double,
    bodyState: BodyState,
    jd: Double
): VisibilityRange {
    val latRad = Math.toRadians(latDeg)
    val sinLat = sin(latRad)
    val cosLat = cos(latRad)

    var decDeg = appDecDeg

    // Moon: apply topocentric parallax correction per latitude row
    if (bodyName == "Moon") {
        val subLon = normLon(appRaDeg - gmstHours * 15.0)
        val refLst = calculateLSTHours(jd, subLon)
        val topo = toTopocentric(appRaDeg, appDecDeg, bodyState.distGeo, latDeg, subLon, refLst)
        decDeg = topo.dec
    }

    val decRad = Math.toRadians(decDeg)
    val sinDec = sin(decRad)
    val cosDec = cos(decRad)

    // Handle poles and zero-declination edge cases
    if (abs(cosLat) < 1e-10 || abs(cosDec) < 1e-10) {
        val sinAlt = sinLat * sinDec
        return if (sinAlt > sinH0) VisibilityRange(0.0, 0.0, alwaysUp = true, neverUp = false)
        else VisibilityRange(0.0, 0.0, alwaysUp = false, neverUp = true)
    }

    val cosHA = (sinH0 - sinLat * sinDec) / (cosLat * cosDec)

    if (cosHA <= -1.0) return VisibilityRange(0.0, 0.0, alwaysUp = true, neverUp = false)
    if (cosHA >= 1.0) return VisibilityRange(0.0, 0.0, alwaysUp = false, neverUp = true)

    val haDeg = Math.toDegrees(acos(cosHA))
    val raHours = appRaDeg / 15.0

    val lonWest = normLon((-haDeg + raHours * 15.0 - gmstHours * 15.0))
    val lonEast = normLon((haDeg + raHours * 15.0 - gmstHours * 15.0))

    return VisibilityRange(lonWest, lonEast, alwaysUp = false, neverUp = false)
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
    val tableHeight = if (topCities.isNotEmpty()) tableHeaderHeight + topCities.size * tableRowHeight + tableGap else 0f
    val availableW = w - 2 * margin
    val availableH = h - titleHeight - margin - keyLabelHeight - keyHeight - keyGap - tableHeight
    val mh = minOf(availableH, availableW / 2f)
    val mw = mh * 2f
    val mLeft = (w - mw) / 2f
    val mTop = titleHeight

    fun lonToX(lon: Double): Float = ((lon + 180.0) / 360.0 * mw + mLeft).toFloat()
    fun latToY(lat: Double): Float = ((90.0 - lat) / 180.0 * mh + mTop).toFloat()

    // 1. Background: solid gray, then paint visible region white per row
    val pixelHeight = mh.toInt()
    val shadeColor = Color(0xFF383838)

    drawScope.drawRect(
        shadeColor,
        topLeft = Offset(mLeft, mTop),
        size = Size(mw, mh)
    )

    // 2. Paint above-horizon region white
    for (py in 0 until pixelHeight) {
        val lat = 90.0 - (py.toDouble() / mh) * 180.0
        val screenY = mTop + py

        val range = computeVisibleLonRange(
            bodyName, lat, appRaDeg, appDecDeg, sinH0, gmstHours, bodyState, jd
        )

        if (range.neverUp) continue
        if (range.alwaysUp) {
            drawScope.drawRect(Color.White, Offset(mLeft, screenY), Size(mw, 2f))
            continue
        }

        val x1 = lonToX(range.lon1)
        val x2 = lonToX(range.lon2)

        if (x1 <= x2) {
            // Visible range doesn't wrap: white between x1 and x2
            drawScope.drawRect(Color.White, Offset(x1, screenY), Size(x2 - x1, 2f))
        } else {
            // Visible range wraps: white on left (mLeft to x2) and right (x1 to edge)
            drawScope.drawRect(Color.White, Offset(mLeft, screenY), Size(x2 - mLeft, 2f))
            drawScope.drawRect(Color.White, Offset(x1, screenY), Size(mLeft + mw - x1, 2f))
        }
    }

    // 3. Elevation contours (half-pixel steps for smoother curves)
    val maxLineLen = mw / 4f
    val contourSteps = pixelHeight * 2
    for ((elevDeg, contourColor) in elevationContours) {
        val sinHC = sin(Math.toRadians(elevDeg))
        var prevWestX: Float? = null
        var prevEastX: Float? = null
        var prevY = 0f
        var firstWestX: Float? = null
        var firstEastX: Float? = null
        var firstY = 0f
        var lastWestX: Float? = null
        var lastEastX: Float? = null
        var lastY = 0f

        for (step in 0..contourSteps) {
            val lat = 90.0 - (step.toDouble() / contourSteps) * 180.0
            val screenY = mTop + step.toFloat() / contourSteps * mh

            val range = computeVisibleLonRange(
                bodyName, lat, appRaDeg, appDecDeg, sinHC, gmstHours, bodyState, jd
            )

            if (range.alwaysUp || range.neverUp) {
                prevWestX = null
                prevEastX = null
                prevY = screenY
                continue
            }

            val curWestX = lonToX(range.lon1)
            val curEastX = lonToX(range.lon2)

            if (firstWestX == null) {
                firstWestX = curWestX; firstEastX = curEastX; firstY = screenY
            }
            lastWestX = curWestX; lastEastX = curEastX; lastY = screenY

            if (prevWestX != null && abs(curWestX - prevWestX!!) < maxLineLen) {
                drawScope.drawLine(contourColor, Offset(prevWestX!!, prevY), Offset(curWestX, screenY), 3f)
            }
            if (prevEastX != null && abs(curEastX - prevEastX!!) < maxLineLen) {
                drawScope.drawLine(contourColor, Offset(prevEastX!!, prevY), Offset(curEastX, screenY), 3f)
            }

            prevWestX = curWestX
            prevEastX = curEastX
            prevY = screenY
        }

        // Close top gap (horizontal line connecting first west and east points)
        if (firstWestX != null && firstEastX != null && abs(firstWestX - firstEastX!!) < maxLineLen) {
            drawScope.drawLine(contourColor, Offset(firstWestX, firstY), Offset(firstEastX!!, firstY), 3f)
        }
        // Close bottom gap (horizontal line connecting last west and east points)
        if (lastWestX != null && lastEastX != null && abs(lastWestX - lastEastX!!) < maxLineLen) {
            drawScope.drawLine(contourColor, Offset(lastWestX, lastY), Offset(lastEastX!!, lastY), 3f)
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
        for (i in elevationContours.indices) {
            val (elevDeg, color) = elevationContours[i]
            val kx = keyMargin + i * entryWidth
            canvas.nativeCanvas.drawRect(kx, ky, kx + swatchSize, ky + swatchSize,
                Paint().apply { this.color = color.toArgb(); style = Paint.Style.FILL })
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
            val cityPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                textSize = 38f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.DEFAULT
            }

            val headerY = tableTop + 22f
            canvas.nativeCanvas.drawText("City", col1, headerY, headerPaint)
            canvas.nativeCanvas.drawText("Country", col2, headerY, headerPaint)
            canvas.nativeCanvas.drawText("Elev", col3, headerY, headerPaint)
            canvas.nativeCanvas.drawText("Az", col4, headerY, headerPaint)

            for (i in topCities.indices) {
                val city = topCities[i]
                val rowY = tableTop + tableHeaderHeight + (i + 1) * tableRowHeight
                canvas.nativeCanvas.drawText(city.name, col1, rowY, cityPaint)
                canvas.nativeCanvas.drawText(city.country, col2, rowY, cityPaint)
                canvas.nativeCanvas.drawText("%.0f\u00B0".format(city.elevation), col3, rowY, cityPaint)
                canvas.nativeCanvas.drawText("%.0f\u00B0".format(city.azimuth), col4, rowY, cityPaint)
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
    val azimuth: Double
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

    val result = mutableListOf<CityVisibility>()
    val countriesSeen = mutableSetOf<String>()

    for (city in cities) {
        if (city.countryCode in countriesSeen) continue

        var raDeg = appRaDeg
        var decDeg = appDecDeg

        val lst = calculateLSTHours(jd, city.lon)

        if (bodyName == "Moon") {
            val topo = toTopocentric(appRaDeg, appDecDeg, bodyState.distGeo, city.lat, city.lon, lst)
            raDeg = topo.ra
            decDeg = topo.dec
        }

        val azAlt = calculateAzAlt(lst, city.lat, raDeg / 15.0, decDeg)

        if (azAlt.alt > 0.0) {
            countriesSeen.add(city.countryCode)
            result.add(CityVisibility(
                city.name,
                CityData.countryName(city.countryCode),
                azAlt.alt, azAlt.az
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

    LaunchedEffect(Unit) {
        shoreline = loadShorelineDataOV(context)
        CityData.load(context)
    }

    val bodyNames = listOf("Sun", "Moon", "Mercury", "Venus", "Mars",
        "Jupiter", "Saturn", "Uranus", "Neptune")

    val topCities = remember(selectedBody, obs.now) {
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
