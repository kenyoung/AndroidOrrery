package com.kenyoung.orrery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Wraps drawing code in density-independent scaling.
 * All coordinates inside [block] are in reference-density units,
 * so layouts look the same physical size on every screen density.
 */
inline fun DrawScope.withDensityScaling(block: DrawScope.(w: Float, h: Float) -> Unit) {
    val dScale = density / REFERENCE_DENSITY
    val w = size.width / dScale
    val h = size.height / dScale
    drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.scale(dScale, dScale) }
    block(w, h)
    drawIntoCanvas { it.nativeCanvas.restore() }
}

// Map a display-clock hour to a drawArc angle on a 24-hour dial:
// 0h at the top, clockwise (drawArc's 0° is at 3-o'clock).
fun hourToArcDeg(hr: Double) = (hr / 24.0 * 360.0 - 90.0).toFloat()

// Filled pie wedge on a 24-hour dial between two local solar hours, mapped to
// the display clock. A wedge with an undefined endpoint (NaN, a Sun-altitude
// phase never reached at high latitudes) is skipped.
fun DrawScope.drawHourWedge(
    startSolar: Double, endSolar: Double, color: Color,
    solarOffset: Double, displayOffsetHours: Double, topLeft: Offset, size: Size
) {
    if (startSolar.isNaN() || endSolar.isNaN()) return
    val startHr = solarToDisplayHour(startSolar, solarOffset, displayOffsetHours)
    val sweepHr = normalizeTime(solarToDisplayHour(endSolar, solarOffset, displayOffsetHours) - startHr)
    if (sweepHr <= 0.0) return
    drawArc(color, hourToArcDeg(startHr), (sweepHr / 24.0 * 360.0).toFloat(),
        useCenter = true, topLeft = topLeft, size = size)
}

// Black wedges over the very-dark (meteor) sub-intervals of the night — one per
// interval, so a bright Moon rising mid-night leaves the moonlit gap unpainted
// instead of blacking out hours that are not dark. The interval values are in
// the darkGraphOffset frame (signed hours from local solar midnight), which is
// monotonic, so each sweep is a plain difference.
fun DrawScope.drawVeryDarkWedges(
    intervals: List<Pair<Double, Double>>,
    solarOffset: Double, displayOffsetHours: Double, topLeft: Offset, size: Size
) {
    for ((gStart, gEnd) in intervals) {
        val startArc = hourToArcDeg(solarToDisplayHour(gStart, solarOffset, displayOffsetHours))
        val sweepArc = ((gEnd - gStart) / 24.0 * 360.0).toFloat()
        drawArc(Color.Black, startArc, sweepArc, useCenter = true, topLeft = topLeft, size = size)
        // Polar night only: the wedge is the whole disc, which would vanish
        // against a black page background — a thin gray rim keeps it visible.
        // Ordinary partial wedges get no rim (it reads as a stray white arc).
        if (gEnd - gStart >= 24.0 - 1e-9) {
            drawArc(Color(0xFF555555), startArc, sweepArc, useCenter = false,
                topLeft = topLeft, size = size, style = Stroke(width = 2f))
        }
    }
}
