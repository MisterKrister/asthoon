package com.asthoonlite.pathfinding

import com.asthoonlite.AsthoonLite
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Items
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
 *     - Bonzo Staff propulsion
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
            if (detectedAction != null) append(""","action":"$detectedAction"""")
            append("}")
        }
        logJson(tickJson)

        lastPos = pos
        lastTickTime = now
    }
}
