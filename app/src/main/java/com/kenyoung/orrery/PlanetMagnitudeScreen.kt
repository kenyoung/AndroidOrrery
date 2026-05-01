package com.kenyoung.orrery

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private const val FORECAST_DAYS = 365
private const val SAMPLE_STEP_DAYS = 1.0
private const val SAMPLE_COUNT = FORECAST_DAYS + 1

private const val MAG_TOP = -5.5
private const val MAG_BOTTOM = 8.0
private const val MAG_GRID_STEP = 0.5
private const val MAG_LABEL_STEP = 1.0

private const val CHART_LEFT_MARGIN = 80f

// Line segments where solar elongation is below this threshold are drawn dim
// — the planet is too close to the Sun to observe regardless of its magnitude.
private const val NEAR_SUN_ELONG_DEG = 15f

// Alpha applied to the planet color for near-Sun line segments (0..255).
private const val NEAR_SUN_ALPHA = 0x50

// Elongation thresholds for marker detection. An outer-planet opposition is a
// local max of elongation at ≈180°; any planet's conjunction is a local min at ≈0°.
private const val OPPOSITION_THRESHOLD_DEG = 170f
private const val CONJUNCTION_THRESHOLD_DEG = 10f

private val YEAR_BUTTON_PADDING = PaddingValues(horizontal = 10.dp, vertical = 2.dp)

private val MONTH_ABBREV = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private data class PlanetMagSeries(
    val name: String,
    val symbol: String,
    val color: Color,
    val colorInt: Int,
    val magnitudes: FloatArray,
    val elongationsDeg: FloatArray,
    val events: List<MagEvent>,
)

// Opposition (outer planets, elongation max near 180°) or conjunction (any planet,
// elongation min near 0°). Stored as the sample's (dayOffset, magnitude) for drawing.
private data class MagEvent(val dayOffset: Double, val magnitude: Float)

private data class MagMonthTick(val dayOffset: Double, val month: Int, val year: Int)

@Composable
fun PlanetMagnitudeScreen(obs: ObserverState, onTimeDisplayChange: (Boolean) -> Unit) {
    var yearOffset by remember { mutableStateOf(0) }
    var tappedRefX by remember { mutableStateOf<Float?>(CHART_LEFT_MARGIN) }
    var hasTapped by remember { mutableStateOf(false) }

    val shiftedEpochSec = obs.now.epochSecond + yearOffset.toLong() * FORECAST_DAYS.toLong() * SECONDS_PER_DAY.toLong()
    val startJd = shiftedEpochSec.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD
    val dayKey = shiftedEpochSec / SECONDS_PER_DAY.toLong()

    val planets = remember { getOrreryPlanets().filter { it.name != "Earth" } }

    // Recomputed once per day. 7 planets × 366 samples, plus one Sun state per sample.
    val series: List<PlanetMagSeries> = remember(dayKey) {
        val sunDistances = DoubleArray(SAMPLE_COUNT)
        val sunRaDeg = DoubleArray(SAMPLE_COUNT)
        val sunDecDeg = DoubleArray(SAMPLE_COUNT)
        for (i in 0 until SAMPLE_COUNT) {
            val jd = startJd + i * SAMPLE_STEP_DAYS
            val ss = AstroEngine.getBodyState("Sun", jd)
            sunDistances[i] = ss.distGeo
            sunRaDeg[i] = ss.ra
            sunDecDeg[i] = ss.dec
        }
        planets.map { p ->
            val mags = FloatArray(SAMPLE_COUNT)
            val elongs = FloatArray(SAMPLE_COUNT)
            for (i in 0 until SAMPLE_COUNT) {
                val jd = startJd + i * SAMPLE_STEP_DAYS
                val state = AstroEngine.getBodyState(p.name, jd)
                val alphaDeg = Math.toDegrees(phaseAngleRad(state.distSun, state.distGeo, sunDistances[i]))
                val ringB = if (p.name == "Saturn")
                    SaturnMoonEngine.calculateRingTiltB(state.eclipticLon, state.eclipticLat, jd)
                else 0.0
                mags[i] = apparentMagnitude(p.name, state.distSun, state.distGeo, alphaDeg, ringB).toFloat()
                val decSRad = Math.toRadians(sunDecDeg[i])
                val raSRad = Math.toRadians(sunRaDeg[i])
                val decPRad = Math.toRadians(state.dec)
                val raPRad = Math.toRadians(state.ra)
                val cosSep = sin(decSRad) * sin(decPRad) +
                        cos(decSRad) * cos(decPRad) * cos(raPRad - raSRad)
                elongs[i] = Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0))).toFloat()
            }
            val events = mutableListOf<MagEvent>()
            for (i in 1 until SAMPLE_COUNT - 1) {
                val prev = elongs[i - 1]; val cur = elongs[i]; val next = elongs[i + 1]
                val isOppositionPeak = cur >= OPPOSITION_THRESHOLD_DEG && cur >= prev && cur >= next
                val isConjunctionDip = cur <= CONJUNCTION_THRESHOLD_DEG && cur <= prev && cur <= next
                if (isOppositionPeak || isConjunctionDip) {
                    events.add(MagEvent(i * SAMPLE_STEP_DAYS, mags[i]))
                }
            }
            PlanetMagSeries(p.name, p.symbol, p.color, p.color.toArgb(), mags, elongs, events)
        }
    }

    val zone: ZoneId = if (obs.useStandardTime)
        ZoneOffset.ofTotalSeconds((obs.stdOffsetHours * 3600).toInt())
    else
        ZoneOffset.UTC

    val monthTicks: List<MagMonthTick> = remember(dayKey, zone) {
        val startInstant = Instant.ofEpochSecond(shiftedEpochSec)
        val endInstant = startInstant.plusSeconds(FORECAST_DAYS.toLong() * SECONDS_PER_DAY.toLong())
        val startLocal = ZonedDateTime.ofInstant(startInstant, zone)
        var cursor = startLocal.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0).plusMonths(1)
        val out = mutableListOf<MagMonthTick>()
        while (!cursor.toInstant().isAfter(endInstant)) {
            val tickEpochSec = cursor.toInstant().epochSecond
            val dayOffset = (tickEpochSec - shiftedEpochSec).toDouble() / SECONDS_PER_DAY
            out.add(MagMonthTick(dayOffset, cursor.monthValue, cursor.year))
            cursor = cursor.plusMonths(1)
        }
        out
    }
    val startMonthOneBased = ZonedDateTime.ofInstant(Instant.ofEpochSecond(shiftedEpochSec), zone).monthValue

    val axisPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(0xAD, 0xD8, 0xE6)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val minorGridPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(0x55, 0x55, 0x55)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val majorGridPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(0x88, 0x88, 0x88)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(0xAD, 0xD8, 0xE6)
            textSize = 44f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
    }
    val monthLetterPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(0xAD, 0xD8, 0xE6)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val yearPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 44f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val symbolPaint = remember {
        Paint().apply {
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val linePaint = remember {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
    }
    val markerFillPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }
    val markerOutlinePaint = remember {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
    }
    val janTickPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            strokeWidth = 4.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val tapLinePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
    }
    val tapLabelPaint = remember {
        Paint().apply {
            textSize = 36f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }
    val tapDatePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    val tapDateFormatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    val brightPaths = remember { List(planets.size) { Path() } }
    val dimPaths = remember { List(planets.size) { Path() } }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    val dScale = density / REFERENCE_DENSITY
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            tappedRefX = offset.x / dScale
                            hasTapped = true
                        }
                    ) { change, _ ->
                        tappedRefX = change.position.x / dScale
                        hasTapped = true
                    }
                }
                .pointerInput(Unit) {
                    val dScale = density / REFERENCE_DENSITY
                    detectTapGestures { offset ->
                        tappedRefX = offset.x / dScale
                        hasTapped = true
                    }
                }
        ) {
            withDensityScaling { w, h ->

                val leftMargin = CHART_LEFT_MARGIN
                val rightMargin = 15f
                val topMargin = 140f
                val bottomMargin = 125f

                val chartLeft = leftMargin
                val chartRight = w - rightMargin
                val chartTop = topMargin
                val chartBottom = h - bottomMargin
                val chartWidth = chartRight - chartLeft
                val chartHeight = chartBottom - chartTop
                val magRange = MAG_BOTTOM - MAG_TOP

                fun dayToX(dayOffset: Double): Float =
                    (chartLeft + (dayOffset / FORECAST_DAYS) * chartWidth).toFloat()
                fun magToY(m: Double): Float =
                    (chartTop + (m - MAG_TOP) / magRange * chartHeight).toFloat()
                fun magToYClamped(m: Float): Float =
                    magToY(m.toDouble().coerceIn(MAG_TOP, MAG_BOTTOM))

                drawIntoCanvas { nc ->
                    val canvas = nc.nativeCanvas

                    // Horizontal gridlines every 0.5 mag across the chart. Integer-mag
                    // lines get the brighter paint.
                    run {
                        var g = ceil(MAG_TOP / MAG_GRID_STEP) * MAG_GRID_STEP
                        while (g <= MAG_BOTTOM + 1e-9) {
                            val y = magToY(g)
                            val paint = if (kotlin.math.abs(g - kotlin.math.round(g)) < 1e-6)
                                majorGridPaint else minorGridPaint
                            canvas.drawLine(chartLeft, y, chartRight, y, paint)
                            g += MAG_GRID_STEP
                        }
                    }

                    // Y-axis labels at integer magnitudes.
                    run {
                        var m = ceil(MAG_TOP / MAG_LABEL_STEP) * MAG_LABEL_STEP
                        while (m <= floor(MAG_BOTTOM / MAG_LABEL_STEP) * MAG_LABEL_STEP + 1e-9) {
                            val y = magToY(m)
                            canvas.drawLine(leftMargin - 6f, y, leftMargin, y, axisPaint)
                            val i = m.toInt()
                            val label = if (i == 0) "0" else "%+d".format(i)
                            canvas.drawText(label, leftMargin - 8f, y + 15f, labelPaint)
                            m += MAG_LABEL_STEP
                        }
                    }

                    // Per-planet polyline, split into bright + dim paths so segments
                    // with elongation below NEAR_SUN_ELONG_DEG render with reduced alpha.
                    for ((sIdx, s) in series.withIndex()) {
                        val bp = brightPaths[sIdx]
                        val dp = dimPaths[sIdx]
                        bp.reset(); dp.reset()
                        var prevNearSun: Boolean? = null
                        for (k in 0 until SAMPLE_COUNT - 1) {
                            val nearSun = s.elongationsDeg[k] < NEAR_SUN_ELONG_DEG &&
                                    s.elongationsDeg[k + 1] < NEAR_SUN_ELONG_DEG
                            val path = if (nearSun) dp else bp
                            if (prevNearSun != nearSun) {
                                path.moveTo(dayToX(k * SAMPLE_STEP_DAYS),
                                    magToYClamped(s.magnitudes[k]))
                                prevNearSun = nearSun
                            }
                            path.lineTo(dayToX((k + 1) * SAMPLE_STEP_DAYS),
                                magToYClamped(s.magnitudes[k + 1]))
                        }
                        linePaint.color = s.colorInt
                        canvas.drawPath(bp, linePaint)
                        linePaint.color = (s.colorInt and 0x00FFFFFF) or (NEAR_SUN_ALPHA shl 24)
                        canvas.drawPath(dp, linePaint)
                    }

                    // Opposition and conjunction markers (filled circle, planet-colored,
                    // with a thin white outline so they stand out on dim line segments).
                    for (s in series) {
                        for (e in s.events) {
                            val x = dayToX(e.dayOffset)
                            val y = magToYClamped(e.magnitude)
                            markerFillPaint.color = s.colorInt
                            canvas.drawCircle(x, y, 5f, markerFillPaint)
                            canvas.drawCircle(x, y, 5f, markerOutlinePaint)
                        }
                    }

                    // Planet symbols at the leftmost sample, offset just inside the chart.
                    for (s in series) {
                        val y = magToYClamped(s.magnitudes[0])
                        symbolPaint.color = s.colorInt
                        canvas.drawText(s.symbol, chartLeft + 14f, y - 6f, symbolPaint)
                    }

                    // Month ticks along the chart bottom.
                    val tickY = chartBottom
                    val tickLen = 6f
                    val janTickLen = tickLen * 10f
                    for (t in monthTicks) {
                        val x = dayToX(t.dayOffset)
                        if (t.month == 1) {
                            canvas.drawLine(x, tickY, x, tickY + janTickLen, janTickPaint)
                        } else {
                            canvas.drawLine(x, tickY, x, tickY + tickLen, axisPaint)
                        }
                    }

                    // Month-name letters centered in each month band.
                    val letterY = chartBottom + 35f
                    val boundaries = mutableListOf<Float>()
                    boundaries.add(chartLeft)
                    for (t in monthTicks) boundaries.add(dayToX(t.dayOffset))
                    boundaries.add(chartRight)
                    for (i in 0 until boundaries.size - 1) {
                        val leftX = boundaries[i]
                        val rightX = boundaries[i + 1]
                        if (rightX - leftX < 14f) continue
                        val monthOneBased = if (i == 0) startMonthOneBased else monthTicks[i - 1].month
                        val label = MONTH_ABBREV[monthOneBased - 1]
                        val cx = (leftX + rightX) / 2f
                        monthLetterPaint.color = if (monthOneBased == 1)
                            android.graphics.Color.YELLOW
                        else
                            android.graphics.Color.rgb(0xAD, 0xD8, 0xE6)
                        canvas.save()
                        canvas.rotate(90f, cx, letterY)
                        canvas.drawText(label, cx, letterY, monthLetterPaint)
                        canvas.restore()
                    }

                    val yearY = chartBottom + 105f
                    for (t in monthTicks) {
                        if (t.month != 7) continue
                        val label = t.year.toString()
                        val halfWidth = yearPaint.measureText(label) / 2f
                        val x = dayToX(t.dayOffset).coerceIn(chartLeft + halfWidth, chartRight - halfWidth)
                        canvas.drawText(label, x, yearY, yearPaint)
                    }

                    // Tap inspection: vertical line, per-planet magnitude readout, date above.
                    tappedRefX?.let { rawX ->
                        val tapX = rawX.coerceIn(chartLeft, chartRight)
                        val dayOffset = ((tapX - chartLeft) / chartWidth) * FORECAST_DAYS
                        val tappedEpochSec = shiftedEpochSec + (dayOffset * SECONDS_PER_DAY).toLong()
                        val tappedLocalDate = Instant.ofEpochSecond(tappedEpochSec).atZone(zone).toLocalDate()
                        val noonInstant = tappedLocalDate.atTime(12, 0).atZone(zone).toInstant()
                        val jdAtNoon = noonInstant.epochSecond.toDouble() / SECONDS_PER_DAY + UNIX_EPOCH_JD

                        // Magnitudes and elongations come from the per-day samples used
                        // to draw the lines, so the readout matches the chart exactly.
                        val sampleIdx = dayOffset.roundToInt().coerceIn(0, FORECAST_DAYS)
                        val magAt = DoubleArray(planets.size) {
                            series[it].magnitudes[sampleIdx].toDouble()
                        }
                        val elongDegAt = DoubleArray(planets.size) {
                            series[it].elongationsDeg[sampleIdx].toDouble()
                        }

                        // Dark hours above horizon: 5-minute-resolution scan of the 24h
                        // noon-to-noon window around the tapped date's night. A sample
                        // counts only when the Sun is at or below CIVIL_TWILIGHT and the
                        // planet is above PLANET_HORIZON_ALT.
                        val darkHoursAt = IntArray(planets.size)
                        run {
                            val darkStepMinutes = 5
                            val darkSampleCount = 24 * 60 / darkStepMinutes
                            val darkStepDays = darkStepMinutes / (24.0 * 60.0)
                            val minutesPerSample = darkStepMinutes
                            val sunDark = BooleanArray(darkSampleCount)
                            val lstAtSample = DoubleArray(darkSampleCount)
                            for (k in 0 until darkSampleCount) {
                                val jd = jdAtNoon + k * darkStepDays
                                val lst = calculateLSTHours(jd, obs.lon)
                                lstAtSample[k] = lst
                                val sunSampleState = AstroEngine.getBodyState("Sun", jd)
                                val sunAlt = calculateAzAlt(
                                    lst, obs.lat, sunSampleState.ra / 15.0, sunSampleState.dec
                                ).alt
                                sunDark[k] = sunAlt <= CIVIL_TWILIGHT
                            }
                            for ((idx, p) in planets.withIndex()) {
                                var darkMinutes = 0
                                for (k in 0 until darkSampleCount) {
                                    if (!sunDark[k]) continue
                                    val jd = jdAtNoon + k * darkStepDays
                                    val ps = AstroEngine.getBodyState(p.name, jd)
                                    val alt = calculateAzAlt(
                                        lstAtSample[k], obs.lat, ps.ra / 15.0, ps.dec
                                    ).alt
                                    if (alt > PLANET_HORIZON_ALT) darkMinutes += minutesPerSample
                                }
                                darkHoursAt[idx] = (darkMinutes / 60.0).roundToInt()
                            }
                        }

                        canvas.drawLine(tapX, chartTop, tapX, chartBottom, tapLinePaint)

                        // Labels on whichever side has more room.
                        val labelOnLeft = tapX > (chartLeft + chartRight) / 2f
                        tapLabelPaint.textAlign = if (labelOnLeft) Paint.Align.RIGHT else Paint.Align.LEFT
                        val labelX = if (labelOnLeft) tapX - 10f else tapX + 10f
                        for ((idx, s) in series.withIndex()) {
                            val y = magToYClamped(magAt[idx].toFloat())
                            tapLabelPaint.color = s.colorInt
                            val text = "%+.1fm  %.0f°  %dh".format(
                                magAt[idx], elongDegAt[idx], darkHoursAt[idx])
                            canvas.drawText(text, labelX, y + 12f, tapLabelPaint)
                        }

                        val dateStr = tappedLocalDate.format(tapDateFormatter)
                        val dateHalfWidth = tapDatePaint.measureText(dateStr) / 2f
                        val dateBaseline = chartTop - tapDatePaint.descent() - 2f
                        val dateX = tapX.coerceIn(dateHalfWidth, w - dateHalfWidth)
                        canvas.drawText(dateStr, dateX, dateBaseline, tapDatePaint)
                    }
                }
            }
        }

        val shiftYear: (Int) -> Unit = { delta ->
            yearOffset += delta
            tappedRefX = CHART_LEFT_MARGIN
        }
        YearShiftButton("-1yr", Alignment.TopStart) { shiftYear(-1) }
        YearShiftButton("+1yr", Alignment.TopEnd) { shiftYear(+1) }
        if (yearOffset != 0) {
            TextButton(
                onClick = { yearOffset = 0 },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00FF00)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
            ) {
                Text("Reset Time", fontWeight = FontWeight.Bold)
            }
        } else if (!hasTapped) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(4.dp)
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tap to move marker",
                    color = LabelColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun BoxScope.YearShiftButton(label: String, alignment: Alignment, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.DarkGray,
            contentColor = Color.White,
        ),
        contentPadding = YEAR_BUTTON_PADDING,
        modifier = Modifier
            .align(alignment)
            .padding(4.dp)
            .height(28.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
