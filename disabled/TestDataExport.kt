package com.kenyoung.orrery

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import java.time.LocalDate
import kotlin.math.*

fun writeTestData(context: Context, utEpochDay: Double, lat: Double, lon: Double) {
    val jd = utEpochDay + 2440587.5
    val tzOffset = lon / 15.0
    val gmst = calculateGMST(jd)
    val lst = calculateLSTHours(jd, lon)
    val eot = calculateEquationOfTimeMinutes(utEpochDay)
    val epochDayInt = floor(utEpochDay)

    val sb = StringBuilder()
    fun line(s: String) { sb.appendLine(s) }
    fun fmt(v: Double, decimals: Int = 6): String = "%.${decimals}f".format(v)

    // Derive UT date/time from epoch day
    val utDays = utEpochDay.toLong()
    val utFrac = utEpochDay - utDays
    val utDate = LocalDate.ofEpochDay(utDays)
    val utSecOfDay = utFrac * 86400.0
    val utH = (utSecOfDay / 3600).toInt()
    val utM = ((utSecOfDay % 3600) / 60).toInt()
    val utS = utSecOfDay % 60

    line("=== Orrery Test Data Export ===")
    line("UT Date/Time: ${utDate} %02d:%02d:%05.2f UT".format(utH, utM, utS))
    line("Epoch Day (UT): ${fmt(utEpochDay)}")
    line("Julian Date: ${fmt(jd)}")
    line("Latitude: ${fmt(lat, 4)} deg")
    line("Longitude: ${fmt(lon, 4)} deg")
    line("Timezone Offset: ${fmt(tzOffset, 4)} hours")
    line("GMST: ${fmt(gmst)} hours")
    line("LST: ${fmt(lst)} hours")
    line("Equation of Time: ${fmt(eot, 2)} minutes")
    line("")

    // Sun
    val sunState = AstroEngine.getBodyState("Sun", jd)
    val (sunAppRa, sunAppDec) = j2000ToApparent(sunState.ra, sunState.dec, jd)
    val sunRaHours = sunAppRa / 15.0
    val sunHa = normalizeHourAngle(lst - sunRaHours)
    val (sunAz, sunAlt) = calculateAzAlt(lst, lat, sunRaHours, sunAppDec)
    val (sunRise, sunSet) = calculateSunTimes(epochDayInt, lat, lon, tzOffset)
    val (sunTransitLocal, sunTransitDec) = calculateSunTransit(epochDayInt, lon, tzOffset)

    line("--- Sun ---")
    line("RA: ${fmt(sunState.ra)} deg  (${fmt(sunState.ra / 15.0)} hours)")
    line("Dec: ${fmt(sunState.dec)} deg")
    line("Distance: ${fmt(sunState.distGeo)} AU")
    line("Ecliptic Lon: ${fmt(sunState.eclipticLon)} deg  Lat: ${fmt(sunState.eclipticLat)} deg")
    line("Hour Angle: ${fmt(sunHa)} hours")
    line("Azimuth: ${fmt(sunAz, 4)} deg  Altitude: ${fmt(applyRefraction(sunAlt), 4)} deg  (geometric: ${fmt(sunAlt, 4)} deg)")
    line("Rise: ${fmt(sunRise, 4)} local hours  Set: ${fmt(sunSet, 4)} local hours")
    line("Transit: ${fmt(sunTransitLocal, 4)} local hours  Transit Dec: ${fmt(sunTransitDec)} deg")
    line("")

    // Moon
    val moonState = AstroEngine.getBodyState("Moon", jd)
    val (moonAppRa, moonAppDec) = j2000ToApparent(moonState.ra, moonState.dec, jd)
    val moonTopo = toTopocentric(moonAppRa, moonAppDec, moonState.distGeo, lat, lon, lst)
    val moonRaHours = moonTopo.ra / 15.0
    val moonHa = normalizeHourAngle(lst - moonRaHours)
    val (moonAz, moonAlt) = calculateAzAlt(lst, lat, moonRaHours, moonTopo.dec)
    val moonEvents = calculateMoonEvents(epochDayInt, lat, lon, tzOffset)
    val moonPhase = calculateMoonPhaseAngle(utEpochDay)
    val moonIllum = (1.0 - cos(Math.toRadians(moonPhase))) / 2.0 * 100.0
    val moonDistKm = moonState.distGeo * 149597870.7

    line("--- Moon ---")
    line("RA (geocentric): ${fmt(moonState.ra)} deg  (${fmt(moonState.ra / 15.0)} hours)")
    line("Dec (geocentric): ${fmt(moonState.dec)} deg")
    line("RA (topocentric): ${fmt(moonTopo.ra)} deg  (${fmt(moonRaHours)} hours)")
    line("Dec (topocentric): ${fmt(moonTopo.dec)} deg")
    line("Distance: ${fmt(moonState.distGeo)} AU  (${fmt(moonDistKm, 1)} km)")
    line("Ecliptic Lon: ${fmt(moonState.eclipticLon)} deg  Lat: ${fmt(moonState.eclipticLat)} deg")
    line("Hour Angle: ${fmt(moonHa)} hours")
    line("Azimuth: ${fmt(moonAz, 4)} deg  Altitude: ${fmt(applyRefraction(moonAlt), 4)} deg  (geometric: ${fmt(moonAlt, 4)} deg)")
    line("Rise: ${fmt(moonEvents.rise, 4)} local hours  Set: ${fmt(moonEvents.set, 4)} local hours")
    line("Transit: ${fmt(moonEvents.transit, 4)} local hours")
    line("Phase Angle: ${fmt(moonPhase, 2)} deg  Illumination: ${fmt(moonIllum, 1)}%")
    line("")

    // Planets
    val planets = getOrreryPlanets().filter { it.name != "Earth" }
    for (p in planets) {
        val state = AstroEngine.getBodyState(p.name, jd)
        val (appRa, appDec) = j2000ToApparent(state.ra, state.dec, jd)
        val raHours = appRa / 15.0
        val ha = normalizeHourAngle(lst - raHours)
        val (az, alt) = calculateAzAlt(lst, lat, raHours, appDec)
        val events = calculatePlanetEvents(epochDayInt, lat, lon, tzOffset, p)

        line("--- ${p.name} ---")
        line("RA: ${fmt(state.ra)} deg  (${fmt(state.ra / 15.0)} hours)")
        line("Dec: ${fmt(state.dec)} deg")
        line("Distance from Earth: ${fmt(state.distGeo)} AU")
        line("Distance from Sun: ${fmt(state.distSun)} AU")
        line("Ecliptic Lon: ${fmt(state.eclipticLon)} deg  Lat: ${fmt(state.eclipticLat)} deg")
        line("Hour Angle: ${fmt(ha)} hours")
        line("Azimuth: ${fmt(az, 4)} deg  Altitude: ${fmt(applyRefraction(alt), 4)} deg  (geometric: ${fmt(alt, 4)} deg)")
        line("Rise: ${fmt(events.rise, 4)} local hours  Set: ${fmt(events.set, 4)} local hours")
        line("Transit: ${fmt(events.transit, 4)} local hours")
        line("")
    }

    // Jovian Moons
    val jovianMoons = calculateJovianMoons(jd)
    line("--- Jovian Moons ---")
    for (name in listOf("Io", "Europa", "Ganymede", "Callisto")) {
        val m = jovianMoons[name]
        if (m != null) {
            line("$name: x=${fmt(m.x, 4)} y=${fmt(m.y, 4)} z=${fmt(m.z, 4)} Jup.radii" +
                 "  shadow=(${fmt(m.shadowX, 4)}, ${fmt(m.shadowY, 4)})" +
                 " onDisk=${m.shadowOnDisk} eclipsed=${m.eclipsed}")
        }
    }

    // Write file to Downloads
    val data = sb.toString()
    val filename = "orrery_test_%04d%02d%02d_%02d%02d%02d.txt".format(
        utDate.year, utDate.monthValue, utDate.dayOfMonth, utH, utM, utS.toInt()
    )
    try {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
        )
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.write(data.toByteArray())
            }
            Toast.makeText(context, "Test data written to Downloads/$filename", Toast.LENGTH_LONG).show()
        } ?: run {
            Toast.makeText(context, "Failed to create file in Downloads", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error writing test data: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
