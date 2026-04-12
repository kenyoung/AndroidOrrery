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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

private fun phaseAnglePair(epochDay: Double, lon: Double): Pair<Double, Double> {
    val phase = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay, lon))
    val nextPhase = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(epochDay + 1.0, lon))
    return Pair(phase, nextPhase)
}

@Composable
fun MoonCalendarScreen(currentDate: LocalDate, lat: Double, lon: Double, onDateChange: (LocalDate) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bgColor = Color.Black
    val yearColorInt = Color.Green.toArgb()
    val yellowColorInt = Color.Yellow.toArgb()
    val lightBlueColorInt = Color(0xFFADD8E6).toArgb()

    val fullMoonRingColor = Color.Red

    val originalBitmap = remember(lat < 0.0) {
        val raw = context.assets.open("fullMoon.png").use { BitmapFactory.decodeStream(it) }
        if (raw != null && lat < 0.0) {
            val matrix = android.graphics.Matrix().apply { postRotate(180f) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else raw
    }

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
        if (originalBitmap == null) return@Canvas
        withDensityScaling { w, h ->

        val topMargin = 80f // For Year
        val headerHeight = 50f
        val footerHeight = 50f
        val sideWidth = 40f // For day numbers

        // Grid Area
        val gridW = w - (2 * sideWidth)
        val gridH = h - topMargin - headerHeight - footerHeight

        val colWidth = gridW / 21f
        val rowHeight = gridH / 31f

        // Moon thumbnail: same size for every cell, so scale once up front.
        val moonRadius = min(colWidth, rowHeight) * 0.4f
        val thumbSize = (moonRadius * 2f).toInt().coerceAtLeast(16)
        val thumbnail = android.graphics.Bitmap.createScaledBitmap(originalBitmap, thumbSize, thumbSize, true)
        val bitmapPaint = Paint().apply { isFilterBitmap = true }

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

        for (mDate in months) {
            val daysInMonth = mDate.lengthOfMonth()
            val fullMoonDays = mutableListOf<Int>()

            for (d in 1..daysInMonth) {
                val epochDay = mDate.withDayOfMonth(d).toEpochDay().toDouble()
                val (phaseAngle, nextPhaseAngle) = phaseAnglePair(epochDay, lon)

                // Full Moon Check: Crossing 180
                if (phaseAngle < 180 && nextPhaseAngle >= 180) {
                    fullMoonDays.add(d)
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

            for (d in 1..daysInMonth) {
                val dayDate = mDate.withDayOfMonth(d)
                val epochDay = dayDate.toEpochDay().toDouble()
                val (phaseAngle, nextPhaseAngle) = phaseAnglePair(epochDay, lon)

                val yCenter = topMargin + headerHeight + ((d - 1) * rowHeight) + (rowHeight / 2)

                val isBlueMoon = blueMoonMap[dayDate] == true
                var dayBitmap = createPhasedMoonBitmap(thumbnail, phaseAngle, lat)
                if (isBlueMoon) {
                    dayBitmap = adjustBrightness(dayBitmap, BRIGHTNESS_BOOST, blueTint = true)
                }
                dayBitmap = adjustBrightness(dayBitmap, 1.5f, blueTint = false)

                val imgLeft = (xCenter - thumbSize / 2f).toInt()
                val imgTop = (yCenter - thumbSize / 2f).toInt()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(
                        dayBitmap, null,
                        android.graphics.Rect(imgLeft, imgTop, imgLeft + thumbSize, imgTop + thumbSize),
                        bitmapPaint
                    )
                }

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
}