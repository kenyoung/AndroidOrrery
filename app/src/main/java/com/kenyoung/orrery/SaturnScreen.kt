package com.kenyoung.orrery

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

// Moon display colors
private val saturnMoonColors = mapOf(
    "Enceladus" to Color.Cyan,
    "Tethys" to Color.Yellow,
    "Dione" to Color(0xFF00FF00),
    "Rhea" to Color(0xFFFFA500),
    "Titan" to Color.White,
    "Iapetus" to Color.Red
)

// Saturn and ring colors (matching Python original)
private val colorSaturn = Color(0xFFC5AB74)
private val colorRingA = Color.LightGray
private val colorRingB = Color.White
private val colorRingC = Color.Gray.copy(alpha = 0.50f)  // Crepe Ring: translucent gray

// Atmospheric band definitions: Saturnographic latitude boundaries and overlay tint.
// Dark bands use semi-transparent dark brown; equatorial zone uses warm yellow.
private data class AtmoBand(val latSouth: Float, val latNorth: Float, val color: Color)

private val saturnAtmoBands = listOf(
    AtmoBand(-90f, -68f, Color(0x20304058)),   // South polar region (blue-gray)
    AtmoBand(-56f, -38f, Color(0x38301808)),   // South temperate belt (moderate)
    AtmoBand(-28f,  -5f, Color(0x4C381C08)),   // South equatorial belt (strongest)
    AtmoBand( -5f,  12f, Color(0x1EFFE880)),   // Equatorial zone (warm/bright)
    AtmoBand( 12f,  28f, Color(0x42341A08)),   // North equatorial belt (strong)
    AtmoBand( 40f,  54f, Color(0x2C281408)),   // North temperate belt (subtle)
    AtmoBand( 68f,  90f, Color(0x20304058)),   // North polar region (blue-gray)
)

@Composable
fun SaturnScreen(
    epochDay: Double,
    currentInstant: Instant,
    stdOffsetHours: Double,
    stdTimeLabel: String,
    useLocalTime: Boolean,
    resetAnimTrigger: Int = 0,
    onAnimStoppedChange: (Boolean) -> Unit = {},
    onTimeDisplayChange: (Boolean) -> Unit
) {
    // --- TIME ZONE ---
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

    // Report stopped state to parent; reset on trigger from parent
    LaunchedEffect(isAnimating, animDayOffset) {
        onAnimStoppedChange(!isAnimating && animDayOffset > 0.0)
    }
    LaunchedEffect(resetAnimTrigger) {
        if (resetAnimTrigger > 0) { animDayOffset = 0.0; isAnimating = false }
    }

    val daysInMonth = remember(currentDate) { currentDate.lengthOfMonth() }
    val startOfMonth = remember(currentDate) { currentDate.withDayOfMonth(1) }
    val startEpoch = remember(currentDate) { startOfMonth.toEpochDay().toDouble() }

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

    val effectiveJD = if (isAnimating || animDayOffset > 0.0) {
        startEpoch + animDayOffset + UNIX_EPOCH_JD
    } else {
        currentInstant.toEpochMilli() / MILLIS_PER_DAY + UNIX_EPOCH_JD
    }

    val displayInstant = Instant.ofEpochMilli(((effectiveJD - UNIX_EPOCH_JD) * MILLIS_PER_DAY).toLong())
    val displayTimeStr = DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(zoneId)
        .format(displayInstant) + timeLabel

    // --- Compute Saturn system data ---
    val saturnData = remember(effectiveJD) {
        SaturnMoonEngine.getSaturnSystemData(effectiveJD)
    }

    // --- PINCH-TO-ZOOM STATE ---
    // zFactor: zoom in/out so the most displaced moon (after PA rotation) sits near the edge.
    // FOV half-extent = 22 / zFactor Saturn radii.
    val zFactor = run {
        val paRad = Math.toRadians(-saturnData.positionAngleP)
        val cosP = cos(paRad)
        val sinP = sin(paRad)
        var maxExtent = 0.0
        saturnData.moons.forEach { moon ->
            val rx = abs(moon.x * cosP - moon.y * sinP)
            val ry = abs(moon.x * sinP + moon.y * cosP)
            if (rx > maxExtent) maxExtent = rx
            if (ry > maxExtent) maxExtent = ry
        }
        if (maxExtent > 0.001) (22.0 / (maxExtent * 1.05)).toFloat() else 1f
    }
    var scale by remember { mutableStateOf(zFactor) }
    var hasZoomed by remember { mutableStateOf(false) }
    // Recalculate zoom when date/time changes via the Date and Time dialog,
    // but not during animation and not after the user has pinch-zoomed.
    LaunchedEffect(zFactor) {
        if (!hasZoomed && !isAnimating && animDayOffset == 0.0) scale = zFactor
    }
    val minScale = 0.3f
    val maxScale = 8f

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Saturn System — ", color = LabelColor, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(monthYearStr, color = Color.White, fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        // Moon color legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            saturnMoonColors.forEach { (name, color) ->
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

        // Main Canvas with pinch-to-zoom
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(minScale, maxScale)
                        if (!hasZoomed) hasZoomed = true
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSaturnSystem(saturnData, scale, isNorthUp, isEastRight)
            }
            // Info box in upper left
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)
            ) {
                val infoSize = 7.sp
                val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                val angDiamArcsec = Math.toDegrees(saturnData.angularRadiusRad) * 2.0 * 3600.0
                // Saturn magnitude: Meeus formula V = -8.95 + 5·log10(r·Δ) + 0.044·|α|
                // with ring correction. Use simplified: add ring brightness term from B
                val phaseAngle = 0.0 // negligible for Saturn (< 6°)
                val mag = -8.95 + 5.0 * kotlin.math.log10(saturnData.distSun * saturnData.distGeo) +
                        0.044 * phaseAngle -
                        2.6 * abs(sin(Math.toRadians(saturnData.ringTiltB))) +
                        1.2 * sin(Math.toRadians(saturnData.ringTiltB)).let { it * it }
                Text("Ring tilt %.1f°".format(saturnData.ringTiltB),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Dist %.2f AU".format(saturnData.distGeo),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Eq diam %.1f\"".format(angDiamArcsec),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
                Text("Mag %.1f".format(mag),
                    color = Color.White, fontSize = infoSize, fontFamily = mono)
            }
        }

        if (!hasZoomed) {
            Text("Pinch to zoom", color = LabelColor, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
                    onClick = {
                        if (!isAnimating && animDayOffset >= daysInMonth) animDayOffset = 0.0
                        isAnimating = !isAnimating
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAnimating) Color.Red else Color.DarkGray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(if (isAnimating) "Stop" else "Animate", fontSize = 14.sp)
                }
                if (isAnimating || animDayOffset > 0.0) {
                    Text(displayTimeStr, color = Color.White, fontSize = 12.sp)
                }
            }
            OrientationControls(isNorthUp, isEastRight, { isNorthUp = it }, { isEastRight = it })
        }

        // Time display toggle
        TimeDisplayToggle(useLocalTime, onTimeDisplayChange)
    }
}

// --- Shadow computation helpers ---
// All work in Saturn equatorial frame (X,Z = ring plane, Y = north pole).
// Output is in Meeus 2D frame (X+ = west, Y+ = north) in Saturn radii.

private data class Vec3(val x: Double, val y: Double, val z: Double)

private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

// Compute Meeus projection basis from earthDir (Saturn equatorial frame)
private fun computeMeeusFrameBasis(earthDir: Vector3): Triple<Vec3, Vec3, Vec3> {
    val ed = Vec3(earthDir.x, earthDir.y, earthDir.z)
    // Z_meeus = earthDir (line of sight)
    // Y_meeus = normalize((0,1,0) - earthDir.y * earthDir)  — north projected onto sky plane
    val rawYx = -ed.y * ed.x
    val rawYy = 1.0 - ed.y * ed.y
    val rawYz = -ed.y * ed.z
    val yLen = sqrt(rawYx * rawYx + rawYy * rawYy + rawYz * rawYz)
    val yMeeus = if (yLen > 1e-10) Vec3(rawYx / yLen, rawYy / yLen, rawYz / yLen)
    else Vec3(0.0, 1.0, 0.0)
    // X_meeus = Y_meeus × Z_meeus
    val xMeeus = Vec3(
        yMeeus.y * ed.z - yMeeus.z * ed.y,
        yMeeus.z * ed.x - yMeeus.x * ed.z,
        yMeeus.x * ed.y - yMeeus.y * ed.x
    )
    return Triple(xMeeus, yMeeus, ed)
}

// Ray-trace a single ring point's shadow on Saturn's oblate globe.
// Returns Meeus-frame (x,y) point in Saturn radii, or null if no visible hit.
private fun traceRingShadowPoint(
    ringRadius: Double,
    phi: Double,
    sd: Vec3,
    polarRatio: Double,
    meeusX: Vec3,
    meeusY: Vec3,
    ed: Vec3
): Offset? {
    val p = polarRatio
    val px = ringRadius * cos(phi)
    val pz = ringRadius * sin(phi)
    // Ray: Q(t) = P - t * sunDir; hit oblate globe x² + (y/p)² + z² = 1
    val a = sd.x * sd.x + (sd.y / p) * (sd.y / p) + sd.z * sd.z
    val b = -2.0 * (px * sd.x + pz * sd.z)  // Py = 0
    val c = ringRadius * ringRadius - 1.0
    val disc = b * b - 4.0 * a * c
    if (disc < 0.0) return null
    val t = (-b - sqrt(disc)) / (2.0 * a)
    if (t < 0.0) return null
    // Shadow point on globe
    val gx = px - t * sd.x
    val gy = -t * sd.y
    val gz = pz - t * sd.z
    // Visibility check: skip if on far hemisphere
    if (gx * ed.x + gy * ed.y + gz * ed.z <= 0.0) return null
    // Project to drawing frame:
    // Ring-plane components (gx, gz) use Meeus basis (matches ring drawing).
    // Pole component (gy) maps directly to screen Y (matches globe drawing,
    // which uses a fixed 1:p ellipse without viewing-angle compression).
    val mx = gx * meeusX.x + gz * meeusX.z
    val my = gx * meeusY.x + gz * meeusY.z + gy
    return Offset(mx.toFloat(), my.toFloat())
}

// Compute globe shadow outline on ring plane (full closed curve).
// Returns list of Meeus-frame (x,y) points in Saturn radii.
// Does NOT filter by ring bounds — caller clips to ring paths.
private fun computeGlobeShadowOutline(
    sunDir: Vector3,
    meeusX: Vec3,
    meeusY: Vec3,
    steps: Int = 200
): List<Offset> {
    val sd = Vec3(sunDir.x, sunDir.y, sunDir.z)
    // Build two vectors perpendicular to sunDir for globe limb as seen from Sun
    val hint = if (abs(sd.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
    val ux = hint.y * sd.z - hint.z * sd.y
    val uy = hint.z * sd.x - hint.x * sd.z
    val uz = hint.x * sd.y - hint.y * sd.x
    val uLen = sqrt(ux * ux + uy * uy + uz * uz)
    val u = Vec3(ux / uLen, uy / uLen, uz / uLen)
    // v = sunDir × u
    val v = Vec3(
        sd.y * u.z - sd.z * u.y,
        sd.z * u.x - sd.x * u.z,
        sd.x * u.y - sd.y * u.x
    )

    val points = mutableListOf<Offset>()
    if (abs(sd.y) < 1e-10) return points  // Sun in ring plane — no shadow on rings

    for (k in 0..steps) {
        val theta = 2.0 * PI * k / steps
        // Globe limb point (unit sphere) as seen from Sun
        val lx = cos(theta) * u.x + sin(theta) * v.x
        val ly = cos(theta) * u.y + sin(theta) * v.y
        val lz = cos(theta) * u.z + sin(theta) * v.z
        // Project shadow onto ring plane (y=0): Q = P - t*sunDir, Q.y = 0
        val t = ly / sd.y
        val qx = lx - t * sd.x
        val qz = lz - t * sd.z
        // Project to Meeus frame (y=0 for ring plane points)
        val mx = qx * meeusX.x + qz * meeusX.z
        val my = qx * meeusY.x + qz * meeusY.z
        points.add(Offset(mx.toFloat(), my.toFloat()))
    }
    return points
}

// --- Canvas drawing ---
// All elements use a unified toScreen() transform: Meeus frame → flip → rotate by PA → translate to center.
// Meeus convention: X+ = west, Y+ = north, units = Saturn radii * pxPerRadius.

private fun DrawScope.drawSaturnSystem(
    data: SaturnSystemData,
    scale: Float,
    isNorthUp: Boolean,
    isEastRight: Boolean
) {
    val w = size.width
    val h = size.height
    val centerX = w / 2f
    val centerY = h / 2f

    val flipX = if (isEastRight) -1f else 1f
    val flipY = if (isNorthUp) -1f else 1f

    // Field of view = ±22 Saturn radii at scale 1; initial scale fits all moons
    val pxPerRadius = (min(w, h) / 44f) * scale

    val paRad = Math.toRadians(-data.positionAngleP)
    val cosP = cos(paRad).toFloat()
    val sinP = sin(paRad).toFloat()

    // Transform from Meeus reference frame (px units) to screen coordinates.
    // 1. Flip for orientation  2. Rotate by -PA  3. Translate to center
    fun toScreen(x: Float, y: Float): Offset {
        val fx = x * flipX
        val fy = y * flipY
        val rx = fx * cosP - fy * sinP
        val ry = fx * sinP + fy * cosP
        return Offset(centerX + rx, centerY + ry)
    }

    val tiltB = data.ringTiltB
    val sinB = abs(sin(Math.toRadians(tiltB))).toFloat()

    // Saturn oblateness: polar/equatorial ≈ 0.902
    val satPolarRatio = 0.902f

    // Limb-darkened Saturn disk: concentric ellipses from outside in, progressively brighter.
    // Linear limb darkening: I(r) = 1 - u*(1 - sqrt(1 - r²)), u=0.5
    val limbSteps = 15
    val limbU = 0.5f
    fun drawLimbDarkenedDisk() {
        for (i in 0 until limbSteps) {
            val r = 1.0f - i.toFloat() / limbSteps
            val cosTheta = sqrt(1.0f - r * r)
            val brightness = 1.0f - limbU * (1.0f - cosTheta)
            val color = Color(
                colorSaturn.red * brightness,
                colorSaturn.green * brightness,
                colorSaturn.blue * brightness
            )
            drawPath(buildEllipsePath(
                pxPerRadius * r, pxPerRadius * satPolarRatio * r, 100, ::toScreen
            ), color)
        }
    }

    if (sinB * pxPerRadius < 0.5f) {
        // Rings edge-on — just draw Saturn disk and moons
        drawLimbDarkenedDisk()
        val globeClipEdge = buildEllipsePath(pxPerRadius, pxPerRadius * satPolarRatio, 100, ::toScreen)
        drawAtmosphericBands(pxPerRadius, satPolarRatio, tiltB, globeClipEdge, ::toScreen)
    } else {
        // Front/back classification: when B > 0, back = north half (Y+), front = south half (Y-)
        val backIsNorthHalf = tiltB > 0

        // Compute Meeus projection basis for shadow ray-tracing
        val (meeusX, meeusY, _) = computeMeeusFrameBasis(data.earthDir)
        val sd = Vec3(data.sunDir.x, data.sunDir.y, data.sunDir.z)
        val ed = Vec3(data.earthDir.x, data.earthDir.y, data.earthDir.z)

        // --- 1. Back ring halves (behind Saturn) ---
        val backRingC = buildRingHalfPath(pxPerRadius, SaturnMoonEngine.C_RING_OUTER.toFloat(),
            SaturnMoonEngine.C_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen)
        val backRingA = buildRingHalfPath(pxPerRadius, SaturnMoonEngine.A_RING_OUTER.toFloat(),
            SaturnMoonEngine.A_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen)
        val backRingB = buildRingHalfPath(pxPerRadius, SaturnMoonEngine.B_RING_OUTER.toFloat(),
            SaturnMoonEngine.B_RING_INNER.toFloat(), sinB, backIsNorthHalf, ::toScreen)
        drawPath(backRingC, colorRingC)
        drawPath(backRingA, colorRingA)
        drawPath(backRingB, colorRingB)

        // --- 2. Globe shadow on back rings (opaque black, clipped to ring area) ---
        val globeShadowPts = computeGlobeShadowOutline(data.sunDir, meeusX, meeusY)
        if (globeShadowPts.size >= 3) {
            val globeShadowPath = Path()
            globeShadowPts.forEachIndexed { i, pt ->
                val screen = toScreen(pt.x * pxPerRadius, pt.y * pxPerRadius)
                if (i == 0) globeShadowPath.moveTo(screen.x, screen.y)
                else globeShadowPath.lineTo(screen.x, screen.y)
            }
            globeShadowPath.close()
            val backRingClip = Path().apply {
                addPath(backRingA)
                addPath(backRingB)
                addPath(backRingC)
            }
            clipPath(backRingClip) {
                drawPath(globeShadowPath, Color.Black)
            }
        }

        // --- 3. Saturn disk (limb darkened) ---
        drawLimbDarkenedDisk()

        // --- 3.5. Atmospheric bands on globe ---
        val globeClip = buildEllipsePath(pxPerRadius, pxPerRadius * satPolarRatio, 100, ::toScreen)
        drawAtmosphericBands(pxPerRadius, satPolarRatio, tiltB, globeClip, ::toScreen)

        // --- 4. Ring shadow on globe (matched-pair bands for proper curvature) ---
        val ringShadowSteps = 200
        val ringShadowAlpha = 0.4f
        // Draw A and B ring shadow bands separately (preserves Cassini Division gap)
        for ((outerR, innerR) in listOf(
            SaturnMoonEngine.A_RING_OUTER to SaturnMoonEngine.A_RING_INNER,
            SaturnMoonEngine.B_RING_OUTER to SaturnMoonEngine.B_RING_INNER
        )) {
            val outerPts = mutableListOf<Offset>()
            val innerPts = mutableListOf<Offset>()
            for (k in 0..ringShadowSteps) {
                val phi = 2.0 * PI * k / ringShadowSteps
                val outerHit = traceRingShadowPoint(outerR, phi, sd,
                    satPolarRatio.toDouble(), meeusX, meeusY, ed)
                val innerHit = traceRingShadowPoint(innerR, phi, sd,
                    satPolarRatio.toDouble(), meeusX, meeusY, ed)
                if (outerHit != null && innerHit != null) {
                    outerPts.add(outerHit)
                    innerPts.add(innerHit)
                }
            }
            if (outerPts.size >= 2) {
                val bandPath = Path()
                outerPts.forEachIndexed { i, pt ->
                    val screen = toScreen(pt.x * pxPerRadius, pt.y * pxPerRadius)
                    if (i == 0) bandPath.moveTo(screen.x, screen.y)
                    else bandPath.lineTo(screen.x, screen.y)
                }
                innerPts.reversed().forEach { pt ->
                    val screen = toScreen(pt.x * pxPerRadius, pt.y * pxPerRadius)
                    bandPath.lineTo(screen.x, screen.y)
                }
                bandPath.close()
                clipPath(globeClip) {
                    drawPath(bandPath, Color.Black.copy(alpha = ringShadowAlpha))
                }
            }
        }

        // --- 5. Front ring halves (in front of Saturn) ---
        drawPath(buildRingHalfPath(pxPerRadius, SaturnMoonEngine.C_RING_OUTER.toFloat(),
            SaturnMoonEngine.C_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingC)
        drawPath(buildRingHalfPath(pxPerRadius, SaturnMoonEngine.A_RING_OUTER.toFloat(),
            SaturnMoonEngine.A_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingA)
        drawPath(buildRingHalfPath(pxPerRadius, SaturnMoonEngine.B_RING_OUTER.toFloat(),
            SaturnMoonEngine.B_RING_INNER.toFloat(), sinB, !backIsNorthHalf, ::toScreen), colorRingB)
    }

    // Moon dots (skip moons occulted behind Saturn's disk or A/B rings)
    // Z convention: z > 0 = behind Saturn (away from Earth),
    //               z < 0 = in front (toward Earth)
    val satPolarRatioOcc = 0.902
    val sinBOcc = abs(sin(Math.toRadians(data.ringTiltB)))
    data.moons.forEach { moon ->
        if (moon.z > 0.0) {
            // Behind the planet's disk?
            if (moon.x * moon.x + (moon.y / satPolarRatioOcc) * (moon.y / satPolarRatioOcc) <= 1.0) return@forEach
            // Behind A or B ring? Ring at radius r projects as ellipse (r, r*sinB);
            // ring-plane distance = sqrt(x² + (y/sinB)²)
            if (sinBOcc > 0.01) {
                val ringDist = sqrt(moon.x * moon.x + (moon.y / sinBOcc) * (moon.y / sinBOcc))
                if ((ringDist >= SaturnMoonEngine.B_RING_INNER && ringDist <= SaturnMoonEngine.B_RING_OUTER) ||
                    (ringDist >= SaturnMoonEngine.A_RING_INNER && ringDist <= SaturnMoonEngine.A_RING_OUTER)) return@forEach
            }
        }
        val color = saturnMoonColors[moon.name] ?: Color.White
        val screen = toScreen((moon.x * pxPerRadius).toFloat(), (moon.y * pxPerRadius).toFloat())
        drawCircle(color, radius = 4f, center = screen)
    }

    // --- Arcsecond scale bar (fixed at bottom of canvas) ---
    val arcsecPerRadius = Math.toDegrees(data.angularRadiusRad) * 3600.0
    val pxPerArcsec = pxPerRadius / arcsecPerRadius.toFloat()

    // Choose a nice round arcsecond value that spans roughly 15-35% of canvas width
    val niceValues = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
    val targetPx = w * 0.25f
    val targetArcsec = targetPx / pxPerArcsec
    val scaleArcsec = niceValues.minByOrNull { abs(it - targetArcsec) } ?: 10.0
    val barPx = (scaleArcsec * pxPerArcsec).toFloat()

    val barY = h - 20f
    val barX0 = (w - barPx) / 2f
    val barX1 = barX0 + barPx
    val tickH = 6f

    // Bar line and end ticks
    drawLine(Color.White, Offset(barX0, barY), Offset(barX1, barY), strokeWidth = 1.5f)
    drawLine(Color.White, Offset(barX0, barY - tickH), Offset(barX0, barY + tickH), strokeWidth = 1.5f)
    drawLine(Color.White, Offset(barX1, barY - tickH), Offset(barX1, barY + tickH), strokeWidth = 1.5f)

    // Label
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

// Draw feathered atmospheric bands on Saturn's disk, clipped to the globe ellipse.
// Bands shift with sub-Earth latitude (ring tilt B) so the equator moves off-center.
private fun DrawScope.drawAtmosphericBands(
    pxPerRadius: Float,
    satPolarRatio: Float,
    tiltBDeg: Double,
    globeClip: Path,
    toScreen: (Float, Float) -> Offset
) {
    val featherDeg = 5f
    val featherSteps = 6
    clipPath(globeClip) {
        for (band in saturnAtmoBands) {
            val feather = min(featherDeg, (band.latNorth - band.latSouth) / 4f)
            // Southern feather ramp (alpha increases from 0 to full)
            for (i in 0 until featherSteps) {
                val t = (i + 0.5f) / featherSteps
                val subLatS = band.latSouth + feather * i / featherSteps
                val subLatN = band.latSouth + feather * (i + 1) / featherSteps
                drawBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    tiltBDeg, satPolarRatio, pxPerRadius, toScreen)
            }
            // Core region (full alpha)
            val coreSouth = band.latSouth + feather
            val coreNorth = band.latNorth - feather
            if (coreNorth > coreSouth) {
                drawBandStrip(coreSouth, coreNorth, band.color,
                    tiltBDeg, satPolarRatio, pxPerRadius, toScreen)
            }
            // Northern feather ramp (alpha decreases from full to 0)
            for (i in 0 until featherSteps) {
                val t = 1f - (i + 0.5f) / featherSteps
                val subLatS = band.latNorth - feather + feather * i / featherSteps
                val subLatN = band.latNorth - feather + feather * (i + 1) / featherSteps
                drawBandStrip(subLatS, subLatN, band.color.copy(alpha = band.color.alpha * t),
                    tiltBDeg, satPolarRatio, pxPerRadius, toScreen)
            }
        }
    }
}

// Draw a single curved latitude strip on the projected sphere, clipped by caller to the globe.
// Latitude lines on a sphere viewed at sub-Earth latitude B project as curved arcs that
// bow toward the equator. The formula ensures the curve meets the globe outline at the
// limb and has correct 3D curvature at the center.
private fun DrawScope.drawBandStrip(
    latSouthDeg: Float, latNorthDeg: Float,
    color: Color,
    tiltBDeg: Double,
    satPolarRatio: Float,
    pxPerRadius: Float,
    toScreen: (Float, Float) -> Offset
) {
    val bRad = Math.toRadians(tiltBDeg)
    val sinB = sin(bRad).toFloat()
    val cosB = cos(bRad).toFloat()
    val p = satPolarRatio
    val steps = 40

    // Compute one point on a latitude curve at parameter alpha (0 to PI).
    // At the limb (alpha=0,PI): y = p*sin(phi), matching the globe outline.
    // At the center (alpha=PI/2): y bows by deltaY = p*sin(phi)*(1-cosB) + cos(phi)*sinB.
    fun latPoint(latDeg: Float, alpha: Double): Pair<Float, Float> {
        val phi = Math.toRadians(latDeg.toDouble())
        val cosPhi = cos(phi).toFloat()
        val sinPhi = sin(phi).toFloat()
        val yLimb = p * sinPhi
        val deltaY = p * sinPhi * (1f - cosB) + cosPhi * sinB
        val x = cosPhi * cos(alpha).toFloat() * pxPerRadius
        val y = (yLimb - deltaY * sin(alpha).toFloat()) * pxPerRadius
        return Pair(x, y)
    }

    val path = Path()

    // North boundary: alpha from 0 to PI (right limb → left limb, bowing in the middle)
    for (k in 0..steps) {
        val alpha = PI * k / steps
        val (x, y) = latPoint(latNorthDeg, alpha)
        val screen = toScreen(x, y)
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }

    // South boundary: alpha from PI to 0 (left limb → right limb)
    for (k in 0..steps) {
        val alpha = PI * (steps - k).toDouble() / steps
        val (x, y) = latPoint(latSouthDeg, alpha)
        val screen = toScreen(x, y)
        path.lineTo(screen.x, screen.y)
    }

    path.close()
    drawPath(path, color)
}

// Build a closed ellipse path through the toScreen transform
private fun buildEllipsePath(
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

// Build a half-annulus path for one ring half.
// isNorthHalf: true = draw the Y+ (north) half, false = draw the Y- (south) half.
private fun buildRingHalfPath(
    pxPerRadius: Float,
    outerRadii: Float, innerRadii: Float,
    sinB: Float, isNorthHalf: Boolean,
    toScreen: (Float, Float) -> Offset
): Path {
    val outerW = outerRadii * pxPerRadius
    val outerH = outerRadii * pxPerRadius * sinB
    val innerW = innerRadii * pxPerRadius
    val innerH = innerRadii * pxPerRadius * sinB

    val path = Path()
    val steps = 100
    // North half: θ from 0 to π (sin positive → Y+ → north in Meeus)
    // South half: θ from π to 2π (sin negative → Y- → south in Meeus)
    val startAngle = if (isNorthHalf) 0.0 else PI

    // Outer arc
    for (k in 0..steps) {
        val theta = startAngle + PI * k / steps
        val screen = toScreen((outerW * cos(theta)).toFloat(), (outerH * sin(theta)).toFloat())
        if (k == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    // Inner arc (reverse direction)
    for (k in steps downTo 0) {
        val theta = startAngle + PI * k / steps
        val screen = toScreen((innerW * cos(theta)).toFloat(), (innerH * sin(theta)).toFloat())
        path.lineTo(screen.x, screen.y)
    }
    path.close()
    return path
}
