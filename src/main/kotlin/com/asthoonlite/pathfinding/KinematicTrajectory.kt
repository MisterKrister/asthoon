package com.asthoonlite.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.*

/**
 * Deterministic kinematic trajectory predictor and solver for Minecraft living entity movement.
 * Replaces heuristic distance thresholds with forward physics simulation, blast vector inversion,
 * and closed-loop mid-air flight guidance.
 */
object KinematicTrajectory {

    // Minecraft entity physics constants
    const val GRAVITY = 0.08
    const val DRAG_AIR_VERTICAL = 0.98
    const val DRAG_AIR_HORIZONTAL = 0.91
    const val BLOCK_FRICTION_DEFAULT = 0.6f
    const val JUMP_VELOCITY = 0.42
    const val SPRINT_JUMP_BOOST = 0.20
    const val AIR_ACCELERATION_SPRINT = 0.026

    // Calibrated Bonzo blast constants from real dungeon captures
    const val BONZO_DETONATION_TICKS = 2 // Projectile impacts / detonates 2 ticks after use
    const val BONZO_IMPULSE_SPEED = 1.25 // ~25 bps impulse magnitude
    const val BONZO_BLAST_OFFSET = 1.35  // Effective blast center distance for knockback
    const val BONZO_UPWARD_VELOCITY = 0.42 // Jump-equivalent upward kick

    data class State(
        val x: Double,
        val y: Double,
        val z: Double,
        val vx: Double,
        val vy: Double,
        val vz: Double,
        val onGround: Boolean
    ) {
        val pos: Vec3 get() = Vec3(x, y, z)
        val vel: Vec3 get() = Vec3(vx, vy, vz)
        val speedH: Double get() = hypot(vx, vz)
        val bpsH: Double get() = speedH * 20.0
    }

    /**
     * Steps 1 tick of Minecraft entity physics forward in air.
     */
    fun stepAirborne(state: State, forwardInput: Double = 0.0, strafeInput: Double = 0.0, yaw: Float = 0f): State {
        var nvx = state.vx
        var nvz = state.vz
        if (abs(forwardInput) > 0.01 || abs(strafeInput) > 0.01) {
            val inputLen = hypot(strafeInput, forwardInput)
            val normFwd = forwardInput / max(inputLen, 1.0)
            val normStr = strafeInput / max(inputLen, 1.0)
            val rad = Math.toRadians(yaw.toDouble())
            val sin = sin(rad)
            val cos = cos(rad)
            val ax = (normStr * cos - normFwd * sin) * AIR_ACCELERATION_SPRINT
            val az = (normFwd * cos + normStr * sin) * AIR_ACCELERATION_SPRINT
            nvx += ax
            nvz += az
        }

        val nx = state.x + nvx
        val ny = state.y + state.vy
        val nz = state.z + nvz

        val postVx = nvx * DRAG_AIR_HORIZONTAL
        val postVz = nvz * DRAG_AIR_HORIZONTAL
        val postVy = (state.vy - GRAVITY) * DRAG_AIR_VERTICAL

        return State(nx, ny, nz, postVx, postVy, postVz, onGround = false)
    }

    /**
     * Predicts where an airborne flight arc will land at the target elevation [targetY].
     * Returns the projected landing position, the velocity at landing, and ticks until landing.
     */
    fun predictAirFlight(
        startPos: Vec3,
        startVel: Vec3,
        targetY: Double,
        forwardInput: Double = 1.0,
        strafeInput: Double = 0.0,
        yaw: Float = 0f,
        maxTicks: Int = 30
    ): Triple<Vec3, Vec3, Int> {
        val effectiveTargetY = if (targetY > startPos.y) startPos.y else targetY
        var cur = State(startPos.x, startPos.y, startPos.z, startVel.x, startVel.y, startVel.z, onGround = false)
        for (tick in 1..maxTicks) {
            val nxt = stepAirborne(cur, forwardInput, strafeInput, yaw)
            if (nxt.y <= effectiveTargetY && cur.y > effectiveTargetY) {
                val t = if (abs(nxt.y - cur.y) > 0.001) (cur.y - effectiveTargetY) / (cur.y - nxt.y) else 0.0
                val landX = cur.x + (nxt.x - cur.x) * t
                val landZ = cur.z + (nxt.z - cur.z) * t
                return Triple(Vec3(landX, effectiveTargetY, landZ), nxt.vel, tick)
            }
            cur = nxt
        }
        return Triple(cur.pos, cur.vel, maxTicks)
    }

    data class BonzoAimPlan(
        val isRedirection: Boolean,
        val shotYaw: Float,
        val shotPitch: Float,
        val destYaw: Float,
        val aimedImpactPos: Vec3,
        val expectedLaunchSpeedBps: Double,
        val jumpOnFire: Boolean
    )

    /**
     * Calculates the exact ground blast coordinate and look rotation needed to produce
     * the required knockback impulse to propel the player towards [destinationPos].
     */
    fun calculateBonzoAimPlan(
        playerPos: Vec3,
        playerVel: Vec3,
        playerEyeY: Double,
        destinationPos: Vec3,
        recordedPitch: Float = 0f,
        recordedYaw: Float = 0f,
        targetLaunchBps: Double = 24.0,
        groundY: Double? = null
    ): BonzoAimPlan {
        val destDx = destinationPos.x - playerPos.x
        val destDz = destinationPos.z - playerPos.z
        val destDistH = hypot(destDx, destDz)
        val destYaw = if (destDistH > 0.01) (-Math.toDegrees(atan2(destDx, destDz))).toFloat() else 0f

        val currentHeadingYaw = if (playerVel.horizontalDistance() > 0.04) {
            (-Math.toDegrees(atan2(playerVel.x, playerVel.z))).toFloat()
        } else {
            destYaw
        }

        val turnAngle = Mth.wrapDegrees(destYaw - currentHeadingYaw)
        val isRedirection = abs(turnAngle) >= 45.0f

        // Desired horizontal launch velocity vector
        val targetLaunchSpeed = (targetLaunchBps / 20.0).coerceIn(0.9, 1.4)
        val destDir = if (destDistH > 0.01) Vec3(destDx / destDistH, 0.0, destDz / destDistH) else Vec3(0.0, 0.0, 1.0)
        val desiredVel = destDir.scale(targetLaunchSpeed)

        // Required impulse vector from blast: ΔV = V_desired - V_current
        val currentHorizVel = Vec3(playerVel.x, 0.0, playerVel.z)
        val requiredImpulse = desiredVel.subtract(currentHorizVel)
        val impulseLen = requiredImpulse.horizontalDistance()
        val impulseDir = if (impulseLen > 0.01) requiredImpulse.scale(1.0 / impulseLen) else destDir

        // Predicted player position at detonation (2 ticks ahead)
        val predDetonationPos = playerPos.add(playerVel.x * BONZO_DETONATION_TICKS, 0.0, playerVel.z * BONZO_DETONATION_TICKS)

        // Explosion center must be placed opposite to the impulse vector:
        // BlastPos = PredPlayerPos - ImpulseDir * BlastOffset
        val blastOffsetDist = if (isRedirection) 1.25 else 1.15
        val blastImpactPos = predDetonationPos.subtract(impulseDir.scale(blastOffsetDist))
        val groundImpactY = groundY ?: floor(playerPos.y)

        // Calculate aim angles from current player eye position to the target blast impact point
        val aimDx = blastImpactPos.x - playerPos.x
        val aimDz = blastImpactPos.z - playerPos.z
        val aimDistH = hypot(aimDx, aimDz)
        val aimDy = (groundImpactY - 0.1) - playerEyeY

        val computedYaw = (-Math.toDegrees(atan2(aimDx, aimDz))).toFloat()
        val computedPitch = (-Math.toDegrees(atan2(aimDy, aimDistH.coerceAtLeast(0.2)))).toFloat().coerceIn(35.0f, 85.0f)

        // Prioritize explicit recorded node angles when present
        val finalPitch = if (recordedPitch in 20.0f..88.0f) {
            recordedPitch
        } else {
            computedPitch
        }

        val finalYaw = if (recordedYaw != 0f) {
            recordedYaw
        } else if (!isRedirection) {
            destYaw
        } else {
            computedYaw
        }

        return BonzoAimPlan(
            isRedirection = isRedirection,
            shotYaw = finalYaw,
            shotPitch = finalPitch,
            destYaw = destYaw,
            aimedImpactPos = Vec3(blastImpactPos.x, groundImpactY, blastImpactPos.z),
            expectedLaunchSpeedBps = targetLaunchSpeed * 20.0,
            jumpOnFire = true
        )
    }

    /**
     * Determines whether the player must jump on this exact tick to clear a ledge/gap ahead.
     * Evaluates future trajectory with vs without a jump.
     */
    fun shouldPredictiveLedgeJump(
        level: Level,
        playerPos: Vec3,
        playerVel: Vec3,
        targetPos: Vec3,
        onGround: Boolean
    ): Boolean {
        if (!onGround) return false
        // Never jump when dropping down to a lower destination
        if (targetPos.y < playerPos.y - 0.5) return false
        val currentSpeedH = playerVel.horizontalDistance()
        if (currentSpeedH < 0.15) return false

        val floorY = floor(playerPos.y - 0.1).toInt()
        val velDir = Vec3(playerVel.x, 0.0, playerVel.z).normalize()
        var dropDetectedAtTick = -1

        for (step in 1..4) {
            val probeX = playerPos.x + velDir.x * (step * currentSpeedH)
            val probeZ = playerPos.z + velDir.z * (step * currentSpeedH)
            val probePos = BlockPos(floor(probeX).toInt(), floorY, floor(probeZ).toInt())
            val stateAtFloor = level.getBlockState(probePos)
            val stateBelow = level.getBlockState(probePos.below())
            if (stateAtFloor.isAir && stateBelow.isAir) {
                dropDetectedAtTick = step
                break
            }
        }

        if (dropDetectedAtTick == -1) return false

        // Check if jumping on this tick clears the gap
        val jumpVel = Vec3(
            playerVel.x + velDir.x * SPRINT_JUMP_BOOST,
            JUMP_VELOCITY,
            playerVel.z + velDir.z * SPRINT_JUMP_BOOST
        )
        val (projectedLand, _, _) = predictAirFlight(playerPos, jumpVel, floorY.toDouble(), maxTicks = 12)
        val landingBlockPos = BlockPos(floor(projectedLand.x).toInt(), floorY, floor(projectedLand.z).toInt())
        val landingState = level.getBlockState(landingBlockPos)

        return !landingState.isAir || dropDetectedAtTick <= 2
    }

    data class AirControl(val forward: Double, val strafe: Double)

    /**
     * Closed-loop proportional guidance controller for airborne trajectory.
     * Computes necessary forward and strafe inputs to steer the flight arc
     * directly into [destinationPos].
     */
    fun computeAirGuidance(
        playerPos: Vec3,
        playerVel: Vec3,
        playerYaw: Float,
        destinationPos: Vec3
    ): AirControl {
        val (projLand, _, _) = predictAirFlight(playerPos, playerVel, destinationPos.y)

        val errX = destinationPos.x - projLand.x
        val errZ = destinationPos.z - projLand.z

        val rad = Math.toRadians(playerYaw.toDouble())
        val cos = cos(rad)
        val sin = sin(rad)

        val localForward = -sin * errX + cos * errZ
        val localStrafe = cos * errX + sin * errZ

        val fwdInput = when {
            localForward > 0.15 -> 1.0
            localForward < -0.15 -> -1.0
            else -> 1.0
        }

        val strafeInput = when {
            localStrafe > 0.15 -> 1.0
            localStrafe < -0.15 -> -1.0
            else -> 0.0
        }

        return AirControl(fwdInput, strafeInput)
    }
}
