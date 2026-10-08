package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.overlay.MapOverlayWindow
import com.asthoonlite.render.HudMapCanvas
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** MouseButtonEvent signatures verified against Minecraft 26.1.2 GuiEventListener. */
class DungeonMapEditorScreen : Screen(Component.literal("Dungeon Map Editor")) {
    private var dragging = false
    private var grabX = 0.0
    private var grabY = 0.0

    override fun init() {
        Config.dungeonMapEditMode = true
        MapOverlayWindow.hide()
        val rect = DungeonMap.screenRect(width, height)
        Config.dungeonMapX = rect.x
        Config.dungeonMapY = rect.y
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }
            .bounds(width / 2 - 40, height - 26, 80, 20).build())
    }

    override fun isPauseScreen() = false

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        DungeonMap.preview(HudMapCanvas(context), width, height)
        context.centeredText(font, "Drag the map to move it. Esc or Done saves.", width / 2, 8, -1)
        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val rect = DungeonMap.screenRect(width, height)
        if (event.button() == 0 && rect.contains(event.x(), event.y())) {
            dragging = true
            grabX = event.x() - rect.x
            grabY = event.y() - rect.y
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (!dragging || event.button() != 0) return super.mouseDragged(event, dx, dy)
        val rect = DungeonMap.clampRect((event.x() - grabX).toInt(), (event.y() - grabY).toInt(),
            DungeonMap.screenRect(width, height).size, width, height)
        Config.dungeonMapX = rect.x
        Config.dungeonMapY = rect.y
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!dragging || event.button() != 0) return super.mouseReleased(event)
        dragging = false
        Config.save()
        return true
    }

    override fun removed() {
        Config.dungeonMapEditMode = false // Saves position even if another screen replaces the editor mid-drag.
    }

    override fun onClose() { minecraft.setScreen(null) }
}
