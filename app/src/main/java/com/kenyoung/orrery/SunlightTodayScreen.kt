package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun SunlightTodayScreen(obs: ObserverState) {
    val epochDay = obs.epochDay; val lat = obs.lat; val lon = obs.lon; val now = obs.now
    val stdOffsetHours = obs.stdOffsetHours; val stdTimeLabel = obs.stdTimeLabel
    val useLocalTime = obs.useStandardTime

    val offset = lon / 15.0
    val timeLabel = if (useLocalTime) stdTimeLabel else "UT"
    val displayOffsetHours = if (useLocalTime) stdOffsetHours else 0.0

    // Sun transit and rise/set for the current day
    val (transitTime, transitDec) = calculateSunTransit(floor(epochDay), lon, offset)
    val (riseTime, setTime) = calculateSunTimes(floor(epochDay), lat, lon, offset)
    val transitAlt = 90.0 - abs(lat - transitDec)

    // Current sun position
    val currentJd = now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val currentUtEpochDay = currentJd - UNIX_EPOCH_JD
    val sunState = AstroEngine.getBodyState("Sun", currentJd)
    val (sunAppRa, sunAppDec) = j2000ToApparent(sunState.ra, sunState.dec, currentJd)
    val lst = calculateLSTHours(currentJd, lon)
    val (currentAzRaw, currentAltRaw) = calculateAzAlt(lst, lat, sunAppRa / 15.0, sunAppDec)
    val currentAlt = applyRefraction(currentAltRaw)
    val currentLocalSolar = normalizeTime((currentUtEpochDay - floor(currentUtEpochDay)) * 24.0 + offset)

    // Skip the moon-altitude calc when not in darkness — its only consumer
    // is the "Moon is up" indicator that draws only during darkness.
    val isDarkness = currentAltRaw < ASTRONOMICAL_TWILIGHT
    val moonIsUp = isDarkness && run {
        val moonState = AstroEngine.getBodyState("Moon", currentJd)
        val (moonAppRa, moonAppDec) = j2000ToApparent(moonState.ra, moonState.dec, currentJd)
        val topoMoon = toTopocentric(moonAppRa, moonAppDec, moonState.distGeo, lat, lon, lst)
        val (_, moonAltRaw) = calculateAzAlt(lst, lat, topoMoon.ra / 15.0, topoMoon.dec)
        applyRefraction(moonAltRaw) > 0.0
    }

    // Twilight data for the table (anchored to observing night).
    // Use dawn Golden Hour end (sun at 6°) as the transition point, not sunrise,
    // so the table shows this morning's dawn times during the Golden Hour period.
    val goldenRiseTime = calculateSunTimes(floor(epochDay), lat, lon, offset, GOLDEN_HOUR_ALT).rise
    val transitionTime = if (!goldenRiseTime.isNaN()) goldenRiseTime else riseTime
    val eventEpochDay = if (!transitionTime.isNaN() && currentLocalSolar < transitionTime) floor(epochDay) - 1.0 else floor(epochDay)
    val twilightData = computeTwilightTimes(eventEpochDay, lat, lon, offset)

    // Date and time for title — round to nearest minute to avoid showing previous minute
    val displayZoneId = if (useLocalTime) ZoneOffset.ofTotalSeconds((stdOffsetHours * 3600).roundToInt()) else ZoneOffset.UTC
    val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(displayZoneId)
    val dateStr = dateFormatter.format(now)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(displayZoneId)
    val currentTimeStr = timeFormatter.format(now.plusSeconds(30))

    // Rise/set azimuths
    val riseAz = if (!riseTime.isNaN()) calculateAzAtRiseSet(lat, transitDec, true, HORIZON_REFRACTED) else Double.NaN
    val setAz = if (!setTime.isNaN()) calculateAzAtRiseSet(lat, transitDec, false, HORIZON_REFRACTED) else Double.NaN

    // "Tonight's" very-dark period — same definition as the Meteor Showers page
    // (Sun below nautical twilight AND Moon below its illumination-dependent threshold).
    // Anchor the noon-to-noon UT scan to the SAME night the dial shows: the night that
    // follows eventEpochDay, whose local (solar) midnight is nightMidnightUt. Keying off
    // the UT "now" instead lands on the adjacent night for observers far from UTC (e.g. a
    // CDT evening falls on the next UT day), so the dial's darkness and this scan would
    // disagree and the clipped wedge below would vanish.
    val nightMidnightUt = (eventEpochDay + 1.0) - offset / 24.0
    val darkSearchBase = floor(nightMidnightUt - 0.5)
    val darkResult = calculateDarkHoursDetails(darkSearchBase, lat, lon)
    val hasDark = !darkResult.startEpochDay.isNaN() && !darkResult.endEpochDay.isNaN()
    // Map the dark period's start/end (UT epoch days) onto the dial's display clock,
    // the same UT-hour + displayOffsetHours mapping the dial uses for the Sun marker.
    val darkStartDisplayHour =
        if (hasDark) normalizeTime((darkResult.startEpochDay - floor(darkResult.startEpochDay)) * 24.0 + displayOffsetHours) else 0.0
    val darkEndDisplayHour =
        if (hasDark) normalizeTime((darkResult.endEpochDay - floor(darkResult.endEpochDay)) * 24.0 + displayOffsetHours) else 0.0
    val darkRangeStr = if (hasDark)
        "%s→%s".format(formatTimeMM(darkStartDisplayHour, false), formatTimeMM(darkEndDisplayHour, false)) else null

    // Longest and shortest day of the current calendar year at this location.
    // The extremes always fall within a few days of a solstice, so sample only a
    // small window around each (≈June 21 and ≈Dec 21) rather than the whole year.
    // The window covers the solstice's date drift across years and both hemispheres
    // (June solstice is longest in the north, shortest in the south, and vice versa).
    // Day length uses the same rise/set solver as today's value, so when today falls
    // in-window the solstice difference reads exactly 00:00:00. Polar days/nights are
    // disambiguated by transit altitude.
    val hasRiseSetToday = !riseTime.isNaN() && !setTime.isNaN()
    val todayDayLenHours = if (hasRiseSetToday) setTime - riseTime else if (transitAlt > 0.0) 24.0 else 0.0
    val scanYear = LocalDate.ofEpochDay(floor(epochDay).toLong()).year
    fun dayLengthHours(d: Double): Double {
        val (dRise, dSet) = calculateSunTimes(d, lat, lon, offset)
        return if (!dRise.isNaN() && !dSet.isNaN()) {
            var l = dSet - dRise
            if (l < 0.0) l += 24.0
            l
        } else {
            val (_, dDec) = calculateSunTransit(d, lon, offset)
            if (90.0 - abs(lat - dDec) > HORIZON_REFRACTED) 24.0 else 0.0
        }
    }
    val solsticeScanRadius = 3
    var maxDayLenHours = 0.0
    var minDayLenHours = 24.0
    for (solsticeMonth in intArrayOf(6, 12)) {
        val solsticeDay = LocalDate.of(scanYear, solsticeMonth, 21).toEpochDay()
        for (k in -solsticeScanRadius..solsticeScanRadius) {
            val len = dayLengthHours((solsticeDay + k).toDouble())
            if (len > maxDayLenHours) maxDayLenHours = len
            if (len < minDayLenHours) minDayLenHours = len
        }
    }
    fun formatHMS(hours: Double): String {
        val totalSec = round(hours * 3600.0).toInt()
        return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }
    val belowMaxStr = "%s < max".format(formatHMS(abs(todayDayLenHours - maxDayLenHours)))
    val aboveMinStr = "%s > min".format(formatHMS(abs(todayDayLenHours - minDayLenHours)))

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            withDensityScaling { w, h ->

            // Paints
            // Drawing constants
            val tickLen = 30f
            val sunRadius = 15f
            val sunRayInner = 21f
            val sunRayOuter = 33f
            val curveStrokeWidth = 6f
            val chartFraction = 0.375f                          // chart height as a proportion of canvas

            // Vertical-fit scale. On short screens, compress the fixed text block (title, axis
            // and event labels, twilight table) and the chart together so the dial always has
            // room and nothing is clipped at the bottom. vScale == 1 on normal/large screens.
            val sunClearEst = (sunRadius + 6f) * 1.33f + sunRayOuter + 8f
            val dialBudgetMin = 2f * (sunClearEst + 6f) + 240f  // dial reserves + a minimum dial diameter
            val fixedBlockEst = 800f                            // title + label bands + table, at full size
            val vScale = ((h - dialBudgetMin) / (fixedBlockEst + chartFraction * h)).coerceIn(0.4f, 1f)

            // Paints
            val titlePaintLabel = Paint().apply { color = LabelColor.toArgb(); textSize = 63f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val titlePaintWhite = Paint().apply { color = android.graphics.Color.WHITE; textSize = 63f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val axisLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
            val hourLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            val eventLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 39f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val eventTimePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val eventAzPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val bandLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val sunLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableHeaderPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableLabelPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 44f; textAlign = Paint.Align.LEFT; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableDataPaint = Paint().apply { textSize = 44f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; isAntiAlias = true }

            // Apply the vertical-fit scale to every upper-block text size (the dial draws its
            // own fixed-size labels and Sun symbol, which must match the chart's Sun symbol).
            listOf(titlePaintLabel, titlePaintWhite, axisLabelPaint, hourLabelPaint, eventLabelPaint,
                   eventTimePaint, eventAzPaint, bandLabelPaint, sunLabelPaint,
                   tableHeaderPaint, tableLabelPaint, tableDataPaint).forEach { it.textSize *= vScale }

            // Layout — four non-overlapping vertical bands, each sized from font metrics.
            // The chart gets a fixed proportion of the canvas so its size never varies
            // with the altitude range. Remaining space is allocated to labels and table.
            val titleY = 60f * vScale
            val chartLeft = 80f
            val chartRight = w - 20f
            val chartTop = titleY + 40f * vScale
            val rowHeight = 57f * vScale

            // Chart height is a proportion of the canvas, compressed with the rest on short screens.
            val chartH = (h * chartFraction * vScale).coerceAtLeast(100f)
            val chartBottom = chartTop + chartH
            val chartW = chartRight - chartLeft

            // Band 2: hour labels, positioned immediately below chart
            val bandGap = 20f * vScale
            val hourBandH = hourLabelPaint.textSize + bandGap

            // Band 3: Rise/Transit/Set event labels
            val eventLineSpacing = 8f * vScale
            val tableGap = 60f * vScale
            val eventBandH = eventLabelPaint.textSize + eventLineSpacing +
                             eventTimePaint.textSize + eventLineSpacing +
                             eventAzPaint.textSize + tableGap

            // Y-axis range: encompasses the full sun path and the horizon.
            // Bottom is whichever is lower: the horizon (0°) or the sun's lowest altitude.
            // Top always includes the horizon and the sun's peak.
            val sunMinAlt = calculateAltitude(12.0, lat, transitDec) // anti-transit altitude
            val yMax = max(transitAlt, 0.0) + 5.0
            val yMin = min(sunMinAlt, 0.0) - 5.0
            val yRange = yMax - yMin

            fun altToY(alt: Double): Float = (chartTop + ((yMax - alt) / yRange * chartH)).toFloat()
            val horizonY = altToY(0.0)

            // X-axis: 24 hours centered on transit
            val startHour = transitTime - 12.0
            fun timeToX(t: Double): Float {
                var dt = t - startHour
                while (dt < 0) dt += 24.0
                while (dt > 24.0) dt -= 24.0
                return (chartLeft + dt / 24.0 * chartW).toFloat()
            }

            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas

                // --- Title (font size auto-scaled to fit screen width) ---
                val titleText1 = "Sunlight Today  "
                val titleText2 = dateStr
                val titleText3 = "  $currentTimeStr"
                val titleText4 = "  ($timeLabel)"
                val titleMargin = 20f
                val availableWidth = w - 2 * titleMargin
                val fullWidth = titlePaintLabel.measureText(titleText1) +
                    titlePaintWhite.measureText(titleText2) +
                    titlePaintWhite.measureText(titleText3) +
                    titlePaintLabel.measureText(titleText4)
                if (fullWidth > availableWidth) {
                    val scale = availableWidth / fullWidth
                    titlePaintLabel.textSize *= scale
                    titlePaintWhite.textSize *= scale
                }
                var tx = titleMargin
                nc.drawText(titleText1, tx, titleY, titlePaintLabel)
                tx += titlePaintLabel.measureText(titleText1)
                nc.drawText(titleText2, tx, titleY, titlePaintWhite)
                tx += titlePaintWhite.measureText(titleText2)
                nc.drawText(titleText3, tx, titleY, titlePaintWhite)
                tx += titlePaintWhite.measureText(titleText3)
                nc.drawText(titleText4, tx, titleY, titlePaintLabel)

                // --- Chart content (y-axis scaled to fit full nadir-to-peak range) ---
                val civilY = altToY(CIVIL_TWILIGHT)
                val nauticalY = altToY(NAUTICAL_TWILIGHT)
                val astroY = altToY(ASTRONOMICAL_TWILIGHT)

                // Compute elevation curve points
                val numPoints = 240
                val curvePoints = mutableListOf<Pair<Float, Float>>()
                for (i in 0..numPoints) {
                    val t = startHour + i * (24.0 / numPoints)
                    val ha = t - transitTime
                    val alt = calculateAltitude(ha, lat, transitDec)
                    curvePoints.add(Pair(timeToX(t), altToY(alt)))
                }

                // Twilight bands below horizon
                drawRect(Color(0xFF0E2499), Offset(chartLeft, horizonY), Size(chartW, civilY - horizonY))
                drawRect(Color(0xFF09165A), Offset(chartLeft, civilY), Size(chartW, nauticalY - civilY))
                drawRect(Color(0xFF050C1A), Offset(chartLeft, nauticalY), Size(chartW, astroY - nauticalY))

                // Band labels midway between Set and Transit vertical lines
                if (!setTime.isNaN()) {
                    val midX = (timeToX(setTime) + timeToX(transitTime)) / 2f
                    nc.drawText("Civil", midX, (horizonY + civilY) / 2f + bandLabelPaint.textSize / 3f, bandLabelPaint)
                    nc.drawText("Nautical", midX, (civilY + nauticalY) / 2f + bandLabelPaint.textSize / 3f, bandLabelPaint)
                    nc.drawText("Astro.", midX, (nauticalY + astroY) / 2f + bandLabelPaint.textSize / 3f, bandLabelPaint)
                    val darknessBottomY = altToY(yMin)
                    nc.drawText("Dark", midX, (astroY + darknessBottomY) / 2f + bandLabelPaint.textSize / 3f, bandLabelPaint)
                }

                // Fill above-horizon area
                val skyPath = Path()
                var inSky = false
                for ((px, py) in curvePoints) {
                    if (py <= horizonY) {
                        if (!inSky) {
                            skyPath.moveTo(px, horizonY)
                            skyPath.lineTo(px, py)
                            inSky = true
                        } else {
                            skyPath.lineTo(px, py)
                        }
                    } else if (inSky) {
                        skyPath.lineTo(px, horizonY)
                        inSky = false
                    }
                }
                if (inSky) {
                    skyPath.lineTo(curvePoints.last().first, horizonY)
                }
                skyPath.close()
                drawPath(skyPath, Color(0xFF0000FF))

                // Draw elevation curve
                val curvePath = Path()
                curvePath.moveTo(curvePoints[0].first, curvePoints[0].second)
                for (i in 1..numPoints) {
                    curvePath.lineTo(curvePoints[i].first, curvePoints[i].second)
                }
                drawPath(curvePath, Color(0xFFFFAA00), style = Stroke(width = curveStrokeWidth))

                // Horizon line
                drawLine(Color.White, Offset(chartLeft, horizonY), Offset(chartRight, horizonY), strokeWidth = 1.5f)
                nc.drawText("0°", chartLeft - 10f, horizonY + 9f, axisLabelPaint)
                drawLine(Color.White, Offset(chartLeft, horizonY), Offset(chartLeft + tickLen, horizonY), strokeWidth = 2f)

                // Y-axis altitude labels
                val altSteps = generateSequence(0) { it + 10 }.takeWhile { it <= yMax.toInt() }.drop(1).toList() +
                    listOf(-6, -12, -18).filter { it >= yMin.toInt() }
                for (alt in altSteps) {
                    val y = altToY(alt.toDouble())
                    if (y > chartTop && y < chartBottom) {
                        drawLine(Color(0xFF333333), Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 0.5f)
                        nc.drawText("${alt}°", chartLeft - 10f, y + 9f, axisLabelPaint)
                        drawLine(Color.White, Offset(chartLeft, y), Offset(chartLeft + tickLen, y), strokeWidth = 2f)
                    }
                }

                // Vertical grid lines and hour tick marks
                for (displayHour in 0..23 step 3) {
                    val t = displayHour.toDouble() - displayOffsetHours + offset
                    val x = timeToX(t)
                    if (x > chartLeft + 15 && x < chartRight - 15) {
                        drawLine(Color(0xFF333333), Offset(x, chartTop), Offset(x, chartBottom), strokeWidth = 0.5f)
                        drawLine(Color.White, Offset(x, chartBottom - tickLen), Offset(x, chartBottom), strokeWidth = 2f)
                    }
                }

                // Sun icon (within chart clip)
                val xNow = timeToX(currentLocalSolar)
                val yNow = altToY(currentAlt)
                if (xNow >= chartLeft && xNow <= chartRight && yNow >= chartTop && yNow <= chartBottom) {
                    val sunColor = Color.Yellow
                    drawCircle(sunColor, radius = sunRadius, center = Offset(xNow, yNow))
                    for (angle in 0 until 360 step 45) {
                        val rad = Math.toRadians(angle.toDouble())
                        drawLine(sunColor,
                            Offset(xNow + sunRayInner * cos(rad).toFloat(), yNow + sunRayInner * sin(rad).toFloat()),
                            Offset(xNow + sunRayOuter * cos(rad).toFloat(), yNow + sunRayOuter * sin(rad).toFloat()),
                            strokeWidth = 2.5f)
                    }
                    // Elevation label to the left, azimuth label to the right
                    val sunLabelOffset = (sunRayOuter + 8f) * 1.5f
                    sunLabelPaint.textAlign = Paint.Align.RIGHT
                    nc.drawText("%.0f°".format(round(currentAlt)), xNow - sunLabelOffset, yNow + sunLabelPaint.textSize / 3f, sunLabelPaint)
                    sunLabelPaint.textAlign = Paint.Align.LEFT
                    nc.drawText("%.0f°".format(round(currentAzRaw)), xNow + sunLabelOffset, yNow + sunLabelPaint.textSize / 3f, sunLabelPaint)
                }

                // "Currently" label + sunlight state in upper-left of chart
                val sunlightState = when {
                    currentAltRaw >= GOLDEN_HOUR_ALT -> "Daylight"
                    currentAltRaw >= HORIZON_REFRACTED -> "Golden Hour"
                    currentAltRaw >= CIVIL_TWILIGHT -> "Civil Twilight"
                    currentAltRaw >= NAUTICAL_TWILIGHT -> "Nautical Twilight"
                    currentAltRaw >= ASTRONOMICAL_TWILIGHT -> "Astro. Twilight"
                    else -> "Darkness"
                }
                val currentlyLabelPaint = Paint().apply {
                    isAntiAlias = true
                    color = LabelColor.toArgb()
                    textSize = 50f * vScale
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT_BOLD
                }
                val currentlyStatePaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = 50f * vScale
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT
                }
                val currentlyX = chartLeft + tickLen + 10f
                val currentlyY = chartTop + currentlyLabelPaint.textSize + 4f
                val stateLineSpacing = 54f * vScale
                nc.drawText("Currently", currentlyX, currentlyY, currentlyLabelPaint)
                nc.drawText(sunlightState, currentlyX, currentlyY + stateLineSpacing, currentlyStatePaint)
                if (moonIsUp) {
                    nc.drawText("Moon is up", currentlyX, currentlyY + 2 * stateLineSpacing, currentlyStatePaint)
                }

                // --- Hour labels (Band 2: below chart) ---
                for (displayHour in 0..23 step 3) {
                    val t = displayHour.toDouble() - displayOffsetHours + offset
                    val x = timeToX(t)
                    if (x > chartLeft + 15 && x < chartRight - 15) {
                        nc.drawText("%02d".format(displayHour), x, chartBottom + hourLabelPaint.textSize, hourLabelPaint)
                    }
                }

                // --- Rise/Transit/Set labels (Band 3: starts after hour band) ---
                val hourBandBottom = chartBottom + hourBandH
                val labelBaseY = hourBandBottom + eventLabelPaint.textSize
                val timeLineY = labelBaseY + eventTimePaint.textSize + eventLineSpacing
                val azLineY = timeLineY + eventAzPaint.textSize + eventLineSpacing

                if (!riseTime.isNaN()) {
                    val xRise = timeToX(riseTime)
                    drawLine(Color(0xFFAAAAAA), Offset(xRise, horizonY), Offset(xRise, chartBottom), strokeWidth = 1f)
                    val riseDisplay = normalizeTime(riseTime - offset + displayOffsetHours)
                    nc.drawText("Sunrise", xRise, labelBaseY, eventLabelPaint)
                    nc.drawText(formatTimeMM(riseDisplay, false), xRise, timeLineY, eventTimePaint)
                    if (!riseAz.isNaN()) nc.drawText("Az %.0f°".format(round(riseAz)), xRise, azLineY, eventAzPaint)
                }

                val xTransit = timeToX(transitTime)
                drawLine(Color(0xFFAAAAAA), Offset(xTransit, horizonY), Offset(xTransit, chartBottom), strokeWidth = 1f)
                val transitDisplay = normalizeTime(transitTime - offset + displayOffsetHours)
                nc.drawText("Solar Noon", xTransit, labelBaseY, eventLabelPaint)
                nc.drawText(formatTimeMM(transitDisplay, false), xTransit, timeLineY, eventTimePaint)
                nc.drawText("El %.0f°".format(round(transitAlt)), xTransit, azLineY, eventAzPaint)

                if (!setTime.isNaN()) {
                    val xSet = timeToX(setTime)
                    drawLine(Color(0xFFAAAAAA), Offset(xSet, horizonY), Offset(xSet, chartBottom), strokeWidth = 1f)
                    val setDisplay = normalizeTime(setTime - offset + displayOffsetHours)
                    nc.drawText("Sunset", xSet, labelBaseY, eventLabelPaint)
                    nc.drawText(formatTimeMM(setDisplay, false), xSet, timeLineY, eventTimePaint)
                    if (!setAz.isNaN()) nc.drawText("Az %.0f°".format(round(setAz)), xSet, azLineY, eventAzPaint)
                }

                // --- Twilight times table ---
                val tableTopY = hourBandBottom + eventBandH
                val twDuskX = w * 0.62f
                val twDawnX = w * 0.84f
                val currentDisplayDate = floor(now.epochSecond.toDouble() / SECONDS_PER_DAY + displayOffsetHours / 24.0).toLong()
                var anyAsterisk = false

                // Find the next twilight event: closest future event to now.
                var nextIsDusk = true
                var nextIndex = -1
                var nextEventUt = Double.MAX_VALUE
                val duskMidnightUt = floor(twilightData.duskAnchor) - offset / 24.0
                val dawnMidnightUt = floor(twilightData.dawnAnchor) - offset / 24.0

                for (i in 0..4) {
                    if (!twilightData.dusk[i].isNaN()) {
                        val eventUt = duskMidnightUt + twilightData.dusk[i] / 24.0
                        if (eventUt > currentUtEpochDay && eventUt < nextEventUt) {
                            nextEventUt = eventUt; nextIsDusk = true; nextIndex = i
                        }
                    }
                    if (!twilightData.dawn[i].isNaN()) {
                        val eventUt = dawnMidnightUt + twilightData.dawn[i] / 24.0
                        if (eventUt > currentUtEpochDay && eventUt < nextEventUt) {
                            nextEventUt = eventUt; nextIsDusk = false; nextIndex = i
                        }
                    }
                }

                // Column headers (two lines)
                var currY = tableTopY
                nc.drawText("Dusk", twDuskX, currY, tableHeaderPaint)
                nc.drawText("Dawn", twDawnX, currY, tableHeaderPaint)
                currY += tableHeaderPaint.textSize + 4f
                nc.drawText("starts at", twDuskX, currY, tableHeaderPaint)
                nc.drawText("ends at", twDawnX, currY, tableHeaderPaint)
                currY += rowHeight

                for (i in 0..4) {
                    // Row label
                    tableLabelPaint.color = LabelColor.toArgb()
                    nc.drawText(TWILIGHT_LABELS[i], 20f, currY, tableLabelPaint)

                    // Dusk time
                    val duskH = twilightData.dusk[i]
                    if (duskH.isNaN()) {
                        tableDataPaint.color = android.graphics.Color.GRAY
                        nc.drawText("---", twDuskX, currY, tableDataPaint)
                    } else {
                        val duskRaw = duskH - offset + displayOffsetHours
                        val duskDisplay = normalizeTime(duskRaw)
                        val duskTomorrow = isEventTomorrow(twilightData.duskAnchor, duskRaw, currentDisplayDate)
                        val duskStr = formatTimeMM(duskDisplay, false) + if (duskTomorrow) "*" else ""
                        if (duskTomorrow) anyAsterisk = true
                        val isNext = nextIsDusk && nextIndex == i
                        tableDataPaint.color = if (isNext) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                        nc.drawText(duskStr, twDuskX, currY, tableDataPaint)
                    }

                    // Dawn time
                    val dawnH = twilightData.dawn[i]
                    if (dawnH.isNaN()) {
                        tableDataPaint.color = android.graphics.Color.GRAY
                        nc.drawText("---", twDawnX, currY, tableDataPaint)
                    } else {
                        val dawnRaw = dawnH - offset + displayOffsetHours
                        val dawnDisplay = normalizeTime(dawnRaw)
                        val dawnTomorrow = isEventTomorrow(twilightData.dawnAnchor, dawnRaw, currentDisplayDate)
                        val dawnStr = formatTimeMM(dawnDisplay, false) + if (dawnTomorrow) "*" else ""
                        if (dawnTomorrow) anyAsterisk = true
                        val isNext = !nextIsDusk && nextIndex == i
                        tableDataPaint.color = if (isNext) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                        nc.drawText(dawnStr, twDawnX, currY, tableDataPaint)
                    }

                    currY += rowHeight
                }

                // "* Tomorrow" footnote
                if (anyAsterisk) {
                    tableLabelPaint.color = LabelColor.toArgb()
                    nc.drawText("* Tomorrow", 20f, currY + 5f, tableLabelPaint)
                }

                // --- 24-hour day/night dial (below all other elements) ---
                // 0:00 at top, time runs clockwise (6h right, noon bottom, 18h left).
                // Yellow = Sun above horizon; the night side darkens through the twilight
                // phases (civil → nautical → astronomical → deepest night), mirroring the
                // elevation chart's bands. Times are in the page's display clock.
                // The ramp is brightened relative to the chart's near-black astro band so the
                // deepest-night fill stays visibly blue and the black very-dark wedge reads on it.
                val dialNightColor = Color(0xFF0C1240)      // deepest night (Sun below -18°)
                val dialAstroColor = Color(0xFF161E60)      // astronomical twilight (-12° to -18°)
                val dialNauticalColor = Color(0xFF24348C)   // nautical twilight (-6° to -12°)
                val dialCivilColor = Color(0xFF3450C8)      // civil twilight (horizon to -6°)
                val dialGoldenColor = Color(0xFFECD383)     // golden hour (Sun +6° to horizon)

                // Day / night lengths (independent of the display offset)
                val hasRiseSet = !riseTime.isNaN() && !setTime.isNaN()
                val polarDay = !hasRiseSet && transitAlt > 0.0
                val dayLenHours = if (hasRiseSet) setTime - riseTime else if (polarDay) 24.0 else 0.0
                val nightLenHours = 24.0 - dayLenHours
                fun formatHoursMinutes(hours: Double): String {
                    var hh = floor(hours).toInt()
                    var mm = round((hours - hh) * 60.0).toInt()
                    if (mm == 60) { hh += 1; mm = 0 }
                    return "%dh %02dm".format(hh, mm)
                }
                val dayValueStr = formatHoursMinutes(dayLenHours)
                val nightValueStr = formatHoursMinutes(nightLenHours)

                // Change in day length vs yesterday (shown under the Daylight value, left of the dial)
                val changeStr: String? = if (hasRiseSet) {
                    val (yesterdayRise, yesterdaySet) = calculateSunTimes(floor(epochDay) - 1.0, lat, lon, offset)
                    if (!yesterdayRise.isNaN() && !yesterdaySet.isNaN()) {
                        val changeMins = (dayLenHours - (yesterdaySet - yesterdayRise)) * 60.0
                        val sign = if (changeMins >= 0) "+" else "-"
                        val absChange = abs(changeMins)
                        val cM = floor(absChange).toInt()
                        val cS = round((absChange - cM) * 60.0).toInt()
                        "Change %s%dm %02ds".format(sign, cM, cS)
                    } else null
                } else null

                // Side-label paints (fixed sizes so their widths can be measured before
                // the circle is sized — the text columns are reserved out of the width).
                val sideLabelPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 40f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                val sideValuePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 46f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                val sideChangePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                val darkRangeStrShown = darkRangeStr ?: "none"
                val sideTextW = maxOf(
                    sideLabelPaint.measureText("Daylight"), sideLabelPaint.measureText("Night"),
                    sideValuePaint.measureText(dayValueStr), sideValuePaint.measureText(nightValueStr),
                    if (changeStr != null) sideChangePaint.measureText(changeStr) else 0f,
                    sideChangePaint.measureText(belowMaxStr), sideChangePaint.measureText(aboveMinStr),
                    sideChangePaint.measureText("Very dark"), sideChangePaint.measureText(darkRangeStrShown)
                ) + 12f

                // The Sun marker sits just outside the rim and travels the whole circle over a
                // day. Reserve its full clearance on every side (so it never clips off-canvas)
                // and, on the sides, an extra column for the day/night length text beyond it.
                val dialLabelPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 44f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
                val sunBeyondRim = (sunRadius + 6f) * 1.33f        // Sun body center this far outside the rim
                val sunClear = sunBeyondRim + sunRayOuter + 8f     // outermost ray tip clearance
                val sideGap = 16f
                val sideReserve = sunClear + sideGap + sideTextW
                val topReserve = sunClear + 6f
                val bottomReserve = sunClear + 6f
                val dialRegionTop = currY + 10f + (if (anyAsterisk) tableLabelPaint.textSize else 0f)

                val availW = w - 2f * sideReserve
                val availH = h - dialRegionTop - topReserve - bottomReserve
                val diameter = min(availW, availH)

                if (diameter > 60f) {
                    val r = diameter / 2f
                    val cx = w / 2f
                    val cy = dialRegionTop + topReserve + r
                    val dialTopLeft = Offset(cx - r, cy - r)
                    val dialSize = Size(diameter, diameter)

                    // Map a display-clock hour to a drawArc angle (0° = +x/3-o'clock, clockwise).
                    fun hourToArc(hr: Double) = (hr / 24.0 * 360.0 - 90.0).toFloat()

                    val riseDisplay = if (hasRiseSet) normalizeTime(riseTime - offset + displayOffsetHours) else 0.0
                    // Is a given display hour within the (yellow) daylight span?
                    fun isDaylightHour(hr: Double): Boolean {
                        if (!hasRiseSet) return polarDay
                        return ((hr - riseDisplay) % 24.0 + 24.0) % 24.0 <= dayLenHours
                    }

                    if (hasRiseSet) {
                        drawCircle(dialNightColor, radius = r, center = Offset(cx, cy))
                        drawArc(Color.Yellow, hourToArc(riseDisplay), (dayLenHours / 24.0 * 360.0).toFloat(),
                            useCenter = true, topLeft = dialTopLeft, size = dialSize)
                    } else {
                        drawCircle(if (polarDay) Color.Yellow else dialNightColor, radius = r, center = Offset(cx, cy))
                    }

                    // Golden-hour and twilight sub-bands on the dusk and dawn flanks, drawn over
                    // the day/night fill. Boundary times are the same Sun-altitude crossings shown
                    // in the table above, mapped to the display clock the same way. A wedge whose
                    // start or end time is undefined (a phase never reached in high-latitude
                    // summer) is skipped. The deepest-night base shows through between the two
                    // astronomical wedges.
                    fun dialDisplayHour(solarH: Double) = normalizeTime(solarH - offset + displayOffsetHours)
                    fun drawDialWedge(startSolar: Double, endSolar: Double, color: Color) {
                        if (startSolar.isNaN() || endSolar.isNaN()) return
                        val startHr = dialDisplayHour(startSolar)
                        var sweepHr = dialDisplayHour(endSolar) - startHr
                        while (sweepHr < 0.0) sweepHr += 24.0
                        while (sweepHr > 24.0) sweepHr -= 24.0
                        if (sweepHr <= 0.0) return
                        drawArc(color, hourToArc(startHr), (sweepHr / 24.0 * 360.0).toFloat(),
                            useCenter = true, topLeft = dialTopLeft, size = dialSize)
                    }
                    // Altitude-crossing index: 0=+6° (golden hour), 1=horizon (sunrise/sunset),
                    // 2=-6° (civil), 3=-12° (nautical), 4=-18° (astro). dusk = evening, dawn = morning.
                    val dusk = twilightData.dusk
                    val dawn = twilightData.dawn
                    // Golden hour (Sun +6° to horizon): above-horizon, so drawn over the daylight arc.
                    drawDialWedge(dusk[0], dusk[1], dialGoldenColor)         // evening golden hour
                    drawDialWedge(dawn[1], dawn[0], dialGoldenColor)         // morning golden hour
                    // Twilight bands below the horizon, darkening into night.
                    drawDialWedge(dusk[1], dusk[2], dialCivilColor)          // evening: civil
                    drawDialWedge(dusk[2], dusk[3], dialNauticalColor)
                    drawDialWedge(dusk[3], dusk[4], dialAstroColor)
                    drawDialWedge(dawn[4], dawn[3], dialAstroColor)          // morning: astro → ...
                    drawDialWedge(dawn[3], dawn[2], dialNauticalColor)
                    drawDialWedge(dawn[2], dawn[1], dialCivilColor)          // ... → civil


                    // Black wedge over the very-dark (meteor) period, clipped to the astronomical-
                    // dark interval (Sun below -18°, i.e. between dusk[4] and dawn[4]) so it can
                    // never paint over the daylight, golden-hour, or twilight wedges. The very-dark
                    // period (Sun below -12° plus a Moon condition) is shallower, so without this
                    // clip it would spill into the twilight bands. The two intervals are intersected
                    // in absolute UT (linear, no clock wrap) and only the overlap is drawn.
                    if (hasDark && !dusk[4].isNaN() && !dawn[4].isNaN()) {
                        val darknessBeginUt = duskMidnightUt + dusk[4] / 24.0
                        val darknessEndUt = dawnMidnightUt + dawn[4] / 24.0
                        val clipStartUt = max(darkResult.startEpochDay, darknessBeginUt)
                        val clipEndUt = min(darkResult.endEpochDay, darknessEndUt)
                        if (clipEndUt > clipStartUt) {
                            val blackStartHour = normalizeTime((clipStartUt - floor(clipStartUt)) * 24.0 + displayOffsetHours)
                            val blackSweepHours = (clipEndUt - clipStartUt) * 24.0
                            drawArc(Color.Black, hourToArc(blackStartHour), (blackSweepHours / 24.0 * 360.0).toFloat(),
                                useCenter = true, topLeft = dialTopLeft, size = dialSize)
                        }
                    }

                    // Thin line bisecting the dial vertically (Midnight at top through center to Noon).
                    // Each half is coloured to contrast with the fill under it (white over the dark
                    // night fill, near-black over the yellow daylight fill).
                    val axisDark = Color(0xFF202020)
                    drawLine(if (isDaylightHour(0.0)) axisDark else Color.White, Offset(cx, cy - r), Offset(cx, cy), strokeWidth = 1f)
                    drawLine(if (isDaylightHour(12.0)) axisDark else Color.White, Offset(cx, cy), Offset(cx, cy + r), strokeWidth = 1f)

                    // Midnight / Noon rim labels, just outside the rim
                    val clockPrefix = if (useLocalTime) "Timezone" else "UTC"
                    nc.drawText("$clockPrefix Midnight", cx, cy - r - 12f, dialLabelPaint)
                    nc.drawText("$clockPrefix Noon", cx, cy + r + dialLabelPaint.textSize + 4f, dialLabelPaint)

                    // Daylight length to the LEFT, night length to the RIGHT — both placed
                    // beyond the Sun's maximum reach (rim + sunClear), so it can never overlap them.
                    val labelRise = 8f
                    val valueDrop = sideValuePaint.textSize + 2f
                    sideLabelPaint.textAlign = Paint.Align.RIGHT
                    sideValuePaint.textAlign = Paint.Align.RIGHT
                    val xLeft = cx - r - sunClear - sideGap
                    nc.drawText("Daylight", xLeft, cy - labelRise, sideLabelPaint)
                    nc.drawText(dayValueStr, xLeft, cy + valueDrop, sideValuePaint)
                    sideChangePaint.textAlign = Paint.Align.RIGHT
                    var leftLineY = cy + valueDrop + sideChangePaint.textSize + 8f
                    if (changeStr != null) {
                        nc.drawText(changeStr, xLeft, leftLineY, sideChangePaint)
                        leftLineY += sideChangePaint.textSize + 4f
                    }
                    nc.drawText(belowMaxStr, xLeft, leftLineY, sideChangePaint)
                    leftLineY += sideChangePaint.textSize + 4f
                    nc.drawText(aboveMinStr, xLeft, leftLineY, sideChangePaint)
                    sideLabelPaint.textAlign = Paint.Align.LEFT
                    sideValuePaint.textAlign = Paint.Align.LEFT
                    val xRight = cx + r + sunClear + sideGap
                    nc.drawText("Night", xRight, cy - labelRise, sideLabelPaint)
                    nc.drawText(nightValueStr, xRight, cy + valueDrop, sideValuePaint)
                    // Very-dark period begin/end, under the night length
                    sideChangePaint.textAlign = Paint.Align.LEFT
                    val darkLabelY = cy + valueDrop + sideChangePaint.textSize + 8f
                    nc.drawText("Very dark", xRight, darkLabelY, sideChangePaint)
                    nc.drawText(darkRangeStr ?: "none", xRight, darkLabelY + sideChangePaint.textSize + 4f, sideChangePaint)

                    // Current-time Sun marker, just outside the rim, with a dotted radial line
                    val currentDisplayHour = normalizeTime((currentUtEpochDay - floor(currentUtEpochDay)) * 24.0 + displayOffsetHours)
                    val sunArcRad = Math.toRadians(hourToArc(currentDisplayHour).toDouble())
                    val sunDist = (r + sunBeyondRim) * 1.03f    // 3% farther out from center
                    val sunX = (cx + sunDist * cos(sunArcRad)).toFloat()
                    val sunY = (cy + sunDist * sin(sunArcRad)).toFloat()
                    // Thin red line from the center out along the current-time / Sun direction
                    drawLine(Color.Red, Offset(cx, cy), Offset(sunX, sunY), strokeWidth = 2f)
                    drawCircle(Color.Yellow, radius = sunRadius, center = Offset(sunX, sunY))
                    for (angle in 0 until 360 step 45) {
                        val rad = Math.toRadians(angle.toDouble())
                        drawLine(Color.Yellow,
                            Offset(sunX + sunRayInner * cos(rad).toFloat(), sunY + sunRayInner * sin(rad).toFloat()),
                            Offset(sunX + sunRayOuter * cos(rad).toFloat(), sunY + sunRayOuter * sin(rad).toFloat()),
                            strokeWidth = 2.5f)
                    }
                }
            }
            }
        }
    }
}
