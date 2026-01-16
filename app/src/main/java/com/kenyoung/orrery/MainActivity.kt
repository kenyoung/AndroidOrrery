package com.kenyoung.orrery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
data class RaDec(val ra: Double, val dec: Double)

// Navigation Enum
enum class Screen { TRANSITS, ELEVATIONS, SCHEMATIC, SCALE, TIMES, ANALEMMA }

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
    val context = LocalContext.current

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

    // Use System Zone for everything
    val zoneId = ZoneId.systemDefault()

    // Helper to calculate Instant from Manual Epoch (Local Standard Time)
    fun getInstantFromManual(epochDay: Double): Instant {
        val days = epochDay.toLong()
        val frac = epochDay - days
        val nanos = (frac * 86_400_000_000_000L).toLong()
        val localDate = LocalDate.ofEpochDay(days)
        val localTime = LocalTime.ofNanoOfDay(nanos)

        // Find Standard Offset (ignoring DST)
        val refInstant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val standardOffset = zoneId.rules.getStandardOffset(refInstant)

        return LocalDateTime.of(localDate, localTime).toInstant(standardOffset)
    }

    // Helper to calculate Manual Epoch from Instant (Local Standard Time)
    fun getManualFromInstant(inst: Instant): Double {
        val standardOffset = zoneId.rules.getStandardOffset(inst)
        val localDateTime = inst.atOffset(standardOffset).toLocalDateTime()
        val days = localDateTime.toLocalDate().toEpochDay()
        val sec = localDateTime.toLocalTime().toSecondOfDay()
        val nano = localDateTime.toLocalTime().nano
        return days + ((sec + (nano / 1_000_000_000.0)) / 86400.0)
    }

    // Animation Loop
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            while (abs(manualEpochDay - animationTargetEpoch) > animationStep) {
                manualEpochDay += animationStep
                currentInstant = getInstantFromManual(manualEpochDay)
                delay(12L) // ~80 frames per second
            }
            manualEpochDay = animationTargetEpoch
            currentInstant = getInstantFromManual(manualEpochDay)
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

    // Standard Time Loop (Modified for 10Hz updates on Times screen)
    LaunchedEffect(usePhoneTime, isAnimating, currentScreen) {
        if (usePhoneTime && !isAnimating) {
            while (true) {
                val now = Instant.now()
                currentInstant = now

                if (currentScreen == Screen.TIMES) {
                    // Update at 10Hz
                    delay(100L)
                } else {
                    // Update once per minute (Analemma fits here)
                    val currentMillis = now.toEpochMilli()
                    val millisUntilNextMinute = 60_000 - (currentMillis % 60_000)
                    delay(millisUntilNextMinute)
                }
            }
        } else if (!usePhoneTime && !isAnimating) {
            currentInstant = getInstantFromManual(manualEpochDay)
        }
    }

    // Calculation Loop
    val effectiveDate = currentInstant.atZone(zoneId).toLocalDate()

    val cache by produceState<AstroCache?>(initialValue = null, currentScreen, effectiveDate.toEpochDay(), effectiveLat, effectiveLon) {
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

    fun startAnimation(years: Int, step: Double) {
        usePhoneTime = false
        if (!isAnimating) {
            manualEpochDay = getManualFromInstant(currentInstant)
        }
        val currentLD = LocalDate.ofEpochDay(manualEpochDay.toLong())
        val targetLD = currentLD.plusYears(years.toLong())
        animationTargetEpoch = targetLD.toEpochDay().toDouble() + (manualEpochDay - manualEpochDay.toLong())
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
                        DropdownMenuItem(
                            text = { Text("Planet Transits") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.TRANSITS
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Planet Elevations") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.ELEVATIONS
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Schematic Orrery") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.SCHEMATIC
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("To-scale Orrery") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.SCALE
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Astronomical Times") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.TIMES
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Analemma") },
                            onClick = {
                                isAnimating = false
                                currentScreen = Screen.ANALEMMA
                                showMenu = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Location") }, onClick = { showMenu = false; showLocationDialog = true })
                        DropdownMenuItem(text = { Text("Date") }, onClick = { showMenu = false; showDateDialog = true })
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                showMenu = false
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kenyoung.github.io/AndroidOrrery/"))
                                context.startActivity(intent)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            // Hide bottom bar for Times, Analemma, and Elevations
            if (currentScreen != Screen.TRANSITS && currentScreen != Screen.TIMES && currentScreen != Screen.ANALEMMA && currentScreen != Screen.ELEVATIONS) {
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
            // INFO LINE (Hidden on Times, Analemma, and Elevations)
            if (currentScreen != Screen.TIMES && currentScreen != Screen.ANALEMMA && currentScreen != Screen.ELEVATIONS) {
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
            }

            // MAIN CONTENT
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                val displayEpoch = if (isAnimating || !usePhoneTime) manualEpochDay else effectiveDate.toEpochDay().toDouble()

                when (currentScreen) {
                    Screen.TRANSITS -> {
                        if (cache != null) GraphicsWindow(effectiveLat, effectiveLon, currentInstant, cache!!, zoneId, usePhoneLocation && usePhoneTime)
                    }
                    Screen.ELEVATIONS -> {
                        PlanetElevationsScreen(displayEpoch, effectiveLat, effectiveLon, currentInstant)
                    }
                    Screen.SCHEMATIC -> {
                        SchematicOrrery(displayEpoch)
                    }
                    Screen.SCALE -> {
                        ScaleOrrery(displayEpoch)
                    }
                    Screen.TIMES -> {
                        TimesScreen(currentInstant, effectiveLat, effectiveLon)
                    }
                    Screen.ANALEMMA -> {
                        AnalemmaScreen(currentInstant, effectiveLat)
                    }
                }
            }
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

                val contentAlpha = if (usePhone) 0.38f else 1f
                val inputColor = if (usePhone) Color.Gray else Color.Green

                Text("Latitude (D M S)", color = Color.LightGray.copy(alpha = contentAlpha), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DmsInput(latDeg, { latDeg = it }, "Deg", !usePhone, inputColor); DmsInput(latMin, { latMin = it }, "Min", !usePhone, inputColor); DmsInput(latSec, { latSec = it }, "Sec", !usePhone, inputColor)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Longitude (D M S)", color = Color.LightGray.copy(alpha = contentAlpha), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DmsInput(lonDeg, { lonDeg = it }, "Deg", !usePhone, inputColor); DmsInput(lonMin, { lonMin = it }, "Min", !usePhone, inputColor); DmsInput(lonSec, { lonSec = it }, "Sec", !usePhone, inputColor)
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
            val formatter = DateTimeFormatter.ofPattern("d/M/yyyy")
            val parsedDate = LocalDate.parse(dateString, formatter)
            onConfirm(false, parsedDate)
        } catch (e: Exception) {
            errorMsg = "Invalid Date. Use D/M/YYYY"
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

                val inputColor = if (usePhone) Color.Gray else Color.Green

                OutlinedTextField(
                    value = dateString,
                    onValueChange = { dateString = it },
                    label = { Text("D/M/YYYY", color = inputColor) },
                    singleLine = true,
                    enabled = !usePhone,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = inputColor, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray, cursorColor = Color.White)
                )

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
fun DmsInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, textColor: Color) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = textColor, fontSize = 10.sp) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.Gray,
            cursorColor = Color.White
        )
    )
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

    // FIX: Use Longitude-based offset, NOT system zone, to ensure graph centering.
    val offsetHours = round(lon / 15.0)

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

fun calculateSunDeclination(epochDay: Double): Double {
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))
    return delta
}

// SHARED FUNCTION
fun calculateEquationOfTimeMinutes(epochDay: Double): Double {
    val jd = epochDay + 2440587.5
    val n = jd - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0
    if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0
    if (g < 0) g += 360.0
    val lambda = L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(Math.toRadians(2 * g))
    val epsilon = 23.439 - 0.0000004 * n
    val alphaRad = atan2(cos(Math.toRadians(epsilon)) * sin(Math.toRadians(lambda)), cos(Math.toRadians(lambda)))
    var alphaDeg = Math.toDegrees(alphaRad)
    if (alphaDeg < 0) alphaDeg += 360.0
    var E_deg = L - alphaDeg
    while (E_deg > 180) E_deg -= 360.0
    while (E_deg <= -180) E_deg += 360.0
    // Convert degrees to minutes of time: degrees / 15 * 60 = degrees * 4
    return E_deg * 4.0
}

fun calculatePlanetEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double, p: PlanetElements): PlanetEvents {
    val d = (2440587.5 + epochDay + 0.5) - 2451545.0
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le_earth = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
    val Re = 1.00014 - 0.01671 * cos(Me) - 0.00014 * cos(2 * Me)
    val xe = Re * cos(Le_earth); val ye = Re * sin(Le_earth); val ze = 0.0

    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0); val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar); var Mp = Lp - w_bar_p
    val Ep = solveKepler(Mp, p.e)
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

// NEW MOON POSITION MATH (Low Precision, suitable for visual rise/set)
fun calculateMoonPosition(epochDay: Double): RaDec {
    val T = (epochDay + 2440587.5 - 2451545.0) / 36525.0
    // Mean Longitude
    val L_prime = Math.toRadians(218.3164477 + 481267.88123421 * T)
    // Mean Elongation
    val D = Math.toRadians(297.8501921 + 445267.1114034 * T)
    // Sun's Mean Anomaly
    val M = Math.toRadians(357.5291092 + 35999.0502909 * T)
    // Moon's Mean Anomaly
    val M_prime = Math.toRadians(134.9633964 + 477198.8675055 * T)
    // Moon's Argument of Latitude
    val F = Math.toRadians(93.2720950 + 483202.0175233 * T)

    // Ecliptic Longitude (lambda) - Major terms
    val lambda = L_prime + Math.toRadians(6.289 * sin(M_prime)) + Math.toRadians(-1.274 * sin(M_prime - 2 * D)) + Math.toRadians(0.658 * sin(2 * D)) + Math.toRadians(-0.186 * sin(M))

    // Ecliptic Latitude (beta) - Major terms
    val beta = Math.toRadians(5.128 * sin(F)) + Math.toRadians(0.280 * sin(M_prime + F))

    // Obliquity of Ecliptic
    val epsilon = Math.toRadians(23.439291 - 0.0130042 * T)

    // Convert to RA/Dec
    val x = cos(beta) * cos(lambda)
    val y = cos(epsilon) * cos(beta) * sin(lambda) - sin(epsilon) * sin(beta)
    val z = sin(epsilon) * cos(beta) * sin(lambda) + cos(epsilon) * sin(beta)

    val raRad = atan2(y, x)
    val decRad = asin(z)

    var raHours = Math.toDegrees(raRad) / 15.0
    if (raHours < 0) raHours += 24.0
    val decDeg = Math.toDegrees(decRad)

    return RaDec(raHours, decDeg)
}

fun calculateMoonEvents(epochDay: Double, lat: Double, lon: Double, timezoneOffset: Double): PlanetEvents {
    // Standard approximation: Calculate RA/Dec at approx transit (local noon-ish)
    val (raHours, decDeg) = calculateMoonPosition(epochDay + 0.5)

    // Calculate times
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0; if (GMST0 < 0) GMST0 += 24.0
    var transitUT = raHours - (lon / 15.0) - GMST0
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transitStandard = transitUT + timezoneOffset

    val cosH = (sin(Math.toRadians(-0.5667)) - sin(Math.toRadians(lat)) * sin(Math.toRadians(decDeg))) / (cos(Math.toRadians(lat)) * cos(Math.toRadians(decDeg)))

    if (cosH >= -1.0 && cosH <= 1.0) {
        val H_hours = Math.toDegrees(acos(cosH)) / 15.0
        // Approx motion correction for Moon (lags ~50 mins/day => ~2 mins/hr)
        // A simple factor 1.035 improves the rise/set spacing relative to transit
        val H_corrected = H_hours * 1.035

        var rise = transitStandard - H_corrected; while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
        var set = transitStandard + H_corrected; while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
        var tr = transitStandard; while(tr < 0) tr+=24.0; while(tr>=24.0) tr-=24.0
        return PlanetEvents(rise, tr, set)
    }
    return PlanetEvents(Double.NaN, Double.NaN, Double.NaN)
}

fun calculateAltitude(epochDay: Double, lat: Double, lon: Double, objectRa: Double, objectDec: Double, timeStandard: Double, timezoneOffset: Double): Double {
    // 1. Convert Standard Time to UT
    var ut = timeStandard - timezoneOffset
    // 2. Calculate GMST for this moment
    // n = (JD at 0h) + (Time/24)
    val n0 = (2440587.5 + epochDay) - 2451545.0
    val n = n0 + (ut / 24.0) // approx
    var gmst = 18.697374558 + 24.06570982441908 * n
    gmst %= 24.0; if (gmst < 0) gmst += 24.0
    // 3. Calculate LST
    var lst = gmst + (lon / 15.0)
    lst %= 24.0; if (lst < 0) lst += 24.0
    // 4. Hour Angle
    var HA = lst - objectRa
    while (HA < -12) HA += 24.0
    while (HA > 12) HA -= 24.0
    val HA_rad = Math.toRadians(HA * 15.0)
    // 5. Altitude
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(objectDec)
    val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(HA_rad)
    return Math.toDegrees(asin(sinAlt))
}

fun solveKepler(M: Double, e: Double): Double {
    var E = M + e * sin(M)
    for (k in 0..5) {
        val dE = (M - (E - e * sin(E))) / (1 - e * cos(E))
        E += dE
    }
    return E
}