package com.asthoonlite.gui

import kotlin.math.abs

fun guiViewportRegressionChecks() {
    fun near(actual: Double, expected: Double) = abs(actual - expected) < 1e-9

    // AUTO scale 6 at 2304x1440, and scale 2 at 854x480, both leave 240 GUI pixels high.
    for ((width, height) in listOf(384 to 240, 427 to 240, 320 to 180, 540 to 360, 854 to 480, 1280 to 720)) {
        val viewport = GuiViewport.fit(width, height, 564, 384)
        val panelLeft = (viewport.logicalWidth - 540) / 2.0
        val panelHeight = (viewport.logicalHeight - 24).coerceIn(360, 560)
        val panelTop = (viewport.logicalHeight - panelHeight) / 2.0
        check(viewport.scale > 0.0 && viewport.scale <= 1.0)
        check(viewport.screenX(panelLeft - 3) >= 0 && viewport.screenY(panelTop - 3) >= 0) {
            "Settings shadow must fit the ${width}x$height GUI viewport"
        }
        check(viewport.screenX(panelLeft + 543) <= width && viewport.screenY(panelTop + panelHeight + 3) <= height)

        // Click/hover/release coordinates and slider/scrollbar/node drag distances
        // all use the same inverse as the rendering pose, including centered margins.
        for (x in listOf(-5.0, panelLeft, panelLeft + 145, panelLeft + 512, viewport.logicalWidth.toDouble())) {
            check(near(viewport.logicalX(viewport.screenX(x)), x))
        }
        for (y in listOf(-5.0, panelTop + 9, panelTop + 90, panelTop + panelHeight - 26)) {
            check(near(viewport.logicalY(viewport.screenY(y)), y))
        }
        for (delta in listOf(-64.0, -4.0, 0.0, 4.0, 128.0)) {
            check(near(viewport.logicalDelta(delta * viewport.scale), delta)) {
                "Scaled drags must preserve logical distances"
            }
        }
    }
    val small = GuiViewport.fit(384, 240, 564, 384)
    check(near(small.scale, 0.625) && near(small.offsetX, 15.75) && near(small.offsetY, 0.0))
    val composing = preeditInputBounds(100, 50, 10, 30, 9, 564, 384)
    check(composing == GuiInputBounds(98, 48, 132, 75)) {
        "IME native hot area must include the input line and composition below it"
    }
    check(small.screenBounds(composing) == GuiInputBounds(77, 30, 99, 47)) {
        "IME bounds must use the fitted panel transform and outward rounding"
    }
    check(preeditInputBounds(540, 370, 10, 80, 9, 564, 384) == GuiInputBounds(482, 346, 566, 382)) {
        "IME edge clamps must use the logical viewport, including composition above the input"
    }
    val longComposition = preeditInputBounds(156, 27, 10, 600, 9, 564, 384)
    check(longComposition == GuiInputBounds(-38, 25, 566, 52)) {
        "Long composition must preserve Minecraft's existing horizontal clamp"
    }
    val roomy = GuiViewport.fit(854, 480, 564, 384)
    check(roomy.scale == 1.0 && roomy.offsetX == 0.0 && roomy.offsetY == 0.0) {
        "Roomy viewports must preserve the existing layout without enlargement"
    }
    check(roomy.logicalWidth == 854 && roomy.logicalHeight == 480)
    check(roomy.screenBounds(composing) == composing) {
        "An unscaled panel must preserve native input-area coordinates"
    }
    val minimized = GuiViewport.fit(0, 0, 564, 384)
    check(minimized.scale.isFinite() && minimized.scale > 0 && minimized.logicalX(0.0).isFinite())
}
