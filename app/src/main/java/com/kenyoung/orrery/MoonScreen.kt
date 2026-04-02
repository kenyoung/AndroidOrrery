package com.kenyoung.orrery

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

            pixels[idx] = (origAlpha shl 24) or
                ((r * shadowFactor).toInt() shl 16) or
                ((g * shadowFactor).toInt() shl 8) or
                (b * shadowFactor).toInt()
        }
    }

    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

private const val MOON_RADIUS_KM = 1737.4
private const val ANOMALISTIC_MONTH = 27.554551 // days, perigee to perigee

@Composable
fun MoonScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
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

    val illumination = (1.0 - cos(Math.toRadians(phaseAngle))) / 2.0 * 100.0

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

    // Moon rise/transit/set
    val offset = obs.lon / 15.0
    val moonEvents = calculateMoonEvents(obs.epochDay, obs.lat, obs.lon, offset)

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

    // Display time offset
    val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
    val timeLabel = if (obs.useStandardTime) obs.stdTimeLabel else "UT"

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        if (phasedBitmap == null) return@Canvas

        withDensityScaling { w, h ->
            val moonW = phasedBitmap.width.toFloat()
            val moonH = phasedBitmap.height.toFloat()

            val infoTextSize = 52f
            val lineSpacing = 60f
            val topMargin = 10f
            val bottomMargin = 10f
            val numInfoLines = 13
            val infoHeight = numInfoLines * lineSpacing + 10f
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
                nc.rotate(parallacticAngleDeg.toFloat(), imgCenterX, imgCenterY)
                val srcRect = android.graphics.Rect(0, 0, moonW.toInt(), moonH.toInt())
                val dstRect = android.graphics.Rect(dstX, dstY, dstX + dstW, dstY + dstH)
                val bitmapPaint = android.graphics.Paint().apply { isFilterBitmap = true }
                nc.drawBitmap(phasedBitmap, srcRect, dstRect, bitmapPaint)
                nc.restore()
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

                // Line 1: Date and time
                var lineY = textTop + infoTextSize
                val displayZone = ZoneOffset.ofTotalSeconds((displayOffsetHours * 3600).toInt())
                val dateTimeStr = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm").withZone(displayZone).format(obs.now) + "  "
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

                // Line 3: Current altitude and azimuth
                lineY += lineSpacing
                if (isUp) {
                    drawCenteredSegments(lineY,
                        "El " to true, "%.1f\u00B0  ".format(currentAlt) to false,
                        "Az " to true, "%.1f\u00B0  ".format(currentAz) to false,
                        "PA " to true, "%.1f\u00B0  ".format(parallacticAngleDeg) to false,
                        "(Above horizon)" to false)
                } else {
                    drawCenteredSegments(lineY,
                        "El " to true, "%.1f\u00B0  ".format(currentAlt) to false,
                        "Az " to true, "%.1f\u00B0  ".format(currentAz) to false,
                        "(Below horizon)" to false)
                }

                // Lines 4-5: Rise / Transit / Set times, then Az/El below
                lineY += lineSpacing
                val riseDisplay = if (!moonEvents.rise.isNaN()) formatTimeMM(normalizeTime(moonEvents.rise - offset + displayOffsetHours), false) else "--:--"
                val transitDisplay = if (!moonEvents.transit.isNaN()) formatTimeMM(normalizeTime(moonEvents.transit - offset + displayOffsetHours), false) else "--:--"
                val setDisplay = if (!moonEvents.set.isNaN()) formatTimeMM(normalizeTime(moonEvents.set - offset + displayOffsetHours), false) else "--:--"

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

                // Line 6: Distance and angular diameter
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Distance  " to true, "%,.0f km  ".format(moonDistKm) to false,
                    "Diameter " to true, "%.1f'".format(angularDiamArcmin) to false)

                // Line 7: Apparent RA/Dec
                lineY += lineSpacing
                val raTotalMin = round(appRaHours * 60.0).toInt()
                val raH = raTotalMin / 60
                val raM = raTotalMin % 60
                val decSign = if (appDecDeg >= 0) "+" else "-"
                val absDec = abs(appDecDeg)
                val decTotalMin = round(absDec * 60.0).toInt()
                val decD = decTotalMin / 60
                val decM = decTotalMin % 60
                val haSign = if (haHours >= 0) "+" else "-"
                val absHa = abs(haHours)
                val haTotalMin = round(absHa * 60.0).toInt()
                val haH = haTotalMin / 60
                val haM = haTotalMin % 60
                drawCenteredSegments(lineY,
                    "RA " to true, "%02dh %02dm  ".format(raH, raM) to false,
                    "Dec " to true, "%s%02d\u00B0 %02d'  ".format(decSign, decD, decM) to false,
                    "HA " to true, "%s%dh %02dm".format(haSign, haH, haM) to false)

                // Line 8: Constellation and elongation
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Constellation  " to true, "$constellation  " to false,
                    "Sun Dist. " to true, "%.1f\u00B0".format(elongation) to false)

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
                    "Next Perigee in " to true, "%.1f days  ".format(daysToPerigee) to false,
                    "Next Apogee in " to true, "%.1f days".format(daysToApogee) to false)

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
                    "Next Full Moon in " to true, "%.1f days".format(daysToFullMoon) to false)

                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Next New Moon in " to true, "%.1f days".format(daysToNewMoon) to false)
            }
        }
    }
    TimeDisplayToggle(obs.useStandardTime, obs.useDst, onTimeDisplayChange)
    }
}
