package com.example.orrery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
                    OrreryApp(initialGpsLat = lat, initialGpsLon = lon)
                }
            }
        } catch (e: SecurityException) { }
    }
}

// --- DATA CLASSES ---
data class PlanetElements(
    val name: String,
    val symbol: String,
    val color: Color,
    val L_0: Double, val L_rate: Double,
    val a: Double, val e: Double,
    val i: Double, val w_bar: Double, val N: Double
)

data class PlanetEvents(val rise: Double, val transit: Double, val set: Double)
data class LabelPosition(var x: Float = 0f, var y: Float = 0f, var minDistToCenter: Float = Float.MAX_VALUE, var found: Boolean = false)

// Navigation Enum
enum class Screen { TRANSITS, SCHEMATIC, SCALE }

// --- CACHE CLASS ---
class AstroCache(
    val startEpochDay: Double,
    val size: Int,
    val sunRise: DoubleArray,
    val sunSet: DoubleArray,
    val astroRise: DoubleArray,
    val astroSet: DoubleArray,
    val planetMap: Map<String, Triple<DoubleArray, DoubleArray, DoubleArray>>
) {
    private fun interp(v1: Double, v2: Double, frac: Double): Double {
        if (v1.isNaN() || v2.isNaN()) return Double.NaN
        var t1 = v1
        var t2 = v2
        if (abs(t1 - t2) > 12.0) { if (t1 > t2) t2 += 24.0 else t1 += 24.0 }
        var res = t1 + (t2 - t1) * frac
        if (res >= 24.0) res -= 24.0
        return res
    }
    fun getSunTimes(epochDay: Double, astro: Boolean): Pair<Double, Double> {
        val offset = epochDay - startEpochDay
        val idx = floor(offset).toInt()
        val frac = offset - idx
        if (idx < 0 || idx >= size - 1) return Pair(Double.NaN, Double.NaN)
        val riseArr = if (astro) astroRise else sunRise
        val setArr = if (astro) astroSet else sunSet
        return Pair(interp(riseArr[idx], riseArr[idx+1], frac), interp(setArr[idx], setArr[idx+1], frac))
    }
    fun getPlanetEvents(epochDay: Double, name: String): PlanetEvents {
        val arrays = planetMap[name] ?: return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
        val offset = epochDay - startEpochDay
        val idx = floor(offset).toInt()
        val frac = offset - idx
        if (idx < 0 || idx >= size - 1) return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
        return PlanetEvents(interp(arrays.first[idx], arrays.first[idx+1], frac), interp(arrays.second[idx], arrays.second[idx+1], frac), interp(arrays.third[idx], arrays.third[idx+1], frac))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrreryApp(initialGpsLat: Double, initialGpsLon: Double) {
    // --- NAVIGATION STATE ---
    var currentScreen by remember { mutableStateOf(Screen.TRANSITS) }

    // --- LOCATION STATE ---
    var usePhoneLocation by remember { mutableStateOf(true) }
    var manualLat by remember { mutableStateOf(0.0) }
    var manualLon by remember { mutableStateOf(0.0) }

    val effectiveLat = if (usePhoneLocation) initialGpsLat else manualLat
    val effectiveLon = if (usePhoneLocation) initialGpsLon else manualLon

    // --- TIME STATE ---
    var usePhoneTime by remember { mutableStateOf(true) }
    var manualEpochDay by remember { mutableStateOf(LocalDate.now().toEpochDay().toDouble()) }

    var currentInstant by remember { mutableStateOf(Instant.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- ANIMATION STATE ---
    var isAnimating by remember { mutableStateOf(false) }
    var animationTargetEpoch by remember { mutableStateOf(0.0) }
    var animationStep by remember { mutableStateOf(0.0) }

    // Animation Loop
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            while (abs(manualEpochDay - animationTargetEpoch) > animationStep) {
                manualEpochDay += animationStep
                currentInstant = Instant.ofEpochSecond((manualEpochDay * 86400).toLong())
                delay(12L) // ~80 frames per second
            }
            manualEpochDay = animationTargetEpoch
            currentInstant = Instant.ofEpochSecond((manualEpochDay * 86400).toLong())
            isAnimating = false
        }
    }

    // Lifecycle Observer (Standard Time)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && usePhoneTime && !isAnimating) {
                currentInstant = Instant.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Standard Time Loop
    LaunchedEffect(usePhoneTime, isAnimating) {
        if (usePhoneTime && !isAnimating) {
            while (true) {
                val now = Instant.now()
                currentInstant = now
                val currentMillis = now.toEpochMilli()
                val millisUntilNextMinute = 60_000 - (currentMillis % 60_000)
                delay(millisUntilNextMinute)
            }
        } else if (!usePhoneTime && !isAnimating) {
            currentInstant = Instant.ofEpochSecond((manualEpochDay * 86400).toLong())
        }
    }

    // Calculation Loop
    val zoneId = ZoneId.systemDefault()
    val effectiveDate = currentInstant.atZone(zoneId).toLocalDate()

    val cache by produceState<AstroCache?>(initialValue = null, key1 = currentScreen, key2 = effectiveDate.toEpochDay()) {
        if (currentScreen == Screen.TRANSITS) {
            value = withContext(Dispatchers.Default) {
                calculateCache(effectiveDate, effectiveLat, effectiveLon, zoneId)
            }
        }
    }

    val utFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
    val utString = utFormatter.format(currentInstant)
    val lstString = calculateLST(currentInstant, effectiveLon)
    val dateString = DateTimeFormatter.ofPattern("dd/MM/yyyy").format(effectiveDate)

    // UI State
    var showMenu by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Dialogs
    if (showLocationDialog) {
        LocationDialog(
            currentUsePhone = usePhoneLocation,
            onDismiss = { showLocationDialog = false },
            onConfirm = { usePhone, lat, lon ->
                usePhoneLocation = usePhone
                if (!usePhone) { manualLat = lat; manualLon = lon }
                showLocationDialog = false
            }
        )
    }
    if (showDateDialog) {
        DateDialog(
            currentUsePhone = usePhoneTime,
            onDismiss = { showDateDialog = false },
            onConfirm = { usePhone, date ->
                usePhoneTime = usePhone
                if (!usePhone && date != null) {
                    manualEpochDay = date.toEpochDay().toDouble()
                }
                showDateDialog = false
            }
        )
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    fun startAnimation(years: Int, step: Double) {
        usePhoneTime = false
        if (!isAnimating) {
            manualEpochDay = currentInstant.atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay().toDouble()
        }
        val currentLD = LocalDate.ofEpochDay(manualEpochDay.toLong())
        val targetLD = currentLD.plusYears(years.toLong())
        animationTargetEpoch = targetLD.toEpochDay().toDouble()
        animationStep = step
        isAnimating = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (!usePhoneTime) {
                            Text(
                                text = dateString,
                                style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        }
                        Text(
                            text = "Orrery",
                            style = TextStyle(color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Planet Transits") }, onClick = { currentScreen = Screen.TRANSITS; showMenu = false })
                        DropdownMenuItem(text = { Text("Schematic Orrery") }, onClick = { currentScreen = Screen.SCHEMATIC; showMenu = false })
                        DropdownMenuItem(text = { Text("To-scale Orrery") }, onClick = { currentScreen = Screen.SCALE; showMenu = false })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Location") }, onClick = { showMenu = false; showLocationDialog = true })
                        DropdownMenuItem(text = { Text("Date") }, onClick = { showMenu = false; showDateDialog = true })
                        DropdownMenuItem(text = { Text("About") }, onClick = { showMenu = false; showAboutDialog = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            if (currentScreen != Screen.TRANSITS) {
                BottomAppBar(
                    containerColor = Color.Black,
                    contentColor = Color.White
                ) {
                    if (isAnimating) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Button(
                                onClick = { isAnimating = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) { Text("STOP") }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val buttonColors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            val buttonPadding = PaddingValues(horizontal = 4.dp)
                            val buttonModifier = Modifier.defaultMinSize(minWidth = 1.dp)
                            val textStyle = TextStyle(fontSize = 12.sp)

                            Button(
                                onClick = { startAnimation(1, 0.125) },
                                colors = buttonColors,
                                contentPadding = buttonPadding,
                                modifier = buttonModifier
                            ) { Text("+1 Yr", style = textStyle) }

                            Button(
                                onClick = { startAnimation(10, 1.25) },
                                colors = buttonColors,
                                contentPadding = buttonPadding,
                                modifier = buttonModifier
                            ) { Text("+10 Yrs", style = textStyle) }

                            Button(
                                onClick = { startAnimation(100, 12.5) },
                                colors = buttonColors,
                                contentPadding = buttonPadding,
                                modifier = buttonModifier
                            ) { Text("+100 Yrs", style = textStyle) }

                            Button(
                                onClick = {
                                    usePhoneTime = true
                                    val now = LocalDate.now()
                                    manualEpochDay = now.toEpochDay().toDouble()
                                    currentInstant = Instant.now()
                                },
                                enabled = !usePhoneTime,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.DarkGray,
                                    contentColor = Color(0xFF00FF00),
                                    disabledContainerColor = Color.DarkGray.copy(alpha = 0.5f),
                                    disabledContentColor = Color.Gray
                                ),
                                contentPadding = buttonPadding,
                                modifier = buttonModifier
                            ) { Text("Reset", style = textStyle) }
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // INFO LINE
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentScreen == Screen.TRANSITS) {
                    Text(text = "Lat %.3f Lon %.4f".format(effectiveLat, effectiveLon), style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    Text(text = "UT $utString  LST $lstString", style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                } else {
                    Text(text = "Date: $dateString", style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    Text(text = "UT $utString", style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
            }

            // MAIN CONTENT
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                val displayEpoch = if (isAnimating || !usePhoneTime) manualEpochDay else effectiveDate.toEpochDay().toDouble()

                when (currentScreen) {
                    Screen.TRANSITS -> {
                        if (cache != null) GraphicsWindow(effectiveLat, effectiveLon, currentInstant, cache!!)
                    }
                    Screen.SCHEMATIC -> {
                        SchematicOrrery(displayEpoch)
                    }
                    Screen.SCALE -> {
                        ScaleOrrery(displayEpoch)
                    }
                }
            }
        }
    }
}

// --- COMPOSABLE: TO-SCALE ORRERY ---
@Composable
fun ScaleOrrery(epochDay: Double) {
    val planetList = remember { getOrreryPlanets() }
    var scale by remember { mutableStateOf(1f) }

    val baseFitAU = 31.25f
    val maxScale = baseFitAU / 0.47f
    val minScale = 1f

    val textPaint = remember {
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
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(minScale, maxScale)
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

            drawCircle(color = Color.Yellow, radius = 12f, center = Offset(cx, cy))

            val d = (2440587.5 + epochDay) - 2451545.0

            for (p in planetList) {
                val orbitPath = androidx.compose.ui.graphics.Path()
                for (angleIdx in 0..100) {
                    val M_sim = (angleIdx / 100.0) * 2.0 * Math.PI
                    var E = M_sim + p.e * sin(M_sim)
                    for (k in 0..5) { E += (M_sim - (E - p.e * sin(E))) / (1 - p.e * cos(E)) }

                    val xv = p.a * (cos(E) - p.e)
                    val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)
                    val v = atan2(yv, xv)

                    val w_bar = Math.toRadians(p.w_bar)
                    val N = Math.toRadians(p.N)
                    val i_rad = Math.toRadians(p.i)
                    val u = v + w_bar - N
                    val r = p.a * (1 - p.e * cos(E))

                    val x_ecl = r * (cos(u) * cos(N) - sin(u) * sin(N) * cos(i_rad))
                    val y_ecl = r * (cos(u) * sin(N) + sin(u) * cos(N) * cos(i_rad))

                    val px = cx + (x_ecl * currentPixelsPerAU).toFloat()
                    val py = cy - (y_ecl * currentPixelsPerAU).toFloat()

                    if (angleIdx == 0) orbitPath.moveTo(px, py) else orbitPath.lineTo(px, py)
                }
                orbitPath.close()
                drawPath(orbitPath, color = Color.Gray, style = Stroke(width = 2f))

                val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
                val w_bar_curr = Math.toRadians(p.w_bar)
                val M_curr = Lp - w_bar_curr

                var E_curr = M_curr + p.e * sin(M_curr)
                for (k in 0..5) { E_curr += (M_curr - (E_curr - p.e * sin(E_curr))) / (1 - p.e * cos(E_curr)) }

                val xv_curr = p.a * (cos(E_curr) - p.e)
                val yv_curr = p.a * sqrt(1 - p.e*p.e) * sin(E_curr)
                val v_curr = atan2(yv_curr, xv_curr)

                val N_curr = Math.toRadians(p.N)
                val i_curr = Math.toRadians(p.i)
                val u_curr = v_curr + w_bar_curr - N_curr
                val r_curr = p.a * (1 - p.e * cos(E_curr))

                val x_pos = r_curr * (cos(u_curr) * cos(N_curr) - sin(u_curr) * sin(N_curr) * cos(i_curr))
                val y_pos = r_curr * (cos(u_curr) * sin(N_curr) + sin(u_curr) * cos(N_curr) * cos(i_curr))

                val px = cx + (x_pos * currentPixelsPerAU).toFloat()
                val py = cy - (y_pos * currentPixelsPerAU).toFloat()

                drawCircle(color = p.color, radius = 12f, center = Offset(px, py))

                val dist = sqrt(x_pos*x_pos + y_pos*y_pos)
                if (dist > 0) {
                    val unitX = x_pos / dist
                    val unitY = y_pos / dist
                    val symbolOffset = 45f * 0.6f
                    val tx = px - (unitX * symbolOffset).toFloat()
                    val ty = py + (unitY * symbolOffset).toFloat()

                    val textY = ty - ((textPaint.descent() + textPaint.ascent()) / 2)
                    textPaint.color = p.color.toArgb()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(p.symbol, tx, textY, textPaint)
                    }
                }
            }

            val arrowDist = 32.0
            val ay = cy - (arrowDist * currentPixelsPerAU).toFloat()
            val arrowTipY = ay - 40f

            drawLine(color = Color.White, start = Offset(cx, ay), end = Offset(cx, arrowTipY), strokeWidth = 2f)
            val arrowHeadPath = Path().apply {
                moveTo(cx, arrowTipY - 5f)
                lineTo(cx - 5f, arrowTipY + 5f)
                lineTo(cx + 5f, arrowTipY + 5f)
                close()
            }
            val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)
                canvas.nativeCanvas.drawText("To Vernal Equinox", cx, arrowTipY - 10f, labelPaint)
            }
        }
    }
}

// --- COMPOSABLE: SCHEMATIC ORRERY ---
@Composable
fun SchematicOrrery(epochDay: Double) {
    val planetList = remember { getOrreryPlanets() }

    val textPaint = remember {
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
            textSize = 24f
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
        val orbitStep = maxRadius / 8f

        drawCircle(color = Color.Yellow, radius = 12f, center = Offset(cx, cy))

        val d = (2440587.5 + epochDay) - 2451545.0

        for (i in 0 until planetList.size) {
            val p = planetList[i]
            val radius = orbitStep * (i + 1)

            drawCircle(color = Color.Gray, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))

            val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0)
            val w_bar = Math.toRadians(p.w_bar)
            val M = Lp - w_bar

            var E = M + p.e * sin(M)
            for (k in 0..5) {
                val dE = (M - (E - p.e * sin(E))) / (1 - p.e * cos(E))
                E += dE
            }

            val xv = p.a * (cos(E) - p.e)
            val yv = p.a * sqrt(1 - p.e*p.e) * sin(E)
            val v = atan2(yv, xv)
            val helioLong = v + w_bar

            // CCW Calculation: x = cx - r*sin, y = cy - r*cos
            val px = cx - (radius * sin(helioLong)).toFloat()
            val py = cy - (radius * cos(helioLong)).toFloat()

            if (p.name == "Earth") {
                val earthTextY = py - ((textPaint.descent() + textPaint.ascent()) / 2)
                textPaint.color = p.color.toArgb()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(p.symbol, px, earthTextY, textPaint)
                }

                val moonOrbitRadius = orbitStep / 2f
                drawCircle(color = Color.Gray, radius = moonOrbitRadius, center = Offset(px, py), style = Stroke(width = 1f))

                val elongationRad = Math.toRadians(calculateMoonPhaseAngle(epochDay))
                val vecES_x = cx - px
                val vecES_y = py - cy
                val sunAngleStandard = atan2(vecES_y.toDouble(), vecES_x.toDouble())

                val moonAngleStandard = sunAngleStandard + elongationRad

                val mx = px + (moonOrbitRadius * cos(moonAngleStandard)).toFloat()
                val my = py - (moonOrbitRadius * sin(moonAngleStandard)).toFloat()

                drawCircle(color = Color.Gray, radius = 5f, center = Offset(mx, my))

            } else {
                drawCircle(color = p.color, radius = 12f, center = Offset(px, py))

                val textRadius = radius - 25f
                val tx = cx - (textRadius * sin(helioLong)).toFloat() // Also CCW
                val ty = cy - (textRadius * cos(helioLong)).toFloat()
                val textY = ty - ((textPaint.descent() + textPaint.ascent()) / 2)

                textPaint.color = p.color.toArgb()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(p.symbol, tx, textY, textPaint)
                }
            }
        }

        val outerRadius = orbitStep * 8f
        val arrowBaseY = cy - outerRadius - 15f
        val arrowTipY = arrowBaseY - 40f

        drawLine(color = Color.White, start = Offset(cx, arrowBaseY), end = Offset(cx, arrowTipY), strokeWidth = 2f)
        val arrowHeadPath = Path().apply {
            moveTo(cx, arrowTipY - 5f)
            lineTo(cx - 5f, arrowTipY + 5f)
            lineTo(cx + 5f, arrowTipY + 5f)
            close()
        }
        val paintWhite = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(arrowHeadPath, paintWhite)
            canvas.nativeCanvas.drawText("To Vernal Equinox", cx, arrowTipY - 10f, labelPaint)
        }
    }
}

// Separate list including Earth for Orrery View
fun getOrreryPlanets(): List<PlanetElements> {
    return listOf(
        PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
        PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
        PlanetElements("Earth",   "⊕", Color(0xFF87CEFA), 100.46, 0.985647, 1.00000, 0.01671, 0.000, 102.94, 0.0),
        PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
        PlanetElements("Jupiter", "♃", Color(0xFFFFA500), 34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
        PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
        PlanetElements("Uranus",  "⛢", Color(0xFF20B2AA), 313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
        PlanetElements("Neptune", "♆", Color(0xFF4D4DFF), 304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
    )
}

// --- DIALOGS ---

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var aboutText by remember { mutableStateOf("Loading...") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("about.txt")
                val reader = BufferedReader(InputStreamReader(inputStream))
                aboutText = reader.readText()
                reader.close()
            } catch (e: Exception) {
                aboutText = "File 'about.txt' not found in assets.\n\nPlease add a file named 'about.txt' to your project's 'src/main/assets/' folder."
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray),
            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 600.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("About", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.weight(1f).background(Color.Black).padding(8.dp)) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = aboutText,
                        style = TextStyle(color = Color.LightGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                    Text("OK", color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun LocationDialog(
    currentUsePhone: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Double, Double) -> Unit
) {
    var usePhone by remember { mutableStateOf(currentUsePhone) }
    var latDeg by remember { mutableStateOf("") }
    var latMin by remember { mutableStateOf("") }
    var latSec by remember { mutableStateOf("") }
    var lonDeg by remember { mutableStateOf("") }
    var lonMin by remember { mutableStateOf("") }
    var lonSec by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        if (usePhone) {
            onConfirm(true, 0.0, 0.0)
            return
        }
        try {
            val ld = latDeg.toIntOrNull(); val lm = latMin.toIntOrNull(); val ls = latSec.toDoubleOrNull()
            val lod = lonDeg.toIntOrNull(); val lom = lonMin.toIntOrNull(); val los = lonSec.toDoubleOrNull()

            if (ld == null || lm == null || ls == null || lod == null || lom == null || los == null) {
                errorMsg = "Please enter all fields."
                return
            }
            if (abs(ld) > 90) { errorMsg = "Latitude degrees must be -90 to 90"; return }
            if (lm !in 0..59) { errorMsg = "Latitude minutes must be 0-59"; return }
            if (ls < 0 || ls >= 60) { errorMsg = "Latitude seconds must be 0-59.9"; return }
            if (abs(lod) > 180) { errorMsg = "Longitude degrees must be -180 to 180"; return }
            if (lom !in 0..59) { errorMsg = "Longitude minutes must be 0-59"; return }
            if (los < 0 || los >= 60) { errorMsg = "Longitude seconds must be 0-59.9"; return }

            val latSign = if (ld < 0) -1.0 else 1.0
            val finalLat = (abs(ld) + (lm / 60.0) + (ls / 3600.0)) * latSign
            val lonSign = if (lod < 0) -1.0 else 1.0
            val finalLon = (abs(lod) + (lom / 60.0) + (los / 3600.0)) * lonSign

            if (abs(finalLat) > 90.0) { errorMsg = "Invalid Latitude."; return }
            if (abs(finalLon) > 180.0) { errorMsg = "Invalid Longitude."; return }
            onConfirm(false, finalLat, finalLon)
        } catch (e: Exception) {
            errorMsg = "Invalid number format."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.DarkGray)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enter Location", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePhone, onCheckedChange = { usePhone = it; errorMsg = null }, colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black))
                    Text("Use phone location", color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!usePhone) {
                    Text("Latitude (D M S)", color = Color.LightGray, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DmsInput(latDeg, { latDeg = it }, "Deg"); DmsInput(latMin, { latMin = it }, "Min"); DmsInput(latSec, { latSec = it }, "Sec")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Longitude (D M S)", color = Color.LightGray, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DmsInput(lonDeg, { lonDeg = it }, "Deg"); DmsInput(lonMin, { lonMin = it }, "Min"); DmsInput(lonSec, { lonSec = it }, "Sec")
                    }
                }
                if (errorMsg != null) { Spacer(modifier = Modifier.height(8.dp)); Text(errorMsg!!, color = Color.Red, fontSize = 14.sp) }
                Spacer(modifier = Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                    Button(onClick = { validateAndSubmit() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) { Text("OK", color = Color.Black) }
                }
            }
        }
    }
}

@Composable
fun DateDialog(
    currentUsePhone: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, LocalDate?) -> Unit
) {
    var usePhone by remember { mutableStateOf(currentUsePhone) }
    var dateString by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun validateAndSubmit() {
        if (usePhone) {
            onConfirm(true, null)
            return
        }
        try {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val parsedDate = LocalDate.parse(dateString, formatter)
            onConfirm(false, parsedDate)
        } catch (e: Exception) {
            errorMsg = "Invalid Date. Use DD/MM/YYYY"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.DarkGray)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enter Date", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePhone, onCheckedChange = { usePhone = it; errorMsg = null }, colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black))
                    Text("Use phone time", color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!usePhone) {
                    OutlinedTextField(
                        value = dateString,
                        onValueChange = { dateString = it },
                        label = { Text("DD/MM/YYYY", color = Color.Gray) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray, cursorColor = Color.White)
                    )
                    Text("Format DD/MM/YYYY", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                }
                if (errorMsg != null) { Spacer(modifier = Modifier.height(8.dp)); Text(errorMsg!!, color = Color.Red, fontSize = 14.sp) }
                Spacer(modifier = Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                    Button(onClick = { validateAndSubmit() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) { Text("OK", color = Color.Black) }
                }
            }
        }
    }
}

@Composable
fun DmsInput(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Gray, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.Gray,
            cursorColor = Color.White
        )
    )
}

// --- RENDERING ---
@Composable
fun GraphicsWindow(lat: Double, lon: Double, now: Instant, cache: AstroCache) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val paddingLeft = 35f
        val paddingRight = 42f
        val drawingWidth = w - paddingLeft - paddingRight
        val centerX = paddingLeft + (drawingWidth / 2f)

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
            return (h - ((daysOffset / 365.25) * h)).toFloat()
        }
        fun getYForEpochDay(targetEpochDay: Double): Float {
            val daysOffset = targetEpochDay - nowEpochDay
            return (h - ((daysOffset / 365.25) * h)).toFloat()
        }
        fun getXSunsetForDate(date: LocalDate): Float {
            val epochDay = date.toEpochDay().toDouble()
            val (_, setTimePrev) = cache.getSunTimes(epochDay - 1.0, false)
            return (centerX + ((setTimePrev - 24.0) * pixelsPerHour)).toFloat()
        }

        val blueGreen = Color(0xFF20B2AA)
        val orange = Color(0xFFFFA500)
        val neptuneBlue = Color(0xFF6495ED)
        val planets = listOf(
            PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
            PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
            PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
            PlanetElements("Jupiter", "♃", orange,        34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
            PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
            PlanetElements("Uranus",  "⛢", blueGreen,    313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
            PlanetElements("Neptune", "♆", neptuneBlue,  304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
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

        val darkBlueArgb = 0xFF0000D1.toInt()
        val blackArgb = android.graphics.Color.BLACK
        val twilightPaint = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }

        drawIntoCanvas { canvas ->
            for (y in textHeight.toInt() until h.toInt()) {
                val fraction = (h - y) / h
                val daysOffset = fraction * 365.25
                val targetEpochDay = nowEpochDay + daysOffset
                val (_, setTimePrev) = cache.getSunTimes(targetEpochDay - 1.0, false)
                val (riseTimeCurr, _) = cache.getSunTimes(targetEpochDay, false)
                val (_, astroSetPrev) = cache.getSunTimes(targetEpochDay - 1.0, true)
                val (astroRiseCurr, _) = cache.getSunTimes(targetEpochDay, true)
                val xSunset = centerX + ((setTimePrev - 24.0) * pixelsPerHour)
                val xSunrise = centerX + (riseTimeCurr * pixelsPerHour)
                val validSunset = !xSunset.isNaN()
                val validSunrise = !xSunrise.isNaN()

                if (validSunset && validSunrise && (astroSetPrev.isNaN() || astroRiseCurr.isNaN())) {
                    twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), intArrayOf(darkBlueArgb, blackArgb, darkBlueArgb), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                } else {
                    if (validSunset && !astroSetPrev.isNaN()) {
                        val xAstroEnd = centerX + ((astroSetPrev - 24.0) * pixelsPerHour)
                        twilightPaint.shader = LinearGradient(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), darkBlueArgb, blackArgb, Shader.TileMode.CLAMP)
                        canvas.nativeCanvas.drawLine(xSunset.toFloat(), y.toFloat(), xAstroEnd.toFloat(), y.toFloat(), twilightPaint)
                    }
                    if (validSunrise && !astroRiseCurr.isNaN()) {
                        var diffAstro = astroRiseCurr - 24.0
                        if (diffAstro < -12.0) diffAstro += 24.0 else if (diffAstro > 12.0) diffAstro -= 24.0
                        val xAstroStart = centerX + (diffAstro * pixelsPerHour)
                        twilightPaint.shader = LinearGradient(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), blackArgb, darkBlueArgb, Shader.TileMode.CLAMP)
                        canvas.nativeCanvas.drawLine(xAstroStart.toFloat(), y.toFloat(), xSunrise.toFloat(), y.toFloat(), twilightPaint)
                    }
                }

                if (validSunset) sunPoints.add(Offset(xSunset.toFloat(), y.toFloat()))
                if (validSunrise) sunPoints.add(Offset(xSunrise.toFloat(), y.toFloat()))

                if (validSunset && validSunrise) {
                    for (planet in planets) {
                        val events = cache.getPlanetEvents(targetEpochDay, planet.name)
                        val targetHourOffset = when (planet.name) { "Mars" -> -2.0; "Jupiter" -> -1.0; "Saturn" -> 0.0; "Uranus" -> 1.0; "Neptune" -> 2.0; else -> 0.0 }
                        val targetX = centerX + (targetHourOffset * pixelsPerHour)
                        fun processEvent(time: Double, list: ArrayList<Offset>, keySuffix: String) {
                            if (time.isNaN()) return
                            var diff = time - 24.0
                            if (diff < -12.0) diff += 24.0 else if (diff > 12.0) diff -= 24.0
                            val xPos = centerX + (diff * pixelsPerHour)
                            val yPos = y.toFloat()
                            if (xPos >= xSunset && xPos <= xSunrise) {
                                list.add(Offset(xPos.toFloat(), yPos))
                                val dist = abs(xPos - targetX)
                                val labelTracker = bestLabels["${planet.name}_$keySuffix"]!!
                                if (dist < labelTracker.minDistToCenter) {
                                    labelTracker.minDistToCenter = dist.toFloat()
                                    labelTracker.x = xPos.toFloat(); labelTracker.y = yPos; labelTracker.found = true
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

        drawPoints(points = sunPoints, pointMode = PointMode.Points, color = Color.White, strokeWidth = 2f)

        drawIntoCanvas { canvas ->
            val monthPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
            val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }
            val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
            val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
            var d = startDate
            while (d.isBefore(endDate)) {
                val nextMonth = d.plusMonths(1)
                val yStart = getYForDate(d)
                val x1_grid = getXSunsetForDate(d)
                val epochDayGrid = d.toEpochDay().toDouble()
                val (riseGrid, _) = cache.getSunTimes(epochDayGrid, false)
                if (yStart >= textHeight && yStart <= h && !riseGrid.isNaN()) {
                    var diffGrid = riseGrid - 24.0
                    if (diffGrid < -12.0) diffGrid += 24.0 else if (diffGrid > 12.0) diffGrid -= 24.0
                    val x2_grid = centerX + (diffGrid * pixelsPerHour)
                    canvas.nativeCanvas.drawLine(x1_grid, yStart, x2_grid.toFloat(), yStart, gridPaint)
                }
                var visibleDays = 0
                var iterDate = d
                while (iterDate.isBefore(nextMonth)) { if (getYForDate(iterDate) in textHeight..h) visibleDays++; iterDate = iterDate.plusDays(1) }
                if (visibleDays >= 15) {
                    val monthName = d.format(DateTimeFormatter.ofPattern("MMM"))
                    val yEnd = getYForDate(nextMonth)
                    val yMid = (yStart + yEnd) / 2f
                    val midMonthDate = d.withDayOfMonth(15)
                    val xSunsetMid = getXSunsetForDate(midMonthDate)
                    val targetX = xSunsetMid - monthPaint.textSize
                    val finalX = max(25f, targetX)
                    val x1 = getXSunsetForDate(d); val x2 = getXSunsetForDate(nextMonth)
                    val angleDeg = Math.toDegrees(atan2((yEnd - yStart).toDouble(), (x2 - x1).toDouble())).toFloat()
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.rotate(angleDeg, finalX, yMid)
                    canvas.nativeCanvas.drawText(monthName, finalX, yMid, monthPaint)
                    canvas.nativeCanvas.restore()
                }
                d = nextMonth
            }
        }

        if (w > 1600f) {
            drawIntoCanvas { canvas ->
                val gridPaint = Paint().apply { color = 0xFF666666.toInt(); strokeWidth = 1f; style = Paint.Style.STROKE }
                for (y in textHeight.toInt() until h.toInt()) {
                    val targetEpochDay = nowEpochDay + ((h - y) / h * 365.25)
                    val (_, setTimePrev) = cache.getSunTimes(targetEpochDay - 1.0, false)
                    val (riseTimeCurr, _) = cache.getSunTimes(targetEpochDay, false)
                    val xSunset = centerX + ((setTimePrev - 24.0) * pixelsPerHour)
                    val xSunrise = centerX + (riseTimeCurr * pixelsPerHour)
                    if (!xSunset.isNaN() && !xSunrise.isNaN()) {
                        for (k in -14..14) {
                            val xGrid = centerX + (k * pixelsPerHour)
                            if (xGrid > xSunset && xGrid < xSunrise) canvas.nativeCanvas.drawPoint(xGrid.toFloat(), y.toFloat(), gridPaint)
                        }
                    }
                }
            }
        }

        for (planet in planets) {
            val isInner = planet.name == "Mercury" || planet.name == "Venus"
            val riseSetColor = if (isInner) planet.color else planet.color.copy(alpha = 0.35f)
            if (!isInner) drawPoints(points = planetTransits[planet.name]!!, pointMode = PointMode.Points, color = planet.color, strokeWidth = 4f)
            drawPoints(points = planetRises[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
            drawPoints(points = planetSets[planet.name]!!, pointMode = PointMode.Points, color = riseSetColor, strokeWidth = 3f)
        }

        val fullMoons = ArrayList<Offset>(); val newMoons = ArrayList<Offset>(); val firstQuarters = ArrayList<Offset>(); val lastQuarters = ArrayList<Offset>()
        var prevElong = calculateMoonPhaseAngle(nowEpochDay - 1.0)
        for (dIdx in 0..380) {
            val scanEpochDay = nowEpochDay + dIdx
            val currElong = calculateMoonPhaseAngle(scanEpochDay)
            fun norm(a: Double): Double { var v = a % 360.0; if (v > 180) v -= 360; if (v <= -180) v += 360; return v }
            fun checkCrossing(targetAngle: Double, list: ArrayList<Offset>) {
                val pNorm = norm(prevElong - targetAngle); val cNorm = norm(currElong - targetAngle)
                if (pNorm < 0 && cNorm >= 0) {
                    val exactMoonDay = (scanEpochDay - 1.0) + (-pNorm / (cNorm - pNorm))
                    val (riseTime, _) = cache.getSunTimes(scanEpochDay - 1.0, false)
                    if (!riseTime.isNaN()) {
                        val xMoon = centerX + (riseTime * pixelsPerHour) + 15f
                        val yMoon = getYForEpochDay(exactMoonDay)
                        if (yMoon >= textHeight && yMoon <= h) list.add(Offset(xMoon.toFloat(), yMoon))
                    }
                }
            }
            checkCrossing(0.0, newMoons); checkCrossing(90.0, firstQuarters); checkCrossing(180.0, fullMoons); checkCrossing(270.0, lastQuarters)
            prevElong = currElong
        }

        drawIntoCanvas { canvas ->
            val timePaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.MONOSPACE }
            for (k in -14..14) {
                val xPos = centerX + (k * pixelsPerHour)
                if (xPos >= paddingLeft && xPos <= (w - paddingRight)) {
                    var hour = (k % 24); if (hour < 0) hour += 24
                    canvas.nativeCanvas.drawText(hour.toString(), xPos.toFloat(), textY, timePaint)
                }
            }
            val mainTextPaint = Paint().apply { textSize = 42f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
            val subTextPaint = Paint().apply { textSize = 27f; textAlign = Paint.Align.LEFT; isAntiAlias = true; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
            fun drawLabel(x: Float, y: Float, sym: String, sub: String, col: Int) {
                val sw = mainTextPaint.measureText(sym); val subw = subTextPaint.measureText(sub)
                val sx = x + sw; val subx = sx + (sw/2f) + (subw * (if (sub == "s") 0.375f else 0.75f))
                mainTextPaint.style = Paint.Style.STROKE; mainTextPaint.strokeWidth = 4f; mainTextPaint.color = android.graphics.Color.BLACK
                canvas.nativeCanvas.drawText(sym, sx, y, mainTextPaint)
                mainTextPaint.style = Paint.Style.FILL; mainTextPaint.color = col
                canvas.nativeCanvas.drawText(sym, sx, y, mainTextPaint)
                subTextPaint.style = Paint.Style.STROKE; subTextPaint.strokeWidth = 3f; subTextPaint.color = android.graphics.Color.BLACK
                canvas.nativeCanvas.drawText(sub, subx, y+8f, subTextPaint)
                subTextPaint.style = Paint.Style.FILL; subTextPaint.color = col
                canvas.nativeCanvas.drawText(sub, subx, y+8f, subTextPaint)
            }
            for (p in planets) {
                if (p.name !in listOf("Mercury", "Venus")) bestLabels["${p.name}_t"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "t", p.color.toArgb()) }
                bestLabels["${p.name}_r"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "r", p.color.toArgb()) }
                bestLabels["${p.name}_s"]?.let { if(it.found) drawLabel(it.x, it.y, p.symbol, "s", p.color.toArgb()) }
            }
            val moonFill = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
            val moonStroke = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
            for (pos in fullMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonFill)
            for (pos in newMoons) canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
            val startAngleFirst = if (lat >= 0) -90f else 90f
            for (pos in firstQuarters) {
                canvas.nativeCanvas.drawArc(RectF(pos.x-10f, pos.y-10f, pos.x+10f, pos.y+10f), startAngleFirst, 180f, true, moonFill)
                canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
            }
            val startAngleLast = if (lat >= 0) 90f else -90f
            for (pos in lastQuarters) {
                canvas.nativeCanvas.drawArc(RectF(pos.x-10f, pos.y-10f, pos.x+10f, pos.y+10f), startAngleLast, 180f, true, moonFill)
                canvas.nativeCanvas.drawCircle(pos.x, pos.y, 10f, moonStroke)
            }
        }
    }
}

// --- MATH FUNCTIONS ---
fun calculateCache(nowDate: LocalDate, lat: Double, lon: Double, zoneId: ZoneId): AstroCache {
    val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
    val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
    val startEpochDay = startDate.toEpochDay().toDouble()
    val daysCount = (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 2

    val sunRise = DoubleArray(daysCount); val sunSet = DoubleArray(daysCount)
    val astroRise = DoubleArray(daysCount); val astroSet = DoubleArray(daysCount)

    val blueGreen = Color(0xFF20B2AA); val orange = Color(0xFFFFA500); val neptuneBlue = Color(0xFF6495ED)
    val planets = listOf(
        PlanetElements("Mercury", "☿", Color.Gray,   252.25, 4.09233, 0.38710, 0.20563, 7.005,  77.46, 48.33),
        PlanetElements("Venus",   "♀", Color.White,  181.98, 1.60213, 0.72333, 0.00677, 3.390, 131.53, 76.68),
        PlanetElements("Mars",    "♂", Color.Red,    355.45, 0.52403, 1.52368, 0.09340, 1.850, 336.04, 49.558),
        PlanetElements("Jupiter", "♃", orange,        34.40, 0.08308, 5.20260, 0.04849, 1.305,  14.75, 100.46),
        PlanetElements("Saturn",  "♄", Color.Yellow,  49.94, 0.03346, 9.55490, 0.05555, 2.485,  92.43, 113.71),
        PlanetElements("Uranus",  "⛢", blueGreen,    313.23, 0.01173, 19.1817, 0.04731, 0.773, 170.96,  74.00),
        PlanetElements("Neptune", "♆", neptuneBlue,  304.88, 0.00598, 30.0582, 0.00860, 1.770,  44.97, 131.78)
    )
    val planetMap = mutableMapOf<String, Triple<DoubleArray, DoubleArray, DoubleArray>>()
    for (p in planets) planetMap[p.name] = Triple(DoubleArray(daysCount), DoubleArray(daysCount), DoubleArray(daysCount))

    val standardOffset = zoneId.rules.getStandardOffset(nowDate.atStartOfDay(zoneId).toInstant())
    val offsetHours = standardOffset.totalSeconds / 3600.0

    for (i in 0 until daysCount) {
        val currentEpochDay = startEpochDay + i
        val (rise, set) = calculateSunTimes(currentEpochDay, lat, lon, offsetHours)
        sunRise[i] = rise; sunSet[i] = set
        val (aRise, aSet) = calculateSunTimes(currentEpochDay, lat, lon, offsetHours, -18.0)
        astroRise[i] = aRise; astroSet[i] = aSet
        for (p in planets) {
            val ev = calculatePlanetEvents(currentEpochDay, lat, lon, offsetHours, p)
            val arrays = planetMap[p.name]!!
            arrays.first[i] = ev.rise; arrays.second[i] = ev.transit; arrays.third[i] = ev.set
        }
    }
    return AstroCache(startEpochDay, daysCount, sunRise, sunSet, astroRise, astroSet, planetMap)
}

fun calculateLST(instant: Instant, lon: Double): String {
    val epochSeconds = instant.epochSecond
    val jd = epochSeconds / 86400.0 + 2440587.5
    val d = jd - 2451545.0
    var gmst = 18.697374558 + 24.06570982441908 * d
    gmst %= 24.0; if (gmst < 0) gmst += 24.0
    var lst = gmst + (lon / 15.0)
    lst %= 24.0; if (lst < 0) lst += 24.0
    return "%02d:%02d".format(floor(lst).toInt(), floor((lst - floor(lst)) * 60).toInt())
}

fun calculateSunTimes(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, altitude: Double = -0.833): Pair<Double, Double> {
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))
    val cosH = (sin(Math.toRadians(altitude)) - sin(Math.toRadians(lat)) * sin(delta)) / (cos(Math.toRadians(lat)) * cos(delta))
    if (cosH < -1.0 || cosH > 1.0) return Pair(Double.NaN, Double.NaN)
    val H_hours = Math.toDegrees(acos(cosH)) / 15.0
    val raHours = Math.toDegrees(alpha) / 15.0
    var GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0; if (GMST0 < 0) GMST0 += 24.0
    var transitUT = raHours - (lon / 15.0) - GMST0
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transitStandard = transitUT + timezoneOffset
    var rise = transitStandard - H_hours; while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
    var set = transitStandard + H_hours; while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
    return Pair(rise, set)
}

fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    val d = (2440587.5 + epochDay + 0.5) - 2451545.0
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le_earth = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
    val Re = 1.00014 - 0.01671 * cos(Me) - 0.00014 * cos(2 * Me)
    val xe = Re * cos(Le_earth); val ye = Re * sin(Le_earth); val ze = 0.0

    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0); val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar); var Mp = Lp - w_bar_p
    var Ep = Mp + p.e * sin(Mp); Ep = Mp + p.e * sin(Ep); Ep = Mp + p.e * sin(Ep)
    val xv = p.a * (cos(Ep) - p.e); val yv = p.a * sqrt(1 - p.e*p.e) * sin(Ep)
    val v = atan2(yv, xv); val u = v + w_bar_p - Np
    val rp = sqrt(xv*xv + yv*yv)
    val xh = rp * (cos(u) * cos(Np) - sin(u) * sin(Np) * cos(ip))
    val yh = rp * (cos(u) * sin(Np) + sin(u) * cos(Np) * cos(ip))
    val zh = rp * (sin(u) * sin(ip))
    val xg = xh - xe; val yg = yh - ye; val zg = zh - ze
    val ecl = Math.toRadians(23.439 - 0.0000004 * d)
    val xeq = xg; val yeq = yg * cos(ecl) - zg * sin(ecl); val zeq = yg * sin(ecl) + zg * cos(ecl)
    val raHours = Math.toDegrees(atan2(yeq, xeq)) / 15.0
    val decRad = atan2(zeq, sqrt(xeq*xeq + yeq*yeq))

    var GMST0 = (6.697374558 + 0.06570982441908 * d) % 24.0; if (GMST0 < 0) GMST0 += 24.0
    var transitUT = raHours - (lon / 15.0) - GMST0; while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transitStandard = transitUT + timezoneOffset
    val cosH = (sin(Math.toRadians(-0.5667)) - sin(Math.toRadians(lat)) * sin(decRad)) / (cos(Math.toRadians(lat)) * cos(decRad))

    if (cosH >= -1.0 && cosH <= 1.0) {
        val H_hours = Math.toDegrees(acos(cosH)) / 15.0
        var rise = transitStandard - H_hours; var set = transitStandard + H_hours
        while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
        while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
        var tr = transitStandard; while(tr < 0) tr+=24.0; while(tr>=24.0) tr-=24.0
        return PlanetEvents(rise, tr, set)
    }
    return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
}

fun calculateMoonPhaseAngle(epochDay: Double): Double {
    val d = (2440587.5 + epochDay) - 2451545.0
    val Ms = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Ls = Math.toRadians((280.466 + 0.98564736 * d) % 360.0)
    val trueLongSun = Math.toDegrees(Ls) + (1.915 * sin(Ms) + 0.020 * sin(2 * Ms))
    val L = (218.316 + 13.176396 * d) % 360.0
    val M = Math.toRadians((134.963 + 13.064993 * d) % 360.0)
    val lambda = L + 6.289 * sin(M)
    var diff = (lambda - trueLongSun) % 360.0; if (diff < 0) diff += 360.0
    return diff
}