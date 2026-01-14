package com.example.orrery

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

// --- COMPOSABLE: TO-SCALE ORRERY ---
@Composable
fun ScaleOrrery(epochDay: Double) {
    val planetList = remember { getOrreryPlanets() }
    var scale by remember { mutableStateOf(1f) }
    var hasZoomed by remember { mutableStateOf(false) }

    val baseFitAU = 31.25f
    val maxScale = baseFitAU / 0.47f
    val minScale = 1f

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(minScale, maxScale)
                    if (zoom != 1f) hasZoomed = true
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val minDim = min(w, h)

            val pixelsPerAU = (minDim / 2f) / baseFitAU
            val currentPixelsPerAU = pixelsPerAU * scale

            // Draw Sun (Yellow circle radius 18f with Black Symbol)
            drawCircle(color = Color.Yellow, radius = 18f, center = Offset(cx, cy))

            val sunTextOffset = (textPaint.descent() + textPaint.ascent()) / 2
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("☉", cx, cy - sunTextOffset, textPaint)
            }

            val d = (2440587.5 + epochDay) - 2451545.0

            for (p in planetList) {
                // Draw Orbit Path
                val orbitPath = androidx.compose.ui.graphics.Path()
                for (angleIdx in 0..100) {
                    val M_sim = (angleIdx / 100.0) * 2.0 * Math.PI
                    val E = solveKepler(M_sim, p.e)

                    val xv = p.a * (cos(E) - p.e)
                    val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)
                    // val v = atan2(yv, xv) // Unused in path calc simplified

                    val w_bar = Math.toRadians(p.w_bar); val N = Math.toRadians(p.N); val i_rad = Math.toRadians(p.i)
                    val u = atan2(yv, xv) + w_bar - N // v + w_bar - N
                    val r = p.a * (1 - p.e * cos(E))

                    val x_ecl = r * (cos(u) * cos(N) - sin(u) * sin(N) * cos(i_rad))
                    val y_ecl = r * (cos(u) * sin(N) + sin(u) * cos(N) * cos(i_rad))

                    // ROTATED 90 DEG: Map (x, y) -> (-y, x) for screen coordinates
                    val px = cx - (y_ecl * currentPixelsPerAU).toFloat()
                    val py = cy - (x_ecl * currentPixelsPerAU).toFloat()

                    if (angleIdx == 0) orbitPath.moveTo(px, py) else orbitPath.lineTo(px, py)
                }
                orbitPath.close()
                drawPath(orbitPath, color = Color.Gray, style = Stroke(width = 2f))

                // Draw Planet
                val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
                val w_bar_curr = Math.toRadians(p.w_bar)
                val M_curr = Lp - w_bar_curr
                val E_curr = solveKepler(M_curr, p.e)

                val xv_curr = p.a * (cos(E_curr) - p.e)
                val yv_curr = p.a * sqrt(1 - p.e*p.e) * sin(E_curr)
                val v_curr = atan2(yv_curr, xv_curr)

                val N_curr = Math.toRadians(p.N); val i_curr = Math.toRadians(p.i)
                val u_curr = v_curr + w_bar_curr - N_curr
                val r_curr = p.a * (1 - p.e * cos(E_curr))

                val x_pos = r_curr * (cos(u_curr) * cos(N_curr) - sin(u_curr) * sin(N_curr) * cos(i_curr))
                val y_pos = r_curr * (cos(u_curr) * sin(N_curr) + sin(u_curr) * cos(N_curr) * cos(i_curr))

                val px = cx - (y_pos * currentPixelsPerAU).toFloat()
                val py = cy - (x_pos * currentPixelsPerAU).toFloat()

                drawCircle(color = p.color, radius = 18f, center = Offset(px, py))

                val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(p.symbol, px, py - textOffset, textPaint)
                }
            }

            // Arrow at 1:30 position (45 degrees, top-right)
            val arrowDistAU = 36.0
            val distPx = arrowDistAU * currentPixelsPerAU
            val angle = Math.toRadians(45.0)
            val ax = cx + (distPx * sin(angle)).toFloat()
            // Shift down 1.5 * diameter (36f) = 54f
            val ay = cy - (distPx * cos(angle)).toFloat() + 54f

            // Arrow points straight up
            val arrowTipY = ay - 80f

            val labelLine1 = "To Vernal"
            val labelLine2 = "Equinox"
            val w1 = labelPaint.measureText(labelLine1)
            val w2 = labelPaint.measureText(labelLine2)
            val maxHalfWidth = max(w1, w2) / 2f

            // Clamp to right edge padding 10f
            val textX = if (ax + maxHalfWidth > w - 10f) { w - 10f - maxHalfWidth } else { ax }

            drawLine(color = Color.White, start = Offset(ax, ay), end = Offset(ax, arrowTipY), strokeWidth = 4f)
            val arrowHeadPath = Path().apply {
                moveTo(ax, arrowTipY - 10f)
                lineTo(ax - 10f, arrowTipY + 10f)
                lineTo(ax + 10f, arrowTipY + 10f)
                close()
            }
            val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)
                canvas.nativeCanvas.drawText(labelLine2, textX, arrowTipY - 20f, labelPaint)
                canvas.nativeCanvas.drawText(labelLine1, textX, arrowTipY - 65f, labelPaint)

                // Draw Bottom View Label (Anchored to 36 AU South + 2.5*Font(40) Offset, moves with Zoom)
                val viewLabelDistPx = 36.0 * currentPixelsPerAU
                val viewLabelY = cy + viewLabelDistPx.toFloat() + 100f
                canvas.nativeCanvas.drawText("View from above the Sun's north pole", cx, viewLabelY, labelPaint)
            }
        }

        if (!hasZoomed) {
            Text(
                text = "Pinch to zoom",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp), // Shifted down (less padding) to stay clear of orbit/label
                style = TextStyle(fontSize = 14.sp)
            )
        }
    }
}

// --- COMPOSABLE: SCHEMATIC ORRERY ---
@Composable
fun SchematicOrrery(epochDay: Double) {
    val planetList = remember { getOrreryPlanets() }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val minDim = min(w, h)

        val topPadding = 60f
        val maxRadius = (minDim / 2f) - topPadding
        val orbitStep = (maxRadius / 8f) * 1.063f

        // Draw Sun (Yellow circle radius 18f with Black Symbol)
        drawCircle(color = Color.Yellow, radius = 18f, center = Offset(cx, cy))

        val sunTextOffset = (textPaint.descent() + textPaint.ascent()) / 2
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText("☉", cx, cy - sunTextOffset, textPaint)
        }

        val d = (2440587.5 + epochDay) - 2451545.0

        for (i in 0 until planetList.size) {
            val p = planetList[i]
            val radius = orbitStep * (i + 1)

            drawCircle(color = Color.Gray, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))

            val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
            val w_bar = Math.toRadians(p.w_bar)
            val M = Lp - w_bar
            val E = solveKepler(M, p.e) // Use Helper

            val xv = p.a * (cos(E) - p.e)
            val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)
            val v = atan2(yv, xv)
            val helioLong = v + w_bar

            // CCW Calculation: x = cx - r*sin, y = cy - r*cos
            val px = cx - (radius * sin(helioLong)).toFloat()
            val py = cy - (radius * cos(helioLong)).toFloat()

            drawCircle(color = p.color, radius = 18f, center = Offset(px, py))

            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(p.symbol, px, py - textOffset, textPaint)
            }

            if (p.name == "Earth") {
                val moonOrbitRadius = orbitStep / 2f
                drawCircle(color = Color.Gray, radius = moonOrbitRadius, center = Offset(px, py), style = Stroke(width = 1f))

                val elongationRad = Math.toRadians(calculateMoonPhaseAngle(epochDay))
                val vecES_x = cx - px
                val vecES_y = py - cy
                val sunAngleStandard = atan2(vecES_y.toDouble(), vecES_x.toDouble())

                val moonAngleStandard = sunAngleStandard + elongationRad

                val mx = px + (moonOrbitRadius * cos(moonAngleStandard)).toFloat()
                val my = py - (moonOrbitRadius * sin(moonAngleStandard)).toFloat()

                drawCircle(color = Color.White, radius = 6.25f, center = Offset(mx, my))
            }
        }

        // Arrow at 1:30 position (45 degrees, top-right)
        val outerRadius = orbitStep * 8f
        val dist = outerRadius + 60f
        val angle = Math.toRadians(45.0)
        val ax = cx + (dist * sin(angle)).toFloat()
        val ay = cy - (dist * cos(angle)).toFloat()

        // Arrow points straight up
        val arrowTipY = ay - 80f

        val labelLine1 = "To Vernal"
        val labelLine2 = "Equinox"
        val w1 = labelPaint.measureText(labelLine1)
        val w2 = labelPaint.measureText(labelLine2)
        val maxHalfWidth = max(w1, w2) / 2f

        // Clamp to right edge padding 10f
        val textX = if (ax + maxHalfWidth > w - 10f) { w - 10f - maxHalfWidth } else { ax }

        drawLine(color = Color.White, start = Offset(ax, ay), end = Offset(ax, arrowTipY), strokeWidth = 4f)
        val arrowHeadPath = Path().apply {
            moveTo(ax, arrowTipY - 10f)
            lineTo(ax - 10f, arrowTipY + 10f)
            lineTo(ax + 10f, arrowTipY + 10f)
            close()
        }
        val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)
            canvas.nativeCanvas.drawText(labelLine2, textX, arrowTipY - 20f, labelPaint)
            canvas.nativeCanvas.drawText(labelLine1, textX, arrowTipY - 65f, labelPaint)

            // Draw Bottom View Label
            canvas.nativeCanvas.drawText("View from above the Sun's north pole", cx, h - 30f, labelPaint)
        }
    }
}