package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import kotlin.math.abs

/**
 * Arrow Align protection based on Noamm's ArrowAlign solver.
 * It never automates clicks: when protection is enabled it only rejects a
 * click on an arrow frame after that frame has reached its required rotation.
 */
object ArrowAlignSolver {
    private val gridCorner = BlockPos(-2, 120, 75)
    private val frameRotations = IntArray(25) { -1 }
    private val clicksRemaining = IntArray(25) { 0 }
    private var solution: List<Int>? = null
    private val possibleSolutions = listOf(
        listOf(7,7,-1,-1,-1,1,-1,-1,-1,-1,1,3,3,3,3,-1,-1,-1,-1,1,-1,-1,-1,7,1),
        listOf(-1,-1,7,7,5,-1,7,1,-1,5,-1,-1,-1,-1,-1,-1,7,5,-1,1,-1,-1,7,7,1),
        listOf(7,7,-1,-1,-1,1,-1,-1,-1,-1,1,3,-1,7,5,-1,-1,-1,-1,5,-1,-1,-1,3,3),
        listOf(5,3,3,3,-1,5,-1,-1,-1,-1,7,7,-1,-1,-1,1,-1,-1,-1,-1,1,3,3,3,-1),
        listOf(5,3,3,3,3,5,-1,-1,-1,1,7,7,-1,-1,1,-1,-1,-1,-1,1,-1,7,7,7,1),
        listOf(7,7,7,7,-1,1,-1,-1,-1,-1,1,3,3,3,3,-1,-1,-1,-1,1,-1,7,7,7,1),
        listOf(-1,-1,-1,-1,-1,1,-1,1,-1,1,1,-1,1,-1,1,1,-1,1,-1,1,-1,-1,-1,-1,-1),
        listOf(-1,-1,-1,-1,-1,1,3,3,3,3,-1,-1,-1,-1,1,7,7,7,7,1,-1,-1,-1,-1,-1),
        listOf(-1,-1,-1,-1,-1,-1,1,-1,1,-1,7,1,7,1,3,1,-1,1,-1,1,-1,-1,-1,-1,-1)
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun tick() {
        if (!DungeonContext.inDungeon) { reset(); return }
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (player.distanceToSqr(gridCorner.x + .5, gridCorner.y + 2.5, gridCorner.z + 2.5) > 200.0) { reset(); return }

        for (i in 0 until 25) {
            val pos = BlockPos(gridCorner.x, gridCorner.y + (i % 5), gridCorner.z + (i / 5))
            val frame = level.getEntitiesOfClass(ItemFrame::class.java, net.minecraft.world.phys.AABB(pos)).firstOrNull { it.item.`is`(Items.ARROW) }
            if (frame != null) frameRotations[i] = frame.rotation
        }

        solution = possibleSolutions.firstOrNull { arr ->
            (0 until 25).all { i ->
                val target = arr[i]
                val current = frameRotations[i]
                // Same matching rule as Odin: an unknown frame or unknown
                // solution slot is acceptable, but two known values must agree.
                (target == -1 || current == -1) || target == current
            }
        }
        val sol = solution ?: return
        for (i in 0 until 25) clicksRemaining[i] = if (frameRotations[i] < 0) 0 else getClicks(frameRotations[i], sol[i])
    }

    /** Return true when a right-click on this arrow frame should be rejected. */
    fun shouldBlockClick(frame: ItemFrame): Boolean {
        if (!Config.blockWrongDeviceClicks || !DungeonContext.inDungeon || !frame.item.`is`(Items.ARROW)) return false
        val pos = frame.blockPosition()
        if (pos.x != gridCorner.x) return false
        val index = (pos.y - gridCorner.y) + (pos.z - gridCorner.z) * 5
        if (index !in 0..24) return false
        if (Minecraft.getInstance().player?.isCrouching == true) return false
        return clicksRemaining[index] <= 0
    }

    fun onAllowedClick(frame: ItemFrame) {
        val pos = frame.blockPosition()
        if (pos.x != gridCorner.x) return
        val index = (pos.y - gridCorner.y) + (pos.z - gridCorner.z) * 5
        if (index !in 0..24) return
        frameRotations[index] = (frameRotations[index] + 1) % 8
        solution?.getOrNull(index)?.let { clicksRemaining[index] = getClicks(frameRotations[index], it) }
    }

    /** Return true when the puzzle is solved or marked completed by a world hologram. */
    fun isSolved(): Boolean {
        if (solution != null && clicksRemaining.all { it == 0 }) return true
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val stands = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand::class.java, net.minecraft.world.phys.AABB(gridCorner).inflate(6.0))
        for (stand in stands) {
            val name = stand.customName?.string ?: continue
            if (name.contains("Completed", ignoreCase = true)) return true
        }
        return false
    }

    private fun getClicks(current: Int, target: Int): Int = if (target < 0) 0 else (8 - current + target) % 8

    private fun reset() {
        java.util.Arrays.fill(frameRotations, -1)
        java.util.Arrays.fill(clicksRemaining, 0)
        solution = null
    }
}
