package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.math.*

@Composable
fun PlanetCompassScreen(epochDay: Double, lat: Double, lon: Double, now: Instant) {
    // Basic setup
    val planets = remember { getOrreryPlanets() }

    // Time zone state
    var useLocalTime by remember { mutableStateOf(false) }
    val timeZoneAbbreviation = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)
    val timeLabel = if (useLocalTime) timeZoneAbbreviation else "UT"

    // State to hold calculated data
    var plotData by remember { mutableStateOf<List<PlotObject>>(emptyList()) }
    // Colors & Paints
    val bgColor = Color.Black
    val labelColor = Color.Green
    val labelColorInt = labelColor.toArgb()
    val redColorInt = Color.Red.toArgb()
    val whiteColorInt = Color.White.toArgb()
    val grayColorInt = Color.Gray.toArgb()
    val tickColorInt = Color(0xFF87CEFA).toArgb()
    val grayTickColorInt = Color.Gray.toArgb()
    val tableHeaderColorInt = tickColorInt

    val paints = remember { CompassPaints(labelColorInt, redColorInt, whiteColorInt, grayColorInt, tickColorInt, grayTickColorInt, tableHeaderColorInt) }

    // Keep a ref to the latest `now` so the loop always reads the current value
    val currentNow by rememberUpdatedState(now)

    // Per-object event cache: resets when lat/lon changes (remember key).
    // JD tag tracks when events were last calculated; empty map triggers initial calc.
    val eventCaches = remember(lat, lon) { mutableMapOf<String, EventCache>() }
    var prevEpochDay by remember { mutableStateOf(Double.NaN) }

    // --- ASYNC CALCULATION (recalculates ~1s before each minute-boundary redraw) ---
    // Event recalculation is conditional per object:
    //   Sun: on JD tag reset (manual time / midnight / app start) or 25h staleness
    //   Moon: on JD tag reset, set time in past, or 30h staleness
    //   Planets: on JD tag reset, set time in past, or 25h staleness
    // Visual positions (RA/Dec) are always recomputed.
    LaunchedEffect(epochDay, lat, lon) {
        while (true) {
            val snapNow = currentNow
            withContext(Dispatchers.Default) {
            val offset = lon / 15.0
            val jdStart = snapNow.epochSecond.toDouble() / 86400.0 + 2440587.5
            val currentUtEpochDay = jdStart - 2440587.5
            val newList = mutableListOf<PlotObject>()

            // Detect epochDay changes: midnight (+1.0) resets Sun only;
            // any other change (manual time entry) resets all objects.
            if (!prevEpochDay.isNaN() && abs(epochDay - prevEpochDay) > 0.0001) {
                if (abs(epochDay - prevEpochDay - 1.0) < 0.001) {
                    eventCaches.remove("Sun")
                } else {
                    eventCaches.clear()
                }
            }
            prevEpochDay = epochDay

            // === SUN ===
            val sunCache = eventCaches["Sun"]
            val needSunCalc = sunCache == null || (jdStart - sunCache.calcJd) > 25.0 / 24.0
            val sunEventData = if (needSunCalc) {
                val (sunRise, sunSet) = calculateSunTimes(epochDay, lat, lon, offset)
                val (sunTransit, _) = calculateSunTransit(epochDay, lon, offset)
                EventCache(
                    events = PlanetEvents(sunRise, sunTransit, sunSet),
                    calcJd = jdStart,
                    anchorEpochDay = epochDay
                ).also { eventCaches["Sun"] = it }
            } else sunCache!!

            // Sun visual position (always recomputed)
            val sunState = AstroEngine.getBodyState("Sun", jdStart)
            val (sunAppRa, sunAppDec) = j2000ToApparent(sunState.ra, sunState.dec, jdStart)
            newList.add(PlotObject("Sun", "☉", redColorInt, sunAppRa, sunAppDec,
                sunEventData.events, HORIZON_REFRACTED, anchorEpochDay = sunEventData.anchorEpochDay))

            // Anchor Moon/planet events to the observing night: before sunrise,
            // use the previous local day so events stay stable all night.
            val nowUtFracDay = currentUtEpochDay - floor(currentUtEpochDay)
            val currentLocalSolar = normalizeTime(nowUtFracDay * 24.0 + offset)
            val eventEpochDay = if (currentLocalSolar < sunEventData.events.rise) epochDay - 1.0 else epochDay

            // === MOON ===
            val moonCache = eventCaches["Moon"]
            val needMoonCalc = run {
                if (moonCache == null) return@run true
                if ((jdStart - moonCache.calcJd) > 30.0 / 24.0) return@run true
                // Check if set time is in the past
                val setHours = moonCache.events.set + if (moonCache.setTomorrow) 24.0 else 0.0
                val baseMidnightUT = floor(moonCache.anchorEpochDay) - offset / 24.0
                baseMidnightUT + setHours / 24.0 < currentUtEpochDay
            }

            // Moon visual position (always recomputed)
            val moonState = AstroEngine.getBodyState("Moon", jdStart)
            val (moonAppRa, moonAppDec) = j2000ToApparent(moonState.ra, moonState.dec, jdStart)
            val lstVal = calculateLSTHours(jdStart, lon)
            val topoMoon = toTopocentric(moonAppRa, moonAppDec, moonState.distGeo, lat, lon, lstVal)
            val moonSdDeg = Math.toDegrees(asin(1737400.0 / (moonState.distGeo * AU_METERS)))
            val moonTargetAlt = -(0.5667 + moonSdDeg)

            val moonEventData = if (needMoonCalc) {
                var moonEvBase = calculateMoonEvents(eventEpochDay, lat, lon, offset)
                var moonEvNext = calculateMoonEvents(eventEpochDay + 1.0, lat, lon, offset)
                var moonAnchor = eventEpochDay

                // If all events are in the past, advance to the next day's events.
                val baseMidnightUT = floor(eventEpochDay) - offset / 24.0
                val checkTransAbs = if (moonEvBase.transit >= moonEvBase.rise) moonEvBase.transit else moonEvNext.transit + 24.0
                val checkSetAbs = if (moonEvBase.set >= checkTransAbs) moonEvBase.set else moonEvNext.set + 24.0
                if (baseMidnightUT + checkSetAbs / 24.0 < currentUtEpochDay) {
                    moonEvBase = moonEvNext
                    moonEvNext = calculateMoonEvents(eventEpochDay + 2.0, lat, lon, offset)
                    moonAnchor = eventEpochDay + 1.0
                }

                // Ensure rise < transit < set chronologically, pulling from next day as needed
                val moonTransitAbs = if (moonEvBase.transit >= moonEvBase.rise) moonEvBase.transit else moonEvNext.transit + 24.0
                val moonSetAbs = if (moonEvBase.set >= moonTransitAbs) moonEvBase.set else moonEvNext.set + 24.0
                val moonTransitTomorrow = moonTransitAbs >= 24.0
                val moonSetTomorrow = moonSetAbs >= 24.0
                val moonEvents = PlanetEvents(moonEvBase.rise,
                    if (moonTransitTomorrow) moonTransitAbs - 24.0 else moonTransitAbs,
                    if (moonSetTomorrow) moonSetAbs - 24.0 else moonSetAbs)

                // Compute Moon's topocentric dec at each event time for accurate Az/El
                val anchorMidnightJD = floor(moonAnchor) + 2440587.5 - offset / 24.0
                val moonDecAt = { jd: Double ->
                    val st = AstroEngine.getBodyState("Moon", jd)
                    val (appRa, appDec) = j2000ToApparent(st.ra, st.dec, jd)
                    val lst = calculateLSTHours(jd, lon)
                    toTopocentric(appRa, appDec, st.distGeo, lat, lon, lst).dec
                }
                val moonRiseDec = if (!moonEvents.rise.isNaN()) moonDecAt(anchorMidnightJD + moonEvents.rise / 24.0) else topoMoon.dec
                val moonTransitDec = if (!moonEvents.transit.isNaN()) moonDecAt(anchorMidnightJD + (moonEvents.transit + if (moonTransitTomorrow) 24.0 else 0.0) / 24.0) else topoMoon.dec
                val moonSetDec = if (!moonEvents.set.isNaN()) moonDecAt(anchorMidnightJD + (moonEvents.set + if (moonSetTomorrow) 24.0 else 0.0) / 24.0) else topoMoon.dec

                EventCache(
                    events = moonEvents,
                    calcJd = jdStart,
                    anchorEpochDay = moonAnchor,
                    transitTomorrow = moonTransitTomorrow,
                    setTomorrow = moonSetTomorrow,
                    riseDec = moonRiseDec,
                    transitDec = moonTransitDec,
                    setDec = moonSetDec
                ).also { eventCaches["Moon"] = it }
            } else moonCache!!

            newList.add(PlotObject("Moon", "☾", redColorInt, topoMoon.ra, topoMoon.dec,
                moonEventData.events, moonTargetAlt,
                moonEventData.transitTomorrow, moonEventData.setTomorrow, moonEventData.anchorEpochDay,
                moonEventData.riseDec, moonEventData.transitDec, moonEventData.setDec))

            // === PLANETS ===
            val pAnchorMidnightJD = floor(eventEpochDay) + 2440587.5 - offset / 24.0
            for (p in planets) {
                if (p.name != "Earth") {
                    val pCache = eventCaches[p.name]
                    val needPlanetCalc = run {
                        if (pCache == null) return@run true
                        if ((jdStart - pCache.calcJd) > 25.0 / 24.0) return@run true
                        // Check if set time is in the past
                        val setHours = pCache.events.set + if (pCache.setTomorrow) 24.0 else 0.0
                        val baseMidnightUT = floor(pCache.anchorEpochDay) - offset / 24.0
                        baseMidnightUT + setHours / 24.0 < currentUtEpochDay
                    }

                    // Planet visual position (always recomputed)
                    val state = AstroEngine.getBodyState(p.name, jdStart)
                    val (pAppRa, pAppDec) = j2000ToApparent(state.ra, state.dec, jdStart)
                    val col = p.color.toArgb()

                    val planetEventData = if (needPlanetCalc) {
                        val evD = calculatePlanetEvents(eventEpochDay, lat, lon, offset, p)
                        val evD1 = calculatePlanetEvents(eventEpochDay + 1.0, lat, lon, offset, p)
                        val pTransitAbs = if (evD.transit >= evD.rise) evD.transit else evD1.transit + 24.0
                        val pSetAbs = if (evD.set >= pTransitAbs) evD.set else evD1.set + 24.0
                        val pTransitTomorrow = pTransitAbs >= 24.0
                        val pSetTomorrow = pSetAbs >= 24.0
                        val events = PlanetEvents(evD.rise,
                            if (pTransitTomorrow) pTransitAbs - 24.0 else pTransitAbs,
                            if (pSetTomorrow) pSetAbs - 24.0 else pSetAbs)

                        val pRiseJD = pAnchorMidnightJD + evD.rise / 24.0
                        val pTransitJD = pAnchorMidnightJD + (events.transit + if (pTransitTomorrow) 24.0 else 0.0) / 24.0
                        val pSetJD = pAnchorMidnightJD + (events.set + if (pSetTomorrow) 24.0 else 0.0) / 24.0
                        val pRiseDec = if (!evD.rise.isNaN()) { val s = AstroEngine.getBodyState(p.name, pRiseJD); j2000ToApparent(s.ra, s.dec, pRiseJD).second } else pAppDec
                        val pTransitDec = if (!events.transit.isNaN()) { val s = AstroEngine.getBodyState(p.name, pTransitJD); j2000ToApparent(s.ra, s.dec, pTransitJD).second } else pAppDec
                        val pSetDec = if (!events.set.isNaN()) { val s = AstroEngine.getBodyState(p.name, pSetJD); j2000ToApparent(s.ra, s.dec, pSetJD).second } else pAppDec

                        EventCache(
                            events = events,
                            calcJd = jdStart,
                            anchorEpochDay = eventEpochDay,
                            transitTomorrow = pTransitTomorrow,
                            setTomorrow = pSetTomorrow,
                            riseDec = pRiseDec,
                            transitDec = pTransitDec,
                            setDec = pSetDec
                        ).also { eventCaches[p.name] = it }
                    } else pCache!!

                    newList.add(PlotObject(p.name, p.symbol, col, pAppRa, pAppDec,
                        planetEventData.events, -0.5667,
                        planetEventData.transitTomorrow, planetEventData.setTomorrow, planetEventData.anchorEpochDay,
                        planetEventData.riseDec, planetEventData.transitDec, planetEventData.setDec))
                }
            }

            plotData = newList
        }
        // Wait until ~1 second before the next minute boundary so fresh
        // data is ready when the once-per-minute redraw fires at :00.
        val nowMillis = System.currentTimeMillis()
        val millisInMinute = nowMillis % 60_000
        val delayMs = if (millisInMinute < 59_000) 59_000 - millisInMinute else 60_000 + 59_000 - millisInMinute
        delay(delayMs)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Box(modifier = Modifier.weight(1f)) {
            if (plotData.isEmpty()) {
                Text("Calculating...", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                CompassCanvas(
                    plotData = plotData,
                    lat = lat,
                    lon = lon,
                    now = now,
                    paints = paints,
                    useLocalTime = useLocalTime,
                    timeLabel = timeLabel
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !useLocalTime, onClick = { useLocalTime = false })
                Text("Universal Time", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = useLocalTime, onClick = { useLocalTime = true })
                Text("Standard Time", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

// --- CANVAS COMPONENT ---
@Composable
fun CompassCanvas(
    plotData: List<PlotObject>,
    lat: Double,
    lon: Double,
    now: Instant,
    paints: CompassPaints,
    useLocalTime: Boolean,
    timeLabel: String
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val tickTriangleHeight = 25f
        val tickTriangleHalfBase = 12f
        val smallTickLength = 12.5f

        // Time Strings
        val standardOffsetMs = TimeZone.getDefault().rawOffset
        val displayZoneId: ZoneId = if (useLocalTime) ZoneOffset.ofTotalSeconds(standardOffsetMs / 1000) else ZoneOffset.UTC
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(displayZoneId)
        val displayTimeStr = timeFormatter.format(now)
        val lst = calculateLSTHours(now.epochSecond / 86400.0 + 2440587.5, lon)
        val lstStr = "%02d:%02d".format(floor(lst).toInt(), floor((lst - floor(lst)) * 60).toInt())

        // Offset in hours to convert UT to display time
        val displayOffsetHours = if (useLocalTime) {
            standardOffsetMs.toDouble() / 3600000.0
        } else {
            0.0
        }

        // Header text parts for multi-color rendering
        val headerPart1 = "Planet positions at  $timeLabel "
        val headerPart2 = displayTimeStr
        val headerPart3 = "  LST "
        val headerPart4 = lstStr

        // Layout
        val textY = 35f
        val rowHeight = 38f
        val numDataRows = 9 // Sun, Moon, 7 planets
        // Calculate total fixed vertical space needed:
        // - textY (80) + gap below header (fixed 40f) + 2*radius (circles)
        // - space below circles to table: 60 + 3*rowHeight = 174
        // - table: 2 header rows + data rows + margin = 2*38 + 5 + numDataRows*38 + 30 ≈ 425
        // Total fixed (excluding 2*radius): 80 + 40 + 174 + 425 = 719
        val fixedVerticalSpace = 719f
        val maxRadiusForHeight = (h - fixedVerticalSpace) / 2f
        val radius = min(w * 0.36f, min(h * 0.34f, maxRadiusForHeight)).coerceAtLeast(60f)
        val gap = 40f // Fixed gap instead of proportional
        val centerY = textY + gap + radius + (h / 15f)
        val centerRightX = w - radius
        val centerRightOff = Offset(centerRightX, centerY)
        val centerLeftX = radius
        val centerLeftOff = Offset(centerLeftX, centerY)
        val textInset = 70f

        // --- DRAWING HELPERS ---
        fun drawInternalTriangle(canvas: NativeCanvas, rimPoint: Offset, center: Offset) {
            val dx = center.x - rimPoint.x
            val dy = center.y - rimPoint.y
            val dist = sqrt(dx*dx + dy*dy)
            val cosA = dx / dist; val sinA = dy / dist
            val tipX = rimPoint.x + tickTriangleHeight * cosA
            val tipY = rimPoint.y + tickTriangleHeight * sinA
            val perpX = -sinA * tickTriangleHalfBase
            val perpY = cosA * tickTriangleHalfBase
            val path = android.graphics.Path()
            path.moveTo(tipX, tipY)
            path.lineTo(rimPoint.x + perpX, rimPoint.y + perpY)
            path.lineTo(rimPoint.x - perpX, rimPoint.y - perpY)
            path.close()
            canvas.drawPath(path, paints.triangleTick)
        }

        fun drawGrayTick(canvas: NativeCanvas, angleDeg: Double, center: Offset) {
            val rad = Math.toRadians(angleDeg)
            val cosA = cos(rad); val sinA = sin(rad)
            val startX = center.x + radius * cosA
            val startY = center.y + radius * sinA
            val endX = center.x + (radius - smallTickLength) * cosA
            val endY = center.y + (radius - smallTickLength) * sinA
            canvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), paints.grayTick)
        }

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // Draw header with yellow labels and white time values
            val totalWidth = paints.headerYellow.measureText(headerPart1) +
                    paints.header.measureText(headerPart2) +
                    paints.headerYellow.measureText(headerPart3) +
                    paints.header.measureText(headerPart4)
            var x = (w - totalWidth) / 2f
            nc.drawText(headerPart1, x, textY, paints.headerYellow)
            x += paints.headerYellow.measureText(headerPart1)
            nc.drawText(headerPart2, x, textY, paints.header)
            x += paints.header.measureText(headerPart2)
            nc.drawText(headerPart3, x, textY, paints.headerYellow)
            x += paints.headerYellow.measureText(headerPart3)
            nc.drawText(headerPart4, x, textY, paints.header)

            // 1. Azimuth Circle (Right)
            drawCircle(color = Color.White, radius = radius, center = centerRightOff, style = Stroke(width = 3f))
            for (az in 0 until 360 step 10) {
                if (az % 90 != 0) drawGrayTick(nc, az - 90.0, centerRightOff)
            }
            paints.label.textAlign = Paint.Align.CENTER
            nc.drawText("N", centerRightX, centerY - radius + textInset, paints.label)
            drawInternalTriangle(nc, Offset(centerRightX, centerY - radius), centerRightOff)
            nc.drawText("S", centerRightX, centerY + radius - (textInset / 2f), paints.label)
            drawInternalTriangle(nc, Offset(centerRightX, centerY + radius), centerRightOff)
            paints.label.textAlign = Paint.Align.RIGHT
            nc.drawText("E", centerRightX + radius - textInset + 10f, centerY + 15f, paints.label)
            drawInternalTriangle(nc, Offset(centerRightX + radius, centerY), centerRightOff)
            paints.label.textAlign = Paint.Align.LEFT
            nc.drawText("W", centerRightX - radius + textInset - 10f, centerY + 15f, paints.label)
            drawInternalTriangle(nc, Offset(centerRightX - radius, centerY), centerRightOff)

            // 2. Elevation Circle (Left)
            drawArc(color = Color.White, startAngle = 90f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(centerLeftX - radius, centerY - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(width = 3f))
            for (ang in 90..270 step 10) {
                if (ang != 90 && ang != 180 && ang != 270) drawGrayTick(nc, ang.toDouble(), centerLeftOff)
            }
            paints.label.textAlign = Paint.Align.CENTER
            nc.drawText("Zenith", centerLeftX, centerY - radius - 20f, paints.label)
            drawInternalTriangle(nc, Offset(centerLeftX, centerY - radius), centerLeftOff)
            paints.labelRed.textAlign = Paint.Align.CENTER
            nc.drawText("Nadir", centerLeftX, centerY + radius + 50f, paints.labelRed)
            drawInternalTriangle(nc, Offset(centerLeftX, centerY + radius), centerLeftOff)
            paints.label.textAlign = Paint.Align.LEFT
            nc.drawText("Horizon", centerLeftX - radius + textInset - 10f, centerY + 15f, paints.label)
            drawInternalTriangle(nc, Offset(centerLeftX - radius, centerY), centerLeftOff)

            // 3. Plot Objects
            val defaultLineLen = radius / 10f
            val occupiedAz = mutableListOf<Rect>()
            val occupiedEl = mutableListOf<Rect>()

            fun drawMarker(center: Offset, angleRad: Double, symbol: String, colorInt: Int, occupied: MutableList<Rect>) {
                var currentLineLen = defaultLineLen
                val cosA = cos(angleRad); val sinA = sin(angleRad)
                val textBounds = Rect(); paints.symbol.getTextBounds(symbol, 0, symbol.length, textBounds)
                val halfW = textBounds.width()/2; val halfH = textBounds.height()/2

                var iter = 0
                while (iter < 10) {
                    val dist = radius - currentLineLen - 25f
                    val sx = center.x + dist * cosA; val sy = center.y + dist * sinA + (halfH/2f)
                    val rect = Rect((sx-halfW-5).toInt(), (sy-halfH-5).toInt(), (sx+halfW+5).toInt(), (sy+halfH+5).toInt())
                    var hit = false
                    for (o in occupied) { if (Rect.intersects(rect, o)) { hit = true; break } }

                    if (!hit) {
                        paints.line.color = colorInt
                        paints.symbol.color = colorInt
                        val x0 = center.x + radius*cosA; val y0 = center.y + radius*sinA
                        val x1 = center.x + (radius-currentLineLen)*cosA; val y1 = center.y + (radius-currentLineLen)*sinA
                        nc.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paints.line)
                        nc.drawText(symbol, sx.toFloat(), sy.toFloat(), paints.symbol)
                        occupied.add(rect)
                        return
                    }
                    currentLineLen += 40f
                    iter++
                }
            }

            for (obj in plotData) {
                // FIXED: obj.ra is in DEGREES. Must convert to HOURS for calculateAzAlt.
                val raHours = obj.ra / 15.0
                val (az, alt) = calculateAzAlt(lst, lat, raHours, obj.dec)
                val apparentAlt = applyRefraction(alt)
                // Color uses geometric alt vs same target as rise/set calculation
                val pColor = if (alt > obj.targetAlt) paints.whiteInt else paints.redInt

                // Plot Az (Rotate -90)
                drawMarker(centerRightOff, Math.toRadians(az - 90.0), obj.symbol, pColor, occupiedAz)
                // Plot El: use apparent (refracted) altitude
                drawMarker(centerLeftOff, Math.toRadians(180.0 + apparentAlt), obj.symbol, pColor, occupiedEl)
            }

            // 4. Data Table
            val tableTop = centerY + radius + 60f + (3 * rowHeight)
            val cols = listOf(20f, w*0.22f, w*0.33f, w*0.46f, w*0.58f, w*0.71f, w*0.83f, w*0.96f)

            val row1Y = tableTop; val row2Y = tableTop + rowHeight

            nc.drawText("Rising", cols[2] + (cols[3]-cols[2]+45f)*0.5f, row1Y, paints.tableHeaderCenter)
            nc.drawText("Transit", cols[4] + (cols[5]-cols[4]+45f)*0.5f, row1Y, paints.tableHeaderCenter)
            nc.drawText("Setting", cols[6] + (cols[7]-cols[6]+45f)*0.5f, row1Y, paints.tableHeaderCenter)

            val timeColHeader = "Time $timeLabel"
            nc.drawText("Planet", cols[0], row2Y, paints.tableHeaderLeft)
            nc.drawText("HA", cols[1], row2Y, paints.tableHeaderRight)
            nc.drawText(timeColHeader, cols[2] - 17f, row2Y, paints.tableHeaderLeft)
            nc.drawText(timeColHeader, cols[4], row2Y, paints.tableHeaderLeft)
            nc.drawText(timeColHeader, cols[6], row2Y, paints.tableHeaderLeft)
            nc.drawText("Az", cols[3]+45f, row2Y, paints.tableHeaderRight)
            nc.drawText("El", cols[5]+45f, row2Y, paints.tableHeaderRight)
            nc.drawText("Az", cols[7]+45f, row2Y, paints.tableHeaderRight)

            var currY = row2Y + rowHeight + 5f
            val offset = lon / 15.0
            var anyAsterisk = false
            // Current display-timezone date for asterisk evaluation
            val currentDisplayDate = floor(now.epochSecond.toDouble() / 86400.0 + displayOffsetHours / 24.0).toLong()
            val currentUtEpochDay = now.epochSecond.toDouble() / 86400.0

            for (obj in plotData) {
                val raHours = obj.ra / 15.0
                val haNorm = normalizeHourAngle(lst - raHours)

                // Determine isUp/isPre/isPost by comparing current time against the
                // displayed event times, avoiding the mismatch between apparent RA/Dec
                // (used by altitude calc) and J2000 RA/Dec (used by rise/set solver).
                val currentLocalSolar = (currentUtEpochDay - floor(obj.anchorEpochDay)) * 24.0 + offset
                val riseH = obj.events.rise
                val transitH = obj.events.transit + if (obj.transitTomorrow) 24.0 else 0.0
                val setH = obj.events.set + if (obj.setTomorrow) 24.0 else 0.0
                val isUp: Boolean
                val isPre: Boolean
                val isPost: Boolean
                if (riseH.isNaN() || obj.events.set.isNaN() || obj.events.transit.isNaN()) {
                    val (_, currAlt) = calculateAzAlt(lst, lat, raHours, obj.dec)
                    isUp = currAlt > obj.targetAlt
                    isPre = isUp && haNorm < 0
                    isPost = isUp && haNorm > 0
                } else {
                    isUp = currentLocalSolar >= riseH && currentLocalSolar <= setH
                    isPre = isUp && currentLocalSolar < transitH
                    isPost = isUp && currentLocalSolar >= transitH
                }
                val anchorDate = floor(obj.anchorEpochDay).toLong()

                // Name
                paints.tableDataLeft.color = if (isUp) paints.greenInt else paints.redInt
                nc.drawText(obj.name, cols[0], currY, paints.tableDataLeft)

                // HA
                paints.tableDataRight.color = if (isUp) paints.whiteInt else paints.grayInt
                nc.drawText(formatTimeMM(haNorm, true), cols[1]+45f, currY, paints.tableDataRight)

                // Rise — un-normalized display hours for date comparison
                val riseRaw = obj.events.rise - offset + displayOffsetHours
                val riseDisplay = normalizeTime(riseRaw)
                val riseTomorrow = anchorDate + floor(riseRaw / 24.0).toLong() > currentDisplayDate
                val riseStr = formatTimeMM(riseDisplay, false) + if (riseTomorrow) "*" else ""
                val riseColor = if (!isUp) paints.whiteInt else paints.grayInt
                paints.tableDataLeft.color = riseColor
                nc.drawText(riseStr, cols[2] - 17f, currY, paints.tableDataLeft)
                val riseDecVal = if (obj.riseDec.isNaN()) obj.dec else obj.riseDec
                val riseAz = calculateAzAtRiseSet(lat, riseDecVal, true, obj.targetAlt)
                paints.tableDataRight.color = riseColor
                nc.drawText("%.0f".format(riseAz), cols[3]+45f, currY, paints.tableDataRight)

                // Transit — restore 24h if pulled from next day, for correct date calc
                val transRaw = (obj.events.transit + if (obj.transitTomorrow) 24.0 else 0.0) - offset + displayOffsetHours
                val transDisplay = normalizeTime(transRaw)
                val transTomorrow = anchorDate + floor(transRaw / 24.0).toLong() > currentDisplayDate
                val transStr = formatTimeMM(transDisplay, false) + if (transTomorrow) "*" else ""
                val transColor = if (isPre) paints.whiteInt else paints.grayInt
                paints.tableDataLeft.color = transColor
                nc.drawText(transStr, cols[4], currY, paints.tableDataLeft)
                val transitDecVal = if (obj.transitDec.isNaN()) obj.dec else obj.transitDec
                val transEl = 90.0 - abs(lat - transitDecVal)
                paints.tableDataRight.color = transColor
                nc.drawText("%.0f".format(transEl), cols[5]+45f, currY, paints.tableDataRight)

                // Set — restore 24h if pulled from next day, for correct date calc
                val setRaw = (obj.events.set + if (obj.setTomorrow) 24.0 else 0.0) - offset + displayOffsetHours
                val setDisplay = normalizeTime(setRaw)
                val setIsTomorrow = anchorDate + floor(setRaw / 24.0).toLong() > currentDisplayDate
                val setStr = formatTimeMM(setDisplay, false) + if (setIsTomorrow) "*" else ""
                val setColor = if (isPost) paints.whiteInt else paints.grayInt
                paints.tableDataLeft.color = setColor
                nc.drawText(setStr, cols[6], currY, paints.tableDataLeft)
                val setDecVal = if (obj.setDec.isNaN()) obj.dec else obj.setDec
                val setAz = calculateAzAtRiseSet(lat, setDecVal, false, obj.targetAlt)
                paints.tableDataRight.color = setColor
                nc.drawText("%.0f".format(setAz), cols[7]+45f, currY, paints.tableDataRight)

                if (riseTomorrow || transTomorrow || setIsTomorrow) anyAsterisk = true
                currY += rowHeight
            }

            // "* Tomorrow" footnote if any time has an asterisk
            if (anyAsterisk) {
                paints.tableDataLeft.color = LabelColor.toArgb()
                nc.drawText("* Tomorrow", cols[0], currY + 5f, paints.tableDataLeft)
            }
        }
    }
}

// --- HELPER CLASSES & LOGIC ---

data class PlotObject(val name: String, val symbol: String, val color: Int, val ra: Double, val dec: Double, val events: PlanetEvents, val targetAlt: Double, val transitTomorrow: Boolean = false, val setTomorrow: Boolean = false, val anchorEpochDay: Double = 0.0, val riseDec: Double = Double.NaN, val transitDec: Double = Double.NaN, val setDec: Double = Double.NaN)

data class EventCache(
    val events: PlanetEvents,
    val calcJd: Double,
    val anchorEpochDay: Double,
    val transitTomorrow: Boolean = false,
    val setTomorrow: Boolean = false,
    val riseDec: Double = Double.NaN,
    val transitDec: Double = Double.NaN,
    val setDec: Double = Double.NaN
)

class CompassPaints(
    val greenInt: Int, val redInt: Int, val whiteInt: Int, val grayInt: Int,
    tickCol: Int, grayTickCol: Int, headerCol: Int
) {
    val header = Paint().apply { color=android.graphics.Color.WHITE; textSize=45f; textAlign=Paint.Align.LEFT; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias=true }
    val headerYellow = Paint().apply { color=LabelColor.toArgb(); textSize=45f; textAlign=Paint.Align.LEFT; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias=true }
    val label = Paint().apply { color=greenInt; textSize=40f; textAlign=Paint.Align.CENTER; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); isAntiAlias=true }
    val labelRed = Paint().apply { color=redInt; textSize=40f; textAlign=Paint.Align.CENTER; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); isAntiAlias=true }
    val triangleTick = Paint().apply { color=tickCol; style=Paint.Style.FILL; isAntiAlias=true }
    val grayTick = Paint().apply { color=grayTickCol; style=Paint.Style.STROKE; strokeWidth=2f; isAntiAlias=true }
    val line = Paint().apply { strokeWidth=4f; style=Paint.Style.STROKE; isAntiAlias=true }
    val symbol = Paint().apply { textSize=54f; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD; isAntiAlias=true }

    val tableHeaderCenter = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.CENTER; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableHeaderLeft = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.LEFT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableHeaderRight = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.RIGHT; typeface=Typeface.MONOSPACE; isAntiAlias=true }

    val tableDataLeft = Paint().apply { textSize=29f; textAlign=Paint.Align.LEFT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableDataRight = Paint().apply { textSize=29f; textAlign=Paint.Align.RIGHT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
}

// --- SHARED MATH HELPERS ---

fun calculateAzAtRiseSet(lat: Double, dec: Double, isRise: Boolean, altitude: Double): Double {
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(dec)
    val altRad = Math.toRadians(altitude)
    val cosAz = (sin(decRad) - sin(latRad) * sin(altRad)) / (cos(latRad) * cos(altRad))
    if (cosAz < -1.0 || cosAz > 1.0) return Double.NaN
    val azRad = acos(cosAz)
    val azDeg = Math.toDegrees(azRad)
    return if (isRise) azDeg else 360.0 - azDeg
}