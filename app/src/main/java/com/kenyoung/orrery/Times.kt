package com.kenyoung.orrery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun TimesScreen(instant: Instant, lat: Double, lon: Double) {
    // 1. Time Calculations
    val epochDay = instant.toEpochMilli() / 86400000.0
    // JD = (Millis / 86400000) + 2440587.5
    val jd = epochDay + 2440587.5
    val mjd = jd - 2400000.5
    val n = jd - 2451545.0 // Days since J2000.0

    // UT String
    val utFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SS").withZone(ZoneId.of("UTC"))
    val utString = utFormatter.format(instant)

    // 2. Solar Calculations
    // Calculate Equation of Time using shared function
    val eotMinutes = calculateEquationOfTimeMinutes(epochDay)
    val eotHours = eotMinutes / 60.0

    // Format EOT
    val eotAbsSeconds = abs(eotHours * 3600.0)
    val eotMin = floor(eotAbsSeconds / 60.0).toInt()
    val eotSec = (eotAbsSeconds - eotMin * 60.0).toInt()
    val eotSign = if (eotHours < 0) "-" else ""
    val eqTimeString = "%s%02d:%02d".format(eotSign, eotMin, eotSec)

    // 3. Equinox Calculations (Nutation)
    // Mean Longitude (L) needed for nutation
    var L = (280.460 + 0.9856474 * n) % 360.0
    if (L < 0) L += 360.0
    // Obliquity (epsilon) needed for nutation
    val epsilon = 23.439 - 0.0000004 * n
    val epsilonRad = Math.toRadians(epsilon)

    // Longitude of Moon's Ascending Node (Omega)
    val omega = 125.04452 - 0.052954 * n
    val omegaRad = Math.toRadians(omega)
    val L2Rad = Math.toRadians(2.0 * L)
    // Nutation in Longitude (Delta Psi) in arcseconds approx
    val deltaPsi = -17.2 * sin(omegaRad) - 1.3 * sin(L2Rad)
    // Equation of Equinoxes = DeltaPsi * cos(epsilon) / 15.0 (to get seconds of time)
    val eqEquinoxSec = (deltaPsi * cos(epsilonRad)) / 15.0
    val eqEquinoxString = "%.4f".format(eqEquinoxSec)

    // 4. Local Time Calculations
    // Mean Solar Time = UT + Lon (hours). Lon is degrees, so + (lon/15)
    val utHours = instant.atZone(ZoneId.of("UTC")).toLocalTime().toNanoOfDay() / 3_600_000_000_000.0
    var meanSolarHours = utHours + (lon / 15.0)
    meanSolarHours = (meanSolarHours % 24.0); if (meanSolarHours < 0) meanSolarHours += 24.0

    // Apparent Solar Time = Mean Solar + EOT
    var appSolarHours = meanSolarHours + eotHours
    appSolarHours = (appSolarHours % 24.0); if (appSolarHours < 0) appSolarHours += 24.0

    // Mean LST = GMST + Lon (hours)
    val meanLST = calculateLSTHours(jd, lon)

    // Apparent LST = Mean LST + EqEquinoxes (converted from seconds to hours)
    var appLST = meanLST + (eqEquinoxSec / 3600.0)
    appLST %= 24.0; if (appLST < 0) appLST += 24.0

    // Formatting Helpers
    fun formatHours(h: Double): String {
        val hh = floor(h).toInt()
        val rem = (h - hh) * 60.0
        val mm = floor(rem).toInt()
        val ss = (rem - mm) * 60.0
        return "%02d:%02d:%02d".format(hh, mm, ss.toInt())
    }

    val meanSolarString = formatHours(meanSolarHours)
    val appSolarString = formatHours(appSolarHours)
    val meanLSTString = formatHours(meanLST)
    val appLSTString = formatHours(appLST)

    // UI Layout
    val headerColor = LabelColor
    val labelColor = LabelColor
    val valueColor = Color.White
    val separatorColor = Color(0xFF20B2AA) // LightSeaGreen (Blue-Green)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Header
        Text(
            text = "Times",
            style = TextStyle(color = headerColor, fontSize = 32.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Separator 1
        Text(
            text = "----- Global Values -----",
            style = TextStyle(color = separatorColor, fontSize = 16.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Global Rows
        TimeRow("JD", "%.6f".format(jd), labelColor, valueColor)
        TimeRow("MJD", "%.6f".format(mjd), labelColor, valueColor)
        TimeRow("UT", utString, labelColor, valueColor)
        TimeRow("Eq. of Time", eqTimeString, labelColor, valueColor)
        TimeRow("Eq. of Equinoxes", eqEquinoxString, labelColor, valueColor)

        Spacer(modifier = Modifier.height(24.dp))

        // Separator 2
        Text(
            text = "----- Local Values -----",
            style = TextStyle(color = separatorColor, fontSize = 16.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Local Rows
        TimeRow("Mean Solar", meanSolarString, labelColor, valueColor)
        TimeRow("App. Solar", appSolarString, labelColor, valueColor)
        TimeRow("Mean LST", meanLSTString, labelColor, valueColor)
        TimeRow("App. LST", appLSTString, labelColor, valueColor)
    }
}

@Composable
fun TimeRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = labelColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
        )
        Text(
            text = value,
            style = TextStyle(
                color = valueColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif, // Or Monospace if preferred
                textAlign = TextAlign.Right
            )
        )
    }
}