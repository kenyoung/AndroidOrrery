package com.kenyoung.orrery

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.*

private const val TRAIL_HALF_HOURS = 12.0
private const val TRAIL_STEP_MINUTES = 15
private const val TRAIL_STEPS = (TRAIL_HALF_HOURS * 2 * 60 / TRAIL_STEP_MINUTES).toInt()
private const val TRAIL_HALF_DAYS = TRAIL_HALF_HOURS / 24.0
private const val TRAIL_STEP_DAYS = TRAIL_STEP_MINUTES / 1440.0

// A trail segment crossing this many degrees of azimuth is assumed to wrap
// around 0°/360° (or cross the pole) and is suppressed to avoid a spurious line.
private const val SEGMENT_WRAP_DEG = 90.0

private const val TIME_LABEL_PAD_PX = 24f
private const val DIM_TRAIL_ALPHA = 100

// Selected symbols flash at 2 Hz: 250 ms on, 250 ms off.
private const val FLASH_HALF_PERIOD_MS = 250L

@Composable
fun PlanetPathsScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
    val jd = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val lst = calculateLSTHours(jd, obs.lon)
    val lat = obs.lat
    val isSouthern = lat < 0.0

    val lightBlueInt = Color(0xFFADD8E6).toArgb()
    val horizonColorInt = Color(0xFF444444).toArgb()

    val trailVisible = remember { mutableStateMapOf("Sun" to true) }
    // Read only inside the Canvas draw lambda, so each toggle invalidates just
    // the draw pass — no recomposition, no trail recomputation.
    var flashOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FLASH_HALF_PERIOD_MS)
            flashOn = !flashOn
        }
    }
    val planets = remember { getOrreryPlanets().filter { it.name != "Earth" } }

    val axisLabelPaint = remember {
        Paint().apply {
            color = lightBlueInt
            textSize = 33f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }
    val axisTitlePaint = remember {
        Paint().apply {
            color = lightBlueInt
            textSize = 52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
    }
    val symbolPaint = remember {
        Paint().apply {
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
    }
    val horizonPaint = remember {
        Paint().apply {
            color = horizonColorInt
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
    }
    val tickPaint = remember {
        Paint().apply {
            color = lightBlueInt
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
    }
    val trailPaint = remember {
        Paint().apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
        }
    }
    val dimTrailPaint = remember {
        Paint().apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
        }
    }
    val timeLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    data class BodyData(
        val name: String, val symbol: String, val color: Color, val colorInt: Int,
        val az: Double, val alt: Double,
        val trail: List<Pair<Double, Double>>
    )

    fun displayAz(az: Double) = if (isSouthern) (az - 180.0).mod(360.0) else az

    val startJd = jd - TRAIL_HALF_DAYS

    fun computeTrail(name: String, isMoon: Boolean): List<Pair<Double, Double>> {
        return (0..TRAIL_STEPS).map { i ->
            val sampleJd = startJd + i * TRAIL_STEP_DAYS
            val sampleLst = calculateLSTHours(sampleJd, obs.lon)
            val state = AstroEngine.getBodyState(name, sampleJd)
            val apparent = j2000ToApparent(state.ra, state.dec, sampleJd)
            val (raH, dec) = if (isMoon) {
                val topo = toTopocentric(apparent.ra, apparent.dec, state.distGeo, lat, obs.lon, sampleLst)
                Pair(topo.ra / 15.0, topo.dec)
            } else {
                Pair(apparent.ra / 15.0, apparent.dec)
            }
            val azAlt = calculateAzAlt(sampleLst, lat, raH, dec)
            Pair(displayAz(azAlt.az), applyRefraction(azAlt.alt).toDouble())
        }
    }

    val bodies = mutableListOf<BodyData>()

    val sunState = AstroEngine.getBodyState("Sun", jd)
    val sunApparent = j2000ToApparent(sunState.ra, sunState.dec, jd)
    val sunAz = calculateAzAlt(lst, lat, sunApparent.ra / 15.0, sunApparent.dec)
    bodies.add(BodyData("Sun", "\u2609", Color.Yellow, Color.Yellow.toArgb(), displayAz(sunAz.az), applyRefraction(sunAz.alt).toDouble(),
        computeTrail("Sun", false)))

    val moonState = AstroEngine.getBodyState("Moon", jd)
    val moonApparent = j2000ToApparent(moonState.ra, moonState.dec, jd)
    val topoMoon = toTopocentric(moonApparent.ra, moonApparent.dec, moonState.distGeo, lat, obs.lon, lst)
    val moonAz = calculateAzAlt(lst, lat, topoMoon.ra / 15.0, topoMoon.dec)
    bodies.add(BodyData("Moon", "\u263E", Color.White, Color.White.toArgb(), displayAz(moonAz.az), applyRefraction(moonAz.alt).toDouble(),
        computeTrail("Moon", true)))

    for (p in planets) {
        val state = AstroEngine.getBodyState(p.name, jd)
        val apparent = j2000ToApparent(state.ra, state.dec, jd)
        val azAlt = calculateAzAlt(lst, lat, apparent.ra / 15.0, apparent.dec)
        bodies.add(BodyData(p.name, p.symbol, p.color, p.color.toArgb(), displayAz(azAlt.az), applyRefraction(azAlt.alt).toDouble(),
            computeTrail(p.name, false)))
    }

    var minAlt = Double.MAX_VALUE
    var maxAlt = -Double.MAX_VALUE
    for (body in bodies) {
        if (body.alt < minAlt) minAlt = body.alt
        if (body.alt > maxAlt) maxAlt = body.alt
        for ((_, alt) in body.trail) {
            if (alt < minAlt) minAlt = alt
            if (alt > maxAlt) maxAlt = alt
        }
    }
    val altMin = (minAlt - 1.0).coerceAtLeast(-90.0)
    val altMax = (maxAlt + 1.0).coerceAtMost(90.0)

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
        withDensityScaling { w, h ->

        val leftMargin = 120f
        val rightMargin = 15f
        val topMargin = 15f
        val bottomMargin = 90f
        val tickLen = 10f

        val chartLeft = leftMargin
        val chartRight = w - rightMargin
        val chartTop = topMargin
        val chartBottom = h - bottomMargin
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        fun azToX(az: Double): Float = (chartLeft + (az / 360.0) * chartWidth).toFloat()
        fun altToY(alt: Double): Float = (chartTop + ((altMax - alt) / (altMax - altMin)) * chartHeight).toFloat()

        val horizonVisible = altMin <= 0.0 && altMax >= 0.0
        if (horizonVisible) {
            val horizonY = altToY(0.0)
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(chartLeft, horizonY, chartRight, horizonY, horizonPaint)
            }
        }

        val tickStart = (ceil(altMin / 10.0) * 10).toInt()
        val tickEnd = (floor(altMax / 10.0) * 10).toInt()
        axisLabelPaint.textAlign = Paint.Align.RIGHT
        for (deg in tickStart..tickEnd step 10) {
            val y = altToY(deg.toDouble())
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(chartLeft - tickLen, y, chartLeft, y, tickPaint)
                val label = "%+d".format(deg)
                nc.nativeCanvas.drawText(label, chartLeft - tickLen - 4f, y + 8f, axisLabelPaint)
            }
        }

        val azTickY = if (horizonVisible) altToY(0.0) else chartBottom
        val cardinalLabels = if (isSouthern)
            listOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")
        else
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        axisLabelPaint.textAlign = Paint.Align.CENTER
        for ((idx, deg) in (0..315 step 45).withIndex()) {
            val x = azToX(deg.toDouble())
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(x, azTickY - tickLen / 2f, x, azTickY + tickLen / 2f, tickPaint)
                nc.nativeCanvas.drawText(cardinalLabels[idx], x, azTickY + tickLen / 2f + 40f, axisLabelPaint)
            }
        }

        drawIntoCanvas { nc ->
            nc.nativeCanvas.save()
            val labelX = 20f
            val labelY = (chartTop + chartBottom) / 2f
            nc.nativeCanvas.rotate(-90f, labelX, labelY)
            val textOffset = -(axisTitlePaint.ascent() + axisTitlePaint.descent()) / 2f
            nc.nativeCanvas.drawText("Elevation", labelX, labelY + textOffset, axisTitlePaint)
            nc.nativeCanvas.restore()
        }

        drawIntoCanvas { nc ->
            val labelX = azToX(180.0)
            val tickLabelBaseline = azTickY + tickLen / 2f + 40f
            val labelY = tickLabelBaseline + axisLabelPaint.descent() - axisTitlePaint.ascent()
            nc.nativeCanvas.drawText("Azimuth", labelX, labelY, axisTitlePaint)
        }

        for (body in bodies) {
            if (trailVisible[body.name] != true) continue
            trailPaint.color = body.colorInt
            dimTrailPaint.color = body.colorInt
            // Paint.color sets the full ARGB, which resets alpha to 255; re-apply dim alpha.
            dimTrailPaint.alpha = DIM_TRAIL_ALPHA
            drawIntoCanvas { nc ->
                for (i in 0 until body.trail.size - 1) {
                    val (az1, alt1) = body.trail[i]
                    val (az2, alt2) = body.trail[i + 1]
                    if (abs(az2 - az1) >= SEGMENT_WRAP_DEG) continue
                    if (alt1 >= 0.0 && alt2 >= 0.0) {
                        nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), azToX(az2), altToY(alt2), trailPaint)
                    } else if (alt1 < 0.0 && alt2 < 0.0) {
                        nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), azToX(az2), altToY(alt2), dimTrailPaint)
                    } else {
                        val t = alt1 / (alt1 - alt2)
                        val crossAz = az1 + t * (az2 - az1)
                        val horizY = altToY(0.0)
                        val crossX = azToX(crossAz)
                        if (alt1 >= 0.0) {
                            nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), crossX, horizY, trailPaint)
                            nc.nativeCanvas.drawLine(crossX, horizY, azToX(az2), altToY(alt2), dimTrailPaint)
                        } else {
                            nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), crossX, horizY, dimTrailPaint)
                            nc.nativeCanvas.drawLine(crossX, horizY, azToX(az2), altToY(alt2), trailPaint)
                        }
                    }
                }
            }
        }

        for (body in bodies) {
            val x = azToX(body.az)
            val y = altToY(body.alt)
            val flashedOff = trailVisible[body.name] == true && !flashOn
            symbolPaint.color = if (flashedOff) android.graphics.Color.BLACK else body.colorInt
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawText(body.symbol, x, y + 14f, symbolPaint)
            }
        }

        // Drawn last so planet symbols cannot obscure them.
        val selectedBodies = bodies.filter { trailVisible[it.name] == true }
        if (selectedBodies.size == 1) {
            val body = selectedBodies[0]
            val trail = body.trail
            val displayZone = if (obs.useStandardTime)
                ZoneOffset.ofTotalSeconds((obs.stdOffsetHours * 3600).toInt())
            else
                ZoneOffset.UTC
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(displayZone)
            drawIntoCanvas { nc ->
                for (i in 0 until trail.size - 1) {
                    val (az1, alt1) = trail[i]
                    val (az2, alt2) = trail[i + 1]
                    if (abs(az2 - az1) >= SEGMENT_WRAP_DEG) continue
                    val lowDeg = (floor(min(alt1, alt2) / 10.0) * 10).toInt()
                    val highDeg = (ceil(max(alt1, alt2) / 10.0) * 10).toInt()
                    for (deg in lowDeg..highDeg step 10) {
                        val level = deg.toDouble()
                        val rising = alt1 < level && alt2 >= level
                        val setting = alt1 >= level && alt2 < level
                        if (!rising && !setting) continue
                        val t = (level - alt1) / (alt2 - alt1)
                        val crossAz = az1 + t * (az2 - az1)
                        val crossX = azToX(crossAz)
                        val crossY = altToY(level)
                        val crossJd = startJd + (i + t) * TRAIL_STEP_DAYS
                        val crossEpochSec = ((crossJd - UNIX_EPOCH_JD) * SECONDS_PER_DAY).toLong()
                        val crossInstant = Instant.ofEpochSecond(crossEpochSec)
                        val timeStr = timeFormatter.format(crossInstant)
                        if (rising) {
                            timeLabelPaint.textAlign = Paint.Align.RIGHT
                            nc.nativeCanvas.drawText(timeStr, crossX - TIME_LABEL_PAD_PX, crossY + 8f, timeLabelPaint)
                        } else {
                            timeLabelPaint.textAlign = Paint.Align.LEFT
                            nc.nativeCanvas.drawText(timeStr, crossX + TIME_LABEL_PAD_PX, crossY + 8f, timeLabelPaint)
                        }
                    }
                }
            }
        }

        }
    }

    BodySelectionChips(bodies.map { ChipBody(it.name, it.symbol, it.color) }, trailVisible)
    }
}
