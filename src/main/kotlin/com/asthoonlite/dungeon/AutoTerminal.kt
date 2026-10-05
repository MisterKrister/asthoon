package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Advanced automatic terminal clicker with RSM-compatible preset support,
 * safe Gaussian random delay intervals, break threshold protection,
 * and Melody skip / party announce features.
 */
object AutoTerminal {
    private var lastClickAt = 0L
    private var terminalOpenedAt = 0L
    private var suppressReopenUntil = 0L
    private var lastTerminalTitle: String? = null
    private var firstClickPending = true
    private var currentClickDelayMs = 0L
    private val melodySkipQueue = ArrayDeque<Int>()
    private val clickedSlotsWithTime = HashMap<Int, Long>()
    private var lastRubixTarget: Int? = null
    private val rubixLastClickedColor = HashMap<Int, Int>()
    private var lastMelodyRow = -1
    private var lastMelodyRowClickAt = 0L

    private const val CLICK_TIMEOUT_MS = 350L
    private const val RUBIX_REPEAT_GUARD_MS = 70L

    /** One server round trip: how long to wait for the previous click's slot
     *  update before reading the next pane. Shorter than any click delay, so
     *  it never becomes the thing pacing the terminal. */
    private const val TARGET_SETTLE_MS = 60L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun isRecentlyClicked(slot: Int, now: Long): Boolean {
        val last = clickedSlotsWithTime[slot] ?: return false
        return now - last < CLICK_TIMEOUT_MS
    }

    private fun clearCarried(screen: AbstractContainerScreen<*>, player: net.minecraft.world.entity.player.Player) {
        if (!screen.menu.carried.isEmpty) {
            screen.menu.carried = ItemStack.EMPTY
        }
        if (!player.containerMenu.carried.isEmpty) {
            player.containerMenu.carried = ItemStack.EMPTY
        }
    }

    private fun tick() {
        if (!Config.autoTerminalEnabled) { reset(); return }
        // The p3 simulator and practice worlds are never a dungeon run, but a
        // terminal title is all the identification any of them need. The
        // dungeon check stays as a safety net for `autoTerminalAnywhere = off`.
        if (!DungeonContext.inDungeon && !Config.autoTerminalAnywhere) { reset(); return }

        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*> ?: run { reset(); return }
        val player = mc.player ?: return
        clearCarried(screen, player)

        val title = screen.title.string
        val cleanTitle = ChatFormatting.stripFormatting(title)?.trim() ?: title.trim()
        if (suppressReopenUntil > System.currentTimeMillis() && typeFor(cleanTitle) != null) {
            mc.setScreen(null)
            return
        }
        val type = typeFor(cleanTitle) ?: run { reset(); return }
        if (!isTypeEnabled(type)) { reset(); return }

        val gameMode = mc.gameMode ?: return
        val now = System.currentTimeMillis()
        val windowId = screen.menu.containerId

        // Hypixel replaces the window ID after clicks; the terminal session continues.
        if (beginTerminal(cleanTitle, now)) {
            currentClickDelayMs = if (type == Type.MELODY) {
                Config.autoTerminalMelodyFirstClickDelayMs.toLong()
            } else {
                nextFirstClickDelayMs()
            }

            // Melody party announcement
            if (type == Type.MELODY && Config.autoTerminalAnnounceMelody) {
                val msg = Config.autoTerminalMelodyMessage.trim()
                if (msg.isNotEmpty()) {
                    mc.player?.connection?.sendCommand("pc $msg")
                }
            }
        }

        // The pointer is on screen from the moment the terminal opens, not from
        // the first solve — otherwise a terminal that has not been clicked yet
        // (or a run where the solver has nothing to do yet) leaves nothing to
        // look at, and it is impossible to tell the feature from a dead one.
        // After beginTerminal, because starting a session resets the pointer.
        if (Config.autoTerminalCursorGlide && (type != Type.MELODY || Config.autoTerminalCursorMelody)) {
            TerminalCursor.show()
        }

        // Dedicated Melody handling: does not stall behind standard click delays
        if (type == Type.MELODY) {
            if (firstClickPending && now - terminalOpenedAt < currentClickDelayMs) {
                return
            }
            firstClickPending = false

            // Pointer still travelling to the previous pane: melody waits for
            // it rather than clicking underneath it. Only matters when the
            // melody glide is on — its 40 ms cadence does not survive the trip,
            // which is why that switch is off by default.
            if (Config.autoTerminalCursorGlide && Config.autoTerminalCursorMelody && TerminalCursor.busy()) return

            val slots = screen.menu.slots
            val size = type.slotCount
            if (slots.size < size) return
            val items = slots.take(size).map { it.item }
            val click = melodyClick(items, now)

            if (click != null) {
                // Melody runs on a 40 ms cadence, so the pointer glide is off
                // unless it is switched on explicitly — see CursorGlide.
                if (glideIfEnabled(screen, player, windowId, type, click, clickNotBeforeMs = now)) return
                fireClick(screen, player, windowId, type, click, clickDelayMs = 40L)
                return
            }

            // Process queued Melody skip clicks only if the real active note is not ready right now
            // (the skip queue is deliberately instant even with the pointer on:
            //  it is a burst, and gliding each hop would drop the cadence.)
            if (melodySkipQueue.isNotEmpty()) {
                if (now - lastClickAt >= 40L) {
                    val nextSlot = melodySkipQueue.removeFirst()
                    gameMode.handleContainerInput(windowId, nextSlot, 2, ContainerInput.CLONE, player)
                    clearCarried(screen, player)
                    recordClick(now, nextSlot, 40L)
                }
            }
            return
        }

        // Pointer still travelling — or parked on the pane waiting out the
        // terminal's own delay. Either way two clicks must never race, and the
        // drawn pointer has to stay one step ahead of the packets.
        if (TerminalCursor.busy()) return

        // One server round trip after the previous click, so the slot update it
        // triggered has landed before the next pane is read. The configured
        // click delay no longer gates this stage: the pointer covers that
        // window on its way to the pane, and the click fires when both the
        // pointer and the terminal are ready.
        if (now - lastClickAt < TARGET_SETTLE_MS) return

        // With no pointer in play the delay gates target selection exactly as
        // it always did — the Rubix chooser records the pane it picks, so it
        // must only run when a click is actually about to go out. With a
        // pointer, selection runs during the delay window and the flight
        // carries it to the pane.
        if (!Config.autoTerminalCursorGlide && !canClick(now)) return

        val slots = screen.menu.slots
        val size = type.slotCount
        if (slots.size < size) return
        val items = slots.take(size).map { it.item }
        val click = nextClick(type, cleanTitle, items, now) ?: return

        // Rubix repeat guard
        if (click.slot == lastSlot && type == Type.RUBIX && now - lastClickAt < RUBIX_REPEAT_GUARD_MS) return

        if (glideIfEnabled(screen, player, windowId, type, click, clickNotBeforeMs = clickNotBeforeAt())) return

        // No pointer in play (glide switched off, or no screen coordinate for
        // the slot): fall back to plain delay timing.
        if (!canClick(now)) return
        fireClick(screen, player, windowId, type, click, nextClickDelayMs())
    }

    /**
     * The instant the terminal will accept the next click — the same instant
     * [canClick] tests, expressed as a timestamp so the glide can be timed to
     * land on it instead of starting after it.
     */
    private fun clickNotBeforeAt(): Long {
        val base = if (firstClickPending) terminalOpenedAt else lastClickAt
        return base + currentClickDelayMs
    }

    /**
     * Sends the pointer to the pane first and queues the click behind it.
     * Returns false — and the caller clicks straight away — when the glide is
     * switched off, when there is no screen coordinate for the slot, or when a
     * flight is already running.
     *
     * [clickNotBeforeMs] is the terminal's own clock: the pointer is stretched
     * to arrive on it when it has further to go, and the click is held at the
     * pane when the pointer wins the race.
     */
    private fun glideIfEnabled(
        screen: AbstractContainerScreen<*>,
        player: net.minecraft.world.entity.player.Player,
        windowId: Int,
        type: Type,
        click: Click,
        clickNotBeforeMs: Long,
        clickDelayMs: Long? = null
    ): Boolean {
        if (!Config.autoTerminalCursorGlide) return false
        // Melody is opt-in: its 40 ms cadence and a travel animation do not mix.
        if (type == Type.MELODY && !Config.autoTerminalCursorMelody) return false
        val target = TerminalCursor.targetFor(screen, click.slot) ?: return false
        return TerminalCursor.glideTo(target.first, target.second, clickNotBeforeMs) {
            // The screen may have closed while the pointer was in the air;
            // firing at a dead window id would just desync the container.
            if (Minecraft.getInstance().screen !== screen) return@glideTo
            fireClick(screen, player, windowId, type, click, clickDelayMs ?: nextClickDelayMs())
        }
    }

    /** The packet itself. Shared by the immediate and the glided path. */
    private fun fireClick(
        screen: AbstractContainerScreen<*>,
        player: net.minecraft.world.entity.player.Player,
        windowId: Int,
        type: Type,
        click: Click,
        clickDelayMs: Long
    ) {
        val gameMode = Minecraft.getInstance().gameMode ?: return
        // Rubix accepts left-click (0) with PICKUP.
        // Other terminals use middle-click (CLONE) to prevent client inventory desyncs.
        if (type == Type.RUBIX) {
            gameMode.handleContainerInput(windowId, click.slot, click.button, ContainerInput.PICKUP, player)
        } else {
            gameMode.handleContainerInput(windowId, click.slot, 2, ContainerInput.CLONE, player)
        }
        clearCarried(screen, player)
        recordClick(System.currentTimeMillis(), click.slot, clickDelayMs)
    }

    private var lastSlot = -1

    internal fun beginTerminal(title: String, now: Long): Boolean {
        if (lastTerminalTitle == title) return false
        reset()
        lastTerminalTitle = title
        terminalOpenedAt = now
        lastClickAt = now
        return true
    }

    internal fun canClick(now: Long): Boolean =
        now - (if (firstClickPending) terminalOpenedAt else lastClickAt) >= currentClickDelayMs

    internal fun recordClick(now: Long, slot: Int, delayMs: Long) {
        lastClickAt = now
        lastSlot = slot
        clickedSlotsWithTime[slot] = now
        firstClickPending = false
        currentClickDelayMs = delayMs
    }

    private data class Click(val slot: Int, val button: Int = 0)
    private enum class Type(val slotCount: Int) { COLORS(54), MELODY(54), NUMBERS(36), REDGREEN(45), RUBIX(45), STARTWITH(45) }

    fun isTerminalTitle(title: String): Boolean {
        val clean = ChatFormatting.stripFormatting(title)?.trim() ?: title.trim()
        return typeFor(clean) != null
    }

    private fun isTypeEnabled(type: Type): Boolean = when (type) {
        Type.COLORS -> Config.autoTermColors
        Type.MELODY -> Config.autoTermMelody
        Type.NUMBERS -> Config.autoTermNumbers
        Type.REDGREEN -> Config.autoTermRedGreen
        Type.RUBIX -> Config.autoTermRubix
        Type.STARTWITH -> Config.autoTermStartsWith
    }

    private fun typeFor(title: String): Type? = when {
        title.startsWith("Select all the ", true) -> Type.COLORS
        title.startsWith("Click the button on time!", true) -> Type.MELODY
        title.startsWith("Click in order!", true) -> Type.NUMBERS
        title.startsWith("Correct all the panes!", true) -> Type.REDGREEN
        title.startsWith("Change all to same color!", true) -> Type.RUBIX
        title.startsWith("What starts with:", true) -> Type.STARTWITH
        else -> null
    }

    private fun nextClick(type: Type, title: String, items: List<ItemStack>, now: Long): Click? = when (type) {
        Type.NUMBERS -> items.mapIndexedNotNull { i, stack ->
            if (isRecentlyClicked(i, now)) null
            else if (stack.`is`(Items.RED_STAINED_GLASS_PANE)) i to stack.count
            else null
        }
            .sortedBy { it.second }
            .take(NUMBER_TERM_COUNT)
            .minByOrNull { it.second }
            ?.let { Click(it.first) }

        Type.REDGREEN -> items.indices.firstOrNull { i ->
            if (isRecentlyClicked(i, now)) false
            else items[i].`is`(Items.RED_STAINED_GLASS_PANE)
        }?.let { Click(it) }

        Type.COLORS -> {
            val match = Regex("Select all the (.+?) items!?", RegexOption.IGNORE_CASE).find(title)
                ?: return null
            val wanted = match.groupValues[1]
            items.indices.firstOrNull { i ->
                if (isRecentlyClicked(i, now)) return@firstOrNull false
                val stack = items[i]
                if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@firstOrNull false
                if (TerminalHelper.isSelected(stack)) return@firstOrNull false
                TerminalHelper.matchesColor(stack, wanted)
            }?.let { Click(it) }
        }

        Type.STARTWITH -> {
            val match = Regex("What starts with: '(.+?)'\\??", RegexOption.IGNORE_CASE).find(title)
                ?: return null
            val wanted = match.groupValues[1].lowercase(java.util.Locale.ROOT)
            items.indices.firstOrNull { i ->
                if (isRecentlyClicked(i, now)) return@firstOrNull false
                val stack = items[i]
                if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@firstOrNull false
                if (TerminalHelper.isSelected(stack)) return@firstOrNull false
                val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(java.util.Locale.ROOT) ?: return@firstOrNull false
                name.startsWith(wanted)
            }?.let { Click(it) }
        }

        Type.RUBIX -> rubixClick(items, now)
        Type.MELODY -> melodyClick(items, now)
    }

    private fun rubixClick(items: List<ItemStack>, now: Long): Click? {
        val allowed = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        val panes = allowed.mapNotNull { slot ->
            val stack = items.getOrNull(slot) ?: return@mapNotNull null
            val idx = TerminalHelper.rubixColorIndex(stack)
            if (idx >= 0) slot to idx else null
        }
        if (panes.size < 9) return null

        val target: Int
        if (lastRubixTarget != null) {
            target = lastRubixTarget!!
        } else {
            val costs = IntArray(5)
            for (t in 0..4) {
                for (p in panes) {
                    val fwd = (t - p.second + 5) % 5
                    costs[t] += fwd
                }
            }
            target = costs.indices.minByOrNull { costs[it] } ?: return null
            lastRubixTarget = target
        }

        val mismatches = panes.filter { it.second != target }
        if (mismatches.isEmpty()) return null

        // Round-robin: pick a slot whose server packet has updated or whose click timed out
        val readyMismatches = mismatches.filter { (slot, color) ->
            val lastClicked = rubixLastClickedColor[slot]
            lastClicked == null || lastClicked != color || (now - (clickedSlotsWithTime[slot] ?: 0L) >= CLICK_TIMEOUT_MS)
        }

        val chosen = readyMismatches.firstOrNull { it.first != lastSlot }
            ?: readyMismatches.firstOrNull()
            ?: return null

        rubixLastClickedColor[chosen.first] = chosen.second
        // Use left click (button 0) to cycle forward (+1)
        return Click(chosen.first, 0)
    }

    private fun melodyClick(items: List<ItemStack>, now: Long): Click? {
        val magentaSlot = (45..53).firstOrNull { slot ->
            items.getOrNull(slot)?.`is`(Items.MAGENTA_STAINED_GLASS_PANE) == true
        } ?: items.indexOfFirst { it.`is`(Items.MAGENTA_STAINED_GLASS_PANE) }
        if (magentaSlot < 0) return null
        val targetCol = (magentaSlot % 9) - 1
        if (targetCol !in 0..4) return null

        var activeRow = -1
        var movingCol = -1

        for (r in 0..3) {
            val buttonSlot = (r + 1) * 9 + 7
            val buttonStack = items.getOrNull(buttonSlot) ?: continue
            val rowPaneSlots = ((r + 1) * 9 + 1)..((r + 1) * 9 + 5)
            val rowPanes = rowPaneSlots.mapNotNull { items.getOrNull(it) }

            val isRowCompleted = buttonStack.`is`(Items.LIME_TERRACOTTA) ||
                buttonStack.`is`(Items.LIME_STAINED_GLASS_PANE) ||
                buttonStack.`is`(Items.LIME_CONCRETE) ||
                buttonStack.`is`(Items.EMERALD_BLOCK) ||
                rowPanes.all { it.`is`(Items.LIME_STAINED_GLASS_PANE) || it.`is`(Items.GREEN_STAINED_GLASS_PANE) }

            if (isRowCompleted) continue

            val limePaneIndex = rowPaneSlots.firstOrNull { slot ->
                val stack = items.getOrNull(slot) ?: return@firstOrNull false
                stack.`is`(Items.LIME_STAINED_GLASS_PANE) || stack.`is`(Items.GREEN_STAINED_GLASS_PANE)
            }

            if (limePaneIndex != null) {
                activeRow = r
                movingCol = (limePaneIndex % 9) - 1
                break
            }
        }

        if (activeRow !in 0..3 || movingCol != targetCol) return null

        // Prevent spam-clicking the same row during a single alignment window (debounce 250ms)
        if (activeRow == lastMelodyRow && now - lastMelodyRowClickAt < 250L) return null

        val clickedSlot = (activeRow + 1) * 9 + 7

        // Melody Skip feature
        if (Config.autoTerminalMelodySkip) {
            val skipAllowed = !(activeRow == 0 && Config.autoTerminalDontSkipFirst)
            if (skipAllowed && activeRow < 3) {
                melodySkipQueue.clear()
                for (r in (activeRow + 1)..3) {
                    melodySkipQueue.add((r + 1) * 9 + 7)
                }
            }
        }

        lastMelodyRow = activeRow
        lastMelodyRowClickAt = now
        return Click(clickedSlot)
    }

    private const val NUMBER_TERM_COUNT = 10

    private fun gaussianRandom(minimum: Int, maximum: Int): Int {
        val minValue = min(minimum, maximum)
        val maxValue = max(minimum, maximum)
        if (minValue == maxValue) return minValue

        val u1 = 1.0 - Random.nextDouble()
        val u2 = 1.0 - Random.nextDouble()
        val gaussian = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)

        val mean = minValue + (maxValue - minValue) / 2.0
        val stdDev = (maxValue - minValue) / 6.0
        val result = gaussian * stdDev + mean

        return result.coerceIn(minValue.toDouble(), maxValue.toDouble()).toInt()
    }

    private fun nextFirstClickDelayMs(): Long {
        val base = Config.autoTerminalFirstClickDelayMs.coerceAtLeast(0)
        if (!Config.autoTerminalRandomDelay) return base.toLong()
        val min = (base - 30).coerceAtLeast(50)
        val max = base + 30
        return gaussianRandom(min, max).toLong()
    }

    private fun nextClickDelayMs(): Long {
        if (!Config.autoTerminalRandomDelay) {
            return Config.autoTerminalClickDelayMs.coerceAtLeast(0).toLong()
        }

        val target = Config.autoTerminalClickDelayMs.coerceAtLeast(0)
        val minDelay = Config.autoTerminalMinRandomDelayMs.coerceAtLeast(0)
        val maxDelay = Config.autoTerminalMaxRandomDelayMs.coerceAtLeast(0)

        val lower = if (minDelay > 0 && maxDelay >= minDelay) minDelay else (target - 20).coerceAtLeast(50)
        val upper = if (maxDelay > 0 && maxDelay >= lower) maxDelay else (target + 20).coerceAtLeast(lower)

        return gaussianRandom(lower, upper).toLong()
    }

    fun onEscape() {
        suppressReopenUntil = System.currentTimeMillis() + 750L
        reset()
    }

    private fun reset() {
        TerminalCursor.reset()
        lastClickAt = 0L
        terminalOpenedAt = 0L
        lastSlot = -1
        lastTerminalTitle = null
        firstClickPending = true
        currentClickDelayMs = 0L
        melodySkipQueue.clear()
        clickedSlotsWithTime.clear()
        lastRubixTarget = null
        rubixLastClickedColor.clear()
        lastMelodyRow = -1
        lastMelodyRowClickAt = 0L
    }
}
