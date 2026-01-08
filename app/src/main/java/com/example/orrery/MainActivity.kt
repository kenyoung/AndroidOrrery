package com.example.orrery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
            .padding(top = topPadding)
    ) {
        // --- TEXT LINE ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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
        val centerY = h / 2

        // --- 1. SETUP SCALE & MARGINS ---
        // Increase paddingLeft to 35f to make room for text + descent
        val paddingLeft = 35f
        val paddingRight = 17f
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
        val (solsticeRise, solsticeSet) = calculateSunTimes(solsticeDate.toLocalDate().toEpochDay(), lat, lon, offsetHours)

        // Nmax logic
        val nMaxDuration = (solsticeRise + (24.0 - solsticeSet))
        val pixelsPerHour = drawingWidth / nMaxDuration

        val textHeight = 40f
        val textY = 30f

        val nowZoned = ZonedDateTime.ofInstant(now, zoneId)
        val nowDate = nowZoned.toLocalDate()

        fun getYForDate(date: LocalDate): Float {
            val daysOffset = ChronoUnit.DAYS.between(nowDate, date)
            val fraction = daysOffset / 365.25
            return (centerY - (fraction * h)).toFloat()
        }

        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 30f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }

            // --- 2. DRAW TIME LABELS (Top) ---
            for (k in -14..14) {
                val xPos = centerX + (k * pixelsPerHour)

                if (xPos >= paddingLeft && xPos <= (w - paddingRight)) {
                    var hour = (k % 24)
                    if (hour < 0) hour += 24
                    canvas.nativeCanvas.drawText(hour.toString(), xPos.toFloat(), textY, paint)
                }
            }

            // --- 3. DRAW MONTH LABELS (Left, Rotated) ---
            val monthPaint = Paint(paint).apply {
                textAlign = Paint.Align.CENTER
            }

            val startDate = nowDate.minusMonths(7).withDayOfMonth(1)
            val endDate = nowDate.plusMonths(7).withDayOfMonth(1)

            // Move text to x = 10 + (textSize/2) = 25
            val textX = 25f

            var d = startDate
            while (d.isBefore(endDate)) {
                val nextMonth = d.plusMonths(1)

                val y1 = getYForDate(d)
                val y2 = getYForDate(nextMonth)

                if (y1 >= textHeight && y1 <= h && y2 >= textHeight && y2 <= h) {
                    val yMid = (y1 + y2) / 2f
                    val monthName = d.format(DateTimeFormatter.ofPattern("MMM"))

                    canvas.nativeCanvas.save()
                    // Rotate around the new text position
                    canvas.nativeCanvas.rotate(-90f, textX, yMid)
                    canvas.nativeCanvas.drawText(monthName, textX, yMid, monthPaint)
                    canvas.nativeCanvas.restore()
                }

                d = nextMonth
            }
        }

        // --- 4. DRAW CURRENT DATE LINE ---
        if (centerY > textHeight) {
            drawLine(
                color = Color(0xFFADD8E6),
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 2f
            )
        }

        // --- 5. DRAW PLOT POINTS & MONTH LINES ---
        val points = ArrayList<Offset>()

        for (y in textHeight.toInt() until h.toInt()) {
            val fraction = (centerY - y) / h
            val daysOffset = fraction * 365.25

            val rowDate = nowZoned.plusDays(daysOffset.toLong()).toLocalDate()
            val rowJulianDay = rowDate.toEpochDay()

            val (_, setTimePrev) = calculateSunTimes(rowJulianDay - 1, lat, lon, offsetHours)
            val (riseTimeCurr, _) = calculateSunTimes(rowJulianDay, lat, lon, offsetHours)

            val xOffsetSunset = (setTimePrev - 24.0) * pixelsPerHour
            val xSunset = centerX + xOffsetSunset

            val xOffsetSunrise = riseTimeCurr * pixelsPerHour
            val xSunrise = centerX + xOffsetSunrise

            val validSunset = !xSunset.isNaN()
            val validSunrise = !xSunrise.isNaN()

            // Draw monthly line if it's the 1st of the month
            if (rowDate.dayOfMonth == 1 && validSunset && validSunrise) {
                drawLine(
                    color = Color.DarkGray,
                    start = Offset(xSunset.toFloat(), y.toFloat()),
                    end = Offset(xSunrise.toFloat(), y.toFloat()),
                    strokeWidth = 1f
                )
            }

            if (validSunset) {
                points.add(Offset(xSunset.toFloat(), y.toFloat()))
            }
            if (validSunrise) {
                points.add(Offset(xSunrise.toFloat(), y.toFloat()))
            }
        }

        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = Color.White,
            strokeWidth = 2f
        )
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

fun calculateSunTimes(epochDay: Long, lat: Double, lon: Double, timezoneOffset: Double): Pair<Double, Double> {
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
    val altRad = Math.toRadians(-0.833)
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