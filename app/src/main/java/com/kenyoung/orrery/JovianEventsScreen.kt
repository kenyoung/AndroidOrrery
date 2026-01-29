package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

// --- DATA CLASSES ---

enum class JovEventPixel {
    VISIBLE, // Green
    HIDDEN,  // Gray (Sun up or Jupiter down)
    NEXT     // Red (Next visible event)
}

data class JovianEventItem(
    val mjd: Double,
    val text: String,
    val type: JovEventPixel,
    val isSimultaneousAlert: Boolean = false
)

data class MoonInstantState(
    val transit: Boolean,
    val occultation: Boolean,
    val shadowTransit: Boolean,
    val eclipse: Boolean
)

data class MoonVisualState(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val shadowX: Double,
    val shadowY: Double,
    val shadowOnDisk: Boolean,
    val eclipsed: Boolean,
    val color: Color
)

private data class MoonCompleteState(
    val x: Double, val y: Double, val z: Double,
    val shadowX: Double, val shadowY: Double,
    val isTransit: Boolean, val isOccultation: Boolean,
    val isShadowTransit: Boolean, val isEclipse: Boolean
)

private data class RawEvent(
    val mjd: Double,
    val text: String,
    val moonName: String,
    val typeId: Int,
    val isStart: Boolean
)

// Helper to decouple from JovianPrecision's Vector3 definition
private data class LocalVector3(val x: Double, val y: Double, val z: Double)

// --- COMPOSABLE ---

@Composable
fun JovianEventsScreen(currentEpochDay: Double, currentInstant: Instant, lat: Double, lon: Double) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val bgColor = Color.Black

    val utcDate = currentInstant.atZone(ZoneId.of("UTC")).toLocalDate()
    val startMJD = utcDate.toEpochDay() + 40587.0
    val nowMJD = (currentInstant.toEpochMilli() / 86400000.0) + 2440587.5 - 2400000.5

    var eventList by remember { mutableStateOf<List<JovianEventItem>?>(null) }
    var visualState by remember { mutableStateOf<List<MoonVisualState>?>(null) }

    LaunchedEffect(startMJD, currentInstant, lat, lon) {
        withContext(Dispatchers.Default) {
            try {
                eventList = generateJovianEvents(startMJD, currentInstant, lat, lon)
                visualState = calculateVisualState(nowMJD)
            } catch (e: Exception) {
                e.printStackTrace()
                eventList = emptyList()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // --- TOP: System Diagram ---
        // Reduced height to 60.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color.Black)
        ) {
            if (visualState != null) {
                JovianSystemDiagram(visualState!!)
            } else {
                Text("Loading...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        }

        // --- BOTTOM: Event List ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val list = eventList
            if (list == null) {
                Text(
                    "Calculating events...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (list.isEmpty()) {
                Text(
                    "No events found or calculation error.",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val rowHeight = 55f
                val headerHeight = 60f
                val dateHeaderHeight = 60f
                val textSizeContent = 40f
                val headerYellow = Color(0xFFFFFFE0)
                val dateYellow = Color(0xFFFFFFE0)
                val textGray = Color.Gray
                val textGreen = Color(0xFF00FF00)
                val textRed = Color.Red

                val distinctDates = list
                    .filter { !it.isSimultaneousAlert }
                    .map { mjdToLocalDate(it.mjd).toEpochDay() }
                    .distinct()
                    .count()

                val totalHeightPx = headerHeight + (distinctDates * dateHeaderHeight) + (list.size * rowHeight) + 100f
                val totalHeightDp = with(density) { totalHeightPx.toDp() }

                Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(totalHeightDp)) {
                        val w = size.width

                        // Paints
                        // Restored Font Size to 48f
                        val titlePaint = Paint().apply { color = headerYellow.toArgb(); textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                        val dateHeaderPaint = Paint().apply { color = dateYellow.toArgb(); textSize = 36f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }

                        val paintGray = Paint().apply { color = textGray.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }
                        val paintGreen = Paint().apply { color = textGreen.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }
                        val paintRed = Paint().apply { color = textRed.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }

                        // Adjusted Y to 50f for the larger font baseline
                        var currentY = 50f

                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText("Galilean Moon Events", w/2, currentY, titlePaint)
                            currentY += 50f

                            var lastEpochDay = -99999L
                            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                            val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

                            for (item in list) {
                                if (!item.isSimultaneousAlert) {
                                    val itemDate = mjdToLocalDate(item.mjd)
                                    val itemEpochDay = itemDate.toEpochDay()
                                    if (itemEpochDay != lastEpochDay) {
                                        currentY += dateHeaderHeight
                                        canvas.nativeCanvas.drawText(dateFormatter.format(itemDate), 20f, currentY, dateHeaderPaint)
                                        currentY += 20f
                                        lastEpochDay = itemEpochDay
                                    }
                                }

                                currentY += rowHeight

                                val activePaint = when(item.type) {
                                    JovEventPixel.HIDDEN -> paintGray
                                    JovEventPixel.VISIBLE -> paintGreen
                                    JovEventPixel.NEXT -> paintRed
                                }

                                val displayString = if (item.isSimultaneousAlert) {
                                    item.text
                                } else {
                                    val instant = mjdToInstant(item.mjd)
                                    val timeStr = instant.atZone(ZoneId.of("UTC")).format(timeFormatter)
                                    "$timeStr UT, ${item.text}"
                                }

                                canvas.nativeCanvas.drawText(displayString, 40f, currentY, activePaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- DIAGRAM COMPOSABLE ---

@Composable
fun JovianSystemDiagram(moons: List<MoonVisualState>) {
    val creamColor = Color(0xFFFDEEBD)
    val tanColor = Color(0xFFD2B48C)
    val shadowColor = Color.Black

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val currentY = h / 2f

        // Scale: Fit +/- 32 Jupiter Radii
        val safeWidth = if (size.width > 0) size.width else 1000f
        val maxElongationRadii = 32.0
        val topScalePxPerRad = ((safeWidth * 1.15f) / (2 * maxElongationRadii)).toFloat()

        val jFlat = 15f / 16f
        val jW = topScalePxPerRad * 2f
        val jH = jW * jFlat

        val flipX = 1f
        val flipY = -1f

        data class DrawOp(val z: Double, val draw: DrawScope.() -> Unit)
        val drawList = mutableListOf<DrawOp>()

        // 1. Jupiter
        drawList.add(DrawOp(0.0) {
            drawOval(creamColor, topLeft = Offset(centerX - jW / 2, currentY - jH / 2), size = Size(jW, jH))
            val bandThickness = jH / 10f
            val bandWidth = jW * 0.8f
            val bandXOffset = jW * 0.1f
            val band1Top = currentY - jH / 4 - bandThickness / 2
            val band2Top = currentY + jH / 4 - bandThickness / 2

            drawRect(tanColor, topLeft = Offset(centerX - jW / 2 + bandXOffset, band1Top), size = Size(bandWidth, bandThickness))
            drawRect(tanColor, topLeft = Offset(centerX - jW / 2 + bandXOffset, band2Top), size = Size(bandWidth, bandThickness))
        })

        val mSize = 7.5f
        val mHalf = mSize / 2f

        moons.forEach { moon ->
            if (moon.shadowOnDisk) {
                if (!moon.shadowX.isNaN() && !moon.shadowY.isNaN()) {
                    drawList.add(DrawOp(0.1) {
                        val sx = centerX + (moon.shadowX * topScalePxPerRad * flipX).toFloat()
                        val sy = currentY + (moon.shadowY * topScalePxPerRad * flipY).toFloat()
                        drawOval(shadowColor, topLeft = Offset(sx - mHalf, sy - mHalf), size = Size(mSize, mSize))
                    })
                }
            }
            if (!moon.eclipsed) {
                if (!moon.x.isNaN() && !moon.y.isNaN()) {
                    drawList.add(DrawOp(moon.z) {
                        val mx = centerX + (moon.x * topScalePxPerRad * flipX).toFloat()
                        val my = currentY + (moon.y * topScalePxPerRad * flipY).toFloat()
                        drawRect(moon.color, topLeft = Offset(mx - mHalf, my - mHalf), size = Size(mSize, mSize))
                    })
                }
            }
        }

        drawList.sortBy { it.z }
        drawList.forEach { it.draw(this) }
    }
}

// --- GENERATION LOGIC ---

private suspend fun generateJovianEvents(startMJD: Double, nowInstant: Instant, lat: Double, lon: Double): List<JovianEventItem> {
    val nowMJD = (nowInstant.toEpochMilli() / 86400000.0) + 2440587.5 - 2400000.5
    val endMJD = startMJD + 2.0
    val stepSize = 1.0 / 1440.0
    val rawEvents = mutableListOf<RawEvent>()

    var tMJD = startMJD
    var prevState = getSystemStateMJD(tMJD)
    val totalSteps = ((endMJD - startMJD) / stepSize).toInt()

    for (step in 0 until totalSteps) {
        val nextMJD = tMJD + stepSize
        val currState = getSystemStateMJD(nextMJD)
        val moons = listOf("Io", "Europa", "Ganymede", "Callisto")
        for (m in moons) {
            val p = prevState[m]!!; val c = currState[m]!!
            if (!p.transit && c.transit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 0), "$m begins transit of Jupiter", m, 0, true))
            if (p.transit && !c.transit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 0), "$m ends transit of Jupiter", m, 0, false))
            if (!p.shadowTransit && c.shadowTransit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 1), "$m's shadow begins to cross Jupiter", m, 1, true))
            if (p.shadowTransit && !c.shadowTransit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 1), "$m's shadow leaves Jupiter's disk", m, 1, false))
            if (!p.occultation && c.occultation) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 2), "$m enters occultation by Jupiter", m, 2, true))
            if (p.occultation && !c.occultation) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 2), "$m exits occultation by Jupiter", m, 2, false))
            if (!p.eclipse && c.eclipse) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 3), "$m eclipsed by Jupiter's shadow", m, 3, true))
            if (p.eclipse && !c.eclipse) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 3), "$m exits eclipse by Jupiter's shadow", m, 3, false))
        }
        prevState = currState
        tMJD = nextMJD
    }

    val sortedRaw = rawEvents.sortedBy { it.mjd }
    val finalItems = mutableListOf<JovianEventItem>()
    var nextEventFound = false

    for (raw in sortedRaw) {
        val jd = raw.mjd + 2400000.5
        val (_, _, sunAlt) = getAltAz(jd, "Sun", lat, lon)
        val (_, _, jupAlt) = getAltAz(jd, "Jupiter", lat, lon)
        val isVisible = (sunAlt < -0.833) && (jupAlt > 0.0)
        var pixelType = if (isVisible) JovEventPixel.VISIBLE else JovEventPixel.HIDDEN

        if (raw.mjd > nowMJD && isVisible && !nextEventFound) {
            pixelType = JovEventPixel.NEXT
            nextEventFound = true
        }
        finalItems.add(JovianEventItem(raw.mjd, raw.text, pixelType, false))

        if (raw.isStart) {
            val checkMJD = raw.mjd + (1.0 / 86400.0)
            val checkState = getSystemStateMJD(checkMJD)
            val transitCount = checkState.values.count { it.transit }
            val shadowCount = checkState.values.count { it.shadowTransit }
            val occCount = checkState.values.count { it.occultation }

            if (raw.typeId == 0 && transitCount > 1) finalItems.add(JovianEventItem(raw.mjd, "*** There are now $transitCount moons transiting! ***", pixelType, true))
            if (raw.typeId == 1 && shadowCount > 1) finalItems.add(JovianEventItem(raw.mjd, "*** There are now $shadowCount shadows transiting! ***", pixelType, true))
            if (raw.typeId == 2 && occCount > 1) finalItems.add(JovianEventItem(raw.mjd, "*** There are now $occCount moons occulted! ***", pixelType, true))
        }
    }
    return finalItems
}

private fun calculateVisualState(mjd: Double): List<MoonVisualState> {
    val systemState = getCompleteSystemState(mjdToJD(mjd))
    val colors = mapOf(
        "Io" to Color.Red,
        "Europa" to Color(0xFF00FF00),
        "Ganymede" to Color(0xFFADD8E6),
        "Callisto" to Color(0xFFFFFF00)
    )

    return systemState.map { (name, state) ->
        MoonVisualState(
            name = name,
            x = state.x, y = state.y, z = state.z,
            shadowX = state.shadowX, shadowY = state.shadowY,
            shadowOnDisk = state.isShadowTransit,
            eclipsed = state.isEclipse,
            color = colors[name] ?: Color.White
        )
    }
}

// --- MATH HELPERS (MJD AWARE) ---

private fun mjdToJD(mjd: Double): Double = mjd + 2400000.5

private fun mjdToLocalDate(mjd: Double): LocalDate {
    val unixDay = (mjd - 40587.0).toLong()
    return LocalDate.ofEpochDay(unixDay)
}

private fun mjdToInstant(mjd: Double): Instant {
    val unixDay = (mjd - 40587.0).toLong()
    val fracDay = mjd - floor(mjd)
    val nanos = (fracDay * 86_400_000_000_000L).toLong()
    val date = LocalDate.ofEpochDay(unixDay)
    val time = java.time.LocalTime.ofNanoOfDay(nanos)
    return java.time.LocalDateTime.of(date, time).toInstant(java.time.ZoneOffset.UTC)
}

private fun getSystemStateMJD(mjd: Double): Map<String, MoonInstantState> {
    return getSystemState(mjdToJD(mjd))
}

fun getSystemState(jd: Double): Map<String, MoonInstantState> {
    val completeState = getCompleteSystemState(jd)
    return completeState.mapValues { (_, state) ->
        MoonInstantState(state.isTransit, state.isOccultation, state.isShadowTransit, state.isEclipse)
    }
}

private fun getCompleteSystemState(jd: Double): Map<String, MoonCompleteState> {
    val jdTT = jd + (69.184 / 86400.0)
    val jupBody = AstroEngine.getBodyState("Jupiter", jdTT)
    val deltaAU = jupBody.distGeo

    val T = (jdTT - 2451545.0) / 36525.0
    val precessionDeg = 1.396971 * T + 0.0003086 * T * T
    val jupLamDegDate = jupBody.eclipticLon + precessionDeg
    val jupBetaDeg = jupBody.eclipticLat

    val moonsMap = JovianPrecision.highAccuracyJovSats(jdTT, deltaAU, jupLamDegDate, jupBetaDeg)

    // Safety check for empty or null return (prevents map loop crash)
    if (moonsMap.isEmpty()) return emptyMap()

    val sunState = AstroEngine.getBodyState("Sun", jdTT)
    var raDiff = sunState.ra - jupBody.ra
    while (raDiff < -180) raDiff += 360; while (raDiff > 180) raDiff -= 360
    val shadowSign = if (raDiff > 0) 1.0 else -1.0

    val r = AstroEngine.getBodyState("Earth", jdTT).distSun
    val R = jupBody.distSun
    val Delta = jupBody.distGeo
    val cosAlpha = (R * R + Delta * Delta - r * r) / (2 * R * Delta)
    val alpha = acos(cosAlpha.coerceIn(-1.0, 1.0))
    val shadowFactor = tan(alpha)
    val xShiftPerZ = shadowSign * shadowFactor

    val resultMap = mutableMapOf<String, MoonCompleteState>()
    val moonRadii = mapOf("Io" to 0.0255, "Europa" to 0.0218, "Ganymede" to 0.0368, "Callisto" to 0.0337)

    val jFlat = 15f / 16f
    val FLATTENING = 1.0 - jFlat
    val yScale = 1.0 / (1.0 - FLATTENING)

    for ((name, vec) in moonsMap) {
        val x = vec.x; val y = vec.y; val z = -vec.z // Invert Z
        val mRad = moonRadii[name] ?: 0.0
        val limitSq = (1.0 + mRad).pow(2)

        val yScaled = y * yScale
        val distSq = x * x + yScaled * yScaled
        val isTransit = (z > 0) && (distSq < limitSq)
        val isOccultation = (z < 0) && (distSq < limitSq)

        val sX = x + (z * xShiftPerZ)
        val sY = y
        val sYScaled = sY * yScale
        val sDistSq = sX * sX + sYScaled * sYScaled
        val isShadow = (z > 0) && (sDistSq < limitSq)

        val cX = -(z * xShiftPerZ)
        val cY = 0.0
        val distEclipseSq = (x - cX).pow(2) + ((y - cY) * yScale).pow(2)
        val isEclipse = (z < 0) && (distEclipseSq < limitSq)

        resultMap[name] = MoonCompleteState(x, y, z, sX, sY, isTransit, isOccultation, isShadow, isEclipse)
    }
    return resultMap
}

fun getAltAz(jd: Double, body: String, lat: Double, lon: Double): Triple<Double, Double, Double> {
    val state = AstroEngine.getBodyState(body, jd)
    val n = jd - 2451545.0
    val gmst = (18.697374558 + 24.06570982441908 * n) % 24.0
    val gmstFixed = if (gmst < 0) gmst + 24.0 else gmst
    val lst = (gmstFixed + lon/15.0 + 24.0) % 24.0
    val ha = (lst - state.ra/15.0) * 15.0
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(state.dec)
    val haRad = Math.toRadians(ha)
    val sinAlt = sin(latRad)*sin(decRad) + cos(latRad)*cos(decRad)*cos(haRad)
    val alt = Math.toDegrees(asin(sinAlt))
    return Triple(0.0, 0.0, alt)
}

private fun refineTimeMJD(tMJD_before: Double, moon: String, typeIdx: Int): Double {
    var low = tMJD_before
    var high = tMJD_before + (1.0/1440.0)
    for (i in 0..12) {
        val mid = (low + high) / 2.0
        val s = getSystemStateMJD(mid)[moon]!!
        val state = when(typeIdx) {
            0 -> s.transit; 1 -> s.shadowTransit; 2 -> s.occultation; 3 -> s.eclipse; else -> false
        }
        val sHigh = getSystemStateMJD(high)[moon]!!
        val highState = when(typeIdx) { 0->sHigh.transit; 1->sHigh.shadowTransit; 2->sHigh.occultation; 3->sHigh.eclipse; else->false }
        if (state == highState) high = mid else low = mid
    }
    return high
}