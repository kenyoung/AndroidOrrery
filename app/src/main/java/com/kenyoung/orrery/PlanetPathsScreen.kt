package com.kenyoung.orrery

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.*

private const val TRAIL_HALF_HOURS = 12.0
private const val TRAIL_STEP_MINUTES = 15
private const val TRAIL_STEPS = (TRAIL_HALF_HOURS * 2 * 60 / TRAIL_STEP_MINUTES).toInt()

@Composable
fun PlanetPathsScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
    val jd = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val lst = calculateLSTHours(jd, obs.lon)
    val lat = obs.lat

    val lightBlueInt = Color(0xFFADD8E6).toArgb()
    val horizonColorInt = Color(0xFF444444).toArgb()

    val planets = remember { getOrreryPlanets().filter { it.name != "Earth" } }

    data class BodyData(
        val symbol: String, val colorInt: Int,
        val az: Double, val alt: Double,
        val trail: List<Pair<Double, Double>>
    )

    fun computeTrail(name: String, isMoon: Boolean): List<Pair<Double, Double>> {
        val stepDays = TRAIL_STEP_MINUTES / 1440.0
        val startJd = jd - TRAIL_HALF_HOURS / 24.0
        return (0..TRAIL_STEPS).map { i ->
            val sampleJd = startJd + i * stepDays
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
            Pair(azAlt.az, applyRefraction(azAlt.alt).toDouble())
        }
    }

    val bodies = mutableListOf<BodyData>()

    // Sun
    val sunState = AstroEngine.getBodyState("Sun", jd)
    val sunApparent = j2000ToApparent(sunState.ra, sunState.dec, jd)
    val sunAz = calculateAzAlt(lst, lat, sunApparent.ra / 15.0, sunApparent.dec)
    bodies.add(BodyData("\u2609", Color.Yellow.toArgb(), sunAz.az, applyRefraction(sunAz.alt).toDouble(),
        computeTrail("Sun", false)))

    // Moon (with topocentric correction)
    val moonState = AstroEngine.getBodyState("Moon", jd)
    val moonApparent = j2000ToApparent(moonState.ra, moonState.dec, jd)
    val topoMoon = toTopocentric(moonApparent.ra, moonApparent.dec, moonState.distGeo, lat, obs.lon, lst)
    val moonAz = calculateAzAlt(lst, lat, topoMoon.ra / 15.0, topoMoon.dec)
    bodies.add(BodyData("\u263E", Color.White.toArgb(), moonAz.az, applyRefraction(moonAz.alt).toDouble(),
        computeTrail("Moon", true)))

    // Planets
    for (p in planets) {
        val state = AstroEngine.getBodyState(p.name, jd)
        val apparent = j2000ToApparent(state.ra, state.dec, jd)
        val azAlt = calculateAzAlt(lst, lat, apparent.ra / 15.0, apparent.dec)
        bodies.add(BodyData(p.symbol, p.color.toArgb(), azAlt.az, applyRefraction(azAlt.alt).toDouble(),
            computeTrail(p.name, false)))
    }

    // Compute elevation range from all trails and current positions
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

    Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        withDensityScaling { w, h ->

        val axisLabelPaint = Paint().apply {
            color = lightBlueInt
            textSize = 33f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val leftAxisLabelPaint = Paint().apply {
            color = lightBlueInt
            textSize = 33f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val axisTitlePaint = Paint().apply {
            color = lightBlueInt
            textSize = 52f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val symbolPaint = Paint().apply {
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val horizonPaint = Paint().apply {
            color = horizonColorInt
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val tickPaint = Paint().apply {
            color = lightBlueInt
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val trailPaint = Paint().apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
        }

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

        // Draw horizon line (if 0° is within the elevation range)
        val horizonVisible = altMin <= 0.0 && altMax >= 0.0
        if (horizonVisible) {
            val horizonY = altToY(0.0)
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(chartLeft, horizonY, chartRight, horizonY, horizonPaint)
            }
        }

        // Draw elevation ticks and labels (left side, every 10°)
        val tickStart = (ceil(altMin / 10.0) * 10).toInt()
        val tickEnd = (floor(altMax / 10.0) * 10).toInt()
        for (deg in tickStart..tickEnd step 10) {
            val y = altToY(deg.toDouble())
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(chartLeft - tickLen, y, chartLeft, y, tickPaint)
                val label = "%+d".format(deg)
                nc.nativeCanvas.drawText(label, chartLeft - tickLen - 4f, y + 8f, leftAxisLabelPaint)
            }
        }

        // Draw azimuth ticks and labels (at horizon or bottom of chart)
        val azTickY = if (horizonVisible) altToY(0.0) else chartBottom
        val cardinalLabels = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for ((idx, deg) in (0..315 step 45).withIndex()) {
            val x = azToX(deg.toDouble())
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawLine(x, azTickY - tickLen / 2f, x, azTickY + tickLen / 2f, tickPaint)
                nc.nativeCanvas.drawText(cardinalLabels[idx], x, azTickY + tickLen / 2f + 40f, axisLabelPaint)
            }
        }

        // Draw "Elevation" label rotated 90° CCW along left side
        drawIntoCanvas { nc ->
            nc.nativeCanvas.save()
            val labelX = 20f
            val labelY = (chartTop + chartBottom) / 2f
            nc.nativeCanvas.rotate(-90f, labelX, labelY)
            val textOffset = -(axisTitlePaint.ascent() + axisTitlePaint.descent()) / 2f
            nc.nativeCanvas.drawText("Elevation", labelX, labelY + textOffset, axisTitlePaint)
            nc.nativeCanvas.restore()
        }

        // Draw "Azimuth" label centered below the 180° tick label
        drawIntoCanvas { nc ->
            val labelX = azToX(180.0)
            val tickLabelBaseline = azTickY + tickLen / 2f + 40f
            val labelY = tickLabelBaseline + axisLabelPaint.descent() - axisTitlePaint.ascent()
            nc.nativeCanvas.drawText("Azimuth", labelX, labelY, axisTitlePaint)
        }

        // Draw trails (dotted lines, ±12 hours; dimmed below horizon)
        val dimTrailPaint = Paint(trailPaint).apply { alpha = 100 }
        for (body in bodies) {
            trailPaint.color = body.colorInt
            dimTrailPaint.color = body.colorInt
            dimTrailPaint.alpha = 100
            drawIntoCanvas { nc ->
                for (i in 0 until body.trail.size - 1) {
                    val (az1, alt1) = body.trail[i]
                    val (az2, alt2) = body.trail[i + 1]
                    if (abs(az2 - az1) >= 90.0) continue
                    if (alt1 >= 0.0 && alt2 >= 0.0) {
                        // Both above horizon — full brightness
                        nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), azToX(az2), altToY(alt2), trailPaint)
                    } else if (alt1 < 0.0 && alt2 < 0.0) {
                        // Both below horizon — dimmed
                        nc.nativeCanvas.drawLine(azToX(az1), altToY(alt1), azToX(az2), altToY(alt2), dimTrailPaint)
                    } else {
                        // Segment crosses horizon — split at the crossing point
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

        // Draw body symbols
        for (body in bodies) {
            val x = azToX(body.az)
            val y = altToY(body.alt)
            symbolPaint.color = body.colorInt
            drawIntoCanvas { nc ->
                nc.nativeCanvas.drawText(body.symbol, x, y + 14f, symbolPaint)
            }
        }

        }
    }
}
