package com.kenyoung.orrery

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

private const val DARK_MONTH_LABEL_TEXT_SIZE = 30f
private const val DARK_BAND_COLOR = 0xFF00FF00.toInt()
private const val DARK_TOP_MARGIN = 40f // reserved at the top for the hour-axis labels
private const val DRAG_TOP_GAIN = 4f      // gain approached for fast moves (1f = 1:1 finger tracking)
private const val DRAG_SLOW_REF_PX = 40f  // sets the low-speed rate; smaller -> finer slow control. Independent of DRAG_TOP_GAIN.
private val DARK_MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM")
private val DARK_READOUT_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

// Signed hours from solar midnight (mirrors GraphicsWindow.normalizeGraphTime). The
// dial maps x = centerX + offset * pixelsPerHour; a normal night runs monotonically
// from negative (evening) through 0 (midnight) to positive (morning).
private fun darkGraphOffset(solarHour: Double): Double {
    var diff = solarHour - 24.0
    if (diff < -12.0) diff += 24.0 else if (diff > 12.0) diff -= 24.0
    return diff
}

// The single mapping between canvas-height fraction (0 = top, 1 = bottom) and the night
// shown there: frac=1 (bottom) is "now", frac=0 (top) is one year out, and the night drawn
// at a row is the evening of (targetEpochDay - 1). Keeping both directions here is the one
// source of truth for the 365.25 span and the -1 night offset.
private fun fracToNightDay(frac: Float, nowEpochDay: Double): Long =
    (nowEpochDay + (1.0 - frac) * 365.25 - 1.0).roundToLong()
private fun nightDayToFrac(nightDay: Long, nowEpochDay: Double): Float =
    (1.0 - (nightDay + 1 - nowEpochDay) / 365.25).toFloat().coerceIn(0f, 1f)

// One night's "very dark" data. `intervals` are the green-band sub-intervals, each as
// (startOffset, endOffset) in the signed-hours-from-midnight frame the screen draws in
// (empty = no dark time: polar day, or bright Moon up all night). The remaining fields
// summarize the overall dark span for the draggable red-line readout.
data class DarkNight(
    val intervals: List<Pair<Double, Double>>,
    val totalHours: Double,
    val startUt: Double, // UT epoch-day of first dark minute (NaN if none)
    val endUt: Double    // UT epoch-day of last dark minute (NaN if none)
)

private val EMPTY_DARK_NIGHT = DarkNight(emptyList(), 0.0, Double.NaN, Double.NaN)

// Per-night dark data indexed by integer epoch day. Built off-thread; never touched on the draw path.
class DarkIntervalCache(
    val startEpochDay: Long,
    val nights: Array<DarkNight>
) {
    fun getNight(epochDay: Long): DarkNight {
        val idx = (epochDay - startEpochDay).toInt()
        return if (idx in nights.indices) nights[idx] else EMPTY_DARK_NIGHT
    }
}

// Scan one night (noon-to-noon, the same window the Meteor Showers page uses) for the
// intervals when isDark() holds — Sun below nautical twilight AND Moon below its
// illumination-dependent altitude. Coarse 5-min scan locates transitions; each edge is
// bisection-refined to ~second precision. Edges are stored as signed-hours offsets so the
// green band lines up with the twilight background drawn from cache.getSunTimes.
private fun computeDarkNight(epochDay: Long, lat: Double, lon: Double): DarkNight {
    val offsetHours = lon / 15.0
    val startSearch = epochDay + 0.5
    val endSearch = epochDay + 1.5
    val coarse = 5.0 / 1440.0 // 5 minutes in days

    fun gAt(t: Double) = darkGraphOffset(jdFracToLocalHours(t, offsetHours))

    // Bisect the dark/not-dark transition bracketed by [tLo, tHi] to a precise crossing time.
    fun refineEdge(tLo: Double, tHi: Double): Double {
        var lo = tLo; var hi = tHi
        val loDark = isDark(lo, lat, lon)
        repeat(12) {
            val mid = (lo + hi) / 2.0
            if (isDark(mid, lat, lon) == loDark) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    val intervals = ArrayList<Pair<Double, Double>>()
    var firstDarkUt = Double.NaN
    var lastDarkUt = Double.NaN

    var prevT = startSearch
    var prevDark = isDark(prevT, lat, lon)
    var segStartG: Double? = if (prevDark) gAt(startSearch) else null
    var segStartUt = if (prevDark) startSearch else Double.NaN

    fun closeSegment(gEnd: Double, utEnd: Double) {
        val g0 = segStartG ?: return
        intervals.add(g0 to gEnd)
        if (firstDarkUt.isNaN()) firstDarkUt = segStartUt
        lastDarkUt = utEnd
        segStartG = null
    }

    val steps = ceil((endSearch - startSearch) / coarse).toInt()
    for (i in 1..steps) {
        val t = min(startSearch + i * coarse, endSearch)
        val dark = isDark(t, lat, lon)
        if (dark != prevDark) {
            val edgeUt = refineEdge(prevT, t)
            val g = gAt(edgeUt)
            if (dark) {
                segStartG = g; segStartUt = edgeUt
            } else {
                closeSegment(g, edgeUt)
            }
            prevDark = dark
        }
        prevT = t
    }
    closeSegment(gAt(endSearch), endSearch)

    // Each interval is monotonic in the signed-hours frame, so its length in hours is just gEnd - gStart.
    val totalHours = intervals.sumOf { it.second - it.first }
    return DarkNight(intervals, totalHours, firstDarkUt, lastDarkUt)
}

// Build the year's worth of nightly dark intervals. Mirrors calculateCache's date range
// so it shares the same observing-date coverage as the Planet Transits chart.
fun calculateDarkCache(nowDate: LocalDate, lat: Double, lon: Double): DarkIntervalCache {
    val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
    val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
    val startEpochDay = startDate.toEpochDay()
    val daysCount = (endDate.toEpochDay() - startEpochDay).toInt() + 2
    val nights = Array(daysCount) { i -> computeDarkNight(startEpochDay + i, lat, lon) }
    return DarkIntervalCache(startEpochDay, nights)
}

@Composable
fun DarkTimeScreen(obs: ObserverState, cache: AstroCache, darkCache: DarkIntervalCache) {
    val lat = obs.lat; val lon = obs.lon; val now = obs.now
    val stdOffsetHours = obs.stdOffsetHours
    val stdTimeLabel = obs.stdTimeLabel
    val offsetHours = lon / 15.0

    // Observing date from the local solar time: before solar noon we're still in the
    // previous night, so use yesterday. Computed once and shared by both canvases.
    val solarOffset = ZoneOffset.ofTotalSeconds((offsetHours * 3600.0).roundToInt())
    val localDateTime = now.atOffset(solarOffset).toLocalDateTime()
    val observingDate = if (localDateTime.hour < 12) localDateTime.toLocalDate().minusDays(1) else localDateTime.toLocalDate()
    val nowEpochDay = observingDate.toEpochDay().toDouble()

    // Vertical position of the draggable red readout line, as a fraction of canvas height (0 = top).
    // Initially placed on the next upcoming night that has a nonzero amount of dark time.
    val initialLineFrac = remember(darkCache, observingDate) {
        val nowDay = observingDate.toEpochDay()
        var nd = nowDay
        val limit = nowDay + 365L
        while (nd < limit && darkCache.getNight(nd).totalHours <= 0.0) nd++
        nightDayToFrac(nd, nowEpochDay)
    }
    var lineFrac by remember { mutableStateOf(initialLineFrac) }

    Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
        detectVerticalDragGestures { change, dragAmount ->
            change.consume()
            // Speed-proportional gain: a fast finger (large per-event travel) moves the line up
            // to DRAG_TOP_GAIN times finger speed, while small/slow moves ease it onto an exact
            // date. The low-speed rate depends only on DRAG_SLOW_REF_PX, not on DRAG_TOP_GAIN.
            val travel = abs(dragAmount)
            val gain = DRAG_TOP_GAIN * travel / (travel + DRAG_TOP_GAIN * DRAG_SLOW_REF_PX)
            lineFrac = (lineFrac + (dragAmount / size.height.toFloat()) * gain).coerceIn(0f, 1f)
        }
    }) {
        // Base chart: independent of the red line, so dragging never re-runs this heavy draw.
        Canvas(modifier = Modifier.fillMaxSize()) {
            withDensityScaling { w, h ->
            val paddingLeft = 35f
            val paddingRight = 42f
            val moonStripMargin = 50f
            val drawingWidth = w - paddingLeft - paddingRight

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

            // Leave room on the right for the moon-phase glyphs (as on the Transits page).
            val pixelsPerHour = (drawingWidth - moonStripMargin) / nMaxDuration

            // With standard timezone offsets the night may be asymmetric around
            // midnight (solar noon != 12:00 standard time), so position centerX
            // based on the solstice sunset extent to keep the full night in view.
            val centerX = if (solsticeSet.isNaN() || solsticeRise.isNaN()) {
                paddingLeft + (drawingWidth / 2f)
            } else {
                (paddingLeft - darkGraphOffset(solsticeSet) * pixelsPerHour).toFloat()
            }
            val textY = 30f

            // Helper for Date lines
            fun getXSunsetForDate(date: LocalDate): Float {
                val epochDay = date.toEpochDay().toDouble()
                val (_, setTimePrev) = cache.getSunTimes(epochDay - 1.0, false)
                return (centerX + (darkGraphOffset(setTimePrev) * pixelsPerHour)).toFloat()
            }

            val sunPoints = ArrayList<Offset>()

            val darkBlueArgb = 0xFF0000D1.toInt()
            val blackArgb = android.graphics.Color.BLACK
            val twilightPaint = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
            val greyPaint = Paint().apply { color = android.graphics.Color.GRAY; strokeWidth = 1f; style = Paint.Style.FILL }
            val darkBandPaint = Paint().apply { color = DARK_BAND_COLOR; strokeWidth = 1f; style = Paint.Style.STROKE }

            val localTime = localDateTime.toLocalTime()
            val currentHour = localTime.hour + (localTime.minute / 60.0) + (localTime.second / 3600.0)
            val xNow = centerX + (darkGraphOffset(currentHour) * pixelsPerHour)

            // --- DRAWING LOOP ---
            drawIntoCanvas { canvas ->
                for (y in DARK_TOP_MARGIN.toInt() until h.toInt()) {
                    val fraction = (h - y) / h
                    val daysOffset = fraction * 365.25

                    val targetEpochDay = nowEpochDay + daysOffset

                    val (_, setTimePrev) = cache.getSunTimes(targetEpochDay - 1.0, false)
                    val (riseTimeCurr, _) = cache.getSunTimes(targetEpochDay, false)
                    val (_, astroSetPrev) = cache.getSunTimes(targetEpochDay - 1.0, true)
                    val (astroRiseCurr, _) = cache.getSunTimes(targetEpochDay, true)

                    val xSunset = centerX + (darkGraphOffset(setTimePrev) * pixelsPerHour)
                    val xSunrise = centerX + (darkGraphOffset(riseTimeCurr) * pixelsPerHour)
                    val validSunset = !xSunset.isNaN()
                    val validSunrise = !xSunrise.isNaN()

                    var hasNight = false
                    var effectiveXSunset = xSunset
                    var effectiveXSunrise = xSunrise

                    val sunDec = calculateSunDeclination(targetEpochDay)
                    val altNoon = 90.0 - abs(lat - Math.toDegrees(sunDec))

                    if (altNoon < HORIZON_REFRACTED) {
                        hasNight = true
                        effectiveXSunset = 0.0
                        effectiveXSunrise = w.toDouble()
                        twilightPaint.shader = null
                        twilightPaint.color = blackArgb
                        canvas.nativeCanvas.drawLine(0f, y.toFloat(), w, y.toFloat(), twilightPaint)
                    } else if (validSunset && validSunrise) {
                        hasNight = true
                        val xAstroEnd = if (!astroSetPrev.isNaN()) centerX + (darkGraphOffset(astroSetPrev) * pixelsPerHour) else null
                        if (xAstroEnd != null) {
                            twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), darkBlueArgb, blackArgb, Shader.TileMode.CLAMP)
                            canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), twilightPaint)
                        } else {
                            twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), intArrayOf(darkBlueArgb, blackArgb, darkBlueArgb), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                            canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                        }

                        if (xAstroEnd != null) {
                            val xAstroStart = if (!astroRiseCurr.isNaN()) centerX + (darkGraphOffset(astroRiseCurr) * pixelsPerHour) else null
                            if (xAstroStart != null) {
                                twilightPaint.shader = LinearGradient(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), blackArgb, darkBlueArgb, Shader.TileMode.CLAMP)
                                canvas.nativeCanvas.drawLine(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                            }
                        }
                    }

                    if (validSunset) sunPoints.add(Offset(xSunset.toFloat(), y.toFloat()))
                    if (validSunrise) sunPoints.add(Offset(xSunrise.toFloat(), y.toFloat()))

                    // Green "very dark" band: one stroke per dark sub-interval for this night.
                    val nightDay = fracToNightDay(y / h, nowEpochDay)
                    for ((gStart, gEnd) in darkCache.getNight(nightDay).intervals) {
                        val xs = (centerX + gStart * pixelsPerHour).toFloat()
                        val xe = (centerX + gEnd * pixelsPerHour).toFloat()
                        if (!xs.isNaN() && !xe.isNaN()) {
                            canvas.nativeCanvas.drawLine(min(xs, xe), y.toFloat(), max(xs, xe), y.toFloat(), darkBandPaint)
                        }
                    }

                    // Draw gray pixel at the current time if it is night time on this specific day
                    if (hasNight) {
                        if (xNow >= effectiveXSunset && xNow <= effectiveXSunrise) {
                            canvas.nativeCanvas.drawPoint(xNow.toFloat(), y.toFloat(), greyPaint)
                        }
                    }
                }
            }

            drawPoints(points = sunPoints, pointMode = PointMode.Points, color = Color.White, strokeWidth = 2f)

            // Grid Lines & Month Labels
            drawIntoCanvas { canvas ->
                val monthPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = DARK_MONTH_LABEL_TEXT_SIZE; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
                val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }

                // Helper to get Y for non-scrolling (fixed window)
                fun getY(tEpoch: Double): Float {
                    val diff = tEpoch - nowEpochDay
                    return (h - ((diff / 365.25) * h)).toFloat()
                }

                var d = observingDate.minusMonths(1).withDayOfMonth(1)
                val endDate = observingDate.plusYears(1).plusMonths(1)

                while (d.isBefore(endDate)) {
                    val nextMonth = d.plusMonths(1)
                    val yStart = getY(d.toEpochDay().toDouble())
                    val x1_grid = getXSunsetForDate(d)

                    val epochDayGrid = d.toEpochDay().toDouble()
                    val (riseGrid, _) = cache.getSunTimes(epochDayGrid, false)

                    if (yStart >= DARK_TOP_MARGIN && yStart <= h && !riseGrid.isNaN()) {
                        val x2_grid = centerX + (darkGraphOffset(riseGrid) * pixelsPerHour)
                        canvas.nativeCanvas.drawLine(x1_grid, yStart, x2_grid.toFloat(), yStart, gridPaint)
                    }

                    val midMonth = d.plusDays(15)
                    val yMid = getY(midMonth.toEpochDay().toDouble())

                    if (yMid in DARK_TOP_MARGIN..h) {
                        val monthName = d.format(DARK_MONTH_FORMAT)
                        val xSunsetMid = getXSunsetForDate(midMonth)
                        val targetX = xSunsetMid - monthPaint.textSize
                        val finalX = max(25f, targetX)

                        val yEnd = getY(nextMonth.toEpochDay().toDouble())
                        val x2 = getXSunsetForDate(nextMonth)
                        val angleDeg = Math.toDegrees(atan2((yEnd - yStart).toDouble(), (x2 - x1_grid).toDouble())).toFloat()

                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.rotate(angleDeg, finalX, yMid)
                        canvas.nativeCanvas.drawText(monthName, finalX, yMid, monthPaint)
                        canvas.nativeCanvas.restore()
                    }
                    d = nextMonth
                }
            }

            // Top hour axis (standard-time hour numbers)
            drawIntoCanvas { canvas ->
                val timePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = DARK_MONTH_LABEL_TEXT_SIZE; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
                for (utHour in 0..23) {
                    val localSolar = normalizeTime(utHour.toDouble() + offsetHours)
                    val k = darkGraphOffset(localSolar)
                    val xPos = centerX + (k * pixelsPerHour)
                    if (xPos >= paddingLeft && xPos <= (w - paddingRight)) {
                        val stdHour = ((utHour + round(stdOffsetHours).toInt()) % 24 + 24) % 24
                        canvas.nativeCanvas.drawText(stdHour.toString(), xPos.toFloat(), textY, timePaint)
                    }
                }
            }

            // Moon phases, drawn just right of the sunrise line (as on the Transits page)
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
                            val xMoon = centerX + (darkGraphOffset(riseTime) * pixelsPerHour) + 15f
                            val diff = exactMoonDay - nowEpochDay
                            val yMoon = (h - ((diff / 365.25) * h)).toFloat()
                            if (yMoon >= DARK_TOP_MARGIN && yMoon <= h) list.add(Offset(xMoon.toFloat(), yMoon))
                        }
                    }
                }
                checkCrossing(0.0, newMoons); checkCrossing(90.0, firstQuarters); checkCrossing(180.0, fullMoons); checkCrossing(270.0, lastQuarters)
                prevElong = currElong
                scanD += 1.0
            }

            drawIntoCanvas { canvas ->
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

        // Lightweight overlay: only the draggable red line + readout reads lineFrac, so a drag
        // repaints just this canvas, not the chart above.
        Canvas(modifier = Modifier.fillMaxSize()) {
            withDensityScaling { w, h ->
                val refY = (lineFrac * h).coerceIn(DARK_TOP_MARGIN, h)
                drawIntoCanvas { canvas ->
                    val redArgb = 0xFFFF0000.toInt()
                    val linePaint = Paint().apply { color = redArgb; strokeWidth = 2f; style = Paint.Style.STROKE }
                    canvas.nativeCanvas.drawLine(0f, refY, w, refY, linePaint)

                    val nightDay = fracToNightDay(refY / h, nowEpochDay)
                    val night = darkCache.getNight(nightDay)

                    fun fmtStd(ut: Double): String {
                        var totalMin = round(((ut - floor(ut)) * 24.0 + stdOffsetHours) * 60.0).toInt()
                        totalMin = ((totalMin % 1440) + 1440) % 1440
                        return "%02d:%02d".format(totalMin / 60, totalMin % 60)
                    }

                    val dateStr = LocalDate.ofEpochDay(nightDay).format(DARK_READOUT_DATE_FORMAT)
                    val lines = if (night.totalHours <= 0.0 || night.startUt.isNaN()) {
                        listOf(dateStr, "No darkness")
                    } else {
                        val mins = round(night.totalHours * 60.0).toInt()
                        listOf(
                            dateStr,
                            "Darkness: ${mins / 60}h ${mins % 60}m",
                            "${fmtStd(night.startUt)} – ${fmtStd(night.endUt)} $stdTimeLabel"
                        )
                    }

                    val textPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 75f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
                    val outlinePaint = Paint(textPaint).apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 12.5f }
                    val lineGap = textPaint.textSize * 1.25f
                    val cx = w / 2f

                    // Text below the line when the line is in the upper half, above it otherwise.
                    val firstBaseline = if (refY < h / 2f) {
                        refY + textPaint.textSize + 8f
                    } else {
                        (refY - 8f) - (lines.size - 1) * lineGap
                    }
                    for (i in lines.indices) {
                        val by = firstBaseline + i * lineGap
                        canvas.nativeCanvas.drawText(lines[i], cx, by, outlinePaint)
                        canvas.nativeCanvas.drawText(lines[i], cx, by, textPaint)
                    }
                }
            }
        }
    }
}
