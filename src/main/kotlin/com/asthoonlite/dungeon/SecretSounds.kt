package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret sounds ported from NoammAddons:
 * Plays SoundEvents.EXPERIENCE_ORB_PICKUP when a secret is clicked or collected:
 * - Chests & trapped chests
 * - Wither essence & redstone key skulls
 * - Levers
 * - Bats killed
 * - Floor secret items collected
 */
object SecretSounds {
    private val clicked = ConcurrentHashMap<BlockPos, Long>()
    private var lastItemSound = 0L

    fun onSecretInteract(pos: BlockPos, block: Block) {
        if (!Config.secretSoundEnabled || !DungeonContext.inDungeon || DungeonContext.inBoss) return
        if (clicked.containsKey(pos)) return
        if (isSecretBlock(block)) {
            clicked[pos] = System.currentTimeMillis()
            playSecretSound()
        }
    }

    fun onSecretItem() {
        if (!Config.secretSoundEnabled || !DungeonContext.inDungeon || DungeonContext.inBoss) return
        val now = System.currentTimeMillis()
        if (now - lastItemSound < 2000L) return
        lastItemSound = now
        playSecretSound()
    }

    fun onBatDeath(x: Double, y: Double, z: Double) {
        if (!Config.secretSoundEnabled || !DungeonContext.inDungeon || DungeonContext.inBoss) return
        val pos = BlockPos(x.toInt(), y.toInt(), z.toInt())
        if (clicked.containsKey(pos)) return
        clicked[pos] = System.currentTimeMillis()
        playSecretSound()
    }

    fun playSecretSound() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        level.playLocalSound(
            player.x, player.y, player.z,
            SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.PLAYERS,
            1.0f, 1.0f, false
        )
    }

    private fun isSecretBlock(block: Block): Boolean =
        block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.LEVER ||
            block == Blocks.WITHER_SKELETON_SKULL || block == Blocks.WITHER_SKELETON_WALL_SKULL

    fun tick() {
        val now = System.currentTimeMillis()
        clicked.entries.removeIf { now - it.value > 3000L }
    }

    fun reset() {
        clicked.clear()
        lastItemSound = 0L
    }
}
