package com.asthoonlite

import com.asthoonlite.config.Config
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Session gate. While it is on, the mod puts no pixels into the game window.
 *
 * Every draw site checks [suppressing] before it queues or renders:
 * world-space boxes, world-space text, the HUD elements, the selection
 * outline override and the inventory slot highlights. The features keep
 * *running* — solvers still solve, the enlarged hitbox is still clickable,
 * timers still count, auras still pick their target. Only the output stops
 * reaching the frame.
 *
 * That is what makes a window capture of the game window look vanilla.
 * Anything the player wants to keep on screen while capturing belongs in the
 * separate overlay window instead (`com.asthoonlite.overlay`), which a
 * window capture never sees, because it is a different window.
 *
 * The state lives in Config so it survives a restart. Leaving it on is the
 * safe direction to fail: the worst case is a session with no overlays, never
 * a session with overlays that should have been off.
 */
object QuietMode {

    /** True when the game window must look like vanilla this frame. */
    @JvmStatic
    fun suppressing(): Boolean = Config.quietModeEnabled

    private var previousKeyDown = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { handleKeybind() }
    }

    private fun handleKeybind() {
        val mc = Minecraft.getInstance()
        val key = Config.quietModeKey
        if (key < 0) {
            previousKeyDown = false
            return
        }

        val down = InputConstants.isKeyDown(mc.window, key)
        if (down && !previousKeyDown && mc.screen == null) {
            setEnabled(!Config.quietModeEnabled)
        }
        previousKeyDown = down
    }

    private fun setEnabled(on: Boolean) {
        Config.quietModeEnabled = on
        Minecraft.getInstance().player?.sendOverlayMessage(
            Component.literal(
                if (on) "§7[ASL] §fQuiet mode §aON §7- overlays hidden"
                else "§7[ASL] §fQuiet mode §cOFF §7- overlays visible"
            )
        )
    }
}
