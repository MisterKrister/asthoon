package com.asthoonlite.dungeon.simulator

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.AutoTerminal
import com.asthoonlite.dungeon.TerminalCapture
import com.asthoonlite.dungeon.TerminalCursor
import com.asthoonlite.dungeon.TermGui
import com.asthoonlite.dungeon.TerminalInput
import com.asthoonlite.dungeon.TerminalHelper
import com.asthoonlite.dungeon.TerminalSolver
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerEquipment
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import java.util.UUID
import kotlin.random.Random
import org.lwjgl.glfw.GLFW

/** Local Odin practice screens; a completed board stays visible for one second. */
object TerminalSimulator {
    const val ODIN_REVISION = "833e0533ef9c47529b790612627a65618ebd5a58"
    private var screen: TerminalSimulatorScreen? = null
    private var sessionId = ""
    private var sequence = 0
    private var completedCount = 0
    private var useAutomation = false
    private var nextContainerId = 1_000_000
    private var startRequested = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (startRequested) { startRequested = false; start() }
            val current = screen ?: return@register
            if (mc.screen !== current || mc.player == null) stop("screen_changed")
            else current.advance(nowMs())
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stop("disconnect") }
    }

    fun start(): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.player == null) {
            AsthoonLite.LOGGER.info("[ASL-TermSim] Join a world before opening the simulator")
            return false
        }
        stop("restarted")
        sessionId = UUID.randomUUID().toString()
        sequence = 0
        completedCount = 0
        useAutomation = Config.terminalSimulatorAutoEnabled
        AutoTerminal.reset()
        openNext()
        return true
    }

    fun requestStart() { startRequested = true }

    private fun openNext() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return stop("disconnect")
        val seed = Random.nextLong()
        val model = TerminalSimulation.random(seed, nowMs())
        val inventory = Inventory(player, PlayerEquipment(player))
        val container = SimpleContainer(model.items.size)
        val rows = model.items.size / 9
        val type = when (rows) {
            1 -> MenuType.GENERIC_9x1
            2 -> MenuType.GENERIC_9x2
            3 -> MenuType.GENERIC_9x3
            4 -> MenuType.GENERIC_9x4
            5 -> MenuType.GENERIC_9x5
            else -> MenuType.GENERIC_9x6
        }
        val menu = ChestMenu(type, nextContainerId++, inventory, container, rows)
        val next = TerminalSimulatorScreen(model, menu, inventory, container, Config.terminalSimulatorPingMs)
        // Set the new reference before removal of the old screen, so it cannot stop this session.
        screen = next
        sequence++
        AutoTerminal.reset()
        TerminalCursor.reset()
        mc.setScreen(next)
        TerminalCapture.beginSimulation(next, mapOf(
            "practice_session" to sessionId, "terminal_number" to sequence,
            "seed" to seed, "odin_revision" to ODIN_REVISION,
            "ping_ms" to next.pingMs, "automation_requested" to useAutomation,
            "automatic_inputs_enabled" to (useAutomation && Config.autoTerminalEnabled),
            "initial_correct_slots" to model.correctSlots
        ))
        AsthoonLite.LOGGER.info("[ASL-TermSim] Open #{} {} seed={} ping={}ms mode={}",
            sequence, model.kind, seed, next.pingMs, if (useAutomation) "current automation" else "manual capture")
    }

    internal fun completed(current: TerminalSimulatorScreen, wrongClicks: Int) {
        if (screen !== current) return
        completedCount++
        TerminalCapture.completeSimulation(current, wrongClicks)
        AsthoonLite.LOGGER.info("[ASL-TermSim] Completed #{} {} wrong={} total={}; next terminal in 1000ms",
            sequence, current.model.kind, wrongClicks, completedCount)
    }

    internal fun restart(current: TerminalSimulatorScreen) { if (screen === current) openNext() }
    internal fun removed(current: TerminalSimulatorScreen) { if (screen === current) stop("screen_removed") }

    fun stop(reason: String = "practice_stopped") {
        startRequested = false
        val current = screen ?: return
        screen = null
        current.cancel()
        if (!current.model.completed) TerminalCapture.abortSimulation(current, reason)
        AutoTerminal.reset()
        TerminalCursor.reset()
        AsthoonLite.LOGGER.info("[ASL-TermSim] Stopped: {} ({} completed)", reason, completedCount)
    }

    fun isSimulation(value: Any?): Boolean = value is TerminalSimulatorScreen
    fun allowsAutomation(value: Any?): Boolean = value === screen && useAutomation &&
        (value as? TerminalSimulatorScreen)?.model?.completed == false
    internal fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

/** Uses a separate inventory/menu; all simulator actions are applied to its own model. */
internal class TerminalSimulatorScreen(
    val model: TerminalSimulation,
    menu: ChestMenu,
    inventory: Inventory,
    private val container: SimpleContainer,
    val pingMs: Int
) : ContainerScreen(menu, inventory, Component.literal(model.title)), TerminalCapture.PracticeScreen {
    private val clock = TerminalPracticeClock()
    private var wrongClicks = 0

    init { syncItems() }

    private fun syncItems() { model.items.forEachIndexed { index, item -> container.setItem(index, item.copy()) } }

    fun submit(slot: Int, button: Int, source: String): Boolean {
        val requested = System.nanoTime()
        if (!clock.enqueue(slot, button, source, requested / 1_000_000L, pingMs, requested)) return false
        val queued = requireNotNull(clock.lastQueued)
        TerminalCapture.simulationInput(this, "click_queued", mapOf(
            "inputId" to queued.id, "slot" to slot, "button" to button, "source" to source,
            "requestedAtNs" to requested, "dueAtMs" to queued.dueAtMs, "pingMs" to pingMs
        ))
        if (model.kind == TerminalSolver.Kind.RUBIX && slot in model.correctSlots && button in 0..2) {
            val previous = AutoTerminal.rubixPredicted(slot) ?: TerminalHelper.rubixColorIndex(container.getItem(slot))
            AutoTerminal.setRubixPredicted(slot, TerminalSolver.rubixAdvance(previous, if (button == 1) 1 else 0))
        }
        return true
    }

    override fun slotClicked(slot: Slot, slotId: Int, button: Int, input: ContainerInput) {
        val index = slot.index
        val accepted = submit(index, button, "manual")
        TerminalCapture.routedClick(this, index, button, input, "manual", accepted, "simulator")
    }

    // Handle practice input locally, including empty space. Vanilla's outside-click
    // path passes a null Slot and its close path operates on player.containerMenu.
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        com.asthoonlite.utils.InputCapture.onTerminalMousePress(event.button(), event.x(), event.y())
        clickAt(event.x(), event.y(), event.button(), "manual")
        return true
    }

    private fun clickAt(x: Double, y: Double, button: Int, source: String) {
        if (TermGui.active(this)) {
            val hit = TermGui.gridFor(this)?.hitTest(x, y)
            if (hit != null) { TermGui.click(this, x, y, button, source); return }
        } else {
            val slot = menu.slots.firstOrNull {
                x >= leftPos + it.x && x < leftPos + it.x + 16 && y >= topPos + it.y && y < topPos + it.y + 16
            }
            if (slot != null) {
                TerminalInput.send(this, slot.index, button, ContainerInput.PICKUP, source)
                return
            }
        }
        TerminalCapture.routedClick(this, -999, button, ContainerInput.PICKUP, source, false, "outside_grid")
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val mc = Minecraft.getInstance()
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(event)) onClose()
        else if (mc.options.keyDrop.matches(event) || mc.options.keyHotbarSlots.any { it.matches(event) }) {
            clickAt(mc.mouseHandler.getScaledXPos(mc.window), mc.mouseHandler.getScaledYPos(mc.window),
                if (event.hasControlDown()) 1 else 0, "keyboard")
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean = true
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean = true
    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean = true

    fun advance(nowMs: Long) {
        if (model.completed) {
            if (clock.restartDue(nowMs)) TerminalSimulator.restart(this)
            return
        }
        if (model.tick(nowMs).isNotEmpty()) {
            syncItems()
            TerminalCapture.snapshot(this, "melody_tick")
        }
        while (true) {
            val click = clock.poll(nowMs) ?: break
            val result = model.applyClick(click.slot, click.button, nowMs)
            if (!result.accepted) wrongClicks++
            syncItems()
            TerminalCapture.clickOutcome(this, click.slot, click.source, result.reason, wrongClicks, mapOf(
                "inputId" to click.id, "button" to click.button, "accepted" to result.accepted,
                "requestedAtNs" to click.requestedAtNs, "dueAtMs" to click.dueAtMs,
                "appliedAtNs" to System.nanoTime(), "modelTimeMs" to nowMs,
                "modelRevision" to model.revision, "changedSlots" to result.changedSlots
            ))
            if (result.completed) {
                recordCancelled(clock.complete(nowMs), "terminal_completed")
                TerminalSimulator.completed(this, wrongClicks)
                break
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val countdown = clock.remainingMs(TerminalSimulator.nowMs())
        val label = if (countdown != null) "Completed! Next random terminal in ${"%.1f".format(countdown / 1000.0)}s"
            else "Terminal Simulator • Recording inputs • Esc to stop"
        graphics.centeredText(font, label, width / 2, height - 30, 0xFFFFFFFF.toInt())
    }

    override fun onClose() { TerminalSimulator.stop(); Minecraft.getInstance().setScreen(null) }
    override fun removed() { cancel(); TerminalSimulator.removed(this) }
    fun cancel() { recordCancelled(clock.cancel(), "screen_closed") }
    private fun recordCancelled(inputs: List<TerminalPracticeClock.PendingClick>, reason: String) {
        if (inputs.isNotEmpty()) TerminalCapture.simulationInput(this, "inputs_cancelled", mapOf(
            "inputIds" to inputs.map { it.id }, "reason" to reason
        ))
    }
}
