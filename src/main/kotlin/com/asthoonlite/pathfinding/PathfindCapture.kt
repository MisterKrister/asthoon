package com.asthoonlite.pathfinding

import com.asthoonlite.AsthoonLite
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * High-precision pathfinding data recorder (activated via `/asl capture 2`).
 *
 * Continuously records:
 * - Exact player coordinates, eye position, yaw and pitch.
 * - Velocity (vx, vy, vz), horizontal BPS, 3D BPS, and player movement speed attribute.
 * - Physics states: onGround, fallDistance, sprinting, sneaking, inLava.
 * - Keyboard inputs (W, A, S, D, Jump, Shift, Sprint) and mouse clicks (Left, Right).
 * - Cursor position & crosshair raycast targets (block, entity, distance).
 * - Specialized movement item detection:
 *     - Bonzo Staff propulsion & clown head projectile tracking
 *     - Aspect of the End / Aspect of the Void teleports
 *     - Etherwarp target raycasting and phase shifts
 *     - Ender Pearl launches
 *     - Spring Boots charged vertical jumps
 *     - F7 / M7 lava bounces
 *
 * Saves to `.minecraft/asthoonlite/captures/pathfind_latest.jsonl` and timestamped session archives.
 */
object PathfindCapture {

    @Volatile
    var isCapturing: Boolean = false
        private set

    private var startTimeMs: Long = 0L
    private var tickCount: Long = 0L
    private var actionCount: Int = 0

    private var sessionLogFile: File? = null
    private var latestLogFile: File? = null
    private var logWriter: PrintWriter? = null

    // Previous tick state for delta & impulse detection
    private var lastPos: Vec3 = Vec3.ZERO
    private var lastTickTime: Long = 0L
    private var wasInLava: Boolean = false
    private var wasRightClick: Boolean = false
    private var wasLeftClick: Boolean = false

    // Bonzo Staff tracking telemetry
    private var lastBonzoShotTick: Long = -100L
    private var lastBonzoShotTimeMs: Long = 0L
    private var bonzoShotSource: String = "NONE"
    private var bonzoShotEyePos: Vec3 = Vec3.ZERO
    private var bonzoShotLookVec: Vec3 = Vec3.ZERO
    private var bonzoShotYaw: Float = 0f
    private var bonzoShotPitch: Float = 0f
    private var bonzoShotPlayerPos: Vec3 = Vec3.ZERO
    private var bonzoShotInitialVel: Vec3 = Vec3.ZERO
    private var preShotEntityIds: Set<Int> = emptySet()
    private var trackedBonzoEntityId: Int? = null
    private var lastTrackedHeadPos: Vec3? = null
    private var bonzoKnockbackDetected: Boolean = false
    private var prevTickVy: Double = 0.0
    private var prevTickBpsH: Double = 0.0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (isCapturing) {
                onClientTick(mc)
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            if (isCapturing) {
                stopCapture("disconnect")
            }
        }
    }

    fun toggle() {
        if (!isCapturing) {
            startCapture()
        } else {
            stopCapture()
        }
    }

    fun startCapture() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        isCapturing = true
        startTimeMs = System.currentTimeMillis()
        tickCount = 0L
        actionCount = 0
        lastTickTime = startTimeMs

        if (player != null) {
            lastPos = player.position()
            wasInLava = player.isInLava
        } else {
            lastPos = Vec3.ZERO
            wasInLava = false
        }
        wasRightClick = false
        wasLeftClick = false

        lastBonzoShotTick = -100L
        lastBonzoShotTimeMs = 0L
        bonzoShotSource = "NONE"
        bonzoShotEyePos = Vec3.ZERO
        bonzoShotLookVec = Vec3.ZERO
        bonzoShotYaw = 0f
        bonzoShotPitch = 0f
        bonzoShotPlayerPos = Vec3.ZERO
        bonzoShotInitialVel = Vec3.ZERO
        preShotEntityIds = emptySet()
        trackedBonzoEntityId = null
        lastTrackedHeadPos = null
        bonzoKnockbackDetected = false
        prevTickVy = 0.0
        prevTickBpsH = 0.0

        try {
            val dir = File(mc.gameDirectory, "asthoonlite/captures")
            if (!dir.exists()) dir.mkdirs()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date(startTimeMs))
            sessionLogFile = File(dir, "pathfind_$timeStamp.jsonl")
            latestLogFile = File(dir, "pathfind_latest.jsonl")
            latestLogFile?.writeText("") // Reset latest
            logWriter = PrintWriter(FileWriter(sessionLogFile, true), true)
        } catch (e: Exception) {
            AsthoonLite.LOGGER.error("[AsthoonLite] Failed to initialize pathfind capture file", e)
        }

        logJson("""{"event":"START","timestamp":$startTimeMs,"date":"${Date(startTimeMs)}"}""")
        player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStarted pathfinding capture §e(#2)§f. Recording positions, velocity, abilities & inputs... Use §e/asl capture 2 §fto stop.")
        )
    }

    fun stopCapture(reason: String = "manual") {
        if (!isCapturing) return
        val elapsedMs = System.currentTimeMillis() - startTimeMs
        val seconds = "%.1f".format(elapsedMs / 1000.0)

        logJson("""{"event":"STOP","reason":"$reason","ticks":$tickCount,"actions":$actionCount,"elapsedMs":$elapsedMs}""")

        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null

        trackedBonzoEntityId = null
        lastTrackedHeadPos = null

        isCapturing = false

        val mc = Minecraft.getInstance()
        mc.player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStopped pathfind capture. Recorded §e$tickCount §fticks, §e$actionCount §factions over §e${seconds}s§f. Saved to §e.minecraft/asthoonlite/captures/pathfind_latest.jsonl§f.")
        )
    }

    private fun logJson(line: String) {
        try {
            logWriter?.println(line)
            latestLogFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    fun notifyBonzoShot(source: String = "MANUAL") {
        if (!isCapturing) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        // Debounce if already triggered on this exact tick
        if (tickCount == lastBonzoShotTick) return

        lastBonzoShotTick = tickCount
        lastBonzoShotTimeMs = System.currentTimeMillis()
        bonzoShotSource = source
        bonzoShotEyePos = player.eyePosition
        bonzoShotLookVec = player.lookAngle
        bonzoShotYaw = player.yRot
        bonzoShotPitch = player.xRot
        bonzoShotPlayerPos = player.position()
        bonzoShotInitialVel = player.deltaMovement
        trackedBonzoEntityId = null
        lastTrackedHeadPos = null
        bonzoKnockbackDetected = false

        // Snapshot current entity IDs to differentiate newly spawned projectiles
        preShotEntityIds = level.entitiesForRendering().map { it.id }.toSet()

        // Detailed block raycast along line of sight up to 30 blocks
        val rayEnd = bonzoShotEyePos.add(bonzoShotLookVec.scale(30.0))
        val rayHit = level.clip(
            ClipContext(
                bonzoShotEyePos,
                rayEnd,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        )
        val rayHitPos = if (rayHit.type == HitResult.Type.BLOCK) rayHit.location else rayEnd
        val rayBlock = if (rayHit.type == HitResult.Type.BLOCK) "${rayHit.blockPos.x},${rayHit.blockPos.y},${rayHit.blockPos.z}" else "NONE"
        val rayFace = if (rayHit.type == HitResult.Type.BLOCK) rayHit.direction.name else "NONE"
        val rayDist = bonzoShotEyePos.distanceTo(rayHitPos)

        val vx = player.deltaMovement.x
        val vy = player.deltaMovement.y
        val vz = player.deltaMovement.z
        val bpsH = hypot(vx, vz) * 20.0
        val speedAttr = try { player.getAttributeValue(Attributes.MOVEMENT_SPEED) } catch (_: Exception) { 0.1 }
        val sbSpeed = speedAttr * 1000.0

        val routeInfo = if (PathExecutor.isActive) {
            val preset = PathExecutor.activePreset
            val nodeIdx = PathExecutor.currentNodeIndex
            val target = preset?.points?.getOrNull(nodeIdx)
            val next = preset?.points?.getOrNull(nodeIdx + 1)
            "route='${preset?.name}',node=$nodeIdx(target=[${target?.x},${target?.y},${target?.z}],action=${target?.action}),next=[${next?.x},${next?.y},${next?.z}]"
        } else {
            "route=MANUAL"
        }

        val t = lastBonzoShotTimeMs - startTimeMs

        // Log any pre-existing entities within 6 blocks of eye pos
        val nearbyPreStr = level.entitiesForRendering()
            .filter { it.id != player.id && it.position().distanceTo(bonzoShotEyePos) <= 6.0 }
            .joinToString("; ") { "${it.id}:${it.type.toShortString()}@[${"%.2f".format(it.x)},${"%.2f".format(it.y)},${"%.2f".format(it.z)}]" }

        val logMsg = "[ASL-CAPTURE] [BONZO_SHOT] src=$source, tick=$tickCount, t=${t}ms, pos=[${"%.4f".format(player.x)},${"%.4f".format(player.y)},${"%.4f".format(player.z)}], eye=[${"%.4f".format(bonzoShotEyePos.x)},${"%.4f".format(bonzoShotEyePos.y)},${"%.4f".format(bonzoShotEyePos.z)}], yaw=${"%.2f".format(bonzoShotYaw)}, pitch=${"%.2f".format(bonzoShotPitch)}, look=[${"%.3f".format(bonzoShotLookVec.x)},${"%.3f".format(bonzoShotLookVec.y)},${"%.3f".format(bonzoShotLookVec.z)}], vel=[${"%.4f".format(vx)},${"%.4f".format(vy)},${"%.4f".format(vz)}], bpsH=${"%.2f".format(bpsH)}, ground=${player.onGround()}, speedAttr=${"%.4f".format(speedAttr)}(${"%.0f".format(sbSpeed)}), aimedBlock=[$rayBlock, face=$rayFace, dist=${"%.2f".format(rayDist)}], $routeInfo, nearbyPre=[$nearbyPreStr]"
        AsthoonLite.LOGGER.info(logMsg)

        val shotJson = """{"t":$t,"tick":$tickCount,"event":"BONZO_SHOT","source":"$source","x":${"%.4f".format(player.x)},"y":${"%.4f".format(player.y)},"z":${"%.4f".format(player.z)},"yaw":${"%.2f".format(bonzoShotYaw)},"pitch":${"%.2f".format(bonzoShotPitch)},"vx":${"%.4f".format(vx)},"vy":${"%.4f".format(vy)},"vz":${"%.4f".format(vz)},"bpsH":${"%.2f".format(bpsH)},"ground":${player.onGround()},"speedAttr":${"%.4f".format(speedAttr)},"aimedBlock":"$rayBlock","aimedFace":"$rayFace","aimedDist":${"%.3f".format(rayDist)},"routeInfo":"$routeInfo"}"""
        logJson(shotJson)
    }

    private fun trackBonzoPostShot(
        level: net.minecraft.client.multiplayer.ClientLevel,
        player: net.minecraft.client.player.LocalPlayer,
        dtTicks: Int,
        t: Long,
        now: Long,
        vx: Double,
        vy: Double,
        vz: Double,
        bpsH: Double
    ) {
        val dtMs = now - lastBonzoShotTimeMs

        // 1. Projectile entity tracking
        val currentEntities = level.entitiesForRendering()
        if (trackedBonzoEntityId != null) {
            val entity = currentEntities.firstOrNull { it.id == trackedBonzoEntityId }
            if (entity != null && entity.isAlive && !entity.isRemoved) {
                val ePos = entity.position()
                val eVel = entity.deltaMovement
                val distPlayer = ePos.distanceTo(player.position())
                val distOrigin = ePos.distanceTo(bonzoShotEyePos)
                lastTrackedHeadPos = ePos

                val tickMsg = "[ASL-CAPTURE] [BONZO_HEAD_TICK] dtTicks=$dtTicks, id=${entity.id}, type=${entity.type.toShortString()}, pos=[${"%.3f".format(ePos.x)},${"%.3f".format(ePos.y)},${"%.3f".format(ePos.z)}], vel=[${"%.3f".format(eVel.x)},${"%.3f".format(eVel.y)},${"%.3f".format(eVel.z)}], distPlayer=${"%.2f".format(distPlayer)}, distOrigin=${"%.2f".format(distOrigin)}"
                AsthoonLite.LOGGER.info(tickMsg)

                val headJson = """{"t":$t,"tick":$tickCount,"event":"BONZO_HEAD_TICK","dtTicks":$dtTicks,"entityId":${entity.id},"type":"${entity.type.toShortString()}","pos":[${"%.3f".format(ePos.x)},${"%.3f".format(ePos.y)},${"%.3f".format(ePos.z)}],"vel":[${"%.3f".format(eVel.x)},${"%.3f".format(eVel.y)},${"%.3f".format(eVel.z)}],"distPlayer":${"%.2f".format(distPlayer)},"distOrigin":${"%.2f".format(distOrigin)}}"""
                logJson(headJson)
            } else {
                // Tracked projectile was removed or exploded
                val impactPos = lastTrackedHeadPos ?: player.position()
                val distPlayer = impactPos.distanceTo(player.position())
                val distOrigin = impactPos.distanceTo(bonzoShotEyePos)

                val impactMsg = "[ASL-CAPTURE] [BONZO_IMPACT] dtTicks=$dtTicks, id=$trackedBonzoEntityId, impactPos=[${"%.3f".format(impactPos.x)},${"%.3f".format(impactPos.y)},${"%.3f".format(impactPos.z)}], distPlayer=${"%.2f".format(distPlayer)}, distOrigin=${"%.2f".format(distOrigin)}, playerPos=[${"%.3f".format(player.x)},${"%.3f".format(player.y)},${"%.3f".format(player.z)}]"
                AsthoonLite.LOGGER.info(impactMsg)

                val impactJson = """{"t":$t,"tick":$tickCount,"event":"BONZO_IMPACT","dtTicks":$dtTicks,"entityId":$trackedBonzoEntityId,"impactPos":[${"%.3f".format(impactPos.x)},${"%.3f".format(impactPos.y)},${"%.3f".format(impactPos.z)}],"distPlayer":${"%.2f".format(distPlayer)},"distOrigin":${"%.2f".format(distOrigin)}}"""
                logJson(impactJson)

                trackedBonzoEntityId = null
            }
        } else {
            // Scan for candidate newly spawned projectile entity near eye pos
            val candidates = currentEntities.filter { entity ->
                entity.id != player.id &&
                entity.position().distanceTo(bonzoShotEyePos) <= 9.0 &&
                (!preShotEntityIds.contains(entity.id) ||
                 entity.type.toShortString().contains("skull", ignoreCase = true) ||
                 entity.type.toShortString().contains("projectile", ignoreCase = true) ||
                 entity.type.toShortString().contains("fireball", ignoreCase = true))
            }
            val bestCandidate = candidates.minByOrNull { it.position().distanceTo(bonzoShotEyePos) }
            if (bestCandidate != null) {
                trackedBonzoEntityId = bestCandidate.id
                lastTrackedHeadPos = bestCandidate.position()
                val headItem = (bestCandidate as? LivingEntity)?.getItemBySlot(EquipmentSlot.HEAD)?.hoverName?.string ?: ""
                val customName = bestCandidate.customName?.string ?: ""

                val spawnMsg = "[ASL-CAPTURE] [BONZO_HEAD_SPAWN] dtTicks=$dtTicks, id=${bestCandidate.id}, type=${bestCandidate.type.toShortString()}, name='$customName', headItem='$headItem', pos=[${"%.3f".format(bestCandidate.x)},${"%.3f".format(bestCandidate.y)},${"%.3f".format(bestCandidate.z)}], vel=[${"%.3f".format(bestCandidate.deltaMovement.x)},${"%.3f".format(bestCandidate.deltaMovement.y)},${"%.3f".format(bestCandidate.deltaMovement.z)}]"
                AsthoonLite.LOGGER.info(spawnMsg)

                val spawnJson = """{"t":$t,"tick":$tickCount,"event":"BONZO_HEAD_SPAWN","dtTicks":$dtTicks,"entityId":${bestCandidate.id},"type":"${bestCandidate.type.toShortString()}","name":"$customName","headItem":"$headItem","pos":[${"%.3f".format(bestCandidate.x)},${"%.3f".format(bestCandidate.y)},${"%.3f".format(bestCandidate.z)}],"vel":[${"%.3f".format(bestCandidate.deltaMovement.x)},${"%.3f".format(bestCandidate.deltaMovement.y)},${"%.3f".format(bestCandidate.deltaMovement.z)}]}"""
                logJson(spawnJson)
            } else if (dtTicks <= 5) {
                val nearby = currentEntities.filter { it.id != player.id && it.position().distanceTo(player.position()) <= 5.0 }
                if (nearby.isNotEmpty()) {
                    val nearbyStr = nearby.joinToString("; ") { "${it.id}:${it.type.toShortString()}@[${"%.2f".format(it.x)},${"%.2f".format(it.y)},${"%.2f".format(it.z)}]" }
                    AsthoonLite.LOGGER.info("[ASL-CAPTURE] [BONZO_NEARBY_ENTITIES] dtTicks=$dtTicks, entities=[$nearbyStr]")
                }
            }
        }

        // 2. Knockback / explosion impulse detection on player
        val dvy = vy - prevTickVy
        val dvH = bpsH - prevTickBpsH
        if ((!bonzoKnockbackDetected || vy > 0.45) && (vy > 0.22 || dvy > 0.30 || (bpsH > 14.0 && dvH > 4.0))) {
            bonzoKnockbackDetected = true
            val kbMsg = "[ASL-CAPTURE] [BONZO_KNOCKBACK] dtTicks=$dtTicks, dtMs=${dtMs}ms, pos=[${"%.4f".format(player.x)},${"%.4f".format(player.y)},${"%.4f".format(player.z)}], vel=[${"%.4f".format(vx)},${"%.4f".format(vy)},${"%.4f".format(vz)}], dvy=+${"%.4f".format(dvy)}, bpsH=${"%.2f".format(bpsH)} (+${"%.2f".format(dvH)}), ground=${player.onGround()}"
            AsthoonLite.LOGGER.info(kbMsg)

            val kbJson = """{"t":$t,"tick":$tickCount,"event":"BONZO_KNOCKBACK","dtTicks":$dtTicks,"dtMs":$dtMs,"pos":[${"%.4f".format(player.x)},${"%.4f".format(player.y)},${"%.4f".format(player.z)}],"vel":[${"%.4f".format(vx)},${"%.4f".format(vy)},${"%.4f".format(vz)}],"dvy":${"%.4f".format(dvy)},"bpsH":${"%.2f".format(bpsH)},"ground":${player.onGround()}}"""
            logJson(kbJson)
        }
    }

    private fun onClientTick(mc: Minecraft) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val t = now - startTimeMs
        tickCount++

        val pos = player.position()
        val eye = player.eyePosition
        val delta = player.deltaMovement
        val vx = delta.x
        val vy = delta.y
        val vz = delta.z
        val bpsH = hypot(vx, vz) * 20.0
        val bps3D = sqrt(vx * vx + vy * vy + vz * vz) * 20.0

        val speedAttr = try {
            player.getAttributeValue(Attributes.MOVEMENT_SPEED)
        } catch (_: Exception) { 0.1 }

        val onGround = player.onGround()
        val fallDist = player.fallDistance
        val isSprinting = player.isSprinting
        val isCrouching = player.isCrouching
        val inLava = player.isInLava || level.getBlockState(player.blockPosition()).`is`(Blocks.LAVA)

        // Inputs
        val input = player.input
        val keyPresses = input.keyPresses
        val fwd = keyPresses.forward()
        val back = keyPresses.backward()
        val left = keyPresses.left()
        val right = keyPresses.right()
        val jump = keyPresses.jump()
        val shift = keyPresses.shift()
        val sprint = keyPresses.sprint()

        // Mouse buttons
        val mouse = mc.mouseHandler
        val leftClick = mouse.isLeftPressed
        val rightClick = mouse.isRightPressed
        val rightClickJustPressed = rightClick && !wasRightClick
        val leftClickJustPressed = leftClick && !wasLeftClick
        wasRightClick = rightClick
        wasLeftClick = leftClick

        // Gear / Items
        val mainStack = player.mainHandItem
        val mainName = if (!mainStack.isEmpty) mainStack.hoverName.string else ""
        val bootsStack = player.getItemBySlot(EquipmentSlot.FEET)
        val bootsName = if (!bootsStack.isEmpty) bootsStack.hoverName.string else ""

        // Detect Actions & Abilities
        var detectedAction: String? = null
        val distMoved = pos.distanceTo(lastPos)

        // 1. AOTE / AOTV / Etherwarp teleport
        if (distMoved > 6.0 && !player.isDeadOrDying) {
            val isAotvOrAote = mainName.contains("Aspect of the", ignoreCase = true)
            if (isCrouching || mainName.contains("Etherwarp", ignoreCase = true)) {
                detectedAction = "ETHERWARP"
            } else if (isAotvOrAote) {
                detectedAction = "AOTE_TELEPORT"
            } else {
                detectedAction = "TELEPORT"
            }
        } else if (rightClickJustPressed && mainName.contains("Aspect of the", ignoreCase = true)) {
            detectedAction = if (isCrouching) "ETHERWARP_ATTEMPT" else "AOTE_USE"
        }

        // 2. Bonzo Staff
        if (mainName.contains("Bonzo", ignoreCase = true) && rightClickJustPressed) {
            detectedAction = "BONZO_STAFF"
            notifyBonzoShot("MANUAL")
        }

        // 3. Ender Pearl
        if ((mainStack.`is`(Items.ENDER_PEARL) || mainName.contains("Ender Pearl", ignoreCase = true)) && rightClickJustPressed) {
            detectedAction = "ENDER_PEARL"
        }

        // 4. Spring Boots jump
        if (bootsName.contains("Spring Boots", ignoreCase = true) && jump && vy > 0.45) {
            detectedAction = "SPRING_BOOTS_JUMP"
        }

        // 5. Lava Bounce (M7 / F7 lava)
        if ((inLava || wasInLava) && vy > 0.15) {
            detectedAction = "LAVA_BOUNCE"
        }

        wasInLava = inLava

        // Post-shot Bonzo projectile & knockback tracking
        val dtTicks = (tickCount - lastBonzoShotTick).toInt()
        if (dtTicks in 1..25) {
            trackBonzoPostShot(level, player, dtTicks, t, now, vx, vy, vz, bpsH)
        }

        // Hit Result
        var hitTargetType = "MISS"
        var hitInfo = ""
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            hitTargetType = "BLOCK"
            hitInfo = "${hit.blockPos.x},${hit.blockPos.y},${hit.blockPos.z}"
        } else if (hit is EntityHitResult && hit.type == HitResult.Type.ENTITY) {
            hitTargetType = "ENTITY"
            hitInfo = hit.entity.name.string
        }

        if (detectedAction != null) {
            actionCount++
            val actionJson = buildString {
                append("""{"t":$t,"event":"ACTION","action":"$detectedAction","pos":[${"%.3f".format(pos.x)},${"%.3f".format(pos.y)},${"%.3f".format(pos.z)}],""")
                append(""""distMoved":${"%.3f".format(distMoved)},"item":"$mainName","bps":${"%.2f".format(bpsH)}}""")
            }
            logJson(actionJson)
        }

        // Periodic/Tick Sample
        val tickJson = buildString {
            append("""{"t":$t,"tick":$tickCount,"x":${"%.4f".format(pos.x)},"y":${"%.4f".format(pos.y)},"z":${"%.4f".format(pos.z)},""")
            append(""""yaw":${"%.2f".format(player.yRot)},"pitch":${"%.2f".format(player.xRot)},""")
            append(""""vx":${"%.4f".format(vx)},"vy":${"%.4f".format(vy)},"vz":${"%.4f".format(vz)},""")
            append(""""bpsH":${"%.2f".format(bpsH)},"bps3D":${"%.2f".format(bps3D)},"attrSpeed":${"%.4f".format(speedAttr)},""")
            append(""""ground":$onGround,"inLava":$inLava,"crouch":$isCrouching,"sprint":$isSprinting,"fall":${"%.2f".format(fallDist)},""")
            append(""""keys":{"w":$fwd,"s":$back,"a":$left,"d":$right,"jump":$jump,"shift":$shift,"sprint":$sprint},""")
            append(""""mouse":{"l":$leftClick,"r":$rightClick},"hit":{"type":"$hitTargetType","target":"$hitInfo"},""")
            append(""""items":{"main":"$mainName","boots":"$bootsName"}""")
            if (dtTicks in 0..25) {
                append(""", "bonzoTrack":{"dt":$dtTicks,"headId":$trackedBonzoEntityId,"kb":$bonzoKnockbackDetected}""")
            }
            val execStatus = PathExecutor.getTelemetryStatus()
            if (execStatus != null) {
                append(""", "executor":"$execStatus"""")
            }
            if (detectedAction != null) append(""","action":"$detectedAction"""")
            append("}")
        }
        logJson(tickJson)

        prevTickVy = vy
        prevTickBpsH = bpsH
        lastPos = pos
        lastTickTime = now
    }
}
