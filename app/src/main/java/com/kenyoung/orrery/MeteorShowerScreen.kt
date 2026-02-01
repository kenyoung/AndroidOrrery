package com.kenyoung.orrery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

// --- DATA MODEL ---
data class MeteorShower(
    val name: String,
    val abbrev: String,
    val startMonth: Int, val startDay: Int,
    val endMonth: Int, val endDay: Int,
    val maxMonth: Int, val maxDay: Int,
    val rate: String,
    val velocity: String
)

data class ShowerRowData(
    val shower: MeteorShower,
    val startDateStr: String,
    val endDateStr: String,
    val maxDateStr: String,
    val moonPercent: String,
    val moonTrend: String, // "+" or "-"
    val maxDarkHours: Double,
    val isActive: Boolean
)

// --- CSV DATA SOURCE ---
val meteorShowersList = listOf(
    MeteorShower("alpha Centaurids", "ACE", 1, 28, 2, 21, 2, 8, "6", "57"),
    MeteorShower("gamma Normids", "GNO", 3, 7, 3, 23, 3, 15, "4", "68"),
    MeteorShower("Lyrids", "LYR", 4, 15, 4, 29, 4, 23, "18", "47"),
    MeteorShower("pi Puppids", "PPU", 4, 16, 4, 30, 4, 24, "Var", "15"),
    MeteorShower("eta Aquariids", "ETA", 4, 19, 5, 28, 5, 6, "55", "66"),
    MeteorShower("June Bootids", "JBO", 6, 22, 7, 2, 6, 27, "Var", "18"),
    MeteorShower("July Pegasids", "JPE", 7, 4, 8, 8, 7, 11, "3", "64"),
    MeteorShower("alpha Capricornids", "CAP", 7, 7, 8, 15, 7, 31, "5", "23"),
    MeteorShower("South delta Aquariids", "SDA", 7, 12, 8, 23, 7, 30, "16", "41"),
    MeteorShower("Perseids", "PER", 7, 14, 9, 1, 8, 12, "100", "59"),
    MeteorShower("kappa Cygnids", "KSG", 8, 3, 8, 25, 8, 17, "3", "25"),
    MeteorShower("Aurigids", "AUR", 8, 16, 9, 5, 9, 1, "6", "65"),
    MeteorShower("Southern Taurids", "STA", 9, 23, 12, 8, 11, 5, "5", "28"),
    MeteorShower("Orionids", "ORI", 10, 2, 11, 7, 10, 21, "20", "66"),
    MeteorShower("Draconids", "DRA", 10, 6, 10, 10, 10, 8, "Var", "20"),
    MeteorShower("Northern Taurids", "NTA", 10, 13, 12, 2, 11, 12, "5", "29"),
    MeteorShower("Leo Minorids", "LMI", 10, 13, 11, 3, 10, 23, "2", "62"),
    MeteorShower("Leonids", "LEO", 11, 3, 12, 2, 11, 17, "15", "70"),
    MeteorShower("sigma Hydrids", "HYD", 11, 11, 1, 4, 12, 7, "5", "59"),
    MeteorShower("alpha Monocerotids", "AMO", 11, 15, 11, 25, 11, 21, "Var", "65"),
    MeteorShower("December Monocerotids", "MON", 11, 27, 12, 17, 12, 9, "2", "42"),
    MeteorShower("Geminids", "GEM", 12, 4, 12, 17, 12, 14, "150", "35"),
    MeteorShower("Coma Berenicids", "CBE", 12, 12, 12, 23, 12, 16, "3", "65"),
    MeteorShower("Ursids", "URS", 12, 17, 12, 26, 12, 22, "10", "33"),
    MeteorShower("Quadrantids", "QUA", 12, 28, 1, 12, 1, 3, "120", "41")
)

@Composable
fun MeteorShowerScreen(
    currentEpochDay: Double,
    lat: Double,
    lon: Double,
    zoneId: ZoneId
) {
    var rowData by remember { mutableStateOf<List<ShowerRowData>?>(null) }
    var tonightDarkHours by remember { mutableStateOf(0.0) }
    var tonightRangeStr by remember { mutableStateOf("") }
    var isCalculating by remember { mutableStateOf(true) }

    val activeYear = LocalDate.ofEpochDay(currentEpochDay.toLong()).year

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fontScale = if (isLandscape) 1.55f else 1.0f

    LaunchedEffect(currentEpochDay, lat, lon) {
        isCalculating = true
        withContext(Dispatchers.Default) {
            // 1. Calculate Rows
            val calculatedRows = meteorShowersList.map { shower ->
                // Date Logic
                val startYear = activeYear
                val endYear = if (shower.endMonth < shower.startMonth) activeYear + 1 else activeYear
                val maxYear = if (shower.maxMonth < shower.startMonth) activeYear + 1 else activeYear

                val startDate = LocalDate.of(startYear, shower.startMonth, shower.startDay)
                val endDate = LocalDate.of(endYear, shower.endMonth, shower.endDay)
                val maxDate = LocalDate.of(maxYear, shower.maxMonth, shower.maxDay)

                // Current Date for "Is Active" check
                val currentDate = LocalDate.ofEpochDay(currentEpochDay.toLong())
                val isActive = !currentDate.isBefore(startDate) && !currentDate.isAfter(endDate)

                // Strings
                val dFmt = DateTimeFormatter.ofPattern("dd/MM")
                val startStr = dFmt.format(startDate)
                val endStr = dFmt.format(endDate)
                val maxStr = dFmt.format(maxDate)

                // Moon Phase at Max
                val maxEpoch = maxDate.toEpochDay().toDouble()
                val phaseAngle = calculateMoonPhaseAngle(maxEpoch) // 0..360

                // Illum = (1 - cos(angle))/2 (Standard approx where 0=New, 180=Full)
                val radPhase = Math.toRadians(phaseAngle)
                val frac = (1.0 - cos(radPhase)) / 2.0
                val pct = (frac * 100).roundToInt()
                val trend = if (phaseAngle > 0 && phaseAngle < 180) "+" else "-"

                // Dark Hours at Max
                val darkHours = calculateDarkHoursForNight(maxEpoch, lat, lon)

                ShowerRowData(
                    shower = shower,
                    startDateStr = startStr,
                    endDateStr = endStr,
                    maxDateStr = maxStr,
                    moonPercent = "$pct",
                    moonTrend = trend,
                    maxDarkHours = darkHours,
                    isActive = isActive
                )
            }
            rowData = calculatedRows

            // 2. Calculate "Tonight's" Dark Hours
            val sunStateNow = calculateSunPositionKepler(currentEpochDay + 2440587.5)
            val sunAltNow = getAltitude(sunStateNow.ra, sunStateNow.dec, currentEpochDay, lat, lon)

            val epochFloor = floor(currentEpochDay)
            val frac = currentEpochDay - epochFloor

            // Determine Start of "Night Block" (Noon to Noon)
            val searchBase = if (sunAltNow > -0.833 || frac > 0.5) {
                epochFloor // Start from Today Noon
            } else {
                epochFloor - 1.0 // Start from Yesterday Noon (we are in the early morning tail)
            }

            val dhResult = calculateDarkHoursDetails(searchBase, lat, lon)
            tonightDarkHours = dhResult.first
            tonightRangeStr = dhResult.second
        }
        isCalculating = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(4.dp)
    ) {
        if (isCalculating || rowData == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Calculating Meteor Data...", color = Color.White)
            }
        } else {
            // Header
            Text(
                "Meteor Shower Information for $activeYear",
                color = Color.White,
                fontSize = (16 * fontScale).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center
            )

            // Table Header
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                HeaderCell("Shower Name", 0.28f, TextAlign.Left, fontScale)
                HeaderCell("Rate", 0.06f, TextAlign.Right, fontScale)
                HeaderCell("Dates", 0.24f, TextAlign.Center, fontScale)
                HeaderCell("Max", 0.06f, TextAlign.Center, fontScale)
                HeaderCell("V", 0.06f, TextAlign.Center, fontScale)
                HeaderCell("Moon%", 0.10f, TextAlign.Center, fontScale)
                HeaderCell("Dark", 0.08f, TextAlign.Center, fontScale)
            }
            HorizontalDivider(color = Color.Gray)

            // List
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rowData!!) { row ->
                    ShowerRow(row, tonightDarkHours, fontScale)
                }
            }

            if (!isLandscape) {
                Text(
                    "This table may be more legible in landscape mode",
                    color = Color(0xFF87CEFA),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            HorizontalDivider(color = Color.Gray)
            // Footer
            Text(
                "The sky will be very dark for %.1f hours %s tonight.".format(tonightDarkHours, tonightRangeStr),
                color = Color.White,
                fontSize = (14 * fontScale).sp,
                modifier = Modifier.padding(8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Center, fontScale: Float = 1.0f) {
    Text(
        text = text,
        color = Color(0xFF87CEFA),
        fontSize = (8.5f * fontScale).sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        modifier = Modifier.weight(weight)
    )
}

@Composable
fun ShowerRow(data: ShowerRowData, tonightDarkHours: Double, fontScale: Float = 1.0f) {
    // Color Logic
    val rowColor = if (data.isActive) {
        if (tonightDarkHours > 2.0) Color(0xFF00FF00) // Bright Green
        else Color(0xFF00A000) // Dark Green
    } else {
        if (data.maxDarkHours > 2.0) Color.White
        else Color.Gray
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        DataCell(data.shower.name, 0.28f, TextAlign.Left, rowColor, fontScale)
        DataCell(data.shower.rate, 0.06f, TextAlign.Right, rowColor, fontScale)
        DataCell("${data.startDateStr} → ${data.endDateStr}", 0.24f, TextAlign.Center, rowColor, fontScale)
        DataCell(data.maxDateStr, 0.06f, TextAlign.Center, rowColor, fontScale)
        DataCell(data.shower.velocity, 0.06f, TextAlign.Center, rowColor, fontScale)
        DataCell("${data.moonPercent}${data.moonTrend}", 0.10f, TextAlign.Center, rowColor, fontScale)
        DataCell("%.1f".format(data.maxDarkHours), 0.08f, TextAlign.Center, rowColor, fontScale)
    }
}

@Composable
fun RowScope.DataCell(text: String, weight: Float, align: TextAlign, color: Color, fontScale: Float = 1.0f) {
    Text(
        text = text,
        color = color,
        fontSize = (7.3f * fontScale).sp,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
        modifier = Modifier.weight(weight),
        maxLines = 1 // Ensure text is forced to fit or truncate cleanly if still too long
    )
}

// --- CALCULATION HELPERS ---

fun calculateDarkHoursForNight(epochDay: Double, lat: Double, lon: Double): Double {
    return calculateDarkHoursDetails(epochDay, lat, lon).first
}

// Returns Pair(TotalHours, TimeString)
fun calculateDarkHoursDetails(epochDay: Double, lat: Double, lon: Double): Pair<Double, String> {
    // We scan at 15 min resolution from Noon (epochDay+0.5) to Noon (epochDay+1.5)
    val startSearch = epochDay + 0.5
    val endSearch = epochDay + 1.5
    val step = 15.0 / 1440.0 // 15 minutes in days
    var totalDays = 0.0

    var firstDark: Double? = null
    var lastDark: Double? = null

    var t = startSearch
    while (t < endSearch) {
        if (isDark(t, lat, lon)) {
            totalDays += step
            if (firstDark == null) firstDark = t
            lastDark = t
        }
        t += step
    }

    val totalHours = totalDays * 24.0
    val rangeStr = if (firstDark != null && lastDark != null) {
        val fLocal = toLocalTimeStr(firstDark)
        val lLocal = toLocalTimeStr(lastDark!!)
        "($fLocal → $lLocal UT)"
    } else {
        ""
    }
    return Pair(totalHours, rangeStr)
}

fun isDark(epochDay: Double, lat: Double, lon: Double): Boolean {
    // 1. Sun Alt < -12
    val sunState = calculateSunPositionKepler(epochDay + 2440587.5)
    // sunState.ra is in DEGREES (from AstroMath BodyState)
    val sunAlt = getAltitude(sunState.ra, sunState.dec, epochDay, lat, lon)
    if (sunAlt >= -12.0) return false

    // 2. Moon Alt < -10
    val moonPos = calculateMoonPosition(epochDay)
    // moonPos.ra is in HOURS (from AstroMath calculateMoonPosition)
    // getAltitude expects DEGREES.
    // FIX: Multiply RA Hours by 15.0 to get Degrees
    val moonAlt = getAltitude(moonPos.ra * 15.0, moonPos.dec, epochDay, lat, lon)
    if (moonAlt >= -10.0) return false

    return true
}

fun getAltitude(raDeg: Double, decDeg: Double, epochDay: Double, lat: Double, lon: Double): Double {
    val jd = epochDay + 2440587.5
    val n = jd - 2451545.0
    val GMST = (18.697374558 + 24.06570982441908 * n) % 24.0
    val gmstFixed = if (GMST < 0) GMST + 24.0 else GMST
    val lst = (gmstFixed + lon / 15.0 + 24.0) % 24.0

    // HA = LST - RA
    // LST is Hours. RA must be Hours.
    // raDeg is Degrees.
    val haHours = lst - (raDeg / 15.0)

    val haRad = Math.toRadians(haHours * 15.0)
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(decDeg)
    val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
    return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
}

fun toLocalTimeStr(epochDay: Double): String {
    val frac = epochDay - floor(epochDay)
    val h = frac * 24.0
    val hh = h.toInt()
    val mm = ((h - hh) * 60).toInt()
    return "%02d:%02d".format(hh, mm)
}