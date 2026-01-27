package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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

@Composable
fun JovianMoonsScreen(epochDay: Double, currentInstant: Instant) {
    val zoneId = ZoneId.of("UTC")
    val currentDate = LocalDate.ofEpochDay(epochDay.toLong())
    val monthYearStr = currentDate.format(DateTimeFormatter.ofPattern("MMMM, yyyy"))

    // Colors
    val bgColor = Color.Black
    val creamColor = Color(0xFFFDEEBD)
    // Tan color for Jupiter's cloud bands
    val tanColor = Color(0xFFD2B48C)
    val shadowColor = Color.Black
    // Updated Io to Bright Red
    val colorIo = Color.Red
    val colorEu = Color(0xFF00FF00)
    // Ganymede to Light Blue
    val colorGa = Color(0xFFADD8E6)
    val colorCa = Color(0xFFFFFF00)
    val gridColor = Color.Gray
    val textColor = Color.White

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

    // Prepare Display String for Animation Time
    val displayInstant = Instant.ofEpochMilli(((effectiveJD - 2440587.5) * 86400000.0).toLong())
    val displayTimeStr = DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(ZoneId.of("UTC"))
        .format(displayInstant)

    // Jupiter Scale
    val maxElongationRadii = 32.0
    val jupState = AstroEngine.getBodyState("Jupiter", epochDay + 2440587.5)
    val distAU = jupState.distGeo
    val arcsecPerRadius = (197.0 / distAU) / 2.0

    // --- CACHED PATH GENERATION ---
    val daysSplit = 16

    val cachedData = remember(epochDay, daysInMonth) {
        val pointsPerDay = 24
        val steps = daysInMonth * pointsPerDay
        val moonNames = listOf("Io", "Europa", "Ganymede", "Callisto")
        val precalcStates = moonNames.associateWith { name ->
            Array(steps + 1) { i ->
                val tDay = i.toDouble() / pointsPerDay.toDouble()
                val calcJD = startEpoch + tDay + 2440587.5
                calculateJovianMoons(calcJD)[name]!!
            }
        }
        precalcStates
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        // 1. Header
        Text(
            text = "Jovian Moons for $monthYearStr",
            color = Color(0xFFFFFFE0),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
        )

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

                // Layout
                val topSectionH = 120f
                val scaleSectionH = 100f
                val graphSectionH = h - topSectionH - scaleSectionH

                val centerX = w / 2f

                // Paints
                val textPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT
                }

                // --- TOP SECTION: CURRENT VIEW (Animated) ---
                val currentY = topSectionH / 2f

                // UPDATED SCALE: Increased from 0.9f to 1.15f (approx +25%)
                val topScalePxPerRad = ((w * 1.15f) / (2 * maxElongationRadii)).toFloat()

                val jFlat = 15f / 16f
                val jW = topScalePxPerRad * 2f
                val jH = jW * jFlat

                val flipX = if (isEastRight) -1f else 1f
                val flipY = if (isNorthUp) -1f else 1f

                // Define DrawOp for sorting objects by depth (Painter's Algorithm)
                data class DrawOp(val z: Double, val draw: DrawScope.() -> Unit)
                val drawList = mutableListOf<DrawOp>()

                // 1. Add Jupiter (Z = 0)
                drawList.add(DrawOp(0.0) {
                    drawOval(creamColor, topLeft = Offset(centerX - jW/2, currentY - jH/2), size = androidx.compose.ui.geometry.Size(jW, jH))
                    val bandThickness = jH / 10f
                    val bandWidth = jW * 0.8f
                    val bandXOffset = jW * 0.1f
                    val band1Top = currentY - jH/4 - bandThickness/2
                    val band2Top = currentY + jH/4 - bandThickness/2

                    drawRect(
                        tanColor,
                        topLeft = Offset(centerX - jW/2 + bandXOffset, band1Top),
                        size = androidx.compose.ui.geometry.Size(bandWidth, bandThickness)
                    )
                    drawRect(
                        tanColor,
                        topLeft = Offset(centerX - jW/2 + bandXOffset, band2Top),
                        size = androidx.compose.ui.geometry.Size(bandWidth, bandThickness)
                    )
                })

                val currentPos = calculateJovianMoons(effectiveJD)
                val moonColors = mapOf("Io" to colorIo, "Europa" to colorEu, "Ganymede" to colorGa, "Callisto" to colorCa)

                // Define Moon Size
                val mSize = 7.5f
                val mHalf = mSize / 2f

                // 2. Add Shadows
                moonColors.forEach { (name, _) ->
                    val pos = currentPos[name]!!
                    if (pos.shadowOnDisk) {
                        // With AstroMath.kt fixed, shadowOnDisk implies Z > 0 (Transit).
                        // Place shadow just in front of Jupiter (0.1).
                        drawList.add(DrawOp(0.1) {
                            val sx = centerX + (pos.shadowX * topScalePxPerRad * flipX).toFloat()
                            val sy = currentY + (pos.shadowY * topScalePxPerRad * flipY).toFloat()
                            drawOval(shadowColor, topLeft = Offset(sx - mHalf, sy - mHalf), size = androidx.compose.ui.geometry.Size(mSize, mSize))
                        })
                    }
                }

                // 3. Add Moons (Z = Calculated Z)
                moonColors.forEach { (name, col) ->
                    val pos = currentPos[name]!!
                    // Skip if eclipsed (inside shadow), otherwise add to list
                    if (!pos.eclipsed) {
                        drawList.add(DrawOp(pos.z) {
                            val mx = centerX + (pos.x * topScalePxPerRad * flipX).toFloat()
                            val my = currentY + (pos.y * topScalePxPerRad * flipY).toFloat()
                            drawRect(col, topLeft = Offset(mx - mHalf, my - mHalf), size = androidx.compose.ui.geometry.Size(mSize, mSize))
                        })
                    }
                }

                // 4. Sort and Draw (Low Z first -> High Z last)
                drawList.sortBy { it.z }
                drawList.forEach { it.draw(this) }

                // --- MIDDLE SECTION: GRAPH ---
                val col1X = w * 0.25f
                val col2X = w * 0.75f
                val colW = w * 0.5f
                val graphScalePxPerRad = ((colW * 0.85f) / (2 * maxElongationRadii)).toFloat()

                // Divider
                drawLine(gridColor, Offset(w/2f, topSectionH), Offset(w/2f, h), strokeWidth = 1f)

                // Jupiter Lines
                val jLineOffset = 1.0f * graphScalePxPerRad
                drawLine(creamColor, Offset(col1X - jLineOffset, topSectionH), Offset(col1X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col1X + jLineOffset, topSectionH), Offset(col1X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col2X - jLineOffset, topSectionH), Offset(col2X - jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)
                drawLine(creamColor, Offset(col2X + jLineOffset, topSectionH), Offset(col2X + jLineOffset, topSectionH + graphSectionH), strokeWidth = 2f)

                // Time Lines (Grid Only)
                val rowH = graphSectionH / daysSplit

                for (d in 1..daysInMonth) {
                    val isCol1 = d <= daysSplit
                    val dayIndex = if (isCol1) d - 1 else d - (daysSplit + 1)
                    val baseX = if (isCol1) col1X else col2X
                    val yLine = topSectionH + (dayIndex * rowH)

                    val gridLineStartX = baseX - colW/2 + 20f
                    drawLine(Color.DarkGray, Offset(gridLineStartX, yLine), Offset(baseX + colW/2 - 20f, yLine), strokeWidth = 1f)
                }

                // Draw Paths using Cached Data
                val pointsPerDay = 24

                cachedData.forEach { (mName, stateArray) ->
                    val path = Path()
                    var penDown = false

                    for (i in stateArray.indices) {
                        val state = stateArray[i]
                        val tDay = i.toDouble() / pointsPerDay.toDouble()

                        val dayInt = floor(tDay).toInt() + 1
                        if (dayInt > daysInMonth) break

                        val isCol1 = dayInt <= daysSplit
                        val dayIndex = if (isCol1) dayInt - 1 else dayInt - (daysSplit + 1)
                        val frac = tDay - floor(tDay)

                        if (dayInt == (daysSplit + 1) && abs(frac) < 0.001) {
                            penDown = false
                        }

                        val baseX = if (isCol1) col1X else col2X
                        val yPos = topSectionH + (dayIndex * rowH) + (frac * rowH).toFloat()

                        val xPx = (state.x * graphScalePxPerRad * flipX).toFloat()
                        val screenX = baseX + xPx

                        val isBehind = state.z > 0 && abs(state.x) < 1.0

                        if (isBehind) {
                            penDown = false
                        } else {
                            if (!penDown) {
                                path.moveTo(screenX, yPos)
                                penDown = true
                            } else {
                                path.lineTo(screenX, yPos)
                            }
                        }
                    }
                    val color = moonColors[mName]!!
                    drawPath(path, color, style = Stroke(width = 2f))
                }

                // Current Time Dots (Animated)
                val currentFracTotal = effectiveJD - 2440587.5 - startEpoch
                val currentDayInt = floor(currentFracTotal).toInt() + 1
                val currentFrac = currentFracTotal - floor(currentFracTotal)

                val isCol1 = currentDayInt <= daysSplit
                if (currentDayInt <= daysInMonth && currentDayInt >= 1) {
                    val dayIndex = if (isCol1) currentDayInt - 1 else currentDayInt - (daysSplit + 1)
                    val baseX = if (isCol1) col1X else col2X
                    val yPos = topSectionH + (dayIndex * rowH) + (currentFrac * rowH).toFloat()

                    val cPos = currentPos
                    cPos.forEach { (name, state) ->
                        val xPx = (state.x * graphScalePxPerRad * flipX).toFloat()
                        val screenX = baseX + xPx
                        drawCircle(bgColor, radius = 15f, center = Offset(screenX, yPos))
                        drawCircle(moonColors[name]!!, radius = 6f, center = Offset(screenX, yPos))
                    }
                }

                // Draw Day Numbers (Last)
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
                        val labelX = gridLineStartX + 10f
                        it.nativeCanvas.drawText(d.toString(), labelX, yLine - textVOffset, textPaint)
                    }
                }

                // --- BOTTOM SECTION: SCALE ---
                val scaleY = topSectionH + graphSectionH + 20f
                val scaleCenter1 = col1X
                val scaleCenter2 = col2X

                // Ticks (Drawn FIRST, above text)
                val tickStepArcsec = 250.0
                val tickStepRad = tickStepArcsec / arcsecPerRadius
                val tickPx = tickStepRad * graphScalePxPerRad

                fun drawScale(cX: Float) {
                    val tickY = scaleY + 10f // Moved up
                    val numY = scaleY + 40f  // Moved up
                    // 0
                    drawLine(Color.White, Offset(cX, tickY), Offset(cX, tickY + 10f), strokeWidth = 1f)
                    // +/- 250
                    drawLine(Color.White, Offset(cX - tickPx.toFloat(), tickY), Offset(cX - tickPx.toFloat(), tickY + 10f), strokeWidth = 1f)
                    drawLine(Color.White, Offset(cX + tickPx.toFloat(), tickY), Offset(cX + tickPx.toFloat(), tickY + 10f), strokeWidth = 1f)
                    // +/- 500
                    drawLine(Color.White, Offset(cX - 2*tickPx.toFloat(), tickY), Offset(cX - 2*tickPx.toFloat(), tickY + 10f), strokeWidth = 1f)
                    drawLine(Color.White, Offset(cX + 2*tickPx.toFloat(), tickY), Offset(cX + 2*tickPx.toFloat(), tickY + 10f), strokeWidth = 1f)

                    drawIntoCanvas {
                        textPaint.textAlign = Paint.Align.CENTER
                        textPaint.textSize = 24f // Reset from day numbers

                        it.nativeCanvas.drawText("0", cX, numY, textPaint)
                        it.nativeCanvas.drawText("250", cX + tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("500", cX + 2*tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("-250", cX - tickPx.toFloat(), numY, textPaint)
                        it.nativeCanvas.drawText("-500", cX - 2*tickPx.toFloat(), numY, textPaint)
                    }
                }
                drawScale(scaleCenter1)
                drawScale(scaleCenter2)

                // Text Label (Drawn BELOW scale)
                val labelY = scaleY + 70f // Below numbers
                drawIntoCanvas {
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = 24f
                    val dirStr = if(isEastRight) "west" else "east"
                    val label = "arc seconds $dirStr"

                    it.nativeCanvas.drawText(label, scaleCenter1, labelY, textPaint)
                    it.nativeCanvas.drawText(label, scaleCenter2, labelY, textPaint)
                }
            }
        }

        // 4. Bottom Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Animation Button and Time Display
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Button(
                    onClick = {
                        if (!isAnimating && animDayOffset >= daysInMonth) animDayOffset = 0.0
                        isAnimating = !isAnimating
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAnimating) Color.Red else Color.DarkGray,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (isAnimating) "Stop" else "Animate", fontSize = 14.sp)
                }

                if (isAnimating || animDayOffset > 0.0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displayTimeStr,
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }

            // Right: Orientation Controls (Stacked Columns)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Vertical Controls Column
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isNorthUp, onClick = { isNorthUp = true })
                        Text("North Up", color = Color.White, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !isNorthUp, onClick = { isNorthUp = false })
                        Text("South Up", color = Color.White, fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Horizontal Controls Column
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !isEastRight, onClick = { isEastRight = false })
                        Text("East Left", color = Color.White, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isEastRight, onClick = { isEastRight = true })
                        Text("East Right", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}