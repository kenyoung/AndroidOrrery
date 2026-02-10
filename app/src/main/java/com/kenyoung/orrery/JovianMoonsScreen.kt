package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

// Helper data class for the High Precision View
private data class MoonPosHighPrec(
    val x: Double,
    val y: Double,
    val z: Double,
    val shadowX: Double,
    val shadowY: Double,
    val shadowOnDisk: Boolean,
    val eclipsed: Boolean
)

@Composable
fun JovianMoonsScreen(epochDay: Double, currentInstant: Instant) {
    val zoneId = ZoneId.of("UTC")
    val currentDate = currentInstant.atZone(zoneId).toLocalDate()
    val monthYearStr = currentDate.format(DateTimeFormatter.ofPattern("MMMM, yyyy"))

    // Colors
    val bgColor = Color.Black
    val creamColor = Color(0xFFFDEEBD)
    val tanColor = Color(0xFFD2B48C)
    val shadowColor = Color.Black
    val colorIo = Color.Red
    val colorEu = Color(0xFF00FF00)
    val colorGa = Color(0xFFADD8E6)
    val colorCa = Color(0xFFFFFF00)
    val gridColor = Color.Gray

    // Orientation State
    var isNorthUp by remember { mutableStateOf(true) }
    var isEastRight by remember { mutableStateOf(false) }

    // --- ANIMATION STATE ---
    var isAnimating by remember { mutableStateOf(false) }
    var animDayOffset by remember { mutableStateOf(0.0) }

    // Constants for Data
    val daysInMonth = remember(currentDate) { currentDate.lengthOfMonth() }
    val startOfMonth = remember(currentDate) { currentDate.withDayOfMonth(1) }
    val startEpoch = remember(currentDate) { startOfMonth.toEpochDay().toDouble() }

    // Animation Logic
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            if (animDayOffset >= daysInMonth || animDayOffset == 0.0) animDayOffset = 0.0
            val startTime = System.nanoTime()
            val startOffset = animDayOffset
            while (isAnimating) {
                withInfiniteAnimationFrameMillis { frameTimeMillis ->
                    val now = System.nanoTime()
                    val elapsedSeconds = (now - startTime) / 1_000_000_000.0
                    val newOffset = startOffset + elapsedSeconds
                    if (newOffset >= daysInMonth) {
                        isAnimating = false
                        animDayOffset = 0.0
                    } else {
                        animDayOffset = newOffset
                    }
                }
            }
        }
    }

    // Determine "Effective Time"
    val effectiveJD = if (isAnimating || animDayOffset > 0.0) {
        startEpoch + animDayOffset + 2440587.5
    } else {
        currentInstant.toEpochMilli() / 86400000.0 + 2440587.5
    }

    // Display Time String
    val displayInstant = Instant.ofEpochMilli(((effectiveJD - 2440587.5) * 86400000.0).toLong())
    val displayTimeStr = DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(ZoneId.of("UTC"))
        .format(displayInstant)

    // Jupiter Scale
    val maxElongationRadii = 32.0

    // --- CACHED DATA (Low Precision for Graph) ---
    val daysSplit = 16
    val cachedData = remember(epochDay, daysInMonth) {
        val pointsPerDay = 24
        val steps = daysInMonth * pointsPerDay
        val moonNames = listOf("Io", "Europa", "Ganymede", "Callisto")
        moonNames.associateWith { name ->
            Array(steps + 1) { i ->
                val tDay = i.toDouble() / pointsPerDay.toDouble()
                val calcJD = startEpoch + tDay + 2440587.5
                // Keep using the existing low-precision calc for the graph lines
                calculateJovianMoons(calcJD)[name]!!
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        // 1. Header
        Row(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Text(text = "Jovian Moons for ", color = LabelColor)
            Text(text = monthYearStr, color = Color.White)
        }

        // 2. Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Io", color = colorIo, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Europa", color = colorEu, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Ganymede", color = colorGa, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Callisto", color = colorCa, modifier = Modifier.padding(horizontal = 8.dp))
        }

        // 3. Main Graphic Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val topSectionH = 120f
                val scaleSectionH = 100f
                val graphSectionH = h - topSectionH - scaleSectionH
                val centerX = w / 2f
                val currentY = topSectionH / 2f

                val textPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT
                }

                // --- TOP SECTION: HIGH PRECISION DIAGRAM ---
                // We use the local high-precision calculator here
                val currentPos = calculateHighPrecisionPositions(effectiveJD)

                val topScalePxPerRad = ((w * 1.15f) / (2 * maxElongationRadii)).toFloat()

                // Jupiter Flattening for Display
                val jFlatFactor = 0.06487
                val jW = topScalePxPerRad * 2f
                val jH = jW * (1.0 - jFlatFactor).toFloat()

                val flipX = if (isEastRight) -1f else 1f
                val flipY = if (isNorthUp) -1f else 1f

                data class DrawOp(val z: Double, val draw: DrawScope.() -> Unit)
                val drawList = mutableListOf<DrawOp>()

                // 1. Jupiter (Z=0)
                drawList.add(DrawOp(0.0) {
                    drawOval(creamColor, topLeft = Offset(centerX - jW/2, currentY - jH/2), size = androidx.compose.ui.geometry.Size(jW, jH))
                    // Bands
                    val bandThickness = jH / 10f
                    val bandWidth = jW * 0.8f
                    val bandXOffset = jW * 0.1f
                    val band1Top = currentY - jH/8 - bandThickness/2
                    val band2Top = currentY + jH/8 - bandThickness/2
                    drawRect(tanColor, topLeft = Offset(centerX - jW/2 + bandXOffset, band1Top), size = androidx.compose.ui.geometry.Size(bandWidth, bandThickness))
                    drawRect(tanColor, topLeft = Offset(centerX - jW/2 + bandXOffset, band2Top), size = androidx.compose.ui.geometry.Size(bandWidth, bandThickness))
                })

                val moonColors = mapOf("Io" to colorIo, "Europa" to colorEu, "Ganymede" to colorGa, "Callisto" to colorCa)
                val mSize = 7.5f; val mHalf = mSize / 2f

                // 2. Shadows (Z slightly > 0)
                moonColors.forEach { (name, _) ->
                    val pos = currentPos[name]!!
                    if (pos.shadowOnDisk) {
                        drawList.add(DrawOp(0.1) {
                            val sx = centerX + (pos.shadowX * topScalePxPerRad * flipX).toFloat()
                            // Shadow Y must be scaled for flattening logic if passed raw
                            // Our HighPrec calculator returns raw Y.
                            // Visual Y should match the flattened disk.
                            // Note: shadowX/Y from HighPrec are projected onto the flattened plane already.
                            val sy = currentY + (pos.shadowY * topScalePxPerRad * flipY).toFloat()
                            drawOval(shadowColor, topLeft = Offset(sx - mHalf, sy - mHalf), size = androidx.compose.ui.geometry.Size(mSize, mSize))
                        })
                    }
                }

                // 3. Moons
                moonColors.forEach { (name, col) ->
                    val pos = currentPos[name]!!
                    if (!pos.eclipsed) {
                        drawList.add(DrawOp(pos.z) {
                            val mx = centerX + (pos.x * topScalePxPerRad * flipX).toFloat()
                            val my = currentY + (pos.y * topScalePxPerRad * flipY).toFloat()
                            drawRect(col, topLeft = Offset(mx - mHalf, my - mHalf), size = androidx.compose.ui.geometry.Size(mSize, mSize))
                        })
                    }
                }

                // Sort and Draw
                drawList.sortBy { it.z }
                drawList.forEach { it.draw(this) }

                // --- GRAPH SECTION (Low Precision Cached) ---
                val col1X = w * 0.25f
                val col2X = w * 0.75f
                val colW = w * 0.5f
                val graphScalePxPerRad = ((colW * 0.85f) / (2 * maxElongationRadii)).toFloat()

                drawLine(gridColor, Offset(w/2f, topSectionH), Offset(w/2f, h), strokeWidth = 1f)
                val jLineOffset = 1.0f * graphScalePxPerRad
                drawLine(creamColor, Offset(col1X - jLineOffset, topSectionH), Offset(col1X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col1X + jLineOffset, topSectionH), Offset(col1X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col2X - jLineOffset, topSectionH), Offset(col2X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col2X + jLineOffset, topSectionH), Offset(col2X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)

                val rowH = graphSectionH / daysSplit
                for (d in 1..daysInMonth) {
                    val isCol1 = d <= daysSplit
                    val dayIndex = if (isCol1) d - 1 else d - (daysSplit + 1)
                    val baseX = if (isCol1) col1X else col2X
                    val yLine = topSectionH + (dayIndex * rowH)
                    val gridLineStartX = baseX - colW/2 + 20f
                    drawLine(Color.DarkGray, Offset(gridLineStartX, yLine), Offset(baseX + colW/2 - 20f, yLine), strokeWidth = 1f)
                }

                cachedData.forEach { (mName, stateArray) ->
                    val path = Path()
                    var penDown = false
                    for (i in stateArray.indices) {
                        val state = stateArray[i] // Low precision state object
                        val tDay = i.toDouble() / 24.0
                        val dayInt = floor(tDay).toInt() + 1
                        if (dayInt > daysInMonth) break
                        val isCol1 = dayInt <= daysSplit
                        val dayIndex = if (isCol1) dayInt - 1 else dayInt - (daysSplit + 1)
                        val frac = tDay - floor(tDay)
                        if (dayInt == (daysSplit + 1) && abs(frac) < 0.001) penDown = false

                        val baseX = if (isCol1) col1X else col2X
                        val yPos = topSectionH + (dayIndex * rowH) + (frac * rowH).toFloat()
                        val xPx = (state.x * graphScalePxPerRad * flipX).toFloat()
                        val screenX = baseX + xPx

                        // Z logic for graph hiding (Low precision state uses z>0 for behind usually)
                        val isBehind = state.z > 0 && abs(state.x) < 1.0
                        if (isBehind) penDown = false
                        else {
                            if (!penDown) { path.moveTo(screenX, yPos); penDown = true }
                            else path.lineTo(screenX, yPos)
                        }
                    }
                    drawPath(path, moonColors[mName]!!, style = Stroke(width = 2f))
                }

                // Current Time Dots on Graph (High Precision for consistency?)
                // No, keep consistency with lines -> use Low Precision or High?
                // Visual consistency implies using the same model as the diagram.
                // But the lines are low precision. If we plot high prec dots on low prec lines, they might drift.
                // Let's use the High Precision pos to match the top diagram.
                val currentFracTotal = effectiveJD - 2440587.5 - startEpoch
                val currentDayInt = floor(currentFracTotal).toInt() + 1
                val currentFrac = currentFracTotal - floor(currentFracTotal)
                val isCol1 = currentDayInt <= daysSplit
                if (currentDayInt <= daysInMonth && currentDayInt >= 1) {
                    val dayIndex = if (isCol1) currentDayInt - 1 else currentDayInt - (daysSplit + 1)
                    val baseX = if (isCol1) col1X else col2X
                    val yPos = topSectionH + (dayIndex * rowH) + (currentFrac * rowH).toFloat()

                    currentPos.forEach { (name, pos) ->
                        val xPx = (pos.x * graphScalePxPerRad * flipX).toFloat()
                        val screenX = baseX + xPx
                        drawCircle(bgColor, radius = 15f, center = Offset(screenX, yPos))
                        drawCircle(moonColors[name]!!, radius = 6f, center = Offset(screenX, yPos))
                    }
                }

                // Day Numbers
                val textVOffset = (textPaint.descent() + textPaint.ascent()) / 2f
                for (d in 1..daysInMonth) {
                    val isCol1 = d <= daysSplit
                    val dayIndex = if (isCol1) d - 1 else d - (daysSplit + 1)
                    val baseX = if (isCol1) col1X else col2X
                    val yLine = topSectionH + (dayIndex * rowH)
                    val gridLineStartX = baseX - colW/2 + 20f
                    drawIntoCanvas {
                        textPaint.textAlign = Paint.Align.LEFT
                        textPaint.textSize = 36f
                        it.nativeCanvas.drawText(d.toString(), gridLineStartX + 10f, yLine - textVOffset, textPaint)
                    }
                }

                // Scale (Bottom)
                val scaleY = topSectionH + graphSectionH + 20f
                val tickStepArcsec = 250.0
                val arcsecPerRadius = (197.0 / AstroEngine.getBodyState("Jupiter", epochDay+2440587.5).distGeo) / 2.0
                val tickStepRad = tickStepArcsec / arcsecPerRadius
                val tickPx = tickStepRad * graphScalePxPerRad

                fun drawScale(cX: Float) {
                    val tickY = scaleY + 10f; val numY = scaleY + 40f
                    drawLine(Color.White, Offset(cX, tickY), Offset(cX, tickY+10f))
                    drawLine(Color.White, Offset(cX - tickPx.toFloat(), tickY), Offset(cX - tickPx.toFloat(), tickY+10f))
                    drawLine(Color.White, Offset(cX + tickPx.toFloat(), tickY), Offset(cX + tickPx.toFloat(), tickY+10f))
                    drawIntoCanvas {
                        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 24f
                        it.nativeCanvas.drawText("0", cX, numY, textPaint)
                        it.nativeCanvas.drawText("250", cX + tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("-250", cX - tickPx.toFloat(), numY, textPaint)
                    }
                }
                drawScale(col1X); drawScale(col2X)
            }
        }

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                Button(
                    onClick = { if (!isAnimating && animDayOffset >= daysInMonth) animDayOffset = 0.0; isAnimating = !isAnimating },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isAnimating) Color.Red else Color.DarkGray, contentColor = Color.White),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text(if (isAnimating) "Stop" else "Animate", fontSize = 14.sp) }
                if (isAnimating || animDayOffset > 0.0) {
                    Text(displayTimeStr, color = Color.White, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = isNorthUp, onClick = { isNorthUp = true }); Text("North Up", color = Color.White, fontSize = 10.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !isNorthUp, onClick = { isNorthUp = false }); Text("South Up", color = Color.White, fontSize = 10.sp) }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !isEastRight, onClick = { isEastRight = false }); Text("East Left", color = Color.White, fontSize = 10.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = isEastRight, onClick = { isEastRight = true }); Text("East Right", color = Color.White, fontSize = 10.sp) }
                }
            }
        }
    }
}

// --- HIGH PRECISION CALCULATOR (Local) ---
// Replicates the physics logic from JovianEventsScreen
private fun calculateHighPrecisionPositions(jd: Double): Map<String, MoonPosHighPrec> {
    // 1. Delta T
    val jdTT = jd + (69.184 / 86400.0)

    val jupBody = AstroEngine.getBodyState("Jupiter", jdTT)
    val deltaAU = jupBody.distGeo

    // 2. Precession
    val T = (jdTT - 2451545.0) / 36525.0
    val precessionDeg = 1.396971 * T + 0.0003086 * T * T
    val jupLamDegDate = jupBody.eclipticLon + precessionDeg
    val jupBetaDeg = jupBody.eclipticLat

    val moons = JovianPrecision.highAccuracyJovSats(jdTT, deltaAU, jupLamDegDate, jupBetaDeg)

    // Shadow Geometry
    val sunState = AstroEngine.getBodyState("Sun", jdTT)
    var raDiff = sunState.ra - jupBody.ra
    while (raDiff < -180) raDiff += 360; while (raDiff > 180) raDiff -= 360
    val shadowSign = if (raDiff > 0) 1.0 else -1.0

    val r = AstroEngine.getBodyState("Earth", jdTT).distSun
    val R = jupBody.distSun
    val Delta = jupBody.distGeo
    val cosAlpha = (R*R + Delta*Delta - r*r) / (2*R*Delta)
    val alpha = acos(cosAlpha.coerceIn(-1.0, 1.0))
    val shadowFactor = tan(alpha)
    val xShiftPerZ = shadowSign * shadowFactor

    val resultMap = mutableMapOf<String, MoonPosHighPrec>()
    val FLATTENING = 0.06487
    val yScale = 1.0 / (1.0 - FLATTENING)

    // Radii for Eclipse check
    val moonRadii = mapOf("Io" to 0.0255, "Europa" to 0.0218, "Ganymede" to 0.0368, "Callisto" to 0.0337)

    for ((name, pos) in moons) {
        val x = pos.x
        val y = pos.y
        val z = -pos.z // Invert Z (Positive = Front/Transit)

        // Shadow Projection
        val sX = x + (z * xShiftPerZ)
        val sY = y
        // Shadow Check on Flattened Disk
        // We project the shadow coordinate onto the flattened system
        val sYScaled = sY * yScale
        val sDistSq = sX*sX + sYScaled*sYScaled
        val shadowOnDisk = (z > 0) && (sDistSq < 1.0) // z>0 means moon is in front, so shadow is thrown back onto disk?
        // Wait. High Precision Model: Z axis is Towards Earth.
        // Transit: Moon in front (Z > 0). Shadow: Moon casts shadow onto background.
        // If Z > 0 (Moon between Earth and Jupiter), shadow is BEHIND the moon, landing on Jupiter. Correct.

        // Eclipse Check (Moon behind Jupiter)
        val mRad = moonRadii[name] ?: 0.0
        val cX = -(z * xShiftPerZ)
        val cY = 0.0
        val distEclipseSq = (x - cX).pow(2) + ((y - cY) * yScale).pow(2)
        val isEclipsed = (z < 0) && (distEclipseSq < (1.0 + mRad).pow(2))

        resultMap[name] = MoonPosHighPrec(x, y, z, sX, sY, shadowOnDisk, isEclipsed)
    }
    return resultMap
}