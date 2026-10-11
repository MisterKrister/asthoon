package com.asthoonlite.gui

import kotlin.math.ceil
import kotlin.math.floor

/** Half-open GUI bounds; rounding outwards keeps the entire native input area. */
data class GuiInputBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Uniformly fits a logical settings layout into Minecraft's scaled GUI viewport. */
data class GuiViewport private constructor(
    val logicalWidth: Int,
    val logicalHeight: Int,
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double
) {
    fun logicalX(screenX: Double): Double = (screenX - offsetX) / scale
    fun logicalY(screenY: Double): Double = (screenY - offsetY) / scale
    fun logicalDelta(screenDelta: Double): Double = screenDelta / scale
    fun screenX(logicalX: Double): Double = offsetX + logicalX * scale
    fun screenY(logicalY: Double): Double = offsetY + logicalY * scale

    fun screenBounds(logical: GuiInputBounds) = GuiInputBounds(
        floor(screenX(logical.left.toDouble())).toInt(),
        floor(screenY(logical.top.toDouble())).toInt(),
        ceil(screenX(logical.right.toDouble())).toInt(),
        ceil(screenY(logical.bottom.toDouble())).toInt()
    )

    companion object {
        fun fit(
            screenWidth: Int,
            screenHeight: Int,
            minimumWidth: Int,
            minimumHeight: Int
        ): GuiViewport {
            require(minimumWidth > 0 && minimumHeight > 0)
            // A minimized window can briefly report zero; retain a finite transform.
            val w = screenWidth.coerceAtLeast(1)
            val h = screenHeight.coerceAtLeast(1)
            val logicalW = maxOf(w, minimumWidth)
            val logicalH = maxOf(h, minimumHeight)
            val scale = minOf(w.toDouble() / logicalW, h.toDouble() / logicalH, 1.0)
            return GuiViewport(
                logicalW, logicalH, scale,
                (w - logicalW * scale) / 2.0,
                (h - logicalH * scale) / 2.0
            )
        }
    }
}

/** Minecraft 26.1.2 IMEPreeditOverlay's hot area, evaluated in logical GUI space. */
internal fun preeditInputBounds(
    inputLeft: Int,
    inputTop: Int,
    inputHeight: Int,
    textWidth: Int,
    lineHeight: Int,
    guiWidth: Int,
    guiHeight: Int
): GuiInputBounds {
    val textLeft = if (inputLeft + textWidth > guiWidth) guiWidth - textWidth else inputLeft
    val textRight = textLeft + textWidth
    val inputBottom = inputTop + inputHeight
    val textBottom = if (inputBottom + 4 + lineHeight > guiHeight) {
        inputTop - 4 - lineHeight
    } else {
        inputBottom + 4 + lineHeight
    }
    val textTop = textBottom - lineHeight
    return GuiInputBounds(
        minOf(textLeft, inputLeft) - 2,
        minOf(textTop, inputTop) - 2,
        textRight + 2,
        maxOf(textBottom, inputBottom) + 2
    )
}
