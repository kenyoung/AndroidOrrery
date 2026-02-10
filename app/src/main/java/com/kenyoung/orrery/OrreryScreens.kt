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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private fun buildOrbitPath(
    p: PlanetElements, cx: Float, cy: Float, pixelsPerAU: Float, steps: Int = 360
): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val wBar = Math.toRadians(p.w_bar)
    val nodeN = Math.toRadians(p.N)
    val iRad = Math.toRadians(p.i)
    for (step in 0..steps) {
        val E = (step.toDouble() / steps) * 2.0 * Math.PI
        val xv = p.a * (cos(E) - p.e)
        val yv = p.a * sqrt(1 - p.e * p.e) * sin(E)
        val u = atan2(yv, xv) + wBar - nodeN
        val r = sqrt(xv * xv + yv * yv)
        val xEcl = r * (cos(u) * cos(nodeN) - sin(u) * sin(nodeN) * cos(iRad))
        val yEcl = r * (cos(u) * sin(nodeN) + sin(u) * cos(nodeN) * cos(iRad))
        val px = cx - (yEcl * pixelsPerAU).toFloat()
        val py = cy - (xEcl * pixelsPerAU).toFloat()
        if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    return path
}

private fun DrawScope.drawArbitraryArrow(
    baseX: Float, baseY: Float, pointingAngleDeg: Double,
    l1: String, l2: String, arrowLen: Float, arrowPaint: Paint, labelPaint: Paint,
    labelOffset: Offset = Offset.Zero
) {
    val h = size.height
    val angleRad = Math.toRadians(90.0 - pointingAngleDeg)
    val vecX = -cos(angleRad).toFloat()
    val vecY = -sin(angleRad).toFloat()
    val tipX = baseX + (arrowLen * vecX)
    val tipY = baseY + (arrowLen * vecY)

    val lightBlueColor = Color(0xFF87CEFA)
    drawLine(color = lightBlueColor, start = Offset(baseX, baseY), end = Offset(tipX, tipY), strokeWidth = 4f)

    val headSize = 10f
    val arrowHeadPath = Path().apply {
        val anglePerp = angleRad + (Math.PI / 2.0)
        val dx = (headSize * cos(anglePerp)).toFloat()
        val dy = (headSize * sin(anglePerp)).toFloat()
        moveTo(tipX, tipY)
        val backX = baseX + ((arrowLen - headSize) * vecX)
        val backY = baseY + ((arrowLen - headSize) * vecY)
        lineTo(backX + dx, backY + dy)
        lineTo(backX - dx, backY - dy)
        close()
    }

    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawPath(arrowHeadPath, arrowPaint)
        val labelDist = 45f
        val labelX = tipX + (labelDist * vecX) + labelOffset.x
        val labelY = tipY + (labelDist * vecY) + labelOffset.y
        val yShift = h / 50f
        canvas.nativeCanvas.drawText(l1, labelX, labelY + yShift, labelPaint)
        canvas.nativeCanvas.drawText(l2, labelX, labelY + 40f + yShift, labelPaint)
    }
}

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
    // Specific paint for Halley's symbol (White)
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
            color = 0xFF87CEFA.toInt()  // Light blue
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    // Halley's Comet Element (Only used for drawing the orbit path now)
    val halley = remember { getHalleyElement() }

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

            // Draw Sun
            drawCircle(color = Color.Yellow, radius = 18f, center = Offset(cx, cy))

            val sunTextOffset = (textPaint.descent() + textPaint.ascent()) / 2
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("☉", cx, cy - sunTextOffset, textPaint)
            }

            // JD for Engine
            val jd = epochDay + 2440587.5

            // --- STANDARD PLANETS ---
            for (p in planetList) {
                // 1. Draw Orbit Path (Keplerian elements define the ellipse shape)
                val orbitPath = buildOrbitPath(p, cx, cy, currentPixelsPerAU.toFloat())
                drawPath(orbitPath, color = Color.Gray, style = Stroke(width = 2f))

                // 2. Draw Planet Position (FROM ENGINE)
                val state = AstroEngine.getBodyState(p.name, jd)

                // Engine returns HelioPos (X,Y,Z). We map X->-Y (Screen Y), Y->-X (Screen X) ??
                // Wait, previous math was:
                // px = cx - (y_ecl * scale)
                // py = cy - (x_ecl * scale)
                // Engine x = x_ecl, Engine y = y_ecl.

                val px = cx - (state.helioPos.y * currentPixelsPerAU).toFloat()
                val py = cy - (state.helioPos.x * currentPixelsPerAU).toFloat()

                drawCircle(color = p.color, radius = 18f, center = Offset(px, py))

                val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(p.symbol, px, py - textOffset, textPaint)
                }
            }

            // --- HALLEY'S COMET ---
            val p = halley
            val halleyPath = buildOrbitPath(p, cx, cy, currentPixelsPerAU.toFloat())
            drawPath(halleyPath, color = Color.Gray, style = Stroke(width = 2f))

            // 2. Current Position Halley (FROM ENGINE - Hybrid)
            val hState = AstroEngine.getBodyState("Halley", jd)

            val pxHalley = cx - (hState.helioPos.y * currentPixelsPerAU).toFloat()
            val pyHalley = cy - (hState.helioPos.x * currentPixelsPerAU).toFloat()

            drawCircle(color = p.color, radius = 18f, center = Offset(pxHalley, pyHalley))
            val halleyTextOffset = (cometTextPaint.descent() + cometTextPaint.ascent()) / 2
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(p.symbol, pxHalley, pyHalley - halleyTextOffset, cometTextPaint)
            }

            // --- DIRECTION ARROWS ---
            val arrowDistAU = 36.0
            val distPx = arrowDistAU * currentPixelsPerAU
            val lightBlue = 0xFF87CEFA.toInt()
            val arrowPaint = Paint().apply { color = lightBlue; style = Paint.Style.FILL }
            val arrowLen = 80f

            // 1. Vernal Equinox
            val vernalAngleRad = Math.toRadians(90.0 - 0.0)
            val vernalBaseX = cx - (distPx * cos(vernalAngleRad)).toFloat()
            val vernalBaseY = cy - (distPx * sin(vernalAngleRad)).toFloat()
            val vernalShiftX = w / 10f
            drawArbitraryArrow(vernalBaseX, vernalBaseY, 0.0, "To Vernal", "Equinox", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(vernalShiftX, 0f))

            // 2. Galactic Center
            val gcUnscaledOffsetX = (w * 0.84f) - cx
            val gcUnscaledOffsetY = (h * 0.8f) - cy
            val gcBaseX = cx + (gcUnscaledOffsetX * scale)
            val gcBaseY = cy + (gcUnscaledOffsetY * scale)

            val gcLabelX = -0.1272f * w
            val gcLabelY = 0.02554f * h
            drawArbitraryArrow(gcBaseX, gcBaseY, 266.85, "To Galactic", "Center", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(gcLabelX, gcLabelY))

            // 3. CMB Dipole
            val cmbUnscaledOffsetX = (w * 0.059f) - cx
            val cmbUnscaledOffsetY = (h * 0.8492f) - cy
            val cmbBaseX = cx + (cmbUnscaledOffsetX * scale)
            val cmbBaseY = cy + (cmbUnscaledOffsetY * scale)

            val cmbLabelOffsetX = 0.03f * w
            val cmbLabelOffsetY = -0.146f * h
            drawArbitraryArrow(cmbBaseX, cmbBaseY, 171.67, "To CMB", "Dipole", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(cmbLabelOffsetX, cmbLabelOffsetY))

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
            color = 0xFF87CEFA.toInt()  // Light blue
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

        val horizontalPadding = 60f
        val planetRadius = 18f
        val topTitleSpace = 75f // Space for app title bar at top
        // Arrow overhead above Neptune orbit: 60 (dist offset) + 80 (arrow) + label space
        val arrowOverhead = 150f
        val labelSpace = planetRadius * 2 + 50f // Space below Neptune for bottom label

        // Width constraint: fit horizontally with padding
        val maxRadiusForWidth = (w / 2f) - horizontalPadding

        // Height constraint: must fit arrows above and label below
        // Available space for orbits: h - topTitleSpace - arrowOverhead - labelSpace
        // This space must accommodate 2 * Neptune orbit radius
        // Neptune radius = orbitStep * 8 = maxRadius * 1.063
        val availableForOrbits = h - topTitleSpace - arrowOverhead - labelSpace
        val maxRadiusForHeight = (availableForOrbits / 2f) / 1.063f

        val maxRadius = min(maxRadiusForWidth, maxRadiusForHeight).coerceAtLeast(50f)
        val orbitStep = (maxRadius / 8f) * 1.063f
        val neptuneOrbitRadius = orbitStep * 8f

        // Position center so Vernal Equinox arrow/label clears the title bar
        val cy = topTitleSpace + arrowOverhead + neptuneOrbitRadius

        drawCircle(color = Color.Yellow, radius = 18f, center = Offset(cx, cy))
        val sunTextOffset = (textPaint.descent() + textPaint.ascent()) / 2
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText("☉", cx, cy - sunTextOffset, textPaint) }

        val jd = epochDay + 2440587.5

        for (i in 0 until planetList.size) {
            val p = planetList[i]
            val radius = orbitStep * (i + 1)

            // Draw Orbit Circle
            drawCircle(color = Color.Gray, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))

            // Get Position from Engine
            val state = AstroEngine.getBodyState(p.name, jd)

            // FIX: Use Heliocentric Vectors to get the true Heliocentric Longitude (in Radians)
            // This prevents "retrograde" motion which is an artifact of using Geocentric Ecliptic Longitude.
            val helioLongRad = atan2(state.helioPos.y, state.helioPos.x)

            val px = cx - (radius * sin(helioLongRad)).toFloat()
            val py = cy - (radius * cos(helioLongRad)).toFloat()

            drawCircle(color = p.color, radius = 18f, center = Offset(px, py))
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(p.symbol, px, py - textOffset, textPaint) }

            if (p.name == "Earth") {
                val moonOrbitRadius = orbitStep / 2f
                drawCircle(color = Color.Gray, radius = moonOrbitRadius, center = Offset(px, py), style = Stroke(width = 1f))

                // Moon Phase Angle from AstroMath
                val elongationRad = Math.toRadians(calculateMoonPhaseAngle(epochDay))
                val vecES_x = cx - px; val vecES_y = py - cy
                val sunAngleStandard = atan2(vecES_y.toDouble(), vecES_x.toDouble())
                val moonAngleStandard = sunAngleStandard + elongationRad
                val mx = px + (moonOrbitRadius * cos(moonAngleStandard)).toFloat()
                val my = py - (moonOrbitRadius * sin(moonAngleStandard)).toFloat()
                drawCircle(color = Color.White, radius = 6.25f, center = Offset(mx, my))
            }
        }

        // --- DIRECTION ARROWS ---
        val outerRadius = orbitStep * 8f
        val dist = outerRadius + 60f
        val lightBlue = 0xFF87CEFA.toInt()
        val arrowPaint = Paint().apply { color = lightBlue; style = Paint.Style.FILL }
        val arrowLen = 80f

        // 1. Vernal Equinox
        val vernalAngleRad = Math.toRadians(90.0 - 0.0)
        val vernalBaseX = cx - (dist * cos(vernalAngleRad)).toFloat()
        val vernalBaseY = cy - (dist * sin(vernalAngleRad)).toFloat()
        val vernalShiftX = (60f + (w / 10f)) - (0.296f * w) + (0.27f * w)
        drawArbitraryArrow(vernalBaseX, vernalBaseY, 0.0, "To Vernal", "Equinox", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(vernalShiftX, 0f))

        // 2. Galactic Center
        val gcBaseX = w * 0.84f
        val gcBaseY = h * 0.8f
        val gcLabelX = -0.1272f * w
        val gcLabelY = 0.02554f * h
        drawArbitraryArrow(gcBaseX, gcBaseY, 266.85, "To Galactic", "Center", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(gcLabelX, gcLabelY))

        // 3. CMB Dipole
        val cmbBaseX = w * 0.059f
        val cmbBaseY = h * 0.8492f
        val cmbLabelOffsetX = 0.03f * w
        val cmbLabelOffsetY = -0.146f * h
        drawArbitraryArrow(cmbBaseX, cmbBaseY, 171.67, "To CMB", "Dipole", arrowLen, arrowPaint, labelPaint, labelOffset = Offset(cmbLabelOffsetX, cmbLabelOffsetY))

        drawIntoCanvas { canvas ->
            // Position label 2 font heights above the bottom (just above the year buttons)
            val viewLabelY = h - 2 * labelPaint.textSize
            canvas.nativeCanvas.drawText("View from above the Sun's north pole", cx, viewLabelY, labelPaint)
        }
    }
}