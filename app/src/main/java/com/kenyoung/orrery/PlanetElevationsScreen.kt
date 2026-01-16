package com.kenyoung.orrery

import android.graphics.Paint
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
    val zoneId = ZoneId.systemDefault()
    val nowZoned = now.atZone(zoneId)
    val nowDate = LocalDate.ofEpochDay(epochDay.toLong())

    // 1. Calculate Midpoint (Center of Display)
    val offsetHours = round(lon / 15.0)

    // Sun Times: Standard (-0.833 deg)
    val (riseToday, sunsetToday) = calculateSunTimes(epochDay, lat, lon, offsetHours)
    val (sunriseTomorrow, setTomorrow) = calculateSunTimes(epochDay + 1.0, lat, lon, offsetHours)

    // Sun Times: Astronomical Twilight (-18 deg)
    // We only need Astro Set for today (Evening) and Astro Rise for tomorrow (Morning)
    val (_, astroSetToday) = calculateSunTimes(epochDay, lat, lon, offsetHours, -18.0)
    val (astroRiseTomorrow, _) = calculateSunTimes(epochDay + 1.0, lat, lon, offsetHours, -18.0)

    // Handle edge cases
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
    val neptuneBlue = Color(0xFF4D4DFF) // Less saturated blue
    val blueAxis = neptuneBlue
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

        // LAYOUT ADJUSTMENTS
        val headerHeight = 160f
        val footerHeight = 116f

        val chartTop = headerHeight
        val chartH = h - headerHeight - footerHeight
        val chartBottom = h - footerHeight

        val pixelsPerHour = w / 24f

        fun timeToX(t: Double): Float {
            var adjustedT = t
            while (adjustedT < startHour) adjustedT += 24.0
            while (adjustedT > endHour) adjustedT -= 24.0
            return ((adjustedT - startHour) * pixelsPerHour).toFloat()
        }

        // Native Paints
        val axisTextPaint = Paint().apply {
            color = blueAxis.toArgb()
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }
        val whiteAxisTextPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }
        val titlePaint = Paint().apply {
            color = textYellow.toArgb()
            textSize = 48f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val subTitlePaint = Paint().apply {
            color = textYellow.toArgb()
            textSize = 36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val labelPaint = Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val sunLabelPaint = Paint().apply {
            color = android.graphics.Color.RED
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val objectLabelPaint = Paint().apply {
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val tickTextPaint = Paint().apply {
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        val currentElevationPaint = Paint().apply {
            color = currentLineGreen.toArgb()
            textSize = 24f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT_BOLD
        }

        // --- DRAW TWILIGHT SHADING (Background) ---
        // Evening Twilight: From Sunset (Blue) -> Astro End (Black)
        if (!sunsetToday.isNaN() && !astroSetToday.isNaN()) {
            val xStart = timeToX(sunsetToday)
            val xEnd = timeToX(astroSetToday)

            // Ensure we draw left-to-right (handle potential wrapping if near edge)
            // timeToX normalizes, so xEnd should generally be > xStart for evening unless wrapping occurred
            if (xEnd > xStart) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(neptuneBlue, Color.Black),
                        startX = xStart,
                        endX = xEnd
                    ),
                    topLeft = Offset(xStart, chartTop),
                    size = androidx.compose.ui.geometry.Size(xEnd - xStart, chartH)
                )
            }
        }

        // Morning Twilight: From Astro Start (Black) -> Sunrise (Blue)
        if (!astroRiseTomorrow.isNaN() && !sunriseTomorrow.isNaN()) {
            val xStart = timeToX(astroRiseTomorrow)
            val xEnd = timeToX(sunriseTomorrow)

            if (xEnd > xStart) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, neptuneBlue),
                        startX = xStart,
                        endX = xEnd
                    ),
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

        // --- CALCULATE CURRENT TIME & POSITION ---
        val currentOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
        val currentLocal = now.atOffset(currentOffset).toLocalTime()
        val currentH = currentLocal.hour + currentLocal.minute / 60.0
        val xNow = timeToX(currentH)

        // --- DRAW UT AXIS (TOP) ---
        val startUT = startHour - offsetHours
        val endUT = endHour - offsetHours
        drawLine(blueAxis, Offset(0f, chartTop), Offset(w, chartTop), strokeWidth = 2f)
        val startTickUT = floor(startUT).toInt()
        val endTickUT = ceil(endUT).toInt()
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

        // --- DRAW LST AXIS (BOTTOM) ---
        fun getLST(t: Double): Double {
            val offset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
            val localTime = nowZoned.toLocalTime()
            val currentH = localTime.hour + localTime.minute / 60.0
            val diff = t - currentH
            val currentLSTStr = calculateLST(now, lon)
            val parts = currentLSTStr.split(":")
            val currentLST = parts[0].toDouble() + parts[1].toDouble() / 60.0
            var lst = currentLST + (diff * 1.0027379)
            while (lst < 0) lst += 24.0
            while (lst >= 24.0) lst -= 24.0
            return lst
        }
        drawLine(blueAxis, Offset(0f, h - footerHeight), Offset(w, h - footerHeight), strokeWidth = 2f)
        for (i in 0..24) {
            val hourVal = floor(startHour) + i
            val x = ((hourVal - startHour) * pixelsPerHour).toFloat()
            if (x >= -1f && x <= w + 1f) {
                drawLine(blueAxis, Offset(x, h - footerHeight), Offset(x, h - footerHeight - 15f), strokeWidth = 2f)
                val lst = getLST(hourVal)
                val lstInt = lst.toInt()
                val paintToUse = if (lstInt % 6 == 0) whiteAxisTextPaint else axisTextPaint
                drawIntoCanvas { it.nativeCanvas.drawText(lstInt.toString(), x, h - footerHeight + 30f, paintToUse) }
            }
        }

        // --- DRAW SUNSET/SUNRISE VERTICAL LINES ---
        val xSS = timeToX(sunsetToday)
        if (xSS >= 0 && xSS <= w) {
            drawLine(Color.Gray, Offset(xSS, chartTop), Offset(xSS, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunset", xSS, h - footerHeight - 10f, labelPaint) }
        }
        val xSR = timeToX(sunriseTomorrow)
        if (xSR >= 0 && xSR <= w) {
            drawLine(Color.Gray, Offset(xSR, chartTop), Offset(xSR, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunrise", xSR, h - footerHeight - 10f, labelPaint) }
        }

        // Night Window X coords (For White Line Logic)
        val xNightStart = max(0f, xSS)
        val xNightEnd = min(w, xSR)
        val hasNight = xNightEnd > xNightStart

        // Helper: Get HA from Alt
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

        // Helper: Get Alt from HA (for current elevation)
        fun getAlt(haHours: Double, decDeg: Double): Double {
            val haRad = Math.toRadians(haHours * 15.0)
            val latRad = Math.toRadians(lat)
            val decRad = Math.toRadians(decDeg)
            val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
            return Math.toDegrees(asin(sinAlt))
        }

        // --- DRAW SUN LINE (Row 1) ---
        val sunY = chartTop + (chartH * 0.12f)
        if (!riseToday.isNaN() && !sunsetToday.isNaN()) {
            val transitToday = (riseToday + sunsetToday) / 2.0
            val sunDecToday = Math.toDegrees(calculateSunDeclination(epochDay))
            val xStart = 0f
            val xEnd = timeToX(sunsetToday)
            if (xEnd > 0) {
                drawLine(sunRed, Offset(xStart, sunY), Offset(xEnd, sunY), strokeWidth = 3f)
                drawIntoCanvas { it.nativeCanvas.drawText("Sun", (xStart + xEnd) / 2, sunY - 15f, sunLabelPaint) }
                listOf(20, 40, 60, 80).forEach { alt ->
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
                drawLine(sunRed, Offset(xStart, sunY), Offset(xEnd, sunY), strokeWidth = 3f)
                drawIntoCanvas { it.nativeCanvas.drawText("Sun", (xStart + xEnd) / 2, sunY - 15f, sunLabelPaint) }
                listOf(20, 40, 60, 80).forEach { alt ->
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

        // --- DRAW MOON (Row 2) AND PLANETS (Rows 3+) ---
        val allObjects = mutableListOf<Triple<String, PlanetEvents, Double>>() // Name, Events, Dec

        // 1. Add Moon
        val moonEv = calculateMoonEvents(epochDay, lat, lon, offsetHours)
        val moonPos = calculateMoonPosition(epochDay)
        allObjects.add(Triple("Moon", moonEv, moonPos.dec))

        // 2. Add Planets
        planetList.forEach { p ->
            val ev = calculatePlanetEvents(epochDay, lat, lon, offsetHours, p)
            val d = (2440587.5 + epochDay + 0.5) - 2451545.0
            val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
            val Np = Math.toRadians(p.N); val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar)
            val Mp = Lp - w_bar_p; val Ep = solveKepler(Mp, p.e)
            val xv = p.a * (cos(Ep) - p.e); val yv = p.a * sqrt(1 - p.e*p.e) * sin(Ep)
            val v = atan2(yv, xv); val u = v + w_bar_p - Np; val rp = sqrt(xv*xv + yv*yv)
            val xh = rp * (cos(u) * cos(Np) - sin(u) * sin(Np) * cos(ip))
            val yh = rp * (cos(u) * sin(Np) + sin(u) * cos(Np) * cos(ip))
            val zh = rp * (sin(u) * sin(ip))
            val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
            val Le = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
            val Re = 1.00014 - 0.01671 * cos(Me)
            val xe = Re * cos(Le); val ye = Re * sin(Le); val ze = 0.0
            val xg = xh - xe; val yg = yh - ye; val zg = zh - ze
            val ecl = Math.toRadians(23.439 - 0.0000004 * d)
            val yeq = yg * cos(ecl) - zg * sin(ecl); val zeq = yg * sin(ecl) + zg * cos(ecl); val xeq = xg
            val dec = Math.toDegrees(atan2(zeq, sqrt(xeq*xeq + yeq*yeq)))

            allObjects.add(Triple(p.name, ev, dec))
        }

        // --- DRAW LOOP ---
        val rowsStartY = chartTop + (chartH * 0.28f)
        val rowHeight = (chartH * 0.72f) / allObjects.size

        allObjects.forEachIndexed { i, (name, ev, dec) ->
            val yPos = rowsStartY + (i * rowHeight)

            if (!ev.rise.isNaN() && !ev.set.isNaN()) {
                val candidates = listOf(-24.0, 0.0, 24.0)

                // Check if Object is UP now (for label color)
                var isUp = false
                var rNorm = ev.rise
                var sNorm = ev.set
                if (sNorm < rNorm) sNorm += 24.0
                var cNorm = currentH
                while (cNorm < rNorm) cNorm += 24.0
                if (cNorm < sNorm) isUp = true

                val isNightNow = (xNow >= xSS && xNow <= xSR)
                val labelColor = if (isNightNow && isUp) labelGreen else labelRed

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

                        // 1. Draw Gray Line
                        drawLine(Color.Gray, Offset(x1, yPos), Offset(x2, yPos), strokeWidth = 3f)

                        // 2. Draw White Line (Night)
                        val xW1 = max(x1, xNightStart)
                        val xW2 = min(x2, xNightEnd)
                        if (xW2 > xW1 && hasNight) {
                            drawLine(Color.White, Offset(xW1, yPos), Offset(xW2, yPos), strokeWidth = 3f)
                        }

                        // 3. Draw Label
                        drawIntoCanvas { it.nativeCanvas.drawText(name, (x1 + x2) / 2, yPos - 15f, objectLabelPaint.apply { color = labelColor.toArgb() }) }

                        // 4. Draw Ticks
                        listOf(20, 40, 60, 80).forEach { alt ->
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

                        // 5. Draw Transit Tick
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

                // --- 6. DRAW CURRENT ELEVATION ---
                if (isNightNow && isUp) {
                    val tTransitBase = ev.transit
                    var tT = tTransitBase
                    while(tT < currentH - 12.0) tT += 24.0
                    while(tT > currentH + 12.0) tT -= 24.0
                    val haHours = currentH - tT
                    val currentAlt = getAlt(haHours, dec)
                    if (currentAlt > 0) {
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(
                                currentAlt.toInt().toString(),
                                xNow + 5f,
                                yPos + 57f,
                                currentElevationPaint
                            )
                        }
                    }
                }
            }
        }

        // --- DRAW CURRENT TIME LINE ---
        val isNightNow = (xNow >= xSS && xNow <= xSR)
        val nowColor = if (isNightNow) currentLineGreen else currentLineGray
        val nowPaint = Paint().apply { color = nowColor.toArgb(); textSize = 30f; textAlign = Paint.Align.CENTER }

        drawLine(nowColor, Offset(xNow, chartTop), Offset(xNow, h - footerHeight), strokeWidth = 3f)

        val utInstant = now.atZone(ZoneId.of("UTC")).toLocalTime()
        val utStr = "%02d:%02d".format(utInstant.hour, utInstant.minute)
        drawIntoCanvas { it.nativeCanvas.drawText(utStr, xNow, chartTop - 30f, nowPaint) }

        val lstNow = getLST(currentH)
        val lstH = floor(lstNow).toInt()
        val lstM = ((lstNow - lstH) * 60).toInt()
        val lstStr = "%02d:%02d".format(lstH, lstM)
        drawIntoCanvas { it.nativeCanvas.drawText(lstStr, xNow, h - footerHeight + 60f, nowPaint) }
    }
}