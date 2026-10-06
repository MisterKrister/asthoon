package com.asthoonlite.render

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.etherwarp.EtherwarpHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Highlights a valid Etherwarp landing spot with a two-tone box: a darker
 * navy-blue fill over the whole block, and a lighter (brighter) blue
 * outline around its edges, instead of the old flat single-color fill.
 *
 * Reads `Config.etherwarpEnabled` directly rather than caching a mirrored
 * `enabled` field — see Config.kt's class doc for why that mirror was the
 * root cause of the "toggle it off and back on to make it work" bug.
 *
 * Rendering itself is delegated to WorldBoxRenderer (see that file for the
 * custom-RenderPipeline plumbing, unchanged from the original 26.1.2 port).
 */
object EtherwarpOverlay {

    private const val RANGE = 60.0

    // AsthoonLite accent palette: darker navy fill, brighter accent-blue
    // outline (same "outline pops, fill stays subtle" language the pet HUD
    // and pickaxe timer panels already use).
    private const val OUTLINE_R = 0x55 / 255f
    private const val OUTLINE_G = 0xB6 / 255f
    private const val OUTLINE_B = 1f
    private const val OUTLINE_A = 1.0f

    @Volatile private var targetPos: BlockPos? = null

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register(::extract)
    }

    /** Called by a mixin into GameRenderer#close — see MixinGameRenderer. */
    fun close() {
        WorldBoxRenderer.close()
    }

    private fun extract(context: LevelExtractionContext) {
        targetPos = null
        if (QuietMode.suppressing()) return
        if (!Config.etherwarpEnabled) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!player.isCrouching) return

        val heldItem = player.mainHandItem
        if (heldItem.isEmpty) return
        val itemName = heldItem.hoverName?.string ?: ""
        if (!itemName.contains("Aspect of the Void", ignoreCase = true) &&
            !itemName.contains("Aspect of the End", ignoreCase = true)) return

        val feet = Vec3(player.x, player.y, player.z)
        val look = player.lookAngle
        val result = EtherwarpHelper.getEtherPos(feet, look, RANGE, sneaking = true)
        if (result.succeeded) {
            targetPos = result.pos
        }

        targetPos?.let { queueBox(it) }
    }

    private fun queueBox(pos: BlockPos) {
        // Expand the target slightly so the box stays visible instead of
        // z-fighting with the block faces. The outline is intentionally
        // outside the block mesh while the fill remains inside it.
        val pad = 0.035
        val x1 = pos.x.toDouble() - pad; val y1 = pos.y.toDouble() - pad; val z1 = pos.z.toDouble() - pad
        val x2 = pos.x.toDouble() + 1.0 + pad; val y2 = pos.y.toDouble() + 1.0 + pad; val z2 = pos.z.toDouble() + 1.0 + pad

        // Outline-only rendering avoids painting over the target block's own
        // mesh. The tiny outward expansion keeps the border from z-fighting
        // with the block face.
        WorldBoxRenderer.queueOutline(x1, y1, z1, x2, y2, z2, OUTLINE_R, OUTLINE_G, OUTLINE_B, OUTLINE_A, thickness = 0.035, throughWalls = true)
    }
}
