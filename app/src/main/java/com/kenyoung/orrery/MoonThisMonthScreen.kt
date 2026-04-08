package com.kenyoung.orrery

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
private const val TITLE_HEIGHT = 55f
private const val HEADER_HEIGHT = 40f
private const val GRID_TOP = TITLE_HEIGHT + HEADER_HEIGHT

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
fun MoonThisMonthScreen(
    currentDate: LocalDate,
    lat: Double,
    lon: Double,
    obs: ObserverState,
    onDateChange: (LocalDate) -> Unit,
    onDayTap: (LocalDate) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val originalBitmap = remember(lat < 0.0) {
        val raw = context.assets.open("fullMoon.png").use { BitmapFactory.decodeStream(it) }
        if (raw != null && lat < 0.0) {
            val matrix = android.graphics.Matrix().apply { postRotate(180f) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else raw
    }

    var displayMonth by remember { mutableStateOf(currentDate.withDayOfMonth(1)) }
    LaunchedEffect(currentDate) { displayMonth = currentDate.withDayOfMonth(1) }
    var dragAccumulator by remember { mutableStateOf(0f) }

    val monthStart = displayMonth
    val daysInMonth = monthStart.lengthOfMonth()
    val startCol = monthStart.dayOfWeek.value % 7 // Sunday=0

    // Pre-compute phase data and events
    val phaseData = remember(displayMonth.year, displayMonth.monthValue, lon) {
        val angles = DoubleArray(daysInMonth + 1)
        val midnightOffset = -lon / 360.0 // local apparent midnight in UT
        for (d in 1..daysInMonth) {
            val epochDay = monthStart.withDayOfMonth(d).toEpochDay().toDouble()
            angles[d - 1] = calculateMoonPhaseAngle(epochDay + midnightOffset)
        }
        // Next day after month end for last-day event detection
        val lastDayEpoch = monthStart.withDayOfMonth(daysInMonth).toEpochDay().toDouble() + 1.0
        angles[daysInMonth] = calculateMoonPhaseAngle(lastDayEpoch + midnightOffset)
        angles
    }

    // Detect phase events using the shared helper. The pre-computed phaseData
    // values are passed in to avoid re-evaluating calculateMoonPhaseAngle.
    val events = mutableMapOf<Int, String>()
    val fullMoonDays = mutableListOf<Int>()
    for (d in 1..daysInMonth) {
        val crossing = findMoonPhaseCrossing(
            dayStartUt = 0.0, dayEndUt = 1.0,
            phaseStart = phaseData[d - 1], phaseEnd = phaseData[d]
        )
        if (crossing != null) {
            events[d] = crossing.event.shortName
            if (crossing.event == MoonPhaseEvent.FULL_MOON) fullMoonDays.add(d)
        }
    }

    val titleStr = monthStart.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(displayMonth) {
                // Tap a day cell to drill down to the Moon On Day page. Pointer
                // offsets are in raw pixels; the cell layout below is in reference
                // units (post-density-scaling), so divide by dScale.
                detectTapGestures { offset ->
                    val dScale = density / REFERENCE_DENSITY
                    val w = size.width / dScale
                    val h = size.height / dScale
                    val numRows = ceil((startCol + daysInMonth) / 7.0).toInt()
                    val cellW = w / 7f
                    val cellH = (h - GRID_TOP) / numRows
                    val refX = offset.x / dScale
                    val refY = offset.y / dScale
                    if (refY >= GRID_TOP) {
                        val tapCol = (refX / cellW).toInt().coerceIn(0, 6)
                        val tapRow = ((refY - GRID_TOP) / cellH).toInt()
                        if (tapRow in 0 until numRows) {
                            val day = tapRow * 7 + tapCol - startCol + 1
                            if (day in 1..daysInMonth) {
                                onDayTap(monthStart.withDayOfMonth(day))
                            }
                        }
                    }
                }
            }
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
            val numRows = ceil((startCol + daysInMonth) / 7.0).toInt()
            val cellW = w / 7f
            val cellH = (h - GRID_TOP) / numRows
            // Text occupies 3 lines above image (day#/%, age, rise) and 2 below (set, phase)
            // with spacing. Constrain image to fit the remaining vertical space.
            val cellTextSize = 28f
            val textAbove = cellTextSize * 3 + 8f + 4f  // day/%, age, rise + gaps
            val textBelow = cellTextSize * 2 + 6f + 4f  // set, phase + gaps
            val availImgH = cellH - textAbove - textBelow
            val maxImgSize = min(cellW - 8f, availImgH)
            val thumbSize = maxImgSize.toInt().coerceAtLeast(32)

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
                nc.drawText(titleStr, w / 2f, TITLE_HEIGHT - 10f, titlePaint)

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
                    nc.drawText(dayNames[col], col * cellW + cellW / 2f, GRID_TOP - 10f, headerPaint)
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
                // Day window: midnight-to-midnight in display TZ (or UT if useStandardTime is off).
                // Rise/set are reported in [0, 24) hours of that same day; events outside the window
                // come back as NaN and render as "(none)".
                val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
                val textSize28 = 28f

                // Draw each day
                for (d in 1..daysInMonth) {
                    val cellIndex = startCol + d - 1
                    val col = cellIndex % 7
                    val row = cellIndex / 7
                    val cellLeft = col * cellW
                    val cellTop = GRID_TOP + row * cellH
                    val cellCenterX = cellLeft + cellW / 2f
                    val cellCenterY = cellTop + cellH / 2f

                    val phaseAngle = phaseData[d - 1]

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

                    // Label color: white for current day, LabelColor for others
                    val cellLabelColor = if (isToday) android.graphics.Color.WHITE else labelColorInt
                    val cellPhaseColor = if (isToday) android.graphics.Color.WHITE else Color.Red.toArgb()

                    // Moon age: days since most recent New Moon, evaluated at local apparent noon
                    val dayEpoch = monthStart.withDayOfMonth(d).toEpochDay()
                    val localNoon = dayEpoch.toDouble() + 0.5 - lon / 360.0
                    val moonAge = calculateMoonAge(localNoon, lon)
                    cellDataPaint.textAlign = Paint.Align.CENTER
                    val ageLabel = "Age "
                    val ageValue = "%.1f".format(moonAge)
                    val ageY = cellTop + textSize28 * 2 + 4f
                    val ageTotalW = cellDataPaint.measureText(ageLabel + ageValue)
                    val ageX = cellCenterX - ageTotalW / 2f
                    cellDataPaint.textAlign = Paint.Align.LEFT
                    cellDataPaint.color = cellLabelColor
                    nc.drawText(ageLabel, ageX, ageY, cellDataPaint)
                    cellDataPaint.color = cellTextColor
                    nc.drawText(ageValue, ageX + cellDataPaint.measureText(ageLabel), ageY, cellDataPaint)

                    // Moon rise/set times: scan only this day's window (midnight to midnight).
                    val dayMoonEvents = calculateMoonEvents(dayEpoch.toDouble(), lat, lon, displayOffsetHours, scanDays = 1.0)

                    // Rise time below the age line
                    val riseLabel = "Rise "
                    val riseValue = if (!dayMoonEvents.rise.isNaN())
                        formatTimeMM(dayMoonEvents.rise, false)
                    else "(none)"
                    val riseY = cellTop + textSize28 * 3 + 8f
                    val riseTotalW = cellDataPaint.measureText(riseLabel + riseValue)
                    val riseX = cellCenterX - riseTotalW / 2f
                    cellDataPaint.color = cellLabelColor
                    nc.drawText(riseLabel, riseX, riseY, cellDataPaint)
                    cellDataPaint.color = cellTextColor
                    nc.drawText(riseValue, riseX + cellDataPaint.measureText(riseLabel), riseY, cellDataPaint)

                    // Two lines at bottom of cell: Set time, phase label
                    val bottomLineY2 = cellTop + cellH - 4f
                    val bottomLineY1 = bottomLineY2 - textSize28 - 2f

                    // Draw moon image centered between rise line bottom and set line top
                    val imgRegionTop = riseY + 4f
                    val imgRegionBottom = bottomLineY1 - textSize28 - 2f
                    val imgCenterY = (imgRegionTop + imgRegionBottom) / 2f

                    var dayBitmap = createPhasedMoonBitmap(thumbnail, phaseAngle, lat)
                    if (fullMoonDays.isNotEmpty() && d == fullMoonDays[0]) {
                        dayBitmap = adjustBrightness(dayBitmap, BRIGHTNESS_BOOST, blueTint = false)
                    } else if (fullMoonDays.size >= 2 && d == fullMoonDays[1]) {
                        dayBitmap = adjustBrightness(dayBitmap, BRIGHTNESS_BOOST, blueTint = true)
                    }

                    val imgLeft = (cellCenterX - thumbSize / 2f).toInt()
                    val imgTop = (imgCenterY - thumbSize / 2f).toInt()
                    nc.drawBitmap(dayBitmap, null,
                        android.graphics.Rect(imgLeft, imgTop, imgLeft + thumbSize, imgTop + thumbSize),
                        bitmapPaint)

                    // Set time
                    val setLabel = "Set "
                    val setValue = if (!dayMoonEvents.set.isNaN())
                        formatTimeMM(dayMoonEvents.set, false)
                    else "(none)"
                    val setTotalW = cellDataPaint.measureText(setLabel + setValue)
                    val setX = cellCenterX - setTotalW / 2f
                    cellDataPaint.color = cellLabelColor
                    nc.drawText(setLabel, setX, bottomLineY1, cellDataPaint)
                    cellDataPaint.color = cellTextColor
                    nc.drawText(setValue, setX + cellDataPaint.measureText(setLabel), bottomLineY1, cellDataPaint)

                    // Phase event label centered at bottom of cell
                    val eventLabel = events[d]
                    if (eventLabel != null) {
                        eventPaint.color = cellPhaseColor
                        eventPaint.textAlign = Paint.Align.CENTER
                        nc.drawText(eventLabel, cellCenterX, bottomLineY2, eventPaint)
                        eventPaint.color = greenColorInt
                    }
                }

                // Grid lines
                val gridPaint = Paint().apply {
                    color = gridLineColor.toArgb()
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                // Horizontal lines
                for (row in 0..numRows) {
                    val y = GRID_TOP + row * cellH
                    nc.drawLine(0f, y, w, y, gridPaint)
                }
                // Vertical lines
                for (col in 0..7) {
                    val x = col * cellW
                    nc.drawLine(x, GRID_TOP, x, GRID_TOP + numRows * cellH, gridPaint)
                }
            }
        }
    }
}
