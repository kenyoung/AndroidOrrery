package com.kenyoung.orrery

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

// Neptune globe and display constants
private const val NEPTUNE_POLAR_RATIO = 0.9829f
private val colorNeptune = Color(0xFF6699FF)

private val neptuneMoonColors = mapOf(
    "Triton" to Color(0xFFFF69B4)
)

// Atmospheric bands — more visible than Uranus
private data class NeptuneAtmoBand(val latSouth: Float, val latNorth: Float, val color: Color)
private val neptuneAtmoBands = listOf(
    NeptuneAtmoBand(-90f, -60f, Color(0x40FFFFFF)),   // south polar brightening
    NeptuneAtmoBand(-30f, -10f, Color(0x40000040)),   // south equatorial dark
    NeptuneAtmoBand( 10f,  30f, Color(0x40000040)),   // north equatorial dark
    NeptuneAtmoBand( 60f,  90f, Color(0x30FFFFFF)),    // north polar
)

@Composable
fun NeptuneScreen(
    obs: ObserverState,
    resetAnimTrigger: Int = 0,
    onAnimStoppedChange: (Boolean) -> Unit = {},
    onTimeDisplayChange: (Boolean) -> Unit
) {
    val currentInstant = obs.now
    val stdOffsetHours = obs.stdOffsetHours; val stdTimeLabel = obs.stdTimeLabel
    val useLocalTime = obs.useStandardTime; val useDst = obs.useDst

    val zoneId: ZoneId = if (useLocalTime)
        ZoneOffset.ofTotalSeconds((stdOffsetHours * 3600).roundToInt())
    else ZoneOffset.UTC
    val timeLabel = if (useLocalTime) " $stdTimeLabel" else " UT"

    val currentDate = currentInstant.atZone(zoneId).toLocalDate()
    val monthYearStr = currentDate.format(DateTimeFormatter.ofPattern("MMMM, yyyy"))

    // --- ORIENTATION STATE ---
    var isNorthUp by remember { mutableStateOf(true) }
    var isEastRight by remember { mutableStateOf(false) }

    // --- ANIMATION STATE ---
    var isAnimating by remember { mutableStateOf(false) }
    var animDayOffset by remember { mutableStateOf(0.0) }

    LaunchedEffect(isAnimating, animDayOffset) {
        onAnimStoppedChange(!isAnimating && animDayOffset > 0.0)
    }
    LaunchedEffect(resetAnimTrigger) {
        if (resetAnimTrigger > 0) { animDayOffset = 0.0; isAnimating = false }
    }

    var animBaseJD by remember { mutableStateOf(0.0) }

    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            if (animDayOffset == 0.0) {
                animBaseJD = currentInstant.toEpochMilli() / MILLIS_PER_DAY + UNIX_EPOCH_JD
            }
            val startTime = System.nanoTime()
            val startOffset = animDayOffset
            while (isAnimating) {
                withInfiniteAnimationFrameMillis { _ ->
                    val now = System.nanoTime()
                    val elapsedSeconds = (now - startTime) / 1_000_000_000.0
                    animDayOffset = startOffset + elapsedSeconds
                }
            }
        }
    }

    val effectiveJD = if (isAnimating || animDayOffset > 0.0) {
        animBaseJD + animDayOffset
    } else {
        currentInstant.toEpochMilli() / MILLIS_PER_DAY + UNIX_EPOCH_JD
    }

    val displayInstant = Instant.ofEpochMilli(((effectiveJD - UNIX_EPOCH_JD) * MILLIS_PER_DAY).toLong())
    val displayTimeStr = DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(zoneId)
        .format(displayInstant) + timeLabel

    // --- Compute Neptune system data ---
    val neptuneData = remember(effectiveJD) {
        NeptuneMoonEngine.getNeptuneSystemData(effectiveJD)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Neptune System — ", color = LabelColor, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(monthYearStr, color = Color.White, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        // Moon color legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            neptuneMoonColors.forEach { (name, color) ->
                Text(name, color = color, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }

        // Date/time display
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(displayTimeStr, color = Color.White, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        // Main Canvas
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawNeptuneSystem(neptuneData, isNorthUp, isEastRight)
            }
            // Info box in upper left
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)
            ) {
                val infoSize = 7.sp
                val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                val angDiamArcsec = neptuneData.angularRadiusArcsec * 2.0
                val mag = -6.87 + 5.0 * log10(neptuneData.distSun * neptuneData.distGeo)
                Text("Sub-Earth %.1f°".format(neptuneData.subEarthLat),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Dist %.2f AU".format(neptuneData.distGeo),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Eq diam %.1f\"".format(angDiamArcsec),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Mag %.1f".format(mag),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                neptuneData.moons.forEach { moon ->
                    if (moon.behindDisk) {
                        val moonColor = neptuneMoonColors[moon.name] ?: Color.White
                        Text("${moon.name} Occulted",
                            color = moonColor, fontSize = infoSize, fontFamily = mono)
                    }
                }
            }
        }

        // Controls row: Animate + Orientation
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Button(
                    onClick = { isAnimating = !isAnimating },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAnimating) Color.Red else Color.DarkGray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(if (isAnimating) "Stop" else "Animate", fontSize = 14.sp)
                }
            }
            OrientationControls(isNorthUp, isEastRight, { isNorthUp = it }, { isEastRight = it })
        }

        // Time display toggle
        TimeDisplayToggle(useLocalTime, useDst, onTimeDisplayChange)
    }
}

// --- Canvas drawing ---

private fun DrawScope.drawNeptuneSystem(
    data: NeptuneSystemData,
    isNorthUp: Boolean,
    isEastRight: Boolean
) {
    withDensityScaling { w, h ->
    val centerX = w / 2f
    val centerY = h / 2f

    val flipE = if (isEastRight) 1f else -1f
    val flipN = if (isNorthUp) -1f else 1f

    // Fixed scale: fit Triton's orbit (354759 km / 24764 km ≈ 14.33 Neptune radii)
    val tritonMaxArcsec = 14.33 * data.angularRadiusArcsec
    val margin = max(5.0, 0.15 * tritonMaxArcsec)
    val fieldArcsec = (tritonMaxArcsec + margin).toFloat()
    val pxPerArcsec = min(w, h) / (2f * fieldArcsec)

    val diskRadiusAs = data.angularRadiusArcsec.toFloat()

    // Position angle for globe orientation
    val paRad = Math.toRadians(data.positionAngleP).toFloat()
    val cosPA = cos(paRad)
    val sinPA = sin(paRad)

    fun toScreen(eastArcsec: Float, northArcsec: Float): Offset {
        val sx = eastArcsec * pxPerArcsec * flipE
        val sy = northArcsec * pxPerArcsec * flipN
        return Offset(centerX + sx, centerY + sy)
    }

    fun toScreenPA(xPlanet: Float, yPlanet: Float): Offset {
        val eastA = xPlanet * cosPA - yPlanet * sinPA
        val northA = xPlanet * sinPA + yPlanet * cosPA
        return toScreen(eastA, northA)
    }

    val subEarthLat = data.subEarthLat

    // --- Limb-darkened globe ---
    val limbSteps = 15
    val limbU = 0.5f
    for (i in 0 until limbSteps) {
        val r = 1.0f - i.toFloat() / limbSteps
        val cosTheta = sqrt(1.0f - r * r)
        val brightness = 1.0f - limbU * (1.0f - cosTheta)
        val color = Color(
            colorNeptune.red * brightness,
            colorNeptune.green * brightness,
            colorNeptune.blue * brightness
        )
        val rx = diskRadiusAs * r
        val ry = diskRadiusAs * NEPTUNE_POLAR_RATIO * r
        drawPath(buildNeptuneEllipsePathPA(rx, ry, 100, ::toScreenPA), color)
    }

    // --- Atmospheric bands ---
    val globeClip = buildNeptuneEllipsePathPA(diskRadiusAs, diskRadiusAs * NEPTUNE_POLAR_RATIO, 100, ::toScreenPA)
    drawNeptuneAtmoBands(diskRadiusAs, NEPTUNE_POLAR_RATIO, subEarthLat, globeClip, ::toScreenPA)

    // --- Moon dot ---
    data.moons.forEach { moon ->
        if (!moon.behindDisk) {
            val color = neptuneMoonColors[moon.name] ?: Color.White
            val screen = toScreen(moon.eastArcsec.toFloat(), moon.northArcsec.toFloat())
            drawCircle(color, radius = 4f, center = screen)
        }
    }

    // --- Scale bar ---
    val niceValues = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
    val targetPx = w * 0.25f
    val targetArcsec = targetPx / pxPerArcsec
    val scaleArcsec = niceValues.minByOrNull { abs(it - targetArcsec) } ?: 10.0
    val barPx = (scaleArcsec * pxPerArcsec).toFloat()

    val barY = h - 20f
    val barX0 = (w - barPx) / 2f
    val barX1 = barX0 + barPx
    val tickH = 6f

    drawLine(Color.White, Offset(barX0, barY), Offset(barX1, barY), strokeWidth = 1.5f)
    drawLine(Color.White, Offset(barX0, barY - tickH), Offset(barX0, barY + tickH), strokeWidth = 1.5f)
    drawLine(Color.White, Offset(barX1, barY - tickH), Offset(barX1, barY + tickH), strokeWidth = 1.5f)

    val labelText = if (scaleArcsec >= 1.0) "${scaleArcsec.toInt()}\"" else "$scaleArcsec\""
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.nativeCanvas.drawText(labelText, (barX0 + barX1) / 2f, barY - tickH - 4f, paint)
    }
    }
}

// Atmospheric bands
private fun DrawScope.drawNeptuneAtmoBands(
    diskRadiusAs: Float,
    polarRatio: Float,
    subEarthLatDeg: Double,
    globeClip: Path,
    toScreen: (Float, Float) -> Offset
) {
    val featherDeg = 5f
    val featherSteps = 6
    clipPath(globeClip) {
        for (band in neptuneAtmoBands) {
            val feather = min(featherDeg, (band.latNorth - band.latSouth) / 4f)
            for (i in 0 until featherSteps) {
                val t = (i + 0.5f) / featherSteps
                val subLatS = band.latSouth + feather * i / featherSteps
                val subLatN = band.latSouth + feather * (i + 1) / featherSteps
                drawNeptuneBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    subEarthLatDeg, polarRatio, diskRadiusAs, toScreen)
            }
            val coreSouth = band.latSouth + feather
            val coreNorth = band.latNorth - feather
            if (coreNorth > coreSouth) {
                drawNeptuneBandStrip(coreSouth, coreNorth, band.color,
                    subEarthLatDeg, polarRatio, diskRadiusAs, toScreen)
            }
            for (i in 0 until featherSteps) {
                val t = 1f - (i + 0.5f) / featherSteps
                val subLatS = band.latNorth - feather + feather * i / featherSteps
                val subLatN = band.latNorth - feather + feather * (i + 1) / featherSteps
                drawNeptuneBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    subEarthLatDeg, polarRatio, diskRadiusAs, toScreen)
            }
        }
    }
}

private fun DrawScope.drawNeptuneBandStrip(
    latSouthDeg: Float, latNorthDeg: Float, color: Color,
    tiltBDeg: Double, polarRatio: Float, diskRadiusAs: Float,
    toScreen: (Float, Float) -> Offset
) {
    val bRad = Math.toRadians(tiltBDeg)
    val sinB = sin(bRad).toFloat()
    val cosB = cos(bRad).toFloat()
    val p = polarRatio
    val steps = 40

    fun latPoint(latDeg: Float, alpha: Double): Pair<Float, Float> {
        val phi = Math.toRadians(latDeg.toDouble())
        val cosPhi = cos(phi).toFloat()
        val sinPhi = sin(phi).toFloat()
        val yLimb = p * sinPhi
        val deltaY = p * sinPhi * (1f - cosB) + cosPhi * sinB
        val x = cosPhi * cos(alpha).toFloat() * diskRadiusAs
        val y = (yLimb - deltaY * sin(alpha).toFloat()) * diskRadiusAs
        return Pair(x, y)
    }

    val path = Path()
    for (k in 0..steps) {
        val alpha = PI * k / steps
        val (x, y) = latPoint(latNorthDeg, alpha)
        val screen = toScreen(x, y)
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    for (k in 0..steps) {
        val alpha = PI * (steps - k).toDouble() / steps
        val (x, y) = latPoint(latSouthDeg, alpha)
        val screen = toScreen(x, y)
        path.lineTo(screen.x, screen.y)
    }
    path.close()
    drawPath(path, color)
}

private fun buildNeptuneEllipsePathPA(
    radiusX: Float, radiusY: Float, steps: Int,
    toScreen: (Float, Float) -> Offset
): Path {
    val path = Path()
    for (k in 0..steps) {
        val theta = 2 * PI * k / steps
        val screen = toScreen((radiusX * cos(theta)).toFloat(), (radiusY * sin(theta)).toFloat())
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    path.close()
    return path
}
