package com.kenyoung.orrery

import androidx.compose.ui.graphics.drawscope.DrawScope
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
