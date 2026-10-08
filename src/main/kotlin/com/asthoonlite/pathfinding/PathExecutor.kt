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
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.Level
import net.minecraft.world.level.ClipContext
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

        GoldorRouteShortcut.cancelPending()
        activePreset = preset
        if (Config.lastPathfindingPresetId != preset.id) Config.lastPathfindingPresetId = preset.id
        currentNodeIndex = 0
        isActive = true
        bonzoState = BonzoState.IDLE
        bonzoTicksRemaining = 0
        bonzoLaunchYaw = 0f
        jumpTicksRemaining = 0
        tickCount = 0L
        resetSpecialNodeState()
        lastHandledNodeIndex = -1

        val mc = Minecraft.getInstance()
        if (mc.screen != null) {
            mc.setScreen(null)
        }
        if (!mc.mouseHandler.isMouseGrabbed) {
            mc.mouseHandler.grabMouse()
        }

        val player = mc.player
        player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStarted path execution: §e${preset.name} §7(${preset.points.size} nodes)")
        )
    }

    fun stop() {
        GoldorRouteShortcut.cancelPending()
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
        try {
            val mc = Minecraft.getInstance()
            mc.options.keyAttack.setDown(false)
            mc.gameMode?.stopDestroyBlock()
        } catch (_: Exception) {}
    }

    private fun releaseAllMovementKeys() {
        val mc = Minecraft.getInstance()
        try {
            mc.options.keyUp.setDown(false)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keyJump.setDown(false)
            mc.options.keyShift.setDown(false)
            mc.options.keySprint.setDown(false)
            mc.options.keyAttack.setDown(false)
            mc.gameMode?.stopDestroyBlock()
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

        // Keep cursor locked/grabbed in window while route is running
        if (isActive && mc.screen == null && !mc.mouseHandler.isMouseGrabbed) {
            mc.mouseHandler.grabMouse()
        }

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
            if (isActive) releaseAllMovementKeys()
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
        val isDeviceNode = nodeType == RouteNodeType.SIMON_SAYS || nodeType == RouteNodeType.ARROWS_ALIGN
        val isStationaryDest = isDeviceNode || nodeType == RouteNodeType.TIMEOUT || currentNodeIndex == points.size - 1
        val isSettled = isSettledAtNode(distH, distY, player.onGround(), player.deltaMovement.horizontalDistance())
        var isBonzoRunway = false

        // Read player speed (Hypixel Skyblock speed = attribute * 1000)
        val speedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
        val skyblockSpeed = speedAttr * 1000.0
        val isHighSpeed = skyblockSpeed > 400.0

        // 1. Handle Active Bonzo Staff Execution State Machine
        if (bonzoState != BonzoState.IDLE) {
            handleActiveBonzoState(points, isHighSpeed)
            return
        }

        val isApproachingBreak = nodeType == RouteNodeType.BREAK ||
            nextTarget?.nodeType() == RouteNodeType.BREAK ||
            points.drop(currentNodeIndex).take(3).any { it.nodeType() == RouteNodeType.BREAK && hypot(it.x - player.x, it.z - player.z) <= 7.0 }

        if (isApproachingBreak) {
            selectDungeonBreaker()
        }

        if (nextTarget?.nodeType() == RouteNodeType.BONZO_STAFF && distH < 5.0) {
            selectBonzoStaff()
        }

        // 2. Handle approaching a Bonzo Staff node
        if (nodeType == RouteNodeType.BONZO_STAFF) {
            val isPrecedingJump = points.getOrNull(currentNodeIndex - 1)?.nodeType() == RouteNodeType.JUMP
            val isTargetMidAir = level.getBlockState(BlockPos.containing(target.x, target.y - 0.5, target.z)).isAir
            val isAirborneBonzo = !player.onGround() || isPrecedingJump || isTargetMidAir

            // Auto-select Bonzo's Staff
            selectBonzoStaff()

            // Keep player firmly grounded during approach to Bonzo launch point ONLY for grounded runs;
            // never clear jump pulse or force grounding if this is an airborne / jump-initiated launch
            if (!isAirborneBonzo && bonzoState == BonzoState.IDLE && jumpTicksRemaining > 0) {
                jumpTicksRemaining = 0
                mc.options.keyJump.setDown(false)
            }

            // Bonzo node vertical elevation check: double height vertically (-1.5 to +2.5)
            // Allows seamless trigger when jumping through or descending through mid-air node
            val isAtNodeElev = if (isAirborneBonzo) {
                player.y >= target.y - 1.5 && player.y <= target.y + 2.5
            } else {
                player.y >= target.y - 0.5 && player.y <= target.y + 1.2
            }

            val toNextDx = if (nextTarget != null) nextTarget.x - player.x else target.x - player.x
            val toNextDz = if (nextTarget != null) nextTarget.z - player.z else target.z - player.z
            val destYaw = if (nextTarget != null) (-Math.toDegrees(atan2(toNextDx, toNextDz))).toFloat() else target.yaw

            val currentHeadingYaw = if (player.deltaMovement.horizontalDistance() > 0.08) {
                (-Math.toDegrees(atan2(player.deltaMovement.x, player.deltaMovement.z))).toFloat()
            } else if (distH > 0.3) {
                (-Math.toDegrees(atan2(target.x - player.x, target.z - player.z))).toFloat()
            } else {
                player.yRot
            }
            val destinationPos = if (nextTarget != null) Vec3(nextTarget.x, nextTarget.y, nextTarget.z) else Vec3(target.x, target.y, target.z)
            val launchParams = calculateBonzoLaunchParams(currentHeadingYaw, Vec3(player.x, player.y, player.z), destinationPos, target.pitch, target.yaw)

            val isAtLaunchLedge = isLedgeOrGapAhead(level, player, currentHeadingYaw) || isLedgeOrGapAhead(level, player, destYaw)
            val launchDistanceThreshold = if (isAirborneBonzo) 2.2 else if (isAtLaunchLedge) 2.2 else 1.5
            val isArrivedOnPlatform = distH <= launchDistanceThreshold
            val isFollowupLaunch = points.take(currentNodeIndex).any { it.nodeType() == RouteNodeType.BONZO_STAFF }
            val downwardFollowup = nextTarget != null && (nextTarget.y < target.y || isFollowupLaunch)

            val currentBpsH = player.deltaMovement.horizontalDistance() * 20.0
            val headingVec = if (abs(toNextDx) > 0.01 || abs(toNextDz) > 0.01) Vec3(toNextDx, 0.0, toNextDz).normalize() else Vec3(0.0, 0.0, 1.0)
            val forwardSpeed = Vec3(player.deltaMovement.x, 0.0, player.deltaMovement.z).dot(headingVec)

            val hasLaunchSpeed = if (launchParams.isRedirection) {
                currentBpsH in 6.5..10.5 || (currentBpsH >= 6.5 && isAtLaunchLedge && currentBpsH <= 11.5)
            } else if (isAirborneBonzo) {
                currentBpsH >= 5.5 && (forwardSpeed >= 0.25 || player.deltaMovement.horizontalDistance() >= 0.20)
            } else {
                hasBonzoRunwayVelocity(Vec3(toNextDx, 0.0, toNextDz), player.deltaMovement, downwardFollowup)
            }

            val launchPitch = launchParams.shotPitch
            val shotYaw = launchParams.shotYaw
            val hasGroundImpact = if (isAirborneBonzo) {
                val hit = level.clip(ClipContext(
                    player.eyePosition, player.eyePosition.add(Vec3.directionFromRotation(launchPitch, shotYaw).scale(7.0)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
                ))
                hit.type == HitResult.Type.BLOCK || launchPitch >= 40.0f
            } else {
                player.onGround() && !player.isInLava && !player.isInWater && isAtNodeElev && isArrivedOnPlatform &&
                    isBonzoGroundImpact(player.y, level.clip(ClipContext(
                        player.eyePosition, player.eyePosition.add(Vec3.directionFromRotation(launchPitch, shotYaw).scale(3.5)),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
                    )))
            }

            // Reserve the jump only once on the launch platform, not while climbing its approach.
            isBonzoRunway = !isAirborneBonzo && nextTarget != null && canReserveBonzoJump(player.y - target.y, hasGroundImpact)
            val canLaunch = (player.onGround() || isAirborneBonzo) && !player.isInLava && !player.isInWater &&
                            isAtNodeElev && isArrivedOnPlatform && hasLaunchSpeed && hasGroundImpact

            if (canLaunch) {
                initiateBonzoLaunch(points, isHighSpeed, launchParams)
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
        if (nodeType == RouteNodeType.SIMON_SAYS && isSettled) {
            releaseAllMovementKeys()
            player.setSprinting(false)
            jumpTicksRemaining = 0
            aimTowards(player, target, Vec3(110.5, 121.5, 93.5))
            if (F7Devices.isSimonCompleted()) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) finishRoute(preset)
            }
            return
        }

        if (nodeType == RouteNodeType.ARROWS_ALIGN && isSettled) {
            releaseAllMovementKeys()
            player.setSprinting(false)
            jumpTicksRemaining = 0
            aimTowards(player, target, Vec3(-2.0, 122.5, 77.0))
            if (ArrowAlignSolver.isSolved()) {
                currentNodeIndex++
                resetSpecialNodeState()
                if (currentNodeIndex >= points.size) finishRoute(preset)
            }
            return
        }

        if (nodeType == RouteNodeType.TIMEOUT && isSettled) {
            releaseAllMovementKeys()
            player.setSprinting(false)
            jumpTicksRemaining = 0
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

        val isBreakNode = nodeType == RouteNodeType.BREAK
        val breakBlockPos = if (isBreakNode) BlockPos.containing(target.x, target.y, target.z) else null
        val isBreakBlockSolid = breakBlockPos != null && !level.getBlockState(breakBlockPos).isAir

        val lookaheadBlend = if (distH < 2.8 && nextTarget != null && (nodeType == RouteNodeType.WALK || (isBreakNode && !isBreakBlockSolid))) {
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

        if (target.hasLookNode && (!isDeviceNode || isSettled)) {
            aimTowardsVec(player, Vec3(target.lookX, target.lookY, target.lookZ))
        } else if (isBreakNode && breakBlockPos != null && isBreakBlockSolid) {
            aimTowardsVec(player, Vec3.atCenterOf(breakBlockPos))
        } else if (nextTarget?.nodeType() == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(nextTarget.x, nextTarget.y, nextTarget.z)).isAir && distH < 3.5) {
            aimTowardsVec(player, Vec3.atCenterOf(BlockPos.containing(nextTarget.x, nextTarget.y, nextTarget.z)))
        } else if (nodeType == RouteNodeType.BONZO_STAFF && distH < 3.0) {
            // Smoothly pre-aim launch yaw and pitch as player approaches Bonzo node
            val approachHeading = if (player.deltaMovement.horizontalDistance() > 0.08) {
                (-Math.toDegrees(atan2(player.deltaMovement.x, player.deltaMovement.z))).toFloat()
            } else if (distH > 0.3) {
                (-Math.toDegrees(atan2(target.x - player.x, target.z - player.z))).toFloat()
            } else {
                player.yRot
            }
            val destinationPos = if (nextTarget != null) Vec3(nextTarget.x, nextTarget.y, nextTarget.z) else Vec3(target.x, target.y, target.z)
            val preLaunchParams = calculateBonzoLaunchParams(approachHeading, Vec3(player.x, player.y, player.z), destinationPos, target.pitch, target.yaw)

            val preYaw = if (target.hasLookNode) {
                val ldx = target.lookX - player.x
                val ldz = target.lookZ - player.z
                (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
            } else {
                preLaunchParams.shotYaw
            }
            val prePitch = if (target.hasLookNode) {
                if (target.pitch in 35.0f..88.0f) target.pitch else 79.0f
            } else {
                preLaunchParams.shotPitch
            }
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
        val offset = Vec3(dx, 0.0, dz)
        val landingFloor = BlockPos.containing(player.x, target.y - 0.1, player.z)
        val landingSupport = isStationaryDest && player.onGround() && !level.getBlockState(landingFloor).getCollisionShape(level, landingFloor).isEmpty
        val stationaryMotion = if (isStationaryDest) stationaryMovement(offset, player.deltaMovement, player.y - target.y, landingSupport) else null
        val isApproaching = stationaryMotion == null || isApproachingNode(offset, stationaryMotion, player.y - target.y, landingSupport)
        val isApproachingBonzoRunway = nodeType == RouteNodeType.BONZO_STAFF &&
            !player.isInLava && !player.isInWater &&
            distH <= 2.5 && player.y >= target.y - 1.5 && player.y <= target.y + 2.5

        if (stationaryMotion != null) {
            // Steer and brake in world coordinates, even while the camera turns toward a device.
            moveInDirection(player, stationaryMotion, !isCrouchNode && isApproaching && (isAirborne || distH > 2.5), isCrouchNode && !isAirborne)
        } else if (isAirborne) {
            moveInDirection(player, airborneMovement(offset, player.deltaMovement), true)
        } else if ((isBonzoRunway || isApproachingBonzoRunway) && nodeType == RouteNodeType.BONZO_STAFF) {
            val destinationPos = if (nextTarget != null) Vec3(nextTarget.x, nextTarget.y, nextTarget.z) else Vec3(target.x, target.y, target.z)
            val heading = if (player.deltaMovement.horizontalDistance() > 0.08) {
                (-Math.toDegrees(atan2(player.deltaMovement.x, player.deltaMovement.z))).toFloat()
            } else if (distH > 0.3) {
                (-Math.toDegrees(atan2(target.x - player.x, target.z - player.z))).toFloat()
            } else {
                player.yRot
            }
            val bonzoParams = calculateBonzoLaunchParams(heading, Vec3(player.x, player.y, player.z), destinationPos, target.pitch, target.yaw)
            val currentBpsH = player.deltaMovement.horizontalDistance() * 20.0

            if (bonzoParams.isRedirection) {
                val runwayVector = if (distH > 0.25) Vec3(target.x - player.x, 0.0, target.z - player.z)
                    else Vec3(-sin(Math.toRadians(heading.toDouble())), 0.0, cos(Math.toRadians(heading.toDouble())))
                if (currentBpsH > bonzoParams.targetBps + 2.5 && distH <= 1.5) {
                    mc.options.keyUp.setDown(false)
                    mc.options.keySprint.setDown(false)
                } else {
                    val wantsSprint = currentBpsH < bonzoParams.targetBps - 0.5
                    moveInDirection(player, runwayVector, wantsSprint)
                }
            } else {
                val motion = if (nextTarget != null) Vec3(nextTarget.x - player.x, 0.0, nextTarget.z - player.z)
                    else Vec3(target.x - player.x, 0.0, target.z - player.z)
                moveInDirection(player, motion, true)
            }
        } else if (isBonzoRunway && nextTarget != null) {
            moveInDirection(player, Vec3(nextTarget.x - player.x, 0.0, nextTarget.z - player.z), true)
        } else {
            // On ground: calculate movement vector towards target
            val targetMoveX = if (lookaheadBlend > 0.0 && nextTarget != null) aimX else target.x
            val targetMoveZ = if (lookaheadBlend > 0.0 && nextTarget != null) aimZ else target.z
            val moveYaw = (-Math.toDegrees(atan2(targetMoveX - player.x, targetMoveZ - player.z))).toFloat()
            val angleDiff = Mth.wrapDegrees(moveYaw - player.yRot)
            val rad = Math.toRadians(angleDiff.toDouble())
            val forward = cos(rad)
            val strafe = -sin(rad)

            if (target.hasLookNode) {
                // Explicit look-node: camera is decoupled, full WASD vectoring
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
                    val wantsSprint = forward > 0.2 || !isSharpTurn
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
        // Auto gap jump: trigger ONLY on WALK nodes when there is a drop ahead.
        // BONZO_STAFF nodes must NEVER auto-jump during approach — they must stay grounded for the staff blast!
        val canAutoGapJump = (nodeType == RouteNodeType.WALK) && player.onGround() && isLedge && distH > 1.4 && !isCrouchNode
        val isObstacleCollision = player.horizontalCollision && player.onGround()
        val isElevationStep = target.y > player.y + 0.35 && distH < 2.5 && player.onGround()

        // BONZO_STAFF nodes must NEVER auto-jump during approach!
        // The jump must occur strictly on the exact tick of initiateBonzoLaunch while grounded on the platform.
        val shouldAutoJump = if (nodeType == RouteNodeType.BONZO_STAFF) {
            false
        } else {
            canAutoGapJump || isObstacleCollision || isElevationStep
        }

        if (!isApproaching || isBonzoRunway || nodeType == RouteNodeType.BONZO_STAFF) {
            jumpTicksRemaining = 0
        } else {
            jumpTicksRemaining = nextAutoJumpPulse(shouldAutoJump, jumpTicksRemaining, mc.options.keyJump.isDown)
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

        // 7b. Break Node Handling: break target block with Dungeon Breaker while running
        val isCurrentBreakSolid = nodeType == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(target.x, target.y, target.z)).isAir
        val isNextBreakSolid = nextTarget?.nodeType() == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(nextTarget.x, nextTarget.y, nextTarget.z)).isAir

        val targetBreakPos = when {
            isCurrentBreakSolid -> BlockPos.containing(target.x, target.y, target.z)
            isNextBreakSolid && distH < 3.5 -> BlockPos.containing(nextTarget.x, nextTarget.y, nextTarget.z)
            else -> null
        }

        if (targetBreakPos != null) {
            selectDungeonBreaker()
            val center = Vec3.atCenterOf(targetBreakPos)
            val eye = player.eyePosition
            if (eye.distanceTo(center) <= 5.2) {
                val hitResult = mc.hitResult
                val dir = if (hitResult is BlockHitResult && hitResult.blockPos == targetBreakPos) {
                    hitResult.direction
                } else {
                    Direction.getApproximateNearest(eye.subtract(center))
                }
                player.swing(InteractionHand.MAIN_HAND)
                mc.gameMode?.startDestroyBlock(targetBreakPos, dir)
                mc.gameMode?.continueDestroyBlock(targetBreakPos, dir)
                mc.options.keyAttack.setDown(true)
            }
        } else if (nodeType != RouteNodeType.BREAK && nextTarget?.nodeType() != RouteNodeType.BREAK) {
            mc.options.keyAttack.setDown(false)
            mc.gameMode?.stopDestroyBlock()
        }

        // 8. Fluid Waypoint Transition: Speed-Scaled Arrival Check
        val canArriveElevation = if (isClimbingToNode) player.y >= target.y - 0.6 else distY < 2.5
        val isPrecedingAirborne = points.getOrNull(currentNodeIndex - 1)?.nodeType() in setOf(RouteNodeType.BONZO_STAFF, RouteNodeType.JUMP)
        val isNextStationary = nextTarget != null && (
            nextTarget.nodeType() == RouteNodeType.SIMON_SAYS ||
            nextTarget.nodeType() == RouteNodeType.ARROWS_ALIGN ||
            nextTarget.nodeType() == RouteNodeType.TERMINAL ||
            nextTarget.nodeType() == RouteNodeType.TIMEOUT
        )
        val requiresTouchdown = isPrecedingAirborne || isNextStationary

        val canAdvanceBreak = isBreakNode && !isBreakBlockSolid
        if ((advancesOnArrival(nodeType) || canAdvanceBreak) &&
            distH < arrivalThreshold && canArriveElevation &&
            (!requiresTouchdown || player.onGround()) &&
            (!isStationaryDest || isSettled)) {
            if (isBreakNode && nextTarget?.nodeType() != RouteNodeType.BREAK) {
                mc.options.keyAttack.setDown(false)
                mc.gameMode?.stopDestroyBlock()
            }
            currentNodeIndex++
            resetSpecialNodeState()
            if (currentNodeIndex >= points.size) {
                finishRoute(preset)
            }
        }
    }

    internal fun stationaryMovement(offset: Vec3, velocity: Vec3, heightAboveTarget: Double, landingSupport: Boolean = true): Vec3 {
        val horizontalVelocity = Vec3(velocity.x, 0.0, velocity.z)
        if (heightAboveTarget < -0.6 || !landingSupport) return airborneMovement(offset, velocity)
        if (offset.horizontalDistance() < 1.4) {
            return if (horizontalVelocity.horizontalDistance() > 0.06) horizontalVelocity.scale(-1.0) else Vec3.ZERO
        }
        // ponytail: fixed eight-tick braking lead; use surface drag if routes need ice support.
        val correction = offset.subtract(horizontalVelocity.scale(8.0))
        return if (correction.horizontalDistance() < 0.1) Vec3.ZERO else correction
    }

    internal fun airborneMovement(offset: Vec3, velocity: Vec3): Vec3 {
        val heading = offset.normalize()
        val horizontalVelocity = Vec3(velocity.x, 0.0, velocity.z)
        val sideways = horizontalVelocity.subtract(heading.scale(horizontalVelocity.dot(heading)))
        return heading.subtract(sideways.scale(8.0))
    }

    internal fun hasBonzoRunwayVelocity(offset: Vec3, velocity: Vec3, downwardFollowup: Boolean = false): Boolean {
        val heading = offset.normalize()
        val horizontalVelocity = Vec3(velocity.x, 0.0, velocity.z)
        val forwardSpeed = horizontalVelocity.dot(heading)
        val sideways = horizontalVelocity.subtract(heading.scale(forwardSpeed))
        // 12 bps initially/uphill; 8 bps on a later downward launch, at most 3 bps sideways.
        return forwardSpeed >= (if (downwardFollowup) 0.4 else 0.6) && sideways.horizontalDistance() <= 0.15
    }

    internal fun isBonzoGroundImpact(feetY: Double, hit: BlockHitResult): Boolean =
        hit.type == HitResult.Type.BLOCK && hit.direction == Direction.UP && hit.location.y in feetY - 1.0..feetY + 0.1

    internal fun canReserveBonzoJump(heightAboveNode: Double, groundImpact: Boolean): Boolean =
        groundImpact && abs(heightAboveNode) <= 0.2

    internal data class BonzoLaunchParams(
        val isRedirection: Boolean,
        val shotYaw: Float,
        val shotPitch: Float,
        val destYaw: Float,
        val targetBps: Double,
        val jumpOnFire: Boolean
    )

    internal fun calculateBonzoLaunchParams(
        playerHeadingYaw: Float,
        targetPos: Vec3,
        destinationPos: Vec3,
        recordedPitch: Float = 0f,
        fallbackYaw: Float = playerHeadingYaw
    ): BonzoLaunchParams {
        val destDx = destinationPos.x - targetPos.x
        val destDz = destinationPos.z - targetPos.z
        val destYaw = if (abs(destDx) > 0.01 || abs(destDz) > 0.01) {
            (-Math.toDegrees(atan2(destDx, destDz))).toFloat()
        } else {
            fallbackYaw
        }
        val turnAngle = Mth.wrapDegrees(destYaw - playerHeadingYaw)
        val absTurn = abs(turnAngle)

        return if (absTurn >= 30.0f) {
            val t = (absTurn / 90.0f).coerceIn(0.0f, 1.0f)
            val pitch = 79.0f - 24.5f * t
            val yawOffset = 16.5f * t * sign(turnAngle)
            val shotYaw = Mth.wrapDegrees(playerHeadingYaw + yawOffset)
            val targetBps = 12.0 - 4.3 * t.toDouble()
            BonzoLaunchParams(
                isRedirection = true,
                shotYaw = shotYaw,
                shotPitch = pitch,
                destYaw = destYaw,
                targetBps = targetBps,
                jumpOnFire = false
            )
        } else {
            val pitch = if (recordedPitch in 35.0f..88.0f) recordedPitch else 79.0f
            BonzoLaunchParams(
                isRedirection = false,
                shotYaw = destYaw,
                shotPitch = pitch,
                destYaw = destYaw,
                targetBps = 12.0,
                jumpOnFire = true
            )
        }
    }

    internal fun nextAutoJumpPulse(requested: Boolean, ticksRemaining: Int, wasJumpDown: Boolean): Int =
        if (requested && ticksRemaining <= 0 && !wasJumpDown) 1 else ticksRemaining

    private fun moveInDirection(player: LocalPlayer, motion: Vec3, sprint: Boolean, crouch: Boolean = false) {
        val options = Minecraft.getInstance().options
        val (forward, strafe) = movementInput(motion, player.yRot)
        options.keyUp.setDown(forward > 0.25)
        options.keyDown.setDown(forward < -0.25)
        options.keyLeft.setDown(strafe > 0.25)
        options.keyRight.setDown(strafe < -0.25)
        options.keyShift.setDown(crouch)
        val wantsSprint = sprint && forward > 0.25
        options.keySprint.setDown(wantsSprint)
        player.setSprinting(wantsSprint)
    }

    internal fun movementInput(motion: Vec3, yaw: Float): Pair<Double, Double> {
        val direction = motion.normalize()
        val radians = Math.toRadians(yaw.toDouble())
        return (-sin(radians) * direction.x + cos(radians) * direction.z) to
            (cos(radians) * direction.x + sin(radians) * direction.z)
    }

    internal fun isApproachingNode(offset: Vec3, motion: Vec3, heightAboveTarget: Double, landingSupport: Boolean = true): Boolean =
        (!landingSupport || offset.horizontalDistance() >= 1.4 || heightAboveTarget < -0.6) && motion.dot(offset) > 0.0

    internal fun advancesOnArrival(nodeType: RouteNodeType): Boolean =
        nodeType == RouteNodeType.WALK || nodeType == RouteNodeType.CROUCH

    internal fun hasLandedAtNode(distH: Double, distY: Double, onGround: Boolean): Boolean =
        onGround && distH < 1.4 && distY <= 0.6

    internal fun isSettledAtNode(distH: Double, distY: Double, onGround: Boolean, horizontalSpeed: Double): Boolean =
        hasLandedAtNode(distH, distY, onGround) && horizontalSpeed <= 0.06

    private fun isSettledAtNode(player: LocalPlayer, node: PathPoint): Boolean =
        isSettledAtNode(hypot(node.x - player.x, node.z - player.z), abs(node.y - player.y),
            player.onGround(), player.deltaMovement.horizontalDistance())

    internal fun canUseSimonSolver(routeActive: Boolean, nodeType: RouteNodeType?, settled: Boolean): Boolean =
        !routeActive || (nodeType == RouteNodeType.SIMON_SAYS && settled)

    fun canUseSimonSolver(): Boolean {
        if (!isActive) return true
        val node = activePreset?.points?.getOrNull(currentNodeIndex) ?: return false
        val player = Minecraft.getInstance().player ?: return false
        return canUseSimonSolver(isActive, node.nodeType(), isSettledAtNode(player, node))
    }

    fun onRenderFrame(deltaTracker: DeltaTracker) {
        if (!isActive) return
        val preset = activePreset ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val points = preset.points
        if (currentNodeIndex !in points.indices) return

        val target = points[currentNodeIndex]
        val nodeType = target.nodeType()
        val nextTarget = points.getOrNull(currentNodeIndex + 1)

        val isDeviceNode = nodeType == RouteNodeType.SIMON_SAYS || nodeType == RouteNodeType.ARROWS_ALIGN
        val isSettled = isSettledAtNode(player, target)
        if (nodeType == RouteNodeType.SIMON_SAYS && isSettled && F7Devices.isSimonAiming()) return

        val partialTick = deltaTracker.getGameTimeDeltaPartialTick(true)
        val dtTicks = deltaTracker.realtimeDeltaTicks.coerceIn(0.005f, 1.0f)

        // Intra-tick interpolated player position for current render frame
        val currentX = Mth.lerp(partialTick.toDouble(), player.xo, player.x)
        val currentY = Mth.lerp(partialTick.toDouble(), player.yo, player.y)
        val currentZ = Mth.lerp(partialTick.toDouble(), player.zo, player.z)
        val currentEyeY = currentY + player.eyeHeight

        val dx = target.x - currentX
        val dz = target.z - currentZ
        val distH = sqrt(dx * dx + dz * dz)

        val goalYaw: Float
        val goalPitch: Float
        val isFastAim: Boolean

        when {
            bonzoState == BonzoState.POST_FIRE_PROPEL -> {
                goalYaw = bonzoLaunchYaw
                goalPitch = 10.0f
                isFastAim = true
            }
            (nodeType == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(target.x, target.y, target.z)).isAir) ||
            (nextTarget?.nodeType() == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(nextTarget.x, nextTarget.y, nextTarget.z)).isAir && distH < 3.5) -> {
                val bPos = if (nodeType == RouteNodeType.BREAK && !level.getBlockState(BlockPos.containing(target.x, target.y, target.z)).isAir) {
                    BlockPos.containing(target.x, target.y, target.z)
                } else {
                    BlockPos.containing(nextTarget!!.x, nextTarget.y, nextTarget.z)
                }
                val bCenter = Vec3.atCenterOf(bPos)
                val bdx = bCenter.x - currentX
                val bdy = bCenter.y - currentEyeY
                val bdz = bCenter.z - currentZ
                val bdistH = sqrt(bdx * bdx + bdz * bdz)
                goalYaw = (-Math.toDegrees(atan2(bdx, bdz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(bdy, bdistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = true
            }
            nodeType == RouteNodeType.BONZO_STAFF && distH < 3.0 -> {
                val renderHeading = if (player.deltaMovement.horizontalDistance() > 0.08) {
                    (-Math.toDegrees(atan2(player.deltaMovement.x, player.deltaMovement.z))).toFloat()
                } else if (distH > 0.3) {
                    (-Math.toDegrees(atan2(target.x - currentX, target.z - currentZ))).toFloat()
                } else {
                    player.yRot
                }
                val destPos = if (nextTarget != null) Vec3(nextTarget.x, nextTarget.y, nextTarget.z) else Vec3(target.x, target.y, target.z)
                val params = calculateBonzoLaunchParams(renderHeading, Vec3(currentX, currentY, currentZ), destPos, target.pitch, target.yaw)
                goalYaw = if (target.hasLookNode) {
                    val ldx = target.lookX - currentX
                    val ldz = target.lookZ - currentZ
                    (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
                } else {
                    params.shotYaw
                }
                goalPitch = if (target.hasLookNode) {
                    if (target.pitch in 35.0f..88.0f) target.pitch else 79.0f
                } else {
                    params.shotPitch
                }
                isFastAim = true
            }
            target.hasLookNode && (!isDeviceNode || isSettled) -> {
                val ldx = target.lookX - currentX
                val ldy = target.lookY - currentEyeY
                val ldz = target.lookZ - currentZ
                val ldistH = sqrt(ldx * ldx + ldz * ldz)
                goalYaw = (-Math.toDegrees(atan2(ldx, ldz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(ldy, ldistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            nodeType == RouteNodeType.SIMON_SAYS && isSettled -> {
                val simonPos = Vec3(110.5, 121.5, 93.5)
                val sdx = simonPos.x - currentX
                val sdy = simonPos.y - currentEyeY
                val sdz = simonPos.z - currentZ
                val sdistH = sqrt(sdx * sdx + sdz * sdz)
                goalYaw = (-Math.toDegrees(atan2(sdx, sdz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(sdy, sdistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            nodeType == RouteNodeType.ARROWS_ALIGN && isSettled -> {
                val arrowPos = Vec3(-2.0, 122.5, 77.0)
                val adx = arrowPos.x - currentX
                val ady = arrowPos.y - currentEyeY
                val adz = arrowPos.z - currentZ
                val adistH = sqrt(adx * adx + adz * adz)
                goalYaw = (-Math.toDegrees(atan2(adx, adz))).toFloat()
                goalPitch = (-Math.toDegrees(atan2(ady, adistH))).toFloat().coerceIn(-89f, 89f)
                isFastAim = false
            }
            else -> {
                val isBreakNode = nodeType == RouteNodeType.BREAK
                val breakAir = isBreakNode && level.getBlockState(BlockPos.containing(target.x, target.y, target.z)).isAir
                val lookaheadBlend = if (distH < 2.8 && nextTarget != null && (nodeType == RouteNodeType.WALK || breakAir)) {
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
                val aimDx = aimX - currentX
                val aimDz = aimZ - currentZ
                val aimDistH = sqrt(aimDx * aimDx + aimDz * aimDz)
                goalYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
                val targetEyeY = target.y + 1.2
                val aimDy = targetEyeY - currentEyeY
                val pitchDistH = aimDistH.coerceAtLeast(3.5)
                goalPitch = (-Math.toDegrees(atan2(aimDy, pitchDistH))).toFloat().coerceIn(-15.0f, 15.0f)
                isFastAim = false
            }
        }

        // Frame-rate independent continuous exponential smoothing matching 8b6a1f9 rates
        val rate = if (isFastAim) 0.45 else 0.30
        val alpha = (1.0 - exp(-rate * dtTicks.toDouble())).toFloat().coerceIn(0.01f, 0.90f)
        val deltaYaw = Mth.wrapDegrees(goalYaw - player.yRot)
        val deltaPitch = (goalPitch - player.xRot)

        val maxFrameYawStep = (if (isFastAim) 18.0f else 12.0f) * dtTicks
        val maxFramePitchStep = (if (isFastAim) 18.0f else 6.0f) * dtTicks

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

    private fun initiateBonzoLaunch(points: List<PathPoint>, isHighSpeed: Boolean, launchParams: BonzoLaunchParams? = null) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val currentNode = points[currentNodeIndex]

        // Auto-select Bonzo's Staff
        selectBonzoStaff()

        // Launch towards the next waypoint in the route
        val nextIdx = currentNodeIndex + 1
        bonzoTargetNextIndex = nextIdx
        val destination = points.getOrNull(nextIdx) ?: currentNode

        val distH = hypot(currentNode.x - player.x, currentNode.z - player.z)
        val params = launchParams ?: run {
            val heading = if (player.deltaMovement.horizontalDistance() > 0.08) {
                (-Math.toDegrees(atan2(player.deltaMovement.x, player.deltaMovement.z))).toFloat()
            } else if (distH > 0.3) {
                (-Math.toDegrees(atan2(currentNode.x - player.x, currentNode.z - player.z))).toFloat()
            } else {
                player.yRot
            }
            calculateBonzoLaunchParams(heading, Vec3(player.x, player.y, player.z), Vec3(destination.x, destination.y, destination.z), currentNode.pitch, currentNode.yaw)
        }

        bonzoLaunchYaw = params.destYaw

        player.yRotO = params.shotYaw
        player.xRotO = params.shotPitch
        cameraYawVelocity = 0f
        cameraPitchVelocity = 0f
        player.yRot = params.shotYaw
        player.xRot = params.shotPitch

        // Movement keys:
        if (params.isRedirection) {
            // Redirection launch: momentary neutral input on fire tick so forward sprint does not fight lateral knockback
            mc.options.keyUp.setDown(false)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keySprint.setDown(false)
            jumpTicksRemaining = 0
            mc.options.keyJump.setDown(false)
        } else {
            // Straight launch: hold forward W and sprint, jump on fire
            mc.options.keyUp.setDown(true)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keySprint.setDown(true)
            val shouldJump = params.jumpOnFire && player.onGround()
            jumpTicksRemaining = if (shouldJump) 3 else 0
            mc.options.keyJump.setDown(shouldJump)
        }

        // Fire immediately so projectile hits the platform floor ahead/under player in time
        player.swing(InteractionHand.MAIN_HAND)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        PathfindCapture.notifyBonzoShot("AUTO_EXECUTOR")

        bonzoState = BonzoState.POST_FIRE_PROPEL
        bonzoTicksRemaining = 8
    }

    private fun handleActiveBonzoState(points: List<PathPoint>, isHighSpeed: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        when (bonzoState) {
            BonzoState.POST_FIRE_PROPEL -> {
                val destPoint = points.getOrNull(bonzoTargetNextIndex)
                val offset = if (destPoint != null) Vec3(destPoint.x - player.x, 0.0, destPoint.z - player.z)
                    else Vec3(-sin(Math.toRadians(bonzoLaunchYaw.toDouble())), 0.0, cos(Math.toRadians(bonzoLaunchYaw.toDouble())))
                moveInDirection(player, airborneMovement(offset, player.deltaMovement), true)

                // Manage jump pulse cleanly so player never bunny hops on landing
                if (jumpTicksRemaining > 0) {
                    mc.options.keyJump.setDown(true)
                    jumpTicksRemaining--
                } else {
                    mc.options.keyJump.setDown(false)
                }

                // Smoothly recover camera pitch from ground (79°) back up to eye level (10°)
                // and steer towards destination waypoint during flight
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

    internal fun isDungeonBreakerItem(item: ItemStack): Boolean {
        if (item.isEmpty) return false
        val name = item.hoverName.string
        if (name.contains("Breaker", ignoreCase = true)) return true
        if (name.contains("Stonks", ignoreCase = true)) return true
        if (item.item.descriptionId.contains("pickaxe", ignoreCase = true)) return true
        if (name.contains("Pickaxe", ignoreCase = true)) return true
        return false
    }

    internal fun selectDungeonBreaker(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val inventory = player.inventory

        // Check held item first
        if (isDungeonBreakerItem(inventory.selectedItem)) {
            return true
        }

        // 1. Search hotbar for an item specifically named "Breaker"
        for (slot in 0..8) {
            val item = inventory.getItem(slot)
            if (item.hoverName.string.contains("Breaker", ignoreCase = true)) {
                inventory.selectedSlot = slot
                return true
            }
        }

        // 2. Fallback: search hotbar for Stonks or any pickaxe
        for (slot in 0..8) {
            val item = inventory.getItem(slot)
            if (isDungeonBreakerItem(item)) {
                inventory.selectedSlot = slot
                return true
            }
        }

        return false
    }

    internal fun isBreakNodeComplete(isAir: Boolean): Boolean = isAir

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
