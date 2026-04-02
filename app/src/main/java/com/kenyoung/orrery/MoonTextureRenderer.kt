package com.kenyoung.orrery

import android.graphics.Bitmap
import kotlin.math.*

/**
 * Renders a Moon bitmap by projecting an equirectangular surface map onto a sphere,
 * accounting for optical libration and phase shading.
 *
 * The equirectangular texture has selenographic longitude on the x-axis (0° at center)
 * and latitude on the y-axis (north pole at top). The per-pixel algorithm:
 * 1. Maps each output pixel to a point on the unit sphere (visible hemisphere)
 * 2. Rotates by libration to find the selenographic coordinates
 * 3. Samples the texture with bilinear interpolation
 * 4. Applies phase shading (tanh-smoothed terminator)
 */
internal fun createTexturedMoonBitmap(
    texturePixels: IntArray,
    textureW: Int,
    textureH: Int,
    outputSize: Int,
    phaseAngleDeg: Double,
    libLonDeg: Double,
    libLatDeg: Double,
    southernHemisphere: Boolean
): Bitmap {
    val result = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(outputSize * outputSize)

    val radius = outputSize / 2.0
    val cx = radius
    val cy = radius
    val rSq = radius * radius

    // Pre-compute libration rotation trig values
    val cosLibLon = cos(Math.toRadians(libLonDeg))
    val sinLibLon = sin(Math.toRadians(libLonDeg))
    val cosLibLat = cos(Math.toRadians(libLatDeg))
    val sinLibLat = sin(Math.toRadians(libLatDeg))

    // Phase shading pre-computation
    val phaseRad = Math.toRadians(phaseAngleDeg)
    val cosPhase = cos(phaseRad)
    val isWaxing = phaseAngleDeg <= 180.0

    for (py in 0 until outputSize) {
        val dy = py - cy
        val dySq = dy * dy
        if (dySq >= rSq) continue

        val rAtY = sqrt(rSq - dySq)
        val xTerm = cosPhase * rAtY
        val transitionWidth = rAtY * 0.06

        for (px in 0 until outputSize) {
            val dx = px - cx
            if (dx * dx + dySq > rSq) continue

            // Step 1: Map pixel to selenographic view-space unit sphere
            // Screen down (dy+) = selenographic south, so negate y for selenographic north
            var sx = dx / radius
            var sy = -dy / radius

            // Step 2: Southern hemisphere — rotate view 180°
            if (southernHemisphere) {
                sx = -sx
                sy = -sy
            }

            val sz = sqrt(1.0 - sx * sx - sy * sy)

            // Step 3: Apply libration rotation (view-aligned → true selenographic)
            // R = Ry(libLon) * Rx(-libLat)
            // First Rx(-libLat):
            val x1 = sx
            val y1 = cosLibLat * sy + sinLibLat * sz
            val z1 = -sinLibLat * sy + cosLibLat * sz
            // Then Ry(libLon):
            val x2 = cosLibLon * x1 + sinLibLon * z1
            val y2 = y1
            val z2 = -sinLibLon * x1 + cosLibLon * z1

            // Step 4: Selenographic coordinates
            val lat = asin(y2.coerceIn(-1.0, 1.0))
            val lon = atan2(x2, z2)

            // Step 5: Equirectangular texture UV
            // u: lon ∈ [-π, π] → [0, 1], with 0° at center of texture
            // v: lat ∈ [π/2, -π/2] → [0, 1], north pole at top
            val u = (lon / PI + 1.0) / 2.0
            val v = 0.5 - lat / PI

            val texXf = u * (textureW - 1)
            val texYf = v * (textureH - 1)

            // Step 6: Bilinear interpolation
            val ix0 = floor(texXf).toInt()
            val iy0 = floor(texYf).toInt()
            val fx = texXf - ix0
            val fy = texYf - iy0

            // Wrap horizontally (longitude wraps), clamp vertically
            val tx0 = ((ix0 % textureW) + textureW) % textureW
            val tx1 = (tx0 + 1) % textureW
            val ty0 = iy0.coerceIn(0, textureH - 1)
            val ty1 = (iy0 + 1).coerceIn(0, textureH - 1)

            val p00 = texturePixels[ty0 * textureW + tx0]
            val p10 = texturePixels[ty0 * textureW + tx1]
            val p01 = texturePixels[ty1 * textureW + tx0]
            val p11 = texturePixels[ty1 * textureW + tx1]

            val w00 = (1.0 - fx) * (1.0 - fy)
            val w10 = fx * (1.0 - fy)
            val w01 = (1.0 - fx) * fy
            val w11 = fx * fy

            val r = (w00 * ((p00 shr 16) and 0xFF) + w10 * ((p10 shr 16) and 0xFF) +
                    w01 * ((p01 shr 16) and 0xFF) + w11 * ((p11 shr 16) and 0xFF))
            val g = (w00 * ((p00 shr 8) and 0xFF) + w10 * ((p10 shr 8) and 0xFF) +
                    w01 * ((p01 shr 8) and 0xFF) + w11 * ((p11 shr 8) and 0xFF))
            val b = (w00 * (p00 and 0xFF) + w10 * (p10 and 0xFF) +
                    w01 * (p01 and 0xFF) + w11 * (p11 and 0xFF))

            // Step 7: Phase shading (screen-space terminator)
            val effectiveX = if (southernHemisphere) -dx else dx
            val signedDist = if (isWaxing) effectiveX - xTerm else -xTerm - effectiveX
            val shadowFactor = if (transitionWidth < 0.001) {
                if (signedDist > 0.0) 1.0 else 0.0
            } else {
                (0.5 + 0.5 * tanh(signedDist / transitionWidth)).coerceIn(0.0, 1.0)
            }

            val finalR = (r * shadowFactor).toInt().coerceIn(0, 255)
            val finalG = (g * shadowFactor).toInt().coerceIn(0, 255)
            val finalB = (b * shadowFactor).toInt().coerceIn(0, 255)

            pixels[py * outputSize + px] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }
    }

    result.setPixels(pixels, 0, outputSize, 0, 0, outputSize, outputSize)
    return result
}
