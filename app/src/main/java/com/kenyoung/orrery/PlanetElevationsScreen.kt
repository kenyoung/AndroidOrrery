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

    val centerTime = if (!sunsetToday.isNaN() && !sunriseTomorrow.isNaN()) {
        val sSet = if (sunsetToday < 12.0) sunsetToday + 24.0 else sunsetToday
        val sRise = sunriseTomorrow + 24.0
        (sSet + sRise) / 2.0
    } else {
        24.0
    }
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

        val currentOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
        val currentLocal = now.atOffset(currentOffset).toLocalTime()
        val currentH = currentLocal.hour + currentLocal.minute / 60.0
        val xNow = timeToX(currentH)

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

                val lstStr = calculateLST(now, lon)
                val parts = lstStr.split(":")
                val currentLST = parts[0].toDouble() + parts[1].toDouble() / 60.0
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

        // HA Calculator (Only suitable for Planets with constant Dec)
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

                        // TICK DRAWING LOGIC
                        tickIncrements.forEach { alt ->
                            if (alt == 0) {
                                // --- HORIZON (0 DEGREE) CASE ---
                                // FIXED: Use 'r' and 'sFinal' which account for the +24h wrap used in line drawing.
                                // This ensures the ticks align with the drawn line endpoints.
                                val tTickRise = r
                                val tTickSet = sFinal

                                val times = listOf(tTickRise, tTickSet)
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
                            } else {
                                // --- INTERIOR TICKS (20, 40, 60, 80) ---
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
                        }
                        // Max Alt Tick (Transit)
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
            drawObjectLineAndTicks(sunY, "Sun", PlanetEvents(riseToday, transitToday, sunsetToday), sunDecToday, android.graphics.Color.RED, sunRed)
        }
        if (!sunriseTomorrow.isNaN() && !setTomorrow.isNaN()) {
            val transitTomorrow = (sunriseTomorrow + setTomorrow) / 2.0
            val sunDecTomorrow = Math.toDegrees(calculateSunDeclination(epochDay + 1.0))
            drawObjectLineAndTicks(sunY, "Sun", PlanetEvents(sunriseTomorrow, transitTomorrow, setTomorrow), sunDecTomorrow, android.graphics.Color.RED, sunRed)
        }

        // --- DRAW MOON (Row 2) ---
        // Use Standard Calculator (matches TransitsScreen)
        val moonEv = calculateMoonEvents(epochDay, lat, lon, offsetHours)

        // Dec: Use Transit Dec for general ticks (good enough for 20,40,60)
        var moonDec = AstroEngine.getBodyState("Moon", epochDay + 2440587.5 + 0.5).dec
        if (!moonEv.transit.isNaN()) {
            val transitJD = epochDay + 2440587.5 + ((moonEv.transit - offsetHours) / 24.0)
            val mTrans = AstroEngine.getBodyState("Moon", transitJD)
            // LST at Transit
            val transitInstant = Instant.ofEpochMilli(((transitJD - 2440587.5) * 86400000.0).toLong())
            val lstParts = calculateLST(transitInstant, lon).split(":")
            val lstVal = lstParts[0].toDouble() + lstParts[1].toDouble()/60.0
            moonDec = toTopocentric(mTrans.ra, mTrans.dec, mTrans.distGeo, lat, lon, lstVal).dec
        }

        val moonY = chartTop + (chartH * 0.28f)
        var moonIsUp = false
        var mRiseNorm = moonEv.rise; var mSetNorm = moonEv.set
        if (mSetNorm < mRiseNorm) mSetNorm += 24.0
        var cNorm = currentH
        while (cNorm < mRiseNorm) cNorm += 24.0
        if (cNorm < mSetNorm) moonIsUp = true
        val isNightNow = (xNow >= xSS && xNow <= xSR)
        val moonLabelColor = if (isNightNow && moonIsUp) labelGreen else labelRed

        drawObjectLineAndTicks(moonY, "Moon", moonEv, moonDec, moonLabelColor.toArgb(), Color.Gray)

        // --- DRAW PLANETS (Rows 3+) ---
        val rowsStartY = chartTop + (chartH * 0.28f)
        val jd = epochDay + 2440587.5
        val rowHeight = (chartH * 0.72f) / (planetList.size + 1)

        planetList.forEachIndexed { i, p ->
            val yPos = rowsStartY + ((i + 1) * rowHeight)
            // Use Standard Calculator (matches TransitsScreen)
            val ev = calculatePlanetEvents(epochDay, lat, lon, offsetHours, p)
            val state = AstroEngine.getBodyState(p.name, jd)
            var pIsUp = false
            var rNorm = ev.rise; var sNorm = ev.set
            if (sNorm < rNorm) sNorm += 24.0
            var cNorm2 = currentH
            while (cNorm2 < rNorm) cNorm2 += 24.0
            if (cNorm2 < sNorm) pIsUp = true
            val pLabelColor = if (isNightNow && pIsUp) labelGreen else labelRed

            drawObjectLineAndTicks(yPos, p.name, ev, state.dec, pLabelColor.toArgb(), Color.Gray)

            // Planet Current Elevation (Simple)
            if (isNightNow && pIsUp) {
                var tT = ev.transit; var tT_adj = tT
                while(tT_adj < currentH - 12.0) tT_adj += 24.0
                while(tT_adj > currentH + 12.0) tT_adj -= 24.0
                val currentAlt = getAlt(currentH - tT_adj, state.dec)
                if (currentAlt > 0) {
                    drawIntoCanvas { it.nativeCanvas.drawText(currentAlt.toInt().toString(), xNow + 5f, yPos + 57f, currentElevationPaint) }
                }
            }
        }

        // --- MOON CURRENT ELEVATION (Precise) ---
        if (isNightNow && moonIsUp) {
            val lstStr = calculateLST(now, lon)
            val parts = lstStr.split(":")
            val lstVal = parts[0].toDouble() + parts[1].toDouble()/60.0
            val mGeo = AstroEngine.getBodyState("Moon", epochDay + 2440587.5 + (currentH/24.0))
            val mTopo = toTopocentric(mGeo.ra, mGeo.dec, mGeo.distGeo, lat, lon, lstVal)
            val (_, currAlt) = calculateAzAlt(lstVal, lat, mTopo.ra/15.0, mTopo.dec)
            if (currAlt > 0) {
                drawIntoCanvas { it.nativeCanvas.drawText(currAlt.toInt().toString(), xNow + 5f, moonY + 57f, currentElevationPaint) }
            }
        }

        // Current Time Line
        val nowColor = if (isNightNow) currentLineGreen else currentLineGray
        val nowPaint = Paint().apply { color = nowColor.toArgb(); textSize = 30f; textAlign = Paint.Align.CENTER }
        drawLine(nowColor, Offset(xNow, chartTop), Offset(xNow, h - footerHeight), strokeWidth = 3f)
        val utInstant = now.atZone(ZoneId.of("UTC")).toLocalTime()
        val utStr = "%02d:%02d".format(utInstant.hour, utInstant.minute)
        drawIntoCanvas { it.nativeCanvas.drawText(utStr, xNow, chartTop - 30f, nowPaint) }
        val lstNow = calculateLST(now, lon)
        drawIntoCanvas { it.nativeCanvas.drawText(lstNow, xNow, h - footerHeight + 60f, nowPaint) }

        // Labels
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