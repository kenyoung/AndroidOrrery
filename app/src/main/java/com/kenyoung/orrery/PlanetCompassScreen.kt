package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun PlanetCompassScreen(epochDay: Double, lat: Double, lon: Double, now: Instant) {
    // Basic setup
    val zoneId = ZoneId.systemDefault()
    val planets = remember { getOrreryPlanets() } // From MainActivity

    // Colors
    val bgColor = Color.Black
    val lineStrokeColor = Color.White
    val labelColor = Color.Green
    val labelColorInt = labelColor.toArgb()
    val redColorInt = Color.Red.toArgb()
    val whiteColorInt = Color.White.toArgb()
    val grayColorInt = Color.Gray.toArgb()
    val tickColorInt = Color(0xFF87CEFA).toArgb() // Light Sky Blue
    val grayTickColorInt = Color.Gray.toArgb()
    val tableHeaderColorInt = tickColorInt

    // Paints
    val mainCirclePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
    }

    val labelPaint = remember {
        Paint().apply {
            color = labelColorInt
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }
    }

    val headerPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 45f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
    }

    val triangleTickPaint = remember {
        Paint().apply {
            color = tickColorInt
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    val grayTickPaint = remember {
        Paint().apply {
            color = grayTickColorInt
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
    }

    val planetLinePaint = remember {
        Paint().apply {
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }

    val planetSymbolPaint = remember {
        Paint().apply {
            textSize = 54f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
    }

    // Paints for Table
    val tableHeaderPaint = remember {
        Paint().apply {
            color = tableHeaderColorInt
            textSize = 24f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
    }

    val tableDataPaint = remember {
        Paint().apply {
            color = whiteColorInt
            textSize = 29f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().background(bgColor)) {
        val w = size.width
        val h = size.height

        val tickTriangleHeight = 25f
        val tickTriangleHalfBase = 12f
        val smallTickLength = 12.5f

        // --- Time Strings Calculation (HH:MM) ---
        val utFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
        val utStr = utFormatter.format(now)

        // Local LST Calculation (HH:MM)
        val epochSeconds = now.epochSecond
        val jd = epochSeconds / 86400.0 + 2440587.5
        val d = jd - 2451545.0
        var gmst = 18.697374558 + 24.06570982441908 * d
        gmst %= 24.0; if (gmst < 0) gmst += 24.0
        var lst = gmst + (lon / 15.0)
        lst %= 24.0; if (lst < 0) lst += 24.0
        val lstH = floor(lst).toInt()
        val lstMinPart = (lst - lstH) * 60.0
        val lstM = floor(lstMinPart).toInt()
        val lstStr = "%02d:%02d".format(lstH, lstM)

        val headerText = "Planet positions at  UT $utStr  LST $lstStr"

        // --- DRAWING HELPERS ---

        fun drawInternalTriangle(canvas: NativeCanvas, rimPoint: Offset, center: Offset) {
            val dx = center.x - rimPoint.x
            val dy = center.y - rimPoint.y
            val dist = sqrt(dx*dx + dy*dy)
            val cosA = dx / dist
            val sinA = dy / dist
            val tipX = rimPoint.x + tickTriangleHeight * cosA
            val tipY = rimPoint.y + tickTriangleHeight * sinA
            val perpX = -sinA * tickTriangleHalfBase
            val perpY = cosA * tickTriangleHalfBase
            val path = android.graphics.Path()
            path.moveTo(tipX, tipY)
            path.lineTo(rimPoint.x + perpX, rimPoint.y + perpY)
            path.lineTo(rimPoint.x - perpX, rimPoint.y - perpY)
            path.close()
            canvas.drawPath(path, triangleTickPaint)
        }

        fun drawGrayTick(canvas: NativeCanvas, angleDeg: Double, center: Offset, radius: Float) {
            val rad = Math.toRadians(angleDeg)
            val cosA = cos(rad)
            val sinA = sin(rad)
            val startX = center.x + radius * cosA
            val startY = center.y + radius * sinA
            val endX = center.x + (radius - smallTickLength) * cosA
            val endY = center.y + (radius - smallTickLength) * sinA
            canvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), grayTickPaint)
        }

        // --- LAYOUT CALCULATIONS ---
        val textY = 80f
        val gap = h * 0.1f
        val radius = min(w * 0.36f, h * 0.34f)
        val centerY = textY + gap + radius
        val centerRightX = w - radius
        val centerRightY = centerY
        val centerLeftX = radius
        val centerLeftY = centerY
        val centerRightOff = Offset(centerRightX, centerRightY)
        val centerLeftOff = Offset(centerLeftX, centerLeftY)
        val textInset = 70f

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(headerText, w / 2f, textY, headerPaint)
        }

        // --- 1. RIGHT CIRCLE (Azimuth) ---
        drawCircle(color = lineStrokeColor, radius = radius, center = centerRightOff, style = Stroke(width = 3f))

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            for (az in 0 until 360 step 10) {
                if (az % 90 != 0) {
                    val cartesianAngle = az - 90.0
                    drawGrayTick(nativeCanvas, cartesianAngle, centerRightOff, radius)
                }
            }
            labelPaint.color = labelColorInt
            labelPaint.textAlign = Paint.Align.CENTER
            nativeCanvas.drawText("N", centerRightX, centerRightY - radius + textInset, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerRightX, centerRightY - radius), centerRightOff)
            nativeCanvas.drawText("S", centerRightX, centerRightY + radius - (textInset / 2f), labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerRightX, centerRightY + radius), centerRightOff)
            labelPaint.textAlign = Paint.Align.RIGHT
            nativeCanvas.drawText("E", centerRightX + radius - textInset + 10f, centerRightY + 15f, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerRightX + radius, centerRightY), centerRightOff)
            labelPaint.textAlign = Paint.Align.LEFT
            nativeCanvas.drawText("W", centerRightX - radius + textInset - 10f, centerRightY + 15f, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerRightX - radius, centerRightY), centerRightOff)
        }

        // --- 2. LEFT CIRCLE (Elevation) ---
        drawArc(color = lineStrokeColor, startAngle = 90f, sweepAngle = 180f, useCenter = false, topLeft = Offset(centerLeftX - radius, centerLeftY - radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(width = 3f))

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            for (ang in 90..270 step 10) {
                if (ang != 90 && ang != 180 && ang != 270) {
                    drawGrayTick(nativeCanvas, ang.toDouble(), centerLeftOff, radius)
                }
            }
            labelPaint.color = labelColorInt
            labelPaint.textAlign = Paint.Align.CENTER
            nativeCanvas.drawText("Zenith", centerLeftX, centerLeftY - radius - 20f, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerLeftX, centerLeftY - radius), centerLeftOff)
            labelPaint.color = redColorInt
            nativeCanvas.drawText("Nadir", centerLeftX, centerLeftY + radius + 50f, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerLeftX, centerLeftY + radius), centerLeftOff)
            labelPaint.color = labelColorInt
            labelPaint.textAlign = Paint.Align.LEFT
            nativeCanvas.drawText("Horizon", centerLeftX - radius + textInset - 10f, centerLeftY + 15f, labelPaint)
            drawInternalTriangle(nativeCanvas, Offset(centerLeftX - radius, centerLeftY), centerLeftOff)
        }

        // --- 3. PLANET PLOTTING ---
        val tDay = epochDay

        data class PlotObject(val name: String, val symbol: String, val color: Int, val ra: Double, val dec: Double, val events: PlanetEvents)
        val objectList = mutableListOf<PlotObject>()
        val offset = round(lon / 15.0)

        // Sun
        val (sRa, sDec) = getSunRaDec(tDay)
        val sEvents = getSunEventsLocal(tDay, lat, lon, offset)
        objectList.add(PlotObject("Sun", "☉", redColorInt, sRa, sDec, sEvents))

        // Moon
        val (mRa, mDec) = getMoonRaDec(tDay)
        val mEvents = calculateMoonEvents(tDay, lat, lon, offset)
        objectList.add(PlotObject("Moon", "☾", redColorInt, mRa, mDec, mEvents))

        // Planets
        for (p in planets) {
            if (p.name != "Earth") {
                val (pRa, pDec) = getPlanetRaDec(tDay, p)
                val pEvents = calculatePlanetEvents(tDay, lat, lon, offset, p)
                val col = p.color.toArgb()
                objectList.add(PlotObject(p.name, p.symbol, col, pRa, pDec, pEvents))
            }
        }

        val defaultLineLen = radius / 10f
        val padding = 25f
        val occupiedRectsAzimuth = mutableListOf<Rect>()
        val occupiedRectsElevation = mutableListOf<Rect>()

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // Helper function to resolve collisions and draw marker
            fun drawMarker(center: Offset, angleRad: Double, symbol: String, baseColor: Int, occupiedRects: MutableList<Rect>) {
                var currentLineLen = defaultLineLen
                val cosA = cos(angleRad); val sinA = sin(angleRad)
                val maxLineLen = radius * 0.8f; val increment = 40f
                val textBounds = Rect(); planetSymbolPaint.getTextBounds(symbol, 0, symbol.length, textBounds)
                val halfW = textBounds.width() / 2; val halfH = textBounds.height() / 2

                var iterations = 0
                while (iterations < 10) {
                    val symbolDist = radius - currentLineLen - padding
                    val symX = center.x + symbolDist * cosA
                    val symY = center.y + symbolDist * sinA + (halfH / 2f)
                    val currentRect = Rect((symX - halfW - 5).toInt(), (symY - halfH - 5).toInt(), (symX + halfW + 5).toInt(), (symY + halfH + 5).toInt())

                    var intersects = false
                    for (occ in occupiedRects) { if (Rect.intersects(currentRect, occ)) { intersects = true; break } }

                    if (!intersects) {
                        planetLinePaint.color = baseColor
                        planetSymbolPaint.color = baseColor
                        val startX = center.x + radius * cosA; val startY = center.y + radius * sinA
                        val endX = center.x + (radius - currentLineLen) * cosA; val endY = center.y + (radius - currentLineLen) * sinA
                        nativeCanvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), planetLinePaint)
                        nativeCanvas.drawText(symbol, symX.toFloat(), symY.toFloat(), planetSymbolPaint)
                        occupiedRects.add(currentRect)
                        return
                    }
                    currentLineLen += increment; iterations++
                    if (currentLineLen > maxLineLen) break
                }
            }

            for (obj in objectList) {
                val (az, alt) = calculateAzAlt(lst, lat, obj.ra, obj.dec)
                val pColor = if (alt > 0) whiteColorInt else redColorInt
                // Azimuth Plot
                val azRad = Math.toRadians(az - 90.0)
                drawMarker(centerRightOff, azRad, obj.symbol, pColor, occupiedRectsAzimuth)
                // Elevation Plot
                val elRad = Math.toRadians(180.0 + alt)
                drawMarker(centerLeftOff, elRad, obj.symbol, pColor, occupiedRectsElevation)
            }
        }

        // --- 4. DATA TABLE ---
        val rowHeight = 38f
        val tableTop = centerLeftY + radius + 60f + (3 * rowHeight)

        // Column positions
        val col0X = 20f
        val col1X = w * 0.22f
        val col2X = w * 0.34f
        val col3X = w * 0.44f
        val col4X = w * 0.59f
        val col5X = w * 0.69f
        val col6X = w * 0.84f
        val col7X = w * 0.96f

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            val row1Y = tableTop
            val row2Y = tableTop + rowHeight

            // Group Headers (Shifted Left for better visual centering)
            val riseGroupX = col2X + (col3X - col2X) * 0.4f - 20f
            val transGroupX = col4X + (col5X - col4X) * 0.4f - 20f
            val setGroupX = col6X + (col7X - col6X) * 0.4f - 20f

            tableHeaderPaint.textAlign = Paint.Align.CENTER
            nc.drawText("Rising", riseGroupX, row1Y, tableHeaderPaint)
            nc.drawText("Transit", transGroupX, row1Y, tableHeaderPaint)
            nc.drawText("Setting", setGroupX, row1Y, tableHeaderPaint)

            // Sub Headers
            tableHeaderPaint.textAlign = Paint.Align.LEFT
            nc.drawText("Planet", col0X, row2Y, tableHeaderPaint)

            tableHeaderPaint.textAlign = Paint.Align.CENTER
            nc.drawText("HA", col1X, row2Y, tableHeaderPaint)

            // Times (Center)
            nc.drawText("Time (UT)", col2X, row2Y, tableHeaderPaint)
            nc.drawText("Time (UT)", col4X, row2Y, tableHeaderPaint)
            nc.drawText("Time (UT)", col6X, row2Y, tableHeaderPaint)

            // Az/El (Right)
            tableHeaderPaint.textAlign = Paint.Align.RIGHT
            nc.drawText("Az", col3X, row2Y, tableHeaderPaint)
            nc.drawText("El", col5X, row2Y, tableHeaderPaint)
            nc.drawText("Az", col7X, row2Y, tableHeaderPaint)

            var currentY = row2Y + rowHeight + 5f

            for (obj in objectList) {
                val (currAz, currAlt) = calculateAzAlt(lst, lat, obj.ra, obj.dec)
                val ha = lst - obj.ra
                var haNorm = ha
                while(haNorm < -12) haNorm += 24.0
                while(haNorm > 12) haNorm -= 24.0

                // Name
                val nameColor = if (currAlt > 0) labelColorInt else redColorInt
                tableDataPaint.color = nameColor
                tableDataPaint.textAlign = Paint.Align.LEFT
                nc.drawText(obj.name, col0X, currentY, tableDataPaint)

                // HA (Centered with Space Padding)
                tableDataPaint.color = if (currAlt > 0) whiteColorInt else grayColorInt
                tableDataPaint.textAlign = Paint.Align.CENTER
                val haStr = formatTimeMM(haNorm, true)
                nc.drawText(haStr, col1X - 15f, currentY, tableDataPaint)

                // Rising
                tableDataPaint.color = if (currAlt <= 0) whiteColorInt else grayColorInt
                val riseUT = normalizeTime(obj.events.rise - offset)
                val riseAz = calculateAzAtRiseSet(lat, obj.dec, true)

                tableDataPaint.textAlign = Paint.Align.CENTER
                nc.drawText(formatTimeMM(riseUT, false), col2X, currentY, tableDataPaint)
                tableDataPaint.textAlign = Paint.Align.RIGHT
                // Shifted Right by 20f
                nc.drawText("%.0f".format(riseAz), col3X + 20f, currentY, tableDataPaint)

                // Transit
                val isPreTransit = (currAlt > 0 && haNorm < 0)
                tableDataPaint.color = if (isPreTransit) whiteColorInt else grayColorInt
                val transUT = normalizeTime(obj.events.transit - offset)
                val transEl = 90.0 - abs(lat - obj.dec)

                tableDataPaint.textAlign = Paint.Align.CENTER
                nc.drawText(formatTimeMM(transUT, false), col4X, currentY, tableDataPaint)
                tableDataPaint.textAlign = Paint.Align.RIGHT
                nc.drawText("%.0f".format(transEl), col5X, currentY, tableDataPaint)

                // Setting
                val isPostTransit = (currAlt > 0 && haNorm > 0)
                tableDataPaint.color = if (isPostTransit) whiteColorInt else grayColorInt
                val setUT = normalizeTime(obj.events.set - offset)
                val setAz = calculateAzAtRiseSet(lat, obj.dec, false)

                tableDataPaint.textAlign = Paint.Align.CENTER
                nc.drawText(formatTimeMM(setUT, false), col6X, currentY, tableDataPaint)
                tableDataPaint.textAlign = Paint.Align.RIGHT
                // Shifted Right by 20f
                nc.drawText("%.0f".format(setAz), col7X + 20f, currentY, tableDataPaint)

                currentY += rowHeight
            }
        }
    }
}

// --- LOCAL MATH HELPERS ---

fun calculateAzAlt(lstHours: Double, latDeg: Double, raHours: Double, decDeg: Double): Pair<Double, Double> {
    val haHours = lstHours - raHours
    val haRad = Math.toRadians(haHours * 15.0)
    val latRad = Math.toRadians(latDeg)
    val decRad = Math.toRadians(decDeg)
    val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
    val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
    val cosAz = (sin(decRad) - sin(altRad) * sin(latRad)) / (cos(altRad) * cos(latRad))
    val azRadAbs = acos(cosAz.coerceIn(-1.0, 1.0))
    val sinHA = sin(haRad)
    var azDeg = Math.toDegrees(azRadAbs)
    if (sinHA > 0) azDeg = 360.0 - azDeg
    return Pair(azDeg, Math.toDegrees(altRad))
}

fun getSunRaDec(epochDay: Double): Pair<Double, Double> {
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))
    var ra = Math.toDegrees(alpha) / 15.0; if (ra < 0) ra += 24.0
    return Pair(ra, Math.toDegrees(delta))
}

fun getSunEventsLocal(epochDay: Double, lat: Double, lon: Double, offset: Double): PlanetEvents {
    val n = (2440587.5 + epochDay + 0.5) - 2451545.0
    var L = (280.460 + 0.9856474 * n) % 360.0; if (L < 0) L += 360.0
    var g = (357.528 + 0.9856003 * n) % 360.0; if (g < 0) g += 360.0
    val lambdaRad = Math.toRadians(L + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(2 * Math.toRadians(g)))
    val epsilonRad = Math.toRadians(23.439 - 0.0000004 * n)
    val alpha = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
    val delta = asin(sin(epsilonRad) * sin(lambdaRad))
    val cosH = (sin(Math.toRadians(-0.833)) - sin(Math.toRadians(lat)) * sin(delta)) / (cos(Math.toRadians(lat)) * cos(delta))

    val raHours = Math.toDegrees(alpha) / 15.0
    var GMST0 = (6.697374558 + 0.06570982441908 * n) % 24.0; if (GMST0 < 0) GMST0 += 24.0
    var transitUT = raHours - (lon / 15.0) - GMST0
    while (transitUT < 0) transitUT += 24.0; while (transitUT >= 24) transitUT -= 24.0
    val transitStandard = transitUT + offset

    if (cosH < -1.0 || cosH > 1.0) return PlanetEvents(Double.NaN, transitStandard, Double.NaN)
    val H_hours = Math.toDegrees(acos(cosH)) / 15.0
    var rise = transitStandard - H_hours; while (rise < 0) rise += 24.0; while (rise >= 24) rise -= 24.0
    var set = transitStandard + H_hours; while (set < 0) set += 24.0; while (set >= 24) set -= 24.0
    return PlanetEvents(rise, transitStandard, set)
}

fun getMoonRaDec(epochDay: Double): Pair<Double, Double> {
    val T = (epochDay + 2440587.5 - 2451545.0) / 36525.0
    val L_prime = Math.toRadians(218.3164477 + 481267.88123421 * T)
    val D = Math.toRadians(297.8501921 + 445267.1114034 * T)
    val M = Math.toRadians(357.5291092 + 35999.0502909 * T)
    val M_prime = Math.toRadians(134.9633964 + 477198.8675055 * T)
    val F = Math.toRadians(93.2720950 + 483202.0175233 * T)
    val lambda = L_prime + Math.toRadians(6.289 * sin(M_prime)) + Math.toRadians(-1.274 * sin(M_prime - 2 * D)) + Math.toRadians(0.658 * sin(2 * D)) + Math.toRadians(-0.186 * sin(M))
    val beta = Math.toRadians(5.128 * sin(F)) + Math.toRadians(0.280 * sin(M_prime + F))
    val epsilon = Math.toRadians(23.439291 - 0.0130042 * T)
    val x = cos(beta) * cos(lambda)
    val y = cos(epsilon) * cos(beta) * sin(lambda) - sin(epsilon) * sin(beta)
    val z = sin(epsilon) * cos(beta) * sin(lambda) + cos(epsilon) * sin(beta)
    var ra = Math.toDegrees(atan2(y, x)) / 15.0; if (ra < 0) ra += 24.0
    return Pair(ra, Math.toDegrees(asin(z)))
}

fun getPlanetRaDec(epochDay: Double, p: PlanetElements): Pair<Double, Double> {
    val d = (2440587.5 + epochDay + 0.5) - 2451545.0
    val Me = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
    val Le_earth = Math.toRadians((280.466 + 0.98564736 * d) % 360.0) + Math.toRadians(1.915 * sin(Me) + 0.020 * sin(2 * Me)) + Math.PI
    val Re = 1.00014 - 0.01671 * cos(Me) - 0.00014 * cos(2 * Me)
    val xe = Re * cos(Le_earth); val ye = Re * sin(Le_earth); val ze = 0.0
    val Lp = Math.toRadians((p.L_0 + p.L_rate * d) % 360.0); val Np = Math.toRadians(p.N)
    val ip = Math.toRadians(p.i); val w_bar_p = Math.toRadians(p.w_bar); var Mp = Lp - w_bar_p
    val Ep = solveKepler(Mp, p.e)
    val xv = p.a * (cos(Ep) - p.e); val yv = p.a * sqrt(1 - p.e*p.e) * sin(Ep)
    val v = atan2(yv, xv); val u = v + w_bar_p - Np
    val rp = sqrt(xv*xv + yv*yv)
    val xh = rp * (cos(u) * cos(Np) - sin(u) * sin(Np) * cos(ip))
    val yh = rp * (cos(u) * sin(Np) + sin(u) * cos(Np) * cos(ip))
    val zh = rp * (sin(u) * sin(ip))
    val xg = xh - xe; val yg = yh - ye; val zg = zh - ze
    val ecl = Math.toRadians(23.439 - 0.0000004 * d)
    val xeq = xg; val yeq = yg * cos(ecl) - zg * sin(ecl); val zeq = yg * sin(ecl) + zg * cos(ecl)
    var ra = Math.toDegrees(atan2(yeq, xeq)) / 15.0; if (ra < 0) ra += 24.0
    return Pair(ra, Math.toDegrees(atan2(zeq, sqrt(xeq*xeq + yeq*yeq))))
}

fun calculateAzAtRiseSet(lat: Double, dec: Double, isRise: Boolean): Double {
    val latRad = Math.toRadians(lat)
    val decRad = Math.toRadians(dec)
    val cosAz = sin(decRad) / cos(latRad)
    if (cosAz < -1.0 || cosAz > 1.0) return Double.NaN
    val azRad = acos(cosAz)
    val azDeg = Math.toDegrees(azRad)
    return if (isRise) azDeg else 360.0 - azDeg
}

fun normalizeTime(t: Double): Double {
    var v = t
    while(v < 0) v += 24.0
    while(v >= 24) v -= 24.0
    return v
}

fun formatTimeMM(t: Double, isSigned: Boolean): String {
    if (t.isNaN()) return "--:--"
    val absT = abs(t)
    val h = floor(absT).toInt()
    val m = floor((absT - h) * 60.0).toInt()
    // Add space padding for positive numbers if signed is requested
    val sign = if (isSigned) {
        if (t < 0) "-" else " "
    } else ""
    return "%s%02d:%02d".format(sign, h, m)
}