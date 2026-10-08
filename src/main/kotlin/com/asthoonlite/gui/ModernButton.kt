package com.asthoonlite.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Modern styled button widget with dark slate background, customizable hover accent,
 * dynamic luminous glow effect on cursor hover, and crisp typography.
 */
class ModernButton(
    x: Int, y: Int, w: Int, h: Int,
    text: Component,
    var customAccent: Int? = null,
    private val onPress: () -> Unit
) : AbstractWidget(x, y, w, h, text) {

    constructor(x: Int, y: Int, w: Int, h: Int, text: Component, onPress: () -> Unit) :
        this(x, y, w, h, text, null, onPress)

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
    }

    override fun extractWidgetRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!visible) return
        val hovered = isHoveredOrFocused
        val accent = customAccent ?: 0xFF38BDF8.toInt()
        val bgCol = if (hovered) 0xFF1F2E45.toInt() else 0xFF141E2D.toInt()
        val borderCol = if (hovered) accent else 0xFF24354A.toInt()
        val textCol = if (hovered) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt()

        // Luminous ambient outer glow on hover
        if (hovered) {
            val glowColor1 = (accent and 0x00FFFFFF) or 0x40000000 // 25% alpha glow
            val glowColor2 = (accent and 0x00FFFFFF) or 0x20000000 // 12% alpha outer aura
            context.fill(x - 2, y - 2, x + width + 2, y + height + 2, glowColor2)
            context.fill(x - 1, y - 1, x + width + 1, y + height + 1, glowColor1)
        }

        // Background
        context.fill(x, y, x + width, y + height, bgCol)
        // 1px border
        context.fill(x, y, x + width, y + 1, borderCol)
        context.fill(x, y + height - 1, x + width, y + height, borderCol)
        context.fill(x, y, x + 1, y + height, borderCol)
        context.fill(x + width - 1, y, x + width, y + height, borderCol)

        // Centered text
        val font = Minecraft.getInstance().font
        val textW = font.width(message)
        val textX = x + (width - textW) / 2
        val textY = y + (height - 8) / 2
        context.text(font, message.string, textX, textY, textCol)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
