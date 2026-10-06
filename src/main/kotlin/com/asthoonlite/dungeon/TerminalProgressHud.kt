package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * `In Terminal (Colors)` over `[7/15]`, centred near the top of the screen
 * while a terminal is open.
 *
 * This is NoammAddons' Fake InvWalk readout: the name of the terminal and how
 * far through it the clicker is, and nothing else — no pane pointed at, no
 * answer printed, no grid. It is the visual that goes with an auto clicker
 * because it describes the run rather than the solution: anyone watching the
 * screen sees a progress counter, which is the sort of thing a person puts on
 * their screen on purpose.
 *
 * The count comes from [AutoTerminal.progress], which latches the denominator
 * once when the terminal opens and counts the clicks sent since. It therefore
 * moves with the clicker and not with the server — a pane that resolves on its
 * own does not change it, and a click the player sends by hand does not count.
 * That is the honest reading of "how far through *this clicker* is".
 *
 * Registered from `AsthoonLite.onInitializeClient`, drawn through Fabric's HUD
 * element registry so it lands with the other HUDs and sits under Quiet Mode
 * like they do.
 */
object TerminalProgressHud : HudElement {

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "terminal_progress"), this
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.autoTerminalHudProgress || !Config.autoTerminalEnabled) return
        val progress = AutoTerminal.progress() ?: return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val centre = mc.window.guiScaledWidth / 2
        val name = "In Terminal (${progress.name})"
        val ratio = "[${progress.done}/${progress.goal}]"
        // A tenth of the screen down: clear of the grid, which is centred, and
        // clear of the title the custom GUI prints above that grid.
        val y = mc.window.guiScaledHeight / 10
        context.text(font, name, centre - font.width(name) / 2, y, 0xFF00AAAA.toInt())
        context.text(font, ratio, centre - font.width(ratio) / 2, y + font.lineHeight + 1, 0xFF55FFFF.toInt())
    }
}
