package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret Triggerbot:
 * Automatically interacts with levers, buttons, skulls, chests, and secret blocks
 * when the player looks at them, matching Noamm and RSM triggerbot behavior.
 */
object SecretTriggerBot {

    private val lastClicked = ConcurrentHashMap<BlockPos, Long>()
    private var lastAction = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun tick() {
        if (!Config.secretTriggerBotEnabled || !DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (mc.screen != null) return

        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos

        val now = System.currentTimeMillis()
        if (now - lastAction < 75L) return

        // Interaction reach limit (vanilla block reach ~4.5 blocks)
        val eye = player.eyePosition
        if (eye.distanceTo(Vec3.atCenterOf(pos)) > 5.0) return

        // Cooldown cleanup
        lastClicked.entries.removeIf { now - it.value > 3000L }
        if (now - (lastClicked[pos] ?: 0L) < 400L) return

        val state = level.getBlockState(pos)
        val block = state.block

        // Only levers, buttons, or recognized secret blocks
        val isSecret = SecretAura.isSecretBlock(block) || block is LeverBlock || block is ButtonBlock
        if (!isSecret) return

        // Don't click already-powered levers or buttons
        if (block is LeverBlock && state.getValue(LeverBlock.POWERED)) return
        if (block is ButtonBlock && state.getValue(ButtonBlock.POWERED)) return

        if (SecretAura.isRightClickSecret(block)) {
            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            lastClicked[pos] = now
            lastAction = now
            SecretSounds.onSecretInteract(pos, block)
        } else if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM ||
            block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK) {
            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.destroyBlock(pos)
            lastClicked[pos] = now
            lastAction = now
            SecretSounds.onSecretInteract(pos, block)
        }
    }

    fun reset() {
        lastClicked.clear()
        lastAction = 0L
    }
}
