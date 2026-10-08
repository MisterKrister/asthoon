package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Autonomous Path Executor with Speed-Aware Movement & Realistic Camera Control:
 *
 * Traversal & Camera:
 * - Traverses node-by-node according to route presets.
 * - Simulates realistic human camera motion via damped angle interpolation and organic micro-sway/tremor,
 *   closely reproducing the camera movements captured in user runs.
 *
 * Bonzo Staff Mechanics:
 * - Reads player speed attribute ([Attributes.MOVEMENT_SPEED] * 1000).
 * - When at a [RouteNodeType.BONZO_STAFF] waypoint:
 *   - At > 400 speed (e.g. 550 speed), pauses holding W for 1-2 ticks while pitching down (~45°),
 *     fires the Bonzo Staff, then re-engages W + Jump to ride the explosive boost without momentum cancellation.
 *   - At <= 400 speed, traverses while pitching down without pausing W.
 *
 * Mobility Constraints:
 * - Strictly enforces M7 constraints (strictly no AOTV / Etherwarp).
 */
object PathExecutor {

    var isActive: Boolean = false
        private set

    var currentNodeIndex: Int = 0
        internal set

    var activePreset: PathPreset? = null
        private set

    private var tickCount: Long = 0L

    // Bonzo execution sub-state machine
    private enum class BonzoState {
        IDLE,
        PRE_FIRE_PAUSE,
        FIRE_CLICK,
        POST_FIRE_PROPEL
    }

    private var bonzoState = BonzoState.IDLE
    private var bonzoTicksRemaining = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stop() }
    }

    fun start(preset: PathPreset) {
        if (preset.points.isEmpty()) {
            Minecraft.getInstance().player?.sendSystemMessage(
                Component.literal("§c[AsthoonLite] Cannot start pathfinding: route has 0 nodes.")
            )
            return
        }

        activePreset = preset
        currentNodeIndex = 0
        isActive = true
        bonzoState = BonzoState.IDLE
        bonzoTicksRemaining = 0
        tickCount = 0L

        val player = Minecraft.getInstance().player
        player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStarted path execution: §e${preset.name} §7(${preset.points.size} nodes)")
        )
    }

    fun stop() {
        if (!isActive && activePreset == null) return
        isActive = false
        activePreset = null
        currentNodeIndex = 0
        bonzoState = BonzoState.IDLE
        bonzoTicksRemaining = 0

        // Safety: release all movement inputs
        releaseAllMovementKeys()
    }

    private fun releaseAllMovementKeys() {
        val mc = Minecraft.getInstance()
        try {
            mc.options.keyUp.setDown(false)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keyJump.setDown(false)
            mc.options.keySprint.setDown(false)
        } catch (_: Exception) {}
    }

    private fun tick() {
        if (!isActive) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { stop(); return }
        val level = mc.level ?: run { stop(); return }
        val preset = activePreset ?: run { stop(); return }

        // Stop if any full screen GUI (except chat) is opened or pathfinding toggled off
        if (mc.screen != null && mc.screen !is net.minecraft.client.gui.screens.ChatScreen) {
            stop()
            return
        }
        if (!Config.pathfindingEnabled) {
            stop()
            return
        }

        tickCount++

        val points = preset.points
        if (currentNodeIndex !in points.indices) {
            finishRoute(preset)
            return
        }

        val target = points[currentNodeIndex]
        val nodeType = target.nodeType()

        val dx = target.x - player.x
        val dy = target.y - (player.y + player.eyeHeight)
        val dz = target.z - player.z
        val distH = sqrt(dx * dx + dz * dz)
        val distY = Math.abs(target.y - player.y)

        // 1. Handle Bonzo's Staff special execution
        if (nodeType == RouteNodeType.BONZO_STAFF) {
            val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
            val skyblockSpeed = speedAttr * 1000.0 // e.g. 550 speed = 0.55 * 1000
            val highSpeedPause = skyblockSpeed > 400.0

            handleBonzoStep(target, dx, dz, distH, highSpeedPause)
            return
        }

        // 2. Aim camera toward target waypoint with realistic micro-sway
        val destYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
        val destPitch = (-Math.toDegrees(atan2(dy, distH))).toFloat().coerceIn(-89f, 89f)

        // Organic micro-sway matching recorded run data (simulates player hand tremors and head bob)
        val swayYaw = (sin(tickCount * 0.45) * 0.45 + sin(tickCount * 0.95) * 0.25).toFloat()
        val swayPitch = (cos(tickCount * 0.4) * 0.35).toFloat()

        val deltaYaw = Mth.wrapDegrees(destYaw + swayYaw - player.yRot)
        val deltaPitch = (destPitch + swayPitch - player.xRot)

        // Smooth human rotation interpolation
        player.yRot += deltaYaw * 0.38f
        player.xRot += deltaPitch * 0.38f

        // 3. Movement controls
        mc.options.keyUp.setDown(true)
        mc.options.keySprint.setDown(true)

        // Jump handling
        if (nodeType == RouteNodeType.JUMP || player.horizontalCollision || (target.y > player.y + 0.5 && distH < 2.0)) {
            mc.options.keyJump.setDown(true)
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false)
        }

        // 4. Interaction node handling
        if (nodeType == RouteNodeType.INTERACT && distH < 3.8 && distY < 3.0) {
            val hit = mc.hitResult
            if (hit != null && hit.type == HitResult.Type.BLOCK) {
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit as BlockHitResult)
            } else {
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            }
            currentNodeIndex++
            return
        }

        // 5. Waypoint arrival check
        if (distH < 0.95 && distY < 1.6) {
            currentNodeIndex++
            if (currentNodeIndex >= points.size) {
                finishRoute(preset)
            }
        }
    }

    private fun handleBonzoStep(
        target: PathPoint,
        dx: Double,
        dz: Double,
        distH: Double,
        highSpeedPause: Boolean
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Auto-switch to Bonzo's Staff if in hotbar
        selectBonzoStaff()

        // Direction to propel forward (Bonzo fires behind player or down-backwards)
        val destYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
        val deltaYaw = Mth.wrapDegrees(destYaw - player.yRot)
        player.yRot += deltaYaw * 0.45f

        // Look down ~45°-55° to get maximum propulsion arc
        val targetPitch = 48.0f
        player.xRot += (targetPitch - player.xRot) * 0.50f

        when (bonzoState) {
            BonzoState.IDLE -> {
                if (highSpeedPause) {
                    // Over 400 speed (e.g. 550 speed): stop holding W for 1-2 ticks
                    mc.options.keyUp.setDown(false)
                    bonzoState = BonzoState.PRE_FIRE_PAUSE
                    bonzoTicksRemaining = 2
                } else {
                    // Under 400 speed: keep traversing while pitching down
                    mc.options.keyUp.setDown(true)
                    bonzoState = BonzoState.FIRE_CLICK
                    bonzoTicksRemaining = 1
                }
            }

            BonzoState.PRE_FIRE_PAUSE -> {
                mc.options.keyUp.setDown(false)
                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.FIRE_CLICK
                    bonzoTicksRemaining = 1
                }
            }

            BonzoState.FIRE_CLICK -> {
                // Fire Bonzo Staff
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                bonzoState = BonzoState.POST_FIRE_PROPEL
                bonzoTicksRemaining = 2
            }

            BonzoState.POST_FIRE_PROPEL -> {
                // Immediately re-engage W and Jump to ride the explosive boost
                mc.options.keyUp.setDown(true)
                mc.options.keyJump.setDown(true)
                mc.options.keySprint.setDown(true)

                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.IDLE
                    currentNodeIndex++
                }
            }
        }
    }

    private fun selectBonzoStaff(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val inventory = player.inventory

        // Check if already holding Bonzo's Staff
        if (inventory.selectedItem.hoverName.string.contains("Bonzo", ignoreCase = true)) {
            return true
        }

        // Search hotbar slots 0..8
        for (slot in 0..8) {
            val item = inventory.getItem(slot)
            if (item.hoverName.string.contains("Bonzo", ignoreCase = true)) {
                inventory.selectedSlot = slot
                return true
            }
        }
        return false
    }

    private fun finishRoute(preset: PathPreset) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        stop()

        player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fRoute §e\"${preset.name}\" §fcompleted successfully!")
        )
        levelSound(SoundEvents.PLAYER_LEVELUP, 1.2f)
    }

    private fun levelSound(sound: net.minecraft.sounds.SoundEvent, pitch: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        level.playLocalSound(player.x, player.y, player.z, sound, SoundSource.PLAYERS, 0.8f, pitch, false)
    }
}
