package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.ArrowAlignSolver
import com.asthoonlite.dungeon.F7Devices
import com.asthoonlite.dungeon.TerminalInteraction
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
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
    private var bonzoLaunchYaw = 0f
    private var jumpTicksRemaining = 0

    // Smooth camera velocity state
    private var cameraYawVelocity = 0f
    private var cameraPitchVelocity = 0f
    private var aimedThisTick = false

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
        bonzoLaunchYaw = 0f
        jumpTicksRemaining = 0
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
        bonzoLaunchYaw = 0f
        jumpTicksRemaining = 0
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
        bonzoLaunchYaw = 0f
        cameraYawVelocity = 0f
        cameraPitchVelocity = 0f
        aimedThisTick = false
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

    fun getTelemetryStatus(): String? {
        if (!isActive) return null
        return "preset=${activePreset?.name},node=$currentNodeIndex,bonzoState=${bonzoState.name},bonzoTicks=$bonzoTicksRemaining"
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
        aimedThisTick = false

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
            val isAtNodeElev = player.y >= target.y - 1.2 && player.y <= target.y + 1.8
            val currentBpsH = player.deltaMovement.horizontalDistance() * 20.0
            val toNextDx = if (nextTarget != null) nextTarget.x - player.x else target.x - player.x
            val toNextDz = if (nextTarget != null) nextTarget.z - player.z else target.z - player.z
            val nextYaw = (-Math.toDegrees(atan2(toNextDx, toNextDz))).toFloat()
            val isAtLaunchLedge = isLedgeOrGapAhead(level, player, nextYaw)

            // Proximity check:
            // Node 0 (start node) triggers when firmly grounded within 2.2m.
            // Subsequent Bonzo nodes trigger within 2.6m, or up to 3.4m if arriving at the chasm launch ledge.
            // isAtLaunchLedge must NEVER trigger when far from the node (e.g. 8+ meters away at the previous landing spot)!
            val isArrivedOnPlatform = if (currentNodeIndex == 0) {
                distH <= 2.2
            } else {
                distH <= 2.6 || (isAtLaunchLedge && distH <= 3.4)
            }
            val hasLaunchSpeed = currentBpsH >= 5.0 || (isAtLaunchLedge && distH <= 3.4) || (currentNodeIndex == 0 && distH <= 1.2)
            val isGrounded = player.onGround()
            val canLaunch = isGrounded && !player.isInLava && !player.isInWater &&
                            isAtNodeElev && isArrivedOnPlatform && hasLaunchSpeed

            if (canLaunch) {
                initiateBonzoLaunch(points, isHighSpeed)
                return
            }
            // Otherwise, continue walking/sprinting towards the Bonzo launch point
        }

        // 3. Handle approaching / arriving at a JUMP node
        if (nodeType == RouteNodeType.JUMP) {
            val jumpArrivalDist = if (isHighSpeed) 2.2 else 1.5
            val bpsH = player.deltaMovement.horizontalDistance() * 20.0
            val toTargetDx = target.x - player.x
            val toTargetDz = target.z - player.z
            val targetYaw = (-Math.toDegrees(atan2(toTargetDx, toTargetDz))).toFloat()
            val isAtLedge = isLedgeOrGapAhead(level, player, targetYaw)
            val hasSpeed = bpsH >= 10.0
            val canExecuteJump = (distH <= jumpArrivalDist && distY < 2.0 && player.onGround()) &&
                                 (isAtLedge || hasSpeed || distH <= 0.8)
            if (canExecuteJump) {
                jumpTicksRemaining = 3
                mc.options.keyJump.setDown(true)
                player.setSprinting(true)
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) {
                    finishRoute(preset)
                    return
                }
            }
        }

        // 3. Specialized Stationary Nodes: Simon Says, Arrows Align, Timeout
        if (nodeType == RouteNodeType.SIMON_SAYS && distH < 1.8 && distY < 2.2) {
            releaseAllMovementKeys()
            val hSpeed = player.deltaMovement.horizontalDistance() * 20.0
            if (hSpeed > 1.2) {
                mc.options.keyDown.setDown(true)
            }
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
            val hSpeed = player.deltaMovement.horizontalDistance() * 20.0
            if (hSpeed > 1.2) {
                mc.options.keyDown.setDown(true)
            }
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
            val hSpeed = player.deltaMovement.horizontalDistance() * 20.0
            if (hSpeed > 1.2) {
                mc.options.keyDown.setDown(true)
            }
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

        // 5. Waypoint Lookahead, Look Node Aiming & Smooth Natural Camera Control
        val isClimbingToNode = target.y > player.y + 0.4
        val arrivalThreshold = if (isClimbingToNode) 1.5 else if (isHighSpeed) 2.5 else 1.5

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

        if (target.hasLookNode) {
            aimTowardsVec(player, Vec3(target.lookX, target.lookY, target.lookZ))
        } else if (nodeType == RouteNodeType.BONZO_STAFF && distH < 2.5) {
            // Smoothly pre-aim launch yaw and pitch as player approaches Bonzo node
            val preYaw = if (target.hasLookNode) {
                val ldx = target.lookX - player.x
                val ldz = target.lookZ - player.z
                (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
            } else if (target.yaw != 0f) {
                target.yaw
            } else if (nextTarget != null) {
                val bDx = nextTarget.x - player.x
                val bDz = nextTarget.z - player.z
                (-Math.toDegrees(atan2(bDx, bDz))).toFloat()
            } else {
                target.yaw
            }
            val prePitch = if (target.pitch != 0f) target.pitch else 79.0f
            aimRotation(player, preYaw, prePitch, launching = true)
        } else {
            val aimDx = aimX - player.x
            val aimDz = aimZ - player.z
            val aimDistH = sqrt(aimDx * aimDx + aimDz * aimDz)

            val destYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
            val targetEyeY = target.y + 1.2
            val aimDy = targetEyeY - (player.y + player.eyeHeight)
            val pitchDistH = aimDistH.coerceAtLeast(3.5)
            val destPitch = (-Math.toDegrees(atan2(aimDy, pitchDistH))).toFloat().coerceIn(-15.0f, 15.0f)

            aimRotation(player, destYaw, destPitch)
        }

        // 6. Movement Controls with WASD Vectoring, In-Air Braking & Ledge Jump Handling
        val isCrouchNode = (nodeType == RouteNodeType.CROUCH)
        val isAirborne = !player.onGround() && !player.isInLava && !player.isInWater
        val isStationaryDest = (nodeType == RouteNodeType.SIMON_SAYS ||
                                nodeType == RouteNodeType.ARROWS_ALIGN ||
                                nodeType == RouteNodeType.TIMEOUT ||
                                currentNodeIndex == points.size - 1)

        // In-Air Braking & Deceleration:
        // Only brake hard when arriving at stationary puzzle stations (e.g. Simon Says, Arrows Align, final stop)
        // NEVER brake or tap S mid-air on normal movement waypoints or chasm flights!
        var inAirBrakeActive = false
        if (isAirborne && bonzoState == BonzoState.IDLE) {
            if (isStationaryDest && distH < 3.8) {
                inAirBrakeActive = true
            }
        }

        if (inAirBrakeActive) {
            // Apply air brake: cancel forward sprint and tap S to drop cleanly
            mc.options.keySprint.setDown(false)
            mc.options.keyUp.setDown(false)
            mc.options.keyDown.setDown(true)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
        } else if (isAirborne) {
            // In air during flight/jumps: NEVER strafe sideways (prevents deflecting into lava)
            mc.options.keyUp.setDown(true)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keySprint.setDown(true)
            player.setSprinting(true)
        } else {
            // On ground: calculate movement vector towards target
            val targetMoveX = if (lookaheadBlend > 0.0 && nextTarget != null) aimX else target.x
            val targetMoveZ = if (lookaheadBlend > 0.0 && nextTarget != null) aimZ else target.z
            val moveYaw = (-Math.toDegrees(atan2(targetMoveX - player.x, targetMoveZ - player.z))).toFloat()
            val angleDiff = Mth.wrapDegrees(moveYaw - player.yRot)
            val rad = Math.toRadians(angleDiff.toDouble())
            val forward = cos(rad)
            val strafe = -sin(rad)

            // Stationary arrival deceleration (e.g. approaching Simon Says on ground)
            val isSlowingForStationary = isStationaryDest && distH < 2.5
            val isStoppingForStationary = isStationaryDest && distH < 1.4

            if (isStoppingForStationary) {
                mc.options.keyUp.setDown(false)
                mc.options.keyDown.setDown(true) // quick brake tap
                mc.options.keyLeft.setDown(false)
                mc.options.keyRight.setDown(false)
                mc.options.keySprint.setDown(false)
            } else if (target.hasLookNode || nodeType == RouteNodeType.BONZO_STAFF) {
                // Decoupled camera or Bonzo pre-aiming: full WASD vectoring to steer onto platform
                mc.options.keyUp.setDown(forward > 0.20)
                mc.options.keyDown.setDown(forward < -0.20)
                mc.options.keyLeft.setDown(strafe > 0.20)
                mc.options.keyRight.setDown(strafe < -0.20)

                if (isCrouchNode) {
                    mc.options.keyShift.setDown(true)
                    mc.options.keySprint.setDown(false)
                } else {
                    mc.options.keyShift.setDown(false)
                    val wantsSprint = (forward > 0.20 || nodeType == RouteNodeType.BONZO_STAFF) && !isSlowingForStationary
                    mc.options.keySprint.setDown(wantsSprint)
                    if (wantsSprint && !player.isSprinting) player.setSprinting(true)
                }
            } else {
                // Normal pathing: forward W dominates to eliminate sideways drift/crab-walking
                val isSharpTurn = Math.abs(angleDiff) > 50.0 && nodeType != RouteNodeType.BONZO_STAFF
                mc.options.keyUp.setDown(forward > 0.1 || !isSharpTurn)
                mc.options.keyDown.setDown(forward < -0.5)
                // Only assist with strafe keys on very sharp corners (> 50°)
                mc.options.keyLeft.setDown(isSharpTurn && strafe > 0.5)
                mc.options.keyRight.setDown(isSharpTurn && strafe < -0.5)

                if (isCrouchNode) {
                    mc.options.keyShift.setDown(true)
                    mc.options.keySprint.setDown(false)
                } else {
                    mc.options.keyShift.setDown(false)
                    val wantsSprint = (forward > 0.2 || !isSharpTurn || nodeType == RouteNodeType.BONZO_STAFF) && !isSlowingForStationary
                    mc.options.keySprint.setDown(wantsSprint)
                    if (wantsSprint && !player.isSprinting) {
                        player.setSprinting(true)
                    }
                }
            }
        }

        // Jump handling: auto-jump on elevation step-up, obstacle collision, gap/ledge detection
        val toTargetDx = target.x - player.x
        val toTargetDz = target.z - player.z
        val targetYaw = (-Math.toDegrees(atan2(toTargetDx, toTargetDz))).toFloat()
        val isLedge = isLedgeOrGapAhead(level, player, targetYaw)
        // Auto gap jump: trigger whenever there is a chasm/drop ahead of the player leading to target platform
        // (distH > 1.4 ensures we jump chasms while traveling to any node platform including BONZO_STAFF)
        val canAutoGapJump = player.onGround() && isLedge && distH > 1.4 && !isCrouchNode
        val isObstacleCollision = player.horizontalCollision && player.onGround()
        val isElevationStep = target.y > player.y + 0.35 && distH < 2.5 && player.onGround()

        if ((canAutoGapJump || isObstacleCollision || isElevationStep) && jumpTicksRemaining <= 0) {
            jumpTicksRemaining = 4
        }

        if (jumpTicksRemaining > 0) {
            mc.options.keyJump.setDown(true)
            if (!isCrouchNode) {
                player.setSprinting(true)
            }
            jumpTicksRemaining--
        } else {
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
        val canArriveElevation = if (isClimbingToNode) player.y >= target.y - 0.6 else distY < 2.5

        if (nodeType != RouteNodeType.BONZO_STAFF && nodeType != RouteNodeType.JUMP &&
            distH < arrivalThreshold && canArriveElevation) {
            currentNodeIndex++
            resetSpecialNodeState()
            if (currentNodeIndex >= points.size) {
                finishRoute(preset)
            }
        }
    }

    fun onRenderFrame(deltaTracker: DeltaTracker) {
        if (!isActive) return
        val preset = activePreset ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val points = preset.points
        if (currentNodeIndex !in points.indices) return

        val target = points[currentNodeIndex]
        val nodeType = target.nodeType()
        val nextTarget = points.getOrNull(currentNodeIndex + 1)

        val partialTick = deltaTracker.getGameTimeDeltaPartialTick(true)
        val dtTicks = deltaTracker.realtimeDeltaTicks.coerceIn(0.005f, 1.0f)

        // Intra-tick kinematic prediction: extrapolate player position along current velocity vector
        val renderX = Mth.lerp(partialTick.toDouble(), player.xo, player.x)
        val renderY = Mth.lerp(partialTick.toDouble(), player.yo, player.y)
        val renderZ = Mth.lerp(partialTick.toDouble(), player.zo, player.z)

        val vx = player.deltaMovement.x
        val vy = player.deltaMovement.y
        val vz = player.deltaMovement.z

        // Forward prediction lookahead (0.5 ticks ahead to lead the camera smoothly along movement)
        val predLead = 0.5
        val predX = renderX + vx * predLead
        val predY = renderY + vy * predLead
        val predZ = renderZ + vz * predLead
        val predEyeY = predY + player.eyeHeight

        val dx = target.x - predX
        val dz = target.z - predZ
        val distH = sqrt(dx * dx + dz * dz)

        val goalYaw: Float
        val goalPitch: Float
        val isFastAim: Boolean

        when {
            bonzoState == BonzoState.POST_FIRE_PROPEL -> {
                val destNode = points.getOrNull(bonzoTargetNextIndex) ?: points.getOrNull(currentNodeIndex + 1)
                goalYaw = if (destNode != null) {
                    val sdx = destNode.x - predX
                    val sdz = destNode.z - predZ
                    (-Math.toDegrees(atan2(sdx, sdz))).toFloat()
                } else {
                    bonzoLaunchYaw
                }
                goalPitch = 10.0f
                isFastAim = true
            }
            nodeType == RouteNodeType.BONZO_STAFF && distH < 3.2 -> {
                goalYaw = if (target.hasLookNode) {
                    val ldx = target.lookX - predX
                    val ldz = target.lookZ - predZ
                    (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
                } else if (target.yaw != 0f) {
                    target.yaw
                } else if (nextTarget != null) {
                    val bDx = nextTarget.x - predX
                    val bDz = nextTarget.z - predZ
                    (-Math.toDegrees(atan2(bDx, bDz))).toFloat()
                } else {
                    target.yaw
                }
                goalPitch = if (target.pitch != 0f) target.pitch else 79.0f
                isFastAim = true
            }
            nodeType == RouteNodeType.SIMON_SAYS && distH < 3.0 -> {
                val simonPos = Vec3(110.5, 121.5, 93.5)
                val sdx = simonPos.x - predX
                val sdy = simonPos.y - predEyeY
                val sdz = simonPos.z - predZ
                val sdistH = sqrt(sdx * sdx + sdz * sdz)
                goalYaw = (-Math.toDegrees(atan2(sdx, sdz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(sdy, sdistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            nodeType == RouteNodeType.ARROWS_ALIGN && distH < 3.0 -> {
                val arrowPos = Vec3(-2.0, 122.5, 77.0)
                val adx = arrowPos.x - predX
                val ady = arrowPos.y - predEyeY
                val adz = arrowPos.z - predZ
                val adistH = sqrt(adx * adx + adz * adz)
                goalYaw = (-Math.toDegrees(atan2(adx, adz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(ady, adistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            target.hasLookNode -> {
                val ldx = target.lookX - predX
                val ldy = target.lookY - predEyeY
                val ldz = target.lookZ - predZ
                val ldistH = sqrt(ldx * ldx + ldz * ldz)
                goalYaw = (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(ldy, ldistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            else -> {
                val lookaheadBlend = if (distH < 2.8 && nextTarget != null && nodeType == RouteNodeType.WALK) {
                    val t = ((2.8 - distH) / 2.8).coerceIn(0.0, 1.0)
                    t * t * (3.0 - 2.0 * t) * 0.40
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
                val aimDx = aimX - predX
                val aimDz = aimZ - predZ
                val aimDistH = sqrt(aimDx * aimDx + aimDz * aimDz)
                goalYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
                val targetEyeY = target.y + 1.2
                val aimDy = targetEyeY - predEyeY
                val pitchDistH = aimDistH.coerceAtLeast(3.5)
                goalPitch = (-Math.toDegrees(atan2(aimDy, pitchDistH))).toFloat().coerceIn(-15.0f, 15.0f)
                isFastAim = false
            }
        }

        // Frame-rate independent continuous exponential smoothing
        val lambda = if (isFastAim) 32.0 else 16.0
        val alpha = (1.0 - exp(-lambda * dtTicks.toDouble())).toFloat().coerceIn(0.05f, 0.95f)
        val deltaYaw = Mth.wrapDegrees(goalYaw - player.yRot)
        val deltaPitch = (goalPitch - player.xRot)

        val maxFrameYawStep = if (isFastAim) 50.0f * dtTicks else 24.0f * dtTicks
        val maxFramePitchStep = if (isFastAim) 45.0f * dtTicks else 18.0f * dtTicks

        val stepYaw = (deltaYaw * alpha).coerceIn(-maxFrameYawStep, maxFrameYawStep)
        val stepPitch = (deltaPitch * alpha).coerceIn(-maxFramePitchStep, maxFramePitchStep)

        player.yRot += stepYaw
        player.xRot = (player.xRot + stepPitch).coerceIn(-89.9f, 89.9f)
        player.yRotO = player.yRot
        player.xRotO = player.xRot
    }

    private fun aimTowards(player: net.minecraft.client.player.LocalPlayer, node: PathPoint, defaultPos: Vec3?) {
        val targetPos = if (node.hasLookNode) {
            Vec3(node.lookX, node.lookY, node.lookZ)
        } else {
            defaultPos
        }
        if (targetPos != null) aimTowardsVec(player, targetPos)
    }

    private fun aimTowardsVec(player: LocalPlayer, targetPos: Vec3) {
        val dx = targetPos.x - player.x
        val dy = targetPos.y - (player.y + player.eyeHeight)
        val dz = targetPos.z - player.z
        val distH = sqrt(dx * dx + dz * dz)

        val destYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
        val destPitch = (-Math.toDegrees(atan2(dy, distH))).toFloat().coerceIn(-89f, 89f)

        aimRotation(player, destYaw, destPitch)
    }

    private fun aimRotation(player: LocalPlayer, yaw: Float, pitch: Float, launching: Boolean = false) {
        if (aimedThisTick) return
        aimedThisTick = true
        // When route execution is active, camera rotation is continuously interpolated
        // at the full render refresh rate (144Hz+) in onRenderFrame. Overwriting player.yRot
        // at 20 Hz tick creates visible micro-stutters.
        if (isActive) return
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val rate = if (launching) 1.2 else 0.55
        val maxYawStep = if (launching) 24f else 10f
        val maxPitchStep = if (launching) 20f else 6f
        val (nextYaw, yawVelocity) = dampRotation(oldYaw, yaw, cameraYawVelocity, rate, maxYawStep, wrap = true)
        val (nextPitch, pitchVelocity) = dampRotation(oldPitch, pitch, cameraPitchVelocity, rate, maxPitchStep)
        cameraYawVelocity = yawVelocity
        cameraPitchVelocity = pitchVelocity
        player.yRotO = oldYaw
        player.xRotO = oldPitch
        player.yRot = nextYaw
        player.xRot = nextPitch
    }

    internal fun dampRotation(
        current: Float, target: Float, velocity: Float, rate: Double, maxStep: Float, wrap: Boolean = false
    ): Pair<Float, Float> {
        val delta = if (wrap) Mth.wrapDegrees(target - current) else target - current
        if (abs(delta) < 0.02f) return (current + delta) to 0f
        val alpha = (1.0 - exp(-rate * 0.65)).toFloat().coerceIn(0.15f, 0.45f)
        val step = (delta * alpha).coerceIn(-maxStep, maxStep)
        return (current + step) to step
    }

    internal fun waypointLookahead(target: PathPoint, next: PathPoint?, distH: Double, arrival: Double): Vec3 {
        val t = if ((target.nodeType() == RouteNodeType.WALK || target.nodeType() == RouteNodeType.JUMP) && next != null)
            ((arrival + 3.0 - distH) / 3.0).coerceIn(0.0, 1.0) else 0.0
        val blend = t * t * (3.0 - 2.0 * t)
        return Vec3(
            target.x + ((next?.x ?: target.x) - target.x) * blend,
            target.y + 1.2 + ((next?.y ?: target.y) - target.y) * blend,
            target.z + ((next?.z ?: target.z) - target.z) * blend
        )
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

    private fun isLedgeOrGapAhead(level: Level, player: LocalPlayer, moveYaw: Float): Boolean {
        if (!player.onGround() || player.isInLava || player.isInWater) return false

        val rad = Math.toRadians(-moveYaw.toDouble())
        val nx = sin(rad)
        val nz = cos(rad)

        val floorY = floor(player.y - 0.1).toInt()
        val isHazardOrAir: (net.minecraft.world.level.block.state.BlockState) -> Boolean = { state ->
            state.isAir || state.`is`(Blocks.LAVA) || state.`is`(Blocks.WATER)
        }

        // Probe 1.0 and 1.6 blocks ahead along movement line
        for (dist in listOf(1.0, 1.6)) {
            val probeX = player.x + nx * dist
            val probeZ = player.z + nz * dist
            val probeBlockPos = BlockPos(floor(probeX).toInt(), floorY, floor(probeZ).toInt())

            val stateAtFloor = level.getBlockState(probeBlockPos)
            val stateBelowFloor = level.getBlockState(probeBlockPos.below())

            // If floor level AND 1 block below are air/lava/hazard, there is a drop/gap ahead!
            if (isHazardOrAir(stateAtFloor) && isHazardOrAir(stateBelowFloor)) {
                return true
            }
        }
        return false
    }

    private fun initiateBonzoLaunch(points: List<PathPoint>, isHighSpeed: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val currentNode = points[currentNodeIndex]

        // Auto-select Bonzo's Staff
        selectBonzoStaff()

        // Launch towards the next waypoint in the route
        val nextIdx = currentNodeIndex + 1
        bonzoTargetNextIndex = nextIdx
        val destination = points.getOrNull(nextIdx) ?: currentNode

        val launchDx = destination.x - player.x
        val launchDz = destination.z - player.z
        val calculatedYaw = (-Math.toDegrees(atan2(launchDx, launchDz))).toFloat()
        val launchYaw = if (currentNode.hasLookNode) {
            val ldx = currentNode.lookX - player.x
            val ldz = currentNode.lookZ - player.z
            (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
        } else if (currentNode.yaw != 0f) {
            currentNode.yaw
        } else {
            calculatedYaw
        }
        bonzoLaunchYaw = launchYaw

        val launchPitch = if (currentNode.pitch != 0f) currentNode.pitch else 79.0f

        player.yRotO = launchYaw
        player.xRotO = launchPitch
        cameraYawVelocity = 0f
        cameraPitchVelocity = 0f
        player.yRot = launchYaw
        player.xRot = launchPitch

        // Movement keys: Hold forward (W) and Sprint on both 500 and 550 speed.
        // Advancing over the impact point ensures the blast catches the player from behind and boosts forward.
        mc.options.keyUp.setDown(true)
        mc.options.keyDown.setDown(false)
        mc.options.keyLeft.setDown(false)
        mc.options.keyRight.setDown(false)
        mc.options.keySprint.setDown(true)
        player.setSprinting(true)

        // Single jump pulse on fire
        jumpTicksRemaining = 3
        mc.options.keyJump.setDown(true)

        // Fire immediately so projectile hits the platform floor ahead/under player in time
        player.swing(InteractionHand.MAIN_HAND)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        PathfindCapture.notifyBonzoShot("AUTO_EXECUTOR")

        bonzoState = BonzoState.POST_FIRE_PROPEL
        bonzoTicksRemaining = 12
    }

    private fun handleActiveBonzoState(points: List<PathPoint>, isHighSpeed: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        when (bonzoState) {
            BonzoState.POST_FIRE_PROPEL -> {
                // Engage forward (W) and Sprint directly in direction of destination
                mc.options.keyUp.setDown(true)
                mc.options.keyDown.setDown(false)
                mc.options.keyLeft.setDown(false)
                mc.options.keyRight.setDown(false)
                mc.options.keySprint.setDown(true)
                player.setSprinting(true)

                // Manage jump pulse cleanly so player never bunny hops on landing
                if (jumpTicksRemaining > 0) {
                    mc.options.keyJump.setDown(true)
                    jumpTicksRemaining--
                } else {
                    mc.options.keyJump.setDown(false)
                }

                // Smoothly recover camera pitch from ground (79°) back up to eye level (10°)
                // and steer towards destination waypoint during flight
                val destPoint = points.getOrNull(bonzoTargetNextIndex)
                val steerYaw = if (destPoint != null) {
                    val sdx = destPoint.x - player.x
                    val sdz = destPoint.z - player.z
                    (-Math.toDegrees(atan2(sdx, sdz))).toFloat()
                } else {
                    bonzoLaunchYaw
                }
                aimRotation(player, steerYaw, 10.0f, launching = true)

                bonzoTicksRemaining--
                if (bonzoTicksRemaining <= 0) {
                    bonzoState = BonzoState.IDLE
                    mc.options.keyJump.setDown(false)
                    jumpTicksRemaining = 0
                    // Immediately transition to the destination waypoint
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

            else -> {
                bonzoState = BonzoState.IDLE
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
