package com.kenyoung.orrery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.*

val jovianCreamColor = Color(0xFFFDEEBD)
val jovianMoonColors = mapOf(
    "Io" to Color.Red,
    "Europa" to Color(0xFF00FF00),
    "Ganymede" to Color(0xFFADD8E6),
    "Callisto" to Color(0xFFFFFF00)
)

data class MoonPosHighPrec(
    val x: Double, val y: Double, val z: Double,
    val shadowX: Double, val shadowY: Double,
    val shadowOnDisk: Boolean, val eclipsed: Boolean
)

fun calculateHighPrecisionPositions(jd: Double): Map<String, MoonPosHighPrec> {
    val jdTT = jd + (69.184 / 86400.0)

    val jupBody = AstroEngine.getBodyState("Jupiter", jdTT)
    val deltaAU = jupBody.distGeo

    val T = (jdTT - 2451545.0) / 36525.0
    val precessionDeg = 1.396971 * T + 0.0003086 * T * T
    val jupLamDegDate = jupBody.eclipticLon + precessionDeg
    val jupBetaDeg = jupBody.eclipticLat

    val moons = JovianPrecision.highAccuracyJovSats(jdTT, deltaAU, jupLamDegDate, jupBetaDeg)

    val sunState = AstroEngine.getBodyState("Sun", jdTT)
    var raDiff = sunState.ra - jupBody.ra
    while (raDiff < -180) raDiff += 360; while (raDiff > 180) raDiff -= 360
    val shadowSign = if (raDiff > 0) 1.0 else -1.0

    val r = AstroEngine.getBodyState("Earth", jdTT).distSun
    val R = jupBody.distSun
    val Delta = jupBody.distGeo
    val cosAlpha = (R * R + Delta * Delta - r * r) / (2 * R * Delta)
    val alpha = acos(cosAlpha.coerceIn(-1.0, 1.0))
    val shadowFactor = tan(alpha)
    val xShiftPerZ = shadowSign * shadowFactor

    val resultMap = mutableMapOf<String, MoonPosHighPrec>()
    val FLATTENING = 0.06487
    val yScale = 1.0 / (1.0 - FLATTENING)

    val moonRadii = mapOf("Io" to 0.0255, "Europa" to 0.0218, "Ganymede" to 0.0368, "Callisto" to 0.0337)

    for ((name, pos) in moons) {
        val x = pos.x
        val y = pos.y
        val z = -pos.z

        val sX = x + (z * xShiftPerZ)
        val sY = y
        val sYScaled = sY * yScale
        val sDistSq = sX * sX + sYScaled * sYScaled
        val shadowOnDisk = (z > 0) && (sDistSq < 1.0)

        val mRad = moonRadii[name] ?: 0.0
        val cX = -(z * xShiftPerZ)
        val cY = 0.0
        val distEclipseSq = (x - cX).pow(2) + ((y - cY) * yScale).pow(2)
        val isEclipsed = (z < 0) && (distEclipseSq < (1.0 + mRad).pow(2))

        resultMap[name] = MoonPosHighPrec(x, y, z, sX, sY, shadowOnDisk, isEclipsed)
    }
    return resultMap
}

fun DrawScope.drawJovianSystem(
    jd: Double,
    centerX: Float,
    centerY: Float,
    availableWidth: Float,
    flipX: Float = 1f,
    flipY: Float = -1f
) {
    val tanColor = Color(0xFFD2B48C)
    val shadowColor = Color.Black

    val maxElongationRadii = 32.0
    val topScalePxPerRad = ((availableWidth * 1.15f) / (2 * maxElongationRadii)).toFloat()

    val jFlatFactor = 0.06487
    val jW = topScalePxPerRad * 2f
    val jH = jW * (1.0 - jFlatFactor).toFloat()

    val currentPos = calculateHighPrecisionPositions(jd)

    data class DrawOp(val z: Double, val draw: DrawScope.() -> Unit)
    val drawList = mutableListOf<DrawOp>()

    // 1. Jupiter (Z=0)
    drawList.add(DrawOp(0.0) {
        drawOval(jovianCreamColor, topLeft = Offset(centerX - jW / 2, centerY - jH / 2), size = Size(jW, jH))
        val bandThickness = jH / 10f
        val bandWidth = jW * 0.8f
        val bandXOffset = jW * 0.1f
        val band1Top = centerY - jH / 8 - bandThickness / 2
        val band2Top = centerY + jH / 8 - bandThickness / 2
        drawRect(tanColor, topLeft = Offset(centerX - jW / 2 + bandXOffset, band1Top), size = Size(bandWidth, bandThickness))
        drawRect(tanColor, topLeft = Offset(centerX - jW / 2 + bandXOffset, band2Top), size = Size(bandWidth, bandThickness))
    })

    val moonColors = jovianMoonColors
    val mSize = 7.5f
    val mHalf = mSize / 2f

    // 2. Shadows (Z slightly > 0)
    moonColors.forEach { (name, _) ->
        val pos = currentPos[name] ?: return@forEach
        if (pos.shadowOnDisk) {
            if (!pos.shadowX.isNaN() && !pos.shadowY.isNaN()) {
                drawList.add(DrawOp(0.1) {
                    val sx = centerX + (pos.shadowX * topScalePxPerRad * flipX).toFloat()
                    val sy = centerY + (pos.shadowY * topScalePxPerRad * flipY).toFloat()
                    drawOval(shadowColor, topLeft = Offset(sx - mHalf, sy - mHalf), size = Size(mSize, mSize))
                })
            }
        }
    }

    // 3. Moons
    moonColors.forEach { (name, col) ->
        val pos = currentPos[name] ?: return@forEach
        if (!pos.eclipsed) {
            if (!pos.x.isNaN() && !pos.y.isNaN()) {
                drawList.add(DrawOp(pos.z) {
                    val mx = centerX + (pos.x * topScalePxPerRad * flipX).toFloat()
                    val my = centerY + (pos.y * topScalePxPerRad * flipY).toFloat()
                    drawRect(col, topLeft = Offset(mx - mHalf, my - mHalf), size = Size(mSize, mSize))
                })
            }
        }
    }

    // Sort and Draw
    drawList.sortBy { it.z }
    drawList.forEach { it.draw(this) }
}
