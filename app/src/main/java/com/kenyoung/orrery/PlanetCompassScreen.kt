package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun PlanetCompassScreen(epochDay: Double, lat: Double, lon: Double, now: Instant) {
    // Basic setup
    val planets = remember { getOrreryPlanets() }

    // State to hold calculated data
    var plotData by remember { mutableStateOf<List<PlotObject>>(emptyList()) }
    var calculationTime by remember { mutableStateOf(0.0) }

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

    // --- ASYNC CALCULATION ---
    LaunchedEffect(epochDay, lat, lon) {
        withContext(Dispatchers.Default) {
            val offset = round(lon / 15.0)
            val jdStart = epochDay + 2440587.5
            val newList = mutableListOf<PlotObject>()

            // 1. Sun
            // Visual Position: High Precision (Engine)
            val sunState = AstroEngine.getBodyState("Sun", jdStart)

            // Events: Standard Low Precision (Math) - Matches TransitsScreen
            // We manually reconstruct the event logic here since AstroMath doesn't have a direct "getSunEvents" returning a triplet.
            val sunKepler = calculateSunPositionKepler(jdStart + 0.5) // Noon
            val (sunRise, sunSet) = calculateRiseSet(sunKepler.ra, sunKepler.dec, lat, lon, offset, HORIZON_REFRACTED, epochDay)

            // Calculate Sun Transit (Local Apparent Noon)
            val nSun = (jdStart + 0.5) - 2451545.0
            val gmstSun = (6.697374558 + 0.06570982441908 * nSun) % 24.0
            val gmstFixedSun = if (gmstSun < 0) gmstSun + 24.0 else gmstSun
            val sunTransitUT = normalizeTime((sunKepler.ra / 15.0) - (lon / 15.0) - gmstFixedSun)
            val sunTransit = normalizeTime(sunTransitUT + offset)

            val sunEvents = PlanetEvents(sunRise, sunTransit, sunSet)
            newList.add(PlotObject("Sun", "☉", redColorInt, sunState.ra, sunState.dec, sunEvents))


            // 2. Moon
            // Visual Position: High Precision (Engine)
            val moonState = AstroEngine.getBodyState("Moon", jdStart)

            // Calculate LST for the current 'now' to place the dot correctly on the Radar
            val lstStr = calculateLST(now, lon)
            val parts = lstStr.split(":")
            val lstVal = parts[0].toDouble() + parts[1].toDouble()/60.0
            val topoMoon = toTopocentric(moonState.ra, moonState.dec, moonState.distGeo, lat, lon, lstVal)

            // Events: Standard Low Precision (Math) - Matches TransitsScreen
            val moonEvents = calculateMoonEvents(epochDay, lat, lon, offset)

            // Use Topocentric coords for plotting the dot, but standard events for the table
            newList.add(PlotObject("Moon", "☾", redColorInt, topoMoon.ra, topoMoon.dec, moonEvents))


            // 3. Planets
            for (p in planets) {
                if (p.name != "Earth") {
                    // Visual Position: High Precision (Engine)
                    val state = AstroEngine.getBodyState(p.name, jdStart)
                    val col = p.color.toArgb()

                    // Events: Standard Low Precision (Math) - Matches TransitsScreen
                    val events = calculatePlanetEvents(epochDay, lat, lon, offset, p)

                    newList.add(PlotObject(p.name, p.symbol, col, state.ra, state.dec, events))
                }
            }

            plotData = newList
            calculationTime = epochDay
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        if (plotData.isEmpty()) {
            Text("Calculating...", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            CompassCanvas(
                plotData = plotData,
                lat = lat,
                lon = lon,
                now = now,
                paints = paints
            )
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
    paints: CompassPaints
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val tickTriangleHeight = 25f
        val tickTriangleHalfBase = 12f
        val smallTickLength = 12.5f

        // Time Strings
        val utFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
        val utStr = utFormatter.format(now)
        val lstStr = calculateLST(now, lon)
        val lstParts = lstStr.split(":")
        val lst = lstParts[0].toDouble() + (lstParts[1].toDouble() / 60.0)

        // Header text parts for multi-color rendering
        val headerPart1 = "Planet positions at  UT "
        val headerPart2 = utStr
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
                val pColor = if (alt > 0) paints.whiteInt else paints.redInt

                // Plot Az (Rotate -90)
                drawMarker(centerRightOff, Math.toRadians(az - 90.0), obj.symbol, pColor, occupiedAz)
                // Plot El (Map -90..90 to 270..90 -> 180+alt)
                drawMarker(centerLeftOff, Math.toRadians(180.0 + alt), obj.symbol, pColor, occupiedEl)
            }

            // 4. Data Table
            val tableTop = centerY + radius + 60f + (3 * rowHeight)
            val cols = listOf(20f, w*0.22f, w*0.34f, w*0.44f, w*0.59f, w*0.69f, w*0.84f, w*0.96f)

            val row1Y = tableTop; val row2Y = tableTop + rowHeight

            nc.drawText("Rising", cols[2] + (cols[3]-cols[2])*0.4f - 20f, row1Y, paints.tableHeaderCenter)
            nc.drawText("Transit", cols[4] + (cols[5]-cols[4])*0.4f - 20f, row1Y, paints.tableHeaderCenter)
            nc.drawText("Setting", cols[6] + (cols[7]-cols[6])*0.4f - 20f, row1Y, paints.tableHeaderCenter)

            nc.drawText("Planet", cols[0], row2Y, paints.tableHeaderLeft)
            nc.drawText("HA", cols[1], row2Y, paints.tableHeaderCenter)
            nc.drawText("Time (UT)", cols[2], row2Y, paints.tableHeaderCenter)
            nc.drawText("Time (UT)", cols[4], row2Y, paints.tableHeaderCenter)
            nc.drawText("Time (UT)", cols[6], row2Y, paints.tableHeaderCenter)
            nc.drawText("Az", cols[3], row2Y, paints.tableHeaderRight)
            nc.drawText("El", cols[5], row2Y, paints.tableHeaderRight)
            nc.drawText("Az", cols[7], row2Y, paints.tableHeaderRight)

            var currY = row2Y + rowHeight + 5f
            val offset = round(lon/15.0)

            for (obj in plotData) {
                val raHours = obj.ra / 15.0
                val (currAz, currAlt) = calculateAzAlt(lst, lat, raHours, obj.dec)
                val haNorm = normalizeHourAngle(lst - raHours)

                // Name
                paints.tableDataLeft.color = if (currAlt > 0) paints.greenInt else paints.redInt
                nc.drawText(obj.name, cols[0], currY, paints.tableDataLeft)

                // HA
                paints.tableDataCenter.color = if (currAlt > 0) paints.whiteInt else paints.grayInt
                nc.drawText(formatTimeMM(haNorm, true), cols[1]-15f, currY, paints.tableDataCenter)

                // Rise
                val riseUT = normalizeTime(obj.events.rise - offset)
                paints.tableDataCenter.color = if (currAlt <= 0) paints.whiteInt else paints.grayInt
                nc.drawText(formatTimeMM(riseUT, false), cols[2], currY, paints.tableDataCenter)
                val riseAz = calculateAzAtRiseSet(lat, obj.dec, true)
                paints.tableDataRight.color = paints.tableDataCenter.color
                nc.drawText("%.0f".format(riseAz), cols[3]+20f, currY, paints.tableDataRight)

                // Transit
                val transUT = normalizeTime(obj.events.transit - offset)
                val isPre = (currAlt > 0 && haNorm < 0)
                paints.tableDataCenter.color = if (isPre) paints.whiteInt else paints.grayInt
                nc.drawText(formatTimeMM(transUT, false), cols[4], currY, paints.tableDataCenter)
                val transEl = 90.0 - abs(lat - obj.dec)
                paints.tableDataRight.color = paints.tableDataCenter.color
                nc.drawText("%.0f".format(transEl), cols[5], currY, paints.tableDataRight)

                // Set
                val setUT = normalizeTime(obj.events.set - offset)
                val isPost = (currAlt > 0 && haNorm > 0)
                paints.tableDataCenter.color = if (isPost) paints.whiteInt else paints.grayInt
                nc.drawText(formatTimeMM(setUT, false), cols[6], currY, paints.tableDataCenter)
                val setAz = calculateAzAtRiseSet(lat, obj.dec, false)
                paints.tableDataRight.color = paints.tableDataCenter.color
                nc.drawText("%.0f".format(setAz), cols[7]+20f, currY, paints.tableDataRight)

                currY += rowHeight
            }
        }
    }
}

// --- HELPER CLASSES & LOGIC ---

data class PlotObject(val name: String, val symbol: String, val color: Int, val ra: Double, val dec: Double, val events: PlanetEvents)

class CompassPaints(
    val greenInt: Int, val redInt: Int, val whiteInt: Int, val grayInt: Int,
    tickCol: Int, grayTickCol: Int, headerCol: Int
) {
    val header = Paint().apply { color=android.graphics.Color.WHITE; textSize=45f; textAlign=Paint.Align.LEFT; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias=true }
    val headerYellow = Paint().apply { color=android.graphics.Color.YELLOW; textSize=45f; textAlign=Paint.Align.LEFT; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias=true }
    val label = Paint().apply { color=greenInt; textSize=40f; textAlign=Paint.Align.CENTER; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); isAntiAlias=true }
    val labelRed = Paint().apply { color=redInt; textSize=40f; textAlign=Paint.Align.CENTER; typeface=Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); isAntiAlias=true }
    val triangleTick = Paint().apply { color=tickCol; style=Paint.Style.FILL; isAntiAlias=true }
    val grayTick = Paint().apply { color=grayTickCol; style=Paint.Style.STROKE; strokeWidth=2f; isAntiAlias=true }
    val line = Paint().apply { strokeWidth=4f; style=Paint.Style.STROKE; isAntiAlias=true }
    val symbol = Paint().apply { textSize=54f; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD; isAntiAlias=true }

    val tableHeaderCenter = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.CENTER; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableHeaderLeft = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.LEFT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableHeaderRight = Paint().apply { color=headerCol; textSize=24f; textAlign=Paint.Align.RIGHT; typeface=Typeface.MONOSPACE; isAntiAlias=true }

    val tableDataCenter = Paint().apply { textSize=29f; textAlign=Paint.Align.CENTER; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableDataLeft = Paint().apply { textSize=29f; textAlign=Paint.Align.LEFT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
    val tableDataRight = Paint().apply { textSize=29f; textAlign=Paint.Align.RIGHT; typeface=Typeface.MONOSPACE; isAntiAlias=true }
}

// --- SHARED MATH HELPERS ---

fun calculateAzAtRiseSet(lat: Double, dec: Double, isRise: Boolean): Double {
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(dec)
    val cosAz = sin(decRad) / cos(latRad)
    if (cosAz < -1.0 || cosAz > 1.0) return Double.NaN
    val azRad = acos(cosAz)
    val azDeg = Math.toDegrees(azRad)
    return if (isRise) azDeg else 360.0 - azDeg
}