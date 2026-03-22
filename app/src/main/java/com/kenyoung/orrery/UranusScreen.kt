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
import androidx.compose.ui.graphics.drawscope.Stroke
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

// Uranus globe and display constants
private const val URANUS_POLAR_RATIO = 0.9771f
private val colorUranus = Color(0xFFAFEEEE)

private val uranusMoonColors = mapOf(
    "Miranda" to Color(0xFFFF69B4),
    "Ariel"   to Color(0xFF00FFFF),
    "Umbriel" to Color(0xFFFFD700),
    "Titania" to Color(0xFF00FF00),
    "Oberon"  to Color(0xFFFFA500)
)

// Subtle atmospheric bands
private data class UranusAtmoBand(val latSouth: Float, val latNorth: Float, val color: Color)
private val uranusAtmoBands = listOf(
    UranusAtmoBand(-90f, -45f, Color(0x0C2E5E6E)),
    UranusAtmoBand( 45f,  90f, Color(0x0C2E5E6E)),
)

// Ring definitions
private data class RingDef(val radiusRu: Float, val color: Color, val strokeWidth: Float)
private val uranusRings = listOf(
    RingDef(UranusMoonEngine.RING_6,       Color(0x40808080), 1.0f),
    RingDef(UranusMoonEngine.RING_5,       Color(0x40808080), 1.0f),
    RingDef(UranusMoonEngine.RING_ALPHA,   Color(0x50909090), 1.0f),
    RingDef(UranusMoonEngine.RING_BETA,    Color(0x50909090), 1.0f),
    RingDef(UranusMoonEngine.RING_ETA,     Color(0x50909090), 1.0f),
    RingDef(UranusMoonEngine.RING_GAMMA,   Color(0x60A0A0A0), 1.0f),
    RingDef(UranusMoonEngine.RING_DELTA,   Color(0x60A0A0A0), 1.0f),
    RingDef(UranusMoonEngine.RING_EPSILON, Color(0x80C0C0C0), 1.5f)
)

@Composable
fun UranusScreen(
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

    // --- Compute Uranus system data ---
    val uranusData = remember(effectiveJD) {
        UranusMoonEngine.getUranusSystemData(effectiveJD)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Uranus System — ", color = LabelColor, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(monthYearStr, color = Color.White, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        // Moon color legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            uranusMoonColors.forEach { (name, color) ->
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
                drawUranusSystem(uranusData, isNorthUp, isEastRight)
            }
            // Info box in upper left
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)
            ) {
                val infoSize = 7.sp
                val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                val angDiamArcsec = uranusData.angularRadiusArcsec * 2.0
                val mag = -7.19 + 5.0 * log10(uranusData.distSun * uranusData.distGeo)
                Text("Ring tilt %.1f°".format(uranusData.ringTiltB),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Dist %.2f AU".format(uranusData.distGeo),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Eq diam %.1f\"".format(angDiamArcsec),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Mag %.1f".format(mag),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                uranusData.moons.forEach { moon ->
                    if (moon.behindDisk) {
                        val moonColor = uranusMoonColors[moon.name] ?: Color.White
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

private fun DrawScope.drawUranusSystem(
    data: UranusSystemData,
    isNorthUp: Boolean,
    isEastRight: Boolean
) {
    withDensityScaling { w, h ->
    val centerX = w / 2f
    val centerY = h / 2f

    // Flip conventions: East Left (default) means east is left on screen = positive x.
    // Moon eastArcsec is positive east. With East Left: east = +screen X.
    // flipEast: +1 for East Left (east = screen right? no...).
    // Actually: in astronomy, "East Left" means east is on the LEFT side of the image.
    // Screen +X = right. So east (+eastArcsec) should map to screen LEFT = -X.
    // With isEastRight=false (East Left): screenX = centerX - eastArcsec * pxPerArcsec
    // With isEastRight=true (East Right): screenX = centerX + eastArcsec * pxPerArcsec
    val flipE = if (isEastRight) 1f else -1f
    // North Up: north (+northArcsec) should map to screen UP = -Y
    val flipN = if (isNorthUp) -1f else 1f

    // Fixed scale: fit Oberon's full orbit (583511 km = 22.83 Uranus radii)
    // regardless of current moon positions, so scale never changes during animation.
    val oberonMaxArcsec = 22.83 * data.angularRadiusArcsec
    val margin = max(5.0, 0.15 * oberonMaxArcsec)
    val fieldArcsec = (oberonMaxArcsec + margin).toFloat()
    val pxPerArcsec = min(w, h) / (2f * fieldArcsec)

    // Planet disk size in arcseconds (toScreenPA converts to pixels)
    val diskRadiusAs = data.angularRadiusArcsec.toFloat()

    // Position angle for ring/globe orientation
    val paRad = Math.toRadians(data.positionAngleP).toFloat()
    // PA rotation applied to globe/ring drawing (relative to sky N/E frame)
    val cosPA = cos(paRad)
    val sinPA = sin(paRad)

    // Transform arcsec (east, north) to screen coordinates
    fun toScreen(eastArcsec: Float, northArcsec: Float): Offset {
        val sx = eastArcsec * pxPerArcsec * flipE
        val sy = northArcsec * pxPerArcsec * flipN
        return Offset(centerX + sx, centerY + sy)
    }

    // Transform planet-frame (x along PA-rotated axis) to screen
    // Used for globe and rings which need PA rotation
    fun toScreenPA(xPlanet: Float, yPlanet: Float): Offset {
        // xPlanet is perpendicular to pole projection, yPlanet is along pole projection
        // Rotate by PA to get sky east/north, then apply flips
        val eastA = xPlanet * cosPA - yPlanet * sinPA
        val northA = xPlanet * sinPA + yPlanet * cosPA
        return toScreen(eastA, northA)
    }

    val tiltB = data.ringTiltB
    val sinB = abs(sin(Math.toRadians(tiltB))).toFloat()

    // --- Limb-darkened globe ---
    val limbSteps = 15
    val limbU = 0.5f
    fun drawGlobe() {
        for (i in 0 until limbSteps) {
            val r = 1.0f - i.toFloat() / limbSteps
            val cosTheta = sqrt(1.0f - r * r)
            val brightness = 1.0f - limbU * (1.0f - cosTheta)
            val color = Color(
                colorUranus.red * brightness,
                colorUranus.green * brightness,
                colorUranus.blue * brightness
            )
            val rx = diskRadiusAs * r
            val ry = diskRadiusAs * URANUS_POLAR_RATIO * r
            drawPath(buildEllipsePathPA(rx, ry, 100, ::toScreenPA), color)
        }
    }

    // --- Ring half (thin line) ---
    fun drawRingHalf(ring: RingDef, isNorthHalf: Boolean) {
        val rAs = ring.radiusRu * data.angularRadiusArcsec.toFloat()
        val rAsY = rAs * sinB
        val steps = 100
        val startAngle = if (isNorthHalf) 0.0 else PI
        val path = Path()
        for (k in 0..steps) {
            val theta = startAngle + PI * k / steps
            val screen = toScreenPA((rAs * cos(theta)).toFloat(), (rAsY * sin(theta)).toFloat())
            if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        drawPath(path, ring.color, style = Stroke(width = ring.strokeWidth))
    }

    val ringsVisible = sinB * diskRadiusAs * pxPerArcsec >= 0.5f
    val backIsNorthHalf = tiltB > 0

    if (!ringsVisible) {
        drawGlobe()
        val globeClip = buildEllipsePathPA(diskRadiusAs, diskRadiusAs * URANUS_POLAR_RATIO, 100, ::toScreenPA)
        drawAtmoBands(diskRadiusAs, URANUS_POLAR_RATIO, tiltB, globeClip, ::toScreenPA)
    } else {
        // Back rings
        uranusRings.forEach { ring -> drawRingHalf(ring, backIsNorthHalf) }
        // Globe
        drawGlobe()
        val globeClip = buildEllipsePathPA(diskRadiusAs, diskRadiusAs * URANUS_POLAR_RATIO, 100, ::toScreenPA)
        drawAtmoBands(diskRadiusAs, URANUS_POLAR_RATIO, tiltB, globeClip, ::toScreenPA)
        // Front rings
        uranusRings.forEach { ring -> drawRingHalf(ring, !backIsNorthHalf) }
    }

    // --- Moon dots ---
    data.moons.forEach { moon ->
        if (!moon.behindDisk) {
            val color = uranusMoonColors[moon.name] ?: Color.White
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

// Atmospheric bands — same approach as Saturn but with Uranus-specific data
private fun DrawScope.drawAtmoBands(
    diskRadiusAs: Float,
    polarRatio: Float,
    tiltBDeg: Double,
    globeClip: Path,
    toScreen: (Float, Float) -> Offset
) {
    val featherDeg = 5f
    val featherSteps = 6
    clipPath(globeClip) {
        for (band in uranusAtmoBands) {
            val feather = min(featherDeg, (band.latNorth - band.latSouth) / 4f)
            for (i in 0 until featherSteps) {
                val t = (i + 0.5f) / featherSteps
                val subLatS = band.latSouth + feather * i / featherSteps
                val subLatN = band.latSouth + feather * (i + 1) / featherSteps
                drawBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    tiltBDeg, polarRatio, diskRadiusAs, toScreen)
            }
            val coreSouth = band.latSouth + feather
            val coreNorth = band.latNorth - feather
            if (coreNorth > coreSouth) {
                drawBandStrip(coreSouth, coreNorth, band.color,
                    tiltBDeg, polarRatio, diskRadiusAs, toScreen)
            }
            for (i in 0 until featherSteps) {
                val t = 1f - (i + 0.5f) / featherSteps
                val subLatS = band.latNorth - feather + feather * i / featherSteps
                val subLatN = band.latNorth - feather + feather * (i + 1) / featherSteps
                drawBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    tiltBDeg, polarRatio, diskRadiusAs, toScreen)
            }
        }
    }
}

private fun DrawScope.drawBandStrip(
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

private fun buildEllipsePathPA(
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
