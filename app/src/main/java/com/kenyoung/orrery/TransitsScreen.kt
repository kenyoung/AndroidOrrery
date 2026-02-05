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

            // Determine the "observing date" based on longitude-derived local time.
            // Use the longitude-based offset rather than the phone's system timezone
            // so the display is consistent with the observation location.
            // If local time is before noon, we're in the morning portion of the
            // previous night, so use yesterday's date.
            val currentOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600).toInt())
            val localDateTime = now.atOffset(currentOffset).toLocalDateTime()
            val observingDate = if (localDateTime.hour < 12) {
                localDateTime.toLocalDate().minusDays(1)
            } else {
                localDateTime.toLocalDate()
            }

            val year = observingDate.year
            val solsticeMonth = if (lat >= 0) 12 else 6
            val solsticeDay = 21
            val solsticeDate = ZonedDateTime.of(year, solsticeMonth, solsticeDay, 12, 0, 0, 0, ZoneOffset.UTC)
            val (solsticeRise, solsticeSet) = calculateSunTimes(solsticeDate.toLocalDate().toEpochDay().toDouble(), lat, lon, offsetHours)

            val nMaxDuration = if (solsticeRise.isNaN() || solsticeSet.isNaN()) {
                24.0
            } else {
                (solsticeRise + (24.0 - solsticeSet))
            }

            // Moon figures are placed at sunrise_x + 15f with radius 10f, so need 26f margin.
            // Reduce drawing width for scale calculation to leave room on the right for moons.
            // The sunrise side of the night extends further than sunset side from centerX,
            // so we need extra margin. Using 50f to account for equation of time variations
            // (latest sunrise can be ~2 weeks after winter solstice).
            val moonMargin = 50f
            val pixelsPerHour = (drawingWidth - moonMargin) / nMaxDuration
            val textHeight = 40f
            val textY = 30f

            val nowDate = observingDate
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

            // --- SUPERIOR PLANET SEGMENT TRACKING (Two-Pass) ---
            // Collect ALL candidate positions per segment, then optimize placement in pass 2
            data class SegmentCandidate(val x: Float, val y: Float)
            data class SegmentState(val candidates: ArrayList<SegmentCandidate>, var lastX: Float, val type: String)
            val superiorSegments = HashMap<String, SegmentState>() // Key: "Planet_Type" e.g. "Saturn_s"

            // Completed segments with all their candidates
            data class CompletedSegment(val planet: String, val type: String, val candidates: List<SegmentCandidate>)
            val completedSegments = ArrayList<CompletedSegment>()

            // All curve points for soft obstacle detection
            val allCurvePoints = ArrayList<Offset>()

            // Store sunset/sunrise x-coordinates for each y position
            val nightBounds = HashMap<Int, Pair<Float, Float>>() // y -> (sunsetX, sunriseX)

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

            val localTime = localDateTime.toLocalTime()
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

                    if (altNoon < HORIZON_REFRACTED) {
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

                    // Store night bounds for label placement
                    if (drawPlanets && validSunset && validSunrise) {
                        nightBounds[y] = Pair(xSunset.toFloat(), xSunrise.toFloat())
                    }

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
                                    // SUPERIOR PLANETS: Collect all candidates per segment
                                    val key = "${planet.name}_$keySuffix"
                                    var state = superiorSegments[key]

                                    if (isVisible) {
                                        // 1. Check for Discontinuity (Wrap-around jump > 6 hours)
                                        if (state != null && abs(xPos - state.lastX) > (pixelsPerHour * 6f)) {
                                            // Commit previous segment with all its candidates
                                            if (state.candidates.isNotEmpty()) {
                                                completedSegments.add(CompletedSegment(planet.name, state.type, state.candidates.toList()))
                                            }
                                            state = null
                                        }

                                        if (state == null) {
                                            // Start new segment
                                            state = SegmentState(ArrayList(), xPos.toFloat(), keySuffix)
                                            superiorSegments[key] = state
                                        }

                                        // Sample candidates every ~20 pixels vertically to avoid too many
                                        if (state.candidates.isEmpty() || abs(yPos - state.candidates.last().y) >= 20f) {
                                            state.candidates.add(SegmentCandidate(xPos.toFloat(), yPos))
                                        }
                                        state.lastX = xPos.toFloat()
                                    } else {
                                        // Segment ended - commit it
                                        if (state != null) {
                                            if (state.candidates.isNotEmpty()) {
                                                completedSegments.add(CompletedSegment(planet.name, state.type, state.candidates.toList()))
                                            }
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
                                    val rDiff = normalizeHourAngle(riseTimeCurr - events.rise)

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
                                    val sDiff = normalizeHourAngle(events.set - setTimePrev)

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
                    if (state.candidates.isNotEmpty()) {
                        completedSegments.add(CompletedSegment(pName, state.type, state.candidates.toList()))
                    }
                }
            }

            // Collect all curve points for soft obstacle detection
            allCurvePoints.addAll(sunPoints)
            for (planet in planets) {
                allCurvePoints.addAll(planetTransits[planet.name]!!)
                allCurvePoints.addAll(planetRises[planet.name]!!)
                allCurvePoints.addAll(planetSets[planet.name]!!)
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
            while (scanD <= endScan) {
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

                // Label dimensions for collision detection based on font metrics
                val fontMetrics = mainTextPaint.fontMetrics
                val maxCharHeight = fontMetrics.descent - fontMetrics.ascent
                // Measure a wide character to get max width (M is typically widest)
                val maxCharWidth = mainTextPaint.measureText("M")
                val labelWidth = 2f * maxCharWidth
                val labelHeight = maxCharHeight
                val labelMargin = 10f  // Margin from screen edges
                val curveProximityThreshold = 30f  // Soft obstacle distance

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

                // --- TWO-PASS LABEL PLACEMENT ---
                // Track placed label centers for spacing calculations
                data class PlacedLabel(val x: Float, val y: Float)
                val placedLabels = ArrayList<PlacedLabel>()

                // Helper: minimum distance to any placed label
                fun minDistToPlaced(x: Float, y: Float): Float {
                    if (placedLabels.isEmpty()) return Float.MAX_VALUE
                    return placedLabels.minOf { sqrt((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y)) }
                }

                // Helper: soft penalty for curve proximity (returns 0 to 1, lower is better)
                fun curveProximityPenalty(x: Float, y: Float): Float {
                    var minDist = Float.MAX_VALUE
                    // Sample every 10th point for performance
                    for (i in allCurvePoints.indices step 10) {
                        val pt = allCurvePoints[i]
                        val d = sqrt((pt.x - x) * (pt.x - x) + (pt.y - y) * (pt.y - y))
                        if (d < minDist) minDist = d
                    }
                    return if (minDist < curveProximityThreshold) 1f - (minDist / curveProximityThreshold) else 0f
                }

                // Helper: get night bounds for a y position
                fun getNightBounds(y: Float): Pair<Float, Float>? {
                    val yInt = y.toInt()
                    return nightBounds[yInt] ?: nightBounds[yInt - 1] ?: nightBounds[yInt + 1]
                }

                // Helper: check if position is within valid bounds
                // Label bounding box: x to x+labelWidth horizontally, y-labelHeight/2 to y+labelHeight/2 vertically
                fun isValidPosition(x: Float, y: Float): Boolean {
                    val halfHeight = labelHeight / 2f

                    // Check vertical bounds (top and bottom of plot)
                    if (y - halfHeight < textHeight) return false
                    if (y + halfHeight > h) return false

                    // Check horizontal screen bounds
                    if (x < paddingLeft) return false
                    if (x + labelWidth > w - paddingRight) return false

                    // Check sunset/sunrise bounds
                    val bounds = getNightBounds(y)
                    if (bounds != null) {
                        val (sunsetX, sunriseX) = bounds
                        if (x < sunsetX) return false  // Left edge past sunset
                        if (x + labelWidth > sunriseX) return false  // Right edge past sunrise
                    }

                    return true
                }

                // Helper: score a candidate position (higher is better)
                fun scoreCandidate(x: Float, y: Float): Float {
                    if (!isValidPosition(x, y)) return -1f
                    val distScore = minDistToPlaced(x, y)
                    val curvePenalty = curveProximityPenalty(x, y) * 50f  // Scale penalty
                    return distScore - curvePenalty
                }

                // 1. Place Mercury labels first (unchanged strategy)
                val mercury = planets.find { it.name == "Mercury" }
                if (mercury != null) {
                    val col = mercury.color.toArgb()
                    val sym = mercury.symbol
                    for (peak in mercuryPeaks) {
                        val offsetX = if (peak.type == "s") -10f else -60f
                        val lx = peak.x + offsetX
                        val ly = peak.y
                        if (isValidPosition(lx, ly)) {
                            drawLabel(lx, ly, sym, peak.type, col)
                            placedLabels.add(PlacedLabel(lx, ly))
                        }
                    }
                }

                // 2. Place Venus labels (unchanged strategy)
                val venus = planets.find { it.name == "Venus" }
                if (venus != null) {
                    val col = venus.color.toArgb()
                    val sym = venus.symbol
                    for (suffix in listOf("t", "r", "s")) {
                        bestLabels["Venus_$suffix"]?.let {
                            if (it.found && isValidPosition(it.x, it.y)) {
                                drawLabel(it.x, it.y, sym, suffix, col)
                                placedLabels.add(PlacedLabel(it.x, it.y))
                            }
                        }
                    }
                }

                // 3. Place superior planet labels using greedy max-min algorithm
                val superiorOrder = listOf("Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
                val typeOrder = listOf("t", "r", "s")

                for (planetName in superiorOrder) {
                    val planet = planets.find { it.name == planetName } ?: continue
                    val col = planet.color.toArgb()
                    val sym = planet.symbol

                    for (type in typeOrder) {
                        // Get all segments for this planet/type
                        val segments = completedSegments.filter { it.planet == planetName && it.type == type }

                        for (segment in segments) {
                            // Find best candidate in this segment using greedy max-min
                            var bestCandidate: SegmentCandidate? = null
                            var bestScore = Float.MIN_VALUE

                            for (candidate in segment.candidates) {
                                val score = scoreCandidate(candidate.x, candidate.y)
                                if (score > bestScore) {
                                    bestScore = score
                                    bestCandidate = candidate
                                }
                            }

                            // Draw the best candidate if valid
                            if (bestCandidate != null && bestScore >= 0) {
                                drawLabel(bestCandidate.x, bestCandidate.y, sym, type, col)
                                placedLabels.add(PlacedLabel(bestCandidate.x, bestCandidate.y))
                            }
                        }
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
