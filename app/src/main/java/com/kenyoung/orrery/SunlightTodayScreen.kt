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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun SunlightTodayScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
    val epochDay = obs.epochDay; val lat = obs.lat; val lon = obs.lon; val now = obs.now
    val stdOffsetHours = obs.stdOffsetHours; val stdTimeLabel = obs.stdTimeLabel
    val useLocalTime = obs.useStandardTime; val useDst = obs.useDst

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

    // Twilight data for the table (anchored to observing night)
    val eventEpochDay = if (!riseTime.isNaN() && currentLocalSolar < riseTime) floor(epochDay) - 1.0 else floor(epochDay)
    val twilightData = computeTwilightTimes(eventEpochDay, lat, lon, offset)

    // Date and time for title — round to nearest minute to avoid showing previous minute
    val displayZoneId = if (useLocalTime) ZoneOffset.ofTotalSeconds((stdOffsetHours * 3600).roundToInt()) else ZoneOffset.UTC
    val dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(displayZoneId)
    val dateStr = dateFormatter.format(now)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(displayZoneId)
    val currentTimeStr = timeFormatter.format(now.plusSeconds(30))

    // Rise/set azimuths
    val riseAz = if (!riseTime.isNaN()) calculateAzAtRiseSet(lat, transitDec, true, HORIZON_REFRACTED) else Double.NaN
    val setAz = if (!setTime.isNaN()) calculateAzAtRiseSet(lat, transitDec, false, HORIZON_REFRACTED) else Double.NaN

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize().weight(1f)) {
            withDensityScaling { w, h ->

            // Paints
            // Drawing constants
            val tickLen = 30f
            val sunRadius = 15f
            val sunRayInner = 21f
            val sunRayOuter = 33f
            val curveStrokeWidth = 6f

            // Paints
            val titlePaintLabel = Paint().apply { color = LabelColor.toArgb(); textSize = 63f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val titlePaintWhite = Paint().apply { color = android.graphics.Color.WHITE; textSize = 63f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val axisLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
            val hourLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            val eventLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 39f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val eventTimePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val eventAzPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val dayLengthPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val bandLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 33f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val sunLabelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableHeaderPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableLabelPaint = Paint().apply { color = LabelColor.toArgb(); textSize = 44f; textAlign = Paint.Align.LEFT; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val tableDataPaint = Paint().apply { textSize = 44f; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val nextLabelPaint = Paint().apply { color = LabelColor.toArgb(); textSize = tableLabelPaint.textSize; textAlign = Paint.Align.LEFT; typeface = Typeface.MONOSPACE; isAntiAlias = true }
            val nextTimePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = tableLabelPaint.textSize; textAlign = Paint.Align.LEFT; typeface = Typeface.MONOSPACE; isAntiAlias = true }

            // Layout — three non-overlapping vertical bands computed from font metrics:
            //   Band 1: chart (top of canvas to chartBottom)
            //   Band 2: hour labels (chartBottom to hourBandBottom)
            //   Band 3: Rise/Transit/Set labels (hourBandBottom to eventBandBottom)
            //   Band 4: twilight table (eventBandBottom to bottom of canvas)
            val titleY = 60f
            val chartLeft = 80f
            val chartRight = w - 20f
            val chartTop = titleY + 40f
            val rowHeight = 57f

            // Band 4 height: twilight table (2-line header + 5 data rows + footnote + pad)
            val tableHeight = rowHeight * 8 + tableHeaderPaint.textSize + 4f

            // Band 2 height: hour labels + generous gap below
            val bandGap = 20f
            val hourBandH = hourLabelPaint.textSize + bandGap

            // Band 3 height: Rise/Transit/Set event labels + gap before table
            val eventLineSpacing = 8f
            val tableGap = 60f
            val eventBandH = eventLabelPaint.textSize + eventLineSpacing +
                             eventTimePaint.textSize + eventLineSpacing +
                             eventAzPaint.textSize + tableGap

            val chartBottom = h - tableHeight - eventBandH - hourBandH
            val chartW = chartRight - chartLeft
            val chartH = chartBottom - chartTop

            // Y-axis range: must encompass full nadir so the curve is never truncated
            val nadirAlt = -(90.0 - abs(lat - transitDec))
            val yMax = transitAlt + 5.0
            val yMin = nadirAlt - 5.0
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

                // --- Title ---
                val titleText1 = "Sunlight Today  "
                val titleText2 = dateStr
                val titleText3 = "  $currentTimeStr"
                val titleText4 = "  ($timeLabel)"
                var tx = 20f
                nc.drawText(titleText1, tx, titleY, titlePaintLabel)
                tx += titlePaintLabel.measureText(titleText1)
                nc.drawText(titleText2, tx, titleY, titlePaintWhite)
                tx += titlePaintWhite.measureText(titleText2)
                nc.drawText(titleText3, tx, titleY, titlePaintWhite)
                tx += titlePaintWhite.measureText(titleText3)
                nc.drawText(titleText4, tx, titleY, titlePaintLabel)

                // Length of day and daily change in upper right corner
                if (!riseTime.isNaN() && !setTime.isNaN()) {
                    val dayLengthHours = setTime - riseTime
                    val dlH = floor(dayLengthHours).toInt()
                    val dlM = floor((dayLengthHours - dlH) * 60.0).toInt()
                    val dayLengthY = chartTop + dayLengthPaint.textSize
                    nc.drawText("Length of Day %dh %dm".format(dlH, dlM), chartRight, dayLengthY, dayLengthPaint)

                    // Day length change from yesterday
                    val (yesterdayRise, yesterdaySet) = calculateSunTimes(floor(epochDay) - 1.0, lat, lon, offset)
                    if (!yesterdayRise.isNaN() && !yesterdaySet.isNaN()) {
                        val yesterdayLength = yesterdaySet - yesterdayRise
                        val changeMins = (dayLengthHours - yesterdayLength) * 60.0
                        val sign = if (changeMins >= 0) "+" else "-"
                        val absChange = abs(changeMins)
                        val cM = floor(absChange).toInt()
                        val cS = round((absChange - cM) * 60.0).toInt()
                        nc.drawText("Change %s%dm %02ds".format(sign, cM, cS), chartRight, dayLengthY + dayLengthPaint.textSize + 6f, dayLengthPaint)
                    }
                }

                // --- Twilight bands below horizon ---
                val civilY = altToY(CIVIL_TWILIGHT)
                val nauticalY = altToY(NAUTICAL_TWILIGHT)
                val astroY = altToY(ASTRONOMICAL_TWILIGHT)

                // Civil twilight band (0 to -6)
                drawRect(Color(0xFF0E2499), Offset(chartLeft, horizonY), Size(chartW, civilY - horizonY))
                // Nautical twilight band (-6 to -12)
                drawRect(Color(0xFF09165A), Offset(chartLeft, civilY), Size(chartW, nauticalY - civilY))
                // Astronomical twilight band (-12 to -18)
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

                // --- Compute elevation curve ---
                val numPoints = 240
                val curvePoints = mutableListOf<Pair<Float, Float>>()
                for (i in 0..numPoints) {
                    val t = startHour + i * (24.0 / numPoints)
                    val ha = t - transitTime
                    val alt = calculateAltitude(ha, lat, transitDec)
                    curvePoints.add(Pair(timeToX(t), altToY(alt)))
                }

                // --- Fill above-horizon area ---
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

                // --- Draw elevation curve ---
                val curvePath = Path()
                curvePath.moveTo(curvePoints[0].first, curvePoints[0].second)
                for (i in 1..numPoints) {
                    curvePath.lineTo(curvePoints[i].first, curvePoints[i].second)
                }
                drawPath(curvePath, Color(0xFFFFAA00), style = Stroke(width = curveStrokeWidth))

                // --- Horizon line ---
                drawLine(Color.White, Offset(chartLeft, horizonY), Offset(chartRight, horizonY), strokeWidth = 1.5f)
                nc.drawText("0°", chartLeft - 10f, horizonY + 9f, axisLabelPaint)
                drawLine(Color.White, Offset(chartLeft, horizonY), Offset(chartLeft + tickLen, horizonY), strokeWidth = 2f)

                // --- Y-axis altitude labels ---
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

                // --- X-axis hour labels (display hours divisible by 3) ---
                for (displayHour in 0..23 step 3) {
                    // Convert display hour back to local solar time for plotting
                    val t = displayHour.toDouble() - displayOffsetHours + offset
                    val x = timeToX(t)
                    if (x > chartLeft + 15 && x < chartRight - 15) {
                        nc.drawText("%02d".format(displayHour), x, chartBottom + hourLabelPaint.textSize, hourLabelPaint)
                        drawLine(Color(0xFF333333), Offset(x, chartTop), Offset(x, chartBottom), strokeWidth = 0.5f)
                        drawLine(Color.White, Offset(x, chartBottom - tickLen), Offset(x, chartBottom), strokeWidth = 2f)
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

                // --- Sun icon at current position with time label ---
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

                // --- Twilight times table ---
                val tableTopY = hourBandBottom + eventBandH
                val twDuskX = w * 0.62f
                val twDawnX = w * 0.84f
                val currentDisplayDate = floor(now.epochSecond.toDouble() / SECONDS_PER_DAY + displayOffsetHours / 24.0).toLong()
                var anyAsterisk = false

                // Find the chronologically next twilight event
                var nextIsDusk = true
                var nextIndex = -1
                val duskMidnightUt = floor(twilightData.duskAnchor) - offset / 24.0
                val dawnMidnightUt = floor(twilightData.dawnAnchor) - offset / 24.0
                for (i in 0..4) {
                    if (!twilightData.dusk[i].isNaN() && duskMidnightUt + twilightData.dusk[i] / 24.0 > currentUtEpochDay) {
                        nextIsDusk = true; nextIndex = i; break
                    }
                }
                if (nextIndex == -1) {
                    // Dawn events are chronologically earliest at index 4 (darkness ends
                    // at -18°) through to latest at index 0 (golden hour ends at 6°).
                    // Find the first future event scanning earliest to latest.
                    for (i in 4 downTo 0) {
                        if (!twilightData.dawn[i].isNaN() && dawnMidnightUt + twilightData.dawn[i] / 24.0 > currentUtEpochDay) {
                            nextIsDusk = false; nextIndex = i; break
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

                // "Next transition in HH:MM" countdown
                if (nextIndex >= 0) {
                    val nextEventUtEpochDay = if (nextIsDusk)
                        duskMidnightUt + twilightData.dusk[nextIndex] / 24.0
                    else
                        dawnMidnightUt + twilightData.dawn[nextIndex] / 24.0
                    val deltaHours = (nextEventUtEpochDay - currentUtEpochDay) * 24.0
                    if (deltaHours > 0) {
                        val neH = floor(deltaHours).toInt()
                        val neM = floor((deltaHours - neH) * 60.0).toInt()
                        val nextLabelStr = "Next transition in "
                        nc.drawText(nextLabelStr, 20f, currY, nextLabelPaint)
                        nc.drawText("%dh %dm".format(neH, neM), 20f + nextLabelPaint.measureText(nextLabelStr), currY, nextTimePaint)
                        currY += rowHeight
                    }
                }

                // "* Tomorrow" footnote
                if (anyAsterisk) {
                    tableLabelPaint.color = LabelColor.toArgb()
                    nc.drawText("* Tomorrow", 20f, currY + 5f, tableLabelPaint)
                }
            }
            }
        }
        TimeDisplayToggle(useLocalTime, useDst, onTimeDisplayChange)
    }
}
