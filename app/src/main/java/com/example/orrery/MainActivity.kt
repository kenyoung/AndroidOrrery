package com.example.orrery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) getLocationAndSetContent()
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            getLocationAndSetContent()
        }
    }

    private fun getLocationAndSetContent() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                setContent {
                    OrreryApp(lat, lon)
                }
            }
        } catch (e: SecurityException) { }
    }
}

// Data classes
data class PlanetElements(
    val name: String,
    val symbol: String,
    val color: Color,
    val L_0: Double,   // Mean Longitude at Epoch
    val L_rate: Double,// Rate (deg/day)
    val a: Double,     // AU
    val e: Double,     // Eccentricity
    val i: Double,     // Inclination (deg)
    val w_bar: Double, // Longitude of Perihelion (deg)
    val N: Double      // Longitude of Ascending Node (deg)
)

data class PlanetEvents(
    val rise: Double,
    val transit: Double,
    val set: Double
)

data class LabelPosition(
    var x: Float = 0f,
    var y: Float = 0f,
    var minDistToCenter: Float = Float.MAX_VALUE,
    var found: Boolean = false
)

@Composable
fun OrreryApp(lat: Double, lon: Double) {
    var currentInstant by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Instant.now()
            currentInstant = now
            val currentMillis = now.toEpochMilli()
            val millisUntilNextMinute = 60_000 - (currentMillis % 60_000)
            delay(millisUntilNextMinute)
        }
    }

    val utFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
    val utString = utFormatter.format(currentInstant)
    val lstString = calculateLST(currentInstant, lon)

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = statusBarHeight + 10.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = statusBarHeight)
    ) {
        // --- APP TITLE ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Orrery",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // --- INFO LINE ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Text(
                text = "Lat %.3f Lon %.4f".format(lat, lon),
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )
            Text(
                text = "UT $utString  LST $lstString",
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )
        }

        // --- GRAPHICS WINDOW ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            GraphicsWindow(lat, lon, currentInstant)
        }
    }
}

@Composable
fun GraphicsWindow(lat: Double, lon: Double, now: Instant) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // --- 1. SETUP SCALE & MARGINS ---
        val paddingLeft = 35f
        val paddingRight = 42f
        val drawingWidth = w - paddingLeft - paddingRight
        val centerX = paddingLeft + (drawingWidth / 2f)

        // Calculate Scale
        val zoneId = ZoneId.systemDefault()
        val standardOffset = zoneId.rules.getStandardOffset(now)
        val offsetHours = standardOffset.totalSeconds / 3600.0

        val year = ZonedDateTime.ofInstant(now, zoneId).year
        val solsticeMonth = if (lat >= 0) 12 else 6
        val solsticeDay = 21
        val solsticeDate = ZonedDateTime.of(year, solsticeMonth, solsticeDay, 12, 0, 0, 0, zoneId)
        val (solsticeRise, solsticeSet) = calculateSunTimes(solsticeDate.toLocalDate().toEpochDay().toDouble(), lat, lon, offsetHours)

        val nMaxDuration = (solsticeRise + (24.0 - solsticeSet))
        val pixelsPerHour = drawingWidth / nMaxDuration

        val textHeight = 40f
        val textY = 30f

        val nowZoned = ZonedDateTime.ofInstant(now, zoneId)
        val nowDate = nowZoned.toLocalDate()
        val nowEpochDay = nowDate.toEpochDay().toDouble()

        fun getYForDate(date: LocalDate): Float {
            val daysOffset = ChronoUnit.DAYS.between(nowDate, date)
            val fraction = daysOffset / 365.25
            return (h - (fraction * h)).toFloat()
        }

        fun getYForEpochDay(targetEpochDay: Double): Float {
            val daysOffset = targetEpochDay - nowEpochDay
            val fraction = daysOffset / 365.25
            return (h - (fraction * h)).toFloat()
        }

        fun getXSunsetForDate(date: LocalDate): Float {
            val epochDay = date.toEpochDay().toDouble()
            val (_, setTimePrev) = calculateSunTimes(epochDay - 1.0, lat, lon, offsetHours)
            val diff = setTimePrev - 24.0
            return (centerX + (diff * pixelsPerHour)).toFloat()
        }

        // --- 4. DEFINE PLANETS ---
        val blueGreen = Color(0xFF20B2AA)
        val orange = Color(0xFFFFA500)

        val planets = listOf(
            PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
            PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
            PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
            PlanetElements("Jupiter", "♃", orange,        34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
            PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
            PlanetElements("Uranus",  "⛢", blueGreen,    313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
            PlanetElements("Neptune", "♆", Color.Blue,   304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
        )

        val sunPoints = ArrayList<Offset>()
        val planetTransits = planets.associate { it.name to ArrayList<Offset>() }
        val planetRises = planets.associate { it.name to ArrayList<Offset>() }
        val planetSets = planets.associate { it.name to ArrayList<Offset>() }

        val bestLabels = HashMap<String, LabelPosition>()
        for (p in planets) {
            bestLabels["${p.name}_t"] = LabelPosition()
            bestLabels["${p.name}_r"] = LabelPosition()
            bestLabels["${p.name}_s"] = LabelPosition()
        }

        // --- LAYER 1: TWILIGHT (BACKGROUND) & CALCULATION LOOP ---
        val darkBlueArgb = 0xFF0000D1.toInt()
        val blackArgb = android.graphics.Color.BLACK
        val twilightPaint = Paint().apply {
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        drawIntoCanvas { canvas ->
            for (y in textHeight.toInt() until h.toInt()) {
                val fraction = (h - y) / h
                val daysOffset = fraction * 365.25
                val targetEpochDay = nowEpochDay + daysOffset

                val (_, setTimePrev) = calculateSunTimes(targetEpochDay - 1.0, lat, lon, offsetHours)
                val (riseTimeCurr, _) = calculateSunTimes(targetEpochDay, lat, lon, offsetHours)
                val (_, astroSetPrev) = calculateSunTimes(targetEpochDay - 1.0, lat, lon, offsetHours, -18.0)
                val (astroRiseCurr, _) = calculateSunTimes(targetEpochDay, lat, lon, offsetHours, -18.0)

                val xOffsetSunset = (setTimePrev - 24.0) * pixelsPerHour
                val xSunset = centerX + xOffsetSunset
                val xOffsetSunrise = riseTimeCurr * pixelsPerHour
                val xSunrise = centerX + xOffsetSunrise
                val validSunset = !xSunset.isNaN()
                val validSunrise = !xSunrise.isNaN()

                if (validSunset && !astroSetPrev.isNaN()) {
                    val xAstroEnd = centerX + (astroSetPrev - 24.0) * pixelsPerHour
                    twilightPaint.shader = LinearGradient(
                        xSunset.toFloat(), y.toFloat(),
                        xAstroEnd.toFloat(), y.toFloat(),
                        darkBlueArgb, blackArgb,
                        Shader.TileMode.CLAMP
                    )
                    canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), twilightPaint)
                }

                if (validSunrise && !astroRiseCurr.isNaN()) {
                    var diffAstro = astroRiseCurr - 24.0
                    if (diffAstro < -12.0) diffAstro += 24.0
                    else if (diffAstro > 12.0) diffAstro -= 24.0
                    val xAstroStart = centerX + (diffAstro * pixelsPerHour)

                    twilightPaint.shader = LinearGradient(
                        xAstroStart.toFloat(), y.toFloat(),
                        xSunrise.toFloat(), y.toFloat(),
                        blackArgb, darkBlueArgb,
                        Shader.TileMode.CLAMP
                    )
                    canvas.nativeCanvas.drawLine(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                }

                if (validSunset) sunPoints.add(Offset(xSunset.toFloat(), y.toFloat()))
                if (validSunrise) sunPoints.add(Offset(xSunrise.toFloat(), y.toFloat()))

                if (validSunset && validSunrise) {
                    for (planet in planets) {
                        val events = calculatePlanetEvents(targetEpochDay, lat, lon, offsetHours, planet)

                        // Determine offset for label target based on planet name
                        val targetHourOffset = when (planet.name) {
                            "Mars" -> -2.0    // 22 hours
                            "Jupiter" -> -1.0 // 23 hours
                            "Saturn" -> 0.0   // 0 hours
                            "Uranus" -> 1.0   // 1 hour
                            "Neptune" -> 2.0  // 2 hours
                            else -> 0.0       // Default center
                        }
                        val targetX = centerX + (targetHourOffset * pixelsPerHour)

                        fun processEvent(time: Double, list: ArrayList<Offset>, keySuffix: String) {
                            if (time.isNaN()) return
                            var diff = time - 24.0
                            if (diff < -12.0) diff += 24.0
                            else if (diff > 12.0) diff -= 24.0
                            val xPos = centerX + (diff * pixelsPerHour)
                            val yPos = y.toFloat()

                            if (xPos >= xSunset && xPos <= xSunrise) {
                                list.add(Offset(xPos.toFloat(), yPos))

                                // Calculate distance to the planet-specific target X
                                val dist = abs(xPos - targetX)
                                val labelTracker = bestLabels["${planet.name}_$keySuffix"]!!
                                if (dist < labelTracker.minDistToCenter) {
                                    labelTracker.minDistToCenter = dist.toFloat()
                                    labelTracker.x = xPos.toFloat()
                                    labelTracker.y = yPos
                                    labelTracker.found = true
                                }
                            }
                        }
                        processEvent(events.transit, planetTransits[planet.name]!!, "t")
                        processEvent(events.rise, planetRises[planet.name]!!, "r")
                        processEvent(events.set, planetSets[planet.name]!!, "s")
                    }
                }
            }
        }

        // --- LAYER 2: SUN CURVE ---
        drawPoints(
            points = sunPoints,
            pointMode = PointMode.Points,
            color = Color.White,
            strokeWidth = 2f
        )

        // --- LAYER 3: GRID LINES & MONTH LABELS ---
        drawIntoCanvas { canvas ->
            val monthPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 30f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }
            val gridPaint = Paint().apply {
                color = 0xFF666666.toInt()
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }

            val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
            val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
            var d = startDate
            while (d.isBefore(endDate)) {
                val nextMonth = d.plusMonths(1)
                val yStart = getYForDate(d)
                val x1_grid = getXSunsetForDate(d)
                val epochDayGrid = d.toEpochDay().toDouble()
                val (riseGrid, _) = calculateSunTimes(epochDayGrid, lat, lon, offsetHours)

                if (yStart >= textHeight && yStart <= h && !riseGrid.isNaN()) {
                    var diffGrid = riseGrid - 24.0
                    if (diffGrid < -12.0) diffGrid += 24.0
                    else if (diffGrid > 12.0) diffGrid -= 24.0
                    val x2_grid = centerX + (diffGrid * pixelsPerHour)
                    canvas.nativeCanvas.drawLine(x1_grid, yStart, x2_grid.toFloat(), yStart, gridPaint)
                }

                var visibleDays = 0
                var iterDate = d
                while (iterDate.isBefore(nextMonth)) {
                    val yDay = getYForDate(iterDate)
                    if (yDay >= textHeight && yDay <= h) visibleDays++
                    iterDate = iterDate.plusDays(1)
                }

                if (visibleDays >= 15) {
                    val monthName = d.format(DateTimeFormatter.ofPattern("MMM"))
                    val yEnd = getYForDate(nextMonth)
                    val yMid = (yStart + yEnd) / 2f
                    val midMonthDate = d.withDayOfMonth(15)
                    val xSunsetMid = getXSunsetForDate(midMonthDate)
                    val charHeight = monthPaint.textSize
                    val targetX = xSunsetMid - charHeight
                    val finalX = max(25f, targetX)

                    val x1 = getXSunsetForDate(d)
                    val x2 = getXSunsetForDate(nextMonth)
                    val dx = x2 - x1
                    val dy = yEnd - yStart
                    val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()

                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.rotate(angleDeg, finalX, yMid)
                    canvas.nativeCanvas.drawText(monthName, finalX, yMid, monthPaint)
                    canvas.nativeCanvas.restore()
                }
                d = nextMonth
            }
        }

        // --- LAYER 4: PLANET TRACKS ---
        for (planet in planets) {
            val isInner = planet.name == "Mercury" || planet.name == "Venus"
            val riseSetColor = if (isInner) planet.color else planet.color.copy(alpha = 0.35f)

            if (!isInner) {
                drawPoints(points = planetTransits[planet.name]!!, pointMode = PointMode.Points, color = planet.color, strokeWidth = 4f)
            }
            drawPoints(points = planetRises[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
            drawPoints(points = planetSets[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
        }

        // --- LAYER 5: MOONS & LABELS ---
        val fullMoons = ArrayList<Offset>()
        val newMoons = ArrayList<Offset>()
        val firstQuarters = ArrayList<Offset>()
        val lastQuarters = ArrayList<Offset>()
        val scanRange = 380
        var prevElong = calculateMoonPhaseAngle(nowEpochDay - 1.0)

        for (dIdx in 0..scanRange) {
            val scanEpochDay = nowEpochDay + dIdx
            val currElong = calculateMoonPhaseAngle(scanEpochDay)

            fun norm(a: Double): Double {
                var v = a % 360.0
                if (v > 180) v -= 360
                if (v <= -180) v += 360
                return v
            }

            fun checkCrossing(targetAngle: Double, list: ArrayList<Offset>) {
                val prevDiff = (prevElong - targetAngle)
                val currDiff = (currElong - targetAngle)
                val pNorm = norm(prevDiff)
                val cNorm = norm(currDiff)

                if (pNorm < 0 && cNorm >= 0) {
                    val totalSpan = cNorm - pNorm
                    val fractionOfDay = -pNorm / totalSpan
                    val exactMoonDay = (scanEpochDay - 1.0) + fractionOfDay
                    val refDay = scanEpochDay - 1.0
                    val (riseTime, _) = calculateSunTimes(refDay, lat, lon, offsetHours)

                    if (!riseTime.isNaN()) {
                        val xSunrise = centerX + (riseTime * pixelsPerHour)
                        val xMoon = xSunrise + 15f
                        val yMoon = getYForEpochDay(exactMoonDay)
                        if (yMoon >= textHeight && yMoon <= h) {
                            list.add(Offset(xMoon.toFloat(), yMoon))
                        }
                    }
                }
            }
            checkCrossing(0.0, newMoons)
            checkCrossing(90.0, firstQuarters)
            checkCrossing(180.0, fullMoons)
            checkCrossing(270.0, lastQuarters)
            prevElong = currElong
        }

        drawIntoCanvas { canvas ->
            val timePaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 30f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }

            // Draw Time Labels
            for (k in -14..14) {
                val xPos = centerX + (k * pixelsPerHour)
                if (xPos >= paddingLeft && xPos <= (w - paddingRight)) {
                    var hour = (k % 24)
                    if (hour < 0) hour += 24
                    canvas.nativeCanvas.drawText(hour.toString(), xPos.toFloat(), textY, timePaint)
                }
            }

            val mainTextPaint = Paint().apply {
                textSize = 42f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val subTextPaint = Paint().apply {
                textSize = 27f
                textAlign = Paint.Align.LEFT
                isAntiAlias = true
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }

            fun drawLabelWithSubscript(canvas: android.graphics.Canvas, x: Float, y: Float, symbol: String, subscript: String, color: Int) {
                val symbolWidth = mainTextPaint.measureText(symbol)
                val subWidth = subTextPaint.measureText(subscript)
                val symX = x + (1.0f * symbolWidth)
                val subX = symX + (symbolWidth / 2f) + (subWidth * 0.75f)
                val subY = y + 8f

                mainTextPaint.color = color
                mainTextPaint.style = Paint.Style.STROKE
                mainTextPaint.strokeWidth = 4f
                mainTextPaint.color = android.graphics.Color.BLACK
                canvas.drawText(symbol, symX, y, mainTextPaint)

                mainTextPaint.style = Paint.Style.FILL
                mainTextPaint.color = color
                canvas.drawText(symbol, symX, y, mainTextPaint)

                subTextPaint.color = color
                subTextPaint.style = Paint.Style.STROKE
                subTextPaint.strokeWidth = 3f
                subTextPaint.color = android.graphics.Color.BLACK
                canvas.drawText(subscript, subX, subY, subTextPaint)

                subTextPaint.style = Paint.Style.FILL
                subTextPaint.color = color
                canvas.drawText(subscript, subX, subY, subTextPaint)
            }

            for (p in planets) {
                if (p.name != "Mercury" && p.name != "Venus") {
                    val label = bestLabels["${p.name}_t"]!!
                    if (label.found) drawLabelWithSubscript(canvas.nativeCanvas, label.x, label.y, p.symbol, "t", p.color.toArgb())
                }
                val rLabel = bestLabels["${p.name}_r"]!!
                if (rLabel.found) drawLabelWithSubscript(canvas.nativeCanvas, rLabel.x, rLabel.y, p.symbol, "r", p.color.toArgb())
                val sLabel = bestLabels["${p.name}_s"]!!
                if (sLabel.found) drawLabelWithSubscript(canvas.nativeCanvas, sLabel.x, sLabel.y, p.symbol, "s", p.color.toArgb())
            }

            val moonFillPaint = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
            val moonStrokePaint = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }

            for (pos in fullMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonFillPaint)
            for (pos in newMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStrokePaint)

            val startAngleFirst = if (lat >= 0) -90f else 90f
            for (pos in firstQuarters) {
                val rect = RectF(pos.x - 10f, pos.y - 10f, pos.x + 10f, pos.y + 10f)
                canvas.nativeCanvas.drawArc(rect, startAngleFirst, 180f, true, moonFillPaint)
                canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStrokePaint)
            }

            val startAngleLast = if (lat >= 0) 90f else -90f
            for (pos in lastQuarters) {
                val rect = RectF(pos.x - 10f, pos.y - 10f, pos.x + 10f, pos.y + 10f)
                canvas.nativeCanvas.drawArc(rect, startAngleLast, 180f, true, moonFillPaint)
                canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStrokePaint)
            }
        }
    }
}

// --- HELPER FUNCTIONS ---

fun calculateLST(instant: Instant, lon: Double): String {
    val epochSeconds = instant.epochSecond
    val jd = epochSeconds / 86400.0 + 2440587.5
    val d = jd - 2451545.0
    var gmst = 18.697374558 + 24.06570982441908 * d
    gmst %= 24.0
    if (gmst < 0) gmst += 24.0
    var lst = gmst + (lon / 15.0)
    lst %= 24.0
    if (lst < 0) lst += 24.0
    val hours = floor(lst).toInt()
    val minutes = floor((lst - hours) * 60).toInt()
    return "%02d:%02d".format(hours, minutes)
}

fun calculateSunTimes(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double = -0.833): Pair<Double, Double> {
    val jd = 2440587.5 + epochDay + 0.5
    val n = jd - 2451545.0
    var L = 280.460 + 0.9856474 * n
    L %= 360.0
    if (L < 0) L += 360.0
    var g = 357.528 + 0.9856003 * n
    g %= 360.0
    if (g < 0) g += 360.0
    val gRad = Math.toRadians(g)
    val lRad = Math.toRadians(L)
    val lambda = L + 1.915 * sin(gRad) + 0.020 * sin(2 * gRad)
    val lambdaRad = Math.toRadians(lambda)
    val epsilon = 23.439 - 0.0000004 * n
    val epsilonRad = Math.toRadians(epsilon)
    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))
    val altRad = Math.toRadians(altitude)
    val latRad = Math.toRadians(lat)
    val cosH = (sin(altRad) - sin(latRad) * sin(delta)) / (cos(latRad) * cos(delta))

    if (cosH < -1.0) return Pair(Double.NaN, Double.NaN)
    if (cosH > 1.0) return Pair(Double.NaN, Double.NaN)

    val H = acos(cosH)
    val H_hours = Math.toDegrees(H) / 15.0
    val raHours = Math.toDegrees(alpha) / 15.0
    var GMST0 = 6.697374558 + 0.06570982441908 * n
    GMST0 %= 24.0
    if (GMST0 < 0) GMST0 += 24.0

    var transitUT = raHours - (lon / 15.0) - GMST0
    while (transitUT < 0) transitUT += 24.0
    while (transitUT >= 24) transitUT -= 24.0

    val transitStandard = transitUT + timezoneOffset
    var rise = transitStandard - H_hours
    while (rise < 0) rise += 24.0
    while (rise >= 24) rise -= 24.0

    var set = transitStandard + H_hours
    while (set < 0) set += 24.0
    while (set >= 24) set -= 24.0

    return Pair(rise, set)
}

fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    val jd = 2440587.5 + epochDay + 0.5
    val d = jd - 2451545.0

    // Earth Elements
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le = Math.toRadians((280.466 + 0.98564736 * d) % 360.0)
    val Ce = 1.915 * sin(Me) + 0.020 * sin(2 * Me)
    val trueLongSun = Le + Math.toRadians(Ce)
    val Re = 1.00014 - 0.01671 * cos(Me) - 0.00014 * cos(2 * Me)

    val Le_earth = trueLongSun + Math.PI
    val xe = Re * cos(Le_earth)
    val ye = Re * sin(Le_earth)
    val ze = 0.0

    // Planet Elements
    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
    val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i)
    val w_bar_p = Math.toRadians(p.w_bar)

    var Mp = Lp - w_bar_p

    var Ep = Mp + p.e * sin(Mp)
    Ep = Mp + p.e * sin(Ep)
    Ep = Mp + p.e * sin(Ep)

    val xv = p.a * (cos(Ep) - p.e)
    val yv = p.a * sqrt(1 - p.e*p.e) * sin(Ep)
    val rp = sqrt(xv*xv + yv*yv)
    val v = atan2(yv, xv)

    val u = v + w_bar_p - Np

    val xh = rp * (cos(u) * cos(Np) - sin(u) * sin(Np) * cos(ip))
    val yh = rp * (cos(u) * sin(Np) + sin(u) * cos(Np) * cos(ip))
    val zh = rp * (sin(u) * sin(ip))

    val xg = xh - xe
    val yg = yh - ye
    val zg = zh - ze

    val ecl = Math.toRadians(23.439 - 0.0000004 * d)
    val xeq = xg
    val yeq = yg * cos(ecl) - zg * sin(ecl)
    val zeq = yg * sin(ecl) + zg * cos(ecl)

    val raRad = atan2(yeq, xeq)
    val raHours = Math.toDegrees(raRad) / 15.0
    val decRad = atan2(zeq, sqrt(xeq*xeq + yeq*yeq))

    var GMST0 = 6.697374558 + 0.06570982441908 * d
    GMST0 %= 24.0
    if (GMST0 < 0) GMST0 += 24.0

    var transitUT = raHours - (lon / 15.0) - GMST0
    while (transitUT < 0) transitUT += 24.0
    while (transitUT >= 24) transitUT -= 24.0

    var transitStandard = transitUT + timezoneOffset
    while (transitStandard < 0) transitStandard += 24.0
    while (transitStandard >= 24) transitStandard -= 24.0

    val altRad = Math.toRadians(-0.5667)
    val latRad = Math.toRadians(lat)
    val cosH = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))

    var rise = Double.NaN
    var set = Double.NaN

    if (cosH >= -1.0 && cosH <= 1.0) {
        val H = acos(cosH)
        val H_hours = Math.toDegrees(H) / 15.0

        rise = transitStandard - H_hours
        set = transitStandard + H_hours

        while (rise < 0) rise += 24.0
        while (rise >= 24) rise -= 24.0
        while (set < 0) set += 24.0
        while (set >= 24) set -= 24.0
    }

    return PlanetEvents(rise, transitStandard, set)
}

// --- MOON FUNCTIONS ---

fun calculateMoonPhaseAngle(epochDay: Double): Double {
    val d = epochDay - 2451545.0

    val Ms = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Ls = Math.toRadians((280.466 + 0.98564736 * d) % 360.0)
    val Cs = 1.915 * sin(Ms) + 0.020 * sin(2 * Ms)
    val trueLongSun = Math.toDegrees(Ls) + Cs

    val L = (218.316 + 13.176396 * d) % 360.0
    val M = Math.toRadians((134.963 + 13.064993 * d) % 360.0)

    val lambda = L + 6.289 * sin(M)

    var diff = (lambda - trueLongSun) % 360.0
    if (diff < 0) diff += 360.0
    return diff
}