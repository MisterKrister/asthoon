package com.asthoonlite.pet

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
 * HUD overlay that draws the currently active pet.
 *
 * Confirmed against real 26.1.2 Mojang-mapped sources / Fabric API jar via
 * javap: `HudRenderCallback` no longer exists — HUD elements are registered
 * through `HudElementRegistry` as `HudElement`s, and `GuiGraphics` was
 * renamed to `GuiGraphicsExtractor` with `text(...)` replacing `drawString`.
 */
object PetHudOverlay : HudElement {

    private const val PAD_X = 6
    private const val PAD_Y = 4
    private const val BAR_W = 3

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "pet_hud"),
            this
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        render(context)
    }

    fun render(context: GuiGraphicsExtractor) {
        if (!Config.petDisplayEnabled) return
        val pet = PetTracker.current ?: return

        val mc    = Minecraft.getInstance()
        val tr    = mc.font
        val scale = Config.petDisplayScale.coerceIn(0.5f, 3f)

        val label  = "\u2665 ${pet.display}"
        val textW  = tr.width(label)
        val textH  = tr.lineHeight

        val panelW = textW + PAD_X * 2 + BAR_W + 4
        val panelH = textH + PAD_Y * 2

        // GUI rendering uses a Matrix3x2fStack accessed via GuiGraphicsExtractor#pose().
        val pose = context.pose()
        pose.pushMatrix()
        pose.translate(Config.petDisplayX.toFloat(), Config.petDisplayY.toFloat())
        pose.scale(scale, scale)

        // Background
        context.fill(0, 0, panelW, panelH, 0xCC0D1F35.toInt())
        // Rarity accent bar
        val barColor = if (pet.rarityColor != 0) (pet.rarityColor or 0xFF000000.toInt()) else 0xFF1E90FF.toInt()
        context.fill(0, 0, BAR_W, panelH, barColor)
        // Border lines
        context.fill(0, 0, panelW, 1, 0x661E90FF.toInt())
        context.fill(0, panelH - 1, panelW, panelH, 0x661E90FF.toInt())

        // Text
        context.text(tr, label, BAR_W + PAD_X, PAD_Y, 0xFFE0EEFF.toInt())

        pose.popMatrix()
    }

    /** Width/height of the panel at scale=1 (used by editor for hit-testing). */
    fun panelSize(pet: PetTracker.ActivePet? = PetTracker.current): Pair<Int, Int> {
        val tr    = Minecraft.getInstance().font
        val label = "\u2665 ${pet?.display ?: "[Lvl 100] PetName"}"
        val w     = tr.width(label) + PAD_X * 2 + BAR_W + 4
        val h     = tr.lineHeight + PAD_Y * 2
        return w to h
    }
}
