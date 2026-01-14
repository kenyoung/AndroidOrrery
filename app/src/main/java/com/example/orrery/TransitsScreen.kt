package com.example.orrery

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

@Composable
fun GraphicsWindow(lat: Double, lon: Double, now: Instant, cache: AstroCache, zoneId: ZoneId, isLive: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val paddingLeft = 35f
        val paddingRight = 42f
        val drawingWidth = w - paddingLeft - paddingRight
        val centerX = paddingLeft + (drawingWidth / 2f)

        val standardOffset = zoneId.rules.getStandardOffset(now)
        val offsetHours = standardOffset.totalSeconds / 3600.0

        val year = ZonedDateTime.ofInstant(now, zoneId).year
        val solsticeMonth = if (lat >= 0) 12 else 6
        val solsticeDay = 21
        val solsticeDate = ZonedDateTime.of(year, solsticeMonth, solsticeDay, 12, 0, 0, 0, zoneId)
        val (solsticeRise, solsticeSet) = calculateSunTimes(solsticeDate.toLocalDate().toEpochDay().toDouble(), lat, lon, offsetHours)

        // FIX: Handle Arctic latitudes where solstice times are NaN (Sun never rises)
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

        fun getYForDate(date: LocalDate): Float {
            val daysOffset = ChronoUnit.DAYS.between(nowDate, date)
            return (h - ((daysOffset / 365.25) * h)).toFloat()
        }
        fun getYForEpochDay(targetEpochDay: Double): Float {
            val daysOffset = targetEpochDay - nowEpochDay
            return (h - ((daysOffset / 365.25) * h)).toFloat()
        }
        fun getXSunsetForDate(date: LocalDate): Float {
            val epochDay = date.toEpochDay().toDouble()
            val (_, setTimePrev) = cache.getSunTimes(epochDay - 1.0, false)
            return (centerX + ((setTimePrev - 24.0) * pixelsPerHour)).toFloat()
        }

        // Helper to normalize time to [-12, +12] relative to midnight
        fun normalizeGraphTime(time: Double): Double {
            var diff = time - 24.0
            if (diff < -12.0) diff += 24.0 else if (diff > 12.0) diff -= 24.0
            return diff
        }

        val blueGreen = Color(0xFF20B2AA)
        val orange = Color(0xFFFFA500)
        val neptuneBlue = Color(0xFF6495ED)
        val planets = listOf(
            PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
            PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
            PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
            PlanetElements("Jupiter", "♃", orange,        34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
            PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
            PlanetElements("Uranus",  "⛢", blueGreen,    313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
            PlanetElements("Neptune", "♆", neptuneBlue,  304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
        )

        val sunPoints = ArrayList<Offset>()
        val planetTransits = planets.associate { it.name to ArrayList<Offset>() }
        val planetRises = planets.associate { it.name to ArrayList<Offset>() }
        val planetSets = planets.associate { it.name to ArrayList<Offset>() }
        val bestLabels = HashMap<String, LabelPosition>()
        for (p in planets) {
            bestLabels["${p.name}_t"] = LabelPosition()
            bestLabels["${p.name}_r"] = LabelPosition()
            bestLabels["${p.name}_s"] = LabelPosition()
        }

        val darkBlueArgb = 0xFF0000D1.toInt()
        val blackArgb = android.graphics.Color.BLACK
        val twilightPaint = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
        val greyPaint = Paint().apply { color = android.graphics.Color.GRAY; strokeWidth = 1f; style = Paint.Style.FILL }

        // Prep calculation for current time line
        val localTime = nowZoned.toLocalTime()
        val currentHour = localTime.hour + (localTime.minute / 60.0) + (localTime.second / 3600.0)
        val xNow = centerX + (normalizeGraphTime(currentHour) * pixelsPerHour)

        // Determine if it is currently night
        val (todayRise, todaySet) = cache.getSunTimes(nowEpochDay, false)
        var isNightNow = false
        if (!todayRise.isNaN() && !todaySet.isNaN()) {
            isNightNow = !(currentHour > todayRise && currentHour < todaySet)
        } else {
            // Handle Polar Night/Midnight Sun check
            val sunDec = calculateSunDeclination(nowEpochDay)
            val altNoon = 90.0 - abs(lat - Math.toDegrees(sunDec))
            isNightNow = altNoon < -0.833
        }

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

                // Check for Polar Night (24-hour darkness)
                val sunDec = calculateSunDeclination(targetEpochDay)
                val altNoon = 90.0 - abs(lat - Math.toDegrees(sunDec))

                if (altNoon < -0.833) {
                    // Polar Night: Sun never rises
                    drawPlanets = true
                    effectiveXSunset = 0.0
                    effectiveXSunrise = w.toDouble()
                    // Draw full darkness background
                    twilightPaint.shader = null
                    twilightPaint.color = blackArgb
                    canvas.nativeCanvas.drawLine(0f, y.toFloat(), w, y.toFloat(), twilightPaint)
                } else if (validSunset && validSunrise) {
                    // Standard Day
                    drawPlanets = true

                    // Evening Twilight Gradient (Sunset -> AstroEnd)
                    val xAstroEnd = if (!astroSetPrev.isNaN()) centerX + (normalizeGraphTime(astroSetPrev) * pixelsPerHour) else null
                    if (xAstroEnd != null) {
                        twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), darkBlueArgb, blackArgb, Shader.TileMode.CLAMP)
                        canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), twilightPaint)
                    } else {
                        // Twilight doesn't end (Merged), draw full line Sunset -> Sunrise
                        twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), intArrayOf(darkBlueArgb, blackArgb, darkBlueArgb), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                        canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                    }

                    // Morning Twilight Gradient (AstroStart -> Sunrise)
                    // Only need to draw this if not merged (xAstroEnd != null)
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

                // Current Time Line (Grey Pixel)
                if (isLive && isNightNow && drawPlanets) {
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
                            // Use effective boundaries
                            if (xPos >= effectiveXSunset && xPos <= effectiveXSunrise) {
                                list.add(Offset(xPos.toFloat(), yPos))
                                val dist = abs(xPos - targetX)
                                val labelTracker = bestLabels["${planet.name}_$keySuffix"]!!
                                if (dist < labelTracker.minDistToCenter) {
                                    labelTracker.minDistToCenter = dist.toFloat()
                                    labelTracker.x = xPos.toFloat(); labelTracker.y = yPos; labelTracker.found = true
                                }
                            }
                        }
                        processEvent(events.transit, planetTransits[planet.name]!!, "t")
                        processEvent(events.rise, planetRises[planet.name]!!, "r")
                        processEvent(events.set, planetSets[planet.name]!!, "s")
                    }
                }
            }
        }

        drawPoints(points = sunPoints, pointMode = PointMode.Points, color = Color.White, strokeWidth = 2f)

        drawIntoCanvas { canvas ->
            val monthPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
            val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }
            val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
            val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
            var d = startDate
            while (d.isBefore(endDate)) {
                val nextMonth = d.plusMonths(1)
                val yStart = getYForDate(d)
                val x1_grid = getXSunsetForDate(d)
                val epochDayGrid = d.toEpochDay().toDouble()
                val (riseGrid, _) = cache.getSunTimes(epochDayGrid, false)
                if (yStart >= textHeight && yStart <= h && !riseGrid.isNaN()) {
                    val x2_grid = centerX + (normalizeGraphTime(riseGrid) * pixelsPerHour)
                    canvas.nativeCanvas.drawLine(x1_grid, yStart, x2_grid.toFloat(), yStart, gridPaint)
                }
                var visibleDays = 0
                var iterDate = d
                while (iterDate.isBefore(nextMonth)) { if (getYForDate(iterDate) in textHeight..h) visibleDays++; iterDate = iterDate.plusDays(1) }
                if (visibleDays >= 15) {
                    val monthName = d.format(DateTimeFormatter.ofPattern("MMM"))
                    val yEnd = getYForDate(nextMonth)
                    val yMid = (yStart + yEnd) / 2f
                    val midMonthDate = d.withDayOfMonth(15)
                    val xSunsetMid = getXSunsetForDate(midMonthDate)
                    val targetX = xSunsetMid - monthPaint.textSize
                    val finalX = max(25f, targetX)
                    val x1 = getXSunsetForDate(d); val x2 = getXSunsetForDate(nextMonth)
                    val angleDeg = Math.toDegrees(atan2((yEnd - yStart).toDouble(), (x2 - x1).toDouble())).toFloat()
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.rotate(angleDeg, finalX, yMid)
                    canvas.nativeCanvas.drawText(monthName, finalX, yMid, monthPaint)
                    canvas.nativeCanvas.restore()
                }
                d = nextMonth
            }
        }

        if (w > 1600f) {
            drawIntoCanvas { canvas ->
                val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }
                for (y in textHeight.toInt() until h.toInt()) {
                    val targetEpochDay = nowEpochDay + ((h - y) / h * 365.25)
                    val (_, setTimePrev) = cache.getSunTimes(targetEpochDay - 1.0, false)
                    val (riseTimeCurr, _) = cache.getSunTimes(targetEpochDay, false)
                    val xSunset = centerX + (normalizeGraphTime(setTimePrev) * pixelsPerHour)
                    val xSunrise = centerX + (normalizeGraphTime(riseTimeCurr) * pixelsPerHour)
                    if (!xSunset.isNaN() && !xSunrise.isNaN()) {
                        for (k in -14..14) {
                            val xGrid = centerX + (k * pixelsPerHour)
                            if (xGrid > xSunset && xGrid < xSunrise) canvas.nativeCanvas.drawPoint(xGrid.toFloat(), y.toFloat(), gridPaint)
                        }
                    }
                }
            }
        }

        for (planet in planets) {
            val isInner = planet.name == "Mercury" || planet.name == "Venus"
            val riseSetColor = if (isInner) planet.color else planet.color.copy(alpha = 0.35f)
            if (!isInner) drawPoints(points = planetTransits[planet.name]!!, pointMode = PointMode.Points, color = planet.color, strokeWidth = 4f)
            drawPoints(points = planetRises[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
            drawPoints(points = planetSets[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
        }

        val fullMoons = ArrayList<Offset>(); val newMoons = ArrayList<Offset>(); val firstQuarters = ArrayList<Offset>(); val lastQuarters = ArrayList<Offset>()
        var prevElong = calculateMoonPhaseAngle(nowEpochDay - 1.0)
        for (dIdx in 0..380) {
            val scanEpochDay = nowEpochDay + dIdx
            val currElong = calculateMoonPhaseAngle(scanEpochDay)
            fun norm(a: Double): Double { var v = a % 360.0; if (v > 180) v -= 360; if (v <= -180) v += 360; return v }
            fun checkCrossing(targetAngle: Double, list: ArrayList<Offset>) {
                val pNorm = norm(prevElong - targetAngle); val cNorm = norm(currElong - targetAngle)
                if (pNorm < 0 && cNorm >= 0) {
                    val exactMoonDay = (scanEpochDay - 1.0) + (-pNorm / (cNorm - pNorm))
                    val (riseTime, _) = cache.getSunTimes(scanEpochDay - 1.0, false)
                    if (!riseTime.isNaN()) {
                        val xMoon = centerX + (normalizeGraphTime(riseTime) * pixelsPerHour) + 15f
                        val yMoon = getYForEpochDay(exactMoonDay)
                        if (yMoon >= textHeight && yMoon <= h) list.add(Offset(xMoon.toFloat(), yMoon))
                    }
                }
            }
            checkCrossing(0.0, newMoons); checkCrossing(90.0, firstQuarters); checkCrossing(180.0, fullMoons); checkCrossing(270.0, lastQuarters)
            prevElong = currElong
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
                if (p.name !in listOf("Mercury", "Venus")) bestLabels["${p.name}_t"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "t", p.color.toArgb()) }
                bestLabels["${p.name}_r"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "r", p.color.toArgb()) }
                bestLabels["${p.name}_s"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "s", p.color.toArgb()) }
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