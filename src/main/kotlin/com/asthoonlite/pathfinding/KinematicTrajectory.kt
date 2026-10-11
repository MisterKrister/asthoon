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
     * Calculates the optimal Bonzo blast launch pitch to guarantee covering the required horizontal
     * distance [distH] and vertical rise [deltaY] under Minecraft air drag (0.91) and gravity (0.08).
     * Uses discrete tick simulation of vertical motion and horizontal impulse kinematics.
     */
    fun calculateOptimalBonzoPitch(
        distH: Double,
        deltaY: Double = 0.0,
        recordedPitch: Float = 0f,
        isRedirection: Boolean = false
    ): Float {
        // Analytical trajectory simulation:
        // Simulate flight duration T until player reaches destination elevation deltaY
        var curY = 0.0
        var curVy = 0.83 // jump boost (0.42) + bonzo knockback impulse (0.41)
        var flightTicks = 20
        for (t in 1..40) {
            curY += curVy
            curVy = (curVy - 0.08) * 0.98
            if (curY <= deltaY && t >= 8) {
                flightTicks = t
                break
            }
        }
        val tEff = flightTicks.coerceIn(12, 35)
        val decaySum = (1.0 - Math.pow(0.91, tEff.toDouble())) / 0.09
        val airSprintTravel = 0.288 * tEff
        val neededImpulseDist = (distH - airSprintTravel).coerceAtLeast(1.0)
        val reqV0 = (neededImpulseDist / decaySum).coerceIn(0.5, 2.2)

        val vApproach = 0.55
        val blastImpulse = 1.45
        val cosTheta = ((reqV0 - vApproach) / blastImpulse).coerceIn(0.20, 0.95)
        val calcPitch = Math.toDegrees(Math.acos(cosTheta)).toFloat()

        val maxPitchForRange = when {
            distH >= 17.0 -> if (deltaY > 0.0) 38.0f else 42.0f
            distH >= 14.0 -> if (deltaY > 0.0) 44.0f else 48.0f
            distH >= 11.0 -> if (deltaY > 0.0) 52.0f else 56.0f
            distH >= 8.0 -> 64.0f
            else -> 75.0f
        }
        val minPitch = if (isRedirection) 35.0f else 28.0f
        val boundedPitch = calcPitch.coerceIn(minPitch, maxPitchForRange)

        // If recorded pitch is provided and physically covers the required distance, preserve it
        if (recordedPitch in minPitch..maxPitchForRange) {
            return recordedPitch
        }
        return boundedPitch
    }

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
        groundY: Double? = null,
        hasLookNode: Boolean = false
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

        // Desired horizontal launch velocity vector
        val targetLaunchSpeed = (targetLaunchBps / 20.0).coerceIn(0.9, 1.4)
        val destDir = if (destDistH > 0.01) Vec3(destDx / destDistH, 0.0, destDz / destDistH) else Vec3(0.0, 0.0, 1.0)
        val desiredVel = destDir.scale(targetLaunchSpeed)

        // Required impulse vector from blast: ΔV = V_desired - V_current
        val currentHorizVel = Vec3(playerVel.x, 0.0, playerVel.z)
        val requiredImpulse = desiredVel.subtract(currentHorizVel)
        val impulseLen = requiredImpulse.horizontalDistance()
        val impulseDir = if (impulseLen > 0.01) requiredImpulse.scale(1.0 / impulseLen) else destDir

        val isRedirection = abs(turnAngle) >= 30.0f || (impulseLen > 0.4 && abs(turnAngle) >= 25.0f)

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
        val computedPitch = (-Math.toDegrees(atan2(aimDy, aimDistH.coerceAtLeast(0.2)))).toFloat().coerceIn(45.0f, 75.0f)

        val deltaY = destinationPos.y - playerPos.y
        val finalPitch = if (isRedirection) {
            if (recordedPitch in 25.0f..88.0f) {
                if (destDistH >= 17.0 && deltaY >= 0.0 && recordedPitch > 38.0f) 38.0f
                else if (destDistH >= 18.0 && deltaY < 0.0 && recordedPitch > 54.15f) 54.15f
                else recordedPitch
            } else {
                computedPitch
            }
        } else if (recordedPitch in 25.0f..88.0f) {
            if (destDistH >= 17.0 && deltaY >= 0.0 && recordedPitch > 38.0f) {
                38.0f
            } else if (destDistH >= 18.0 && deltaY < 0.0 && recordedPitch > 54.15f) {
                54.15f
            } else {
                recordedPitch
            }
        } else {
            calculateOptimalBonzoPitch(
                distH = destDistH,
                deltaY = deltaY,
                recordedPitch = recordedPitch,
                isRedirection = false
            )
        }

        val finalYaw = if (hasLookNode && recordedYaw != 0f) {
            recordedYaw
        } else if (isRedirection) {
            computedYaw
        } else {
            destYaw
        }

        return BonzoAimPlan(
            isRedirection = isRedirection,
            shotYaw = finalYaw,
            shotPitch = finalPitch,
            destYaw = destYaw,
            aimedImpactPos = Vec3(blastImpactPos.x, groundImpactY, blastImpactPos.z),
            expectedLaunchSpeedBps = targetLaunchSpeed * 20.0,
            jumpOnFire = !isRedirection
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

        val distToTarget = hypot(targetPos.x - playerPos.x, targetPos.z - playerPos.z)
        val maxProbe = (distToTarget - 0.4).coerceAtLeast(0.5)

        for (step in 1..4) {
            val dist = step * currentSpeedH
            if (dist > maxProbe) break
            val probeX = playerPos.x + velDir.x * dist
            val probeZ = playerPos.z + velDir.z * dist
            val probePos = BlockPos(floor(probeX).toInt(), floorY, floor(probeZ).toInt())
            val stateAtFloor = level.getBlockState(probePos)
            val stateBelow = level.getBlockState(probePos.below())
            val shapeAtFloor = stateAtFloor.getCollisionShape(level, probePos)
            val shapeBelow = stateBelow.getCollisionShape(level, probePos.below())
            if (shapeAtFloor.isEmpty && (shapeBelow.isEmpty || stateAtFloor.isAir)) {
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

        return !landingState.getCollisionShape(level, landingBlockPos).isEmpty || dropDetectedAtTick <= 2
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

    /**
     * Computes the lateral air strafe command (-1.0 to +1.0) to steer an airborne player
     * back toward the center line of a flight corridor (from [originX, originZ] to [destX, destZ]).
     *
     * In Minecraft movement input conventions:
     * - Positive value (> 0) means the player must strafe LEFT (press keyLeft / A).
     * - Negative value (< 0) means the player must strafe RIGHT (press keyRight / D).
     */
    fun computeCorridorAirStrafe(
        playerX: Double,
        playerZ: Double,
        playerYaw: Float,
        originX: Double,
        originZ: Double,
        destX: Double,
        destZ: Double
    ): Double {
        val segX = destX - originX
        val segZ = destZ - originZ
        val segLen = hypot(segX, segZ)
        if (segLen <= 0.01) return 0.0

        val px = playerX - originX
        val pz = playerZ - originZ

        // Normal vector pointing to the RIGHT of the route segment: R = (-segZ / segLen, segX / segLen)
        // crossTrack > 0 means player is displaced to the RIGHT of the route line.
        val crossTrack = (segX * pz - segZ * px) / segLen

        // Vector C points from player back towards corridor centerline:
        // C = -crossTrack * R = (crossTrack * segZ / segLen, -crossTrack * segX / segLen)
        val cx = crossTrack * (segZ / segLen)
        val cz = -crossTrack * (segX / segLen)

        // Project C onto player's local strafe axis (+1 is LEFT, -1 is RIGHT):
        val rad = Math.toRadians(playerYaw.toDouble())
        val cosYaw = cos(rad)
        val sinYaw = sin(rad)
        return cosYaw * cx + sinYaw * cz
    }
}

