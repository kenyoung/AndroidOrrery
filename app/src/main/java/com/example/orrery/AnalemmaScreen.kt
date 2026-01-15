package com.example.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun AnalemmaScreen(instant: Instant, lat: Double) {
    val zoneId = ZoneId.systemDefault()
    val currentDateTime = instant.atZone(zoneId)
    val currentYear = currentDateTime.year
    val todayEpochDay = currentDateTime.toLocalDate().toEpochDay().toDouble()

    val gridColorInt = 0xFF000080.toInt() // Dark Blue
    val curveColor = Color.Green
    val markerColorInt = Color.Yellow.toArgb()
    val textColorInt = Color.White.toArgb()
    val nowColor = Color.Red

    // Paint objects
    val textPaint = remember {
        Paint().apply {
            color = textColorInt
            textSize = 60f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    val axisNumberPaint = remember {
        Paint().apply {
            color = textColorInt
            textSize = 45f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    val markerTextPaint = remember {
        Paint().apply {
            color = markerColorInt
            textSize = 48f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val w = size.width
        val h = size.height

        val paddingY = 120f
        val paddingX = 180f

        val graphW = w - 2 * paddingX
        val graphH = h - 2 * paddingY
        val centerX = w / 2f

        //--- 1. Define Coordinate System Scaling ---

        val minEoT = -17.0
        val maxEoT = 17.0
        val eotRange = maxEoT - minEoT
        val pixelsPerMinute = graphW / eotRange

        val maxAltPossible = 90.0 - abs(lat - 23.44)
        val minAltPossible = 90.0 - abs(lat - (-23.44))

        val yPadDegrees = 5.0
        var maxAltGraph = (maxAltPossible + yPadDegrees).coerceAtMost(90.0)
        var minAltGraph = (minAltPossible - yPadDegrees).coerceAtLeast(-10.0)

        if (minAltGraph >= maxAltGraph) {
            maxAltGraph = minAltGraph + 10.0
        }

        val altRange = maxAltGraph - minAltGraph
        val pixelsPerDegree = graphH / altRange

        fun mapEoTToX(eotMinutes: Double): Float {
            return centerX + ((eotMinutes * pixelsPerMinute).toFloat())
        }

        fun mapAltToY(altitudeDegrees: Double): Float {
            return (paddingY + graphH) - ((altitudeDegrees - minAltGraph) * pixelsPerDegree).toFloat()
        }


        //--- 2. Draw Grid & Axes (First) ---
        val gridWidth = 4f

        // Vertical grid lines (EoT) every 5 minutes
        for (e in -15..15 step 5) {
            val x = mapEoTToX(e.toDouble())
            drawLine(Color(gridColorInt), start = Offset(x, paddingY), end = Offset(x, h - paddingY), strokeWidth = gridWidth)
            drawIntoCanvas {
                axisNumberPaint.textAlign = Paint.Align.CENTER
                it.nativeCanvas.drawText(e.toString(), x, h - paddingY + 10f, axisNumberPaint)
            }
        }

        // Horizontal grid lines (Altitude) every 10 degrees
        val startGridAlt = (ceil(minAltGraph / 10.0) * 10.0).toInt()
        val endGridAlt = (floor(maxAltGraph / 10.0) * 10.0).toInt()
        for (a in startGridAlt..endGridAlt step 10) {
            val y = mapAltToY(a.toDouble())
            drawLine(Color(gridColorInt), start = Offset(paddingX, y), end = Offset(w - paddingX, y), strokeWidth = gridWidth)
            drawIntoCanvas {
                axisNumberPaint.textAlign = Paint.Align.RIGHT
                it.nativeCanvas.drawText(a.toString(), paddingX - 20f, y + 15f, axisNumberPaint)
            }
        }

        // Axis Titles
        drawIntoCanvas {
            textPaint.textAlign = Paint.Align.CENTER
            // Title
            textPaint.textSize = 100f
            textPaint.color = markerColorInt
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            it.nativeCanvas.drawText("$currentYear Analemma", centerX, paddingY - 40f, textPaint)

            // X-Axis Label
            textPaint.textSize = 70f
            textPaint.color = textColorInt
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            it.nativeCanvas.drawText("Equation of Time (minutes)", centerX, h - 20f, textPaint)

            // Y-Axis Label (rotated)
            it.nativeCanvas.save()
            it.nativeCanvas.rotate(-90f, 50f, h / 2f)
            it.nativeCanvas.drawText("Sun Altitude at Transit (degrees)", 50f, h / 2f, textPaint)
            it.nativeCanvas.restore()
        }


        //--- 3. Draw the Analemma Curve ---
        val analemmaPath = androidx.compose.ui.graphics.Path()
        val firstDayOfYear = LocalDate.of(currentYear, 1, 1)
        val daysInYear = firstDayOfYear.lengthOfYear()
        val startEpoch = firstDayOfYear.toEpochDay().toDouble()

        for (i in 0 until daysInYear) {
            val d = startEpoch + i
            val eotMinutes = calculateEquationOfTimeMinutes(d)
            val x = mapEoTToX(eotMinutes)
            val declinationRad = calculateSunDeclination(d)
            val declinationDeg = Math.toDegrees(declinationRad)
            val altitudeDeg = 90.0 - abs(lat - declinationDeg)
            val y = mapAltToY(altitudeDeg)

            if (i == 0) {
                analemmaPath.moveTo(x, y)
            } else {
                analemmaPath.lineTo(x, y)
            }
        }
        analemmaPath.close()
        drawPath(analemmaPath, curveColor, style = Stroke(width = 6f))


        //--- 4. Draw Month Markers and Labels ---
        for (month in 1..12) {
            val date = LocalDate.of(currentYear, month, 1)
            val d = date.toEpochDay().toDouble()
            val eotMinutes = calculateEquationOfTimeMinutes(d)
            val decRad = calculateSunDeclination(d)
            val altDeg = 90.0 - abs(lat - Math.toDegrees(decRad))

            val x = mapEoTToX(eotMinutes)
            val y = mapAltToY(altDeg)

            drawCircle(Color(markerColorInt), radius = 10f, center = Offset(x, y))

            drawIntoCanvas {
                val monthName = date.format(DateTimeFormatter.ofPattern("MMM d"))

                var textAlign = if (eotMinutes > 0) Paint.Align.LEFT else Paint.Align.RIGHT
                var xOffset = if (eotMinutes > 0) 20f else -20f

                if (month == 9) {
                    textAlign = Paint.Align.LEFT
                    xOffset = 20f
                }

                markerTextPaint.textAlign = textAlign
                it.nativeCanvas.drawText(monthName, x + xOffset, y + 15f, markerTextPaint)
            }
        }

        //--- 5. Draw Solstice/Equinox Markers & Logic ---
        val solsticeDates = listOf(LocalDate.of(currentYear, 6, 21), LocalDate.of(currentYear, 12, 21))
        solsticeDates.forEach { date ->
            val d = date.toEpochDay().toDouble()
            val eot = calculateEquationOfTimeMinutes(d); val alt = 90.0 - abs(lat - Math.toDegrees(calculateSunDeclination(d)))
            val x = mapEoTToX(eot); val y = mapAltToY(alt)
            drawCircle(Color(markerColorInt), radius = 12f, center = Offset(x, y))
            drawIntoCanvas {
                markerTextPaint.textAlign = Paint.Align.CENTER
                val yOffset = if (date.monthValue == 6) -35f else 55f
                it.nativeCanvas.drawText("Solstice", x, y + yOffset, markerTextPaint)
            }
        }

        // Calculate positions for Equinoxes
        val equinoxFontWidth = 32f

        val vernalDate = LocalDate.of(currentYear, 3, 20)
        val autumnalDate = LocalDate.of(currentYear, 9, 23)

        val dVernal = vernalDate.toEpochDay().toDouble()
        val dAutumnal = autumnalDate.toEpochDay().toDouble()

        // Calculate curve X positions at Equinox
        val xCurveLeft = mapEoTToX(calculateEquationOfTimeMinutes(dVernal))
        val xCurveRight = mapEoTToX(calculateEquationOfTimeMinutes(dAutumnal))

        val yEq = mapAltToY(90.0 - abs(lat))

        // Measure text width for "Equinoxes"
        val labelStr = "Equinoxes"
        markerTextPaint.textSize = equinoxFontWidth
        val labelWidth = markerTextPaint.measureText(labelStr)
        val halfLabelWidth = labelWidth / 2f

        // Arrow Logic:
        val arrowLStart = centerX - halfLabelWidth - equinoxFontWidth
        val arrowLEnd = xCurveLeft + equinoxFontWidth

        val arrowRStart = centerX + halfLabelWidth + equinoxFontWidth
        val arrowREnd = xCurveRight - equinoxFontWidth

        // Draw Lines
        drawLine(Color(markerColorInt), start = Offset(arrowLStart, yEq), end = Offset(arrowLEnd, yEq), strokeWidth = 4f)
        drawLine(Color(markerColorInt), start = Offset(arrowRStart, yEq), end = Offset(arrowREnd, yEq), strokeWidth = 4f)

        // Draw Arrowheads
        val pathArrowL = androidx.compose.ui.graphics.Path().apply {
            moveTo(arrowLEnd, yEq)
            lineTo(arrowLEnd + 25f, yEq - 15f)
            lineTo(arrowLEnd + 25f, yEq + 15f)
            close()
        }
        drawPath(pathArrowL, Color(markerColorInt))

        val pathArrowR = androidx.compose.ui.graphics.Path().apply {
            moveTo(arrowREnd, yEq)
            lineTo(arrowREnd - 25f, yEq - 15f)
            lineTo(arrowREnd - 25f, yEq + 15f)
            close()
        }
        drawPath(pathArrowR, Color(markerColorInt))

        // Draw Text Label
        drawIntoCanvas {
            markerTextPaint.textAlign = Paint.Align.CENTER
            markerTextPaint.textSize = equinoxFontWidth
            it.nativeCanvas.drawText(labelStr, centerX, yEq + (equinoxFontWidth / 3f), markerTextPaint)
        }

        // --- ARIES SYMBOL (UPDATED) ---
        val ariesX = xCurveLeft - 50f // Position to the left of the curve
        val ariesY = yEq

        // Draw Black Background Circle to hide grid lines behind the symbol
        drawCircle(Color.Black, radius = 25f, center = Offset(ariesX, ariesY))

        drawIntoCanvas {
            markerTextPaint.textAlign = Paint.Align.CENTER
            // Set font size to 48f (same as month labels)
            markerTextPaint.textSize = 48f
            // Set color to White
            markerTextPaint.color = Color.White.toArgb()

            // Vertical centering
            val textCenterOffset = (markerTextPaint.descent() + markerTextPaint.ascent()) / 2

            // Draw unicode character with text presentation selector to avoid emoji color
            it.nativeCanvas.drawText("\u2648\uFE0E", ariesX, ariesY - textCenterOffset, markerTextPaint)

            // Reset paint for next loop/safety
            markerTextPaint.color = markerColorInt
        }


        //--- 6. Draw "Now" Marker ---
        val nowEoTMinutes = calculateEquationOfTimeMinutes(todayEpochDay)
        val nowX = mapEoTToX(nowEoTMinutes)
        val nowDecRad = calculateSunDeclination(todayEpochDay)
        val nowAltDeg = 90.0 - abs(lat - Math.toDegrees(nowDecRad))
        val nowY = mapAltToY(nowAltDeg)

        drawCircle(Color.White, radius = 18f, center = Offset(nowX, nowY), style = Stroke(width = 4f))
        drawCircle(nowColor, radius = 9f, center = Offset(nowX, nowY))

        drawIntoCanvas {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.White.toArgb()
            it.nativeCanvas.drawText("Now", nowX + 30f, nowY + 20f, textPaint)
        }
    }
}