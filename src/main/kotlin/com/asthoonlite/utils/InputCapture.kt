package com.asthoonlite.utils

import com.asthoonlite.AsthoonLite
import com.asthoonlite.dungeon.AutoTerminal
import com.asthoonlite.dungeon.TermGui
import com.asthoonlite.dungeon.TerminalCursor
import com.asthoonlite.dungeon.TerminalSolver
import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs

/**
 * High-resolution input, cursor, terminal, and environment recorder.
 *
 * Captures:
 * 1. World camera look angles (yaw, pitch), velocity (dYaw, dPitch), eye pos, raycast hit target.
 * 2. World interactions & Simon Says 3D button / sea-lantern device updates.
 * 3. Terminal GUI events:
 *    - Terminal opened (title, type, slot count, goal, candidates).
 *    - Terminal cursor movements frame-by-frame (x, y, dx, dy, speed, hovered slot, OS mouse pos, drawn pointer pos).
 *    - Terminal button clicks: sequence order (#1, #2, ...), slot, row, col, item, button,
 *      delta time between each click, time since terminal opened.
 *    - Terminal closed: open duration, total clicks made, full click sequence summary.
 *
 * Activated via `/asl capture`. Outputs with `[ASL-CAPTURE]` prefix to `latest.log`
 * and `.minecraft/asthoonlite/captures/capture_latest.log`.
 */
object InputCapture {

    @Volatile
    var isCapturing: Boolean = false
        private set

    private var startTimeMs: Long = 0L
    private var eventCount: Int = 0

    private var sessionLogFile: File? = null
    private var latestLogFile: File? = null
    private var logWriter: PrintWriter? = null

    // ── 3D World / Simon Says player tracking ───────────────────────────────
    private var lastYaw: Float = 0f
    private var lastPitch: Float = 0f
    private var lastPos: Vec3 = Vec3.ZERO
    private var lastFrameLogTime: Long = 0L

    // Simon Says F7 device positions
    private val ssObsidians = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(111, y, z) } }
    private val ssButtons = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }
    private val ssStart = BlockPos(110, 121, 91)

    // State change trackers
    private val lastObsidianBlocks = HashMap<BlockPos, String>()
    private val lastButtonPowered = HashMap<BlockPos, Boolean>()
    private var lastStartPowered: Boolean? = null
    private val nearbyStates = HashMap<BlockPos, String>()

    // ── Terminal session tracking ───────────────────────────────────────────
    private var currentTerminalTitle: String? = null
    private var currentTerminalKind: String? = null
    private var terminalOpenedAt: Long = 0L
    private var terminalClickIndex: Int = 0
    private var lastTerminalClickTime: Long = 0L
    private val clickedSlotsOrder = ArrayList<Int>()

    // Terminal cursor tracking
    private var lastMouseX: Double = 0.0
    private var lastMouseY: Double = 0.0
    private var lastCursorLogTime: Long = 0L

    // Click deduplication
    private var lastClickedSlotLogged: Int = -1
    private var lastClickedSlotTime: Long = 0L

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register {
            if (isCapturing) {
                onRenderFrame()
            }
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (isCapturing) {
                onUseBlock(player, hand, hitResult)
            }
            InteractionResult.PASS
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (isCapturing && screen is AbstractContainerScreen<*>) {
                onScreenInit(screen)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (isCapturing && currentTerminalTitle != null) {
                if (mc.screen !is AbstractContainerScreen<*>) {
                    closeActiveTerminal("SCREEN_CHANGED")
                }
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
        eventCount = 0

        lastObsidianBlocks.clear()
        lastButtonPowered.clear()
        lastStartPowered = null
        nearbyStates.clear()

        currentTerminalTitle = null
        currentTerminalKind = null
        terminalOpenedAt = 0L
        terminalClickIndex = 0
        lastTerminalClickTime = 0L
        clickedSlotsOrder.clear()
        lastClickedSlotLogged = -1
        lastClickedSlotTime = 0L

        try {
            val dir = File(mc.gameDirectory, "asthoonlite/captures")
            dir.mkdirs()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date(startTimeMs))
            sessionLogFile = File(dir, "capture_$timeStamp.log")
            latestLogFile = File(dir, "capture_latest.log")
            latestLogFile?.writeText("") // Reset latest capture
            logWriter = PrintWriter(FileWriter(sessionLogFile, true), true)
        } catch (e: Exception) {
            AsthoonLite.LOGGER.error("[AsthoonLite] Failed to initialize capture log file", e)
        }

        log("[START] Input Capture started at ${Date(startTimeMs)}")
        if (player != null) {
            lastYaw = player.yRot
            lastPitch = player.xRot
            lastPos = player.position()
            lastFrameLogTime = startTimeMs
            val eye = player.eyePosition
            log("[START_STATE] playerPos=(%.3f, %.3f, %.3f) eye=(%.3f, %.3f, %.3f) yaw=%.3f pitch=%.3f onGround=%b".format(
                player.x, player.y, player.z, eye.x, eye.y, eye.z, player.yRot, player.xRot, player.onGround()
            ))
        }

        // Check if a terminal screen is already open right now
        val currentScreen = mc.screen as? AbstractContainerScreen<*>
        if (currentScreen != null) {
            onScreenInit(currentScreen)
        }

        player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStarted input capture. Simon Says stops recording when the device completes; §e/asl capture §fstops other recordings.")
        )
    }

    @JvmOverloads
    fun stopCapture(reason: String = "manual") {
        if (!isCapturing) return
        if (currentTerminalTitle != null) {
            closeActiveTerminal("CAPTURE_STOPPED")
        }

        val elapsedMs = System.currentTimeMillis() - startTimeMs
        val seconds = "%.1f".format(elapsedMs / 1000.0)
        log("[STOP] Capture stopped. reason=$reason Total events=$eventCount, duration=${elapsedMs}ms (${seconds}s)")

        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null

        isCapturing = false

        val mc = Minecraft.getInstance()
        mc.player?.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fStopped capture. Recorded §e$eventCount §fevents over §e${seconds}s§f. Saved to game log and §e.minecraft/asthoonlite/captures/capture_latest.log§f.")
        )
    }

    /** A server-confirmed Simon completion ends this recording automatically. */
    fun onSimonCompleted(reason: String, durationNs: Long?, observedRounds: Int) {
        if (!isCapturing) return
        log("[SS_COMPLETE] source=$reason durationNs=$durationNs observedRounds=$observedRounds t=+${System.currentTimeMillis() - startTimeMs}ms")
        stopCapture("simon_completed")
    }

    private fun log(msg: String) {
        eventCount++
        val line = "[ASL-CAPTURE] $msg"
        AsthoonLite.LOGGER.info(line)
        try {
            logWriter?.println(line)
            latestLogFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    // ── Terminal Lifecycle & Frame Hooks ────────────────────────────────────

    private fun onScreenInit(screen: AbstractContainerScreen<*>) {
        val title = screen.title.string
        val kind = TerminalSolver.kindOf(title)
        val isTerm = kind != null || AutoTerminal.isTerminalTitle(title)

        if (isTerm) {
            val now = System.currentTimeMillis()
            val elapsed = now - startTimeMs

            // If a previous terminal was still considered open, close it out
            if (currentTerminalTitle != null) {
                closeActiveTerminal("NEW_TERMINAL_OPENED")
            }

            currentTerminalTitle = title
            currentTerminalKind = kind?.name ?: "UNKNOWN"
            terminalOpenedAt = now
            terminalClickIndex = 0
            lastTerminalClickTime = 0L
            clickedSlotsOrder.clear()
            lastClickedSlotLogged = -1
            lastClickedSlotTime = 0L

            val items = screen.menu.slots.map { it.item }
            val goal = kind?.let { TerminalSolver.goalFor(title, items) } ?: -1
            val candidates = kind?.let { TerminalSolver.clickCandidates(title, items) } ?: emptyList()

            log("[TERM_OPEN] t=+${elapsed}ms type=${currentTerminalKind} title=\"$title\" containerId=${screen.menu.containerId} slotCount=${screen.menu.slots.size} goal=$goal initialCandidates=$candidates")

            ScreenEvents.afterExtract(screen).register { _, _, mouseX, mouseY, _ ->
                if (isCapturing && currentTerminalTitle != null) {
                    onTerminalFrame(screen, mouseX.toDouble(), mouseY.toDouble())
                }
            }

            ScreenEvents.remove(screen).register { s ->
                if (isCapturing && currentTerminalTitle != null) {
                    closeActiveTerminal("SCREEN_REMOVED")
                }
            }
        }
    }

    private fun onTerminalFrame(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double) {
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val elapsedInTerm = now - terminalOpenedAt

        val dX = mouseX - lastMouseX
        val dY = mouseY - lastMouseY
        val moved = abs(dX) > 0.4 || abs(dY) > 0.4
        val timePassed = now - lastCursorLogTime >= 35L // ~30Hz cursor position stream

        if (moved || timePassed) {
            val hoveredSlot = if (TermGui.active(screen)) {
                TermGui.gridFor(screen)?.hitTest(mouseX, mouseY)
            } else {
                (screen as? AbstractContainerScreenAccessor)?.hoveredSlot?.index
            }

            val mc = Minecraft.getInstance()
            val rawX = mc.mouseHandler.xpos()
            val rawY = mc.mouseHandler.ypos()
            val drawnInfo = if (TerminalCursor.ownsCursor()) {
                "drawnPointer=(%.1f, %.1f)".format(TerminalCursor.currentX(), TerminalCursor.currentY())
            } else "drawnPointer=NONE"

            log("[TERM_CURSOR] t=+${elapsed}ms inTerm=+${elapsedInTerm}ms mouse=(%.1f, %.1f) dMouse=(%.1f, %.1f) hoveredSlot=$hoveredSlot osMouse=(%.1f, %.1f) $drawnInfo".format(
                mouseX, mouseY, dX, dY, rawX, rawY
            ))

            lastMouseX = mouseX
            lastMouseY = mouseY
            lastCursorLogTime = now
        }
    }

    fun onTerminalMousePress(button: Int, x: Double, y: Double) {
        if (!isCapturing || currentTerminalTitle == null) return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val elapsedInTerm = now - terminalOpenedAt

        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*>
        val hoveredSlot = if (screen != null && TermGui.active(screen)) {
            TermGui.gridFor(screen)?.hitTest(x, y)
        } else if (screen != null) {
            (screen as? AbstractContainerScreenAccessor)?.hoveredSlot?.index
        } else null

        log("[TERM_MOUSE_PRESS] t=+${elapsed}ms inTerm=+${elapsedInTerm}ms button=$button at=(%.1f, %.1f) hoveredSlot=$hoveredSlot".format(
            x, y
        ))
    }

    fun onTerminalClick(screen: AbstractContainerScreen<*>, slotIndex: Int, button: Int, source: String) {
        if (!isCapturing) return
        val title = screen.title.string
        if (!AutoTerminal.isTerminalTitle(title) && TerminalSolver.kindOf(title) == null) return

        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs

        // Prevent duplicate logging if both slotClicked and TerminalInput.send fire for same click within 25ms
        if (lastClickedSlotLogged == slotIndex && now - lastClickedSlotTime < 25L) return
        lastClickedSlotLogged = slotIndex
        lastClickedSlotTime = now

        terminalClickIndex++
        clickedSlotsOrder.add(slotIndex)

        val elapsedSinceOpen = if (terminalOpenedAt > 0L) now - terminalOpenedAt else 0L
        val deltaSinceLastClick = if (lastTerminalClickTime > 0L) now - lastTerminalClickTime else elapsedSinceOpen
        lastTerminalClickTime = now

        val slot = screen.menu.slots.getOrNull(slotIndex)
        val itemStack = slot?.item ?: ItemStack.EMPTY
        val itemName = if (!itemStack.isEmpty) itemStack.hoverName.string else "EMPTY"
        val itemId = if (!itemStack.isEmpty) itemStack.item.descriptionId else "air"
        val row = slotIndex / 9
        val col = slotIndex % 9

        log("[TERM_CLICK] order=#$terminalClickIndex slot=$slotIndex (row=$row, col=$col) button=$button timeBetweenClicks=${deltaSinceLastClick}ms timeSinceOpen=${elapsedSinceOpen}ms item=\"$itemName\"($itemId x${itemStack.count}) cursor=(%.1f, %.1f) src=$source".format(
            lastMouseX, lastMouseY
        ))
    }

    private fun closeActiveTerminal(reason: String) {
        if (currentTerminalTitle == null) return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val duration = if (terminalOpenedAt > 0L) now - terminalOpenedAt else 0L
        val clicks = terminalClickIndex
        val order = clickedSlotsOrder.joinToString(", ") { it.toString() }

        log("[TERM_CLOSE] t=+${elapsed}ms type=${currentTerminalKind} title=\"${currentTerminalTitle}\" duration=${duration}ms totalClicks=$clicks clickOrder=[$order] reason=$reason")

        currentTerminalTitle = null
        currentTerminalKind = null
        terminalOpenedAt = 0L
        terminalClickIndex = 0
        lastTerminalClickTime = 0L
        clickedSlotsOrder.clear()
    }

    // ── 3D World / Simon Says Frame & Click Hooks ───────────────────────────

    private fun onRenderFrame() {
        val mc = Minecraft.getInstance()
        // If a GUI screen is open, terminal frame handler handles GUI cursor
        if (mc.screen != null) return

        val player = mc.player ?: return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs

        val yaw = player.yRot
        val pitch = player.xRot
        val pos = player.position()
        val eye = player.eyePosition
        val hit = mc.hitResult

        val dYaw = yaw - lastYaw
        val dPitch = pitch - lastPitch
        val moved = pos.distanceToSqr(lastPos) > 0.0001
        val rotated = abs(dYaw) > 0.005f || abs(dPitch) > 0.005f
        val timeInterval = now - lastFrameLogTime >= 40L // At least 25Hz logging even when idle

        if (rotated || moved || timeInterval) {
            val hitStr = formatHit(hit, level, eye)
            log("[LOOK] t=+${elapsed}ms yaw=%.3f pitch=%.3f dYaw=%.3f dPitch=%.3f eye=(%.3f,%.3f,%.3f) hit=%s".format(
                yaw, pitch, dYaw, dPitch, eye.x, eye.y, eye.z, hitStr
            ))
            lastYaw = yaw
            lastPitch = pitch
            lastPos = pos
            lastFrameLogTime = now
        }

        // Monitor Simon Says F7 device state changes
        monitorSimonDevice(level, elapsed, player)
    }

    private fun monitorSimonDevice(level: net.minecraft.client.multiplayer.ClientLevel, elapsed: Long, player: Player) {
        // 1. Check SS start button
        val startState = level.getBlockState(ssStart)
        val isStartButton = startState.block is ButtonBlock
        val startPowered = if (isStartButton) startState.getValue(ButtonBlock.POWERED) else false
        if (lastStartPowered != null && lastStartPowered != startPowered) {
            log("[SS_START_CHANGE] t=+${elapsed}ms powered=$startPowered block=${startState.block.descriptionId}")
        }
        lastStartPowered = startPowered

        // 2. Check 4x4 sea lanterns / obsidians
        for (pos in ssObsidians) {
            val state = level.getBlockState(pos)
            val desc = state.block.descriptionId
            val prev = lastObsidianBlocks[pos]
            if (prev != null && prev != desc) {
                val isLantern = state.block == Blocks.SEA_LANTERN
                log("[SS_LANTERN_CHANGE] t=+${elapsed}ms pos=(${pos.x},${pos.y},${pos.z}) state=$desc isLantern=$isLantern")
            }
            lastObsidianBlocks[pos] = desc
        }

        // 3. Check 4x4 button powered states
        for (pos in ssButtons) {
            val state = level.getBlockState(pos)
            val isButton = state.block is ButtonBlock
            val powered = if (isButton) state.getValue(ButtonBlock.POWERED) else false
            val prev = lastButtonPowered[pos]
            if (prev != null && prev != powered) {
                log("[SS_BUTTON_CHANGE] t=+${elapsed}ms pos=(${pos.x},${pos.y},${pos.z}) powered=$powered")
            }
            lastButtonPowered[pos] = powered
        }

        // 4. Fallback for custom / simulator worlds (detect nearby buttons and lanterns)
        if (player.distanceToSqr(Vec3(110.5, 121.5, 93.5)) > 100.0) {
            val pPos = player.blockPosition()
            for (dx in -5..5) {
                for (dy in -3..4) {
                    for (dz in -5..5) {
                        val checkPos = pPos.offset(dx, dy, dz)
                        val state = level.getBlockState(checkPos)
                        val block = state.block
                        if (block is ButtonBlock || block == Blocks.SEA_LANTERN) {
                            val desc = if (block is ButtonBlock) "${block.descriptionId}[powered=${state.getValue(ButtonBlock.POWERED)}]" else block.descriptionId
                            val prev = nearbyStates[checkPos]
                            if (prev != null && prev != desc) {
                                log("[NEARBY_DEVICE_CHANGE] t=+${elapsed}ms pos=(${checkPos.x},${checkPos.y},${checkPos.z}) state=$desc")
                            }
                            nearbyStates[checkPos] = desc
                        }
                    }
                }
            }
        }
    }

    fun onRightClick() {
        if (!isCapturing) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val hit = mc.hitResult
        val eye = player.eyePosition
        val hitStr = formatHit(hit, level, eye)

        log("[CLICK_RIGHT] t=+${elapsed}ms yaw=%.3f pitch=%.3f eye=(%.3f,%.3f,%.3f) hit=%s".format(
            player.yRot, player.xRot, eye.x, eye.y, eye.z, hitStr
        ))
    }

    fun onLeftClick() {
        if (!isCapturing) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val hit = mc.hitResult
        val eye = player.eyePosition
        val hitStr = formatHit(hit, level, eye)

        log("[CLICK_LEFT] t=+${elapsed}ms yaw=%.3f pitch=%.3f eye=(%.3f,%.3f,%.3f) hit=%s".format(
            player.yRot, player.xRot, eye.x, eye.y, eye.z, hitStr
        ))
    }

    fun onUseBlock(player: Player, hand: InteractionHand, hitResult: BlockHitResult) {
        if (!isCapturing) return
        val now = System.currentTimeMillis()
        val elapsed = now - startTimeMs
        val pos = hitResult.blockPos
        val side = hitResult.direction
        val hitVec = hitResult.location
        val state = player.level().getBlockState(pos)
        val isSSBtn = pos in ssButtons
        val isSSSt = pos == ssStart

        log("[USE_BLOCK] t=+${elapsed}ms pos=(${pos.x},${pos.y},${pos.z}) side=$side hitVec=(%.3f,%.3f,%.3f) hand=$hand block=${state.block.descriptionId} isSSButton=$isSSBtn isSSStart=$isSSSt".format(
            hitVec.x, hitVec.y, hitVec.z
        ))
    }

    private fun formatHit(hit: HitResult?, level: net.minecraft.client.multiplayer.ClientLevel, eye: Vec3): String {
        if (hit == null) return "NULL"
        return when (hit.type) {
            HitResult.Type.BLOCK -> {
                val blockHit = hit as BlockHitResult
                val pos = blockHit.blockPos
                val side = blockHit.direction
                val loc = blockHit.location
                val dist = eye.distanceTo(loc)
                val block = level.getBlockState(pos).block.descriptionId
                val isSSBtn = pos in ssButtons
                val isSSSt = pos == ssStart
                "BLOCK(${pos.x},${pos.y},${pos.z},$side) @ (%.3f,%.3f,%.3f) dist=%.2f block=%s ssBtn=%b ssStart=%b".format(
                    loc.x, loc.y, loc.z, dist, block, isSSBtn, isSSSt
                )
            }
            HitResult.Type.ENTITY -> {
                val entHit = hit as EntityHitResult
                val ent = entHit.entity
                "ENTITY(${ent.name.string}) @ (%.3f,%.3f,%.3f)".format(ent.x, ent.y, ent.z)
            }
            HitResult.Type.MISS -> "MISS"
        }
    }
}
