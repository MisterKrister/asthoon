package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.ArrowAlignSolver
import com.asthoonlite.dungeon.F7Devices
import com.asthoonlite.dungeon.TerminalInteraction
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.*

/**
 * Autonomous path executor with speed-aware movement and damped camera control.
 *
 * Traversal & Fluid Continuity:
 * - Begins pathfinding from player's current location directly towards node #1 (index 0).
 * - Traverses node-by-node without halting between consecutive walk waypoints, utilizing
 *   speed-scaled lookahead and corner-rounding to preserve sprinting momentum.
 * - Blends waypoint lookahead and maintains camera angular velocity between nodes.
 *
 * Look Node Support:
 * - When approaching or standing at a waypoint with a Look Node, camera aims smoothly
 *   towards the specified 3D target in the world/air, with strafe-compensated navigation.
 *
 * Specialized Nodes:
 * - Bonzo Staff: Pre-aims a forward ground shot and requires aligned sprint momentum.
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

    private var aimedThisTick = false
    private var cameraYawVelocity = 0f
    private var cameraPitchVelocity = 0f

    // Bonzo execution sub-state machine
    private enum class BonzoState {
        IDLE,
        POST_FIRE_PROPEL
    }

    private var bonzoState = BonzoState.IDLE
    private var bonzoTicksRemaining = 0
    private var bonzoTargetNextIndex = 0
    private var bonzoLaunchYaw = 0f
    private var jumpTicksRemaining = 0

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
        cameraYawVelocity = 0f
        cameraPitchVelocity = 0f
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
        cameraYawVelocity = 0f
        cameraPitchVelocity = 0f
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
            mc.options.keyShift.setDown(false)
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
        val dz = target.z - player.z
        val targetYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
        val distH = sqrt(dx * dx + dz * dz)
        val distY = abs(target.y - player.y)

        // Read player speed (Hypixel Skyblock speed = attribute * 1000)
        val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
        val skyblockSpeed = speedAttr * 1000.0
        val isHighSpeed = skyblockSpeed > 400.0

        // 1. Handle Active Bonzo Staff Execution State Machine
        if (bonzoState != BonzoState.IDLE) {
            handleActiveBonzoState(points)
            return
        }

        val isAtNodeElev = player.y >= target.y - 0.5 && player.y <= target.y + 1.2
        val launchDestination = nextTarget ?: target
        val launchYaw = (-Math.toDegrees(atan2(launchDestination.x - player.x, launchDestination.z - player.z))).toFloat()
        val launchPitch = bonzoLaunchPitch(target.pitch)
        val bonzoApproachDistance = max(3.5, player.deltaMovement.horizontalDistance() * 4.0 + 1.5)
        val preparingBonzo = nodeType == RouteNodeType.BONZO_STAFF && isAtNodeElev && distH <= bonzoApproachDistance &&
            hasPlatformRunway(level, player.position(), Vec3(target.x, target.y, target.z))
        val approachingJump = nodeType == RouteNodeType.JUMP && nextTarget != null && distH <= (if (isHighSpeed) 2.2 else 1.5)
        val moveYaw = if ((preparingBonzo && distH <= 2.2) || approachingJump) launchYaw else targetYaw

        // 2. Pre-aim while still on the runway; firing never changes the camera angles.
        if (nodeType == RouteNodeType.BONZO_STAFF) {
            if (preparingBonzo && player.onGround()) {
                jumpTicksRemaining = 0
                mc.options.keyJump.setDown(false)
            }
            if (preparingBonzo) aimRotation(player, launchYaw, launchPitch, launching = true)
            val isAtLaunchLedge = isLedgeOrGapAhead(level, player, launchYaw)
            if (bonzoLaunchReady(
                    onPlatform = player.onGround() && !player.isInLava && !player.isInWater && isAtNodeElev,
                    distH = distH,
                    isAtLaunchLedge = isAtLaunchLedge,
                    hasMomentum = hasForwardSprintMomentum(player.deltaMovement, launchYaw, player.isSprinting),
                    alignedAim = abs(Mth.wrapDegrees(launchYaw - player.yRot)) <= 3f && abs(launchPitch - player.xRot) <= 3f,
                    hasLaunchFloor = preparingBonzo && hasBonzoLaunchFloor(level, player)
                )) {
                initiateBonzoLaunch(launchYaw)
                return
            }
            // Otherwise, continue walking/sprinting towards the Bonzo launch point
        }

        // 3. Handle approaching / arriving at a JUMP node
        if (nodeType == RouteNodeType.JUMP) {
            val jumpArrivalDist = if (isHighSpeed) 2.2 else 1.5
            val jumpYaw = if (nextTarget != null) launchYaw else targetYaw
            val isAtLedge = isLedgeOrGapAhead(level, player, jumpYaw)
            val hasSpeed = hasForwardSprintMomentum(player.deltaMovement, jumpYaw, player.isSprinting)
            val canExecuteJump = (distH <= jumpArrivalDist && distY < 2.0 && player.onGround()) &&
                                 (if (isAtLedge) hasSpeed else hasSpeed || distH <= 0.8)
            if (canExecuteJump) {
                jumpTicksRemaining = 3
                mc.options.keyJump.setDown(true)
                player.setSprinting(true)
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) {
                    finishRoute(preset)
                }
                return
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

        // 5. Blend all the way to the next waypoint before the arrival threshold.
        val isClimbingToNode = target.y > player.y + 0.4
        val arrivalThreshold = if (isClimbingToNode) 1.2 else if (isHighSpeed) 2.2 else 1.2
        val aimPos = waypointLookahead(target, nextTarget, distH, arrivalThreshold)
        if (target.hasLookNode) {
            aimTowardsVec(player, Vec3(target.lookX, target.lookY, target.lookZ))
        } else {
            val aimDx = aimPos.x - player.x
            val aimDz = aimPos.z - player.z
            val aimDistH = sqrt(aimDx * aimDx + aimDz * aimDz)
            val destYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
            val aimDy = aimPos.y - (player.y + player.eyeHeight)
            val destPitch = (-Math.toDegrees(atan2(aimDy, aimDistH))).toFloat().coerceIn(-15.0f, 15.0f)
            aimRotation(player, destYaw, destPitch)
        }

        // 6. Movement Controls with WASD Vectoring, In-Air Braking & Ledge Jump Handling
        val isCrouchNode = (nodeType == RouteNodeType.CROUCH)
        val isAirborne = !player.onGround() && !player.isInLava && !player.isInWater
        val isStationaryDest = (nodeType == RouteNodeType.SIMON_SAYS ||
                                nodeType == RouteNodeType.ARROWS_ALIGN ||
                                nodeType == RouteNodeType.TIMEOUT ||
                                currentNodeIndex == points.size - 1)

        val forwardBps = forwardSpeedBps(player.deltaMovement, targetYaw)
        val inAirBrakeActive = isAirborne && shouldInAirBrake(
            isStationaryDest, distH, player.deltaMovement.y, forwardBps, player.y, target.y
        )

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
            // Keep the route position independent of camera lookahead. Near a Bonzo
            // node, the runway follows the launch heading instead of circling the marker.
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
            } else if (target.hasLookNode || aimPos.x != target.x || aimPos.z != target.z || preparingBonzo) {
                // Compensate movement for the independent lookahead or look-node aim.
                mc.options.keyUp.setDown(forward > 0.25)
                mc.options.keyDown.setDown(forward < -0.25)
                mc.options.keyLeft.setDown(strafe > 0.25)
                mc.options.keyRight.setDown(strafe < -0.25)

                if (isCrouchNode) {
                    mc.options.keyShift.setDown(true)
                    mc.options.keySprint.setDown(false)
                } else {
                    mc.options.keyShift.setDown(false)
                    val wantsSprint = forward > 0.38
                    mc.options.keySprint.setDown(wantsSprint)
                    if (wantsSprint && !player.isSprinting) player.setSprinting(true)
                }
            } else {
                // Normal pathing: forward W dominates to eliminate sideways drift/crab-walking
                val isSharpTurn = Math.abs(angleDiff) > 50.0
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
                    val wantsSprint = (forward > 0.2 || !isSharpTurn) && !isSlowingForStationary
                    mc.options.keySprint.setDown(wantsSprint)
                    if (wantsSprint && !player.isSprinting) {
                        player.setSprinting(true)
                    }
                }
            }
        }

        // Jump handling: auto-jump on elevation step-up, obstacle collision, gap/ledge detection
        val isLedge = isLedgeOrGapAhead(level, player, moveYaw)
        val hasGapSpeed = hasForwardSprintMomentum(player.deltaMovement, moveYaw, player.isSprinting)
        val edgeDirection = Vec3(-sin(Math.toRadians(moveYaw.toDouble())), 0.0, cos(Math.toRadians(moveYaw.toDouble())))
        val edgeProbe = player.position().add(edgeDirection.scale(0.5))
        if (player.onGround() && !isCrouchNode && isLedge && (preparingBonzo || !hasGapSpeed) &&
            !hasPlatformFloor(level, edgeProbe.add(0.0, 0.25, 0.0), edgeProbe.add(0.0, -0.6, 0.0), player.y)) {
            // Wait on the runway when aim or momentum is not ready at the edge.
            releaseAllMovementKeys()
            mc.options.keyShift.setDown(true)
            mc.options.keyDown.setDown(true)
            jumpTicksRemaining = 0
            return
        }
        val canAutoGapJump = shouldJumpGap(player.onGround(), isLedge, distH, isCrouchNode, preparingBonzo, hasGapSpeed)
        val isObstacleCollision = player.horizontalCollision && player.onGround()
        val isElevationStep = target.y > player.y + 0.35 && distH < 2.5 && player.onGround()

        if (!preparingBonzo && (!isLedge || hasGapSpeed) &&
            (canAutoGapJump || isObstacleCollision || isElevationStep) && jumpTicksRemaining <= 0) {
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
        val canArriveElevation = if (isClimbingToNode) player.y >= target.y - 0.4 else distY < 2.2

        if (nodeType != RouteNodeType.BONZO_STAFF && nodeType != RouteNodeType.JUMP &&
            distH < arrivalThreshold && canArriveElevation) {
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

        aimRotation(player, destYaw, destPitch)
    }

    private fun aimRotation(player: LocalPlayer, yaw: Float, pitch: Float, launching: Boolean = false) {
        // Terminal approach and general navigation can both request an aim in one tick.
        if (aimedThisTick) return
        aimedThisTick = true
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val rate = if (launching) 1.6 else 0.8
        val (nextYaw, yawVelocity) = dampRotation(oldYaw, yaw, cameraYawVelocity, rate, if (launching) 30f else 12f, wrap = true)
        val (nextPitch, pitchVelocity) = dampRotation(oldPitch, pitch, cameraPitchVelocity, rate, if (launching) 30f else 12f)
        cameraYawVelocity = yawVelocity
        cameraPitchVelocity = pitchVelocity
        player.yRotO = oldYaw
        player.xRotO = oldPitch
        player.yRot = nextYaw
        player.xRot = nextPitch
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

        // Reserve two movement ticks of runway at the measured speed.
        val probeDistance = gapProbeDistance(player.deltaMovement)
        for (dist in listOf(probeDistance * 0.6, probeDistance)) {
            val probe = player.position().add(nx * dist, 0.0, nz * dist)
            if (!hasPlatformFloor(level, probe.add(0.0, 0.25, 0.0), probe.add(0.0, -1.0, 0.0), player.y, maxDrop = 1.0)) {
                return true
            }
        }
        return false
    }

    private fun hasBonzoLaunchFloor(level: Level, player: LocalPlayer): Boolean {
        val eye = player.eyePosition
        return hasPlatformFloor(level, eye, eye.add(player.lookAngle.scale(4.0)), player.y)
    }

    private fun initiateBonzoLaunch(launchYaw: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // Auto-select Bonzo's Staff
        if (!selectBonzoStaff() || mc.gameMode == null) {
            stop()
            Config.activePathfindingPresetId = ""
            player.sendSystemMessage(Component.literal("§e[AsthoonLite] Route stopped: Bonzo's Staff is required in the hotbar."))
            return
        }

        // Launch towards the next waypoint in the route
        val nextIdx = currentNodeIndex + 1
        bonzoTargetNextIndex = nextIdx
        bonzoLaunchYaw = launchYaw

        // Movement keys: Hold forward (W) and Sprint on both 500 and 550 speed.
        // Advancing over the impact point ensures the blast catches the player from behind and boosts forward.
        mc.options.keyUp.setDown(true)
        mc.options.keyDown.setDown(false)
        mc.options.keyLeft.setDown(false)
        mc.options.keyRight.setDown(false)
        mc.options.keyShift.setDown(false)
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
        bonzoTicksRemaining = 8
    }

    private fun handleActiveBonzoState(points: List<PathPoint>) {
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

                aimRotation(player, bonzoLaunchYaw, 10f)

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

    internal fun bonzoLaunchPitch(recordedPitch: Float): Float =
        if (recordedPitch.isFinite() && recordedPitch > 0f) recordedPitch.coerceIn(45f, 65f) else 60f

    internal fun forwardSpeedBps(velocity: Vec3, yaw: Float): Double {
        val rad = Math.toRadians(-yaw.toDouble())
        return (velocity.x * sin(rad) + velocity.z * cos(rad)) * 20.0
    }

    internal fun hasForwardSprintMomentum(velocity: Vec3, yaw: Float, sprinting: Boolean): Boolean {
        val bpsH = velocity.horizontalDistance() * 20.0
        val forwardBps = forwardSpeedBps(velocity, yaw)
        return sprinting && bpsH >= 12.0 && forwardBps >= 12.0 &&
            forwardBps >= bpsH * cos(Math.toRadians(12.0))
    }

    internal fun bonzoLaunchReady(
        onPlatform: Boolean, distH: Double, isAtLaunchLedge: Boolean,
        hasMomentum: Boolean, alignedAim: Boolean, hasLaunchFloor: Boolean
    ): Boolean = onPlatform && (distH <= 1.5 || (isAtLaunchLedge && distH <= 2.2)) &&
        hasMomentum && alignedAim && hasLaunchFloor

    internal fun shouldJumpGap(
        onGround: Boolean, isLedge: Boolean, distH: Double,
        crouching: Boolean, preparingBonzo: Boolean, hasMomentum: Boolean
    ): Boolean = onGround && isLedge && distH > 1.4 && !crouching && !preparingBonzo && hasMomentum

    internal fun gapProbeDistance(velocity: Vec3): Double =
        (velocity.horizontalDistance() * 2.0 + 0.3).coerceIn(1.0, 2.2)

    internal fun hasPlatformFloor(level: BlockGetter, from: Vec3, to: Vec3, feetY: Double, maxDrop: Double = 0.5): Boolean {
        // COLLIDER reads actual physics shapes; empty context excludes interaction expansions.
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
        return hit.type == HitResult.Type.BLOCK && hit.direction == Direction.UP &&
            level.getFluidState(hit.blockPos).isEmpty &&
            hit.location.y >= feetY - maxDrop && hit.location.y <= feetY + 0.1
    }

    internal fun hasPlatformRunway(level: BlockGetter, position: Vec3, target: Vec3): Boolean {
        val steps = ceil(target.subtract(position).horizontalDistance() * 2.0).toInt().coerceAtLeast(1)
        return (0..steps).all { i ->
            val probe = position.lerp(target, i.toDouble() / steps)
            hasPlatformFloor(level, probe.add(0.0, 0.25, 0.0), probe.add(0.0, -0.6, 0.0), probe.y)
        }
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

    internal fun dampRotation(
        current: Float, target: Float, velocity: Float, rate: Double, maxStep: Float, wrap: Boolean = false
    ): Pair<Float, Float> {
        val delta = if (wrap) Mth.wrapDegrees(target - current) else target - current
        val goal = current + delta
        val decay = exp(-rate)
        val impulse = velocity - rate * delta
        val step = (delta + (-delta + impulse) * decay).toFloat().coerceIn(-maxStep, maxStep)
        val nextVelocity = ((velocity - rate * impulse) * decay).toFloat().coerceIn(-maxStep, maxStep)
        // Carry angular velocity across targets, but never coast past a settled target.
        return if (delta == 0f || (delta > 0f && step >= delta) || (delta < 0f && step <= delta))
            goal to 0f else (current + step) to nextVelocity
    }

    internal fun shouldInAirBrake(
        stationary: Boolean, distH: Double, vy: Double, forwardBps: Double, playerY: Double, targetY: Double
    ): Boolean {
        // A platform above the player is still ahead of the landing; retain forward momentum.
        if (playerY < targetY || forwardBps <= 0.0) return false
        if (stationary && distH < 3.8) return true
        if (vy >= 0.1 || distH >= 6.0 || forwardBps <= 5.0) return false
        val ticksToLand = ((playerY - targetY).coerceAtLeast(0.2) / abs(vy).coerceAtLeast(0.18)).coerceIn(1.0, 12.0)
        return forwardBps / 20.0 * ticksToLand > distH + 0.8
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
