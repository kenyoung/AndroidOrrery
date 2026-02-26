package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun JovianMoonsScreen(epochDay: Double, currentInstant: Instant) {
    val zoneId = ZoneId.of("UTC")
    val currentDate = currentInstant.atZone(zoneId).toLocalDate()
    val monthYearStr = currentDate.format(DateTimeFormatter.ofPattern("MMMM, yyyy"))

    // Colors
    val bgColor = Color.Black
    val colorIo = jovianMoonColors["Io"]!!
    val colorEu = jovianMoonColors["Europa"]!!
    val colorGa = jovianMoonColors["Ganymede"]!!
    val colorCa = jovianMoonColors["Callisto"]!!
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
                withInfiniteAnimationFrameMillis { _ ->
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
        startEpoch + animDayOffset + UNIX_EPOCH_JD
    } else {
        currentInstant.toEpochMilli() / MILLIS_PER_DAY + UNIX_EPOCH_JD
    }

    // Display Time String
    val displayInstant = Instant.ofEpochMilli(((effectiveJD - UNIX_EPOCH_JD) * MILLIS_PER_DAY).toLong())
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
                val calcJD = startEpoch + tDay + UNIX_EPOCH_JD
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
                val currentPos = calculateHighPrecisionPositions(effectiveJD)
                val flipX = if (isEastRight) -1f else 1f
                val flipY = if (isNorthUp) -1f else 1f
                drawJovianSystem(effectiveJD, centerX, currentY, w, flipX, flipY)

                val moonColors = jovianMoonColors

                // --- GRAPH SECTION (Low Precision Cached) ---
                val col1X = w * 0.25f
                val col2X = w * 0.75f
                val colW = w * 0.5f
                val graphScalePxPerRad = ((colW * 0.85f) / (2 * maxElongationRadii)).toFloat()

                drawLine(gridColor, Offset(w/2f, topSectionH), Offset(w/2f, h), strokeWidth = 1f)
                val jLineOffset = 1.0f * graphScalePxPerRad
                drawLine(jovianCreamColor, Offset(col1X - jLineOffset, topSectionH), Offset(col1X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(jovianCreamColor, Offset(col1X + jLineOffset, topSectionH), Offset(col1X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(jovianCreamColor, Offset(col2X - jLineOffset, topSectionH), Offset(col2X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(jovianCreamColor, Offset(col2X + jLineOffset, topSectionH), Offset(col2X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)

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
                val currentFracTotal = effectiveJD - UNIX_EPOCH_JD - startEpoch
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
                val arcsecPerRadius = (197.0 / AstroEngine.getBodyState("Jupiter", epochDay+UNIX_EPOCH_JD).distGeo) / 2.0
                val tickStepRad = tickStepArcsec / arcsecPerRadius
                val tickPx = tickStepRad * graphScalePxPerRad

                val tickPx500 = 2 * tickPx
                fun drawScale(cX: Float) {
                    val tickY = scaleY + 10f; val numY = scaleY + 40f; val unitY = numY + 24f
                    drawLine(Color.White, Offset(cX, tickY), Offset(cX, tickY+10f))
                    drawLine(Color.White, Offset(cX - tickPx.toFloat(), tickY), Offset(cX - tickPx.toFloat(), tickY+10f))
                    drawLine(Color.White, Offset(cX + tickPx.toFloat(), tickY), Offset(cX + tickPx.toFloat(), tickY+10f))
                    drawLine(Color.White, Offset(cX - tickPx500.toFloat(), tickY), Offset(cX - tickPx500.toFloat(), tickY+10f))
                    drawLine(Color.White, Offset(cX + tickPx500.toFloat(), tickY), Offset(cX + tickPx500.toFloat(), tickY+10f))
                    drawIntoCanvas {
                        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 24f
                        it.nativeCanvas.drawText("0", cX, numY, textPaint)
                        it.nativeCanvas.drawText("250", cX + tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("-250", cX - tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("500", cX + tickPx500.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("-500", cX - tickPx500.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("(arcsec)", cX, unitY, textPaint)
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
            OrientationControls(isNorthUp, isEastRight, { isNorthUp = it }, { isEastRight = it })
        }
    }
}

