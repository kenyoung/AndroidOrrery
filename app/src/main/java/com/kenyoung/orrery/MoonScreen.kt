package com.kenyoung.orrery

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

private fun createPhasedMoonBitmap(
    original: android.graphics.Bitmap,
    phaseAngleDeg: Double,
    lat: Double
): ImageBitmap {
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

    for (py in 0 until h) {
        val dy = py - cy
        val dySq = dy * dy
        val rSq = radius * radius
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
    return result.asImageBitmap()
}

// Moon age: days since last new moon.
private const val NEW_MOON_REF_EPOCH_DAY = 20531.0576 // Mar 19, 2026 01:23 UTC
private const val SYNODIC_MONTH = 29.530588853

private fun calculateMoonAgeDays(utEpochDay: Double): Double {
    val elapsed = utEpochDay - NEW_MOON_REF_EPOCH_DAY
    return elapsed - floor(elapsed / SYNODIC_MONTH) * SYNODIC_MONTH
}

private const val MOON_RADIUS_KM = 1737.4

@Composable
fun MoonScreen(obs: ObserverState) {
    val context = LocalContext.current

    // Ensure constellation boundaries are loaded
    LaunchedEffect(Unit) {
        ConstellationBoundary.ensureLoaded(context)
    }

    val originalBitmap = remember(obs.lat) {
        val raw = context.assets.open("fullMoon.png").use { BitmapFactory.decodeStream(it) }
        if (raw != null && obs.lat < 0.0) {
            val matrix = android.graphics.Matrix().apply { postRotate(180f) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else raw
    }

    val jd = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val currentUtEpochDay = obs.now.epochSecond.toDouble() / SECONDS_PER_DAY

    val phaseAngle = remember(obs.now) {
        calculateMoonPhaseAngle(obs.epochDay)
    }

    val quantizedPhase = (phaseAngle * 2).roundToInt() / 2.0

    val phasedBitmap = remember(quantizedPhase, obs.lat) {
        if (originalBitmap != null) createPhasedMoonBitmap(originalBitmap, quantizedPhase, obs.lat)
        else null
    }

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

    val moonAge = remember(obs.now) { calculateMoonAgeDays(currentUtEpochDay) }

    // Moon state: position, distance, apparent coords
    val moonState = remember(obs.now) { AstroEngine.getBodyState("Moon", jd) }
    val apparent = remember(obs.now) { j2000ToApparent(moonState.ra, moonState.dec, jd) }
    val lst = calculateLSTHours(jd, obs.lon)
    val topo = toTopocentric(apparent.ra, apparent.dec, moonState.distGeo, obs.lat, obs.lon, lst)

    // Current altitude and azimuth
    val azAlt = calculateAzAlt(lst, obs.lat, topo.ra / 15.0, topo.dec)
    val currentAlt = applyRefraction(azAlt.alt)
    val currentAz = azAlt.az
    val isUp = currentAlt > HORIZON_REFRACTED

    // Moon rise/transit/set
    val offset = obs.lon / 15.0
    val moonEvents = remember(obs.now, obs.lat, obs.lon) {
        calculateMoonEvents(obs.epochDay, obs.lat, obs.lon, offset)
    }

    // Rise/set azimuths
    val moonSdDeg = Math.toDegrees(asin(MOON_RADIUS_KM * 1000.0 / (moonState.distGeo * AU_METERS)))
    val moonTargetAlt = PLANET_HORIZON_ALT - moonSdDeg
    val riseAz = if (!moonEvents.rise.isNaN()) calculateAzAtRiseSet(obs.lat, topo.dec, true, moonTargetAlt) else Double.NaN
    val setAz = if (!moonEvents.set.isNaN()) calculateAzAtRiseSet(obs.lat, topo.dec, false, moonTargetAlt) else Double.NaN

    // Distance
    val moonDistKm = moonState.distGeo * AU_METERS / 1000.0

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
    val sunState = remember(obs.now) { AstroEngine.getBodyState("Sun", jd) }
    val sunApparent = remember(obs.now) { j2000ToApparent(sunState.ra, sunState.dec, jd) }
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

    // Ecliptic latitude
    val eclipticLat = moonState.eclipticLat

    // Display time offset
    val displayOffsetHours = if (obs.useStandardTime) obs.stdOffsetHours else 0.0
    val timeLabel = if (obs.useStandardTime) obs.stdTimeLabel else "UT"

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (phasedBitmap == null) return@Canvas

        withDensityScaling { w, h ->
            val moonW = phasedBitmap.width
            val moonH = phasedBitmap.height

            val infoTextSize = 52f
            val lineSpacing = 60f
            val topMargin = 10f
            val bottomMargin = 10f
            val numInfoLines = 11
            val infoHeight = numInfoLines * lineSpacing + 10f
            val availH = h - infoHeight - topMargin - bottomMargin
            val availW = w

            val scale = min(availW / moonW, availH / moonH) * 0.75f
            val dstW = (moonW * scale).toInt()
            val dstH = (moonH * scale).toInt()
            val dstX = ((w - dstW) / 2f).toInt()
            val dstY = (topMargin + (availH - dstH) / 2f).toInt()
            val textTop = topMargin + availH

            drawImage(
                phasedBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(moonW, moonH),
                dstOffset = IntOffset(dstX, dstY),
                dstSize = IntSize(dstW, dstH)
            )

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
                val upDown = if (isUp) "Above horizon" else "Below horizon"
                drawCenteredSegments(lineY,
                    "Alt " to true, "%.1f\u00B0  ".format(currentAlt) to false,
                    "Az " to true, "%.1f\u00B0  ".format(currentAz) to false,
                    "($upDown)" to false)

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
                drawCenteredSegments(lineY,
                    "RA " to true, "%02dh %02dm  ".format(raH, raM) to false,
                    "Dec " to true, "%s%02d\u00B0 %02d'".format(decSign, decD, decM) to false)

                // Line 8: Constellation and elongation
                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Constellation  " to true, "$constellation  " to false,
                    "☉ Elong. " to true, "%.1f\u00B0".format(elongation) to false)

                // Line 9: Ecliptic longitude and latitude
                lineY += lineSpacing
                val eclLatSign = if (eclipticLat >= 0) "+" else ""
                drawCenteredSegments(lineY,
                    "Ecliptic  " to true,
                    "Lon " to true, "%.2f\u00B0  ".format(moonState.eclipticLon) to false,
                    "Lat " to true, "%s%.2f\u00B0".format(eclLatSign, eclipticLat) to false)

                // Line 10: Days until next Full Moon and New Moon
                lineY += lineSpacing
                val halfSynodic = SYNODIC_MONTH / 2.0
                val daysToFullMoon = if (moonAge < halfSynodic) halfSynodic - moonAge else SYNODIC_MONTH - moonAge + halfSynodic
                val daysToNewMoon = SYNODIC_MONTH - moonAge
                drawCenteredSegments(lineY,
                    "Next Full Moon " to true, "%.1f days".format(daysToFullMoon) to false)

                lineY += lineSpacing
                drawCenteredSegments(lineY,
                    "Next New Moon " to true, "%.1f days".format(daysToNewMoon) to false)
            }
        }
    }
}
