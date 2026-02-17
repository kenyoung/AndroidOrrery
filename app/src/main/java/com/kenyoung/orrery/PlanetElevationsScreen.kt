package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun PlanetElevationsScreen(epochDay: Double, lat: Double, lon: Double, now: Instant, stdOffsetHours: Double, stdTimeLabel: String, useLocalTime: Boolean, onTimeDisplayChange: (Boolean) -> Unit) {

    // 1. Setup Time and Date
    val offsetHours = lon / 15.0

    // Determine the "observing night" date. In astronomy, a night is identified by
    // its evening date - e.g., the night of June 15 runs from sunset June 15 to
    // sunrise June 16. If local time is before sunrise, we're in the morning portion
    // of the previous night, so use yesterday's date. This keeps the entire night
    // (sunset through sunrise) using consistent rise/set data.
    val currentOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
    val localDateTime = now.atOffset(currentOffset).toLocalDateTime()
    val todayDate = localDateTime.toLocalDate()
    val todayEpochDay = todayDate.toEpochDay().toDouble()

    // Calculate today's sunrise to determine if we should show yesterday's "observing night"
    val (todaySunrise, _) = calculateSunTimes(todayEpochDay, lat, lon, offsetHours)
    val currentLocalHours = localDateTime.hour + localDateTime.minute / 60.0

    // If before today's sunrise, we're still on last night's "observing night"
    val observingDate = if (!todaySunrise.isNaN() && currentLocalHours < todaySunrise) {
        todayDate.minusDays(1)
    } else {
        todayDate
    }
    val epochDayInt = observingDate.toEpochDay().toDouble()
    val nowDate = observingDate

    // 2. Calculate Sun Times for Windowing
    val (riseToday, sunsetToday) = calculateSunTimes(epochDayInt, lat, lon, offsetHours)
    val (sunriseTomorrow, setTomorrow) = calculateSunTimes(epochDayInt + 1.0, lat, lon, offsetHours)
    val (_, astroSetToday) = calculateSunTimes(epochDayInt, lat, lon, offsetHours, ASTRONOMICAL_TWILIGHT)
    val (astroRiseTomorrow, _) = calculateSunTimes(epochDayInt + 1.0, lat, lon, offsetHours, ASTRONOMICAL_TWILIGHT)

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
    val textLabelColor = LabelColor
    val labelRed = Color.Red
    val labelGreen = Color.Green
    val currentLineGreen = Color(0xFF00FF00)
    val currentLineGray = Color.Gray

    // Data Preparation
    val planetList = remember { getOrreryPlanets().filter { it.name != "Earth" } }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Canvas(modifier = Modifier.fillMaxSize().weight(1f)) {
        val w = size.width
        val h = size.height

        // Native Paints
        val axisTextPaint = Paint().apply { color = brightBlue.toArgb(); textSize = 24f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
        val whiteAxisTextPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
        val titlePaintYellow = Paint().apply { color = textLabelColor.toArgb(); textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT }
        val titlePaintWhite = Paint().apply { color = android.graphics.Color.WHITE; textSize = 48f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT }
        val subTitlePaint = Paint().apply { color = textLabelColor.toArgb(); textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val labelPaint = Paint().apply { color = standardGray.toArgb(); textSize = 30f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val objectLabelPaint = Paint().apply { textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val tickTextPaint = Paint().apply { textSize = 24f; textAlign = Paint.Align.CENTER }
        val currentElevationPaint = Paint().apply { color = currentLineGreen.toArgb(); textSize = 24f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD }
        val blackFillPaint = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.FILL }
        val nowPaint = Paint().apply { textSize = 30f; textAlign = Paint.Align.CENTER }

        // Design constants
        val topMargin = 8f
        val bottomMargin = 8f
        val sectionGap = 4f
        val tickLen = 15f
        val tickHalf = 10f
        val labelGap = 4f
        val boxPad = 6f

        // Header layout (top-down band stacking — subtitle removed, timezone in title)
        val titleBaseline = topMargin - titlePaintYellow.ascent()
        val currentUtBaseline = titleBaseline + titlePaintYellow.descent() + sectionGap - nowPaint.ascent()
        val utAxisNumberBaseline = currentUtBaseline + nowPaint.descent() + sectionGap - axisTextPaint.ascent()
        val headerHeight = utAxisNumberBaseline + axisTextPaint.descent() + labelGap

        // Footer layout (bottom-up band stacking — halved bottomMargin to move LST label closer to radio buttons)
        val halfBottomMargin = bottomMargin / 2f
        val footerHeight = labelGap - axisTextPaint.ascent() + axisTextPaint.descent() + sectionGap - nowPaint.ascent() + nowPaint.descent() + sectionGap - subTitlePaint.ascent() + subTitlePaint.descent() + halfBottomMargin
        val lstSubtitleBaseline = h - halfBottomMargin - subTitlePaint.descent()
        val currentLstBaseline = lstSubtitleBaseline + subTitlePaint.ascent() - sectionGap - nowPaint.descent()
        val lstAxisNumberBaseline = currentLstBaseline + nowPaint.ascent() - sectionGap - axisTextPaint.descent()

        // Tick label offsets from body-line Y position
        val tickNumberOffset = tickHalf + labelGap - tickTextPaint.ascent()
        val elevReadoutOffset = tickNumberOffset + tickTextPaint.fontSpacing

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
        var currentH = currentLocal.hour + currentLocal.minute / 60.0 + currentLocal.second / 3600.0
        while (currentH < startHour) currentH += 24.0
        while (currentH > endHour) currentH -= 24.0
        val xNow = timeToX(currentH)
        val currentUtEpochDay = now.epochSecond.toDouble() / 86400.0

        // Asterisk tracking — same algorithm as Compass page
        val displayOffsetHours = if (useLocalTime) stdOffsetHours else 0.0
        val currentDisplayDate = floor(currentUtEpochDay + displayOffsetHours / 24.0).toLong()
        var anyAsterisk = false

        fun isRiseTomorrow(ev: PlanetEvents, anchorEpochDay: Double): Boolean {
            if (ev.rise.isNaN()) return false
            val anchorDate = floor(anchorEpochDay).toLong()
            val riseRaw = ev.rise - offsetHours + displayOffsetHours
            return anchorDate + floor(riseRaw / 24.0).toLong() > currentDisplayDate
        }

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
        val tzSuffix = if (useLocalTime) " ($stdTimeLabel)" else " (UT)"
        val titlePart1 = "Planet Elevations for "
        drawIntoCanvas {
            val totalTitleWidth = titlePaintYellow.measureText(titlePart1) + titlePaintWhite.measureText(dateStr) + titlePaintYellow.measureText(tzSuffix)
            var titleX = (w - totalTitleWidth) / 2f
            it.nativeCanvas.drawText(titlePart1, titleX, titleBaseline, titlePaintYellow)
            titleX += titlePaintYellow.measureText(titlePart1)
            it.nativeCanvas.drawText(dateStr, titleX, titleBaseline, titlePaintWhite)
            titleX += titlePaintWhite.measureText(dateStr)
            it.nativeCanvas.drawText(tzSuffix, titleX, titleBaseline, titlePaintYellow)
            it.nativeCanvas.drawText("Local Sidereal Time", w/2, lstSubtitleBaseline, subTitlePaint)
        }

        // --- AXIS & GRID ---
        val startTickUT = floor(startHour - offsetHours).toInt()
        val endTickUT = ceil(endHour - offsetHours).toInt()
        drawLine(blueAxis, Offset(0f, chartTop), Offset(w, chartTop), strokeWidth = 2f)
        val stdOffsetInt = round(stdOffsetHours).toInt()
        for (h_ut in startTickUT..endTickUT) {
            val hourDisplay = if (useLocalTime) ((h_ut + stdOffsetInt) % 24 + 24) % 24 else (h_ut % 24 + 24) % 24
            val t_std = h_ut.toDouble() + offsetHours
            val x = timeToX(t_std)
            if (x >= -2f && x <= w + 2f) {
                drawLine(blueAxis, Offset(x, chartTop), Offset(x, chartTop + tickLen), strokeWidth = 2f)
                val paintToUse = if (hourDisplay % 6 == 0) whiteAxisTextPaint else axisTextPaint
                drawIntoCanvas { it.nativeCanvas.drawText(hourDisplay.toString(), x, utAxisNumberBaseline, paintToUse) }
            }
        }

        drawLine(blueAxis, Offset(0f, h - footerHeight), Offset(w, h - footerHeight), strokeWidth = 2f)
        // Place tick marks at integer LST hours by inverting LST→local-time
        val currentLST = calculateLSTHours(now.epochSecond / 86400.0 + 2440587.5, lon)
        val lstAtStart = currentLST + ((startHour - currentH) * 1.0027379)
        val firstLSTHour = ceil(lstAtStart).toInt()
        for (i in 0..25) {
            val lstHour = firstLSTHour + i
            val lstDisplay = ((lstHour % 24) + 24) % 24
            val localTime = currentH + (lstHour - currentLST) / 1.0027379
            val x = ((localTime - startHour) * pixelsPerHour).toFloat()
            if (x >= -1f && x <= w + 1f) {
                drawLine(blueAxis, Offset(x, h - footerHeight), Offset(x, h - footerHeight - tickLen), strokeWidth = 2f)
                val paintToUse = if (lstDisplay % 6 == 0) whiteAxisTextPaint else axisTextPaint
                drawIntoCanvas { it.nativeCanvas.drawText(lstDisplay.toString(), x, lstAxisNumberBaseline, paintToUse) }
            }
        }

        val xSS = timeToX(sunsetToday)
        if (xSS >= 0 && xSS <= w) {
            drawLine(standardGray, Offset(xSS, chartTop), Offset(xSS, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunset", xSS, h - footerHeight - labelGap - labelPaint.descent(), labelPaint) }
        }
        val xSR = timeToX(sunriseTomorrow)
        if (xSR >= 0 && xSR <= w) {
            drawLine(standardGray, Offset(xSR, chartTop), Offset(xSR, h - footerHeight), strokeWidth = 3f)
            drawIntoCanvas { it.nativeCanvas.drawText("Sunrise", xSR, h - footerHeight - labelGap - labelPaint.descent(), labelPaint) }
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
            return calculateAltitude(haHours, lat, decDeg)
        }

        val tickIncrements = listOf(0, 20, 40, 60, 80)
        data class LabelData(val text: String, val x: Float, val y: Float, val color: Int)
        val labelsToDraw = mutableListOf<LabelData>()

        fun drawObjectLineAndTicks(yPos: Float, name: String, ev: PlanetEvents, dec: Double, labelColorInt: Int) {
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
                        labelsToDraw.add(LabelData(name, (x1 + x2) / 2, yPos - tickHalf - labelGap - objectLabelPaint.descent(), labelColorInt))

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
                                        drawLine(tColor, Offset(xTick, yPos - tickHalf), Offset(xTick, yPos + tickHalf), strokeWidth = 2f)
                                        drawIntoCanvas { it.nativeCanvas.drawText(alt.toString(), xTick, yPos + tickNumberOffset, tickTextPaint.apply { color = tickColor }) }
                                    }
                                }
                            } else {
                                // --- INTERIOR TICKS (20, 40, 60, 80) ---
                                val ha = getHA(alt.toDouble(), dec)
                                if (!ha.isNaN()) {
                                    var tTransit = ev.transit + shift
                                    // Adjust transit to fall between r and sFinal (handles midnight wrap)
                                    while (tTransit < r) tTransit += 24.0
                                    while (tTransit > sFinal) tTransit -= 24.0
                                    val times = listOf(tTransit - ha, tTransit + ha)
                                    times.forEach { tTick ->
                                        if (tTick >= overlapStart && tTick <= overlapEnd) {
                                            val xTick = timeToX(tTick)
                                            val isTickNight = (xTick >= xNightStart && xTick <= xNightEnd)
                                            val tickColor = if (isTickNight) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                                            val tColor = Color(tickColor)
                                            drawLine(tColor, Offset(xTick, yPos - tickHalf), Offset(xTick, yPos + tickHalf), strokeWidth = 2f)
                                            drawIntoCanvas { it.nativeCanvas.drawText(alt.toString(), xTick, yPos + tickNumberOffset, tickTextPaint.apply { color = tickColor }) }
                                        }
                                    }
                                }
                            }
                        }
                        // Max Alt Tick (Transit)
                        val maxAlt = 90.0 - abs(lat - dec)
                        var tTransit = ev.transit + shift
                        // Adjust transit to fall between r and sFinal (handles midnight wrap)
                        while (tTransit < r) tTransit += 24.0
                        while (tTransit > sFinal) tTransit -= 24.0
                        if (tTransit >= overlapStart && tTransit <= overlapEnd) {
                            val xTick = timeToX(tTransit)
                            val isTickNight = (xTick >= xNightStart && xTick <= xNightEnd)
                            val tickColor = if (isTickNight) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                            val tColor = Color(tickColor)
                            drawLine(tColor, Offset(xTick, yPos), Offset(xTick, yPos + tickHalf + 2f), strokeWidth = 2f)
                            drawIntoCanvas { it.nativeCanvas.drawText(round(maxAlt).toInt().toString(), xTick, yPos + tickNumberOffset, tickTextPaint.apply { color = tickColor }) }
                        }
                    }
                }
            }
        }

        // --- Bounding-box-aware vertical spacing ---
        // aboveCenter: from body Y up to label box top (label baseline + ascent - boxPad)
        val aboveCenter = tickHalf + labelGap + objectLabelPaint.descent() - objectLabelPaint.ascent() + boxPad
        // belowCenter: from body Y down to elevation readout bottom
        val belowCenter = elevReadoutOffset + currentElevationPaint.descent()
        // Reserve space at chart bottom for Sunset/Sunrise labels
        val sunsetLabelHeight = labelGap + labelPaint.descent() - labelPaint.ascent()
        val numBodies = planetList.size + 2  // Sun + Moon + planets
        val firstBodyY = chartTop + aboveCenter
        val lastBodyY = chartTop + chartH - sunsetLabelHeight - belowCenter
        val bodySpacing = if (numBodies > 1) (lastBodyY - firstBodyY) / (numBodies - 1).toFloat() else 0f

        val isNightNow = (xNow >= xSS && xNow <= xSR)
        currentElevationPaint.color = if (isNightNow) currentLineGreen.toArgb() else currentLineGray.toArgb()

        // --- DRAW SUN LINE (Row 1) ---
        // Sun asterisk: mirror Compass auto-advance to determine effective anchor
        val sunEffectiveAnchor = if (!sunsetToday.isNaN()) {
            val baseMidnightUT = floor(epochDayInt) - offsetHours / 24.0
            if (baseMidnightUT + sunsetToday / 24.0 < currentUtEpochDay) epochDayInt + 1.0 else epochDayInt
        } else epochDayInt
        val sunAsterisk = if (sunEffectiveAnchor > epochDayInt && !sunriseTomorrow.isNaN() && !setTomorrow.isNaN()) {
            val (st, _) = calculateSunTransit(epochDayInt + 1.0, lon, offsetHours)
            isRiseTomorrow(PlanetEvents(sunriseTomorrow, st, setTomorrow), sunEffectiveAnchor)
        } else if (!riseToday.isNaN() && !sunsetToday.isNaN()) {
            val (st, _) = calculateSunTransit(epochDayInt, lon, offsetHours)
            isRiseTomorrow(PlanetEvents(riseToday, st, sunsetToday), sunEffectiveAnchor)
        } else false
        if (sunAsterisk) anyAsterisk = true
        val sunLabel = if (sunAsterisk) "Sun*" else "Sun"

        val sunY = firstBodyY
        if (!riseToday.isNaN() && !sunsetToday.isNaN()) {
            val (transitToday, sunDecToday) = calculateSunTransit(epochDayInt, lon, offsetHours)
            drawObjectLineAndTicks(sunY, sunLabel, PlanetEvents(riseToday, transitToday, sunsetToday), sunDecToday, android.graphics.Color.RED)
        }
        if (!sunriseTomorrow.isNaN() && !setTomorrow.isNaN()) {
            val (transitTomorrow, sunDecTomorrow) = calculateSunTransit(epochDayInt + 1.0, lon, offsetHours)
            drawObjectLineAndTicks(sunY, sunLabel, PlanetEvents(sunriseTomorrow, transitTomorrow, setTomorrow), sunDecTomorrow, android.graphics.Color.RED)
        }
        // Sun Current Elevation
        val sunEvList = listOfNotNull(
            if (!riseToday.isNaN() && !sunsetToday.isNaN()) PlanetEvents(riseToday, 0.0, sunsetToday) else null,
            if (!sunriseTomorrow.isNaN() && !setTomorrow.isNaN()) PlanetEvents(sunriseTomorrow, 0.0, setTomorrow) else null
        )
        var sunLineAtXNow = false
        for (sEv in sunEvList) {
            for (shift in listOf(-24.0, 0.0, 24.0)) {
                val r = sEv.rise + shift
                var sFinal = sEv.set + shift
                if (sFinal < r) sFinal += 24.0
                val overlapStart = max(startHour, r)
                val overlapEnd = min(endHour, sFinal)
                if (overlapEnd > overlapStart) {
                    val x1 = timeToX(overlapStart)
                    val x2 = timeToX(overlapEnd)
                    if (xNow >= x1 && xNow <= x2) { sunLineAtXNow = true; break }
                }
            }
            if (sunLineAtXNow) break
        }
        if (sunLineAtXNow) {
            val currentJD = now.epochSecond.toDouble() / 86400.0 + 2440587.5
            val sunState = AstroEngine.getBodyState("Sun", currentJD)
            val (sAppRa, sAppDec) = j2000ToApparent(sunState.ra, sunState.dec, currentJD)
            val sLst = calculateLSTHours(currentJD, lon)
            val sunAlt = applyRefraction(calculateAltitude(sLst - sAppRa / 15.0, lat, sAppDec))
            if (sunAlt > 0) {
                drawIntoCanvas { it.nativeCanvas.drawText(round(sunAlt).toInt().toString(), xNow + 5f, sunY + elevReadoutOffset, currentElevationPaint) }
            }
        }

        // --- DRAW MOON (Row 2) ---
        // Use Standard Calculator (matches TransitsScreen)
        var moonEv = calculateMoonEvents(epochDayInt, lat, lon, offsetHours, pairedRiseSet = true)
        var moonEpochBase = epochDayInt

        // If all Moon events are in the past, advance to the next day
        if (!moonEv.rise.isNaN() && !moonEv.set.isNaN()) {
            val moonDayStartUT = floor(epochDayInt) - offsetHours / 24.0
            val moonSetAbsLocal = if (moonEv.set >= moonEv.rise) moonEv.set else moonEv.set + 24.0
            if (moonDayStartUT + moonSetAbsLocal / 24.0 < currentUtEpochDay) {
                moonEv = calculateMoonEvents(epochDayInt + 1.0, lat, lon, offsetHours, pairedRiseSet = true)
                moonEpochBase = epochDayInt + 1.0
            }
        }

        // Dec: Use apparent topocentric dec at transit for tick marks
        var moonDec = run {
            val fallbackJD = moonEpochBase + 2440587.5 + 0.5
            val st = AstroEngine.getBodyState("Moon", fallbackJD)
            val (ar, ad) = j2000ToApparent(st.ra, st.dec, fallbackJD)
            val lst = calculateLSTHours(fallbackJD, lon)
            toTopocentric(ar, ad, st.distGeo, lat, lon, lst).dec
        }
        if (!moonEv.transit.isNaN()) {
            val transitJD = moonEpochBase + 2440587.5 + ((moonEv.transit - offsetHours) / 24.0)
            val mTrans = AstroEngine.getBodyState("Moon", transitJD)
            val (appRa, appDec) = j2000ToApparent(mTrans.ra, mTrans.dec, transitJD)
            val lstAtTransit = calculateLSTHours(transitJD, lon)
            moonDec = toTopocentric(appRa, appDec, mTrans.distGeo, lat, lon, lstAtTransit).dec
        }

        val moonY = firstBodyY + bodySpacing
        // Determine if Moon is up at current time (handles midnight wrap correctly)
        var moonIsUp = false
        for (shift in listOf(-24.0, 0.0, 24.0)) {
            val r = moonEv.rise + shift
            var sFinal = moonEv.set + shift
            if (sFinal < r) sFinal += 24.0
            // Check if this window overlaps with the visible chart range
            if (min(endHour, sFinal) > max(startHour, r)) {
                var c = currentH
                while (c < r) c += 24.0
                if (c <= sFinal) {
                    moonIsUp = true
                    break
                }
            }
        }
        val moonLabelColor = if (isNightNow && moonIsUp) labelGreen else labelRed
        val moonAsterisk = isRiseTomorrow(moonEv, moonEpochBase)
        if (moonAsterisk) anyAsterisk = true
        val moonLabel = if (moonAsterisk) "Moon*" else "Moon"

        drawObjectLineAndTicks(moonY, moonLabel, moonEv, moonDec, moonLabelColor.toArgb())

        // --- DRAW PLANETS (Rows 3+) ---
        planetList.forEachIndexed { i, p ->
            val yPos = firstBodyY + (i + 2) * bodySpacing
            // Use Standard Calculator (matches TransitsScreen)
            var ev = calculatePlanetEvents(epochDayInt, lat, lon, offsetHours, p)
            var planetEpochBase = epochDayInt

            // If planet has set, advance to next day's events (matches Compass auto-advance)
            if (!ev.rise.isNaN() && !ev.set.isNaN()) {
                val setAbs = if (ev.set >= ev.rise) ev.set else ev.set + 24.0
                val baseMidnightUT = floor(epochDayInt) - offsetHours / 24.0
                if (baseMidnightUT + setAbs / 24.0 < currentUtEpochDay) {
                    ev = calculatePlanetEvents(epochDayInt + 1.0, lat, lon, offsetHours, p)
                    planetEpochBase = epochDayInt + 1.0
                }
            }

            val jd = planetEpochBase + 2440587.5
            // Apparent dec at transit time for tick marks and max-alt display
            val transitDec = if (!ev.transit.isNaN()) {
                val transitJD = jd + ((ev.transit - offsetHours) / 24.0)
                val s = AstroEngine.getBodyState(p.name, transitJD)
                j2000ToApparent(s.ra, s.dec, transitJD).second
            } else {
                val s = AstroEngine.getBodyState(p.name, jd)
                j2000ToApparent(s.ra, s.dec, jd).second
            }
            var pIsUp = false
            var rNorm = ev.rise; var sNorm = ev.set
            if (sNorm < rNorm) sNorm += 24.0
            var cNorm2 = currentH
            while (cNorm2 < rNorm) cNorm2 += 24.0
            if (cNorm2 < sNorm) pIsUp = true
            val pLabelColor = if (isNightNow && pIsUp) labelGreen else labelRed
            val planetAsterisk = isRiseTomorrow(ev, planetEpochBase)
            if (planetAsterisk) anyAsterisk = true
            val planetLabel = if (planetAsterisk) "${p.name}*" else p.name

            drawObjectLineAndTicks(yPos, planetLabel, ev, transitDec, pLabelColor.toArgb())

            // Planet Current Elevation — check all shifts like Moon code
            var pLineAtXNow = false
            if (!ev.rise.isNaN() && !ev.set.isNaN()) {
                for (shift in listOf(-24.0, 0.0, 24.0)) {
                    val r = ev.rise + shift
                    var sFinal = ev.set + shift
                    if (sFinal < r) sFinal += 24.0
                    val overlapStart = max(startHour, r)
                    val overlapEnd = min(endHour, sFinal)
                    if (overlapEnd > overlapStart) {
                        val x1 = timeToX(overlapStart)
                        val x2 = timeToX(overlapEnd)
                        if (xNow >= x1 && xNow <= x2) { pLineAtXNow = true; break }
                    }
                }
            }
            if (pLineAtXNow) {
                val currentJD = now.epochSecond.toDouble() / 86400.0 + 2440587.5
                val pState = AstroEngine.getBodyState(p.name, currentJD)
                val (pAppRa, pAppDec) = j2000ToApparent(pState.ra, pState.dec, currentJD)
                val pLst = calculateLSTHours(currentJD, lon)
                val currentAlt = applyRefraction(calculateAltitude(pLst - pAppRa / 15.0, lat, pAppDec))
                if (currentAlt > 0) {
                    drawIntoCanvas { it.nativeCanvas.drawText(round(currentAlt).toInt().toString(), xNow + 5f, yPos + elevReadoutOffset, currentElevationPaint) }
                }
            }
        }

        // --- MOON CURRENT ELEVATION (Precise) ---
        // Check if xNow intersects the Moon's drawn line segment
        var moonLineAtXNow = false
        for (shift in listOf(-24.0, 0.0, 24.0)) {
            val r = moonEv.rise + shift
            var sFinal = moonEv.set + shift
            if (sFinal < r) sFinal += 24.0
            val overlapStart = max(startHour, r)
            val overlapEnd = min(endHour, sFinal)
            if (overlapEnd > overlapStart) {
                val x1 = timeToX(overlapStart)
                val x2 = timeToX(overlapEnd)
                if (xNow >= x1 && xNow <= x2) {
                    moonLineAtXNow = true
                    break
                }
            }
        }
        if (moonLineAtXNow) {
            // Use apparent topocentric coordinates for Moon elevation readout
            val currentJD = now.epochSecond.toDouble() / 86400.0 + 2440587.5
            val moonSt = AstroEngine.getBodyState("Moon", currentJD)
            val (mAppRa, mAppDec) = j2000ToApparent(moonSt.ra, moonSt.dec, currentJD)
            val mLst = calculateLSTHours(currentJD, lon)
            val mTopo = toTopocentric(mAppRa, mAppDec, moonSt.distGeo, lat, lon, mLst)
            val currAlt = applyRefraction(calculateAltitude(mLst - mTopo.ra / 15.0, lat, mTopo.dec))
            if (currAlt > 0) {
                drawIntoCanvas { it.nativeCanvas.drawText(round(currAlt).toInt().toString(), xNow + 5f, moonY + elevReadoutOffset, currentElevationPaint) }
            }
        }

        // Current Time Line
        val nowColor = if (isNightNow) currentLineGreen else currentLineGray
        nowPaint.color = nowColor.toArgb()
        drawLine(nowColor, Offset(xNow, chartTop), Offset(xNow, h - footerHeight), strokeWidth = 3f)
        val displayZoneId: ZoneId = if (useLocalTime) ZoneOffset.ofTotalSeconds((stdOffsetHours * 3600).roundToInt()) else ZoneOffset.UTC
        val displayInstant = now.atZone(displayZoneId).toLocalTime()
        val displayStr = "%02d:%02d".format(displayInstant.hour, displayInstant.minute)
        drawIntoCanvas { it.nativeCanvas.drawText(displayStr, xNow, currentUtBaseline, nowPaint) }
        val lstNow = calculateLST(now, lon)
        drawIntoCanvas { it.nativeCanvas.drawText(lstNow, xNow, currentLstBaseline, nowPaint) }

        // Labels
        labelsToDraw.forEach { item ->
            drawIntoCanvas { canvas ->
                val paint = objectLabelPaint.apply { color = item.color }
                val textBounds = Rect()
                paint.getTextBounds(item.text, 0, item.text.length, textBounds)
                val textW = textBounds.width().toFloat()
                val boxTop = item.y + textBounds.top - boxPad
                val boxBottom = item.y + textBounds.bottom + boxPad
                val boxLeft = item.x - textW/2 - boxPad
                val boxRight = item.x + textW/2 + boxPad
                canvas.nativeCanvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, blackFillPaint)
                canvas.nativeCanvas.drawText(item.text, item.x, item.y, paint)
            }
        }

        // "* Tomorrow" footnote if any object name has an asterisk
        if (anyAsterisk) {
            drawIntoCanvas {
                val tomorrowPaint = Paint().apply {
                    color = textLabelColor.toArgb()
                    textSize = 36f
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT
                }
                it.nativeCanvas.drawText("* Tomorrow", 20f, lstSubtitleBaseline, tomorrowPaint)
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
    } // Column
}