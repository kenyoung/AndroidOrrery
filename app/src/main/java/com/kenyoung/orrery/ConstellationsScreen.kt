package com.kenyoung.orrery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

private val zodiacSymbols = mapOf(
    "Aries" to "\u2648\uFE0E",
    "Taurus" to "\u2649\uFE0E",
    "Gemini" to "\u264A\uFE0E",
    "Cancer" to "\u264B\uFE0E",
    "Leo" to "\u264C\uFE0E",
    "Virgo" to "\u264D\uFE0E",
    "Libra" to "\u264E\uFE0E",
    "Scorpius" to "\u264F\uFE0E",
    "Sagittarius" to "\u2650\uFE0E",
    "Capricornus" to "\u2651\uFE0E",
    "Aquarius" to "\u2652\uFE0E",
    "Pisces" to "\u2653\uFE0E",
)

private val planetSymbols = mapOf(
    "Sun" to "\u2609",       // ☉
    "Moon" to "\u263D",      // ☽
    "Mercury" to "\u263F",   // ☿
    "Venus" to "\u2640",     // ♀
    "Mars" to "\u2642",      // ♂
    "Jupiter" to "\u2643",   // ♃
    "Saturn" to "\u2644",    // ♄
    "Uranus" to "\u2645",    // ♅
    "Neptune" to "\u2646",   // ♆
)

private data class ObjectRow(
    val name: String,
    val constellation: String,
    val appRaHours: Double,
    val appDecDeg: Double
)

private const val DEC_MIN = -50.0
private const val DEC_MAX = 50.0

private fun DrawScope.raToX(raHours: Double): Float {
    val chartRa = if (raHours > 12.0) raHours - 24.0 else raHours
    return (size.width * (12.0 - chartRa) / 24.0).toFloat()
}

private fun DrawScope.decToY(dec: Double): Float {
    val DEC_RANGE = DEC_MAX - DEC_MIN
    return (size.height * (DEC_MAX - dec) / DEC_RANGE).toFloat()
}

private fun DrawScope.drawEcliptic(obliquity: Double) {
    val w = size.width
    val epsRad = Math.toRadians(obliquity)
    val eclipticPoints = (0..360).map { lambdaDeg ->
        val lambdaRad = Math.toRadians(lambdaDeg.toDouble())
        val ra = Math.toDegrees(atan2(sin(lambdaRad) * cos(epsRad), cos(lambdaRad)))
        val dec = Math.toDegrees(asin(sin(lambdaRad) * sin(epsRad)))
        val raH = normalizeDegrees(ra) * DEGREES_TO_HOURS
        Offset(raToX(raH), decToY(dec))
    }
    for (i in 0 until eclipticPoints.size - 1) {
        val p1 = eclipticPoints[i]
        val p2 = eclipticPoints[i + 1]
        if (abs(p1.x - p2.x) < w * 0.5f) {
            drawLine(Color.Red, p1, p2, strokeWidth = 3.0f)
        }
    }
}

/**
 * Boundary segment in RA/Dec coordinates (B1875.0 hours/degrees).
 */
private data class MapSegment(
    val ra1: Double, val dec1: Double,
    val ra2: Double, val dec2: Double
)

private data class ZodiacLabel(
    val raHours: Double,
    val decDeg: Double,
    val symbol: String
)

private data class ZodiacMapData(
    val segments: List<MapSegment>,
    val labels: List<ZodiacLabel>
)

private val zodiacIndexToSymbol = mapOf(
    4 to "\u2652\uFE0E",   // Aquarius ♒
    6 to "\u2648\uFE0E",   // Aries ♈
    11 to "\u2651\uFE0E",  // Capricornus ♑
    21 to "\u264B\uFE0E",  // Cancer ♋
    37 to "\u264A\uFE0E",  // Gemini ♊
    45 to "\u264C\uFE0E",  // Leo ♌
    47 to "\u264E\uFE0E",  // Libra ♎
    66 to "\u2653\uFE0E",  // Pisces ♓
    71 to "\u264F\uFE0E",  // Scorpius ♏
    76 to "\u2650\uFE0E",  // Sagittarius ♐
    77 to "\u2649\uFE0E",  // Taurus ♉
    85 to "\u264D\uFE0E",  // Virgo ♍
)

private data class BrightStar(
    val raHours: Double,
    val decDeg: Double,
    val vmag: Double
)

// --- Planet disk comparison constants ---

private val planetRadiiKm = mapOf(
    "Mercury" to 2439.7,
    "Venus" to 6051.8,
    "Mars" to 3396.2,
    "Jupiter" to 71492.0,
    "Saturn" to 60268.0,
    "Uranus" to 25559.0,
    "Neptune" to 24764.0
)

private val planetDiskColors = mapOf(
    "Mercury" to Color(0xFFB4B4B4),
    "Venus" to Color(0xFFFFFFFF),
    "Mars" to Color(0xFFFFB460),
    "Jupiter" to jovianCreamColor,
    "Saturn" to Color(0xFFC5AB74),
    "Uranus" to Color(0xFFAFEEEE),
    "Neptune" to Color(0xFF4169E1)
)

private data class PlanetDiskInfo(
    val name: String,
    val angularRadiusRad: Double,
    val polarRatio: Double,
    val baseColor: Color,
    val phaseAngleDeg: Double,
    val litFromLeft: Boolean = false,
    val ringTiltB: Double = 0.0,
    val positionAngleP: Double = 0.0,
    val ringAngularRadiusRad: Double = 0.0,
    val subEarthLatDeg: Double = 0.0
)

/**
 * Loads bright star positions and magnitudes from assets.
 * Data generated by scripts/generateBrightStars.py.
 */
private fun loadBrightStars(context: android.content.Context): List<BrightStar> {
    val stars = mutableListOf<BrightStar>()
    context.assets.open("bright_stars.bin").use { raw ->
        BufferedInputStream(raw).use { stream ->
            val headerBuf = ByteArray(2)
            stream.read(headerBuf)
            val starCount = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                .short.toInt() and 0xFFFF

            val buf = ByteArray(12)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until starCount) {
                stream.read(buf)
                bb.rewind()
                stars.add(BrightStar(
                    bb.float.toDouble(), bb.float.toDouble(), bb.float.toDouble()
                ))
            }
        }
    }
    return stars
}

/**
 * Loads precomputed zodiac boundary segments and label positions from assets.
 * Data generated by scripts/generateZodiacMapData.py.
 */
private fun loadZodiacMapData(context: android.content.Context): ZodiacMapData {
    val segments = mutableListOf<MapSegment>()
    val labels = mutableListOf<ZodiacLabel>()
    context.assets.open("zodiac_map_data.bin").use { raw ->
        BufferedInputStream(raw).use { stream ->
            val headerBuf = ByteArray(4)
            stream.read(headerBuf)
            val header = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val segmentCount = header.short.toInt() and 0xFFFF
            val labelCount = header.short.toInt() and 0xFFFF

            val segBuf = ByteArray(16)
            val segBB = ByteBuffer.wrap(segBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until segmentCount) {
                stream.read(segBuf)
                segBB.rewind()
                segments.add(MapSegment(
                    segBB.float.toDouble(), segBB.float.toDouble(),
                    segBB.float.toDouble(), segBB.float.toDouble()
                ))
            }

            val labelBuf = ByteArray(9)
            val labelBB = ByteBuffer.wrap(labelBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until labelCount) {
                stream.read(labelBuf)
                labelBB.rewind()
                val ra = labelBB.float.toDouble()
                val dec = labelBB.float.toDouble()
                val idx = labelBuf[8].toInt() and 0xFF
                val symbol = zodiacIndexToSymbol[idx] ?: continue
                labels.add(ZodiacLabel(ra, dec, symbol))
            }
        }
    }
    return ZodiacMapData(segments, labels)
}

@Composable
fun ConstellationsScreen(
    obs: ObserverState,
    resetAnimTrigger: Int = 0,
    onAnimStoppedChange: (Boolean) -> Unit = {}
) {
    val DISK_JUPITER_POLAR_RATIO = 0.93513  // 1 - 0.06487
    val DISK_SATURN_POLAR_RATIO = 0.902
    val MARS_POLE_RA_DEG = 317.68
    val MARS_POLE_DEC_DEG = 52.89
    val displayEpoch = obs.epochDay; val currentInstant = obs.now
    val lat = obs.lat; val stdOffsetHours = obs.stdOffsetHours
    val stdTimeLabel = obs.stdTimeLabel; val useStandardTime = obs.useStandardTime
    val context = LocalContext.current
    ConstellationBoundary.ensureLoaded(context)
    val marsBitmap = remember {
        context.assets.open("MarsAsset.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }

    // --- ANIMATION STATE ---
    var isAnimating by remember { mutableStateOf(false) }
    var animDayOffset by remember { mutableStateOf(0.0) }

    // Report stopped state to parent; reset on trigger from parent
    LaunchedEffect(isAnimating, animDayOffset) {
        onAnimStoppedChange(!isAnimating && animDayOffset > 0.0)
    }
    LaunchedEffect(resetAnimTrigger) {
        if (resetAnimTrigger > 0) { animDayOffset = 0.0; isAnimating = false }
    }

    // Animation loop: 30 fps, 1/6 day (4 hours) per frame
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            while (isAnimating) {
                animDayOffset += 1.0 / 6.0
                delay(33)
            }
        }
    }

    // Compute effective JD
    val effectiveJD = if (isAnimating || animDayOffset > 0.0) {
        displayEpoch + animDayOffset + UNIX_EPOCH_JD
    } else {
        currentInstant.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    }
    val jd = effectiveJD

    // Date/time string for animation display
    val showDateTime = isAnimating || animDayOffset > 0.0
    val displayOffsetSeconds = if (useStandardTime) (stdOffsetHours * 3600).toLong() else 0L
    val displayZone = ZoneId.ofOffset("", java.time.ZoneOffset.ofTotalSeconds(displayOffsetSeconds.toInt()))
    val timeLabel = if (useStandardTime) stdTimeLabel else "UT"
    val dateStr = if (showDateTime) {
        val millis = Math.round((jd - UNIX_EPOCH_JD) * MILLIS_PER_DAY / 60000.0) * 60000L
        val displayInstant = Instant.ofEpochMilli(millis)
        DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(displayZone).format(displayInstant)
    } else ""
    val timeStr = if (showDateTime) {
        val millis = Math.round((jd - UNIX_EPOCH_JD) * MILLIS_PER_DAY / 60000.0) * 60000L
        val displayInstant = Instant.ofEpochMilli(millis)
        DateTimeFormatter.ofPattern("HH:mm").withZone(displayZone).format(displayInstant) + " $timeLabel"
    } else ""

    val bodies = listOf("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")

    val rows = remember(jd) {
        bodies.map { name ->
            val state = AstroEngine.getBodyState(name, jd)
            val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
            val (b1875Ra, b1875Dec) = precessJ2000ToDate(state.ra, state.dec, B1875_JD)
            val b1875RaHours = normalizeDegrees(b1875Ra) * DEGREES_TO_HOURS
            val constellationName = ConstellationBoundary.findConstellation(b1875RaHours, b1875Dec)
            val symbol = zodiacSymbols[constellationName]
            val displayName = when (constellationName) {
                "Capricornus" -> "Capricorn."
                "Sagittarius" -> "Sagittar."
                else -> constellationName
            }
            val constellation = buildString {
                append(displayName)
                if (symbol != null) append("  $symbol")
            }
            val appRaHours = normalizeDegrees(appRa) * DEGREES_TO_HOURS
            ObjectRow(name, constellation, appRaHours, appDec)
        }
    }

    val mapData = remember { loadZodiacMapData(context) }
    val brightStars = remember { loadBrightStars(context) }

    val obliquity = remember(jd) {
        val t = (jd - J2000_JD) / DAYS_PER_JULIAN_CENTURY
        23.439291 - 0.0130042 * t
    }

    val planetDisks = remember(jd) {
        val sunState = AstroEngine.getBodyState("Sun", jd)
        val earthSunDist = sunState.distGeo
        val sunEclipticLon = sunState.eclipticLon
        val planetNames = listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
        planetNames.map { name ->
            val state = AstroEngine.getBodyState(name, jd)
            val radiusKm = planetRadiiKm[name]!!
            val angularRadiusRad = atan(radiusKm / (state.distGeo * AU_KM))
            val polarRatio = when (name) {
                "Jupiter" -> DISK_JUPITER_POLAR_RATIO
                "Saturn" -> DISK_SATURN_POLAR_RATIO
                else -> 1.0
            }
            val phaseAngleDeg = Math.toDegrees(phaseAngleRad(state.distSun, state.distGeo, earthSunDist))

            // East of Sun → lit limb faces left; west of Sun → lit limb faces right
            val dLon = normalizeDegrees(state.eclipticLon - sunEclipticLon)
            val litFromLeft = dLon > 0.0 && dLon < 180.0

            var ringTiltB = 0.0
            var positionAngleP = 0.0
            var ringAngularRadiusRad = 0.0
            var subEarthLatDeg = 0.0
            if (name == "Saturn") {
                ringTiltB = SaturnMoonEngine.calculateRingTiltB(state.eclipticLon, state.eclipticLat, jd)
                val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
                positionAngleP = SaturnMoonEngine.calculatePositionAngleP(appRa, appDec, jd)
                ringAngularRadiusRad = atan(radiusKm * SaturnMoonEngine.A_RING_OUTER / (state.distGeo * AU_KM))
            }
            if (name == "Mars") {
                // Sub-Earth latitude: angle between Mars' north pole and the Earth-Mars line
                // De = arcsin(sin(poleDec)*sin(marsDec) + cos(poleDec)*cos(marsDec)*cos(poleRa - marsRa))
                val poleRaRad = Math.toRadians(MARS_POLE_RA_DEG)
                val poleDecRad = Math.toRadians(MARS_POLE_DEC_DEG)
                val marsRaRad = Math.toRadians(state.ra)
                val marsDecRad = Math.toRadians(state.dec)
                // Direction from Earth to Mars is opposite to Mars's apparent position
                // Sub-Earth lat = 90° - angular distance between pole and anti-Mars direction
                // Equivalently: De = asin(-sinPoleDec*sinMarsDec - cosPoleDec*cosMarsDec*cos(poleRa-marsRa))
                // But the standard formula uses the direction FROM Mars TO Earth:
                // earthRa = marsRa + 180°, earthDec = -marsDec (in Mars-centric J2000)
                // Simplification: De = asin(sin(poleDec)*sin(-marsDec) + cos(poleDec)*cos(marsDec)*cos(poleRa - (marsRa+PI)))
                val earthFromMarsRa = marsRaRad + PI
                val earthFromMarsDec = -marsDecRad
                subEarthLatDeg = Math.toDegrees(asin(
                    sin(poleDecRad) * sin(earthFromMarsDec) +
                    cos(poleDecRad) * cos(earthFromMarsDec) * cos(poleRaRad - earthFromMarsRa)
                ))
            }
            PlanetDiskInfo(name, angularRadiusRad, polarRatio, baseColor = planetDiskColors[name]!!,
                phaseAngleDeg, litFromLeft, ringTiltB, positionAngleP, ringAngularRadiusRad, subEarthLatDeg)
        }
    }

    val density = LocalDensity.current
    val headerHeightPx = remember { mutableStateOf(0) }
    val spacerBeforeMaps = 8.dp
    val spacerBetweenMaps = 24.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val spacersPx = with(density) { (spacerBeforeMaps + spacerBetweenMaps).toPx() }

        // Compute map width: full width unless both maps won't fit vertically
        val mapWidthDp = if (headerHeightPx.value > 0) {
            val availableForMaps = maxHeightPx - headerHeightPx.value - spacersPx
            val fittedWidthPx = availableForMaps * 1.8f  // 2 maps at aspect 3.6 each
            val mapPx = minOf(maxWidthPx, maxOf(fittedWidthPx, 0f))
            with(density) { mapPx.toDp() }
        } else {
            maxWidth  // First frame before measurement: use full width
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: title + table, measured to compute available map space
            Column(
                modifier = Modifier.onSizeChanged { headerHeightPx.value = it.height },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showDateTime) {
                        Text(
                            text = dateStr,
                            style = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        text = "Constellations",
                        style = TextStyle(color = LabelColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    )
                    if (showDateTime) {
                        Text(
                            text = timeStr,
                            style = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                val pairs = listOf(
                    rows[0] to rows[1],
                    rows[2] to rows[3],
                    rows[4] to rows[5],
                    rows[6] to rows[7],
                    rows[8] to null
                )

                for ((left, right) in pairs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ObjectCell(left, modifier = Modifier.weight(1f))
                        if (right != null) {
                            ObjectCell(right, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacerBeforeMaps))

            // Constellation boundary map
            Canvas(
                modifier = Modifier
                    .width(mapWidthDp)
                    .aspectRatio(3.6f)
            ) {
                drawSkyMap(mapData.segments, mapData.labels, rows, obliquity)
            }

            Spacer(modifier = Modifier.height(spacerBetweenMaps))

            // Star map
            Canvas(
                modifier = Modifier
                    .width(mapWidthDp)
                    .aspectRatio(3.6f)
            ) {
                drawStarMap(brightStars, rows, obliquity)
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Planet disk apparent size comparison
            Canvas(
                modifier = Modifier
                    .width(mapWidthDp)
                    .aspectRatio(1.5f)
            ) {
                drawPlanetDisks(planetDisks, northUp = lat >= 0.0, marsBitmap = marsBitmap)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { isAnimating = !isAnimating },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAnimating) Color.Red else Color.DarkGray,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(if (isAnimating) "Stop" else "Animate", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ObjectCell(row: ObjectRow, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(24.dp)
        ) {
            Text(
                text = row.name,
                style = TextStyle(
                    color = LabelColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    lineHeight = 24.sp
                ),
                maxLines = 1
            )
            Text(
                text = "  ${row.constellation}",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.SansSerif,
                    lineHeight = 24.sp
                ),
                maxLines = 1
            )
        }
    }
}

private fun DrawScope.drawSkyMap(
    segments: List<MapSegment>,
    labels: List<ZodiacLabel>,
    rows: List<ObjectRow>,
    obliquity: Double
) {
    val w = size.width
    val h = size.height

    // Border box
    drawRect(Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))

    val boundaryColor = LabelColor
    val boundaryStroke = 2.25f

    // Draw zodiac boundary segments (J2025.0 coordinates, slightly tilted after precession)
    for (seg in segments) {
        val x1 = raToX(seg.ra1)
        val y1 = decToY(seg.dec1)
        val x2 = raToX(seg.ra2)
        val y2 = decToY(seg.dec2)
        if (abs(x1 - x2) <= w * 0.5f) {
            drawLine(boundaryColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = boundaryStroke)
        } else {
            // Segment crosses RA=12h map edge — draw as two pieces
            val nearLeft: Offset
            val nearRight: Offset
            if (x1 < x2) {
                nearLeft = Offset(x1, y1); nearRight = Offset(x2, y2)
            } else {
                nearLeft = Offset(x2, y2); nearRight = Offset(x1, y1)
            }
            val dx = nearLeft.x + w - nearRight.x
            val tRight = (w - nearRight.x) / dx
            val yEdge = nearRight.y + tRight * (nearLeft.y - nearRight.y)
            drawLine(boundaryColor, nearRight, Offset(w, yEdge), strokeWidth = boundaryStroke)
            drawLine(boundaryColor, Offset(0f, yEdge), nearLeft, strokeWidth = boundaryStroke)
        }
    }

    // Celestial equator (Dec=0) — white horizontal
    val eqY = decToY(0.0)
    drawLine(Color.White, Offset(0f, eqY), Offset(w, eqY), strokeWidth = 1f)

    // Vernal Equinox (RA=0) — white vertical
    val veX = raToX(0.0)
    drawLine(Color.White, Offset(veX, 0f), Offset(veX, h), strokeWidth = 1f)

    drawEcliptic(obliquity)

    // Zodiac constellation symbols in white
    val zodiacPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 36f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    // Planet symbols in green
    val symbolPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.GREEN
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val fontMetrics = symbolPaint.fontMetrics
    val textVerticalOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f

    // Ophiuchus label — no Unicode symbol, so draw name in white at planet symbol size
    val ophiuchusPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    drawContext.canvas.nativeCanvas.let { canvas ->
        for (label in labels) {
            val x = raToX(label.raHours)
            val y = decToY(label.decDeg - 3.0)
            canvas.drawText(label.symbol, x, y, zodiacPaint)
        }
        // Ophiuchus text at RA 18h15m, Dec +9°30'
        canvas.drawText("Ophiuchus", raToX(17.75), decToY(18.0), ophiuchusPaint)
        for (row in rows) {
            if (row.appDecDeg < DEC_MIN || row.appDecDeg > DEC_MAX) continue
            val x = raToX(row.appRaHours)
            val y = decToY(row.appDecDeg)
            val symbol = planetSymbols[row.name] ?: continue
            canvas.drawText(symbol, x, y + textVerticalOffset, symbolPaint)
        }
    }
}

private fun DrawScope.drawStarMap(
    stars: List<BrightStar>,
    rows: List<ObjectRow>,
    obliquity: Double
) {
    val w = size.width
    val h = size.height

    // Border box
    drawRect(Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))

    // Stars — size and brightness scale with magnitude
    // Magnitude range: ~-1.5 (Sirius) to 3.0
    val magMin = -1.5  // brightest
    val magMax = 3.5   // faintest
    val magRange = magMax - magMin
    val maxRadius = 5f
    val minRadius = 1f

    for (star in stars) {
        if (star.decDeg < DEC_MIN || star.decDeg > DEC_MAX) continue
        val x = raToX(star.raHours)
        val y = decToY(star.decDeg)
        val t = ((star.vmag - magMin) / magRange).coerceIn(0.0, 1.0)
        val radius = (maxRadius + (minRadius - maxRadius) * t).toFloat()
        val shade = (255 * (1.0 - t * 0.6)).toInt()  // brightest=255, faintest=102
        drawCircle(
            color = Color(shade, shade, shade),
            radius = radius,
            center = Offset(x, y)
        )
    }

    // Celestial equator (Dec=0)
    val eqY = decToY(0.0)
    drawLine(Color.White, Offset(0f, eqY), Offset(w, eqY), strokeWidth = 1f)

    // Vernal Equinox (RA=0)
    val veX = raToX(0.0)
    drawLine(Color.White, Offset(veX, 0f), Offset(veX, h), strokeWidth = 1f)

    drawEcliptic(obliquity)

    // Planet symbols in green
    val symbolPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.GREEN
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val fontMetrics = symbolPaint.fontMetrics
    val textVerticalOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f

    drawContext.canvas.nativeCanvas.let { canvas ->
        for (row in rows) {
            if (row.appDecDeg < DEC_MIN || row.appDecDeg > DEC_MAX) continue
            val x = raToX(row.appRaHours)
            val y = decToY(row.appDecDeg)
            val symbol = planetSymbols[row.name] ?: continue
            canvas.drawText(symbol, x, y + textVerticalOffset, symbolPaint)
        }
    }
}

// ==========================================================================
// Planet disk apparent size comparison drawing
// ==========================================================================

private const val RULER_ARCSEC = 100.0
private const val RULER_TICK_ARCSEC = 20.0

private fun DrawScope.drawPlanetDisks(disks: List<PlanetDiskInfo>, northUp: Boolean, marsBitmap: ImageBitmap) {
    val RAD_TO_ARCSEC = 180.0 * 3600.0 / PI
    val ROW1_MAX_RADIUS_ARCSEC = 32.5       // Venus at inferior conjunction (~0.26 AU)
    val SATURN_MAX_RING_RADIUS_ARCSEC = 24.0 // A ring outer at ~8.0 AU
    val w = size.width
    val h = size.height

    val row1 = listOf(disks[0], disks[1], disks[2], disks[3]) // Mercury, Venus, Mars, Jupiter
    val row2 = listOf(disks[4], disks[5], disks[6])           // Saturn, Uranus, Neptune

    // Fixed scale: 100 arcsec ruler = 45% of canvas width (does not change with time)
    val rulerLengthPx = w * 0.45f
    val pxPerArcsec = rulerLengthPx / RULER_ARCSEC.toFloat()
    val pxPerRad = (pxPerArcsec * RAD_TO_ARCSEC).toFloat()

    val cellWidth = w / 4f
    val row1MaxPx = (ROW1_MAX_RADIUS_ARCSEC * pxPerArcsec).toFloat()
    val saturnRingMaxPx = (SATURN_MAX_RING_RADIUS_ARCSEC * pxPerArcsec).toFloat()

    val namePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 22f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val symbolPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 44f
        textAlign = android.graphics.Paint.Align.LEFT
        isAntiAlias = true
    }
    val sizePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.GRAY
        textSize = 18f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val labelHeight = namePaint.textSize + sizePaint.textSize + 4f

    // Row 1 center: Jupiter at max radius just touches top of canvas
    val row1CenterY = row1MaxPx
    val row1LabelTop = row1CenterY + row1MaxPx + 2f

    // Row 2 center: below row 1 labels, with room for Saturn's rings above center
    val row2CenterY = row1LabelTop + labelHeight + saturnRingMaxPx + 2f
    val row2LabelTop = row2CenterY + saturnRingMaxPx + 2f

    val rulerAreaHeight = 36f

    // Draw row 1: Mercury, Venus, Mars, Jupiter
    for ((i, disk) in row1.withIndex()) {
        val cx = cellWidth * (i + 0.5f)
        drawSinglePlanet(disk, cx, row1CenterY, pxPerRad, northUp, marsBitmap)

        val arcsec = Math.toDegrees(disk.angularRadiusRad) * 2.0 * 3600.0
        val symbol = planetSymbols[disk.name] ?: ""
        val nameY = row1LabelTop + namePaint.textSize
        drawContext.canvas.nativeCanvas.let { canvas ->
            val nameWidth = namePaint.measureText(disk.name + " ")
            val symbolWidth = symbolPaint.measureText(symbol)
            val totalWidth = nameWidth + symbolWidth
            val startX = cx - totalWidth / 2f
            namePaint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText(disk.name + " ", startX, nameY, namePaint)
            canvas.drawText(symbol, startX + nameWidth, nameY, symbolPaint)
            namePaint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("%.1f\"".format(arcsec), cx,
                nameY + sizePaint.textSize + 2f, sizePaint)
        }
    }

    // Draw row 2: Saturn, Uranus, Neptune (centered)
    val row2Offset = (w - 3 * cellWidth) / 2f
    for ((i, disk) in row2.withIndex()) {
        val cx = row2Offset + cellWidth * (i + 0.5f)
        drawSinglePlanet(disk, cx, row2CenterY, pxPerRad, northUp, marsBitmap)

        val arcsec = Math.toDegrees(disk.angularRadiusRad) * 2.0 * 3600.0
        val symbol = planetSymbols[disk.name] ?: ""
        val nameY = row2LabelTop + namePaint.textSize
        drawContext.canvas.nativeCanvas.let { canvas ->
            val nameWidth = namePaint.measureText(disk.name + " ")
            val symbolWidth = symbolPaint.measureText(symbol)
            val totalWidth = nameWidth + symbolWidth
            val startX = cx - totalWidth / 2f
            namePaint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText(disk.name + " ", startX, nameY, namePaint)
            canvas.drawText(symbol, startX + nameWidth, nameY, symbolPaint)
            namePaint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("%.1f\"".format(arcsec), cx,
                nameY + sizePaint.textSize + 2f, sizePaint)
        }
    }

    // Scale ruler at the bottom
    drawScaleRuler(w, h, rulerAreaHeight, pxPerArcsec)
}

private fun DrawScope.drawScaleRuler(
    canvasWidth: Float, canvasHeight: Float,
    rulerAreaHeight: Float, pxPerArcsec: Float
) {
    val rulerLengthPx = (RULER_ARCSEC * pxPerArcsec).toFloat()
    val rulerX = (canvasWidth - rulerLengthPx) / 2f
    val rulerY = canvasHeight - rulerAreaHeight + 4f
    val tickHeight = 8f

    // Main horizontal line
    drawLine(Color.White, Offset(rulerX, rulerY), Offset(rulerX + rulerLengthPx, rulerY), strokeWidth = 1.5f)

    // Tick marks and labels
    val tickPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 16f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val numTicks = (RULER_ARCSEC / RULER_TICK_ARCSEC).toInt()
    for (i in 0..numTicks) {
        val arcsec = i * RULER_TICK_ARCSEC
        val x = rulerX + (arcsec * pxPerArcsec).toFloat()
        drawLine(Color.White, Offset(x, rulerY - tickHeight / 2), Offset(x, rulerY + tickHeight / 2), strokeWidth = 1.5f)
        val label = "${arcsec.toInt()}\""
        drawContext.canvas.nativeCanvas.drawText(label, x, rulerY + tickHeight / 2 + tickPaint.textSize + 2f, tickPaint)
    }
}

private fun DrawScope.drawSinglePlanet(
    disk: PlanetDiskInfo, cx: Float, cy: Float, pxPerRad: Float, northUp: Boolean, marsBitmap: ImageBitmap
) {
    val eqRadiusPx = (disk.angularRadiusRad * pxPerRad).toFloat()
    val polarRadiusPx = eqRadiusPx * disk.polarRatio.toFloat()

    // Southern hemisphere: 180° rotation flips left/right and north/south
    val litFromLeft = if (northUp) disk.litFromLeft else !disk.litFromLeft
    val subEarthLat = if (northUp) disk.subEarthLatDeg else -disk.subEarthLatDeg
    val d = if (northUp) disk else disk.copy(
        litFromLeft = litFromLeft,
        positionAngleP = disk.positionAngleP + 180.0
    )

    when (disk.name) {
        "Saturn" -> drawSaturnWithRings(d, cx, cy, eqRadiusPx, polarRadiusPx)
        "Jupiter" -> drawJupiterDisk(cx, cy, eqRadiusPx, polarRadiusPx, disk.phaseAngleDeg, litFromLeft, northUp)
        "Mars" -> drawMarsDisk(cx, cy, eqRadiusPx, disk.phaseAngleDeg, litFromLeft, marsBitmap)
        else -> drawSimpleDisk(cx, cy, eqRadiusPx, disk.baseColor, disk.phaseAngleDeg, litFromLeft)
    }
}

/**
 * Draws a limb-darkened circular disk with phase shadow.
 */
private fun DrawScope.drawSimpleDisk(
    cx: Float, cy: Float, radiusPx: Float, color: Color,
    phaseAngleDeg: Double, litFromLeft: Boolean
) {
    val limbSteps = maxOf(10, (radiusPx / 2).toInt())
    val limbU = 0.4f
    for (i in 0 until limbSteps) {
        val r = 1.0f - i.toFloat() / limbSteps
        val cosTheta = sqrt(1.0f - r * r)
        val brightness = 1.0f - limbU * (1.0f - cosTheta)
        val c = Color(color.red * brightness, color.green * brightness, color.blue * brightness)
        drawCircle(c, radius = radiusPx * r, center = Offset(cx, cy))
    }
    if (phaseAngleDeg > 2.0) {
        drawPhaseShadow(cx, cy, radiusPx, radiusPx, phaseAngleDeg, litFromLeft)
    }
}

/**
 * Draws Mars using a photo bitmap with limb darkening and phase shadow.
 */
private fun DrawScope.drawMarsDisk(
    cx: Float, cy: Float, radiusPx: Float,
    phaseAngleDeg: Double, litFromLeft: Boolean, bitmap: ImageBitmap
) {
    // Draw the photo bitmap scaled to the disk size, clipped to a circle
    val diskClip = Path().apply {
        addOval(Rect(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx))
    }
    clipPath(diskClip) {
        val diameter = (radiusPx * 2).toInt()
        drawImage(
            bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset((cx - radiusPx).toInt(), (cy - radiusPx).toInt()),
            dstSize = IntSize(diameter, diameter)
        )
    }

    // Limb darkening overlay — draw as rings so each band only darkens its own annulus
    val limbSteps = maxOf(10, (radiusPx / 2).toInt())
    val limbU = 0.4f
    for (i in 0 until limbSteps) {
        val rOuter = 1.0f - i.toFloat() / limbSteps
        val rInner = 1.0f - (i + 1).toFloat() / limbSteps
        val rMid = (rOuter + rInner) / 2f
        val cosTheta = sqrt(1.0f - rMid * rMid)
        val darkening = limbU * (1.0f - cosTheta)
        if (darkening > 0.01f) {
            val strokeWidth = (rOuter - rInner) * radiusPx
            val strokeRadius = (rOuter + rInner) / 2f * radiusPx
            drawCircle(
                Color.Black.copy(alpha = darkening),
                radius = strokeRadius,
                center = Offset(cx, cy),
                style = Stroke(width = strokeWidth)
            )
        }
    }

    // Phase shadow
    if (phaseAngleDeg > 2.0) {
        drawPhaseShadow(cx, cy, radiusPx, radiusPx, phaseAngleDeg, litFromLeft)
    }
}

/**
 * Draws Jupiter as an oblate limb-darkened ellipse with realistic atmospheric bands.
 * Latitudes are planetographic: positive = north, 0 = equator.
 * Band data approximates visual appearance: dark belts and bright zones.
 */
private data class JovianBand(
    val latSouth: Float, val latNorth: Float, val color: Color,
    val featherScale: Float = 1f
)

private val jovianBands = listOf(
    // South polar region — extra feathering on equator-facing edge
    JovianBand(-90f, -58f, Color(0x28382818), featherScale = 3f),
    // South equatorial belt
    JovianBand(-24f,  -7f, Color(0x30502808)),
    // Equatorial zone — warm bright band
    JovianBand( -7f,   7f, Color(0x10FFC850)),
    // North equatorial belt
    JovianBand(  7f,  22f, Color(0x2C482408)),
    // North polar region — extra feathering on equator-facing edge
    JovianBand( 58f,  90f, Color(0x28382818), featherScale = 3f),
)

private fun DrawScope.drawJupiterDisk(
    cx: Float, cy: Float, eqRadiusPx: Float, polarRadiusPx: Float,
    phaseAngleDeg: Double, litFromLeft: Boolean, northUp: Boolean = true
) {
    // ySign: +1 = north up (negative lat at bottom), -1 = south up (flipped)
    val ySign = if (northUp) 1f else -1f
    val color = jovianCreamColor
    val limbSteps = maxOf(10, (eqRadiusPx / 2).toInt())
    val limbU = 0.4f
    for (i in 0 until limbSteps) {
        val r = 1.0f - i.toFloat() / limbSteps
        val cosTheta = sqrt(1.0f - r * r)
        val brightness = 1.0f - limbU * (1.0f - cosTheta)
        val c = Color(color.red * brightness, color.green * brightness, color.blue * brightness)
        drawOval(
            c,
            topLeft = Offset(cx - eqRadiusPx * r, cy - polarRadiusPx * r),
            size = Size(eqRadiusPx * 2 * r, polarRadiusPx * 2 * r)
        )
    }

    // Atmospheric bands clipped to the oblate disk
    val diskClip = Path().apply {
        addOval(Rect(
            cx - eqRadiusPx, cy - polarRadiusPx,
            cx + eqRadiusPx, cy + polarRadiusPx
        ))
    }
    clipPath(diskClip) {
        val featherPx = polarRadiusPx * 0.08f  // soft edge width
        for (band in jovianBands) {
            val y1 = cy - polarRadiusPx * ySign * (band.latNorth / 90f)
            val y2 = cy - polarRadiusPx * ySign * (band.latSouth / 90f)
            val yTop = minOf(y1, y2)
            val yBot = maxOf(y1, y2)
            val bandH = yBot - yTop
            // Main band
            drawRect(band.color, topLeft = Offset(cx - eqRadiusPx, yTop),
                size = Size(eqRadiusPx * 2, bandH))
            // Feathered edges — polar caps get wider feathering via featherScale
            val f = featherPx * band.featherScale
            if (f > 0.5f) {
                val fadeColor = band.color.copy(alpha = 0f)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(fadeColor, band.color),
                        startY = yTop - f, endY = yTop + f
                    ),
                    topLeft = Offset(cx - eqRadiusPx, yTop - f),
                    size = Size(eqRadiusPx * 2, f * 2)
                )
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(band.color, fadeColor),
                        startY = yBot - f, endY = yBot + f
                    ),
                    topLeft = Offset(cx - eqRadiusPx, yBot - f),
                    size = Size(eqRadiusPx * 2, f * 2)
                )
            }
        }
    }

    if (phaseAngleDeg > 2.0) {
        drawPhaseShadow(cx, cy, eqRadiusPx, polarRadiusPx, phaseAngleDeg, litFromLeft)
    }
}

/**
 * Draws Saturn with oblate disk and rings at the correct tilt and orientation.
 */
private fun DrawScope.drawSaturnWithRings(
    disk: PlanetDiskInfo, cx: Float, cy: Float,
    eqRadiusPx: Float, polarRadiusPx: Float
) {
    val tiltB = disk.ringTiltB
    val sinB = abs(sin(Math.toRadians(tiltB))).toFloat()
    val posAngleP = disk.positionAngleP

    val cosP = cos(Math.toRadians(-posAngleP)).toFloat()
    val sinP = sin(Math.toRadians(-posAngleP)).toFloat()

    // Transform from Saturn-frame coordinates to screen coordinates
    // Negate y before rotation: Meeus y-positive = north, screen y-positive = down
    fun toScreen(x: Float, y: Float): Offset {
        val fy = -y
        val rx = x * cosP - fy * sinP
        val ry = x * sinP + fy * cosP
        return Offset(cx + rx, cy + ry)
    }

    val colorRingA = Color.LightGray
    val colorRingB = Color.White
    val colorRingC = Color.Gray.copy(alpha = 0.50f)
    val colorSaturn = disk.baseColor

    // Limb-darkened oblate disk
    fun drawDisk() {
        val limbSteps = maxOf(10, (eqRadiusPx / 2).toInt())
        val limbU = 0.4f
        for (i in 0 until limbSteps) {
            val r = 1.0f - i.toFloat() / limbSteps
            val cosTheta = sqrt(1.0f - r * r)
            val brightness = 1.0f - limbU * (1.0f - cosTheta)
            val c = Color(
                colorSaturn.red * brightness,
                colorSaturn.green * brightness,
                colorSaturn.blue * brightness
            )
            drawPath(buildDiskEllipsePath(eqRadiusPx * r, polarRadiusPx * r, ::toScreen), c)
        }
    }

    if (sinB * eqRadiusPx < 0.5f) {
        // Rings edge-on — just draw disk
        drawDisk()
        return
    }

    // Back/front classification: B > 0 means north pole visible, back = north half
    val backIsNorthHalf = tiltB > 0

    // Back ring halves
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.C_RING_OUTER.toFloat(),
        SaturnMoonEngine.C_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen), colorRingC)
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.A_RING_OUTER.toFloat(),
        SaturnMoonEngine.A_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen), colorRingA)
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.B_RING_OUTER.toFloat(),
        SaturnMoonEngine.B_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen), colorRingB)

    // Saturn disk (covers back rings where it overlaps)
    drawDisk()

    // Front ring halves
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.C_RING_OUTER.toFloat(),
        SaturnMoonEngine.C_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingC)
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.A_RING_OUTER.toFloat(),
        SaturnMoonEngine.A_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingA)
    drawPath(buildDiskRingHalfPath(eqRadiusPx, SaturnMoonEngine.B_RING_OUTER.toFloat(),
        SaturnMoonEngine.B_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingB)
}

/**
 * Draws a phase shadow overlay.
 * litFromLeft=true: illuminated limb on the left, shadow on the right.
 * litFromLeft=false: illuminated limb on the right, shadow on the left.
 */
private fun DrawScope.drawPhaseShadow(
    cx: Float, cy: Float,
    eqRadiusPx: Float, polarRadiusPx: Float,
    phaseAngleDeg: Double, litFromLeft: Boolean
) {
    val phaseRad = Math.toRadians(phaseAngleDeg)
    val cosPhase = cos(phaseRad).toFloat()
    // flip = +1 → shadow on left (lit from right); -1 → shadow on right (lit from left)
    val flip = if (litFromLeft) -1f else 1f

    val path = Path()
    val steps = 60

    // Shadow-side limb arc: from top to bottom through the shadow side
    for (k in 0..steps) {
        val theta = PI / 2 + flip * PI * k / steps
        val x = cx + eqRadiusPx * cos(theta).toFloat()
        val y = cy - polarRadiusPx * sin(theta).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // Terminator arc: from bottom back to top
    for (k in 0..steps) {
        val alpha = -PI / 2 + PI * k / steps
        val x = cx - flip * cosPhase * eqRadiusPx * cos(alpha).toFloat()
        val y = cy - polarRadiusPx * sin(alpha).toFloat()
        path.lineTo(x, y)
    }

    path.close()
    drawPath(path, Color.Black.copy(alpha = 0.85f))
}

/**
 * Builds a closed ellipse path through a toScreen transform (for Saturn's position angle rotation).
 */
private fun buildDiskEllipsePath(
    radiusX: Float, radiusY: Float,
    toScreen: (Float, Float) -> Offset
): Path {
    val path = Path()
    val steps = 80
    for (k in 0..steps) {
        val theta = 2 * PI * k / steps
        val screen = toScreen((radiusX * cos(theta)).toFloat(), (radiusY * sin(theta)).toFloat())
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    path.close()
    return path
}

/**
 * Builds a half-annulus path for one ring half, in Saturn equatorial radii scaled to pixels.
 */
private fun buildDiskRingHalfPath(
    pxPerRadius: Float,
    outerRadii: Float, innerRadii: Float,
    sinB: Float, isNorthHalf: Boolean,
    toScreen: (Float, Float) -> Offset
): Path {
    val outerW = outerRadii * pxPerRadius
    val outerH = outerRadii * pxPerRadius * sinB
    val innerW = innerRadii * pxPerRadius
    val innerH = innerRadii * pxPerRadius * sinB

    val path = Path()
    val steps = 80
    val startAngle = if (isNorthHalf) 0.0 else PI

    // Outer arc
    for (k in 0..steps) {
        val theta = startAngle + PI * k / steps
        val screen = toScreen((outerW * cos(theta)).toFloat(), (outerH * sin(theta)).toFloat())
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    // Inner arc (reverse direction)
    for (k in steps downTo 0) {
        val theta = startAngle + PI * k / steps
        val screen = toScreen((innerW * cos(theta)).toFloat(), (innerH * sin(theta)).toFloat())
        path.lineTo(screen.x, screen.y)
    }
    path.close()
    return path
}
