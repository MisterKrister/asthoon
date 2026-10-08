package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * Secret aura: picks the nearest secret-like block inside the configured
 * range/FOV and interacts with it. Chests/buttons/levers/skulls (Wither Essence)
 * use right click; mushrooms can be broken when the optional break toggle is enabled.
 * Tracks clicked positions with a cooldown to prevent spamming the same secret.
 */
object SecretAura {
    private var target: BlockPos? = null
    private var lastAction = 0L
    val clickedPositions = ConcurrentHashMap<BlockPos, Long>()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { render() }
    }

    private fun tick() {
        if (!Config.secretAuraEnabled || !DungeonContext.inDungeon) { target = null; return }
        val mc = Minecraft.getInstance(); val level = mc.level ?: return; val player = mc.player ?: return
        if (mc.screen != null) return

        val now = System.currentTimeMillis()
        clickedPositions.entries.removeIf { now - it.value > 4000L }

        val max = Config.secretAuraRange.toDouble()
        val eye = player.eyePosition
        val look = player.lookAngle
        var best: Pair<BlockPos, Double>? = null
        val r = ceil(max).toInt()
        for (x in (player.x - r).toInt()..(player.x + r).toInt()) {
            for (y in (player.y - r).toInt()..(player.y + r).toInt()) {
                for (z in (player.z - r).toInt()..(player.z + r).toInt()) {
                    val pos = BlockPos(x, y, z)
                    if (clickedPositions.containsKey(pos)) continue

                    val state = level.getBlockState(pos)
                    if (!isSecretBlock(state.block)) continue

                    // Don't re-click already powered levers or buttons
                    if (state.block is LeverBlock && state.getValue(LeverBlock.POWERED)) continue
                    if (state.block is ButtonBlock && state.getValue(ButtonBlock.POWERED)) continue

                    val center = Vec3.atCenterOf(pos)
                    val distance = eye.distanceTo(center)
                    if (distance > max) continue
                    val to = center.subtract(eye)
                    if (to.lengthSqr() <= 1.0e-6) continue
                    val dot = look.dot(to.normalize()).coerceIn(-1.0, 1.0)
                    val angle = Math.toDegrees(acos(dot))
                    if (angle > Config.secretAuraFov / 2.0) continue

                    if (!Config.secretAuraThroughWalls) {
                        val clip = level.clip(
                            net.minecraft.world.level.ClipContext(
                                eye,
                                center,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                player
                            )
                        )
                        if (clip.type == net.minecraft.world.phys.HitResult.Type.BLOCK && clip.blockPos != pos) {
                            continue
                        }
                    }

                    val currentBest = best
                    if (currentBest == null || angle < currentBest.second) best = pos to angle
                }
            }
        }
        target = best?.first
        val pos = target ?: return
        if (System.currentTimeMillis() - lastAction < 100L) return
        val center = Vec3.atCenterOf(pos)
        val d = eye.subtract(center)
        val dir = Direction.getNearest((d.x * 10).toInt(), (d.y * 10).toInt(), (d.z * 10).toInt(), Direction.UP) ?: Direction.UP
        val hit = BlockHitResult(center, dir, pos, false)
        val block = level.getBlockState(pos).block
        if (isRightClickSecret(block)) {
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            clickedPositions[pos] = System.currentTimeMillis()
        } else if (Config.secretAuraBreakBlocks) {
            mc.gameMode?.destroyBlock(pos)
            clickedPositions[pos] = System.currentTimeMillis()
            SecretSounds.onSecretInteract(pos, block)
        }
        lastAction = System.currentTimeMillis()
    }

    /** Immediate single-secret interaction used by the Funny > I1 feature. */
    fun instantInteract(pos: BlockPos) {
        if (!DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val state = level.getBlockState(pos)
        if (!isSecretBlock(state.block)) return
        val center = Vec3.atCenterOf(pos)
        val d = player.eyePosition.subtract(center)
        val dir = Direction.getNearest((d.x * 10).toInt(), (d.y * 10).toInt(), (d.z * 10).toInt(), Direction.UP) ?: Direction.UP
        val hit = BlockHitResult(center, dir, pos, false)
        if (isRightClickSecret(state.block)) {
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            clickedPositions[pos] = System.currentTimeMillis()
        } else if (Config.secretAuraBreakBlocks) {
            mc.gameMode?.destroyBlock(pos)
            clickedPositions[pos] = System.currentTimeMillis()
            SecretSounds.onSecretInteract(pos, state.block)
        }
    }

    /**
     * I1 uses the exact same range/FOV target selection and interaction path as
     * Secret Aura, but limits the candidate set to the Simon Says device.
     * The camera is moved onto the selected button before the interaction so
     * I1 behaves like the requested "look + click" version of Secret Aura.
     */
    fun tickI1Targets(candidates: List<BlockPos>) {
        if (!DungeonContext.inDungeon || candidates.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (mc.screen != null) return

        val max = 7.0
        val eye = player.eyePosition
        val look = player.lookAngle
        var best: Pair<BlockPos, Double>? = null
        for (pos in candidates) {
            val state = level.getBlockState(pos)
            if (!isSecretBlock(state.block)) continue
            val center = Vec3.atCenterOf(pos)
            val distance = eye.distanceTo(center)
            if (distance > max) continue
            val to = center.subtract(eye)
            if (to.lengthSqr() <= 1.0e-6) continue
            val angle = Math.toDegrees(acos(look.dot(to.normalize()).coerceIn(-1.0, 1.0)))
            // I1 uses a fixed 7 block range and a 360° FOV.
            val currentBest = best
            if (currentBest == null || angle < currentBest.second) best = pos to angle
        }
        val pos = best?.first ?: return
        val center = Vec3.atCenterOf(pos)
        val d = center.subtract(eye)
        val horizontal = sqrt(d.x * d.x + d.z * d.z)
        player.yRot = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        player.xRot = Math.toDegrees(atan2(-d.y, horizontal)).toFloat()
        instantInteract(pos)
    }

    /**
     * Simon Says uses the exact same interaction primitive as Secret Aura:
     * choose a candidate with the same FOV/range logic and call gameMode.useItemOn
     * with a BlockHitResult. No camera movement is required.
     */
    fun tickSimonLike(start: BlockPos, buttons: List<BlockPos>, started: Boolean): Boolean {
        if (!DungeonContext.inDungeon) return false
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        if (mc.screen != null) return false
        if (System.currentTimeMillis() - lastAction < 100L) return false

        val candidates = if (!started) listOf(start) else buttons
        val max = Config.secretAuraRange.toDouble().coerceAtLeast(7.0)
        val eye = player.eyePosition
        val look = player.lookAngle
        var best: Pair<BlockPos, Double>? = null

        // Keep Secret Aura's target policy: nearest angular secret inside the
        // configured range/FOV. Here the candidate set is restricted to Simon.
        for (pos in candidates) {
            val block = level.getBlockState(pos).block
            if (!isRightClickSecret(block)) continue
            val center = Vec3.atCenterOf(pos)
            if (eye.distanceTo(center) > max) continue
            val to = center.subtract(eye)
            if (to.lengthSqr() <= 1e-6) continue
            val angle = Math.toDegrees(acos(look.dot(to.normalize()).coerceIn(-1.0, 1.0)))
            if (angle > Config.secretAuraFov / 2.0) continue
            val currentBest = best
            if (currentBest == null || angle < currentBest.second) best = pos to angle
        }

        val pos = best?.first ?: return false
        instantInteract(pos)
        lastAction = System.currentTimeMillis()
        return true
    }

    private fun render() {
        if (!DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        if (Config.secretAuraEnabled && Config.secretAuraVisualizer) {
            renderFovVisualizer(player)
        }

        if (!Config.secretAuraEnabled) return
        val pos = target ?: return
        WorldBoxRenderer.queueOutline(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0, 0.2f, 0.9f, 1f, 1f, throughWalls = true)
        WorldBoxRenderer.queueFilled(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0, 0.2f, 0.9f, 1f, 0.08f, throughWalls = true)
    }

    private fun renderFovVisualizer(player: net.minecraft.world.entity.player.Player) {
        val radius = Config.secretAuraRange.toDouble()
        val fov = Config.secretAuraFov.toDouble()
        val halfFov = fov / 2.0
        val y = player.y + 0.05
        val segments = 64
        val lookYaw = player.yRot.toDouble()

        val lookDirX = -sin(Math.toRadians(lookYaw))
        val lookDirZ = cos(Math.toRadians(lookYaw))

        for (i in 0 until segments) {
            val a1 = (2.0 * Math.PI * i) / segments
            val a2 = (2.0 * Math.PI * (i + 1)) / segments

            val x1 = player.x - sin(a1) * radius
            val z1 = player.z + cos(a1) * radius
            val x2 = player.x - sin(a2) * radius
            val z2 = player.z + cos(a2) * radius

            val midA = (a1 + a2) / 2.0
            val dirX = -sin(midA)
            val dirZ = cos(midA)
            val dot = (dirX * lookDirX + dirZ * lookDirZ).coerceIn(-1.0, 1.0)
            val angleFromLook = Math.toDegrees(acos(dot))

            if (angleFromLook <= halfFov) {
                WorldBoxRenderer.queueLine(x1, y, z1, x2, y, z2, 0.2f, 0.9f, 1.0f, 0.85f, thickness = 0.035, throughWalls = true)
            } else {
                WorldBoxRenderer.queueLine(x1, y, z1, x2, y, z2, 0.2f, 0.9f, 1.0f, 0.20f, thickness = 0.02, throughWalls = true)
            }
        }

        if (fov < 180.0) {
            val leftRad = Math.toRadians(lookYaw - halfFov)
            val lx = player.x - sin(leftRad) * radius
            val lz = player.z + cos(leftRad) * radius
            WorldBoxRenderer.queueLine(player.x, y, player.z, lx, y, lz, 0.2f, 0.9f, 1.0f, 0.6f, thickness = 0.03, throughWalls = true)

            val rightRad = Math.toRadians(lookYaw + halfFov)
            val rx = player.x - sin(rightRad) * radius
            val rz = player.z + cos(rightRad) * radius
            WorldBoxRenderer.queueLine(player.x, y, player.z, rx, y, rz, 0.2f, 0.9f, 1.0f, 0.6f, thickness = 0.03, throughWalls = true)
        }
    }

    fun isRightClickSecret(block: net.minecraft.world.level.block.Block): Boolean =
        block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block is ButtonBlock || block is LeverBlock ||
            block == Blocks.WITHER_SKELETON_SKULL || block == Blocks.WITHER_SKELETON_WALL_SKULL

    fun isSecretBlock(block: net.minecraft.world.level.block.Block): Boolean =
        isRightClickSecret(block) || block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM ||
            block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK

    fun resetRun() {
        target = null
        clickedPositions.clear()
        lastAction = 0L
    }
}
