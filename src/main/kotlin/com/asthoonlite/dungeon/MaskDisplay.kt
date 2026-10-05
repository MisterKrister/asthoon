package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * Devonian-style mask HUD: each mask shows READY, its short immunity window,
 * or the remaining cooldown. The compact text layout keeps it readable
 * alongside AsthoonLite's other dungeon HUD elements.
 */
object MaskDisplay : HudElement {
    private data class Mask(val name: String, val immunityMs: Long, val cooldownMs: Long)
    private val masks = listOf(
        Mask("Bonzo", 3000, 180000),
        Mask("Spirit", 3000, 30000),
        Mask("Phoenix", 4000, 60000)
    )
    private val lastProc = LongArray(3) { -1L }
    private val cooldown = LongArray(3) { 0L }

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "mask_display"), this
        )
        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) {
                when (ChatFormatting.stripFormatting(text.string) ?: "") {
                    "Your Bonzo's Mask saved your life!" -> proc(0)
                    "Second Wind Activated! Your Spirit Mask saved your life!" -> proc(1)
                    "Your Phoenix Pet saved you from certain death!" -> proc(2)
                }
            }
            true
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun proc(i: Int) {
        lastProc[i] = System.currentTimeMillis()
        cooldown[i] = masks[i].cooldownMs
    }

    private fun reset() {
        for (i in lastProc.indices) {
            lastProc[i] = -1L
            cooldown[i] = 0L
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.maskDisplayEnabled || !DungeonContext.inDungeon) return
        val now = System.currentTimeMillis()
        val mc = Minecraft.getInstance()
        var y = Config.maskHudY

        for (i in masks.indices) {
            val mask = masks[i]
            val proc = lastProc[i]
            val text: String
            val color: Int
            if (proc < 0) {
                text = "${mask.name}: READY"
                color = 0xFF55FF55.toInt()
            } else {
                val elapsed = now - proc
                val immunityLeft = mask.immunityMs - elapsed
                val cooldownLeft = mask.cooldownMs - elapsed
                if (immunityLeft > 0) {
                    text = String.format("%s: %.2fs", mask.name, immunityLeft / 1000.0)
                    color = 0xFF55FFFF.toInt()
                } else if (cooldownLeft > 0) {
                    text = String.format("%s: %.2fs", mask.name, cooldownLeft / 1000.0)
                    color = when {
                        cooldownLeft >= mask.cooldownMs * .75 -> 0xFFFF5555.toInt()
                        cooldownLeft >= mask.cooldownMs * .5 -> 0xFFFFAA00.toInt()
                        cooldownLeft >= mask.cooldownMs * .25 -> 0xFFFFFF55.toInt()
                        else -> 0xFF55FF55.toInt()
                    }
                } else {
                    lastProc[i] = -1L
                    text = "${mask.name}: READY"
                    color = 0xFF55FF55.toInt()
                }
            }
            context.text(mc.font, text, Config.maskHudX, y, color)
            y += mc.font.lineHeight + 2
        }
    }
}
