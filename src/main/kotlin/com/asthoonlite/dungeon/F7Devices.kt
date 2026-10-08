package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.utils.InputCapture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*
import kotlin.random.Random

/**
 * Floor 7 devices:
 * - Auto I4 / Sharpshooter: Fixed emerald positions with dev-room targeting.
 * - Auto Simon Says: Authentic human-mode solver that reconstructs the 5-round
 *   pattern from sea-lantern updates, turning the player's camera smoothly to look
 *   at each button with authentic cubic-eased angular velocities, settling onto the
 *   button face, and clicking with natural human timing.
 */
object F7Devices {
    private val devBlocks = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50)
    )

    private val ssObsidians = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(111, y, z) } }
    private val ssButtons = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }
    private val ssStart = BlockPos(110, 121, 91)
    private val ssDeviceCenter = Vec3(110.5, 121.5, 93.5)

    private var stormStarted = false
    private val emeraldSeen = HashSet<BlockPos>()
    private var activeEmerald: BlockPos? = null
    private var lastI4Click = 0L

    private var lastSSClick = 0L
    private var lastSSState = BooleanArray(ssObsidians.size)
    private var ssLastClientTick = -1L
    private var isSkipping = false
    private var skipOver = false
    private var ssStartClicked = false
    private var startClicksDone = 0
    private var nextStartClickAt = 0L
    private val ssSequence = ArrayList<BlockPos>()
    private var clickIndex = 0
    private var brokenButton: BlockPos? = null
    private var startingButton: BlockPos? = null
    private val ssButtonCheck = BlockPos(110, 120, 93)
    private val simonLifecycle = SimonDeviceLifecycle()

    // ── Human Aim Controller State ──────────────────────────────────────────
    enum class AimState {
        IDLE,
        TURNING,
        SETTLED,
        POST_CLICK_PAUSE
    }

    private var aimState = AimState.IDLE
    private var aimTargetVec: Vec3? = null
    private var aimTargetBlock: BlockPos? = null
    private var aimStartYaw = 0f
    private var aimStartPitch = 0f
    private var aimDestYaw = 0f
    private var aimDestPitch = 0f
    private var aimStartTime = 0L
    private var aimDurationMs = 0L
    private var aimSettledUntil = 0L
    private var postClickPauseUntil = 0L

    // Organic varied movement parameters per aim
    private var aimArcAmplitudeYaw = 0f
    private var aimArcAmplitudePitch = 0f
    private var aimArcSkew = 0f
    private var aimEasePowerYaw = 3.0f
    private var aimEasePowerPitch = 3.0f
    private var aimYawLead = 0f
    private var aimTremorAmpYaw = 0f
    private var aimTremorAmpPitch = 0f
    private var aimTremorFreq1 = 11.0f
    private var aimTremorFreq2 = 22.0f
    private var aimTremorPhase1 = 0f
    private var aimTremorPhase2 = 0f
    private var aimOvershootYaw = 0f
    private var aimOvershootPitch = 0f

    private var skipWaitTargetVec: Vec3? = null

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) onChat(ChatFormatting.stripFormatting(text.string) ?: "")
            true
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { _ -> onRenderFrame() }
    }

    private fun onChat(msg: String) {
        if (msg == "[BOSS] Storm: I should have known that I stood no chance.") {
            stormStarted = true
            emeraldSeen.clear()
            activeEmerald = null
        }
        if (msg.contains("Goldor: Who dares trespass into my domain?")) {
            resetSimonState(clearCompletion = true)
        }
        if (DungeonContext.inDungeon) {
            val player = Minecraft.getInstance().player
            if (player != null && simonLifecycle.completeFromMessage(msg, player.name.string,
                    player.distanceToSqr(ssDeviceCenter) <= 36.0)) {
                completeSimon("server_device_message")
            }
        }
    }

    private fun onRenderFrame() {
        if (!DungeonContext.inDungeon || !Config.autoSimonSaysEnabled || simonLifecycle.completed) return
        val player = Minecraft.getInstance().player ?: return
        updateCameraAim(player, System.currentTimeMillis())
    }

    private fun tick() {
        if (!DungeonContext.inDungeon) {
            resetDeviceStateOnly()
            return
        }
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        // Recording a hand-solved device must still observe its lifecycle
        // when all automatic input features are disabled.
        if (Config.autoSimonSaysEnabled || Config.blockWrongDeviceClicks || InputCapture.isCapturing) {
            observeSimonBoard(level, player)
        }
        if (!Config.inAnyDeviceFeatureEnabled() && !Config.blockWrongDeviceClicks) {
            cancelSimonAim()
            return
        }

        if (Config.autoI4Enabled && stormStarted) tickI4(mc, level, player)
        if (Config.blockWrongDeviceClicks || Config.autoSimonSaysEnabled) updateSimonSequence(level, player)
        if (Config.autoSimonSaysEnabled) tickSimon(mc, level, player)
    }

    private fun tickI4(mc: Minecraft, level: net.minecraft.client.multiplayer.ClientLevel, player: net.minecraft.client.player.LocalPlayer) {
        if (player.distanceToSqr(Vec3(63.0, 127.0, 35.0)) > 20.0) return
        val emerald = devBlocks.firstOrNull { level.getBlockState(it).block == Blocks.EMERALD_BLOCK }
            ?: return
        if (emerald in emeraldSeen && activeEmerald == emerald) return

        activeEmerald = emerald
        emeraldSeen.add(emerald)
        if (System.currentTimeMillis() - lastI4Click < 45L) return
        shootI4(mc, player, emerald)
    }

    private fun shootI4(mc: Minecraft, player: net.minecraft.client.player.LocalPlayer, pos: BlockPos) {
        val i = devBlocks.indexOf(pos).coerceAtLeast(0)
        val col = i % 3
        val row = i / 3
        val targetX = when (col) {
            0 -> 67.5
            2 -> 65.5
            else -> 66.5
        }
        val target = Vec3(targetX, 131.0 - 2.0 * row, 50.0)
        lookAtDirect(player, target)
        val hit = BlockHitResult(target, Direction.SOUTH, pos, false)
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        lastI4Click = System.currentTimeMillis()
    }

    internal fun shortestAngleDist(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    internal fun easeInOutCubic(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return if (clamped < 0.5f) 4f * clamped * clamped * clamped
        else 1f - (-2f * clamped + 2f).let { it * it * it } / 2f
    }

    internal fun easeNatural(t: Float, power: Float = 3.0f): Float {
        val clamped = t.coerceIn(0f, 1f)
        return if (clamped < 0.45f) {
            (clamped / 0.45f).pow(power) * 0.45f
        } else {
            1f - ((1f - clamped) / 0.55f).pow(power) * 0.55f
        }
    }

    internal fun aimDuration(angleDist: Float, slow: Boolean, fastMode: Boolean = false): Long =
        if (fastMode) {
            if (slow) (170L + (angleDist * 1.3f).toLong() + Random.nextLong(-15L, 20L)).coerceIn(180L, 260L)
            else (38L + (angleDist * 0.95f).toLong() + Random.nextLong(-5L, 10L)).coerceIn(40L, 80L)
        } else {
            if (slow) (320L + (angleDist * 3.0f).toLong() + Random.nextLong(-25L, 30L)).coerceIn(360L, 480L)
            else (70L + (angleDist * 1.8f).toLong() + Random.nextLong(-8L, 18L)).coerceIn(75L, 145L)
        }

    internal fun aimDuration(angleDist: Float, slow: Boolean): Long =
        aimDuration(angleDist, slow, fastMode = false)

    private fun updateCameraAim(player: net.minecraft.client.player.LocalPlayer, now: Long) {
        if (simonLifecycle.completed || player.distanceToSqr(ssDeviceCenter) > 36.0) {
            cancelSimonAim()
            return
        }
        val target = aimTargetBlock
        val level = Minecraft.getInstance().level ?: return
        if (target != null) {
            if (target == ssStart) {
                if (level.getBlockState(target).block != Blocks.STONE_BUTTON) {
                    cancelSimonAim()
                    return
                }
            } else if (target in ssButtons) {
                if (target == brokenButton) {
                    cancelSimonAim()
                    return
                }
                val boardReady = level.getBlockState(ssButtonCheck).block == Blocks.STONE_BUTTON
                if (boardReady && aimState != AimState.TURNING && level.getBlockState(target).block != Blocks.STONE_BUTTON) {
                    cancelSimonAim()
                    return
                }
            } else {
                cancelSimonAim()
                return
            }
        }
        when (aimState) {
            AimState.TURNING -> {
                val elapsed = now - aimStartTime
                val t = (elapsed.toFloat() / aimDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

                // Asymmetric arc envelope: smooth bell curve with subtle skew
                val envelope = (sin(t * Math.PI.toFloat()) * (1f + aimArcSkew * (t - 0.5f))).coerceAtLeast(0f)

                // Decoupled yaw and pitch easing with slight phase lead/lag
                val tYaw = (t + aimYawLead * envelope).coerceIn(0f, 1f)
                val tPitch = (t - aimYawLead * envelope).coerceIn(0f, 1f)
                val easedYaw = easeNatural(tYaw, aimEasePowerYaw)
                val easedPitch = easeNatural(tPitch, aimEasePowerPitch)

                val dy = shortestAngleDist(aimStartYaw, aimDestYaw)
                val dp = aimDestPitch - aimStartPitch

                // Path arc curvature
                val arcYaw = aimArcAmplitudeYaw * envelope
                val arcPitch = aimArcAmplitudePitch * envelope

                // Multi-frequency physiological motor noise
                val tremor1 = sin(t * aimTremorFreq1 + aimTremorPhase1)
                val tremor2 = sin(t * aimTremorFreq2 + aimTremorPhase2) * 0.4f
                val noiseYaw = aimTremorAmpYaw * envelope * (tremor1 + tremor2)
                val noisePitch = aimTremorAmpPitch * envelope * (tremor1 + tremor2)

                // Subtle organic deceleration micro-adjustment in final 25%
                val overYaw: Float
                val overPitch: Float
                if (t > 0.75f) {
                    val st = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
                    val overEnv = sin(st * Math.PI.toFloat()) * (1f - st)
                    overYaw = aimOvershootYaw * overEnv
                    overPitch = aimOvershootPitch * overEnv
                } else {
                    overYaw = 0f
                    overPitch = 0f
                }

                player.yRot = aimStartYaw + dy * easedYaw + arcYaw + noiseYaw + overYaw
                player.xRot = aimStartPitch + dp * easedPitch + arcPitch + noisePitch + overPitch

                if (t >= 1f) {
                    player.yRot = aimDestYaw
                    player.xRot = aimDestPitch
                    aimState = AimState.SETTLED
                    val settleDelay = if (Config.autoSimonSaysFast) {
                        Random.nextLong(4L, 12L)
                    } else {
                        Random.nextLong(20L, 40L)
                    }
                    aimSettledUntil = now + settleDelay
                    if (aimTargetBlock == ssStart) {
                        nextStartClickAt = aimSettledUntil
                    }
                }
            }
            AimState.SETTLED -> {
                // Organic resting hand micro-sway while waiting / settled
                val settleTime = (now - (aimStartTime + aimDurationMs)).coerceAtLeast(0L).toFloat()
                val swayYaw = sin(settleTime * 0.007f + aimTremorPhase1) * 0.025f
                val swayPitch = cos(settleTime * 0.009f + aimTremorPhase2) * 0.02f
                player.yRot = aimDestYaw + swayYaw
                player.xRot = aimDestPitch + swayPitch
            }
            AimState.POST_CLICK_PAUSE -> {
                if (now >= postClickPauseUntil) {
                    aimState = AimState.IDLE
                }
            }
            AimState.IDLE -> {}
        }
    }

    private fun startAim(player: net.minecraft.client.player.LocalPlayer, pos: BlockPos, slow: Boolean = false) {
        val offY = if (slow) Random.nextDouble(-0.16, 0.16) else Random.nextDouble(-0.085, 0.085)
        val offZ = if (slow) Random.nextDouble(-0.16, 0.16) else Random.nextDouble(-0.085, 0.085)
        val target = Vec3(110.875, pos.y + 0.52 + offY, pos.z + 0.52 + offZ)
        startAimVec(player, target, pos, slow)
    }

    private fun startAimVec(
        player: net.minecraft.client.player.LocalPlayer,
        target: Vec3,
        pos: BlockPos?,
        slow: Boolean = false
    ) {
        val fastMode = Config.autoSimonSaysFast

        val eye = player.eyePosition
        val d = target.subtract(eye)
        val horizontal = sqrt(d.x * d.x + d.z * d.z)
        val destYaw = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        val destPitch = Math.toDegrees(atan2(-d.y, horizontal)).toFloat()

        val curYaw = player.yRot
        val curPitch = player.xRot
        val dy = shortestAngleDist(curYaw, destYaw)
        val dp = destPitch - curPitch
        val angleDist = hypot(dy, dp)

        val duration = aimDuration(angleDist, slow, fastMode)

        // Path curvature (arc/bow) perpendicular to travel direction:
        // A natural wrist/arm sweep curves off the straight line.
        val arcMagnitude = if (slow) {
            if (fastMode) Random.nextFloat() * 3.4f - 1.7f
            else Random.nextFloat() * 5.8f - 2.9f
        } else {
            val base = if (fastMode) 1.2f else 2.2f
            (Random.nextFloat() * (2 * base) - base) * (angleDist / 16f).coerceIn(0.4f, 1.6f)
        }

        // Perpendicular vector (-dp, dy) normalized
        if (angleDist > 0.01f) {
            val perpX = -dp / angleDist
            val perpY = dy / angleDist
            aimArcAmplitudeYaw = perpX * arcMagnitude
            aimArcAmplitudePitch = perpY * arcMagnitude
        } else {
            aimArcAmplitudeYaw = 0f
            aimArcAmplitudePitch = 0f
        }

        // Asymmetric arc envelope skew (-0.35 to +0.35)
        aimArcSkew = Random.nextFloat() * 0.7f - 0.35f

        // Decoupled easing powers: yaw and pitch accelerate/decelerate independently
        aimEasePowerYaw = if (fastMode) Random.nextFloat() * 0.6f + 2.3f else Random.nextFloat() * 0.9f + 2.5f
        aimEasePowerPitch = if (fastMode) Random.nextFloat() * 0.6f + 2.4f else Random.nextFloat() * 0.9f + 2.6f

        // Axis phase lead/lag: either wrist leads fingers or fingers lead wrist slightly
        aimYawLead = Random.nextFloat() * 0.10f - 0.05f

        // Multi-frequency neuromuscular tremor
        val tremorScale = if (fastMode) 0.035f else 0.075f
        aimTremorAmpYaw = (Random.nextFloat() * 2f - 1f) * tremorScale
        aimTremorAmpPitch = (Random.nextFloat() * 2f - 1f) * tremorScale
        aimTremorFreq1 = Random.nextFloat() * 4.0f + 9.0f   // 9 to 13 Hz primary sway
        aimTremorFreq2 = Random.nextFloat() * 8.0f + 18.0f  // 18 to 26 Hz secondary micro-fluctuation
        aimTremorPhase1 = Random.nextFloat() * (2 * Math.PI.toFloat())
        aimTremorPhase2 = Random.nextFloat() * (2 * Math.PI.toFloat())

        // Subtle organic deceleration micro-adjustment (overshoot/dampening)
        val overScale = if (fastMode) 0.09f else 0.18f
        aimOvershootYaw = (Random.nextFloat() * 2f - 1f) * overScale * (angleDist / 20f).coerceIn(0.3f, 1.0f)
        aimOvershootPitch = (Random.nextFloat() * 2f - 1f) * overScale * (angleDist / 20f).coerceIn(0.3f, 1.0f)

        aimTargetVec = target
        aimTargetBlock = pos
        aimStartYaw = curYaw
        aimStartPitch = curPitch
        aimDestYaw = destYaw
        aimDestPitch = destPitch
        aimStartTime = System.currentTimeMillis()
        aimDurationMs = duration
        aimState = AimState.TURNING
    }

    /** Real-time packet-driven block updates for instant, zero-delay SS sequence tracking. */
    fun onBlockUpdate(pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState) {
        if (!DungeonContext.inDungeon ||
            (!Config.autoSimonSaysEnabled && !Config.blockWrongDeviceClicks && !InputCapture.isCapturing)) return
        val player = Minecraft.getInstance().player ?: return
        if (player.distanceToSqr(ssDeviceCenter) > 36.0) return
        if (simonLifecycle.completed) return

        val block = state.block
        if (pos == aimTargetBlock && block != Blocks.STONE_BUTTON && (pos == ssStart || (simonLifecycle.completed || !simonLifecycle.active))) cancelSimonAim()
        if (pos == ssStart && block == Blocks.STONE_BUTTON &&
            state.getValue(net.minecraft.world.level.block.ButtonBlock.POWERED)) {
            simonLifecycle.observeRun(System.nanoTime())
        }
        if (pos in ssObsidians && block == Blocks.SEA_LANTERN) {
            simonLifecycle.observeRun(System.nanoTime())
            val button = pos.west()
            if (isSkipping && brokenButton == null) {
                // The very first lantern in skip mode is the button that breaks off
                brokenButton = button
            } else if (button != brokenButton && !ssSequence.contains(button)) {
                if (startingButton == null) {
                    startingButton = button
                    skipOver = true
                }
                if (ssSequence.size < 5) {
                    ssSequence.add(button)
                }
            }
        } else if (pos == ssButtonCheck) {
            if (block == Blocks.AIR) {
                clickIndex = 0
            } else if (block == Blocks.STONE_BUTTON) {
                if (!isSkipping || ssSequence.isNotEmpty()) {
                    skipOver = true
                }
            }
        }
    }

    private fun updateSimonSequence(level: net.minecraft.client.multiplayer.ClientLevel, player: net.minecraft.client.player.LocalPlayer) {
        if (simonLifecycle.completed || player.distanceToSqr(ssDeviceCenter) > 36.0) return

        if (level.getBlockState(ssButtonCheck).block == Blocks.AIR) {
            clickIndex = 0
            if (ssSequence.isEmpty()) {
                lastSSState = BooleanArray(ssObsidians.size)
                // An empty board also appears between rounds. A new local
                // start or a new dungeon run rearms the start button.
                if (!simonLifecycle.active) {
                    ssStartClicked = false
                    isSkipping = false
                    skipOver = false
                    brokenButton = null
                    startingButton = null
                    skipWaitTargetVec = null
                }
            }
            return
        }
        if (level.getBlockState(ssButtonCheck).block == Blocks.STONE_BUTTON) {
            if (!isSkipping || ssSequence.isNotEmpty()) {
                skipOver = true
            }
        }

        val now = BooleanArray(ssObsidians.size)
        ssObsidians.forEachIndexed { index, pos ->
            now[index] = level.getBlockState(pos).block == Blocks.SEA_LANTERN
        }

        now.forEachIndexed { index, active ->
            if (!active || lastSSState[index]) return@forEachIndexed
            val button = ssObsidians[index].west()
            if (isSkipping && brokenButton == null) {
                brokenButton = button
            } else if (button != brokenButton && !ssSequence.contains(button)) {
                if (startingButton == null) {
                    startingButton = button
                    skipOver = true
                }
                if (ssSequence.size < 5) {
                    ssSequence.add(button)
                }
            }
        }
        lastSSState = now
    }

    private fun tickSimon(mc: Minecraft, level: net.minecraft.client.multiplayer.ClientLevel, player: net.minecraft.client.player.LocalPlayer) {
        if (simonLifecycle.completed || player.distanceToSqr(ssDeviceCenter) > 36.0) {
            cancelSimonAim()
            return
        }

        val now = System.currentTimeMillis()
        updateCameraAim(player, now)

        val fastMode = Config.autoSimonSaysFast

        // 1. If currently settled on start button and performing 3 start clicks:
        if (aimTargetBlock == ssStart && aimState == AimState.SETTLED) {
            if (now >= nextStartClickAt && level.getBlockState(ssStart).block == Blocks.STONE_BUTTON) {
                val targetVec = aimTargetVec ?: Vec3(110.875, ssStart.y + 0.52, ssStart.z + 0.52)
                val hit = BlockHitResult(targetVec, Direction.WEST, ssStart, false)
                if (!simonLifecycle.active) simonLifecycle.start(System.nanoTime())
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                lastSSClick = now
                startClicksDone++
                if (startClicksDone >= 3) {
                    ssStartClicked = true
                    isSkipping = true
                    skipOver = false
                    startClicksDone = 0
                    aimState = AimState.POST_CLICK_PAUSE
                    postClickPauseUntil = now + (if (fastMode) Random.nextLong(10L, 25L) else Random.nextLong(20L, 45L))
                    val randY = 121.75 + Random.nextDouble(-0.65, 0.65)
                    val randZ = 93.55 + Random.nextDouble(-0.75, 0.75)
                    skipWaitTargetVec = Vec3(110.875, randY, randZ)
                } else {
                    // Cadence between start clicks: fast mode ~15 CPS (55-75ms), normal ~9-10 CPS (95-115ms)
                    val delay = if (fastMode) Random.nextLong(55L, 75L) else Random.nextLong(95L, 115L)
                    nextStartClickAt = now + delay
                }
            }
            return
        }

        // 2. If settled on a sequence button:
        if (aimState == AimState.SETTLED && now >= aimSettledUntil) {
            val targetBlock = aimTargetBlock
            val targetVec = aimTargetVec
            if (targetBlock != null && targetVec != null && level.getBlockState(targetBlock).block == Blocks.STONE_BUTTON) {
                val boardReady = level.getBlockState(ssButtonCheck).block == Blocks.STONE_BUTTON
                val expected = ssSequence.getOrNull(clickIndex)
                if (boardReady && expected != null && targetBlock == expected && targetBlock != brokenButton) {
                    val hit = BlockHitResult(targetVec, Direction.WEST, targetBlock, false)
                    mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                    lastSSClick = now
                    ssLastClientTick = DungeonServerTick.current

                    clickIndex++
                    aimState = AimState.POST_CLICK_PAUSE
                    val pause = if (fastMode) Random.nextLong(2L, 8L) else Random.nextLong(12L, 28L)
                    postClickPauseUntil = now + pause
                    return
                }
            }
        }

        val boardReady = level.getBlockState(ssButtonCheck).block == Blocks.STONE_BUTTON

        // Inter-round waiting: smoothly look back towards the button at the start of the sequence
        // (if known, ignoring broken button) or towards the middle of the device right after skip.
        if (!boardReady && (simonLifecycle.active || ssStartClicked) && !simonLifecycle.completed && simonLifecycle.round < 5) {
            clickIndex = 0
            val waitCooldown = if (fastMode) 15L else 30L
            if (now - lastSSClick > waitCooldown && (aimState == AimState.IDLE || aimState == AimState.SETTLED)) {
                if (startingButton != null) {
                    val startBtn = startingButton!!
                    if (aimTargetBlock != startBtn) {
                        startAim(player, startBtn, slow = true)
                        return
                    }
                } else {
                    val targetVec = skipWaitTargetVec ?: run {
                        val randY = 121.75 + Random.nextDouble(-0.65, 0.65)
                        val randZ = 93.55 + Random.nextDouble(-0.75, 0.75)
                        Vec3(110.875, randY, randZ).also { skipWaitTargetVec = it }
                    }
                    if (aimTargetVec != targetVec) {
                        startAimVec(player, targetVec, pos = null, slow = true)
                        return
                    }
                }
            }
        }

        if (aimState == AimState.TURNING || aimState == AimState.POST_CLICK_PAUSE) return

        // 3. Check if device needs to be started
        if (!ssStartClicked && ssSequence.isEmpty()) {
            if (level.getBlockState(ssStart).block == Blocks.STONE_BUTTON && now - lastSSClick > (if (fastMode) 80L else 150L)) {
                startClicksDone = 0
                nextStartClickAt = 0L
                startAim(player, ssStart)
                return
            }
        }

        // 4. Check if sequence buttons are ready on the wall (priority order)
        if (boardReady) {
            val expected = ssSequence.getOrNull(clickIndex)
            if (expected != null && expected != brokenButton) {
                if (level.getBlockState(expected).block == Blocks.STONE_BUTTON) {
                    // If button is already pressed / powered, drop and advance
                    if (level.getBlockState(expected).getValue(net.minecraft.world.level.block.ButtonBlock.POWERED)) {
                        clickIndex++
                        return
                    }
                    val minDelay = if (fastMode) 12L else 28L
                    if (now - lastSSClick > minDelay) {
                        if (aimTargetBlock != expected) {
                            startAim(player, expected, slow = false)
                        }
                    }
                    return
                }
            } else if (clickIndex >= ssSequence.size && ssSequence.isNotEmpty()) {
                // All buttons for current sequence clicked; focus back on starting button of sequence
                val startBtn = startingButton ?: ssSequence.firstOrNull()
                if (startBtn != null && aimTargetBlock != startBtn && now - lastSSClick > 20L) {
                    startAim(player, startBtn, slow = true)
                    return
                }
            }
        }
    }

    /** Called after an allowed manual interaction. Uses NoammAddons' queue model. */
    @JvmOverloads
    fun onSimonClick(pos: BlockPos, activatesButton: Boolean = true) {
        if (!DungeonContext.inDungeon) return
        if (pos == ssStart) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            if (player.distanceToSqr(ssDeviceCenter) > 36.0 || mc.level?.getBlockState(pos)?.block != Blocks.STONE_BUTTON) return
            startClicksDone++
            if (startClicksDone >= 2) {
                isSkipping = true
                skipOver = false
            }
            if (!simonLifecycle.startFromInput(System.nanoTime(), activatesButton)) return
            resetSimonState()
            return
        }
        if (simonLifecycle.completed) return
        if (pos !in ssButtons) return
        if (pos == brokenButton) return
        simonLifecycle.observeRun(System.nanoTime())
        val expected = ssSequence.getOrNull(clickIndex) ?: return

        if (pos == expected) {
            if (ssLastClientTick == DungeonServerTick.current) return
            clickIndex++
            ssLastClientTick = DungeonServerTick.current
        }
    }

    /** Exact NoammAddons-style pre-interaction protection. */
    fun shouldBlockSimonClick(pos: BlockPos): Boolean {
        if (!Config.blockWrongDeviceClicks || !DungeonContext.inDungeon || simonLifecycle.completed) return false
        val player = Minecraft.getInstance().player ?: return false
        if (player.isCrouching || pos !in ssButtons) return false
        if (pos == brokenButton) return true
        val expected = ssSequence.getOrNull(clickIndex) ?: return false
        return pos != expected
    }

    private fun lookAtDirect(player: net.minecraft.client.player.LocalPlayer, target: Vec3) {
        val eye = player.eyePosition
        val d = target.subtract(eye)
        val horizontal = sqrt(d.x * d.x + d.z * d.z)
        player.yRot = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        player.xRot = Math.toDegrees(atan2(-d.y, horizontal)).toFloat()
    }

    private fun resetDeviceStateOnly() {
        stormStarted = false
        emeraldSeen.clear()
        activeEmerald = null
        resetSimonState(clearCompletion = true)
    }

    private fun resetSimonState(clearCompletion: Boolean = false) {
        if (clearCompletion) simonLifecycle.reset()
        ssSequence.clear()
        clickIndex = 0
        brokenButton = null
        startingButton = null
        lastSSState = BooleanArray(ssObsidians.size)
        isSkipping = false
        skipOver = false
        ssStartClicked = false
        startClicksDone = 0
        nextStartClickAt = 0L
        ssLastClientTick = -1L
        skipWaitTargetVec = null
        cancelSimonAim()
    }

    private fun cancelSimonAim() {
        aimState = AimState.IDLE
        aimTargetVec = null
        aimTargetBlock = null
        aimSettledUntil = 0L
        postClickPauseUntil = 0L
        aimArcAmplitudeYaw = 0f
        aimArcAmplitudePitch = 0f
        aimArcSkew = 0f
        aimYawLead = 0f
        aimTremorAmpYaw = 0f
        aimTremorAmpPitch = 0f
        aimOvershootYaw = 0f
        aimOvershootPitch = 0f
    }

    private fun observeSimonBoard(level: net.minecraft.client.multiplayer.ClientLevel, player: net.minecraft.client.player.LocalPlayer) {
        if (simonLifecycle.completed || player.distanceToSqr(ssDeviceCenter) > 36.0) return
        val ready = level.getBlockState(ssButtonCheck).block == Blocks.STONE_BUTTON
        if (ready || ssObsidians.any { level.getBlockState(it).block == Blocks.SEA_LANTERN }) {
            simonLifecycle.observeRun(System.nanoTime())
        }
        if (simonLifecycle.observeBoard(ready)) {
            clickIndex = 0
        }
        if (!ready && aimTargetBlock in ssButtons && (simonLifecycle.completed || !simonLifecycle.active)) cancelSimonAim()
    }

    private fun completeSimon(reason: String) {
        val durationNs = simonLifecycle.startedNs?.let { System.nanoTime() - it }
        val rounds = simonLifecycle.round
        // Clear every pending turn/click while retaining the acknowledgement
        // latch. Neither a config toggle nor a board reset restarts this run.
        resetSimonState()
        AsthoonLite.LOGGER.info("[ASL-SIMON] complete source={} durationNs={} observedRounds={}", reason, durationNs, rounds)
        InputCapture.onSimonCompleted(reason, durationNs, rounds)
    }

    fun reset() {
        resetDeviceStateOnly()
        lastI4Click = 0L
        lastSSClick = 0L
        resetSimonState()
    }
}

private fun Config.inAnyDeviceFeatureEnabled(): Boolean =
    autoI4Enabled || autoSimonSaysEnabled
