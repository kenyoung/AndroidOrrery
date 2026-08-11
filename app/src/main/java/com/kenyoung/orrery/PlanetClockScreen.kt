package com.kenyoung.orrery

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

private const val CLOCK_STEP_MINUTES = 15
private const val CLOCK_STEPS = 24 * 60 / CLOCK_STEP_MINUTES
private const val CLOCK_STEP_DAYS = CLOCK_STEP_MINUTES / 1440.0

// Sky-state palette (draft-image style, deliberately different from the
// Sunlight Today dial palette).
private val clockDaylightColor = Color(0xFFADD8E6)
private val clockGoldenColor = Color(0xFFFFA040)
private val clockCivilColor = Color(0xFF4A6FB5)
private val clockNauticalColor = Color(0xFF25386B)
private val clockAstroColor = Color(0xFF14204A)
// Moonlit night (Sun below -18° but the Moon interferes): dim, but clearly
// brighter than the exact-black very-dark wedges and sub-horizon disk.
private val clockNightColor = Color(0xFF0F0F24)
private val gridGreyColor = Color(0xFF888888)
private val rimGreyColor = Color(0xFFBBBBBB)
private val axisGreyColor = Color(0xFFAAAAAA)

private val clockDateFormatter = DateTimeFormatter.ofPattern("MMM d")

// The two bodies drawn with dotted curves; they also start deselected.
private val dottedBodies = setOf("Sun", "Moon")

// Sun and Moon curves are dotted to set them apart from the planets.
private val dottedLineEffect = DashPathEffect(floatArrayOf(4f, 10f), 0f)

// Altitude of every body at each sample step; the sample's time is implicit
// (windowStartUt + index * step), so the cache stays valid when the
// Standard/UT display toggle changes.
private class ClockCurve(val name: String, val colorInt: Int, val dotted: Boolean, val samples: List<Double>)
private class ClockData(
    val curves: List<ClockCurve>, val twilight: TwilightTimes, val noonAlt: Double,
    // Very-dark spans (darkGraphOffset frame: signed hours from local solar midnight)
    val darkIntervals: List<Pair<Double, Double>>
)

// Current azimuth/elevation/magnitude for the table below the dial.
private class ClockRow(val name: String, val colorInt: Int, val az: Double, val alt: Double, val mag: Double)

// Apparent az/alt of a body at jd; the Moon gets the parallax correction,
// applied after precession (the order matters).
private fun apparentAzAlt(name: String, state: BodyState, jd: Double, lst: Double, lat: Double, lon: Double): AzAlt {
    val apparent = j2000ToApparent(state.ra, state.dec, jd)
    val (raH, dec) = if (name == "Moon") {
        val topo = toTopocentric(apparent.ra, apparent.dec, state.distGeo, lat, lon, lst)
        Pair(topo.ra / 15.0, topo.dec)
    } else {
        Pair(apparent.ra / 15.0, apparent.dec)
    }
    return calculateAzAlt(lst, lat, raH, dec)
}

private fun format12h(hr: Double): String {
    val totalMin = ((round(hr * 60.0).toInt() % 1440) + 1440) % 1440
    val h = totalMin / 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val suffix = if (h < 12) "am" else "pm"
    return "%d:%02d%s".format(h12, totalMin % 60, suffix)
}

// A 24-hour polar clock: angle is time of day (midnight at top, clockwise,
// noon at bottom), radius is elevation (center = -90°, rim = +90°, horizon
// halfway out). The window is the current night, local solar noon to the
// next noon, so the data seam hides at the bottom of the dial.
@Composable
fun PlanetClockScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
    val lat = obs.lat
    val lon = obs.lon
    val offset = lon / 15.0
    val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
    val currentUtEpochDay = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY
    // Local solar date whose noon starts the shown window; rolls over at solar noon.
    val nightAnchor = floor(currentUtEpochDay + offset / 24.0 - 0.5)
    val windowStartUt = nightAnchor + 0.5 - offset / 24.0

    val bodyList = remember {
        listOf(ChipBody("Sun", "☉", Color.Yellow), ChipBody("Moon", "☾", Color.White)) +
            getOrreryPlanets().filter { it.name != "Earth" }.map { ChipBody(it.name, it.symbol, it.color) }
    }
    // Selection map (present-and-true = selected). Initially all planets are
    // selected, the Sun and Moon are not.
    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            bodyList.forEach { if (it.name !in dottedBodies) this[it.name] = true }
        }
    }

    val data = remember(nightAnchor, lat, lon) {
        val twilight = computeTwilightTimes(nightAnchor, lat, lon, offset)
        val noonAlt = 90.0 - abs(lat - calculateSunTransit(nightAnchor, lon, offset).dec)
        // Same night window (local solar noon to noon) as the dial shows, and the
        // same very-dark definition as the Sunlight Today and Dark Time pages.
        val darkNight = computeDarkNight(nightAnchor.toLong(), lat, lon)
        val curves = bodyList.map { body ->
            val samples = (0..CLOCK_STEPS).map { i ->
                val sampleJd = windowStartUt + i * CLOCK_STEP_DAYS + UNIX_EPOCH_JD
                val sampleLst = calculateLSTHours(sampleJd, lon)
                val state = AstroEngine.getBodyState(body.name, sampleJd)
                applyRefraction(apparentAzAlt(body.name, state, sampleJd, sampleLst, lat, lon).alt)
            }
            ClockCurve(body.name, body.color.toArgb(), body.name in dottedBodies, samples)
        }
        ClockData(curves, twilight, noonAlt, darkNight.intervals)
    }

    // Current positions and magnitudes for all bodies, refreshed by the
    // once-per-minute obs.now tick. Unselected bodies are filtered out at
    // draw time, so chip taps recompute nothing here.
    val currentJd = currentUtEpochDay + UNIX_EPOCH_JD
    val rows = remember(currentJd, lat, lon) {
        val lstNow = calculateLSTHours(currentJd, lon)
        val sunState = AstroEngine.getBodyState("Sun", currentJd)
        val earthSunDist = sunState.distGeo
        val sunMagnitude = -26.7
        bodyList.map { body ->
            val state = if (body.name == "Sun") sunState else AstroEngine.getBodyState(body.name, currentJd)
            val azAlt = apparentAzAlt(body.name, state, currentJd, lstNow, lat, lon)
            val mag = if (body.name == "Sun") sunMagnitude else {
                val alphaDeg = Math.toDegrees(phaseAngleRad(state.distSun, state.distGeo, earthSunDist))
                val ringB = if (body.name == "Saturn")
                    SaturnMoonEngine.calculateRingTiltB(state.eclipticLon, state.eclipticLat, currentJd)
                else 0.0
                apparentMagnitude(body.name, state.distSun, state.distGeo, alphaDeg, ringB)
            }
            ClockRow(body.name, body.color.toArgb(), azAlt.az, applyRefraction(azAlt.alt), mag)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            withDensityScaling { w, h ->

            val shownRows = rows.filter { selected[it.name] == true }

            val tickLen = 12f
            val numeralBand = 44f   // rotated hour numerals outside the ticks
            val captionBand = 54f   // Midnight / Noon captions
            val captionTextSize = 44f
            val outerBand = tickLen + numeralBand
            val tableRowH = 34f
            val tableTextSize = 30f
            val tableHeight = (shownRows.size + 1) * tableRowH + 12f
            // Dial as high as it fits under the Midnight caption; the table
            // gets the remaining space below the Noon caption.
            val radius = min(w / 2f - outerBand - 8f,
                (h - captionBand - 2f * outerBand - captionTextSize - 14f - tableHeight) / 2f)
            if (radius < 60f) return@withDensityScaling
            val cx = w / 2f
            val cy = captionBand + outerBand + radius
            val center = Offset(cx, cy)
            val dialTopLeft = Offset(cx - radius, cy - radius)
            val dialSize = Size(2f * radius, 2f * radius)

            fun altToR(alt: Double) = ((alt + 90.0) / 180.0 * radius).toFloat()
            fun drawWedge(startSolar: Double, endSolar: Double, color: Color) =
                drawHourWedge(startSolar, endSolar, color, offset, displayOffsetHours, dialTopLeft, dialSize)

            // Sky-state wedges, nested: the base disk is the state at solar noon
            // (also the polar day/night fallback); each wedge k covers the span
            // with the Sun below altitude crossing k and paints over the previous.
            // skyColors[k + 1] is the state below TWILIGHT_ALTITUDES[k].
            val skyColors = listOf(
                clockDaylightColor, clockGoldenColor, clockCivilColor,
                clockNauticalColor, clockAstroColor, clockNightColor
            )
            val noonIdx = TWILIGHT_ALTITUDES.indexOfFirst { data.noonAlt >= it }
            drawCircle(if (noonIdx < 0) skyColors.last() else skyColors[noonIdx], radius, center)
            val belowColors = skyColors.drop(1)
            for (k in belowColors.indices) {
                drawWedge(data.twilight.dusk[k], data.twilight.dawn[k], belowColors[k])
            }
            // Very dark (no Sun or Moon interference): exact black over the night band.
            // The g-frame values are hours relative to local solar midnight, so they
            // map like any other local solar hour; the frame is monotonic, so the
            // sweep is a plain difference.
            for ((gStart, gEnd) in data.darkIntervals) {
                drawArc(Color.Black, hourToArcDeg(solarToDisplayHour(gStart, offset, displayOffsetHours)),
                    ((gEnd - gStart) / 24.0 * 360.0).toFloat(),
                    useCenter = true, topLeft = dialTopLeft, size = dialSize)
            }

            // Below-horizon disk
            drawCircle(Color.Black, radius / 2f, center)

            // Elevation grid: horizon plus 22.5° steps above it, brighter rim.
            for (alt in listOf(0.0, 22.5, 45.0, 67.5)) {
                drawCircle(gridGreyColor, altToR(alt), center, style = Stroke(1.5f))
            }
            drawCircle(rimGreyColor, radius, center, style = Stroke(2f))
            // Midnight-noon axis
            drawLine(axisGreyColor, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeWidth = 1.5f)

            // Sample angles are the same for every curve; precompute the unit vectors.
            val cosA = FloatArray(CLOCK_STEPS + 1)
            val sinA = FloatArray(CLOCK_STEPS + 1)
            for (i in 0..CLOCK_STEPS) {
                val displayHr = jdFracToLocalHours(windowStartUt + i * CLOCK_STEP_DAYS, displayOffsetHours)
                val rad = Math.toRadians(hourToArcDeg(displayHr).toDouble())
                cosA[i] = cos(rad).toFloat()
                sinA[i] = sin(rad).toFloat()
            }

            val curvePaint = Paint().apply {
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            // Each curve is one Path so the dotted pattern flows continuously
            // across the 15-minute segments instead of restarting at each one.
            val curvePath = Path()
            drawIntoCanvas { nc ->
                // Reversed so the Moon and Sun draw last, on top of the planets.
                for (curve in data.curves.asReversed()) {
                    if (selected[curve.name] != true) continue
                    curvePaint.color = curve.colorInt
                    curvePaint.pathEffect = if (curve.dotted) dottedLineEffect else null
                    curvePath.rewind()
                    for (i in 0..CLOCK_STEPS) {
                        val r = altToR(curve.samples[i])
                        val x = cx + r * cosA[i]
                        val y = cy + r * sinA[i]
                        if (i == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
                    }
                    nc.nativeCanvas.drawPath(curvePath, curvePaint)
                }
            }

            // Current-time radial line with a label along it near the rim
            val currentDisplayHour = jdFracToLocalHours(currentUtEpochDay, displayOffsetHours)
            val arcDeg = hourToArcDeg(currentDisplayHour)
            val arcRad = Math.toRadians(arcDeg.toDouble())
            val rimPoint = Offset(cx + radius * cos(arcRad).toFloat(), cy + radius * sin(arcRad).toFloat())
            drawLine(Color.Red, center, rimPoint, strokeWidth = 3f)
            drawIntoCanvas { nc ->
                val timePaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 30f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                }
                val ax = cx + (radius - 12f) * cos(arcRad).toFloat()
                val ay = cy + (radius - 12f) * sin(arcRad).toFloat()
                nc.nativeCanvas.save()
                // Rotate the text to lie along the line, flipped on the left
                // half of the dial so it always reads outward, never upside down.
                if (cos(arcRad) >= 0.0) {
                    nc.nativeCanvas.rotate(arcDeg, ax, ay)
                    timePaint.textAlign = Paint.Align.RIGHT
                } else {
                    nc.nativeCanvas.rotate(arcDeg + 180f, ax, ay)
                    timePaint.textAlign = Paint.Align.LEFT
                }
                nc.nativeCanvas.drawText(format12h(currentDisplayHour), ax, ay - 8f, timePaint)
                nc.nativeCanvas.restore()
            }

            drawIntoCanvas { nc ->
                val tickPaint = Paint().apply {
                    color = rimGreyColor.toArgb()
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                val numeralPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 27f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                for (hour in 0 until 24) {
                    val arc = hourToArcDeg(hour.toDouble())
                    val rad = Math.toRadians(arc.toDouble())
                    val cosH = cos(rad).toFloat()
                    val sinH = sin(rad).toFloat()
                    nc.nativeCanvas.drawLine(
                        cx + radius * cosH, cy + radius * sinH,
                        cx + (radius + tickLen) * cosH, cy + (radius + tickLen) * sinH, tickPaint
                    )
                    if (hour % 12 == 0) continue
                    val nx = cx + (radius + tickLen + 20f) * cosH
                    val ny = cy + (radius + tickLen + 20f) * sinH
                    // Tangential numerals, flipped on the lower half so they
                    // are never upside down.
                    val rawRot = arc + 90f
                    val rot = if (rawRot > 90f && rawRot < 270f) rawRot - 180f else rawRot
                    nc.nativeCanvas.save()
                    nc.nativeCanvas.rotate(rot, nx, ny)
                    nc.nativeCanvas.drawText("${hour % 12}", nx, ny + numeralPaint.textSize / 3f, numeralPaint)
                    nc.nativeCanvas.restore()
                }

                val captionPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = captionTextSize
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val clockPrefix = clockCaptionPrefix(obs.useStandardTime)
                nc.nativeCanvas.drawText("$clockPrefix Midnight", cx, cy - radius - outerBand - 10f, captionPaint)
                nc.nativeCanvas.drawText("$clockPrefix Noon", cx, cy + radius + outerBand + captionPaint.textSize, captionPaint)

                val datePaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 33f
                    isAntiAlias = true
                }
                val midnightDate = LocalDate.ofEpochDay(nightAnchor.toLong() + 1)
                nc.nativeCanvas.drawText(clockDateFormatter.format(midnightDate), 16f, 44f, datePaint)

                // Az/El/Mag table below the dial, one row per object in its usual color
                val tableTop = cy + radius + outerBand + captionTextSize + 14f
                val headerPaint = Paint().apply {
                    color = LabelColor.toArgb()
                    textSize = tableTextSize
                    typeface = Typeface.MONOSPACE
                    isAntiAlias = true
                }
                val rowPaint = Paint().apply {
                    textSize = tableTextSize
                    typeface = Typeface.MONOSPACE
                    isAntiAlias = true
                }
                val nameX = cx - radius
                val azX = cx + radius * 0.3f
                val elX = cx + radius * 0.65f
                val magX = cx + radius
                fun drawTableRow(paint: Paint, y: Float, name: String, az: String, el: String, mag: String) {
                    paint.textAlign = Paint.Align.LEFT
                    nc.nativeCanvas.drawText(name, nameX, y, paint)
                    paint.textAlign = Paint.Align.RIGHT
                    nc.nativeCanvas.drawText(az, azX, y, paint)
                    nc.nativeCanvas.drawText(el, elX, y, paint)
                    nc.nativeCanvas.drawText(mag, magX, y, paint)
                }
                drawTableRow(headerPaint, tableTop + tableRowH, "Object", "Az", "El", "Mag")
                for ((i, row) in shownRows.withIndex()) {
                    rowPaint.color = row.colorInt
                    val label = if (row.name in dottedBodies) "${row.name} (dotted)" else row.name
                    drawTableRow(rowPaint, tableTop + (i + 2) * tableRowH, label,
                        "%d°".format(round(row.az).toInt()),
                        "%d°".format(round(row.alt).toInt()),
                        "%.1f".format(row.mag))
                }
            }

            }
        }
        BodySelectionChips(bodyList, selected)
        TimeDisplayToggle(obs.useStandardTime, obs.useDst, onTimeDisplayChange)
    }
}
