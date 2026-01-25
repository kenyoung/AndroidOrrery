package com.kenyoung.orrery

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

@Composable
fun GraphicsWindow(lat: Double, lon: Double, now: Instant, cache: AstroCache, zoneId: ZoneId, isLive: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val paddingLeft = 35f
            val paddingRight = 42f
            val drawingWidth = w - paddingLeft - paddingRight
            val centerX = paddingLeft + (drawingWidth / 2f)

            val offsetHours = round(lon / 15.0)

            val year = ZonedDateTime.ofInstant(now, zoneId).year
            val solsticeMonth = if (lat >= 0) 12 else 6
            val solsticeDay = 21
            val solsticeDate = ZonedDateTime.of(year, solsticeMonth, solsticeDay, 12, 0, 0, 0, zoneId)
            val (solsticeRise, solsticeSet) = calculateSunTimes(solsticeDate.toLocalDate().toEpochDay().toDouble(), lat, lon, offsetHours)

            val nMaxDuration = if (solsticeRise.isNaN() || solsticeSet.isNaN()) {
                24.0
            } else {
                (solsticeRise + (24.0 - solsticeSet))
            }

            val pixelsPerHour = drawingWidth / nMaxDuration
            val textHeight = 40f
            val textY = 30f

            val nowZoned = ZonedDateTime.ofInstant(now, zoneId)
            val nowDate = nowZoned.toLocalDate()
            val nowEpochDay = nowDate.toEpochDay().toDouble()

            // Helper for Date lines
            fun getXSunsetForDate(date: LocalDate): Float {
                val epochDay = date.toEpochDay().toDouble()
                val (_, setTimePrev) = cache.getSunTimes(epochDay - 1.0, false)
                return (centerX + ((setTimePrev - 24.0) * pixelsPerHour)).toFloat()
            }

            fun normalizeGraphTime(time: Double): Double {
                var diff = time - 24.0
                if (diff < -12.0) diff += 24.0 else if (diff > 12.0) diff -= 24.0
                return diff
            }

            val planets = getOrreryPlanets().filter { it.name != "Earth" }
            val superiorPlanets = listOf("Mars", "Jupiter", "Saturn", "Uranus", "Neptune")

            val sunPoints = ArrayList<Offset>()
            val planetTransits = planets.associate { it.name to ArrayList<Offset>() }
            val planetRises = planets.associate { it.name to ArrayList<Offset>() }
            val planetSets = planets.associate { it.name to ArrayList<Offset>() }

            // Standard Labels (Venus)
            val bestLabels = HashMap<String, LabelPosition>()
            for (p in planets) {
                bestLabels["${p.name}_t"] = LabelPosition()
                bestLabels["${p.name}_r"] = LabelPosition()
                bestLabels["${p.name}_s"] = LabelPosition()
            }

            // --- SUPERIOR PLANET SEGMENT TRACKING ---
            // Data structure to track the "best" label candidate for the current visible segment
            data class SegmentState(var bestX: Float, var bestY: Float, var minDist: Float, var lastX: Float, val type: String)
            val superiorSegments = HashMap<String, SegmentState>() // Key: "Planet_Type" e.g. "Saturn_s"

            data class SuperiorLabel(val x: Float, val y: Float, val type: String)
            val superiorLabels = HashMap<String, ArrayList<SuperiorLabel>>()
            for (p in superiorPlanets) superiorLabels[p] = ArrayList()

            // --- MERCURY PEAK DETECTION STATE ---
            data class MercuryPeak(val x: Float, val y: Float, val type: String)
            val mercuryPeaks = ArrayList<MercuryPeak>()
            // Windows to detect local maxima in elongation (Time diff). Size 3 for simple peak check.
            val mercRiseWindow = ArrayDeque<Triple<Double, Float, Float>>(3) // diff, x, y
            val mercSetWindow = ArrayDeque<Triple<Double, Float, Float>>(3)
            // ------------------------------------

            val darkBlueArgb = 0xFF0000D1.toInt()
            val blackArgb = android.graphics.Color.BLACK
            val twilightPaint = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
            val greyPaint = Paint().apply { color = android.graphics.Color.GRAY; strokeWidth = 1f; style = Paint.Style.FILL }

            val targetOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
            val localTime = now.atOffset(targetOffset).toLocalTime()
            val currentHour = localTime.hour + (localTime.minute / 60.0) + (localTime.second / 3600.0)
            val xNow = centerX + (normalizeGraphTime(currentHour) * pixelsPerHour)

            // --- DRAWING LOOP ---
            drawIntoCanvas { canvas ->
                for (y in textHeight.toInt() until h.toInt()) {
                    val fraction = (h - y) / h
                    val daysOffset = fraction * 365.25

                    val targetEpochDay = nowEpochDay + daysOffset

                    val (_, setTimePrev) = cache.getSunTimes(targetEpochDay - 1.0, false)
                    val (riseTimeCurr, _) = cache.getSunTimes(targetEpochDay, false)
                    val (_, astroSetPrev) = cache.getSunTimes(targetEpochDay - 1.0, true)
                    val (astroRiseCurr, _) = cache.getSunTimes(targetEpochDay, true)

                    val xSunset = centerX + (normalizeGraphTime(setTimePrev) * pixelsPerHour)
                    val xSunrise = centerX + (normalizeGraphTime(riseTimeCurr) * pixelsPerHour)
                    var validSunset = !xSunset.isNaN()
                    var validSunrise = !xSunrise.isNaN()

                    var drawPlanets = false
                    var effectiveXSunset = xSunset
                    var effectiveXSunrise = xSunrise

                    val sunDec = calculateSunDeclination(targetEpochDay)
                    val altNoon = 90.0 - abs(lat - Math.toDegrees(sunDec))

                    if (altNoon < -0.833) {
                        drawPlanets = true
                        effectiveXSunset = 0.0
                        effectiveXSunrise = w.toDouble()
                        twilightPaint.shader = null
                        twilightPaint.color = blackArgb
                        canvas.nativeCanvas.drawLine(0f, y.toFloat(), w, y.toFloat(), twilightPaint)
                    } else if (validSunset && validSunrise) {
                        drawPlanets = true
                        val xAstroEnd = if (!astroSetPrev.isNaN()) centerX + (normalizeGraphTime(astroSetPrev) * pixelsPerHour) else null
                        if (xAstroEnd != null) {
                            twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), darkBlueArgb, blackArgb, Shader.TileMode.CLAMP)
                            canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), twilightPaint)
                        } else {
                            twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), intArrayOf(darkBlueArgb, blackArgb, darkBlueArgb), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                            canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                        }

                        if (xAstroEnd != null) {
                            val xAstroStart = if (!astroRiseCurr.isNaN()) centerX + (normalizeGraphTime(astroRiseCurr) * pixelsPerHour) else null
                            if (xAstroStart != null) {
                                twilightPaint.shader = LinearGradient(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), blackArgb, darkBlueArgb, Shader.TileMode.CLAMP)
                                canvas.nativeCanvas.drawLine(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                            }
                        }
                    }

                    if (validSunset) sunPoints.add(Offset(xSunset.toFloat(), y.toFloat()))
                    if (validSunrise) sunPoints.add(Offset(xSunrise.toFloat(), y.toFloat()))

                    // Draw gray pixel at the current time if it is night time on this specific day
                    if (drawPlanets) {
                        if (xNow >= effectiveXSunset && xNow <= effectiveXSunrise) {
                            canvas.nativeCanvas.drawPoint(xNow.toFloat(), y.toFloat(), greyPaint)
                        }
                    }

                    if (drawPlanets) {
                        for (planet in planets) {
                            val events = cache.getPlanetEvents(targetEpochDay, planet.name)
                            val targetHourOffset = when (planet.name) { "Mars" -> -2.0; "Jupiter" -> -1.0; "Saturn" -> 0.0; "Uranus" -> 1.0; "Neptune" -> 2.0; else -> 0.0 }
                            val targetX = centerX + (targetHourOffset * pixelsPerHour)

                            fun processEvent(time: Double, list: ArrayList<Offset>, keySuffix: String) {
                                if (time.isNaN()) return
                                val xPos = centerX + (normalizeGraphTime(time) * pixelsPerHour)
                                val yPos = y.toFloat()
                                val isVisible = (xPos >= effectiveXSunset && xPos <= effectiveXSunrise)

                                if (isVisible) {
                                    list.add(Offset(xPos.toFloat(), yPos))
                                }

                                // --- LABELING LOGIC ---
                                if (planet.name == "Mercury") {
                                    // Mercury Handled Separately via Peak Detection
                                } else if (planet.name in superiorPlanets) {
                                    // SUPERIOR PLANETS: Find the gaps
                                    val key = "${planet.name}_$keySuffix"
                                    var state = superiorSegments[key]

                                    if (isVisible) {
                                        // 1. Check for Discontinuity (Wrap-around jump > 6 hours)
                                        // If the curve jumps significantly, treat it as a new segment
                                        if (state != null && abs(xPos - state.lastX) > (pixelsPerHour * 6f)) {
                                            // Commit previous segment
                                            superiorLabels[planet.name]?.add(SuperiorLabel(state.bestX, state.bestY, state.type))
                                            state = null // Force new start
                                        }

                                        val dist = abs(xPos - targetX)
                                        if (state == null) {
                                            // Start tracking a new visible segment
                                            state = SegmentState(xPos.toFloat(), yPos, dist.toFloat(), xPos.toFloat(), keySuffix)
                                            superiorSegments[key] = state
                                        } else {
                                            // Update existing segment if this point is closer to target
                                            if (dist.toFloat() < state.minDist) {
                                                state.minDist = dist.toFloat()
                                                state.bestX = xPos.toFloat()
                                                state.bestY = yPos
                                            }
                                            state.lastX = xPos.toFloat()
                                        }
                                    } else {
                                        // 2. Not Visible (Daylight Gap)
                                        // If we were tracking a segment and it went invisible, the segment ended. Commit it.
                                        if (state != null) {
                                            superiorLabels[planet.name]?.add(SuperiorLabel(state.bestX, state.bestY, state.type))
                                            superiorSegments.remove(key)
                                        }
                                    }

                                } else {
                                    // VENUS (Inferior): Standard single-point logic
                                    if (isVisible) {
                                        val dist = abs(xPos - targetX)
                                        val labelTracker = bestLabels["${planet.name}_$keySuffix"]!!
                                        if (dist < labelTracker.minDistToCenter) {
                                            labelTracker.minDistToCenter = dist.toFloat()
                                            labelTracker.x = xPos.toFloat(); labelTracker.y = yPos; labelTracker.found = true
                                        }
                                    }
                                }
                            }

                            processEvent(events.transit, planetTransits[planet.name]!!, "t")
                            processEvent(events.rise, planetRises[planet.name]!!, "r")
                            processEvent(events.set, planetSets[planet.name]!!, "s")

                            // --- SPECIAL MERCURY PEAK DETECTION ---
                            if (planet.name == "Mercury") {
                                // 1. RISING (Morning / GWE)
                                if (!events.rise.isNaN() && !riseTimeCurr.isNaN()) {
                                    var rDiff = riseTimeCurr - events.rise
                                    while(rDiff < -12) rDiff += 24.0; while(rDiff > 12) rDiff -= 24.0

                                    if (rDiff > 0) {
                                        val mx = centerX + (normalizeGraphTime(events.rise) * pixelsPerHour).toFloat()
                                        if (mx >= effectiveXSunset && mx <= effectiveXSunrise) {
                                            mercRiseWindow.add(Triple(rDiff, mx, y.toFloat()))
                                            if (mercRiseWindow.size > 3) mercRiseWindow.removeFirst()
                                            if (mercRiseWindow.size == 3) {
                                                val (d1, _, _) = mercRiseWindow.first()
                                                val (d2, x2, y2) = mercRiseWindow.elementAt(1)
                                                val (d3, _, _) = mercRiseWindow.last()
                                                if (d2 > d1 && d2 > d3) {
                                                    mercuryPeaks.add(MercuryPeak(x2, y2, "r"))
                                                }
                                            }
                                        } else mercRiseWindow.clear()
                                    } else mercRiseWindow.clear()
                                } else mercRiseWindow.clear()

                                // 2. SETTING (Evening / GEE)
                                if (!events.set.isNaN() && !setTimePrev.isNaN()) {
                                    var sDiff = events.set - setTimePrev
                                    while(sDiff < -12) sDiff += 24.0; while(sDiff > 12) sDiff -= 24.0

                                    if (sDiff > 0) {
                                        val mx = centerX + (normalizeGraphTime(events.set) * pixelsPerHour).toFloat()
                                        if (mx >= effectiveXSunset && mx <= effectiveXSunrise) {
                                            mercSetWindow.add(Triple(sDiff, mx, y.toFloat()))
                                            if (mercSetWindow.size > 3) mercSetWindow.removeFirst()
                                            if (mercSetWindow.size == 3) {
                                                val (d1, _, _) = mercSetWindow.first()
                                                val (d2, x2, y2) = mercSetWindow.elementAt(1)
                                                val (d3, _, _) = mercSetWindow.last()
                                                if (d2 > d1 && d2 > d3) {
                                                    mercuryPeaks.add(MercuryPeak(x2, y2, "s"))
                                                }
                                            }
                                        } else mercSetWindow.clear()
                                    } else mercSetWindow.clear()
                                } else mercSetWindow.clear()
                            }
                        }
                    }
                }

                // End of Drawing Loop: Commit any active superior segments that run to the bottom of the screen
                for ((key, state) in superiorSegments.entries) {
                    val pName = key.substringBefore("_")
                    superiorLabels[pName]?.add(SuperiorLabel(state.bestX, state.bestY, state.type))
                }
            }

            drawPoints(points = sunPoints, pointMode = PointMode.Points, color = Color.White, strokeWidth = 2f)

            // Grid Lines & Month Labels
            drawIntoCanvas { canvas ->
                val monthPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
                val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }

                // Helper to get Y for non-scrolling (fixed window)
                fun getY(tEpoch: Double): Float {
                    val diff = tEpoch - nowEpochDay
                    return (h - ((diff / 365.25) * h)).toFloat()
                }

                var d = nowDate.minusMonths(1).withDayOfMonth(1)
                val endDate = nowDate.plusYears(1).plusMonths(1)

                while (d.isBefore(endDate)) {
                    val nextMonth = d.plusMonths(1)
                    val yStart = getY(d.toEpochDay().toDouble())
                    val x1_grid = getXSunsetForDate(d)

                    val epochDayGrid = d.toEpochDay().toDouble()
                    val (riseGrid, _) = cache.getSunTimes(epochDayGrid, false)

                    if (yStart >= textHeight && yStart <= h && !riseGrid.isNaN()) {
                        val x2_grid = centerX + (normalizeGraphTime(riseGrid) * pixelsPerHour)
                        canvas.nativeCanvas.drawLine(x1_grid, yStart, x2_grid.toFloat(), yStart, gridPaint)
                    }

                    val midMonth = d.plusDays(15)
                    val yMid = getY(midMonth.toEpochDay().toDouble())

                    if (yMid in textHeight..h) {
                        val monthName = d.format(DateTimeFormatter.ofPattern("MMM"))
                        val xSunsetMid = getXSunsetForDate(midMonth)
                        val targetX = xSunsetMid - monthPaint.textSize
                        val finalX = max(25f, targetX)

                        val yEnd = getY(nextMonth.toEpochDay().toDouble())
                        val x1 = getXSunsetForDate(d)
                        val x2 = getXSunsetForDate(nextMonth)
                        val angleDeg = Math.toDegrees(atan2((yEnd - yStart).toDouble(), (x2 - x1).toDouble())).toFloat()

                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.rotate(angleDeg, finalX, yMid)
                        canvas.nativeCanvas.drawText(monthName, finalX, yMid, monthPaint)
                        canvas.nativeCanvas.restore()
                    }
                    d = nextMonth
                }
            }

            for (planet in planets) {
                val isInner = planet.name == "Mercury" || planet.name == "Venus"
                val riseSetColor = if (isInner) planet.color else planet.color.copy(alpha = 0.35f)
                if (!isInner) drawPoints(points = planetTransits[planet.name]!!, pointMode = PointMode.Points, color = planet.color, strokeWidth = 4f)
                drawPoints(points = planetRises[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
                drawPoints(points = planetSets[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
            }

            // Moons
            val fullMoons = ArrayList<Offset>(); val newMoons = ArrayList<Offset>(); val firstQuarters = ArrayList<Offset>(); val lastQuarters = ArrayList<Offset>()

            val startScan = nowEpochDay - 2.0
            val endScan = nowEpochDay + 365.25 + 2.0

            var prevElong = calculateMoonPhaseAngle(startScan)
            var scanD = startScan + 1.0
            while(scanD <= endScan) {
                val currElong = calculateMoonPhaseAngle(scanD)
                fun norm(a: Double): Double { var v = a % 360.0; if (v > 180) v -= 360; if (v <= -180) v += 360; return v }
                fun checkCrossing(targetAngle: Double, list: ArrayList<Offset>) {
                    val pNorm = norm(prevElong - targetAngle); val cNorm = norm(currElong - targetAngle)
                    if (pNorm < 0 && cNorm >= 0) {
                        val exactMoonDay = (scanD - 1.0) + (-pNorm / (cNorm - pNorm))
                        val (riseTime, _) = cache.getSunTimes(scanD - 1.0, false)
                        if (!riseTime.isNaN()) {
                            val xMoon = centerX + (normalizeGraphTime(riseTime) * pixelsPerHour) + 15f
                            val diff = exactMoonDay - nowEpochDay
                            val yMoon = (h - ((diff / 365.25) * h)).toFloat()
                            if (yMoon >= textHeight && yMoon <= h) list.add(Offset(xMoon.toFloat(), yMoon))
                        }
                    }
                }
                checkCrossing(0.0, newMoons); checkCrossing(90.0, firstQuarters); checkCrossing(180.0, fullMoons); checkCrossing(270.0, lastQuarters)
                prevElong = currElong
                scanD += 1.0
            }

            drawIntoCanvas { canvas ->
                val timePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
                for (k in -14..14) {
                    val xPos = centerX + (k * pixelsPerHour)
                    if (xPos >= paddingLeft && xPos <= (w - paddingRight)) {
                        var hour = (k % 24); if (hour < 0) hour += 24
                        canvas.nativeCanvas.drawText(hour.toString(), xPos.toFloat(), textY, timePaint)
                    }
                }
                val mainTextPaint = Paint().apply { textSize = 42f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                val subTextPaint = Paint().apply { textSize = 27f; textAlign = Paint.Align.LEFT; isAntiAlias = true; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }

                fun drawLabel(x: Float, y: Float, sym: String, sub: String, col: Int) {
                    val sw = mainTextPaint.measureText(sym); val subw = subTextPaint.measureText(sub)
                    val sx = x + sw; val subx = sx + (sw/2f) + (subw * (if (sub == "s") 0.375f else 0.75f))
                    mainTextPaint.style = Paint.Style.STROKE; mainTextPaint.strokeWidth = 4f; mainTextPaint.color = android.graphics.Color.BLACK
                    canvas.nativeCanvas.drawText(sym, sx, y, mainTextPaint)
                    mainTextPaint.style = Paint.Style.FILL; mainTextPaint.color = col
                    canvas.nativeCanvas.drawText(sym, sx, y, mainTextPaint)
                    subTextPaint.style = Paint.Style.STROKE; subTextPaint.strokeWidth = 3f; subTextPaint.color = android.graphics.Color.BLACK
                    canvas.nativeCanvas.drawText(sub, subx, y+8f, subTextPaint)
                    subTextPaint.style = Paint.Style.FILL; subTextPaint.color = col
                    canvas.nativeCanvas.drawText(sub, subx, y+8f, subTextPaint)
                }

                for (p in planets) {
                    val col = p.color.toArgb()
                    val sym = p.symbol

                    if (p.name == "Mercury") {
                        // Skip main loop, handled below
                    } else if (p.name in superiorPlanets) {
                        // Draw ALL collected superior labels
                        superiorLabels[p.name]?.forEach { label ->
                            drawLabel(label.x, label.y, sym, label.type, col)
                        }
                    } else {
                        // VENUS/Others: Standard loop
                        bestLabels["${p.name}_t"]?.let { if(it.found) drawLabel(it.x, it.y, sym, "t", col) }
                        bestLabels["${p.name}_r"]?.let { if(it.found) drawLabel(it.x, it.y, sym, "r", col) }
                        bestLabels["${p.name}_s"]?.let { if(it.found) drawLabel(it.x, it.y, sym, "s", col) }
                    }
                }

                // --- DRAW MERCURY PEAK LABELS ---
                val mercury = planets.find { it.name == "Mercury" }
                if (mercury != null) {
                    val col = mercury.color.toArgb()
                    val sym = mercury.symbol
                    for (peak in mercuryPeaks) {
                        // Offset: -10f for Setting (GEE), -60f for Rising (GWE)
                        val offsetX = if (peak.type == "s") -10f else -60f
                        drawLabel(peak.x + offsetX, peak.y, sym, peak.type, col)
                    }
                }

                val moonFill = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
                val moonStroke = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
                for (pos in fullMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonFill)
                for (pos in newMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
                val startAngleFirst = if (lat >= 0) -90f else 90f
                for (pos in firstQuarters) {
                    canvas.nativeCanvas.drawArc(RectF(pos.x-10f, pos.y-10f, pos.x+10f, pos.y+10f), startAngleFirst, 180f, true, moonFill)
                    canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
                }
                val startAngleLast = if (lat >= 0) 90f else -90f
                for (pos in lastQuarters) {
                    canvas.nativeCanvas.drawArc(RectF(pos.x-10f, pos.y-10f, pos.x+10f, pos.y+10f), startAngleLast, 180f, true, moonFill)
                    canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
                }
            }
        }
    }
}