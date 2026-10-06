package com.asthoonlite.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component

/**
 * Modern integer-range slider (min..max) with dark card styling,
 * cyan progress fill, and dynamic value badge.
 */
class IntSlider(
    x: Int, y: Int, w: Int, h: Int,
    private val min: Int,
    private val max: Int,
    initial: Int,
    private val labelPrefix: String,
    private val labelSuffix: String = "",
    private val onChange: (Int) -> Unit
) : AbstractSliderButton(
    x, y, w, h,
    Component.literal("$labelPrefix$initial$labelSuffix"),
    ((initial - min).toDouble() / (max - min).toDouble()).coerceIn(0.0, 1.0)
) {

    var intValue: Int = initial
        private set

    override fun updateMessage() {
        message = Component.literal("$labelPrefix$intValue$labelSuffix")
    }

    override fun applyValue() {
        intValue = (min + value * (max - min)).toInt().coerceIn(min, max)
        onChange(intValue)
    }

    override fun extractWidgetRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!visible) return
        drawSliderCard(
            context, x, y, width, height,
            hovered = isHoveredOrFocused,
            label = labelPrefix.trimEnd(':', ' '),
            valueText = "$intValue$labelSuffix",
            fraction = value
        )
    }
}

/**
 * The card every slider in the settings screen is drawn as: label left,
 * value badge right, progress track along the bottom.
 *
 * Top-level rather than a protected member of [IntSlider] so [FloatSlider]
 * can be the same widget with a different number in it. Two copies of this
 * drawing would be two places for the styling to drift apart, and a slider
 * row that renders a pixel differently from its neighbour is the kind of
 * thing nobody reports and everybody notices.
 */
internal fun drawSliderCard(
    context: GuiGraphicsExtractor,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    hovered: Boolean,
    label: String,
    valueText: String,
    fraction: Double
) {
    val font = Minecraft.getInstance().font

    val bgCol = if (hovered) 0xFF182638.toInt() else 0xFF111B29.toInt()
    val borderCol = if (hovered) 0xFF38BDF8.toInt() else 0xFF22344A.toInt()

    // Background card
    context.fill(x, y, x + width, y + height, bgCol)
    // 1px border
    context.fill(x, y, x + width, y + 1, borderCol)
    context.fill(x, y + height - 1, x + width, y + height, borderCol)
    context.fill(x, y, x + 1, y + height, borderCol)
    context.fill(x + width - 1, y, x + width, y + height, borderCol)

    // Label on left
    context.text(font, label, x + 8, y + 4, 0xFFE2E8F0.toInt())

    // Value badge on right
    val valW = font.width(valueText)
    context.text(font, valueText, x + width - valW - 8, y + 4, 0xFF38BDF8.toInt())

    // Track bar
    val trackX = x + 8
    val trackY = y + height - 6
    val trackW = width - 16
    val trackH = 3

    // Unfilled track
    context.fill(trackX, trackY, trackX + trackW, trackY + trackH, 0xFF1E293B.toInt())
    // Filled track
    val filledW = (trackW * fraction.coerceIn(0.0, 1.0)).toInt()
    context.fill(trackX, trackY, trackX + filledW, trackY + trackH, 0xFF0284C7.toInt())

    // Thumb
    val thumbX = (trackX + filledW).coerceIn(trackX, trackX + trackW)
    val thumbCol = if (hovered) 0xFF38BDF8.toInt() else 0xFFFFFFFF.toInt()
    context.fill(thumbX - 2, trackY - 2, thumbX + 2, trackY + trackH + 2, thumbCol)
}
