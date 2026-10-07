package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import com.asthoonlite.utils.InputCapture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Terminal-only input and state records. File writes run away from input callbacks. */
object TerminalCapture {
    /** Practice screens opt in after initialization instead of creating a real-terminal session first. */
    interface PracticeScreen

    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)
    private var session: Session? = null
    private var simulationScreen: AbstractContainerScreen<*>? = null
    private var finishedSimulationScreen: AbstractContainerScreen<*>? = null
    private var registered = false
    private var reportedHookError = false
    private val routeDepth = ThreadLocal.withInitial { 0 }

    private data class SlotState(
        val slot: Int, val item: String, val count: Int, val name: String,
        val selected: Boolean, val x: Int, val y: Int
    )

    private class Session(
        val screen: AbstractContainerScreen<*>, val kind: TerminalSolver.Kind,
        val simulation: Boolean, val id: String, val openedNs: Long, val path: Path,
        val stream: TerminalCaptureStream
    ) {
        var lastSlots: List<SlotState>? = null
        var lastCandidates: List<Int> = emptyList()
        var lastGeometry: Map<String, Any?>? = null
        var lastSettings: Map<String, Any?>? = null
        var initialGoal = 0
        var clicks = 0
        var manualClicks = 0
        var automaticClicks = 0
        var suspectedWrongClicks = 0
        var exactWrongClicks: Int? = null
        var observedComplete = false
        var lastClickNs: Long? = null
        var lastMouseX: Double? = null
        var lastMouseY: Double? = null
        var lastMouseNs: Long? = null
        @Volatile var failed = false
    }

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) safely {
                if (enabledFor(screen)) ensureSession(screen)?.let { snapshotInternal(it, "screen_initialized", System.nanoTime()) }
                ScreenEvents.remove(screen).register { removed -> safely {
                    session?.takeIf { it.screen === removed }?.let { finish(it, "screen_removed") }
                    if (simulationScreen === removed) simulationScreen = null
                    if (finishedSimulationScreen === removed) finishedSimulationScreen = null
                } }
                ScreenEvents.afterExtract(screen).register { _, _, mouseX, mouseY, _ -> safely {
                    if (Minecraft.getInstance().screen === screen && enabledFor(screen) && TerminalCursor.ownsCursor()) {
                        ensureSession(screen)?.let { current ->
                            val now = System.nanoTime()
                            emit(current, "drawn_pointer_frame", now, mapOf(
                                "x" to TerminalCursor.currentX(), "y" to TerminalCursor.currentY(),
                                "renderMouseX" to mouseX, "renderMouseY" to mouseY,
                                "hoveredSlot" to TermGui.gridFor(screen)?.hitTest(
                                    TerminalCursor.currentX().toDouble(), TerminalCursor.currentY().toDouble())
                            ))
                        }
                    }
                } }
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> safely {
            val screen = mc.screen as? AbstractContainerScreen<*>
            val old = session
            if (old != null && (screen !== old.screen || !enabledFor(old.screen))) {
                finish(old, if (screen !== old.screen) "screen_changed" else "recording_stopped")
            }
            if (screen != null && enabledFor(screen)) {
                ensureSession(screen)?.let { snapshotInternal(it, "client_tick", System.nanoTime()) }
            }
        } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stop("disconnect") }
        ClientLifecycleEvents.CLIENT_STOPPING.register { stop("client_stopping") }
    }

    /** Called after opening a practice screen; each puzzle gets its own file. */
    fun beginSimulation(screen: AbstractContainerScreen<*>, metadata: Map<String, Any?> = emptyMap()) = safely {
        finishedSimulationScreen = null
        simulationScreen = screen
        val current = session
        if (current != null && (current.screen !== screen || !current.simulation)) finish(current, "practice_started")
        ensureSession(screen)?.let {
            emit(it, "simulation_started", System.nanoTime(), metadata)
            snapshotInternal(it, "practice_initialized", System.nanoTime())
        }
    }

    fun completeSimulation(screen: AbstractContainerScreen<*>, wrongClicks: Int) = safely {
        val current = session?.takeIf { it.screen === screen } ?: return@safely
        current.exactWrongClicks = wrongClicks.coerceAtLeast(0)
        current.observedComplete = true
        snapshotInternal(current, "practice_completed", System.nanoTime())
        finish(current, "simulation_completed", "completed")
        finishedSimulationScreen = screen
        if (simulationScreen === screen) simulationScreen = null
    }

    fun abortSimulation(screen: AbstractContainerScreen<*>, reason: String = "practice_stopped") = safely {
        session?.takeIf { it.screen === screen }?.let { finish(it, reason, "aborted") }
        finishedSimulationScreen = screen
        if (simulationScreen === screen) simulationScreen = null
    }

    /** Forwarding a local menu click must not record a second manual route. */
    fun <T> withRouteContext(action: () -> T): T {
        val before = routeDepth.get()
        routeDepth.set(before + 1)
        return try { action() } finally { routeDepth.set(before) }
    }

    @JvmStatic
    fun onVanillaClick(screen: AbstractContainerScreen<*>, slot: Int, button: Int, input: ContainerInput) {
        if (routeDepth.get() > 0) return
        routedClick(screen, slot, button, input, "manual", true, "vanilla_slot_clicked")
    }

    fun routedClick(
        screen: AbstractContainerScreen<*>, slot: Int, button: Int, input: ContainerInput,
        source: String, accepted: Boolean, path: String
    ) = safely {
        if (!enabledFor(screen)) return@safely
        val current = ensureSession(screen) ?: return@safely
        val now = System.nanoTime()
        val matchesCandidate = slot in current.lastCandidates
        if (accepted) {
            current.clicks++
            if (source == "automatic") current.automaticClicks++ else current.manualClicks++
            if (!matchesCandidate) current.suspectedWrongClicks++
        }
        emit(current, "click_routed", now, linkedMapOf(
            "slot" to slot, "row" to slot / 9, "column" to slot % 9,
            "button" to button, "input" to input.name, "source" to source,
            "accepted" to accepted, "path" to path, "clickNumber" to current.clicks,
            "candidateMatch" to matchesCandidate, "candidatesBeforeClick" to current.lastCandidates,
            "sincePreviousClickNs" to current.lastClickNs?.let { now - it },
            "position" to position(screen, Minecraft.getInstance().mouseHandler.xpos(), Minecraft.getInstance().mouseHandler.ypos())
        ))
        if (accepted) current.lastClickNs = now
    }

    /** The simulator supplies exact acceptance/rejection instead of guessing from candidates. */
    fun clickOutcome(
        screen: AbstractContainerScreen<*>, slot: Int, source: String, outcome: String,
        wrongClicks: Int? = null, fields: Map<String, Any?> = emptyMap()
    ) = safely {
        val current = session?.takeIf { it.screen === screen } ?: return@safely
        if (wrongClicks != null) current.exactWrongClicks = wrongClicks.coerceAtLeast(0)
        val metadata = LinkedHashMap(fields)
        metadata.putAll(mapOf(
            "slot" to slot, "source" to source, "outcome" to outcome, "wrongClicks" to wrongClicks
        ))
        emit(current, "click_outcome", System.nanoTime(), metadata)
        snapshotInternal(current, "click_outcome", System.nanoTime())
    }

    /** Links queued inputs to their later application without deduplicating identical clicks. */
    fun simulationInput(screen: AbstractContainerScreen<*>, event: String, fields: Map<String, Any?>) = safely {
        if (simulationScreen !== screen || !enabledFor(screen)) return@safely
        val current = ensureSession(screen) ?: return@safely
        emit(current, event, System.nanoTime(), LinkedHashMap(fields))
    }

    fun snapshot(screen: AbstractContainerScreen<*>, reason: String = "explicit_update") = safely {
        if (enabledFor(screen)) ensureSession(screen)?.let { snapshotInternal(it, reason, System.nanoTime()) }
    }

    @JvmStatic
    fun rawMouseMove(window: Long, x: Double, y: Double, atNs: Long) = safely {
        val current = liveSession(window, atNs) ?: return@safely
        val previousNs = current.lastMouseNs
        emit(current, "mouse_move", atNs, linkedMapOf(
            "position" to position(current.screen, x, y),
            "deltaRawX" to current.lastMouseX?.let { x - it },
            "deltaRawY" to current.lastMouseY?.let { y - it },
            "sincePreviousMoveNs" to previousNs?.let { atNs - it }
        ))
        current.lastMouseX = x
        current.lastMouseY = y
        current.lastMouseNs = atNs
    }

    @JvmStatic
    fun rawMouseButton(window: Long, button: Int, modifiers: Int, action: Int, atNs: Long) = safely {
        val current = liveSession(window, atNs) ?: return@safely
        snapshotInternal(current, "before_mouse_button", atNs)
        val mouse = Minecraft.getInstance().mouseHandler
        emit(current, "mouse_button", atNs, mapOf(
            "button" to button, "modifiers" to modifiers, "action" to actionName(action),
            "glfwAction" to action, "position" to position(current.screen, mouse.xpos(), mouse.ypos())
        ))
    }

    @JvmStatic
    fun rawMouseScroll(window: Long, horizontal: Double, vertical: Double, atNs: Long) = safely {
        val current = liveSession(window, atNs) ?: return@safely
        val mouse = Minecraft.getInstance().mouseHandler
        emit(current, "mouse_scroll", atNs, mapOf(
            "horizontal" to horizontal, "vertical" to vertical,
            "position" to position(current.screen, mouse.xpos(), mouse.ypos())
        ))
    }

    @JvmStatic
    fun rawKey(window: Long, key: Int, scancode: Int, modifiers: Int, action: Int, atNs: Long) = safely {
        val current = liveSession(window, atNs) ?: return@safely
        snapshotInternal(current, "before_key", atNs)
        val mouse = Minecraft.getInstance().mouseHandler
        emit(current, "key", atNs, mapOf(
            "key" to key, "scancode" to scancode, "modifiers" to modifiers,
            "action" to actionName(action), "glfwAction" to action,
            "position" to position(current.screen, mouse.xpos(), mouse.ypos())
        ))
    }

    @JvmStatic
    fun menuChanged(menu: AbstractContainerMenu, reason: String, slot: Int, atNs: Long) = safely {
        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return@safely
        if (screen.menu !== menu || !enabledFor(screen)) return@safely
        val current = ensureSession(screen) ?: return@safely
        emit(current, "menu_update", atNs, mapOf("reason" to reason, "slot" to slot, "stateId" to menu.stateId))
        snapshotInternal(current, reason, atNs)
    }

    private fun enabledFor(screen: AbstractContainerScreen<*>): Boolean {
        if (finishedSimulationScreen === screen || TerminalSolver.kindOf(screen.title.string) == null) return false
        if (screen is PracticeScreen) return simulationScreen === screen
        return simulationScreen === screen || Config.terminalInputLoggingEnabled || InputCapture.isCapturing
    }

    private fun liveSession(window: Long, atNs: Long): Session? {
        val mc = Minecraft.getInstance()
        if (window != mc.window.handle()) return null
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return null
        if (!enabledFor(screen)) return null
        return ensureSession(screen, atNs)
    }

    private fun ensureSession(screen: AbstractContainerScreen<*>, atNs: Long = System.nanoTime()): Session? {
        val previous = session
        if (previous?.screen === screen) return previous
        if (!enabledFor(screen)) return null
        if (previous != null) finish(previous, "new_terminal")
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return null
        val now = atNs
        val id = UUID.randomUUID().toString()
        val file = "terminal_${timestamp.format(Instant.now())}_${kind.name.lowercase()}_$id.jsonl"
        val path = Minecraft.getInstance().gameDirectory.toPath().resolve("logs/asthoonlite/terminals").resolve(file)
        val current = Session(screen, kind, simulationScreen === screen, id, now, path, TerminalCaptureStream(id, now))
        session = current
        startWriter(current)
        emit(current, "session_open", now, linkedMapOf(
            "wallClockMs" to System.currentTimeMillis(), "kind" to kind.name,
            "title" to screen.title.string, "simulation" to current.simulation,
            "screenClass" to screen.javaClass.name, "containerId" to screen.menu.containerId,
            "menuSlotCount" to screen.menu.slots.size, "settings" to settings(),
            "geometry" to geometry(screen), "timestampSource" to "System.nanoTime",
            "sequenceSource" to "callback_order", "stateCandidateSource" to "terminal_solver"
        ))
        snapshotInternal(current, "session_open", now)
        AsthoonLite.LOGGER.info("[ASL-TERMINAL] open id={} kind={} simulation={} file={}", id, kind.name, current.simulation, path)
        return current
    }

    private fun snapshotInternal(current: Session, reason: String, now: Long) {
        val screen = current.screen
        val items = screen.menu.slots.take(current.kind.slotCount).map { it.item }
        val slots = screen.menu.slots.take(current.kind.slotCount).mapIndexed { index, slot ->
            val stack = slot.item
            SlotState(index, BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count,
                if (stack.isEmpty) "" else stack.hoverName.string, TerminalHelper.isSelected(stack), slot.x, slot.y)
        }
        val candidates = TerminalSolver.clickCandidates(screen.title.string, items, rubixTarget = AutoTerminal.rubixTargetOrNull())
        val shape = geometry(screen)
        val configuration = settings()
        val first = current.lastSlots == null
        if (first || current.initialGoal == 0 && current.clicks == 0) {
            current.initialGoal = TerminalSolver.goalFor(screen.title.string, items)
        }
        if (slots != current.lastSlots || candidates != current.lastCandidates || shape != current.lastGeometry || configuration != current.lastSettings) {
            val changed = if (first) slots.indices.toList() else slots.indices.filter { slots[it] != current.lastSlots?.getOrNull(it) }
            emit(current, "state_snapshot", now, linkedMapOf(
                "reason" to reason, "stateId" to screen.menu.stateId, "slots" to slots,
                "changedSlots" to changed, "candidates" to candidates,
                "goalAtOpen" to current.initialGoal, "geometry" to shape,
                "settings" to configuration,
                "melodyRows" to if (current.kind == TerminalSolver.Kind.MELODY) TerminalSolver.melodyRows(items) else null,
                "melodyMarker" to if (current.kind == TerminalSolver.Kind.MELODY) TerminalSolver.melodyMarker(items) else null
            ))
            current.lastSlots = slots
            current.lastCandidates = candidates.toList()
            current.lastGeometry = shape
            current.lastSettings = configuration
        }
        if (!current.simulation && current.initialGoal > 0) {
            val complete = if (current.kind == TerminalSolver.Kind.MELODY) {
                val rows = TerminalSolver.melodyRows(items)
                rows.isNotEmpty() && rows.all { it.completed }
            } else TerminalSolver.goalFor(screen.title.string, items) == 0
            if (complete && !current.observedComplete) {
                current.observedComplete = true
                emit(current, "completion_observed", now, mapOf("source" to "slot_state", "confidence" to "inferred"))
            }
        }
    }

    private fun position(screen: AbstractContainerScreen<*>, x: Double, y: Double): Map<String, Any?> {
        val mc = Minecraft.getInstance()
        val scaledX = MouseHandler.getScaledXPos(mc.window, x)
        val scaledY = MouseHandler.getScaledYPos(mc.window, y)
        val grid = TermGui.gridFor(screen)
        val accessor = screen as? AbstractContainerScreenAccessor
        // Compute hit tests at this event's coordinates; hoveredSlot can be
        // one rendered frame behind the actual mouse callback.
        val hovered = if (grid != null) grid.hitTest(scaledX, scaledY) else if (accessor != null) {
            screen.menu.slots.firstOrNull { slot ->
                val left = accessor.leftPos + slot.x
                val top = accessor.topPos + slot.y
                scaledX >= left - 1 && scaledX < left + 17 && scaledY >= top - 1 && scaledY < top + 17
            }?.index
        } else null
        return linkedMapOf(
            "rawX" to x, "rawY" to y, "scaledX" to scaledX, "scaledY" to scaledY,
            "hoveredSlot" to hovered, "lastRenderedHoveredSlot" to accessor?.hoveredSlot?.index,
            "drawnPointerX" to if (TerminalCursor.ownsCursor()) TerminalCursor.currentX() else null,
            "drawnPointerY" to if (TerminalCursor.ownsCursor()) TerminalCursor.currentY() else null,
            "geometry" to geometry(screen)
        )
    }

    private fun geometry(screen: AbstractContainerScreen<*>): Map<String, Any?> {
        val window = Minecraft.getInstance().window
        val grid = TermGui.gridFor(screen)
        val accessor = screen as? AbstractContainerScreenAccessor
        return linkedMapOf(
            "windowWidth" to window.screenWidth, "windowHeight" to window.screenHeight,
            "framebufferWidth" to window.width, "framebufferHeight" to window.height,
            "guiWidth" to screen.width, "guiHeight" to screen.height, "guiScale" to window.guiScale,
            "customGrid" to (grid != null), "containerLeft" to accessor?.leftPos, "containerTop" to accessor?.topPos,
            "gridOriginX" to grid?.originX, "gridOriginY" to grid?.originY,
            "gridScale" to grid?.scale, "gridWidth" to grid?.width, "gridHeight" to grid?.height,
            "tileSize" to TermGui.TILE_SIZE
        )
    }

    private fun settings(): Map<String, Any?> = linkedMapOf(
        "autoTerminalEnabled" to Config.autoTerminalEnabled,
        "autoTerminalMode" to Config.autoTerminalMode,
        "solverEnabled" to Config.terminalSolverEnabled,
        "clickOrder" to Config.autoTerminalClickOrder,
        "firstClickDelayMs" to Config.autoTerminalFirstClickDelayMs,
        "melodyFirstClickDelayMs" to Config.autoTerminalMelodyFirstClickDelayMs,
        "clickDelayMs" to Config.autoTerminalClickDelayMs,
        "randomDelay" to Config.autoTerminalRandomDelay,
        "minRandomDelayMs" to Config.autoTerminalMinRandomDelayMs,
        "maxRandomDelayMs" to Config.autoTerminalMaxRandomDelayMs,
        "melodySkip" to Config.autoTerminalMelodySkip,
        "cursorGlide" to Config.autoTerminalCursorGlide,
        "cursorMelody" to Config.autoTerminalCursorMelody,
        "cursorSpeed" to Config.autoTerminalCursorSpeed,
        "cursorArc" to Config.autoTerminalCursorArc,
        "cursorJitter" to Config.autoTerminalCursorJitter,
        "humanize" to Config.autoTerminalHumanize,
        "bezier" to listOf(Config.autoTerminalEaseX1, Config.autoTerminalEaseY1, Config.autoTerminalEaseX2, Config.autoTerminalEaseY2),
        "termGuiEnabled" to Config.termGuiEnabled, "termGuiSize" to Config.termGuiSize,
        "termGuiMelodySize" to Config.termGuiMelodySize, "termGuiGap" to Config.termGuiGap
    )

    private fun emit(current: Session, type: String, now: Long, fields: Map<String, Any?>) {
        current.stream.offer(type, now, fields)
    }

    private fun finish(current: Session, reason: String, status: String? = null) {
        if (session !== current) return
        val now = System.nanoTime()
        val resolved = status ?: if (current.observedComplete) "completed" else "closed_unconfirmed"
        emit(current, "session_close", now, linkedMapOf(
            "reason" to reason, "status" to resolved, "durationNs" to now - current.openedNs,
            "clicks" to current.clicks, "manualClicks" to current.manualClicks,
            "automaticClicks" to current.automaticClicks, "wrongClicks" to current.exactWrongClicks,
            "suspectedWrongClicks" to current.suspectedWrongClicks,
            "wrongClickSource" to if (current.exactWrongClicks == null) "candidate_mismatch_only" else "simulator_validation",
            "eventsLost" to current.stream.totalLosses, "fileFailed" to current.failed
        ))
        current.stream.close()
        session = null
        AsthoonLite.LOGGER.info("[ASL-TERMINAL] close id={} kind={} status={} durationMs={} clicks={} wrongClicks={} suspectedWrongClicks={} eventsLost={} file={}",
            current.id, current.kind.name, resolved, (now - current.openedNs) / 1_000_000.0,
            current.clicks, current.exactWrongClicks, current.suspectedWrongClicks, current.stream.totalLosses, current.path)
    }

    private fun stop(reason: String) = safely {
        session?.let { finish(it, reason, "aborted") }
        finishedSimulationScreen = simulationScreen
        simulationScreen = null
    }

    private fun startWriter(current: Session) {
        // A non-daemon worker drains and closes on client stop even when the
        // main thread has already finished. Input callbacks never touch disk.
        Thread({
            try {
                Files.createDirectories(current.path.parent)
                Files.newBufferedWriter(current.path, StandardCharsets.UTF_8).use { writer -> writeLoop(current, writer) }
            } catch (error: Exception) {
                current.failed = true
                current.stream.close()
                AsthoonLite.LOGGER.error("[ASL-TERMINAL] Could not write {}. Terminal inputs continue normally.", current.path, error)
            }
        }, "asthoonlite-terminal-capture-${current.id.take(8)}").apply { isDaemon = false; start() }
    }

    private fun writeLoop(current: Session, writer: BufferedWriter) {
        var flushedAt = System.nanoTime()
        while (!current.stream.drained()) {
            val record = current.stream.poll()
            if (record != null) {
                writer.write(TerminalCaptureFormat.jsonLine(record))
                writer.newLine()
            } else Thread.sleep(10)
            val now = System.nanoTime()
            if (now - flushedAt >= 500_000_000L) { writer.flush(); flushedAt = now }
        }
        writer.flush()
    }

    private fun actionName(action: Int): String = when (action) { 0 -> "release"; 1 -> "press"; 2 -> "repeat"; else -> "unknown" }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            if (!reportedHookError) {
                reportedHookError = true
                AsthoonLite.LOGGER.error("[ASL-TERMINAL] Input recording hook failed. Terminal inputs continue normally.", error)
            }
        }
    }
}
