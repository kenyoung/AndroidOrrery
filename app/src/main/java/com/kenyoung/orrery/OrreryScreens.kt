package com.kenyoung.orrery

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
    // Specific paint for Halley's symbol (White) to contrast with Purple
    val cometTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
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

    // Halley's Comet Data (J2000 approx)
    val halley = remember {
        PlanetElements(
            "Halley", "☄", Color(0xFF800080), // Purple
            236.35, 0.013126,
            17.834, 0.96714,
            162.26, 169.75, 58.42
        )
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

            // --- STANDARD PLANETS ---
            for (p in planetList) {
                // Draw Orbit Path
                val orbitPath = androidx.compose.ui.graphics.Path()
                for (angleIdx in 0..100) {
                    val M_sim = (angleIdx / 100.0) * 2.0 * Math.PI
                    val E = solveKepler(M_sim, p.e)

                    val xv = p.a * (cos(E) - p.e)
                    val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)

                    val w_bar = Math.toRadians(p.w_bar); val N = Math.toRadians(p.N); val i_rad = Math.toRadians(p.i)
                    val u = atan2(yv, xv) + w_bar - N
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

                // Draw Planet Position
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

            // --- HALLEY'S COMET ---
            val p = halley
            // 1. Draw Orbit Path
            val halleyPath = androidx.compose.ui.graphics.Path()
            val w_bar = Math.toRadians(p.w_bar); val N = Math.toRadians(p.N); val i_rad = Math.toRadians(p.i)

            for (deg in 0..360) {
                val E_rad = Math.toRadians(deg.toDouble())
                val xv = p.a * (cos(E_rad) - p.e)
                val yv = p.a * sqrt(1 - p.e*p.e) * sin(E_rad)
                val v = atan2(yv, xv)
                val u = v + w_bar - N
                val r = sqrt(xv*xv + yv*yv)
                val x_ecl = r * (cos(u) * cos(N) - sin(u) * sin(N) * cos(i_rad))
                val y_ecl = r * (cos(u) * sin(N) + sin(u) * cos(N) * cos(i_rad))
                val px = cx - (y_ecl * currentPixelsPerAU).toFloat()
                val py = cy - (x_ecl * currentPixelsPerAU).toFloat()
                if (deg == 0) halleyPath.moveTo(px, py) else halleyPath.lineTo(px, py)
            }
            halleyPath.close()
            drawPath(halleyPath, color = Color.Gray, style = Stroke(width = 2f))

            // Current Position Halley
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
            val pxHalley = cx - (y_pos * currentPixelsPerAU).toFloat()
            val pyHalley = cy - (x_pos * currentPixelsPerAU).toFloat()
            drawCircle(color = p.color, radius = 18f, center = Offset(pxHalley, pyHalley))
            val halleyTextOffset = (cometTextPaint.descent() + cometTextPaint.ascent()) / 2
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(p.symbol, pxHalley, pyHalley - halleyTextOffset, cometTextPaint)
            }

            // --- HELPER FOR DIRECTION ARROWS ---
            val arrowDistAU = 36.0
            val distPx = arrowDistAU * currentPixelsPerAU
            val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }

            fun drawArrow(eclipticLongDeg: Double, l1: String, l2: String, textOffset: Offset = Offset.Zero) {
                val angleRad = Math.toRadians(eclipticLongDeg + 90.0)

                val xBase = cx - (distPx * cos(angleRad)).toFloat()
                val yBase = cy - (distPx * sin(angleRad)).toFloat()

                val arrowLen = 80f
                val tipX = cx - ((distPx + arrowLen) * cos(angleRad)).toFloat()
                val tipY = cy - ((distPx + arrowLen) * sin(angleRad)).toFloat()

                drawLine(color = Color.White, start = Offset(xBase, yBase), end = Offset(tipX, tipY), strokeWidth = 4f)

                val headSize = 10f
                val arrowHeadPath = Path().apply {
                    val anglePerp = angleRad + (Math.PI / 2.0)
                    val dx = (headSize * cos(anglePerp)).toFloat()
                    val dy = (headSize * sin(anglePerp)).toFloat()
                    moveTo(tipX, tipY)
                    val backX = cx - ((distPx + (arrowLen - headSize)) * cos(angleRad)).toFloat()
                    val backY = cy - ((distPx + (arrowLen - headSize)) * sin(angleRad)).toFloat()
                    lineTo(backX + dx, backY + dy)
                    lineTo(backX - dx, backY - dy)
                    close()
                }

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)

                    val labelDist = 45f
                    val labelX = tipX - (labelDist * cos(angleRad)).toFloat() + textOffset.x
                    val labelY = tipY - (labelDist * sin(angleRad)).toFloat() + textOffset.y

                    canvas.nativeCanvas.drawText(l1, labelX, labelY, labelPaint)
                    canvas.nativeCanvas.drawText(l2, labelX, labelY + 40f, labelPaint)
                }
            }

            // 1. Vernal Equinox (0.0) - Up. Shift label Right by w/10
            val vernalShiftX = w / 10f
            drawArrow(0.0, "To Vernal", "Equinox", Offset(vernalShiftX, 0f))

            // 2. Galactic Center (135.0 for 4:30) - Bottom Right
            // MATCHING SCHEMATIC: 4:30 position. Text shifted Left to avoid clip.
            drawArrow(135.0, "To Galactic", "Center", Offset(-80f, 0f))

            // 3. CMB Dipole (225.0 for 7:30) - Bottom Left
            // MATCHING SCHEMATIC: 7:30 position. Text shifted Right to avoid clip.
            drawArrow(225.0, "To CMB", "Dipole", Offset(80f, 0f))

            drawIntoCanvas { canvas ->
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
                    .padding(bottom = 40.dp),
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

        drawCircle(color = Color.Yellow, radius = 18f, center = Offset(cx, cy))
        val sunTextOffset = (textPaint.descent() + textPaint.ascent()) / 2
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText("☉", cx, cy - sunTextOffset, textPaint) }

        val d = (2440587.5 + epochDay) - 2451545.0

        for (i in 0 until planetList.size) {
            val p = planetList[i]
            val radius = orbitStep * (i + 1)
            drawCircle(color = Color.Gray, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))
            val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
            val w_bar = Math.toRadians(p.w_bar)
            val M = Lp - w_bar
            val E = solveKepler(M, p.e)
            val xv = p.a * (cos(E) - p.e)
            val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)
            val v = atan2(yv, xv)
            val helioLong = v + w_bar
            val px = cx - (radius * sin(helioLong)).toFloat()
            val py = cy - (radius * cos(helioLong)).toFloat()
            drawCircle(color = p.color, radius = 18f, center = Offset(px, py))
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(p.symbol, px, py - textOffset, textPaint) }

            if (p.name == "Earth") {
                val moonOrbitRadius = orbitStep / 2f
                drawCircle(color = Color.Gray, radius = moonOrbitRadius, center = Offset(px, py), style = Stroke(width = 1f))
                val elongationRad = Math.toRadians(calculateMoonPhaseAngle(epochDay))
                val vecES_x = cx - px; val vecES_y = py - cy
                val sunAngleStandard = atan2(vecES_y.toDouble(), vecES_x.toDouble())
                val moonAngleStandard = sunAngleStandard + elongationRad
                val mx = px + (moonOrbitRadius * cos(moonAngleStandard)).toFloat()
                val my = py - (moonOrbitRadius * sin(moonAngleStandard)).toFloat()
                drawCircle(color = Color.White, radius = 6.25f, center = Offset(mx, my))
            }
        }

        // --- HELPER FOR DIRECTION ARROWS (Schematic) ---
        val outerRadius = orbitStep * 8f
        val dist = outerRadius + 60f
        val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }

        // Updated helper to take Offset
        fun drawSchematicArrow(eclipticLongDeg: Double, l1: String, l2: String, textOffset: Offset = Offset.Zero) {
            val angleRad = Math.toRadians(eclipticLongDeg + 90.0)

            val xBase = cx - (dist * cos(angleRad)).toFloat()
            val yBase = cy - (dist * sin(angleRad)).toFloat()
            val arrowLen = 80f
            val tipX = cx - ((dist + arrowLen) * cos(angleRad)).toFloat()
            val tipY = cy - ((dist + arrowLen) * sin(angleRad)).toFloat()

            drawLine(color = Color.White, start = Offset(xBase, yBase), end = Offset(tipX, tipY), strokeWidth = 4f)

            val headSize = 10f
            val arrowHeadPath = Path().apply {
                val anglePerp = angleRad + (Math.PI / 2.0)
                val dx = (headSize * cos(anglePerp)).toFloat()
                val dy = (headSize * sin(anglePerp)).toFloat()
                moveTo(tipX, tipY)
                val backX = cx - ((dist + arrowLen - headSize) * cos(angleRad)).toFloat()
                val backY = cy - ((dist + arrowLen - headSize) * sin(angleRad)).toFloat()
                lineTo(backX + dx, backY + dy)
                lineTo(backX - dx, backY - dy)
                close()
            }

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)

                val labelDist = 45f
                val labelX = tipX - (labelDist * cos(angleRad)).toFloat() + textOffset.x
                val labelY = tipY - (labelDist * sin(angleRad)).toFloat() + textOffset.y

                // Shift down h/50f logic included in y
                val yShift = h / 50f

                canvas.nativeCanvas.drawText(l1, labelX, labelY + yShift, labelPaint)
                canvas.nativeCanvas.drawText(l2, labelX, labelY + 40f + yShift, labelPaint)
            }
        }

        // 1. Vernal Equinox (0.0) - Up.
        val vernalShiftX = 60f + (w / 10f)
        drawSchematicArrow(0.0, "To Vernal", "Equinox", Offset(vernalShiftX, 0f))

        // 2. Galactic Center (135.0 for 4:30) - Down Right.
        drawSchematicArrow(135.0, "To Galactic", "Center", Offset(-80f, 0f))

        // 3. CMB Dipole (225.0 for 7:30) - Down Left.
        drawSchematicArrow(225.0, "To CMB", "Dipole", Offset(80f, 0f))

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText("View from above the Sun's north pole", cx, h - 30f, labelPaint)
        }
    }
}