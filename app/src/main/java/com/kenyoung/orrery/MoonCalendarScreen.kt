package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun MoonCalendarScreen(currentDate: LocalDate, lat: Double, lon: Double, onDateChange: (LocalDate) -> Unit) {
    val bgColor = Color.Black
    val yearColorInt = Color.Green.toArgb()
    val yellowColorInt = Color.Yellow.toArgb()
    val lightBlueColorInt = Color(0xFFADD8E6).toArgb()
    val cyanColor = Color.Cyan

    // Color definitions for Earthshine shading
    val grayColor = Color.Gray          // For +-3 days
    val darkGrayColor = Color.DarkGray  // For exact New Moon
    val fullMoonRingColor = Color.Red

    // Get the actual system date for the "Today" highlight.
    // Don't use remember {} here - we want this to update if the app
    // runs past midnight so the yellow "today" box moves to the new day.
    val systemToday = LocalDate.now()

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    val yearPaint = remember {
        Paint().apply {
            color = yearColorInt
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 60f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }

    val separatorPaint = remember {
        Paint().apply {
            color = yearColorInt // Green lines
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
    }

    // Capture state for gestures
    val currentOnDateChange by rememberUpdatedState(onDateChange)
    val currentCurrentDate by rememberUpdatedState(currentDate)
    var dragAccumulator by remember { mutableStateOf(0f) }

    // Prepare data range: 21 months, center is current month
    val centerMonthIndex = 10
    val startMonthDate = currentDate.minusMonths(centerMonthIndex.toLong()).withDayOfMonth(1)
    val months = (0..20).map { i -> startMonthDate.plusMonths(i.toLong()) }
    val currentMonthYear = currentDate.year
    val currentMonthVal = currentDate.monthValue
    val currentDayVal = currentDate.dayOfMonth

    // --- CALENDAR CANVAS ---
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        val threshold = 100f // Pixels to scroll one year
                        if (abs(dragAccumulator) > threshold) {
                            val yearsToShift = -(dragAccumulator / threshold).toInt()
                            if (yearsToShift != 0) {
                                currentOnDateChange(currentCurrentDate.plusYears(yearsToShift.toLong()))
                                dragAccumulator %= threshold
                            }
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height

        val topMargin = 80f // For Year
        val headerHeight = 50f
        val footerHeight = 50f
        val sideWidth = 40f // For day numbers

        // Grid Area
        val gridW = w - (2 * sideWidth)
        val gridH = h - topMargin - headerHeight - footerHeight

        val colWidth = gridW / 21f
        val rowHeight = gridH / 31f

        // --- DRAW YEARS ---
        val monthsByYear = months.withIndex().groupBy { it.value.year }
        monthsByYear.forEach { (year, indexedValues) ->
            if (indexedValues.size >= 4) {
                val firstIndex = indexedValues.first().index
                val lastIndex = indexedValues.last().index
                val startX = sideWidth + (firstIndex * colWidth)
                val endX = sideWidth + (lastIndex * colWidth) + colWidth
                val centerX = (startX + endX) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(year.toString(), centerX, 60f, yearPaint)
                }
            }
        }

        // --- DRAW DAY NUMBERS (Left/Right) ---
        for (d in 1..31) {
            val yPos = topMargin + headerHeight + ((d - 1) * rowHeight) + (rowHeight / 2) + 8f
            val isCurrentDay = (d == currentDayVal)
            textPaint.color = if (isCurrentDay) yellowColorInt else lightBlueColorInt
            textPaint.textSize = if (isCurrentDay) 30f else 20f

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(d.toString(), sideWidth / 2f, yPos, textPaint)
                canvas.nativeCanvas.drawText(d.toString(), w - (sideWidth / 2f), yPos, textPaint)
            }
        }

        // --- PRE-CALCULATE EVENTS ---
        val blueMoonMap = mutableMapOf<LocalDate, Boolean>()
        val newMoonDates = mutableSetOf<LocalDate>()

        for (mDate in months) {
            val daysInMonth = mDate.lengthOfMonth()
            val fullMoonDays = mutableListOf<Int>()

            for (d in 1..daysInMonth) {
                val epochDay = mDate.withDayOfMonth(d).toEpochDay().toDouble()
                val phaseAngle = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay, lon))
                val nextPhaseAngle = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay + 1.0, lon))

                // Full Moon Check: Crossing 180
                if (phaseAngle < 180 && nextPhaseAngle >= 180) {
                    fullMoonDays.add(d)
                }

                // New Moon Check: Crossing 360/0 (High to Low)
                // e.g., 350 -> 5
                if (phaseAngle > 300.0 && nextPhaseAngle < 60.0) {
                    newMoonDates.add(mDate.withDayOfMonth(d))
                }
            }

            if (fullMoonDays.size >= 2) {
                blueMoonMap[mDate.withDayOfMonth(fullMoonDays[1])] = true
            }
        }

        // --- DRAW COLUMNS (Months & Moons) ---
        for (i in 0..20) {
            val mDate = months[i]
            val xCenter = sideWidth + (i * colWidth) + (colWidth / 2f)
            val monthInitial = mDate.format(DateTimeFormatter.ofPattern("MMMMM")).substring(0, 1)

            val isCurrentMonth = (mDate.year == currentMonthYear && mDate.monthValue == currentMonthVal)
            textPaint.color = if (isCurrentMonth) yellowColorInt else lightBlueColorInt
            textPaint.textSize = if (isCurrentMonth) 30f else 24f

            // Header & Footer Initials
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(monthInitial, xCenter, topMargin + 35f, textPaint)
                canvas.nativeCanvas.drawText(monthInitial, xCenter, h - 15f, textPaint)
            }

            // Dec/Jan Separator
            if (mDate.monthValue == 12) {
                val xLine = sideWidth + ((i + 1) * colWidth)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawLine(xLine, topMargin + headerHeight, xLine, h - footerHeight, separatorPaint)
                }
            }

            // Draw Moons
            val daysInMonth = mDate.lengthOfMonth()
            val moonRadius = min(colWidth, rowHeight) * 0.4f

            for (d in 1..daysInMonth) {
                val dayDate = mDate.withDayOfMonth(d)
                val epochDay = dayDate.toEpochDay().toDouble()
                val phaseAngle = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay, lon))
                val nextPhaseAngle = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay + 1.0, lon))

                val yCenter = topMargin + headerHeight + ((d - 1) * rowHeight) + (rowHeight / 2)

                val isBlueMoon = blueMoonMap[dayDate] == true
                val illuminationColor = if (isBlueMoon) cyanColor else Color.White

                // Determine Background Color by Date Offset from New Moon (Expanded to 3 days)
                val bgFillColor = when {
                    newMoonDates.contains(dayDate) -> darkGrayColor // Exact New Moon
                    newMoonDates.contains(dayDate.minusDays(1)) -> grayColor // NM + 1
                    newMoonDates.contains(dayDate.minusDays(2)) -> grayColor // NM + 2
                    newMoonDates.contains(dayDate.minusDays(3)) -> grayColor // NM + 3
                    newMoonDates.contains(dayDate.plusDays(1)) -> grayColor  // NM - 1
                    newMoonDates.contains(dayDate.plusDays(2)) -> grayColor  // NM - 2
                    newMoonDates.contains(dayDate.plusDays(3)) -> grayColor  // NM - 3
                    else -> Color.Black
                }

                drawMoonPhase(
                    center = Offset(xCenter, yCenter),
                    radius = moonRadius,
                    phaseAngle = phaseAngle,
                    lat = lat,
                    illuminatedColor = illuminationColor,
                    bgColor = bgFillColor
                )

                // Draw Red Ring if this is the exact Full Moon day
                if (phaseAngle < 180 && nextPhaseAngle >= 180) {
                    drawCircle(
                        color = fullMoonRingColor,
                        radius = moonRadius + 2.25f,
                        center = Offset(xCenter, yCenter),
                        style = Stroke(width = 4.5f)
                    )
                }

                // Draw Yellow Box for the Current Day (Real World Today only)
                if (dayDate == systemToday) {
                    val boxHalfSize = moonRadius + 7f
                    drawRect(
                        color = Color.Yellow,
                        topLeft = Offset(xCenter - boxHalfSize, yCenter - boxHalfSize),
                        size = Size(boxHalfSize * 2, boxHalfSize * 2),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }
    }
}

fun DrawScope.drawMoonPhase(
    center: Offset,
    radius: Float,
    phaseAngle: Double, // 0..360
    lat: Double,
    illuminatedColor: Color,
    bgColor: Color
) {
    // 1. Draw Background
    drawCircle(color = bgColor, radius = radius, center = center)

    // 2. Geometry Setup
    val isNorth = lat >= 0
    val isWaxing = phaseAngle in 0.0..180.0
    val pRad = Math.toRadians(phaseAngle)

    // Terminator width geometry
    val semiMinorAxis = (radius * cos(pRad)).toFloat()
    val bulgeRect = Rect(center.x - abs(semiMinorAxis), center.y - radius, center.x + abs(semiMinorAxis), center.y + radius)

    val moonPath = Path()

    if (isNorth) {
        if (isWaxing) {
            // Waxing North: Right side illuminated
            moonPath.arcTo(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), -90f, 180f, true)
            if (phaseAngle < 90) { // Crescent
                moonPath.arcTo(bulgeRect, 90f, -180f, false)
            } else { // Gibbous
                moonPath.arcTo(bulgeRect, 90f, 180f, false)
            }
        } else {
            // Waning North: Left side illuminated
            moonPath.arcTo(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), 90f, 180f, true)
            if (phaseAngle < 270) { // Gibbous
                moonPath.arcTo(bulgeRect, -90f, 180f, false)
            } else { // Crescent
                moonPath.arcTo(bulgeRect, -90f, -180f, false)
            }
        }
    } else {
        // Southern Hemisphere
        if (isWaxing) {
            // Waxing South: Left side illuminated
            moonPath.arcTo(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), 90f, 180f, true)
            if (phaseAngle < 90) { // Crescent
                moonPath.arcTo(bulgeRect, -90f, -180f, false)
            } else { // Gibbous
                moonPath.arcTo(bulgeRect, -90f, 180f, false)
            }
        } else {
            // Waning South: Right side illuminated
            moonPath.arcTo(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), -90f, 180f, true)
            if (phaseAngle < 270) { // Gibbous
                moonPath.arcTo(bulgeRect, 90f, 180f, false)
            } else { // Crescent
                moonPath.arcTo(bulgeRect, 90f, -180f, false)
            }
        }
    }

    moonPath.close()
    drawPath(path = moonPath, color = illuminatedColor)
}