package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalSolver.Kind
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Advanced automatic terminal clicker with RSM-compatible preset support,
 * safe Gaussian random delay intervals, and Melody skip / party announce
 * features.
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

        val cleanTitle = TerminalSolver.cleanTitle(screen.title.string)
        val kind = TerminalSolver.kindOf(cleanTitle) ?: run { reset(); return }

        val now = System.currentTimeMillis()
        if (suppressReopenUntil > now) {
            mc.setScreen(null)
            return
        }

        val gameMode = mc.gameMode ?: return
        val windowId = screen.menu.containerId

        // The pointer is on screen from the moment the terminal opens, not
        // from the first solve — otherwise a terminal that has not been
        // clicked yet leaves nothing to look at, and it is impossible to tell
        // the feature from a dead one. Shown before the type gate on purpose:
        // a terminal the player asked to leave alone still shows a pointer,
        // it just never clicks under it.
        // After beginTerminal, because starting a session resets the pointer.
        if (Config.autoTerminalCursorGlide && (kind != Kind.MELODY || Config.autoTerminalCursorMelody)) {
            TerminalCursor.show()
        }

        if (!isTypeEnabled(kind)) { resetSolver(); return }

        // Hypixel replaces the window ID after clicks; the terminal session continues.
        if (beginTerminal(cleanTitle, now)) {
            currentClickDelayMs = if (kind == Kind.MELODY) {
                Config.autoTerminalMelodyFirstClickDelayMs.toLong()
            } else {
                nextFirstClickDelayMs()
            }

            // Melody party announcement
            if (kind == Kind.MELODY && Config.autoTerminalAnnounceMelody) {
                val msg = Config.autoTerminalMelodyMessage.trim()
                if (msg.isNotEmpty()) {
                    mc.player?.connection?.sendCommand("pc $msg")
                }
            }
        }

        // Dedicated Melody handling: does not stall behind standard click delays
        if (kind == Kind.MELODY) {
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
            val size = kind.slotCount
            if (slots.size < size) return
            val items = slots.take(size).map { it.item }
            val click = melodyClick(items, now)

            if (click != null) {
                // Melody runs on a 40 ms cadence, so the pointer glide is off
                // unless it is switched on explicitly — see CursorGlide.
                if (glideIfEnabled(screen, player, windowId, kind, click, clickNotBeforeMs = now)) return
                fireClick(screen, player, windowId, kind, click, clickDelayMs = 40L)
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
        val size = kind.slotCount
        if (slots.size < size) return
        val items = slots.take(size).map { it.item }
        val click = nextClick(kind, cleanTitle, items, now) ?: return

        // Rubix repeat guard
        if (click.slot == lastSlot && kind == Kind.RUBIX && now - lastClickAt < RUBIX_REPEAT_GUARD_MS) return

        if (glideIfEnabled(screen, player, windowId, kind, click, clickNotBeforeMs = clickNotBeforeAt())) return

        // No pointer in play (glide switched off, or no screen coordinate for
        // the slot): fall back to plain delay timing.
        if (!canClick(now)) return
        fireClick(screen, player, windowId, kind, click, nextClickDelayMs())
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
     * [clickNotBeforeMs] is the terminal's own clock: the pointer fits its
     * trip inside that window so it is never the thing pacing the terminal,
     * and the click is held at the pane when the pointer wins the race.
     */
    private fun glideIfEnabled(
        screen: AbstractContainerScreen<*>,
        player: net.minecraft.world.entity.player.Player,
        windowId: Int,
        kind: Kind,
        click: Click,
        clickNotBeforeMs: Long,
        clickDelayMs: Long? = null
    ): Boolean {
        if (!Config.autoTerminalCursorGlide) return false
        // Melody is opt-in: its 40 ms cadence and a travel animation do not mix.
        if (kind == Kind.MELODY && !Config.autoTerminalCursorMelody) return false
        val target = TerminalCursor.targetFor(screen, click.slot) ?: return false
        return TerminalCursor.glideTo(target.first, target.second, clickNotBeforeMs) {
            // The screen may have closed while the pointer was in the air;
            // firing at a dead window id would just desync the container.
            if (Minecraft.getInstance().screen !== screen) return@glideTo
            fireClick(screen, player, windowId, kind, click, clickDelayMs ?: nextClickDelayMs())
        }
    }

    /** The packet itself. Shared by the immediate and the glided path. */
    private fun fireClick(
        screen: AbstractContainerScreen<*>,
        player: net.minecraft.world.entity.player.Player,
        windowId: Int,
        kind: Kind,
        click: Click,
        clickDelayMs: Long
    ) {
        val gameMode = Minecraft.getInstance().gameMode ?: return
        // Rubix accepts left-click (0) with PICKUP.
        // Other terminals use middle-click (CLONE) to prevent client inventory desyncs.
        if (kind == Kind.RUBIX) {
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

    /**
     * The clicker's live view of the terminal, read by the next-click marker
     * so the ring lands on the pane the packets are actually about to go to.
     *
     * [unsettledSlots] are panes hit but not yet confirmed by the server —
     * clicking one again is what makes a solver look stuck. [lastClickedSlot]
     * is where the pointer is coming from. [rubixTargetOrNull] is the colour
     * Rubix has committed to; null before the first read.
     */
    internal fun unsettledSlots(now: Long = System.currentTimeMillis()): Set<Int> =
        clickedSlotsWithTime.filterValues { now - it < CLICK_TIMEOUT_MS }.keys.toSet()

    internal fun lastClickedSlot(): Int? = lastSlot.takeIf { it >= 0 }

    internal fun rubixTargetOrNull(): Int? = lastRubixTarget

    private data class Click(val slot: Int, val button: Int = 0)

    fun isTerminalTitle(title: String): Boolean = TerminalSolver.isTerminalTitle(title)

    /**
     * Whether this terminal type is switched on.
     *
     * All six off used to mean "nothing may run", which is how a player who
     * had only ever touched the master switch got a clicker that sat there.
     * It now means "no filter chosen": every terminal runs. The switches stay
     * real — turn any one of them on and the rest are excluded, which is what
     * a filter is for.
     */
    private fun isTypeEnabled(kind: Kind): Boolean = typeAllowed(kind, selectedTypes())

    /** The types the player has explicitly allowed. Empty == no filter. */
    private fun selectedTypes(): Set<Kind> = buildSet {
        if (Config.autoTermColors) add(Kind.SELECT)
        if (Config.autoTermMelody) add(Kind.MELODY)
        if (Config.autoTermNumbers) add(Kind.ORDER)
        if (Config.autoTermRedGreen) add(Kind.PANES)
        if (Config.autoTermRubix) add(Kind.RUBIX)
        if (Config.autoTermStartsWith) add(Kind.STARTS)
    }

    /**
     * The policy itself, split out so it can be pinned without a config file.
     * An empty [selected] is not "nothing is allowed" — it is "the player has
     * not expressed a preference", and the master switch alone has to be
     * enough to make the clicker do something.
     */
    internal fun typeAllowed(kind: Kind, selected: Set<Kind>): Boolean =
        selected.isEmpty() || kind in selected

    /**
     * The next pane to click, ordered by where the pointer already is rather
     * than by slot number.
     *
     * Candidate selection lives in [TerminalSolver] — the same list the
     * highlight reads — so the drawn solver and the packets cannot disagree.
     * What is left here are the guards that belong to *clicking*: the
     * server-round-trip window, Rubix's sticky target and its per-slot
     * colour bookkeeping.
     */
    private fun nextClick(kind: Kind, title: String, items: List<ItemStack>, now: Long): Click? {
        // Slots whose last click has not come back yet are off limits: picking
        // one means clicking a pane the solver has already dealt with.
        val blocked = items.indices.filterTo(mutableSetOf()) { isRecentlyClicked(it, now) }

        if (kind == Kind.RUBIX) return rubixClick(kind, title, items, blocked, now)

        val candidates = TerminalSolver.clickCandidates(title, items, blocked)
        val slot = choose(candidates, kind) ?: return null
        return Click(slot)
    }

    /**
     * Nearest candidate to the pane the pointer is on, ignoring that pane
     * itself while anything else is available — otherwise "nearest" is always
     * "the one I just clicked" and the clicker would sit on it forever.
     */
    private fun choose(candidates: List<Int>, kind: Kind): Int? {
        if (candidates.isEmpty()) return null
        val elsewhere = candidates.filter { it != lastSlot }
        val pool = if (elsewhere.isNotEmpty()) elsewhere else candidates
        return TerminalClickOrder.pickNearest(pool, lastSlot.takeIf { it >= 0 }, kind.slotCount)
    }

    private fun rubixClick(kind: Kind, title: String, items: List<ItemStack>, blocked: Set<Int>, now: Long): Click? {
        // The target colour sticks for the life of the terminal: switching
        // target mid-solve would leave panes stranded between two colours.
        if (lastRubixTarget == null) {
            lastRubixTarget = TerminalSolver.optimalRubixTarget(items.take(kind.slotCount)) ?: return null
        }

        val mismatches = TerminalSolver.clickCandidates(title, items, blocked, lastRubixTarget)
        if (mismatches.isEmpty()) return null

        // Round-robin: only take a pane whose colour has actually changed
        // since the last click on it, or whose packet has timed out.
        val ready = mismatches.filter { slot ->
            val color = TerminalHelper.rubixColorIndex(items[slot])
            val last = rubixLastClickedColor[slot]
            last == null || last != color || (now - (clickedSlotsWithTime[slot] ?: 0L)) >= CLICK_TIMEOUT_MS
        }

        val slot = choose(ready, kind) ?: return null
        rubixLastClickedColor[slot] = TerminalHelper.rubixColorIndex(items[slot])
        // Left click cycles forward; that is the only button Rubix takes.
        return Click(slot, 0)
    }

    private fun melodyClick(items: List<ItemStack>, now: Long): Click? {
        // Which row is aligned lives with the solver so the highlight and the
        // clicker can never point at different rows. What is left here is the
        // clicker's business: how often it is willing to fire, and the skip
        // queue that rides along behind it.
        val clickedSlot = TerminalSolver.melodyCandidate(items) ?: return null
        val activeRow = clickedSlot / TerminalClickOrder.COLS - 1

        // Prevent spam-clicking the same row during a single alignment window (debounce 250ms)
        if (activeRow == lastMelodyRow && now - lastMelodyRowClickAt < 250L) return null

        // Melody Skip feature: park the pointer on the rows still to come so
        // they are already in place when their window opens. The buttons live
        // at column 7 of rows 1..3 — slot 16, 25, 34 — and activeRow counts
        // them from zero, so the last one is 2, not 3: queuing one past it
        // used to send the pointer at slot 43, which is the indicator row and
        // has nothing to click.
        if (Config.autoTerminalMelodySkip) {
            val skipAllowed = !(activeRow == 0 && Config.autoTerminalDontSkipFirst)
            if (skipAllowed && activeRow < 2) {
                melodySkipQueue.clear()
                for (row in (activeRow + 1)..2) {
                    melodySkipQueue.add((row + 1) * TerminalClickOrder.COLS + 7)
                }
            }
        }

        lastMelodyRow = activeRow
        lastMelodyRowClickAt = now
        return Click(clickedSlot)
    }

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

    /**
     * Clears solver state without taking the pointer off the screen.
     *
     * [reset] is the "this terminal is gone" path and drops the pointer with
     * it. When a terminal is open but its type is switched off, the clicker
     * has nothing to forget — the pointer stays where it is, because the
     * thing that made it appear (a terminal on screen) is still true.
     */
    private fun resetSolver() {
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

    private fun reset() {
        TerminalCursor.reset()
        resetSolver()
    }
}
