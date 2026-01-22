package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun PlanetElevationsScreen(epochDay: Double, lat: Double, lon: Double, now: Instant) {
    // 1. Setup Time and Date
    val nowDate = LocalDate.ofEpochDay(epochDay.toLong())
    val offsetHours = round(lon / 15.0)

    // 2. Calculate Sun Times for Windowing
    val (riseToday, sunsetToday) = calculateSunTimes(epochDay, lat, lon, offsetHours)
    val (sunriseTomorrow, setTomorrow) = calculateSunTimes(epochDay + 1.0, lat, lon, offsetHours)
    val (_, astroSetToday) = calculateSunTimes(epochDay, lat, lon, offsetHours, -18.0)
    val (astroRiseTomorrow, _) = calculateSunTimes(epochDay + 1.0, lat, lon, offsetHours, -18.0)

    // Handle edge cases for center time
    val centerTime = if (!sunsetToday.isNaN() && !sunriseTomorrow.isNaN()) {
        val sSet = if (sunsetToday < 12.0) sunsetToday + 24.0 else sunsetToday
        val sRise = sunriseTomorrow + 24.0
        (sSet + sRise) / 2.0
    } else {
        24.0
    }

    // Window: Center +/- 12 hours
    val startHour = centerTime - 12.0
    val endHour = centerTime + 12.0

    // Colors
    val neptuneBlue = Color(0xFF4D4DFF)
    val brightBlue = Color(0xFF9999FF)
    val blueAxis = neptuneBlue
    val standardGray = Color.Gray
    val textYellow = Color.Yellow
    val sunRed = Color.Red
    val labelRed = Color.Red
    val labelGreen = Color.Green
    val currentLineGreen = Color(0xFF00FF00)
    val currentLineGray = Color.Gray

    // Data Preparation
    val planetList = remember { getOrreryPlanets().filter { it.name != "Earth" } }

    Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val w = size.width
        val h = size.height

        val headerHeight = 160f
        val footerHeight = 116f
        val chartTop = headerHeight
        val chartH = h - headerHeight - footerHeight
        val pixelsPerHour = w / 24f

        fun timeToX(t: Double): Float {
            var adjustedT = t
            while (adjustedT < startHour) adjustedT += 24.0
            while (adjustedT > endHour) adjustedT -= 24.0
            return ((adjustedT - startHour) * pixelsPerHour).toFloat()
        }

        // Native Paints
        val axisTextPaint = Paint().apply { color = brightBlue.toArgb(); textSize = 24f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
        val whiteAxisTextPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
        val titlePaint = Paint().apply { color = textYellow.toArgb(); textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val subTitlePaint = Paint().apply { color = textYellow.toArgb(); textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val labelPaint = Paint().apply { color = standardGray.toArgb(); textSize = 30f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val objectLabelPaint = Paint().apply { textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val tickTextPaint = Paint().apply { textSize = 24f; textAlign = Paint.Align.CENTER }
        val currentElevationPaint = Paint().apply { color = currentLineGreen.toArgb(); textSize = 24f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD }
        val blackFillPaint = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.FILL }

        // --- DRAW TWILIGHT SHADING ---
        if (!sunsetToday.isNaN() && !astroSetToday.isNaN()) {
            val xStart = timeToX(sunsetToday)
            val xEnd = timeToX(astroSetToday)
            if (xEnd > xStart) {
                drawRect(
                    brush = Brush.horizontalGradient(colors = listOf(neptuneBlue, Color.Black), startX = xStart, endX = xEnd),
                    topLeft = Offset(xStart, chartTop),
                    size = androidx.compose.ui.geometry.Size(xEnd - xStart, chartH)
                )
            }
        }
        if (!astroRiseTomorrow.isNaN() && !sunriseTomorrow.isNaN()) {
            val xStart = timeToX(astroRiseTomorrow)
            val xEnd = timeToX(sunriseTomorrow)
            if (xEnd > xStart) {
                drawRect(
                    brush = Brush.horizontalGradient(colors = listOf(Color.Black, neptuneBlue), startX = xStart, endX = xEnd),
                    topLeft = Offset(xStart, chartTop),
                    size = androidx.compose.ui.geometry.Size(xEnd - xStart, chartH)
                )
            }
        }

        // --- DRAW HEADER ---
        val dateStr = nowDate.format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
        drawIntoCanvas {
            it.nativeCanvas.drawText("Planet Elevations for $dateStr", w/2, 60f, titlePaint)
            it.nativeCanvas.drawText("Universal Time", w/2, 101f, subTitlePaint)
            it.nativeCanvas.drawText("Local Sidereal Time", w/2, h - 20f, subTitlePaint)
        }

        // --- AXIS & GRID ---
        val currentOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
        val currentLocal = now.atOffset(currentOffset).toLocalTime()
        val currentH = currentLocal.hour + currentLocal.minute / 60.0
        val xNow = timeToX(currentH)

        val startTickUT = floor(startHour - offsetHours).toInt()
        val endTickUT = ceil(endHour - offsetHours).toInt()
        drawLine(blueAxis, Offset(0f, chartTop), Offset(w, chartTop), strokeWidth = 2f)
        for (h_ut in startTickUT..endTickUT) {
            val hourDisplay = (h_ut % 24 + 24) % 24
            val t_std = h_ut.toDouble() + offsetHours
            val x = timeToX(t_std)
            if (x >= -2f && x <= w + 2f) {
                drawLine(blueAxis, Offset(x, chartTop), Offset(x, chartTop + 15f), strokeWidth = 2f)
                val paintToUse = if (hourDisplay % 6 == 0) whiteAxisTextPaint else axisTextPaint
                drawIntoCanvas { it.nativeCanvas.drawText(hourDisplay.toString(), x, chartTop - 10f, paintToUse) }
            }
        }

        drawLine(blueAxis, Offset(0f, h - footerHeight), Offset(w, h - footerHeight), strokeWidth = 2f)
        for (i in 0..24) {
            val hourVal = floor(startHour) + i
            val x = ((hourVal - startHour) * pixelsPerHour).toFloat()
            if (x >= -1f && x <= w + 1f) {
                drawLine(blueAxis, Offset(x, h - footerHeight), Offset(x, h - footerHeight - 15f), strokeWidth = 2f)

                // Approximate LST for grid
                val lstStr = calculateLST(now, lon) // Just to get current
                val parts = lstStr.split(":")
                val currentLST = parts[0].toDouble() + parts[1].toDouble() / 60.0
                // Simple linear shift for grid labels
                var lst = currentLST + ((hourVal - currentH) * 1.0027379)
                while (lst < 0) lst += 24.0; while (lst >= 24.0) lst -= 24.0
                val lstInt = lst.toInt()

                val paintToUse = if (lstInt % 6 == 0) whiteAxisTextPaint else axisTextPaint
                drawIntoCanvas { it.nativeCanvas.drawText(lstInt.toString(), x, h - footerHeight + 30f, paintToUse) }
            }
        }

        val xSS = timeToX(sunsetToday)
        if (xSS >= 0 && xSS <= w) {
            drawLine(standardGray, Offset(xSS, chartTop), Offset(xSS, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunset", xSS, h - footerHeight - 10f, labelPaint) }
        }
        val xSR = timeToX(sunriseTomorrow)
        if (xSR >= 0 && xSR <= w) {
            drawLine(standardGray, Offset(xSR, chartTop), Offset(xSR, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunrise", xSR, h - footerHeight - 10f, labelPaint) }
        }

        val xNightStart = max(0f, xSS)
        val xNightEnd = min(w, xSR)
        val hasNight = xNightEnd > xNightStart

        fun getHA(targetAlt: Double, decDeg: Double): Double {
            val altRad = Math.toRadians(targetAlt)
            val latRad = Math.toRadians(lat)
            val decRad = Math.toRadians(decDeg)
            val num = sin(altRad) - sin(latRad) * sin(decRad)
            val den = cos(latRad) * cos(decRad)
            val cosH = num / den
            if (cosH < -1.0 || cosH > 1.0) return Double.NaN
            return Math.toDegrees(acos(cosH)) / 15.0
        }

        fun getAlt(haHours: Double, decDeg: Double): Double {
            val haRad = Math.toRadians(haHours * 15.0)
            val latRad = Math.toRadians(lat)
            val decRad = Math.toRadians(decDeg)
            val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
            return Math.toDegrees(asin(sinAlt))
        }

        val tickIncrements = listOf(0, 20, 40, 60, 80)
        data class LabelData(val text: String, val x: Float, val y: Float, val color: Int)
        val labelsToDraw = mutableListOf<LabelData>()

        // --- ORIGINAL DRAWING FUNCTION ---
        fun drawObjectLineAndTicks(yPos: Float, name: String, ev: PlanetEvents, dec: Double, labelColorInt: Int, lineColor: Color) {
            if (!ev.rise.isNaN() && !ev.set.isNaN()) {
                val candidates = listOf(-24.0, 0.0, 24.0)
                candidates.forEach { shift ->
                    val r = ev.rise + shift
                    val s = ev.set + shift
                    var sFinal = s
                    if (sFinal < r) sFinal += 24.0
                    val overlapStart = max(startHour, r)
                    val overlapEnd = min(endHour, sFinal)

                    if (overlapEnd > overlapStart) {
                        val x1 = timeToX(overlapStart)
                        val x2 = timeToX(overlapEnd)
                        drawLine(Color.Gray, Offset(x1, yPos), Offset(x2, yPos), strokeWidth = 6f)
                        val xW1 = max(x1, xNightStart)
                        val xW2 = min(x2, xNightEnd)
                        if (xW2 > xW1 && hasNight) {
                            drawLine(Color.White, Offset(xW1, yPos), Offset(xW2, yPos), strokeWidth = 6f)
                        }
                        labelsToDraw.add(LabelData(name, (x1 + x2) / 2, yPos - 15f, labelColorInt))
                        tickIncrements.forEach { alt ->
                            val ha = getHA(alt.toDouble(), dec)
                            if (!ha.isNaN()) {
                                val tTransit = ev.transit + shift
                                val times = listOf(tTransit - ha, tTransit + ha)
                                times.forEach { tTick ->
                                    if (tTick >= overlapStart && tTick <= overlapEnd) {
                                        val xTick = timeToX(tTick)
                                        val isTickNight = (xTick >= xNightStart && xTick <= xNightEnd)
                                        val tickColor = if (isTickNight) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                                        val tColor = Color(tickColor)
                                        drawLine(tColor, Offset(xTick, yPos - 10), Offset(xTick, yPos + 10), strokeWidth = 2f)
                                        drawIntoCanvas { it.nativeCanvas.drawText(alt.toString(), xTick, yPos + 30f, tickTextPaint.apply { color = tickColor }) }
                                    }
                                }
                            }
                        }
                        val maxAlt = 90.0 - abs(lat - dec)
                        val tTransit = ev.transit + shift
                        if (tTransit >= overlapStart && tTransit <= overlapEnd) {
                            val xTick = timeToX(tTransit)
                            val isTickNight = (xTick >= xNightStart && xTick <= xNightEnd)
                            val tickColor = if (isTickNight) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                            val tColor = Color(tickColor)
                            drawLine(tColor, Offset(xTick, yPos), Offset(xTick, yPos + 20f), strokeWidth = 2f)
                            drawIntoCanvas { it.nativeCanvas.drawText(maxAlt.toInt().toString(), xTick, yPos + 45f, tickTextPaint.apply { color = tickColor }) }
                        }
                    }
                }
            }
        }

        // --- DRAW SUN LINE (Row 1) ---
        val sunY = chartTop + (chartH * 0.12f)
        if (!riseToday.isNaN() && !sunsetToday.isNaN()) {
            val transitToday = (riseToday + sunsetToday) / 2.0
            val sunDecToday = Math.toDegrees(calculateSunDeclination(epochDay))
            val xStart = 0f
            val xEnd = timeToX(sunsetToday)
            if (xEnd > 0) {
                drawLine(sunRed, Offset(xStart, sunY), Offset(xEnd, sunY), strokeWidth = 6f)
                labelsToDraw.add(LabelData("Sun", (xStart + xEnd)/2, sunY - 15f, android.graphics.Color.RED))
                tickIncrements.forEach { alt ->
                    val ha = getHA(alt.toDouble(), sunDecToday)
                    if (!ha.isNaN()) {
                        val tTick = transitToday + ha
                        if (tTick < sunsetToday) {
                            val xTick = timeToX(tTick)
                            if (xTick >= 0 && xTick <= xEnd) {
                                drawLine(sunRed, Offset(xTick, sunY - 10), Offset(xTick, sunY + 10), strokeWidth = 2f)
                                drawIntoCanvas { it.nativeCanvas.drawText(alt.toString(), xTick, sunY + 30f, tickTextPaint.apply { color = android.graphics.Color.RED }) }
                            }
                        }
                    }
                }
            }
        }
        if (!sunriseTomorrow.isNaN() && !setTomorrow.isNaN()) {
            val transitTomorrow = (sunriseTomorrow + setTomorrow) / 2.0
            val sunDecTomorrow = Math.toDegrees(calculateSunDeclination(epochDay + 1.0))
            val xStart = timeToX(sunriseTomorrow)
            val xEnd = w
            if (xStart < w) {
                drawLine(sunRed, Offset(xStart, sunY), Offset(xEnd, sunY), strokeWidth = 6f)
                labelsToDraw.add(LabelData("Sun", (xStart + xEnd)/2, sunY - 15f, android.graphics.Color.RED))
                tickIncrements.forEach { alt ->
                    val ha = getHA(alt.toDouble(), sunDecTomorrow)
                    if (!ha.isNaN()) {
                        val tTick = transitTomorrow - ha
                        if (tTick > sunriseTomorrow) {
                            val xTick = timeToX(tTick)
                            if (xTick >= xStart && xTick <= w) {
                                drawLine(sunRed, Offset(xTick, sunY - 10), Offset(xTick, sunY + 10), strokeWidth = 2f)
                                drawIntoCanvas { it.nativeCanvas.drawText(alt.toString(), xTick, sunY + 30f, tickTextPaint.apply { color = android.graphics.Color.RED }) }
                            }
                        }
                    }
                }
            }
        }

        // --- DRAW MOON (Row 2) ---
        // FIX 1: Use ComplexEventSolver for accurate Moon Times (Rise/Set)
        val moonEv = ComplexEventSolver.solveEvents(epochDay, "Moon", lat, lon, offsetHours, 0.125)

        // FIX 2: Use AstroEngine for accurate Moon Declination (better ticks)
        val moonState = AstroEngine.getBodyState("Moon", epochDay + 2440587.5 + 0.5)
        val moonDec = moonState.dec
        val moonY = chartTop + (chartH * 0.28f)

        // Moon Visibility Logic
        var moonIsUp = false
        var mRiseNorm = moonEv.rise; var mSetNorm = moonEv.set
        if (mSetNorm < mRiseNorm) mSetNorm += 24.0
        var cNorm = currentH
        while (cNorm < mRiseNorm) cNorm += 24.0
        if (cNorm < mSetNorm) moonIsUp = true
        val isNightNow = (xNow >= xSS && xNow <= xSR)
        val moonLabelColor = if (isNightNow && moonIsUp) labelGreen else labelRed

        // Use the Original Drawing Function with corrected Data
        drawObjectLineAndTicks(moonY, "Moon", moonEv, moonDec, moonLabelColor.toArgb(), Color.Gray)

        // --- DRAW PLANETS (Rows 3+) ---
        val rowsStartY = chartTop + (chartH * 0.28f)
        val planetObjs = mutableListOf<Triple<String, PlanetEvents, Double>>()
        val jd = epochDay + 2440587.5

        planetList.forEach { p ->
            // Use ComplexEventSolver for planets too for consistency (High Precision)
            val ev = ComplexEventSolver.solveEvents(epochDay, p.name, lat, lon, offsetHours, -0.5667)
            val state = AstroEngine.getBodyState(p.name, jd)
            planetObjs.add(Triple(p.name, ev, state.dec))
        }

        val rowHeight = (chartH * 0.72f) / (planetObjs.size + 1)
        val allObjs = mutableListOf<Triple<String, PlanetEvents, Double>>()
        allObjs.add(Triple("Moon", moonEv, moonDec))
        allObjs.addAll(planetObjs)

        allObjs.forEachIndexed { i, (name, ev, dec) ->
            val yPos = rowsStartY + (i * rowHeight)
            var pIsUp = false
            var rNorm = ev.rise; var sNorm = ev.set
            if (sNorm < rNorm) sNorm += 24.0
            var cNorm2 = currentH
            while (cNorm2 < rNorm) cNorm2 += 24.0
            if (cNorm2 < sNorm) pIsUp = true
            val pLabelColor = if (isNightNow && pIsUp) labelGreen else labelRed

            // Draw Line/Ticks
            drawObjectLineAndTicks(yPos, name, ev, dec, pLabelColor.toArgb(), Color.Gray)

            // --- MOON FIX 3: Topocentric Elevation for Current Altitude Text ---
            if (isNightNow && pIsUp) {
                // Default Geocentric Alt calc
                var tT = ev.transit
                var tT_adj = tT
                while(tT_adj < currentH - 12.0) tT_adj += 24.0
                while(tT_adj > currentH + 12.0) tT_adj -= 24.0
                val haHours = currentH - tT_adj

                // For Moon, calculate Topocentric Alt specifically
                if (name == "Moon") {
                    // Need accurate LST
                    val lstStr = calculateLST(now, lon)
                    val parts = lstStr.split(":")
                    val lstVal = parts[0].toDouble() + parts[1].toDouble()/60.0

                    // Geocentric State at *current* time
                    val mGeo = AstroEngine.getBodyState("Moon", epochDay + 2440587.5 + (currentH/24.0))
                    val mTopo = toTopocentric(mGeo.ra, mGeo.dec, mGeo.distGeo, lat, lon, lstVal)
                    val (_, currAlt) = calculateAzAlt(lstVal, lat, mTopo.ra/15.0, mTopo.dec)

                    if (currAlt > 0) {
                        drawIntoCanvas { it.nativeCanvas.drawText(currAlt.toInt().toString(), xNow + 5f, yPos + 57f, currentElevationPaint) }
                    }
                } else {
                    // Planets (Legacy logic is fine, or update later)
                    val currentAlt = getAlt(haHours, dec)
                    if (currentAlt > 0) {
                        drawIntoCanvas { it.nativeCanvas.drawText(currentAlt.toInt().toString(), xNow + 5f, yPos + 57f, currentElevationPaint) }
                    }
                }
            }
        }

        // Draw Current Time Line
        val nowColor = if (isNightNow) currentLineGreen else currentLineGray
        val nowPaint = Paint().apply { color = nowColor.toArgb(); textSize = 30f; textAlign = Paint.Align.CENTER }
        drawLine(nowColor, Offset(xNow, chartTop), Offset(xNow, h - footerHeight), strokeWidth = 3f)
        val utInstant = now.atZone(ZoneId.of("UTC")).toLocalTime()
        val utStr = "%02d:%02d".format(utInstant.hour, utInstant.minute)
        drawIntoCanvas { it.nativeCanvas.drawText(utStr, xNow, chartTop - 30f, nowPaint) }
        val lstNow = calculateLST(now, lon)
        drawIntoCanvas { it.nativeCanvas.drawText(lstNow, xNow, h - footerHeight + 60f, nowPaint) }

        // Draw Labels (Batched)
        labelsToDraw.forEach { item ->
            drawIntoCanvas { canvas ->
                val paint = objectLabelPaint.apply { color = item.color }
                val textBounds = Rect()
                paint.getTextBounds(item.text, 0, item.text.length, textBounds)
                val textW = textBounds.width().toFloat()
                val boxTop = item.y - 40f
                val boxBottom = item.y + 10f
                val boxLeft = item.x - (textW/2) - 10f
                val boxRight = item.x + (textW/2) + 10f
                canvas.nativeCanvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, blackFillPaint)
                canvas.nativeCanvas.drawText(item.text, item.x, item.y, paint)
            }
        }
    }
}