package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Autonomous Path Executor with Speed-Aware Movement & Fluid Human-like Camera Control:
 *
 * Traversal & Fluid Continuity:
 * - Begins pathfinding from player's current location directly towards node #1 (index 0).
 * - Traverses node-by-node without halting between consecutive walk waypoints, utilizing
 *   speed-scaled lookahead and corner-rounding to preserve sprinting momentum.
 * - Simulates authentic human camera steering with damped angle interpolation and
 *   organic micro-sway/tremor derived from recorded high-speed runs.
 *
 * Bonzo Staff Mechanics:
 * - A Bonzo Staff waypoint acts as an explosive propulsion trigger zone.
 * - When entering the trigger zone of a Bonzo waypoint, it immediately launches towards
 *   the subsequent waypoint:
 *   - At > 400 speed (e.g. 550 speed), pauses forward (W) for 1 tick while pitching down (~48°),
 *     fires the Bonzo Staff, and instantly re-engages forward + jump.
 *   - At <= 400 speed, maintains forward movement while pitching down and firing.
 *   - Advances target to the next walk waypoint immediately, seamlessly flying forward.
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
    private var bonzoTargetNextIndex = 0

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
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { if (isActive) stop(); return }
        val level = mc.level ?: run { if (isActive) stop(); return }

        // Stop if player died
        if (player.isDeadOrDying) {
            if (isActive) {
                stop()
                Config.activePathfindingPresetId = ""
            }
            return
        }

        // Emergency stop if user opened PauseScreen (ESC in world)
        if (mc.screen is net.minecraft.client.gui.screens.PauseScreen) {
            if (isActive) {
                stop()
                Config.activePathfindingPresetId = ""
                player.sendSystemMessage(
                    Component.literal("§e[AsthoonLite] §fPath execution §cSTOPPED§f.")
                )
            }
            return
        }

        // Release movement keys while any other screen/menu is open (e.g. settings screen, container)
        if (mc.screen != null && mc.screen !is net.minecraft.client.gui.screens.ChatScreen) {
            releaseAllMovementKeys()
            return
        }

        // Check if an active route preset is configured in Config
        val activePresetId = Config.activePathfindingPresetId
        if (Config.pathfindingEnabled && activePresetId.isNotBlank()) {
            val preset = PathPresetManager.getPresetById(activePresetId)
            if (preset != null && preset.points.isNotEmpty()) {
                if (!isActive || activePreset?.id != preset.id) {
                    start(preset)
                }
            } else {
                if (isActive) {
                    stop()
                    Config.activePathfindingPresetId = ""
                }
                return
            }
        } else if (RouteEditor.activePreset == null && !isActive) {
            return
        } else if (Config.activePathfindingPresetId.isBlank() && isActive && RouteEditor.activePreset == null) {
            stop()
            return
        }

        if (!isActive) return
        val preset = activePreset ?: run { stop(); return }
        if (!Config.pathfindingEnabled && RouteEditor.activePreset == null) {
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
        val nextTarget = points.getOrNull(currentNodeIndex + 1)

        val dx = target.x - player.x
        val dy = target.y - (player.y + player.eyeHeight)
        val dz = target.z - player.z
        val distH = sqrt(dx * dx + dz * dz)
        val distY = Math.abs(target.y - player.y)

        // Read player speed (Hypixel Skyblock speed = attribute * 1000)
        val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
        val skyblockSpeed = speedAttr * 1000.0
        val isHighSpeed = skyblockSpeed > 400.0

        // 1. Handle Active Bonzo Staff Execution State Machine
        if (bonzoState != BonzoState.IDLE) {
            handleActiveBonzoState(points, isHighSpeed)
            return
        }

        // 2. Handle approaching a Bonzo Staff node
        if (nodeType == RouteNodeType.BONZO_STAFF) {
            // Trigger Bonzo launch when inside trigger zone (~2.0 blocks) or if already close
            if (distH <= 2.2 && distY <= 2.5) {
                initiateBonzoLaunch(points, isHighSpeed)
                return
            }
            // Otherwise, sprint towards the Bonzo trigger zone
        }

        // 3. Waypoint Lookahead & Corner-Rounding
        // When approaching a walk waypoint, smoothly blend aim towards the next waypoint
        // so the player rounds corners naturally without deceleration.
        val lookaheadBlend = if (distH < 2.8 && nextTarget != null && nodeType == RouteNodeType.WALK) {
            ((2.8 - distH) / 2.8 * 0.40).coerceIn(0.0, 0.40)
        } else {
            0.0
        }

        val aimX = if (lookaheadBlend > 0.0 && nextTarget != null) {
            target.x * (1.0 - lookaheadBlend) + nextTarget.x * lookaheadBlend
        } else {
            target.x
        }
        val aimZ = if (lookaheadBlend > 0.0 && nextTarget != null) {
            target.z * (1.0 - lookaheadBlend) + nextTarget.z * lookaheadBlend
        } else {
            target.z
        }

        val aimDx = aimX - player.x
        val aimDz = aimZ - player.z
        val aimDistH = sqrt(aimDx * aimDx + aimDz * aimDz)

        val destYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
        val destPitch = (-Math.toDegrees(atan2(dy, aimDistH))).toFloat().coerceIn(-89f, 89f)

        // Organic micro-sway matching recorded run data (simulates player hand tremors and head bob)
        val swayYaw = (sin(tickCount * 0.45) * 0.35 + sin(tickCount * 0.95) * 0.18).toFloat()
        val swayPitch = (cos(tickCount * 0.40) * 0.22).toFloat()

        val deltaYaw = Mth.wrapDegrees(destYaw + swayYaw - player.yRot)
        val deltaPitch = (destPitch + swayPitch - player.xRot)

        // Smooth human rotation interpolation with realistic turn-rate damping
        val maxTurnRate = 32.0f
        val turnStep = (deltaYaw * 0.42f).coerceIn(-maxTurnRate, maxTurnRate)
        player.yRot += turnStep
        player.xRot += deltaPitch * 0.42f

        // 4. Movement Controls: Continuous Forward Sprinting
        mc.options.keyUp.setDown(true)
        mc.options.keySprint.setDown(true)

        // Jump handling: auto-jump on elevation step-up, obstacle collision, or Jump nodes
        if (nodeType == RouteNodeType.JUMP || player.horizontalCollision || (target.y > player.y + 0.35 && distH < 2.5)) {
            mc.options.keyJump.setDown(true)
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false)
        }

        // 5. Interaction Node Handling
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

        // 6. Fluid Waypoint Transition: Speed-Scaled Arrival Check
        // At high speeds (~16-25 bps), arrival threshold is larger to prevent overshooting or stutter
        val arrivalThreshold = if (isHighSpeed) 2.2 else 1.2
        if (distH < arrivalThreshold && distY < 2.2) {
            // Immediately advance to next waypoint without dropping forward key
            currentNodeIndex++
            if (currentNodeIndex >= points.size) {
                finishRoute(preset)
            }
        }
    }

    private fun initiateBonzoLaunch(points: List<PathPoint>, isHighSpeed: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Auto-select Bonzo's Staff
        selectBonzoStaff()

        // Launch towards the next waypoint in the route
        val nextIdx = currentNodeIndex + 1
        bonzoTargetNextIndex = nextIdx
        val destination = points.getOrNull(nextIdx) ?: points[currentNodeIndex]

        val launchDx = destination.x - player.x
        val launchDz = destination.z - player.z
        val launchYaw = (-Math.toDegrees(atan2(launchDx, launchDz))).toFloat()
        val deltaYaw = Mth.wrapDegrees(launchYaw - player.yRot)
        player.yRot += deltaYaw * 0.60f

        // Flick pitch down ~48° to catch explosive propulsion
        val targetPitch = 48.0f
        player.xRot += (targetPitch - player.xRot) * 0.65f

        if (isHighSpeed) {
            // Over 400 speed (e.g. 550 speed): pause W for 1-2 ticks
            mc.options.keyUp.setDown(false)
            bonzoState = BonzoState.PRE_FIRE_PAUSE
            bonzoTicksRemaining = 2
        } else {
            // Under 400 speed: fire immediately while moving forward
            mc.options.keyUp.setDown(true)
            bonzoState = BonzoState.FIRE_CLICK
            bonzoTicksRemaining = 1
        }
    }

    private fun handleActiveBonzoState(points: List<PathPoint>, isHighSpeed: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        when (bonzoState) {
            BonzoState.PRE_FIRE_PAUSE -> {
                mc.options.keyUp.setDown(false)
                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.FIRE_CLICK
                    bonzoTicksRemaining = 1
                }
            }

            BonzoState.FIRE_CLICK -> {
                // Fire Bonzo Staff explosive recoil
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                bonzoState = BonzoState.POST_FIRE_PROPEL
                bonzoTicksRemaining = 1
            }

            BonzoState.POST_FIRE_PROPEL -> {
                // Instantly re-engage forward (W) and Jump to ride the explosive propulsion
                mc.options.keyUp.setDown(true)
                mc.options.keyJump.setDown(true)
                mc.options.keySprint.setDown(true)

                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.IDLE
                    // Immediately transition to the next walk waypoint
                    if (bonzoTargetNextIndex < points.size) {
                        currentNodeIndex = bonzoTargetNextIndex
                    } else {
                        currentNodeIndex++
                    }
                    if (currentNodeIndex >= points.size) {
                        activePreset?.let { finishRoute(it) }
                    }
                }
            }

            BonzoState.IDLE -> {}
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
        if (Config.activePathfindingPresetId == preset.id) {
            Config.activePathfindingPresetId = ""
            Config.save()
        }

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
