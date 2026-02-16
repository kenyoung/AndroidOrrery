package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

private data class MoonCompleteState(
    val isTransit: Boolean, val isOccultation: Boolean,
    val isShadowTransit: Boolean, val isEclipse: Boolean
)

private data class RawEvent(
    val mjd: Double,
    val text: String,
    val typeId: Int,
    val isStart: Boolean
)

// --- COMPOSABLE ---

@Composable
fun JovianEventsScreen(currentEpochDay: Double, currentInstant: Instant, lat: Double, lon: Double, stdOffsetHours: Double, stdTimeLabel: String, useLocalTime: Boolean, onTimeDisplayChange: (Boolean) -> Unit) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val bgColor = Color.Black

    // --- TIME ZONE STATE ---
    val zoneId: ZoneId = if (useLocalTime) ZoneOffset.ofTotalSeconds((stdOffsetHours * 3600).roundToInt()) else ZoneOffset.UTC
    val timeLabel = if (useLocalTime) " $stdTimeLabel" else " UT"

    // Calculate start MJD based on the "Today" of the selected zone
    val zonedDateTime = currentInstant.atZone(zoneId)
    val todayDate = zonedDateTime.toLocalDate()
    val startOfDayInstant = todayDate.atStartOfDay(zoneId).toInstant()

    // MJD = JD - 2400000.5. JD of Instant is (millis/86400000) + 2440587.5
    val startMJD = (startOfDayInstant.toEpochMilli() / 86400000.0) + 2440587.5 - 2400000.5
    val nowMJD = (currentInstant.toEpochMilli() / 86400000.0) + 2440587.5 - 2400000.5

    var eventList by remember { mutableStateOf<List<JovianEventItem>?>(null) }

    LaunchedEffect(startMJD, currentInstant, lat, lon) {
        withContext(Dispatchers.Default) {
            try {
                eventList = generateJovianEvents(startMJD, currentInstant, lat, lon)
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Color.Black)
        ) {
            val currentJD = mjdToJD(nowMJD)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawJovianSystem(currentJD, size.width / 2f, size.height / 2f, size.width)
            }
        }

        // --- MIDDLE: Event List ---
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val fullList = eventList
            if (fullList == null) {
                Text(
                    "Calculating events...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (fullList.isEmpty()) {
                Text(
                    "No events found or calculation error.",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val rowHeight = 55f
                val headerHeight = 90f
                val dateHeaderHeight = 60f
                val bottomPadding = 20f
                val textSizeContent = 40f
                val headerYellow = LabelColor
                val dateYellow = LabelColor
                val textGray = Color.Gray
                val textGreen = Color(0xFF00FF00)
                val textRed = Color.Red

                // Determine if we show 3 days or filter to 2
                // We must group by the *Selected Zone Date*
                val distinctDates3 = fullList
                    .filter { !it.isSimultaneousAlert }
                    .map { mjdToInstant(it.mjd).atZone(zoneId).toLocalDate().toEpochDay() }
                    .distinct()
                    .count()
                val height3Days = headerHeight + (distinctDates3 * dateHeaderHeight) + (fullList.size * rowHeight) + bottomPadding

                val screenHeight = constraints.maxHeight.toFloat()

                // If 3 days fit, show all. Else filter to 2 days (standard behavior)
                val list = if (height3Days <= screenHeight) {
                    fullList
                } else {
                    val limit2Days = startMJD + 2.0
                    fullList.filter { it.mjd < limit2Days }
                }

                // Recalculate metrics for the chosen list
                val distinctDates = list
                    .filter { !it.isSimultaneousAlert }
                    .map { mjdToInstant(it.mjd).atZone(zoneId).toLocalDate().toEpochDay() }
                    .distinct()
                    .count()

                val totalHeightPx = headerHeight + (distinctDates * dateHeaderHeight) + (list.size * rowHeight) + bottomPadding
                val totalHeightDp = with(density) { totalHeightPx.toDp() }

                Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(totalHeightDp)) {
                        val w = size.width

                        // Paints
                        val titlePaint = Paint().apply { color = LabelColor.toArgb(); textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                        val timeHeaderYellow = Paint().apply { color = LabelColor.toArgb(); textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }
                        val timeHeaderWhite = Paint().apply { color = Color.White.toArgb(); textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }
                        val dateHeaderPaint = Paint().apply { color = dateYellow.toArgb(); textSize = 36f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }

                        val paintGray = Paint().apply { color = textGray.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }
                        val paintGreen = Paint().apply { color = textGreen.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }
                        val paintRed = Paint().apply { color = textRed.toArgb(); textSize = textSizeContent; textAlign = Paint.Align.LEFT; typeface = Typeface.SANS_SERIF; isAntiAlias = true }

                        var currentY = 50f

                        drawIntoCanvas { canvas ->
                            // 1. Title
                            canvas.nativeCanvas.drawText("Galilean Moon Events", w/2, currentY, titlePaint)
                            currentY += 45f

                            // 2. Current Time (Centered below title)
                            val timeOnlyFormatter = DateTimeFormatter.ofPattern("HH:mm")
                            val timeOnly = currentInstant.atZone(zoneId).format(timeOnlyFormatter)
                            val part1 = "It is now "
                            val part2 = timeOnly
                            val part3 = timeLabel
                            val totalWidth = timeHeaderYellow.measureText(part1) + timeHeaderWhite.measureText(part2) + timeHeaderYellow.measureText(part3)
                            var timeX = (w - totalWidth) / 2f
                            canvas.nativeCanvas.drawText(part1, timeX, currentY, timeHeaderYellow)
                            timeX += timeHeaderYellow.measureText(part1)
                            canvas.nativeCanvas.drawText(part2, timeX, currentY, timeHeaderWhite)
                            timeX += timeHeaderWhite.measureText(part2)
                            canvas.nativeCanvas.drawText(part3, timeX, currentY, timeHeaderYellow)
                            currentY += 45f

                            var lastEpochDay = -99999L
                            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                            val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

                            for (item in list) {
                                if (!item.isSimultaneousAlert) {
                                    // Use Zone-aware date
                                    val itemDate = mjdToInstant(item.mjd).atZone(zoneId).toLocalDate()
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
                                    val timeStr = instant.atZone(zoneId).format(timeFormatter)
                                    "$timeStr$timeLabel, ${item.text}"
                                }

                                canvas.nativeCanvas.drawText(displayString, 40f, currentY, activePaint)

                                // Check Mark for Past Events
                                if (item.mjd < nowMJD) {
                                    val textWidth = activePaint.measureText(displayString)
                                    canvas.nativeCanvas.drawText("✓", 40f + textWidth + 20f, currentY, activePaint)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- BOTTOM: Radio Buttons ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !useLocalTime,
                    onClick = { onTimeDisplayChange(false) }
                )
                Text("Universal Time", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = useLocalTime,
                    onClick = { onTimeDisplayChange(true) }
                )
                Text("Standard Time", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

// --- GENERATION LOGIC ---

private suspend fun generateJovianEvents(startMJD: Double, nowInstant: Instant, lat: Double, lon: Double): List<JovianEventItem> {
    val nowMJD = (nowInstant.toEpochMilli() / 86400000.0) + 2440587.5 - 2400000.5
    // Increase range to 3 days to check if they fit
    val endMJD = startMJD + 3.0
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

            // Transit events (always visible if Jupiter is visible)
            if (!p.transit && c.transit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 0), "$m begins transit", 0, true))
            if (p.transit && !c.transit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 0), "$m ends transit", 0, false))

            // Shadow Transit events
            if (!p.shadowTransit && c.shadowTransit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 1), "$m shadow transit begins", 1, true))
            if (p.shadowTransit && !c.shadowTransit) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 1), "$m shadow transit ends", 1, false))

            // Occultation events - only if NOT Eclipsed (in shadow)
            if (!c.eclipse) {
                if (!p.occultation && c.occultation) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 2), "$m enters occultation", 2, true))
                if (p.occultation && !c.occultation) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 2), "$m exits occultation", 2, false))
            }

            // Eclipse events - only if NOT Occulted (behind disk)
            if (!c.occultation) {
                if (!p.eclipse && c.eclipse) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 3), "$m enters eclipse", 3, true))
                if (p.eclipse && !c.eclipse) rawEvents.add(RawEvent(refineTimeMJD(tMJD, m, 3), "$m exits eclipse", 3, false))
            }
        }
        prevState = currState
        tMJD = nextMJD
    }

    val sortedRaw = rawEvents.sortedBy { it.mjd }
    val finalItems = mutableListOf<JovianEventItem>()
    var nextEventFound = false

    for (raw in sortedRaw) {
        val jd = raw.mjd + 2400000.5
        val sunAlt = getAltitude(jd, "Sun", lat, lon)
        val jupAlt = getAltitude(jd, "Jupiter", lat, lon)
        val isVisible = (sunAlt < HORIZON_REFRACTED) && (jupAlt > -0.5667)
        var pixelType = if (isVisible) JovEventPixel.VISIBLE else JovEventPixel.HIDDEN

        if (raw.mjd > nowMJD && isVisible && !nextEventFound) {
            pixelType = JovEventPixel.NEXT
            nextEventFound = true
        }

        // Append altitude info to the text
        val altString = " (El %.0f°)".format(jupAlt)
        finalItems.add(JovianEventItem(raw.mjd, raw.text + altString, pixelType, false))

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

// --- MATH HELPERS (MJD AWARE) ---

private fun mjdToJD(mjd: Double): Double = mjd + 2400000.5

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

    val moonsMap = JovianPrecision.highAccuracyJovSats(jdTT, deltaAU, jupBody.eclipticLon, jupBody.eclipticLat)

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

        resultMap[name] = MoonCompleteState(isTransit, isOccultation, isShadow, isEclipse)
    }
    return resultMap
}

private fun getAltitude(jd: Double, body: String, lat: Double, lon: Double): Double {
    val state = AstroEngine.getBodyState(body, jd)
    val lst = calculateLSTHours(jd, lon)
    val haHours = lst - state.ra / 15.0
    return calculateAltitude(haHours, lat, state.dec)
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