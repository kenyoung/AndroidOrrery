package com.kenyoung.orrery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlin.math.*

private val zodiacSymbols = mapOf(
    "Aries" to "\u2648\uFE0E",
    "Taurus" to "\u2649\uFE0E",
    "Gemini" to "\u264A\uFE0E",
    "Cancer" to "\u264B\uFE0E",
    "Leo" to "\u264C\uFE0E",
    "Virgo" to "\u264D\uFE0E",
    "Libra" to "\u264E\uFE0E",
    "Scorpius" to "\u264F\uFE0E",
    "Sagittarius" to "\u2650\uFE0E",
    "Capricornus" to "\u2651\uFE0E",
    "Aquarius" to "\u2652\uFE0E",
    "Pisces" to "\u2653\uFE0E",
)

private data class ObjectRow(
    val name: String,
    val constellation: String,
    val raString: String,
    val decString: String
)

@Composable
fun ConstellationsScreen(instant: Instant, lat: Double, lon: Double) {
    val context = LocalContext.current
    ConstellationBoundary.ensureLoaded(context)

    val jd = instant.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD

    val bodies = listOf("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")

    val rows = remember(instant) {
        bodies.map { name ->
            val state = AstroEngine.getBodyState(name, jd)
            val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
            val (b1875Ra, b1875Dec) = precessJ2000ToDate(state.ra, state.dec, B1875_JD)
            val b1875RaHours = normalizeDegrees(b1875Ra) * DEGREES_TO_HOURS
            val constellationName = ConstellationBoundary.findConstellation(b1875RaHours, b1875Dec)
            val symbol = zodiacSymbols[constellationName]
            val constellation = if (symbol != null) "$constellationName   $symbol" else constellationName
            val raStr = formatRa(normalizeDegrees(appRa) * DEGREES_TO_HOURS)
            val decStr = formatDec(appDec)
            ObjectRow(name, constellation, raStr, decStr)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Constellations",
            style = TextStyle(color = LabelColor, fontSize = 28.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        for (row in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.name,
                    style = TextStyle(
                        color = LabelColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    ),
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 80.dp)
                )
                Text(
                    text = row.constellation,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif
                    ),
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = row.raString,
                    style = TextStyle(
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = row.decString,
                    style = TextStyle(
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

/**
 * Formats RA in hours to "HHh MMm SS.Ss" with 1 decimal digit for seconds.
 */
private fun formatRa(raHours: Double): String {
    var h = raHours % 24.0
    if (h < 0) h += 24.0
    var hh = floor(h).toInt()
    val rem = (h - hh) * 60.0
    var mm = floor(rem).toInt()
    var ss = (rem - mm) * 60.0
    // Round to 1 decimal and handle rollover
    ss = round(ss * 10.0) / 10.0
    if (ss >= 60.0) { ss -= 60.0; mm++ }
    if (mm >= 60) { mm -= 60; hh++ }
    if (hh >= 24) hh -= 24
    return "%02dh %02dm %04.1fs".format(hh, mm, ss)
}

/**
 * Formats Dec in degrees to "±DD° MM' SS\"" with integer arc seconds.
 */
private fun formatDec(decDeg: Double): String {
    val sign = if (decDeg < 0) "-" else "+"
    val absDec = abs(decDeg)
    var dd = floor(absDec).toInt()
    val rem = (absDec - dd) * 60.0
    var mm = floor(rem).toInt()
    var ss = round((rem - mm) * 60.0).toInt()
    if (ss == 60) { ss = 0; mm++ }
    if (mm == 60) { mm = 0; dd++ }
    return "%s%02d° %02d' %02d\"".format(sign, dd, mm, ss)
}
