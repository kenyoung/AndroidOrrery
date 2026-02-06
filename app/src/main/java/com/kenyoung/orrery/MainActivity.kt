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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        // Initialize Engine (Load CSVs in background)
        GlobalScope.launch(Dispatchers.IO) {
            EphemerisManager.loadEphemeris(applicationContext)
        }

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
        } catch (e: SecurityException) {
            setContent {
                OrreryApp(initialGpsLat = 0.0, initialGpsLon = 0.0)
            }
        }
    }
}

// Navigation Enum
enum class Screen {
    TRANSITS, ELEVATIONS, PHENOMENA, COMPASS, MOON_CALENDAR, LUNAR_ECLIPSES,
    JOVIAN_MOONS, JOVIAN_EVENTS, SCHEMATIC, SCALE, TIMES, ANALEMMA,
    METEOR_SHOWERS
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
    // Stores the last text entered by the user (LatDeg, LatMin, LatSec, LonDeg, LonMin, LonSec)
    var savedLocationInput by remember { mutableStateOf<List<String>?>(null) }

    val effectiveLat = if (usePhoneLocation) initialGpsLat else manualLat
    val effectiveLon = if (usePhoneLocation) initialGpsLon else manualLon

    // --- TIME STATE ---
    var usePhoneTime by remember { mutableStateOf(true) }
    var manualEpochDay by remember { mutableStateOf(LocalDate.now().toEpochDay().toDouble()) }
    // Stores the last text entered by the user (Day, Month, Year, Hour, Min, Sec)
    var savedDateInput by remember { mutableStateOf<List<String>?>(null) }

    var currentInstant by remember { mutableStateOf(Instant.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- ANIMATION STATE ---
    var isAnimating by remember { mutableStateOf(false) }
    var animationTargetEpoch by remember { mutableStateOf(0.0) }
    var animationStep by remember { mutableStateOf(0.0) }

    val zoneId = ZoneId.systemDefault()

    fun getInstantFromManual(epochDay: Double): Instant {
        val days = floor(epochDay).toLong()
        val frac = epochDay - days
        val nanos = (frac * 86_400_000_000_000L).toLong()
        val localDate = LocalDate.ofEpochDay(days)
        val localTime = LocalTime.ofNanoOfDay(nanos)
        val refInstant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val standardOffset = zoneId.rules.getStandardOffset(refInstant)
        return LocalDateTime.of(localDate, localTime).toInstant(standardOffset)
    }

    fun getManualFromInstant(inst: Instant): Double {
        val standardOffset = zoneId.rules.getStandardOffset(inst)
        val localDateTime = inst.atOffset(standardOffset).toLocalDateTime()
        val days = localDateTime.toLocalDate().toEpochDay()
        val sec = localDateTime.toLocalTime().toSecondOfDay()
        val nano = localDateTime.toLocalTime().nano
        return days + ((sec + (nano / 1_000_000_000.0)) / 86400.0)
    }

    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            while (abs(manualEpochDay - animationTargetEpoch) > animationStep) {
                manualEpochDay += animationStep
                currentInstant = getInstantFromManual(manualEpochDay)
                delay(12L)
            }
            manualEpochDay = animationTargetEpoch
            currentInstant = getInstantFromManual(manualEpochDay)
            isAnimating = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && usePhoneTime && !isAnimating) {
                currentInstant = Instant.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(usePhoneTime, isAnimating, currentScreen) {
        if (usePhoneTime && !isAnimating) {
            while (true) {
                val now = Instant.now()
                currentInstant = now
                if (currentScreen == Screen.TIMES) {
                    delay(100L) // 10Hz
                } else if (currentScreen == Screen.MOON_CALENDAR || currentScreen == Screen.ANALEMMA || currentScreen == Screen.METEOR_SHOWERS) {
                    // Update once per hour
                    val currentMillis = now.toEpochMilli()
                    val millisUntilNextHour = 3_600_000 - (currentMillis % 3_600_000)
                    delay(millisUntilNextHour)
                } else {
                    // Update once per minute
                    val currentMillis = now.toEpochMilli()
                    val millisUntilNextMinute = 60_000 - (currentMillis % 60_000)
                    delay(millisUntilNextMinute)
                }
            }
        } else if (!usePhoneTime && !isAnimating) {
            currentInstant = getInstantFromManual(manualEpochDay)
        }
    }

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

    var showMenu by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }

    if (showLocationDialog) {
        LocationDialog(
            currentUsePhone = usePhoneLocation,
            phoneLat = initialGpsLat,
            phoneLon = initialGpsLon,
            savedInput = savedLocationInput,
            onDismiss = { showLocationDialog = false },
            onConfirm = { usePhone, lat, lon, inputStrings ->
                usePhoneLocation = usePhone
                if (!usePhone) {
                    manualLat = lat
                    manualLon = lon
                    savedLocationInput = inputStrings
                }
                showLocationDialog = false
            }
        )
    }
    if (showDateDialog) {
        DateDialog(
            currentUsePhone = usePhoneTime,
            phoneInstant = Instant.now(),
            savedInput = savedDateInput,
            onDismiss = { showDateDialog = false },
            onConfirm = { usePhone, utEpochDay, inputStrings ->
                usePhoneTime = usePhone
                if (!usePhone && utEpochDay != null) {
                    val days = utEpochDay.toLong()
                    val frac = utEpochDay - days
                    val nanos = (frac * 86_400_000_000_000L).toLong()
                    val ld = LocalDate.ofEpochDay(days)
                    val lt = LocalTime.ofNanoOfDay(nanos)
                    currentInstant = LocalDateTime.of(ld, lt).toInstant(ZoneOffset.UTC)
                    manualEpochDay = getManualFromInstant(currentInstant)
                    savedDateInput = inputStrings
                }
                showDateDialog = false
            }
        )
    }

    fun startAnimation(years: Int, step: Double) {
        usePhoneTime = false
        if (!isAnimating) manualEpochDay = getManualFromInstant(currentInstant)
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
                            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                                Text(
                                    text = dateString,
                                    style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.offset(y = (-6).dp)
                                )
                                Text(
                                    text = "${DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("UTC")).format(currentInstant)} UT",
                                    style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.offset(y = (-6).dp)
                                )
                            }
                            if (!isAnimating) {
                                TextButton(onClick = { usePhoneTime = true; val now = LocalDate.now(); manualEpochDay = now.toEpochDay().toDouble(); currentInstant = Instant.now() }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00FF00)), modifier = Modifier.align(Alignment.CenterEnd)) { Text("Reset Time", fontWeight = FontWeight.Bold) }
                            }
                        }
                        Text(text = "Orrery", style = TextStyle(color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.Center))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) { Icon(Icons.Default.MoreVert, "Options", tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        val screens = listOf(
                            "Planet Transits" to Screen.TRANSITS,
                            "Planet Elevations" to Screen.ELEVATIONS,
                            "Planet Compass" to Screen.COMPASS,
                            "Planet Phenomena" to Screen.PHENOMENA,
                            "Moon Calendar" to Screen.MOON_CALENDAR,
                            "Lunar Eclipses" to Screen.LUNAR_ECLIPSES,
                            "Jovian Moons" to Screen.JOVIAN_MOONS,
                            "Jovian Moon Events" to Screen.JOVIAN_EVENTS,
                            "Analemma" to Screen.ANALEMMA,
                            "Meteor Showers" to Screen.METEOR_SHOWERS,
                            "Schematic Orrery" to Screen.SCHEMATIC,
                            "To-scale Orrery" to Screen.SCALE,
                            "Astronomical Times" to Screen.TIMES
                        )
                        screens.forEach { (title, screen) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = { isAnimating = false; currentScreen = screen; showMenu = false })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Location") }, onClick = { showMenu = false; showLocationDialog = true })
                        DropdownMenuItem(text = { Text("Date and Time") }, onClick = { showMenu = false; showDateDialog = true })
                        DropdownMenuItem(text = { Text("About") }, onClick = { showMenu = false; val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kenyoung.github.io/AndroidOrrery/")); context.startActivity(intent) })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            if (currentScreen in listOf(Screen.SCHEMATIC, Screen.SCALE)) {
                BottomAppBar(containerColor = Color.Black, contentColor = Color.White) {
                    if (isAnimating) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Button(onClick = { isAnimating = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("STOP") }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            listOf(1 to 0.125, 10 to 1.25, 100 to 12.5).forEach { (yr, st) ->
                                Button(onClick = { startAnimation(yr, st) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text("+$yr Yr", style = TextStyle(fontSize = 12.sp)) }
                            }
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (currentScreen == Screen.TRANSITS) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        Text(text = "Lat ", style = TextStyle(color = Color.Yellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = "%.3f ".format(effectiveLat), style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = "Lon ", style = TextStyle(color = Color.Yellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = "%.4f".format(effectiveLon), style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                    Row {
                        Text(text = "UT ", style = TextStyle(color = Color.Yellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = utString, style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = "  LST ", style = TextStyle(color = Color.Yellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                        Text(text = lstString, style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                val displayEpoch = if (isAnimating || !usePhoneTime) manualEpochDay else effectiveDate.toEpochDay().toDouble()
                when (currentScreen) {
                    Screen.TRANSITS -> if (cache != null) GraphicsWindow(effectiveLat, effectiveLon, currentInstant, cache!!, zoneId, usePhoneLocation && usePhoneTime)
                    Screen.ELEVATIONS -> PlanetElevationsScreen(displayEpoch, effectiveLat, effectiveLon, currentInstant)
                    Screen.PHENOMENA -> PlanetPhenomenaScreen(displayEpoch)
                    Screen.COMPASS -> PlanetCompassScreen(displayEpoch, effectiveLat, effectiveLon, currentInstant)
                    Screen.SCHEMATIC -> SchematicOrrery(displayEpoch)
                    Screen.SCALE -> ScaleOrrery(displayEpoch)
                    Screen.MOON_CALENDAR -> MoonCalendarScreen(currentDate = effectiveDate, lat = effectiveLat, onDateChange = { newDate -> usePhoneTime = false; manualEpochDay = newDate.toEpochDay().toDouble(); currentInstant = getInstantFromManual(manualEpochDay) })
                    Screen.LUNAR_ECLIPSES -> LunarEclipseScreen(latitude = effectiveLat, longitude = effectiveLon)
                    Screen.JOVIAN_MOONS -> JovianMoonsScreen(displayEpoch, currentInstant)
                    Screen.JOVIAN_EVENTS -> JovianEventsScreen(displayEpoch, currentInstant, effectiveLat, effectiveLon)
                    Screen.TIMES -> TimesScreen(currentInstant, effectiveLat, effectiveLon)
                    Screen.ANALEMMA -> AnalemmaScreen(currentInstant, effectiveLat, effectiveLon)
                    Screen.METEOR_SHOWERS -> MeteorShowerScreen(displayEpoch, effectiveLat, effectiveLon, zoneId)
                }
            }
        }
    }
}

// --- MATH FUNCTIONS (Cache Only) ---
fun calculateCache(nowDate: LocalDate, lat: Double, lon: Double, zoneId: ZoneId): AstroCache {
    val startDate = nowDate.minusMonths(1).withDayOfMonth(1)
    val endDate = nowDate.plusMonths(14).withDayOfMonth(1)
    val startEpochDay = startDate.toEpochDay().toDouble()
    val daysCount = (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 2

    val sunRise = DoubleArray(daysCount); val sunSet = DoubleArray(daysCount)
    val astroRise = DoubleArray(daysCount); val astroSet = DoubleArray(daysCount)

    val planets = getOrreryPlanets().filter { it.name != "Earth" }
    val planetMap = mutableMapOf<String, Triple<DoubleArray, DoubleArray, DoubleArray>>()
    for (p in planets) planetMap[p.name] = Triple(DoubleArray(daysCount), DoubleArray(daysCount), DoubleArray(daysCount))

    val offsetHours = round(lon / 15.0)

    for (i in 0 until daysCount) {
        val currentEpochDay = startEpochDay + i
        // Use Compatibility Function
        val (rise, set) = calculateSunTimes(currentEpochDay, lat, lon, offsetHours)
        sunRise[i] = rise; sunSet[i] = set
        val (aRise, aSet) = calculateSunTimes(currentEpochDay, lat, lon, offsetHours, ASTRONOMICAL_TWILIGHT)
        astroRise[i] = aRise; astroSet[i] = aSet
        for (p in planets) {
            val ev = calculatePlanetEvents(currentEpochDay, lat, lon, offsetHours, p)
            val arrays = planetMap[p.name]!!
            arrays.first[i] = ev.rise; arrays.second[i] = ev.transit; arrays.third[i] = ev.set
        }
    }
    return AstroCache(startEpochDay, daysCount, sunRise, sunSet, astroRise, astroSet, planetMap)
}

// --- UTILITIES ---
fun degToDms(valDeg: Double): Triple<String, String, String> {
    val absVal = abs(valDeg)
    val d = floor(absVal).toInt()
    val mPart = (absVal - d) * 60.0
    val m = floor(mPart).toInt()
    val s = (mPart - m) * 60.0
    val sign = if (valDeg < 0) "-" else ""
    // Use integer for seconds if very close, else decimal
    val sInt = s.roundToInt()
    val sStr = if (abs(s - sInt) < 0.001) sInt.toString() else "%.1f".format(s)
    return Triple("$sign$d", "$m", sStr)
}

@Composable
fun LocationDialog(
    currentUsePhone: Boolean,
    phoneLat: Double,
    phoneLon: Double,
    savedInput: List<String>?,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Double, Double, List<String>) -> Unit
) {
    var usePhone by remember { mutableStateOf(currentUsePhone) }

    // Initialize Fields based on mode
    val phoneLatDms = degToDms(phoneLat)
    val phoneLonDms = degToDms(phoneLon)

    // If saving input exists, use it. Otherwise fallback to phone defaults.
    val initialVals = if (savedInput != null) {
        savedInput
    } else {
        listOf(phoneLatDms.first, phoneLatDms.second, phoneLatDms.third,
            phoneLonDms.first, phoneLonDms.second, phoneLonDms.third)
    }

    // If using phone currently, we show phone values. If Manual, we show saved values.
    val startVals = if (currentUsePhone) {
        listOf(phoneLatDms.first, phoneLatDms.second, phoneLatDms.third,
            phoneLonDms.first, phoneLonDms.second, phoneLonDms.third)
    } else {
        initialVals
    }

    var latDeg by remember { mutableStateOf(startVals[0]) }
    var latMin by remember { mutableStateOf(startVals[1]) }
    var latSec by remember { mutableStateOf(startVals[2]) }
    var lonDeg by remember { mutableStateOf(startVals[3]) }
    var lonMin by remember { mutableStateOf(startVals[4]) }
    var lonSec by remember { mutableStateOf(startVals[5]) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // When toggling Use Phone, update fields accordingly
    LaunchedEffect(usePhone) {
        if (usePhone) {
            latDeg = phoneLatDms.first; latMin = phoneLatDms.second; latSec = phoneLatDms.third
            lonDeg = phoneLonDms.first; lonMin = phoneLonDms.second; lonSec = phoneLonDms.third
        } else {
            if (savedInput != null) {
                latDeg = savedInput[0]; latMin = savedInput[1]; latSec = savedInput[2]
                lonDeg = savedInput[3]; lonMin = savedInput[4]; lonSec = savedInput[5]
            }
        }
    }

    fun validateAndSubmit() {
        if (usePhone) {
            // Return empty strings for saved input as they are ignored in phone mode
            onConfirm(true, 0.0, 0.0, emptyList())
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

            val inputStrings = listOf(latDeg, latMin, latSec, lonDeg, lonMin, lonSec)
            onConfirm(false, finalLat, finalLon, inputStrings)
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
    phoneInstant: Instant,
    savedInput: List<String>?,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Double?, List<String>?) -> Unit
) {
    var usePhone by remember { mutableStateOf(currentUsePhone) }

    // Default values from Phone (System Time)
    val phoneZDT = phoneInstant.atZone(ZoneId.of("UTC"))
    val pDate = phoneZDT.toLocalDate()
    val pTime = phoneZDT.toLocalTime()
    val s = pTime.second + (pTime.nano / 1_000_000_000.0)
    val sStr = if (pTime.nano == 0) pTime.second.toString() else "%.1f".format(s)

    val phoneVals = listOf(
        pDate.dayOfMonth.toString(), pDate.monthValue.toString(), pDate.year.toString(),
        pTime.hour.toString(), pTime.minute.toString(), sStr
    )

    // Initial values logic
    val startVals = if (currentUsePhone) phoneVals else (savedInput ?: phoneVals)

    var dayString by remember { mutableStateOf(startVals[0]) }
    var monthString by remember { mutableStateOf(startVals[1]) }
    var yearString by remember { mutableStateOf(startVals[2]) }

    var hourString by remember { mutableStateOf(startVals[3]) }
    var minString by remember { mutableStateOf(startVals[4]) }
    var secString by remember { mutableStateOf(startVals[5]) }

    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Toggle Logic
    LaunchedEffect(usePhone) {
        if (usePhone) {
            dayString = phoneVals[0]; monthString = phoneVals[1]; yearString = phoneVals[2]
            hourString = phoneVals[3]; minString = phoneVals[4]; secString = phoneVals[5]
        } else {
            if (savedInput != null) {
                dayString = savedInput[0]; monthString = savedInput[1]; yearString = savedInput[2]
                hourString = savedInput[3]; minString = savedInput[4]; secString = savedInput[5]
            }
        }
    }

    fun validateAndSubmit() {
        if (usePhone) {
            onConfirm(true, null, null)
            return
        }
        try {
            val d = if (dayString.isBlank()) 0 else dayString.toInt()
            val m = if (monthString.isBlank()) 0 else monthString.toInt()
            var y = if (yearString.isBlank()) 0 else yearString.toInt()

            // Logic for 2-digit years
            if (y >= 0 && y < 100) {
                y += 2000
            }

            // Constructs LocalDate (will throw DateTimeException if invalid)
            val parsedDate = LocalDate.of(y, m, d)
            val dateEpoch = parsedDate.toEpochDay().toDouble()

            val h = if (hourString.isBlank()) 0 else hourString.toInt()
            val min = if (minString.isBlank()) 0 else minString.toInt()
            val s = if (secString.isBlank()) 0.0 else secString.toDouble()

            if (h !in 0..23) { errorMsg = "Hour must be 0-23"; return }
            if (min !in 0..59) { errorMsg = "Minute must be 0-59"; return }
            if (s < 0.0 || s >= 60.0) { errorMsg = "Second must be 0-59.9"; return }

            val timeFraction = (h * 3600.0 + min * 60.0 + s) / 86400.0
            val finalEpochDay = dateEpoch + timeFraction

            val inputStrings = listOf(dayString, monthString, yearString, hourString, minString, secString)

            onConfirm(false, finalEpochDay, inputStrings)
        } catch (e: Exception) {
            errorMsg = "Invalid Date or Time."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.DarkGray)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enter Date & Time (UT)", style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = usePhone, onCheckedChange = { usePhone = it; errorMsg = null }, colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black))
                    Text("Use phone time", color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))

                val inputColor = if (usePhone) Color.Gray else Color.Green
                val contentAlpha = if (usePhone) 0.38f else 1f

                Text("Date (DD/MM/{YY}YY)", color = Color.LightGray.copy(alpha = contentAlpha), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DmsInput(dayString, { dayString = it }, "Day", !usePhone, inputColor)
                    DmsInput(monthString, { monthString = it }, "Month", !usePhone, inputColor)
                    DmsInput(yearString, { yearString = it }, "Year", !usePhone, inputColor)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Universal Time (HH:MM:SS)", color = Color.LightGray.copy(alpha = contentAlpha), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DmsInput(hourString, { hourString = it }, "Hour", !usePhone, inputColor)
                    DmsInput(minString, { minString = it }, "Min", !usePhone, inputColor)
                    DmsInput(secString, { secString = it }, "Sec", !usePhone, inputColor)
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