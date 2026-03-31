package com.kenyoung.orrery

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

private const val BRIGHTNESS_BOOST = 1.25f
private const val SYNODIC_MONTH_MTM = 29.530588853

private fun adjustBrightness(bitmap: android.graphics.Bitmap, factor: Float, blueTint: Boolean): android.graphics.Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        if (a == 0) continue
        var r = ((p shr 16) and 0xFF)
        var g = ((p shr 8) and 0xFF)
        var b = (p and 0xFF)
        r = (r * factor).toInt().coerceAtMost(255)
        g = (g * factor).toInt().coerceAtMost(255)
        b = (b * factor).toInt().coerceAtMost(255)
        if (blueTint) {
            b = (b * 2.0f).toInt().coerceAtMost(255)
            r = (r * 0.65f).toInt()
            g = (g * 0.65f).toInt()
        }
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    val result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

@Composable
fun MoonThisMonthScreen(currentDate: LocalDate, lat: Double, lon: Double, obs: ObserverState, onDateChange: (LocalDate) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val originalBitmap = remember(lat < 0.0) {
        val raw = context.assets.open("fullMoon.png").use { BitmapFactory.decodeStream(it) }
        if (raw != null && lat < 0.0) {
            val matrix = android.graphics.Matrix().apply { postRotate(180f) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else raw
    }

    LaunchedEffect(Unit) { ConstellationBoundary.ensureLoaded(context) }

    var displayMonth by remember { mutableStateOf(currentDate.withDayOfMonth(1)) }
    LaunchedEffect(currentDate) { displayMonth = currentDate.withDayOfMonth(1) }
    var dragAccumulator by remember { mutableStateOf(0f) }

    val monthStart = displayMonth
    val daysInMonth = monthStart.lengthOfMonth()
    val startCol = monthStart.dayOfWeek.value % 7 // Sunday=0

    // Pre-compute phase data and events
    val phaseData = remember(displayMonth.year, displayMonth.monthValue, lon) {
        val angles = DoubleArray(daysInMonth + 1)
        for (d in 1..daysInMonth) {
            val epochDay = monthStart.withDayOfMonth(d).toEpochDay().toDouble()
            angles[d - 1] = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay, lon))
        }
        // Next day after month end for last-day event detection
        val lastDayEpoch = monthStart.withDayOfMonth(daysInMonth).toEpochDay().toDouble() + 1.0
        angles[daysInMonth] = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(lastDayEpoch, lon))
        angles
    }

    // Detect phase events
    val events = mutableMapOf<Int, String>()
    val fullMoonDays = mutableListOf<Int>()
    var newMoonDayInMonth = -1
    for (d in 1..daysInMonth) {
        val phase = phaseData[d - 1]
        val nextPhase = phaseData[d]
        if (phase > 300.0 && nextPhase < 60.0) { events[d] = "New"; newMoonDayInMonth = d }
        if (phase < 90.0 && nextPhase >= 90.0) events[d] = "1st Qtr"
        if (phase < 180.0 && nextPhase >= 180.0) { events[d] = "Full"; fullMoonDays.add(d) }
        if (phase < 270.0 && nextPhase >= 270.0) events[d] = "Last Qtr"
    }

    // Find the most recent New Moon epoch day for age calculation.
    // If there's a New Moon in this month, use it. Otherwise scan backwards from month start.
    val newMoonEpochDay = if (newMoonDayInMonth > 0) {
        monthStart.withDayOfMonth(newMoonDayInMonth).toEpochDay()
    } else {
        val startEpoch = monthStart.toEpochDay().toDouble()
        var nmEpoch = monthStart.toEpochDay()
        for (offset in 1..30) {
            val ed = startEpoch - offset
            val p = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(ed, lon))
            val pNext = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(ed + 1.0, lon))
            if (p > 300.0 && pNext < 60.0) { nmEpoch = ed.toLong(); break }
        }
        nmEpoch
    }

    val titleStr = monthStart.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        val threshold = 200f
                        if (abs(dragAccumulator) > threshold) {
                            val monthsToShift = -(dragAccumulator / threshold).toInt()
                            if (monthsToShift != 0) {
                                displayMonth = displayMonth.plusMonths(monthsToShift.toLong())
                                dragAccumulator %= threshold
                            }
                        }
                    }
                )
            }
    ) {
        if (originalBitmap == null) return@Canvas

        withDensityScaling { w, h ->
            val titleHeight = 55f
            val headerHeight = 40f
            val gridTop = titleHeight + headerHeight
            val numRows = ceil((startCol + daysInMonth) / 7.0).toInt()
            val cellW = w / 7f
            val cellH = (h - gridTop) / numRows
            val moonRadius = min(cellW, cellH) * 0.38f
            val thumbSize = (moonRadius * 2).toInt().coerceAtLeast(32)

            // Pre-scale bitmap to thumbnail size
            val thumbnail = android.graphics.Bitmap.createScaledBitmap(originalBitmap, thumbSize, thumbSize, true)

            val gridLineColor = Color(0xFF4444FF)
            val greenColorInt = Color.Green.toArgb()
            val labelColorInt = LabelColor.toArgb()

            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas

                // Title
                val titlePaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = 48f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                nc.drawText(titleStr, w / 2f, titleHeight - 10f, titlePaint)

                // Day-of-week headers
                val headerPaint = Paint().apply {
                    isAntiAlias = true
                    color = labelColorInt
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT
                }
                val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                for (col in 0..6) {
                    nc.drawText(dayNames[col], col * cellW + cellW / 2f, gridTop - 10f, headerPaint)
                }

                // Day number paint
                val dayNumPaint = Paint().apply {
                    isAntiAlias = true
                    color = greenColorInt
                    textSize = 28f
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT
                }

                // Phase event label paint
                val eventPaint = Paint().apply {
                    isAntiAlias = true
                    color = greenColorInt
                    textSize = 28f
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

                // Paints shared across all day cells
                val bitmapPaint = Paint().apply { isFilterBitmap = true }
                val cellDataPaint = Paint().apply {
                    isAntiAlias = true
                    textSize = 28f
                    typeface = Typeface.DEFAULT
                }
                val spaceWidth = dayNumPaint.measureText(" ")
                val offset = lon / 15.0
                val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
                val textSize28 = 28f

                // Draw each day
                for (d in 1..daysInMonth) {
                    val cellIndex = startCol + d - 1
                    val col = cellIndex % 7
                    val row = cellIndex / 7
                    val cellLeft = col * cellW
                    val cellTop = gridTop + row * cellH
                    val cellCenterX = cellLeft + cellW / 2f
                    val cellCenterY = cellTop + cellH / 2f

                    // Create phased moon bitmap for this day
                    val phaseAngle = phaseData[d - 1]
                    var dayBitmap = createPhasedMoonBitmap(thumbnail, phaseAngle, lat)

                    // Brightness/tint for full moons
                    if (fullMoonDays.isNotEmpty() && d == fullMoonDays[0]) {
                        dayBitmap = adjustBrightness(dayBitmap, BRIGHTNESS_BOOST, blueTint = false)
                    } else if (fullMoonDays.size >= 2 && d == fullMoonDays[1]) {
                        dayBitmap = adjustBrightness(dayBitmap, BRIGHTNESS_BOOST, blueTint = true)
                    }

                    // Draw moon image centered in cell
                    val imgLeft = (cellCenterX - thumbSize / 2f).toInt()
                    val imgTop = (cellCenterY - thumbSize / 2f).toInt()
                    nc.drawBitmap(dayBitmap, null,
                        android.graphics.Rect(imgLeft, imgTop, imgLeft + thumbSize, imgTop + thumbSize),
                        bitmapPaint)

                    val isToday = (d == currentDate.dayOfMonth && monthStart.year == currentDate.year && monthStart.monthValue == currentDate.monthValue)
                    val cellTextColor = if (isToday) android.graphics.Color.WHITE else greenColorInt

                    // Illumination percentage in upper-right
                    val illum = (1.0 - cos(Math.toRadians(phaseAngle))) / 2.0 * 100.0
                    cellDataPaint.color = cellTextColor
                    cellDataPaint.textAlign = Paint.Align.RIGHT
                    nc.drawText("%.0f%%".format(illum), cellLeft + cellW - 4f, cellTop + textSize28, cellDataPaint)

                    // Day number in upper-left
                    dayNumPaint.color = cellTextColor
                    nc.drawText(d.toString(), cellLeft + 4f + spaceWidth, cellTop + textSize28, dayNumPaint)
                    dayNumPaint.color = greenColorInt

                    // Moon age: days since most recent New Moon
                    val dayEpoch = monthStart.withDayOfMonth(d).toEpochDay()
                    var moonAge = (dayEpoch - newMoonEpochDay).toInt()
                    if (moonAge < 0) moonAge += round(SYNODIC_MONTH_MTM).toInt()
                    cellDataPaint.textAlign = Paint.Align.CENTER
                    nc.drawText("Age $moonAge", cellCenterX, cellTop + textSize28 * 2 + 4f, cellDataPaint)

                    // Moon rise/set times
                    val dayMoonEvents = calculateMoonEvents(dayEpoch.toDouble(), lat, lon, offset)

                    // Rise time below the age line
                    val riseStr = if (!dayMoonEvents.rise.isNaN())
                        "Rise " + formatTimeMM(normalizeTime(dayMoonEvents.rise - offset + displayOffsetHours), false)
                    else "Rise --:--"
                    nc.drawText(riseStr, cellCenterX, cellTop + textSize28 * 3 + 8f, cellDataPaint)

                    // Three lines at bottom of cell: Set time, constellation, phase label
                    val bottomLineY3 = cellTop + cellH - 4f
                    val bottomLineY2 = bottomLineY3 - textSize28 - 2f
                    val bottomLineY1 = bottomLineY2 - textSize28 - 2f

                    // Set time
                    val setStr = if (!dayMoonEvents.set.isNaN())
                        "Set " + formatTimeMM(normalizeTime(dayMoonEvents.set - offset + displayOffsetHours), false)
                    else "Set --:--"
                    nc.drawText(setStr, cellCenterX, bottomLineY1, cellDataPaint)

                    // Constellation
                    val transitJd = estimateMoonTransitEpochDay(dayEpoch.toDouble(), lon) + UNIX_EPOCH_JD
                    val moonState = AstroEngine.getBodyState("Moon", transitJd)
                    val b1875 = precessJ2000ToDate(moonState.ra, moonState.dec, B1875_JD)
                    val b1875RaH = normalizeDegrees(b1875.ra) * DEGREES_TO_HOURS
                    val constellation = ConstellationBoundary.findConstellation(b1875RaH, b1875.dec)
                    nc.drawText(constellation, cellCenterX, bottomLineY2, cellDataPaint)

                    // Phase event label centered at bottom of cell
                    val eventLabel = events[d]
                    eventPaint.color = cellTextColor
                    eventPaint.textAlign = Paint.Align.CENTER
                    if (eventLabel != null) {
                        nc.drawText(eventLabel, cellCenterX, bottomLineY3, eventPaint)
                    }
                    eventPaint.color = greenColorInt
                }

                // Grid lines
                val gridPaint = Paint().apply {
                    color = gridLineColor.toArgb()
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                // Horizontal lines
                for (row in 0..numRows) {
                    val y = gridTop + row * cellH
                    nc.drawLine(0f, y, w, y, gridPaint)
                }
                // Vertical lines
                for (col in 0..7) {
                    val x = col * cellW
                    nc.drawLine(x, gridTop, x, gridTop + numRows * cellH, gridPaint)
                }
            }
        }
    }
}
