package com.kenyoung.orrery

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

internal fun createPhasedMoonBitmap(
    original: android.graphics.Bitmap,
    phaseAngleDeg: Double,
    lat: Double
): android.graphics.Bitmap {
    val result = original.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)

    val cx = w / 2.0
    val cy = h / 2.0
    val radius = min(w, h) / 2.0

    val phaseRad = Math.toRadians(phaseAngleDeg)
    val cosPhase = cos(phaseRad)
    val isWaxing = phaseAngleDeg <= 180.0
    val flipH = lat < 0.0
    val rSq = radius * radius
    val illumination = illuminationFromPhaseAngle(phaseAngleDeg)
    val esFloor = earthshineBrightness(illumination)

    for (py in 0 until h) {
        val dy = py - cy
        val dySq = dy * dy
        if (dySq >= rSq) continue

        val rAtY = sqrt(rSq - dySq)
        val xTerm = cosPhase * rAtY
        val transitionWidth = rAtY * 0.06

        for (px in 0 until w) {
            val dx = px - cx
            if (dx * dx + dySq > rSq) continue

            val idx = py * w + px
            val origPixel = pixels[idx]
            val origAlpha = (origPixel ushr 24) and 0xFF
            if (origAlpha == 0) continue

            val effectiveX = if (flipH) -dx else dx

            val signedDist = if (isWaxing) effectiveX - xTerm else -xTerm - effectiveX

            val shadowFactor = if (transitionWidth < 0.001) {
                if (signedDist > 0.0) 1.0 else 0.0
            } else {
                (0.5 + 0.5 * tanh(signedDist / transitionWidth)).coerceIn(0.0, 1.0)
            }

            val r = ((origPixel shr 16) and 0xFF)
            val g = ((origPixel shr 8) and 0xFF)
            val b = (origPixel and 0xFF)

            val shadow = shadowFactor.coerceAtLeast(esFloor)
            pixels[idx] = (origAlpha shl 24) or
                ((r * shadow).toInt() shl 16) or
                ((g * shadow).toInt() shl 8) or
                (b * shadow).toInt()
        }
    }

    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

private const val KM_TO_MILES = 0.621371
private const val MOON_RADIUS_KM = 1737.4
private const val ANOMALISTIC_MONTH = 27.554551 // days, perigee to perigee

@Composable
fun MoonScreen(
    obs: ObserverState,
    onTimeDisplayChange: (Boolean) -> Unit,
    dayMode: Boolean = false,
    onBack: (() -> Unit)? = null,
    refreshKey: Int = 0
) {
    val context = LocalContext.current

    // Ensure constellation boundaries are loaded
    LaunchedEffect(Unit) {
        ConstellationBoundary.ensureLoaded(context)
    }

    // Load equirectangular lunar surface texture once
    val textureData = remember {
        val bmp = context.assets.open("lunarSurface.png").use { BitmapFactory.decodeStream(it) }
        if (bmp != null) {
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            Triple(pixels, w, h)
        } else null
    }

    val currentUtEpochDay = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY
    val jd = currentUtEpochDay + UNIX_EPOCH_JD

    val phaseAngle = calculateMoonPhaseAngle(currentUtEpochDay)

    // Compute moonState, position, and libration early — needed for texture-mapped bitmap
    val moonState = AstroEngine.getBodyState("Moon", jd)
    val eclipticLat = moonState.eclipticLat
    val apparent = j2000ToApparent(moonState.ra, moonState.dec, jd)
    val lst = calculateLSTHours(jd, obs.lon)
    val topo = toTopocentric(apparent.ra, apparent.dec, moonState.distGeo, obs.lat, obs.lon, lst)

    val azAlt = calculateAzAlt(lst, obs.lat, topo.ra / 15.0, topo.dec)
    val currentAlt = applyRefraction(azAlt.alt)
    val currentAz = azAlt.az
    val isUp = currentAlt > HORIZON_REFRACTED

    val haHours = lst - topo.ra / 15.0
    val haRad = Math.toRadians(haHours * 15.0)
    val latRad = Math.toRadians(obs.lat)
    val decRad = Math.toRadians(topo.dec)
    val parallacticAngleDeg = if (isUp) {
        Math.toDegrees(atan2(sin(haRad), tan(latRad) * cos(decRad) - sin(decRad) * cos(haRad)))
    } else 0.0

    // Optical libration (Meeus ch. 53)
    val (optLibLon, optLibLat) = calculateLibration(jd, moonState.eclipticLon, eclipticLat)

    // Diurnal libration — correction for observer's position on Earth's surface
    // (Meeus ch. 53, section on diurnal libration)
    val moonDistKm = moonState.distGeo * AU_METERS / 1000.0
    val lunarParallax = asin(EARTH_RADIUS_EQ_METERS / (moonDistKm * 1000.0))
    val earthFlattening = 1.0 / 298.257
    val u = atan((1.0 - earthFlattening) * tan(latRad))
    val rhoCosPhi = cos(u)         // observer's geocentric distance * cos(geocentric lat)
    val rhoSinPhi = (1.0 - earthFlattening) * (1.0 - earthFlattening) * sin(u)
    val optLibLatRad = Math.toRadians(optLibLat)
    val diurnalLibLon = Math.toDegrees(
        -lunarParallax * rhoCosPhi * sin(haRad) / cos(optLibLatRad)
    )
    val diurnalLibLat = Math.toDegrees(
        -lunarParallax * (rhoSinPhi * cos(optLibLatRad) - rhoCosPhi * cos(haRad) * sin(optLibLatRad))
    )

    val libLon = optLibLon + diurnalLibLon
    val libLat = optLibLat + diurnalLibLat

    val phasedBitmap = if (textureData != null) {
        createTexturedMoonBitmap(
            textureData.first, textureData.second, textureData.third,
            outputSize = 512,
            phaseAngleDeg = phaseAngle,
            libLonDeg = libLon,
            libLatDeg = libLat,
            southernHemisphere = obs.lat < 0.0
        )
    } else null

    val illumination = illuminationFromPhaseAngle(phaseAngle)

    val phaseName = when {
        illumination < 3.0 -> "New Moon"
        illumination > 97.0 -> "Full Moon"
        illumination in 47.0..53.0 && phaseAngle <= 180.0 -> "First Quarter"
        illumination in 47.0..53.0 && phaseAngle > 180.0 -> "Last Quarter"
        phaseAngle < 90.0 -> "Waxing Crescent"
        phaseAngle < 180.0 -> "Waxing Gibbous"
        phaseAngle < 270.0 -> "Waning Gibbous"
        else -> "Waning Crescent"
    }

    val moonAge = calculateMoonAge(currentUtEpochDay, obs.lon)

    // Moon track: rise/transit/set times. Live mode fetches the next track via
    // computeMoonTrack, refreshed once per wall-clock minute. dayMode runs a single
    // per-day scan (midnight-to-midnight in display tz), matching Moon This Month —
    // values are already in display hours and "(none)" shows on days with no rise/set.
    val offset = obs.lon / 15.0
    val cacheKey = if (dayMode) obs.epochDay.toLong() else obs.now.epochSecond / 60
    val moonEventData = remember(dayMode, obs.lat, obs.lon, cacheKey, refreshKey) {
        if (dayMode) {
            val displayOffsetForScan = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
            val ev = calculateMoonEvents(obs.epochDay, obs.lat, obs.lon, displayOffsetForScan, scanDays = 1.0)
            EventCache(
                events = ev,
                anchorEpochDay = obs.epochDay,
                transitTomorrow = false,
                setTomorrow = false,
                riseDec = topo.dec,
                transitDec = topo.dec,
                setDec = topo.dec,
                isCircumpolar = ev.rise.isNaN() && ev.set.isNaN()
            )
        } else {
            computeMoonTrack(obs.lat, obs.lon, offset, currentUtEpochDay, topo.dec)
        }
    }
    val moonEvents = moonEventData.events

    // Duration Moon is above the horizon during its current moonrise-to-moonset
    // passage (the one with a rise within the calendar day, or — if the Moon
    // was already up at day-start — the passage still in progress). Polar cases:
    // Moon never up during the day → "No moonrise today"; Moon up all day with
    // no rise or set in the window → "No moonset today" with the calendar day
    // as the interval. Sunless = minutes within the Moon window when the Sun
    // is below the horizon; polar phrasings test the calendar day. Same altitude
    // thresholds used elsewhere on this page. 72h scan at 1-minute resolution
    // so the window can extend into the previous or next day.
    val moonUpLineText = remember(dayMode, obs.lat, obs.lon, obs.useStandardTime, cacheKey, refreshKey) {
        val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
        val currentLocalEpochDay = currentUtEpochDay + displayOffsetHours / 24.0
        val dayStartLocal = floor(currentLocalEpochDay)
        val dayStartUt = dayStartLocal - displayOffsetHours / 24.0

        val scanMinutes = 4320   // T-24h through T+48h relative to day start
        val dayStartIdx = 1440
        val dayEndIdx = 2880
        val sampleStepDays = 1.0 / 1440.0
        val scanStartUt = dayStartUt - 1.0

        val moonUp = BooleanArray(scanMinutes)
        val sunUp = BooleanArray(scanMinutes)
        for (i in 0 until scanMinutes) {
            val sampleUt = scanStartUt + (i + 0.5) * sampleStepDays
            val sampleJd = sampleUt + UNIX_EPOCH_JD
            val sampleLst = calculateLSTHours(sampleJd, obs.lon)

            // Raw geometric altitude against the refraction-baked thresholds,
            // matching calculateMoonEvents / calculateSunTimes in AstroMath.kt.
            // Applying applyRefraction here would double-count refraction.
            val moonStateI = AstroEngine.getBodyState("Moon", sampleJd)
            val moonAppI = j2000ToApparent(moonStateI.ra, moonStateI.dec, sampleJd)
            val moonTopoI = toTopocentric(moonAppI.ra, moonAppI.dec, moonStateI.distGeo, obs.lat, obs.lon, sampleLst)
            val moonAzAltI = calculateAzAlt(sampleLst, obs.lat, moonTopoI.ra / 15.0, moonTopoI.dec)
            val moonSdI = Math.toDegrees(asin(MOON_RADIUS_KM * 1000.0 / (moonStateI.distGeo * AU_METERS)))
            moonUp[i] = moonAzAltI.alt > (PLANET_HORIZON_ALT - moonSdI)

            val sunStateI = AstroEngine.getBodyState("Sun", sampleJd)
            val sunAppI = j2000ToApparent(sunStateI.ra, sunStateI.dec, sampleJd)
            val sunAzAltI = calculateAzAlt(sampleLst, obs.lat, sunAppI.ra / 15.0, sunAppI.dec)
            sunUp[i] = sunAzAltI.alt > HORIZON_REFRACTED
        }

        var moonUpInDay = 0
        var sunUpInDay = 0
        for (i in dayStartIdx until dayEndIdx) {
            if (moonUp[i]) moonUpInDay++
            if (sunUp[i]) sunUpInDay++
        }
        val sunNeverSetsToday = sunUpInDay == 1440
        val sunNeverRisesToday = sunUpInDay == 0

        fun sunlessText(count: Int): String = when {
            sunNeverSetsToday -> "No sunset today"
            sunNeverRisesToday -> "No sunrise today"
            else -> "%dh %02dm sunless".format(count / 60, count % 60)
        }

        if (moonUpInDay == 0) {
            return@remember "No moonrise today"
        }

        // Find rise: first false→true transition within the calendar day, else the
        // latest rise before dayStart (Moon was already up at day-start).
        var riseIdx = -1
        for (i in dayStartIdx until dayEndIdx) {
            if (moonUp[i] && i > 0 && !moonUp[i - 1]) { riseIdx = i; break }
        }
        if (riseIdx == -1) {
            for (i in dayStartIdx - 1 downTo 1) {
                if (moonUp[i] && !moonUp[i - 1]) { riseIdx = i; break }
            }
        }
        var setIdx = -1
        if (riseIdx >= 0) {
            for (i in riseIdx + 1 until scanMinutes) {
                if (!moonUp[i] && moonUp[i - 1]) { setIdx = i; break }
            }
        }

        if (riseIdx < 0 || setIdx < 0) {
            // Moon up for the entire scan (polar): report the calendar day.
            var sunlessInDay = 0
            for (i in dayStartIdx until dayEndIdx) if (moonUp[i] && !sunUp[i]) sunlessInDay++
            val moonPart = if (moonUpInDay == 1440) "No moonset today"
                else "Moon up %dh %02dm".format(moonUpInDay / 60, moonUpInDay % 60)
            return@remember "$moonPart (${sunlessText(sunlessInDay)})"
        }

        val windowMinutes = setIdx - riseIdx
        var sunlessInWindow = 0
        for (i in riseIdx until setIdx) if (!sunUp[i]) sunlessInWindow++

        val moonPart = "Moon up %dh %02dm".format(windowMinutes / 60, windowMinutes % 60)
        "$moonPart (${sunlessText(sunlessInWindow)})"
    }

    // If a named principal phase (New / 1st Qtr / Full / Last Qtr) falls within
    // the current display-tz day, show it above the moon image on both pages.
    val phaseEventText: String? = run {
        val displayOffsetForPhase = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
        val dayStartUt = obs.epochDay - displayOffsetForPhase / 24.0
        val crossing = findMoonPhaseCrossing(dayStartUt, dayStartUt + 1.0)
        crossing?.let {
            val zone = ZoneOffset.ofTotalSeconds((displayOffsetForPhase * 3600).toInt())
            val tzLabel = if (obs.useStandardTime) obs.stdTimeLabel else "UT"
            val eventInstant = Instant.ofEpochSecond((it.utEpochDay * SECONDS_PER_DAY).toLong())
            val timeStr = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(eventInstant)
            "${it.event.longName} at $timeStr $tzLabel"
        }
    }

    // Rise/set azimuths
    val moonSdDeg = Math.toDegrees(asin(MOON_RADIUS_KM * 1000.0 / (moonState.distGeo * AU_METERS)))
    val moonTargetAlt = PLANET_HORIZON_ALT - moonSdDeg
    val riseAz = if (!moonEvents.rise.isNaN()) calculateAzAtRiseSet(obs.lat, topo.dec, true, moonTargetAlt) else Double.NaN
    val setAz = if (!moonEvents.set.isNaN()) calculateAzAtRiseSet(obs.lat, topo.dec, false, moonTargetAlt) else Double.NaN

    // Angular diameter
    val angularDiamArcmin = Math.toDegrees(2.0 * asin(MOON_RADIUS_KM / moonDistKm)) * 60.0

    // Constellation
    val b1875 = precessJ2000ToDate(moonState.ra, moonState.dec, B1875_JD)
    val b1875RaHours = normalizeDegrees(b1875.ra) * DEGREES_TO_HOURS
    val constellation = ConstellationBoundary.findConstellation(b1875RaHours, b1875.dec)

    // Apparent RA/Dec for display
    val appRaHours = normalizeDegrees(apparent.ra) * DEGREES_TO_HOURS
    val appDecDeg = apparent.dec

    // Elongation from Sun
    val sunState = AstroEngine.getBodyState("Sun", jd)
    val sunApparent = j2000ToApparent(sunState.ra, sunState.dec, jd)
    val elongation = run {
        val moonRaRad = Math.toRadians(apparent.ra)
        val moonDecRad = Math.toRadians(apparent.dec)
        val sunRaRad = Math.toRadians(sunApparent.ra)
        val sunDecRad = Math.toRadians(sunApparent.dec)
        Math.toDegrees(acos(
            (sin(sunDecRad) * sin(moonDecRad) +
                cos(sunDecRad) * cos(moonDecRad) * cos(moonRaRad - sunRaRad)).coerceIn(-1.0, 1.0)
        ))
    }

    // Perigee/apogee: use Moon's mean anomaly to estimate days to next
    val T = (jd - 2451545.0) / 36525.0
    val meanAnomaly = ((134.9634 + 477198.8676 * T) % 360.0 + 360.0) % 360.0
    val daysToPerigee = ((360.0 - meanAnomaly) % 360.0) / 360.0 * ANOMALISTIC_MONTH
    val daysToApogee = ((180.0 - meanAnomaly + 360.0) % 360.0) / 360.0 * ANOMALISTIC_MONTH

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        if (phasedBitmap == null) return@Canvas

        withDensityScaling { w, h ->
            val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
            val timeLabel = if (obs.useStandardTime) obs.stdTimeLabel else "UT"

            // In live mode the per-track events are in solar local hours and might fall
            // on a later display-tz day; convert and flag with "*". In dayMode the per-day
            // scan returned events already in display hours, all on the anchor day.
            val currentDisplayDate = floor(currentUtEpochDay + displayOffsetHours / 24.0).toLong()
            val riseRaw = if (dayMode) moonEvents.rise
                else moonEvents.rise - offset + displayOffsetHours
            val transitRaw = if (dayMode) moonEvents.transit
                else (moonEvents.transit + if (moonEventData.transitTomorrow) 24.0 else 0.0) - offset + displayOffsetHours
            val setRaw = if (dayMode) moonEvents.set
                else (moonEvents.set + if (moonEventData.setTomorrow) 24.0 else 0.0) - offset + displayOffsetHours
            val riseIsTomorrow = !dayMode && isEventTomorrow(moonEventData.anchorEpochDay, riseRaw, currentDisplayDate)
            val transitIsTomorrow = !dayMode && isEventTomorrow(moonEventData.anchorEpochDay, transitRaw, currentDisplayDate)
            val setIsTomorrow = !dayMode && isEventTomorrow(moonEventData.anchorEpochDay, setRaw, currentDisplayDate)

            val moonW = phasedBitmap.width.toFloat()
            val moonH = phasedBitmap.height.toFloat()

            val infoTextSize = 52f
            val lineSpacing = 60f
            val topMargin = 10f
            val bottomMargin = 10f
            val hasNextDayFootnote = riseIsTomorrow || transitIsTomorrow || setIsTomorrow
            // dayMode omits Line 3 (current El/Az/PA + countdown), so one fewer line.
            val numInfoLines = (if (hasNextDayFootnote) 15 else 14) - if (dayMode) 1 else 0
            val boxGap = lineSpacing * 0.5f
            val infoHeight = numInfoLines * lineSpacing + 2 * boxGap + (if (hasNextDayFootnote) boxGap / 4f else 0f) + 10f
            val availH = h - infoHeight - topMargin - bottomMargin
            val availW = w

            val scale = min(availW / moonW, availH / moonH) * 0.75f
            val dstW = (moonW * scale).toInt()
            val dstH = (moonH * scale).toInt()
            val dstX = ((w - dstW) / 2f).toInt()
            val dstY = (topMargin + (availH - dstH) / 2f).toInt()
            val textTop = topMargin + availH

            // Draw moon image with parallactic angle rotation
            val imgCenterX = dstX + dstW / 2f
            val imgCenterY = dstY + dstH / 2f
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                // Live mode: rotate by the parallactic angle so the image matches what
                // the user sees in the sky. dayMode represents a static day, not a
                // sky orientation, so skip the parallactic rotation. The 180° flip for
                // southern observers stays in both modes.
                val rotationDeg = (if (dayMode) 0.0 else parallacticAngleDeg) +
                    if (obs.lat < 0.0) 180.0 else 0.0
                nc.rotate(rotationDeg.toFloat(), imgCenterX, imgCenterY)
                val srcRect = android.graphics.Rect(0, 0, moonW.toInt(), moonH.toInt())
                val dstRect = android.graphics.Rect(dstX, dstY, dstX + dstW, dstY + dstH)
                val bitmapPaint = android.graphics.Paint().apply { isFilterBitmap = true }
                nc.drawBitmap(phasedBitmap, srcRect, dstRect, bitmapPaint)
                nc.restore()

                if (phaseEventText != null) {
                    val phasePaint = Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.WHITE
                        textSize = infoTextSize
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    nc.drawText(phaseEventText, w / 2f, dstY - 10f - infoTextSize, phasePaint)
                }
            }

            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val labelPaint = Paint().apply {
                    isAntiAlias = true
                    color = LabelColor.toArgb()
                    textSize = infoTextSize
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val dataPaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = infoTextSize
                    textAlign = Paint.Align.LEFT
                    typeface = Typeface.DEFAULT
                }
                val centerPaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    textSize = infoTextSize
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT
                }

                // Helper to draw centered segments alternating label/data paint.
                // Vararg pairs: true = label paint, false = data paint.
                fun drawCenteredSegments(y: Float, vararg segments: Pair<String, Boolean>) {
                    val totalW = segments.sumOf { (text, isLabel) ->
                        (if (isLabel) labelPaint else dataPaint).measureText(text).toDouble()
                    }.toFloat()
                    var x = (w - totalW) / 2f
                    for ((text, isLabel) in segments) {
                        val paint = if (isLabel) labelPaint else dataPaint
                        nc.drawText(text, x, y, paint)
                        x += paint.measureText(text)
                    }
                }

                // Line 1: Date and time. dayMode shows the noon instant for which
                // RA/Dec etc. were computed, so HH:mm is enough; live mode needs HH:mm:ss.
                var lineY = textTop + infoTextSize
                val displayZone = ZoneOffset.ofTotalSeconds((displayOffsetHours * 3600).toInt())
                val datePattern = if (dayMode) "dd MMM yyyy  HH:mm" else "dd MMM yyyy  HH:mm:ss"
                val dateTimeStr = DateTimeFormatter.ofPattern(datePattern).withZone(displayZone).format(obs.now) + "  "
                drawCenteredSegments(lineY,
                    dateTimeStr to false,
                    timeLabel to true)

                // Line 2: Phase name, illumination, age
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "$phaseName  " to true,
                    "%.1f%%  ".format(illumination) to false,
                    "Age " to true,
                    "%.1f days".format(moonAge) to false)

                // Line 3: Current altitude and azimuth, countdown to next rise/set
                // Skipped in dayMode (the page represents a static moment with no
                // "current time" to count down from).
                if (!dayMode) {
                    lineY += lineSpacing
                    val countdownHasTarget = if (isUp) !moonEvents.set.isNaN() else !moonEvents.rise.isNaN()
                    val countdownText = if (countdownHasTarget) {
                        val targetHoursFromAnchor = if (isUp) {
                            moonEvents.set + if (moonEventData.setTomorrow) 24.0 else 0.0
                        } else {
                            moonEvents.rise
                        }
                        val anchorMidnightUT = floor(moonEventData.anchorEpochDay) - offset / 24.0
                        var eventUtEpochDay = anchorMidnightUT + targetHoursFromAnchor / 24.0
                        // Safety: ensure positive countdown if altitude/event-time edges disagree
                        if (eventUtEpochDay < currentUtEpochDay) eventUtEpochDay += 1.0
                        val secondsUntil = ((eventUtEpochDay - currentUtEpochDay) * SECONDS_PER_DAY).toLong()
                            .coerceAtLeast(0L)
                        val h = secondsUntil / 3600
                        val m = (secondsUntil % 3600) / 60
                        if (isUp) "Sets in %dh %02dm".format(h, m)
                        else "Rises in %dh %02dm".format(h, m)
                    } else {
                        if (isUp) "(Circumpolar)" else "(Never rises)"
                    }
                    if (isUp) {
                        drawCenteredSegments(lineY,
                            "El " to true, "%.1f\u00B0  ".format(currentAlt) to false,
                            "Az " to true, "%.1f\u00B0  ".format(currentAz) to false,
                            "PA " to true, "%.1f\u00B0  ".format(parallacticAngleDeg) to false,
                            countdownText to false)
                    } else {
                        drawCenteredSegments(lineY,
                            "El " to true, "%.1f\u00B0  ".format(currentAlt) to false,
                            "Az " to true, "%.1f\u00B0  ".format(currentAz) to false,
                            countdownText to false)
                    }
                }

                // Lines 4-5: Rise / Transit / Set times, then Az/El below
                lineY += lineSpacing + boxGap
                val riseDisplay = if (!moonEvents.rise.isNaN()) formatTimeMM(normalizeTime(riseRaw), false) + (if (riseIsTomorrow) "*" else "") else "--:--"
                val transitDisplay = if (!moonEvents.transit.isNaN()) formatTimeMM(normalizeTime(transitRaw), false) + (if (transitIsTomorrow) "*" else "") else "--:--"
                val setDisplay = if (!moonEvents.set.isNaN()) formatTimeMM(normalizeTime(setRaw), false) + (if (setIsTomorrow) "*" else "") else "--:--"

                val rLabel = "Rise "
                val tLabel = "  Transit "
                val sLabel = "  Set "

                val line4Width = labelPaint.measureText(rLabel) + dataPaint.measureText(riseDisplay) +
                    labelPaint.measureText(tLabel) + dataPaint.measureText(transitDisplay) +
                    labelPaint.measureText(sLabel) + dataPaint.measureText(setDisplay)
                var tx = (w - line4Width) / 2f
                // Track start and end x of each group for centering sub-line
                val riseStartX = tx
                nc.drawText(rLabel, tx, lineY, labelPaint); tx += labelPaint.measureText(rLabel)
                nc.drawText(riseDisplay, tx, lineY, dataPaint); tx += dataPaint.measureText(riseDisplay)
                val riseEndX = tx
                val transitStartX = tx
                nc.drawText(tLabel, tx, lineY, labelPaint); tx += labelPaint.measureText(tLabel)
                nc.drawText(transitDisplay, tx, lineY, dataPaint); tx += dataPaint.measureText(transitDisplay)
                val transitEndX = tx
                val setStartX = tx
                nc.drawText(sLabel, tx, lineY, labelPaint); tx += labelPaint.measureText(sLabel)
                nc.drawText(setDisplay, tx, lineY, dataPaint)
                val setEndX = tx + dataPaint.measureText(setDisplay)

                // Sub-line: Az under Rise, El under Transit, Az under Set (centered)
                lineY += lineSpacing
                val transitEl = 90.0 - abs(obs.lat - topo.dec)

                fun drawSubValue(startX: Float, endX: Float, label: String, value: String) {
                    val subW = labelPaint.measureText(label) + dataPaint.measureText(value)
                    val subX = (startX + endX - subW) / 2f
                    nc.drawText(label, subX, lineY, labelPaint)
                    nc.drawText(value, subX + labelPaint.measureText(label), lineY, dataPaint)
                }

                if (!riseAz.isNaN()) drawSubValue(riseStartX, riseEndX, "Az ", "%.0f\u00B0".format(riseAz))
                if (!moonEvents.transit.isNaN()) drawSubValue(transitStartX, transitEndX, "El ", "%.0f\u00B0".format(transitEl))
                if (!setAz.isNaN()) drawSubValue(setStartX, setEndX, "Az ", "%.0f\u00B0".format(setAz))

                // Draw boxes around rise/transit/set groups
                val boxPaint = Paint().apply {
                    style = Paint.Style.STROKE
                    color = android.graphics.Color.GRAY
                    strokeWidth = 2f
                    isAntiAlias = true
                }
                val ascent = dataPaint.ascent()
                val descent = dataPaint.descent()
                val boxTop = lineY - lineSpacing - (-ascent) - 4f
                val boxBottom = lineY + descent + 4f
                val boxPad = 6f
                val transitBoxStartX = transitStartX + labelPaint.measureText("  ")
                val setBoxStartX = setStartX + labelPaint.measureText("  ")
                nc.drawRect(riseStartX - boxPad, boxTop, riseEndX + boxPad, boxBottom, boxPaint)
                nc.drawRect(transitBoxStartX - boxPad, boxTop, transitEndX + boxPad, boxBottom, boxPaint)
                nc.drawRect(setBoxStartX - boxPad, boxTop, setEndX + boxPad, boxBottom, boxPaint)

                if (hasNextDayFootnote) {
                    lineY += lineSpacing
                    nc.drawText("* Tomorrow", w / 2f, lineY, centerPaint)
                }

                // Moon-up duration line (above Dist.)
                lineY += lineSpacing + (if (hasNextDayFootnote) boxGap / 4f else boxGap)
                nc.drawText(moonUpLineText, w / 2f, lineY, centerPaint)

                // Line 6: Distance and angular diameter
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Dist. " to true, "%,.0f km (%,.0f mi)  ".format(moonDistKm, moonDistKm * KM_TO_MILES) to false,
                    "Diam. " to true, "%.1f'".format(angularDiamArcmin) to false)

                // Line 7: Apparent RA/Dec
                lineY += lineSpacing
                val raTotalSec = round(appRaHours * 3600.0).toInt()
                val raH = raTotalSec / 3600
                val raM = (raTotalSec % 3600) / 60
                val raS = raTotalSec % 60
                val decSign = if (appDecDeg >= 0) "+" else "-"
                val absDec = abs(appDecDeg)
                val decTotalSec = round(absDec * 3600.0).toInt()
                val decD = decTotalSec / 3600
                val decM = (decTotalSec % 3600) / 60
                val decS = decTotalSec % 60
                val haDisplay = normalizeHourAngle(haHours)
                val haSign = if (haDisplay >= 0) "+" else "-"
                val absHa = abs(haDisplay)
                val haTotalSec = round(absHa * 3600.0).toInt()
                val haH = haTotalSec / 3600
                val haM = (haTotalSec % 3600) / 60
                val haS = haTotalSec % 60
                drawCenteredSegments(lineY,
                    "RA " to true, "%02d:%02d:%02d  ".format(raH, raM, raS) to false,
                    "Dec " to true, "%s%02d:%02d:%02d  ".format(decSign, decD, decM, decS) to false,
                    "HA " to true, "%s%d:%02d:%02d".format(haSign, haH, haM, haS) to false)

                // Line 8: Constellation and elongation
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Constellation  " to true, "$constellation  " to false,
                    "\u2609 Dist. " to true, "%.1f\u00B0".format(elongation) to false)

                // Line 9: Ecliptic longitude and latitude
                lineY += lineSpacing
                val eclLatSign = if (eclipticLat >= 0) "+" else ""
                drawCenteredSegments(lineY,
                    "Ecliptic  " to true,
                    "Lon " to true, "%.2f\u00B0  ".format(moonState.eclipticLon) to false,
                    "Lat " to true, "%s%.2f\u00B0".format(eclLatSign, eclipticLat) to false)

                // Line 10: Libration
                lineY += lineSpacing
                val libLonSign = if (libLon >= 0) "+" else ""
                val libLatSign = if (libLat >= 0) "+" else ""
                drawCenteredSegments(lineY,
                    "Libration  " to true,
                    "Lon " to true, "%s%.2f\u00B0  ".format(libLonSign, libLon) to false,
                    "Lat " to true, "%s%.2f\u00B0".format(libLatSign, libLat) to false)

                // Line 11: Perigee/Apogee
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Perigee in " to true, "%.1f days  ".format(daysToPerigee) to false,
                    "Apogee in " to true, "%.1f days".format(daysToApogee) to false)

                // Line 12: Days until next Full Moon and New Moon
                // Scan forward using actual phase angle crossings, then interpolate
                // within the crossing day for fractional precision.
                var daysToFullMoon = -1.0
                var daysToNewMoon = -1.0
                val baseEpoch = floor(currentUtEpochDay)
                val fractionalDay = currentUtEpochDay - baseEpoch
                var prevPhase = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(baseEpoch - 1, obs.lon))
                for (dayOffset in 0..30) {
                    val ed = baseEpoch + dayOffset
                    val curPhase = calculateMoonPhaseAngle(estimateMoonTransitEpochDay(ed, obs.lon))
                    if (daysToFullMoon < 0.0 && prevPhase < 180.0 && curPhase >= 180.0) {
                        val frac = (180.0 - prevPhase) / (curPhase - prevPhase)
                        daysToFullMoon = (dayOffset - 1 + frac) - fractionalDay
                        if (daysToFullMoon < 0.0) daysToFullMoon = 0.0
                    }
                    if (daysToNewMoon < 0.0 && prevPhase > 300.0 && curPhase < 60.0) {
                        val adjustedPrev = prevPhase - 360.0
                        val frac = (0.0 - adjustedPrev) / (curPhase - adjustedPrev)
                        daysToNewMoon = (dayOffset - 1 + frac) - fractionalDay
                        if (daysToNewMoon < 0.0) daysToNewMoon = 0.0
                    }
                    if (daysToFullMoon >= 0.0 && daysToNewMoon >= 0.0) break
                    prevPhase = curPhase
                }

                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Full Moon in " to true, "%.1f days".format(daysToFullMoon) to false)

                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "New Moon in " to true, "%.1f days".format(daysToNewMoon) to false)
            }
        }
    }
    if (onBack != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00FF00))
            ) {
                Text("\u25C0 Back", fontWeight = FontWeight.Bold)
            }
        }
    } else {
        TimeDisplayToggle(obs.useStandardTime, obs.useDst, onTimeDisplayChange)
    }
    }
}
