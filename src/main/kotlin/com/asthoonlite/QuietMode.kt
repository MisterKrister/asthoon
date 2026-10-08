package com.asthoonlite

import com.asthoonlite.config.Config
import com.asthoonlite.gui.AsthoonLiteScreen
import com.asthoonlite.pet.PetHudEditorScreen
import com.asthoonlite.dungeon.DungeonMapEditorScreen
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

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
 * Two things are deliberately not gated, because the bar is "an ordinary
 * modded client", not "an unmodded one":
 *
 *  - the drawn terminal pointer. Every accepted Skyblock mod highlights,
 *    chimes and answers out loud; none of that reads as odd. A pointer that
 *    travels to a pane and clicks it is the picture of a hand doing the
 *    work, and the real cursor is swapped out for it — see
 *    `dungeon/TerminalCursor`.
 *  - the solver chat lines, which are what every other mod prints too.
 *
 * The external dungeon map follows the same flag and hides while suppressed.
 *
 * That leaves the settings screens, which cannot gate on [suppressing]
 * (a settings menu that draws nothing is useless). They are closed on the
 * rising edge of the flag instead — see [dismissModScreens].
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

    /** Last tick's flag, so a screen is closed once on the way in rather
     *  than every tick — which would make the menu impossible to reach. */
    private var wasSuppressing = false

    fun register() {
        wasSuppressing = Config.quietModeEnabled
        ClientTickEvents.END_CLIENT_TICK.register {
            handleKeybind()
            detectEngage()
        }
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

    /**
     * Detects quiet mode coming on, and clears the screen when it does.
     *
     * Only on the rising edge. Closing every tick would also fire on the
     * tick after a deliberate `/asl`, which would make the menu unreachable
     * while quiet mode is on and, with no keybind bound, leave the settings
     * stranded behind a flag with no way to clear it. One shot on the way in
     * covers the real accident — toggling quiet mode while the menu is open
     * and leaving a wall of settings on screen — and the menu still opens on
     * purpose afterwards, from where it can be used to switch back.
     */
    private fun detectEngage() {
        val now = Config.quietModeEnabled
        if (now && !wasSuppressing) dismissModScreens()
        wasSuppressing = now
    }

    /**
     * Closes this mod's own screens through their own `onClose()`, not with a
     * bare `setScreen(null)`.
     *
     * The difference matters: `setScreen` calls `removed()`, which runs no
     * save, so anything a slider had written without its own `save()` — the
     * reason `AsthoonLiteScreen.onClose` exists at all — would be dropped at
     * exactly the moment the menu disappears. Going through `onClose()` is
     * the same path the Esc key takes and keeps the write.
     */
    private fun dismissModScreens() {
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen is AsthoonLiteScreen || screen is PetHudEditorScreen || screen is DungeonMapEditorScreen) screen.onClose()
    }

    /** The single way the flag changes, so every path logs the same way.
     *  [AsthoonLiteScreen]'s toggle row calls this too rather than writing
     *  Config directly. */
    internal fun setEnabled(on: Boolean) {
        Config.quietModeEnabled = on
        // Deliberately not an overlay message. Anything printed here lands in
        // the frame, and the whole point is that nothing on screen says this
        // is on — a toggle that announced itself would be the one line of the
        // capture that gives the game away. Feedback goes to the log instead,
        // which is not part of the window.
        AsthoonLite.LOGGER.info("[AsthoonLite] Quiet mode {}", if (on) "ON" else "OFF")
    }
}
