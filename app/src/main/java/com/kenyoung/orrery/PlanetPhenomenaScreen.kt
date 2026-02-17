package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun PlanetPhenomenaScreen(epochDay: Double, stdOffsetHours: Double, useLocalTime: Boolean, onTimeDisplayChange: (Boolean) -> Unit) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val bgColor = Color.Black
    val headerLabelColor = LabelColor
    val planetLabelColor = LabelColor
    val eventBlue = Color(0xFF87CEFA) // Updated to match PlanetCompassScreen
    val textWhite = Color.White

    val displayOffsetHours = if (useLocalTime) stdOffsetHours else 0.0

    var phenomenaData by remember { mutableStateOf<List<PlanetPhenomenaData>?>(null) }

    LaunchedEffect(epochDay) {
        withContext(Dispatchers.Default) {
            phenomenaData = calculatePhenomena(epochDay)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
        val rowHeight = 45f
        val headerHeight = 120f
        val planetHeaderHeight = 60f
        val totalHeightPx = headerHeight + (7 * planetHeaderHeight) + (2 * 4 * rowHeight) + (5 * 2 * rowHeight) + 100f
        val totalHeightDp = with(density) { totalHeightPx.toDp() }

        Canvas(modifier = Modifier.fillMaxWidth().height(totalHeightDp)) {
            val w = size.width
            val titlePaintYellow = Paint().apply { color = headerLabelColor.toArgb(); textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val titlePaintWhite = Paint().apply { color = textWhite.toArgb(); textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val colHeaderPaint = Paint().apply { color = eventBlue.toArgb(); textSize = 40f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT; isAntiAlias = true }
            val planetNamePaint = Paint().apply { color = planetLabelColor.toArgb(); textSize = 42f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }
            val eventNamePaint = Paint().apply { color = eventBlue.toArgb(); textSize = 34f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT; isAntiAlias = true }
            val datePaint = Paint().apply { color = textWhite.toArgb(); textSize = 34f; textAlign = Paint.Align.LEFT; typeface = Typeface.MONOSPACE; isAntiAlias = true }

            val charW = datePaint.measureText("0")
            val shiftW = 2 * charW
            val col1X = 20f
            val shift = w / 9f
            val colLastDateX = (w * 0.35f) - shift
            val colNextDateX = (w * 0.68f) - shift + (3 * shiftW)
            val nextHourRelativeX = 230f - shiftW
            val nextAngleRelativeX = 320f - shiftW

            var currentY = 60f
            val nowLocalDate = LocalDate.ofEpochDay(epochDay.toLong())
            val dateStr = nowLocalDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

            drawIntoCanvas { canvas ->
                // Draw title with yellow text and white date
                val titlePart1 = "Planet Phenomena for "
                val totalTitleWidth = titlePaintYellow.measureText(titlePart1) + titlePaintWhite.measureText(dateStr)
                var titleX = (w - totalTitleWidth) / 2f
                canvas.nativeCanvas.drawText(titlePart1, titleX, currentY, titlePaintYellow)
                titleX += titlePaintYellow.measureText(titlePart1)
                canvas.nativeCanvas.drawText(dateStr, titleX, currentY, titlePaintWhite)
                currentY += 60f
                canvas.nativeCanvas.drawText("Last", colLastDateX + 100f, currentY, colHeaderPaint)
                canvas.nativeCanvas.drawText("Next", colNextDateX + 100f, currentY, colHeaderPaint)
                currentY += 20f

                val data = phenomenaData
                if (data == null) {
                    canvas.nativeCanvas.drawText("Calculating...", w/2, currentY + 100f, datePaint.apply { textAlign = Paint.Align.CENTER })
                } else {
                    for (planetData in data) {
                        currentY += planetHeaderHeight
                        canvas.nativeCanvas.drawText(planetData.name, col1X, currentY, planetNamePaint)
                        currentY += 10f
                        for (row in planetData.rows) {
                            currentY += rowHeight
                            canvas.nativeCanvas.drawText(row.label, col1X, currentY, eventNamePaint)
                            fun drawEventDetails(ev: PhenomenonEvent, baseX: Float, hourOffset: Float, angleOffset: Float) {
                                var dispHour = ev.hour + displayOffsetHours.roundToInt()
                                var dispDate = ev.date
                                if (dispHour >= 24) { dispHour -= 24; dispDate = dispDate.plusDays(1) }
                                if (dispHour < 0) { dispHour += 24; dispDate = dispDate.minusDays(1) }
                                val dateText = dispDate.format(DateTimeFormatter.ofPattern("dd/MM/yy"))
                                val hourText = "%02dh".format(dispHour)
                                canvas.nativeCanvas.drawText(dateText, baseX, currentY, datePaint)
                                canvas.nativeCanvas.drawText(hourText, baseX + hourOffset, currentY, datePaint)
                                if (ev.angle != null) {
                                    val angText = "${round(ev.angle).toInt()}°"
                                    canvas.nativeCanvas.drawText(angText, baseX + angleOffset, currentY, datePaint)
                                }
                            }
                            drawEventDetails(row.lastEvent, colLastDateX, 230f - shiftW, 320f - shiftW)
                            drawEventDetails(row.nextEvent, colNextDateX, nextHourRelativeX, nextAngleRelativeX)
                        }
                    }
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !useLocalTime, onClick = { onTimeDisplayChange(false) })
            Text("Universal Time", color = Color.White, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = useLocalTime, onClick = { onTimeDisplayChange(true) })
            Text("Standard Time", color = Color.White, fontSize = 14.sp)
        }
    }
    }
}

// --- DATA STRUCTURES ---
data class PlanetPhenomenaData(val name: String, val rows: List<PhenomenaRow>)
data class PhenomenaRow(val label: String, val lastEvent: PhenomenonEvent, val nextEvent: PhenomenonEvent)
data class PhenomenonEvent(val date: LocalDate, val hour: Int, val angle: Double? = null)
enum class EventType { CONJUNCTION_INF, CONJUNCTION_SUP, OPPOSITION, ELONGATION_EAST, ELONGATION_WEST }

// --- CALCULATION LOGIC (Using AstroEngine) ---

suspend fun calculatePhenomena(epochDay: Double): List<PlanetPhenomenaData> {
    val planets = getOrreryPlanets().filter { it.name != "Earth" }
    val results = mutableListOf<PlanetPhenomenaData>()

    for (p in planets) {
        val isInferior = (p.name == "Mercury" || p.name == "Venus")
        val scanRange = if (p.name == "Mars") 900 else 600
        val rows = mutableListOf<PhenomenaRow>()

        if (isInferior) {
            rows.add(findPhenomenaRow("Inf. Conj.", epochDay, p.name, scanRange, targetVal = 0.0, eventType = EventType.CONJUNCTION_INF))
            rows.add(findPhenomenaRow("Max. West", epochDay, p.name, scanRange, eventType = EventType.ELONGATION_WEST))
            rows.add(findPhenomenaRow("Sup. Conj.", epochDay, p.name, scanRange, targetVal = 0.0, eventType = EventType.CONJUNCTION_SUP))
            rows.add(findPhenomenaRow("Max. East", epochDay, p.name, scanRange, eventType = EventType.ELONGATION_EAST))
        } else {
            rows.add(findPhenomenaRow("Conjunction", epochDay, p.name, scanRange, targetVal = 0.0, eventType = EventType.CONJUNCTION_SUP))
            rows.add(findPhenomenaRow("Opposition", epochDay, p.name, scanRange, targetVal = 180.0, eventType = EventType.OPPOSITION))
        }
        results.add(PlanetPhenomenaData(p.name, rows))
    }
    return results
}

fun findPhenomenaRow(
    label: String, centerEpoch: Double, bodyName: String, rangeDays: Int,
    targetVal: Double = 0.0, eventType: EventType
): PhenomenaRow {

    // Returns signed difference or elongation value
    fun getCrossingVal(t: Double): Double {
        val jd = t + 2440587.5
        val planetState = AstroEngine.getBodyState(bodyName, jd)
        val sunState = AstroEngine.getBodyState("Sun", jd)

        var diff = planetState.eclipticLon - sunState.eclipticLon
        while (diff < -180) diff += 360; while (diff > 180) diff -= 360

        if (eventType == EventType.ELONGATION_EAST || eventType == EventType.ELONGATION_WEST) {
            return diff
        }

        var valToCheck = diff - targetVal
        while (valToCheck < -180) valToCheck += 360; while (valToCheck > 180) valToCheck -= 360
        return valToCheck
    }

    // Binary search for zero crossing (Conjunctions/Oppositions)
    fun refineTime(tStart: Double, tEnd: Double): Double {
        var low = tStart; var high = tEnd
        val vLow = getCrossingVal(low)
        for (i in 0..19) {
            val mid = (low + high) / 2.0
            val vMid = getCrossingVal(mid)
            if (vMid * vLow > 0) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    // UPDATED: Golden Section Search for finding Max/Min (Elongations for Mercury and Venus)
    // precision ~ 1 minute (0.0007 days)
    fun findPeakTime(tStart: Double, tEnd: Double, isMax: Boolean): Double {
        val gr = (sqrt(5.0) - 1.0) / 2.0
        var a = tStart
        var b = tEnd
        var c = b - gr * (b - a)
        var d = a + gr * (b - a)

        val epsilon = 0.0001

        while (abs(b - a) > epsilon) {
            val vc = getCrossingVal(c)
            val vd = getCrossingVal(d)

            // If finding Max (East), we want larger val. If Min (West, negative), we want smaller val.
            // Actually West elongation is negative, so "Max West" is a Minimum in the signed value.

            if (isMax) { // Maximize
                if (vc > vd) {
                    b = d; d = c; c = b - gr * (b - a)
                } else {
                    a = c; c = d; d = a + gr * (b - a)
                }
            } else { // Minimize
                if (vc < vd) {
                    b = d; d = c; c = b - gr * (b - a)
                } else {
                    a = c; c = d; d = a + gr * (b - a)
                }
            }
        }
        return (b + a) / 2.0
    }

    fun isEvent(t1: Double, val1: Double, val2: Double): Boolean {
        // Zero Crossing Check (Conj/Opp)
        if (val1 * val2 <= 0) {
            if (abs(val1 - val2) > 180) return false
            if (eventType == EventType.CONJUNCTION_INF || eventType == EventType.CONJUNCTION_SUP) {
                val tEvent = refineTime(t1, t1 + 1.0)
                val jd = tEvent + 2440587.5
                val pState = AstroEngine.getBodyState(bodyName, jd)
                val sunState = AstroEngine.getBodyState("Sun", jd)

                // FIXED: Use Planet-Earth Dist vs Sun-Earth Dist to distinguish Inf/Sup
                val isInf = pState.distGeo < sunState.distGeo

                if (eventType == EventType.CONJUNCTION_INF && !isInf) return false
                if (eventType == EventType.CONJUNCTION_SUP && isInf) return false
            }
            return true
        }
        return false
    }

    // Backwards Search
    var lastEvent: PhenomenonEvent? = null
    var t = centerEpoch
    while (t > centerEpoch - rangeDays) {
        val tPrev = t - 1.0
        val vCurr = getCrossingVal(t)
        val vPrev = getCrossingVal(tPrev)

        if (eventType == EventType.ELONGATION_EAST) {
            val vNext = getCrossingVal(t + 1.0)
            // Check for local Max
            if (vCurr > 0 && vCurr > vPrev && vCurr > vNext) {
                // Use Golden Section Search for precision
                val tPeak = findPeakTime(t - 1.0, t + 1.0, true)
                lastEvent = makeEvent(tPeak, getCrossingVal(tPeak))
                break
            }
        } else if (eventType == EventType.ELONGATION_WEST) {
            val vNext = getCrossingVal(t + 1.0)
            // Check for local Min (most negative)
            if (vCurr < 0 && vCurr < vPrev && vCurr < vNext) {
                // Use Golden Section Search
                val tPeak = findPeakTime(t - 1.0, t + 1.0, false)
                lastEvent = makeEvent(tPeak, abs(getCrossingVal(tPeak)))
                break
            }
        } else {
            if (isEvent(tPrev, vPrev, vCurr)) {
                lastEvent = makeEvent(refineTime(tPrev, t), null)
                break
            }
        }
        t -= 1.0
    }

    // Forwards Search
    var nextEvent: PhenomenonEvent? = null
    t = centerEpoch
    while (t < centerEpoch + rangeDays) {
        val tNext = t + 1.0
        val vCurr = getCrossingVal(t)
        val vNext = getCrossingVal(tNext)

        if (eventType == EventType.ELONGATION_EAST) {
            val vPrev = getCrossingVal(t - 1.0)
            if (vCurr > 0 && vCurr > vPrev && vCurr > vNext) {
                val tPeak = findPeakTime(t - 1.0, t + 1.0, true)
                nextEvent = makeEvent(tPeak, getCrossingVal(tPeak))
                break
            }
        } else if (eventType == EventType.ELONGATION_WEST) {
            val vPrev = getCrossingVal(t - 1.0)
            if (vCurr < 0 && vCurr < vPrev && vCurr < vNext) {
                val tPeak = findPeakTime(t - 1.0, t + 1.0, false)
                nextEvent = makeEvent(tPeak, abs(getCrossingVal(tPeak)))
                break
            }
        } else {
            if (isEvent(t, vCurr, vNext)) {
                nextEvent = makeEvent(refineTime(t, tNext), null)
                break
            }
        }
        t += 1.0
    }

    val fallback = PhenomenonEvent(LocalDate.ofEpochDay(centerEpoch.toLong()), 0, null)
    return PhenomenaRow(label, lastEvent ?: fallback, nextEvent ?: fallback)
}

fun makeEvent(epochDay: Double, angle: Double?): PhenomenonEvent {
    val dayFrac = epochDay - floor(epochDay)
    var hour = (dayFrac * 24.0).roundToInt()
    var dateOffset = 0L
    if (hour == 24) { hour = 0; dateOffset = 1 }
    return PhenomenonEvent(LocalDate.ofEpochDay(epochDay.toLong()).plusDays(dateOffset), hour, angle)
}