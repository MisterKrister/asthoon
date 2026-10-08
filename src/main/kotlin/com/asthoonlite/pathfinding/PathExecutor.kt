package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.ArrowAlignSolver
import com.asthoonlite.dungeon.F7Devices
import com.asthoonlite.dungeon.TerminalInteraction
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*

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
 * Look Node Support:
 * - When approaching or standing at a waypoint with a Look Node, camera aims smoothly
 *   towards the specified 3D target in the world/air, with strafe-compensated navigation.
 *
 * Specialized Nodes:
 * - Bonzo Staff: Launches with ~82° downward pitch and 4-tick ping-absorption delay.
 * - Terminal: Smoothly looks at the terminal interaction hitbox as it approaches and triggers it.
 * - Simon Says: Stands still at waypoint until device completion is detected.
 * - Arrows Align: Stands still at waypoint until puzzle completion is detected.
 * - Timeout: Pauses standing still for the configured seconds duration.
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

    // Specialized node states
    private var terminalScreenWasOpen = false
    private var lastTerminalClickTime = 0L
    private var terminalClicksDone = 0
    private var terminalTicks = 0
    private var timeoutTicksRemaining = -1
    private var lastHandledNodeIndex = -1

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
        resetSpecialNodeState()
        lastHandledNodeIndex = -1

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
        resetSpecialNodeState()
        lastHandledNodeIndex = -1

        // Safety: release all movement inputs
        releaseAllMovementKeys()
    }

    private fun resetSpecialNodeState() {
        terminalScreenWasOpen = false
        lastTerminalClickTime = 0L
        terminalClicksDone = 0
        terminalTicks = 0
        timeoutTicksRemaining = -1
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
            val p = activePreset
            if (p != null && currentNodeIndex in p.points.indices && p.points[currentNodeIndex].nodeType() == RouteNodeType.TERMINAL) {
                terminalScreenWasOpen = true
            }
            return
        }

        // If a terminal container screen was open and has now closed (completed or cancelled), advance
        if (terminalScreenWasOpen) {
            terminalScreenWasOpen = false
            val p = activePreset
            if (p != null && currentNodeIndex in p.points.indices && p.points[currentNodeIndex].nodeType() == RouteNodeType.TERMINAL) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= p.points.size) {
                    finishRoute(p)
                    return
                }
            }
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

        if (lastHandledNodeIndex != currentNodeIndex) {
            resetSpecialNodeState()
            lastHandledNodeIndex = currentNodeIndex
        }

        val target = points[currentNodeIndex]
        val nodeType = target.nodeType()
        val nextTarget = points.getOrNull(currentNodeIndex + 1)

        val dx = target.x - player.x
        val dy = target.y - (player.y + player.eyeHeight)
        val dz = target.z - player.z
        val distH = sqrt(dx * dx + dz * dz)
        val distY = abs(target.y - player.y)

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
            // Trigger Bonzo launch when inside trigger zone (~2.2 blocks)
            if (distH <= 2.2 && distY <= 2.5) {
                initiateBonzoLaunch(points, isHighSpeed)
                return
            }
            // Otherwise, continue walking towards the Bonzo trigger zone
        }

        // 3. Specialized Stationary Nodes: Simon Says, Arrows Align, Timeout
        if (nodeType == RouteNodeType.SIMON_SAYS && distH < 1.8 && distY < 2.2) {
            releaseAllMovementKeys()
            aimTowards(player, target, Vec3(110.5, 121.5, 93.5))
            if (F7Devices.isSimonCompleted()) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) finishRoute(preset)
            }
            return
        }

        if (nodeType == RouteNodeType.ARROWS_ALIGN && distH < 1.8 && distY < 2.2) {
            releaseAllMovementKeys()
            aimTowards(player, target, Vec3(-2.0, 122.5, 77.0))
            if (ArrowAlignSolver.isSolved()) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) finishRoute(preset)
            }
            return
        }

        if (nodeType == RouteNodeType.TIMEOUT && distH < 1.3 && distY < 2.2) {
            releaseAllMovementKeys()
            aimTowards(player, target, null)
            if (timeoutTicksRemaining < 0) {
                val duration = if (target.timeoutSeconds > 0.0) target.timeoutSeconds else 1.0
                timeoutTicksRemaining = (duration * 20.0).toInt().coerceAtLeast(1)
            }
            timeoutTicksRemaining--
            if (timeoutTicksRemaining <= 0) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) finishRoute(preset)
            }
            return
        }

        // 4. Specialized Approach Node: Terminal
        if (nodeType == RouteNodeType.TERMINAL) {
            terminalTicks++
            val termTarget = if (target.hasLookNode) {
                Vec3(target.lookX, target.lookY, target.lookZ)
            } else {
                findTerminalTarget(level, Vec3(target.x, target.y, target.z)) ?: Vec3(target.x, target.y + 1.2, target.z)
            }

            if (distH < 5.0) {
                aimTowardsVec(player, termTarget)
            }

            // In interaction range: halt forward movement and use triggerbot
            if (distH < 2.2 && distY < 2.2) {
                releaseAllMovementKeys()

                // Triggerbot on terminal
                val now = System.currentTimeMillis()
                val hit = mc.hitResult
                if (hit is EntityHitResult) {
                    val e = hit.entity
                    if ((e is ItemFrame && TerminalInteraction.isTerminalItemFrame(level, e)) ||
                        (e is ArmorStand && TerminalInteraction.isTerminalArmorStand(e))) {
                        if (now - lastTerminalClickTime > 400L) {
                            lastTerminalClickTime = now
                            terminalClicksDone++
                            player.swing(InteractionHand.MAIN_HAND)
                            mc.gameMode?.interact(player, e, hit, InteractionHand.MAIN_HAND)
                        }
                    }
                } else if (hit is BlockHitResult && TerminalInteraction.isTerminalBlock(level, hit.blockPos)) {
                    if (now - lastTerminalClickTime > 400L) {
                        lastTerminalClickTime = now
                        terminalClicksDone++
                        player.swing(InteractionHand.MAIN_HAND)
                        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                    }
                }

                // If clicked multiple times and timed out without GUI, or if already marked completed
                if (terminalClicksDone >= 3 && terminalTicks > 70) {
                    currentNodeIndex++
                    resetSpecialNodeState()
                    if (currentNodeIndex >= points.size) finishRoute(preset)
                }
                return
            }
        }

        // 5. Waypoint Lookahead, Look Node Aiming & Corner-Rounding
        if (target.hasLookNode) {
            aimTowardsVec(player, Vec3(target.lookX, target.lookY, target.lookZ))
        } else {
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

            // Organic micro-sway matching recorded run data
            val swayYaw = (sin(tickCount * 0.45) * 0.35 + sin(tickCount * 0.95) * 0.18).toFloat()
            val swayPitch = (cos(tickCount * 0.40) * 0.22).toFloat()

            val deltaYaw = Mth.wrapDegrees(destYaw + swayYaw - player.yRot)
            val deltaPitch = (destPitch + swayPitch - player.xRot)

            val maxTurnRate = 32.0f
            val turnStep = (deltaYaw * 0.42f).coerceIn(-maxTurnRate, maxTurnRate)
            player.yRot += turnStep
            player.xRot += deltaPitch * 0.42f
        }

        // 6. Movement Controls with Look-Node Strafe Compensation
        if (target.hasLookNode && distH > 0.8) {
            val moveYaw = (-Math.toDegrees(atan2(target.x - player.x, target.z - player.z))).toFloat()
            val angleDiff = Mth.wrapDegrees(moveYaw - player.yRot)
            val rad = Math.toRadians(angleDiff.toDouble())
            val forward = cos(rad)
            val strafe = -sin(rad)
            mc.options.keyUp.setDown(forward > 0.2)
            mc.options.keyDown.setDown(forward < -0.2)
            mc.options.keyLeft.setDown(strafe < -0.2)
            mc.options.keyRight.setDown(strafe > 0.2)
            mc.options.keySprint.setDown(forward > 0.4)
        } else {
            mc.options.keyUp.setDown(true)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keySprint.setDown(true)
        }

        // Jump handling: auto-jump on elevation step-up, obstacle collision, or Jump nodes
        if (nodeType == RouteNodeType.JUMP || player.horizontalCollision || (target.y > player.y + 0.35 && distH < 2.5)) {
            mc.options.keyJump.setDown(true)
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false)
        }

        // 7. Interaction Node Handling
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
            resetSpecialNodeState()
            return
        }

        // 8. Fluid Waypoint Transition: Speed-Scaled Arrival Check
        val arrivalThreshold = if (isHighSpeed) 2.2 else 1.2
        if (distH < arrivalThreshold && distY < 2.2) {
            currentNodeIndex++
            resetSpecialNodeState()
            if (currentNodeIndex >= points.size) {
                finishRoute(preset)
            }
        }
    }

    private fun aimTowards(player: net.minecraft.client.player.LocalPlayer, node: PathPoint, defaultPos: Vec3?) {
        val targetPos = if (node.hasLookNode) {
            Vec3(node.lookX, node.lookY, node.lookZ)
        } else {
            defaultPos
        }
        if (targetPos != null) aimTowardsVec(player, targetPos)
    }

    private fun aimTowardsVec(player: net.minecraft.client.player.LocalPlayer, targetPos: Vec3) {
        val dx = targetPos.x - player.x
        val dy = targetPos.y - (player.y + player.eyeHeight)
        val dz = targetPos.z - player.z
        val distH = sqrt(dx * dx + dz * dz)

        val destYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
        val destPitch = (-Math.toDegrees(atan2(dy, distH))).toFloat().coerceIn(-89f, 89f)

        val deltaYaw = Mth.wrapDegrees(destYaw - player.yRot)
        val deltaPitch = (destPitch - player.xRot)

        val maxTurnRate = 35.0f
        player.yRot += (deltaYaw * 0.45f).coerceIn(-maxTurnRate, maxTurnRate)
        player.xRot += (deltaPitch * 0.45f).coerceIn(-maxTurnRate, maxTurnRate)
    }

    private fun findTerminalTarget(level: net.minecraft.world.level.Level, center: Vec3): Vec3? {
        val aabb = AABB(center.x - 4.0, center.y - 3.0, center.z - 4.0, center.x + 4.0, center.y + 3.0, center.z + 4.0)
        // 1. Terminal ItemFrame
        val frames = level.getEntitiesOfClass(ItemFrame::class.java, aabb)
        val termFrame = frames.firstOrNull { TerminalInteraction.isTerminalItemFrame(level, it) }
        if (termFrame != null) return termFrame.position().add(0.0, 0.25, 0.0)

        // 2. Terminal ArmorStand
        val stands = level.getEntitiesOfClass(ArmorStand::class.java, aabb)
        val termStand = stands.firstOrNull { TerminalInteraction.isTerminalArmorStand(it) }
        if (termStand != null) return termStand.position().add(0.0, 1.0, 0.0)

        // 3. Terminal CommandBlock
        val minX = floor(aabb.minX).toInt()
        val maxX = ceil(aabb.maxX).toInt()
        val minY = floor(aabb.minY).toInt()
        val maxY = ceil(aabb.maxY).toInt()
        val minZ = floor(aabb.minZ).toInt()
        val maxZ = ceil(aabb.maxZ).toInt()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val pos = BlockPos(x, y, z)
                    if (TerminalInteraction.isTerminalBlock(level, pos)) {
                        return Vec3.atCenterOf(pos)
                    }
                }
            }
        }
        return null
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
        player.yRot += deltaYaw * 0.65f

        // Look all the way down (~82°) so the projectile hits the ground properly under the player
        player.xRot = 82.0f

        if (isHighSpeed) {
            // Over 400 speed (e.g. 550 speed): pause W for 2 ticks to fling cleanly
            mc.options.keyUp.setDown(false)
            bonzoState = BonzoState.PRE_FIRE_PAUSE
            bonzoTicksRemaining = 2
        } else {
            // Under 400 speed: fire immediately while maintaining forward momentum
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
                player.xRot = 82.0f
                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.FIRE_CLICK
                    bonzoTicksRemaining = 1
                }
            }

            BonzoState.FIRE_CLICK -> {
                player.xRot = 82.0f
                // Fire Bonzo Staff explosive recoil
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                bonzoState = BonzoState.POST_FIRE_PROPEL
                // Keep looking down for ~4 ticks (200ms) to accommodate server ping and floor explosion
                bonzoTicksRemaining = 4
            }

            BonzoState.POST_FIRE_PROPEL -> {
                // Instantly re-engage forward (W) and Jump to ride the explosive propulsion
                mc.options.keyUp.setDown(true)
                mc.options.keyJump.setDown(true)
                mc.options.keySprint.setDown(true)
                player.xRot = 82.0f

                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.IDLE
                    // Immediately transition to the next walk waypoint
                    if (bonzoTargetNextIndex < points.size) {
                        currentNodeIndex = bonzoTargetNextIndex
                    } else {
                        currentNodeIndex++
                    }
                    resetSpecialNodeState()
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
