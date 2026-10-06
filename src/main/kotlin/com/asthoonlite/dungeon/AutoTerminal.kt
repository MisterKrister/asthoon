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
    /** Each pane's ring position as *we* last knew it. Advanced the moment a
     *  packet leaves, not when the reply lands — see [fireClick]. */
    private val rubixPredicted = HashMap<Int, Int>()
    private var lastMelodyRow = -1
    private var lastMelodyRowClickAt = 0L

    private const val CLICK_TIMEOUT_MS = 350L
    private const val RUBIX_REPEAT_GUARD_MS = 70L

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

    /** Internal rather than private only because [TerminalCursor] drives it per
     *  frame — see the note where it is called. The END_CLIENT_TICK listener
     *  below stays as the floor for frames that do not extract. */
    internal fun tick() {
        if (!Config.autoTerminalEnabled || (!DungeonContext.inDungeon && !Config.autoTerminalAnywhere)) {
            reset()
            return
        }
        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*> ?: run { reset(); return }
        val player = mc.player ?: run { reset(); return }
        val title = TerminalSolver.cleanTitle(screen.title.string)
        val kind = TerminalSolver.kindOf(title) ?: run { reset(); return }
        val now = System.currentTimeMillis()
        if (suppressReopenUntil > now) {
            reset()
            mc.setScreen(null)
            return
        }
        clearCarried(screen, player)
        val glide = Config.autoTerminalCursorGlide && (kind != Kind.MELODY || Config.autoTerminalCursorMelody)
        if (!isTypeEnabled(kind)) {
            resetSolver()
            if (glide) TerminalCursor.show() else TerminalCursor.reset()
            return
        }
        if (beginTerminal(title, now)) {
            currentClickDelayMs = if (kind == Kind.MELODY) Config.autoTerminalMelodyFirstClickDelayMs.toLong()
                else nextFirstClickDelayMs()
            if (kind == Kind.MELODY && Config.autoTerminalAnnounceMelody) {
                val message = Config.autoTerminalMelodyMessage.trim()
                if (message.isNotEmpty()) player.connection.sendCommand("pc $message")
            }
        }
        if (glide) TerminalCursor.show() else TerminalCursor.reset()
        if (screen.menu.slots.size < kind.slotCount) return
        val items = screen.menu.slots.take(kind.slotCount).map { it.item }

        if (kind == Kind.MELODY) {
            if (!Config.autoTerminalMelodySkip) melodySkipQueue.clear()
            // Keep the next row under the pointer while the old row awaits acknowledgement.
            melodyAimSlot(items, now)?.let { aim(screen, kind, it, now + 40L) }
            if (!canClick(now)) return
            val click = melodyClick(items, now)
            if (click != null) {
                aim(screen, kind, click.slot, now)
                if (fireClick(screen, player, screen.menu.containerId, kind, click, 40L)) {
                    recordMelodyClick(items, click.slot, now, Config.autoTerminalMelodySkip, Config.autoTerminalDontSkipFirst)
                    TerminalSolver.melodyNextButton(items, click.slot)?.let { aim(screen, kind, it, now + 40L) }
                }
                return
            }
            // A real aligned note takes priority. Revalidate queued buttons against the live rows.
            val remaining = TerminalSolver.melodyRows(items).filter { !it.completed }.map { it.buttonSlot }.toSet()
            while (melodySkipQueue.isNotEmpty()) {
                val slot = melodySkipQueue.removeFirst()
                if (slot !in remaining || slot / 9 <= lastMelodyRow) continue
                aim(screen, kind, slot, now)
                if (fireClick(screen, player, screen.menu.containerId, kind, Click(slot), 40L)) {
                    recordMelodyClick(items, slot, now, Config.autoTerminalMelodySkip, Config.autoTerminalDontSkipFirst)
                    TerminalSolver.melodyNextButton(items, slot)?.let { aim(screen, kind, it, now + 40L) }
                }
                break
            }
            return
        }

        // Selection and travel run during the delay. Re-read live candidates each tick:
        // no callback can retain an obsolete slot, player or window ID.
        val click = nextClick(kind, title, items, now) ?: return
        aim(screen, kind, click.slot, clickNotBeforeAt())
        if (!canClick(now)) return
        if (kind == Kind.RUBIX && click.slot == lastSlot && now - lastClickAt < RUBIX_REPEAT_GUARD_MS) return
        fireClick(screen, player, screen.menu.containerId, kind, click, nextClickDelayMs())
    }

    private fun clickNotBeforeAt(): Long =
        (if (firstClickPending) terminalOpenedAt else lastClickAt) + currentClickDelayMs

    private fun aim(screen: AbstractContainerScreen<*>, kind: Kind, slot: Int, deadline: Long) {
        if (!Config.autoTerminalCursorGlide || (kind == Kind.MELODY && !Config.autoTerminalCursorMelody)) return
        val target = TerminalCursor.targetFor(screen, slot) ?: return
        TerminalCursor.glideTo(target.first, target.second, deadline)
    }

    /** Send against the live menu. Pointer position is deliberately irrelevant. */
    private fun fireClick(
        screen: AbstractContainerScreen<*>,
        player: net.minecraft.world.entity.player.Player,
        windowId: Int,
        kind: Kind,
        click: Click,
        clickDelayMs: Long
    ): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.screen !== screen || mc.player !== player || player.containerMenu !== screen.menu || screen.menu.containerId != windowId) return false
        val gameMode = mc.gameMode ?: return false
        // Rubix accepts left-click (0) with PICKUP.
        // Other terminals use middle-click (CLONE) to prevent client inventory desyncs.
        if (kind == Kind.RUBIX) {
            gameMode.handleContainerInput(windowId, click.slot, click.button, ContainerInput.PICKUP, player)
        } else {
            gameMode.handleContainerInput(windowId, click.slot, 2, ContainerInput.CLONE, player)
        }
        clearCarried(screen, player)
        recordClick(System.currentTimeMillis(), click.slot, clickDelayMs)
        // Predict on send, not on reply. The pane has been asked to move the
        // moment the packet leaves, and Rubix's next decision is made from
        // that number rather than from whatever colour is still on screen.
        if (kind == Kind.RUBIX) {
            val here = rubixPredicted[click.slot]
            if (here != null) rubixPredicted[click.slot] = TerminalSolver.rubixAdvance(here, click.button)
        }
        return true
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
        val target = lastRubixTarget ?: return null

        // Reconcile every pane the server has caught up on. A pane with a
        // click in flight keeps our number — its colour on screen is the one
        // from before the burst.
        for (slot in TerminalSolver.RUBIX_SLOTS) {
            val actual = TerminalHelper.rubixColorIndex(items.getOrNull(slot) ?: continue)
            if (actual < 0) continue
            val inFlight = now - (clickedSlotsWithTime[slot] ?: 0L) < CLICK_TIMEOUT_MS
            if (!inFlight || rubixPredicted[slot] == null) rubixPredicted[slot] = actual
        }

        // Spam: stay on the pane already being worked and hit it again until
        // it lands on the target. Bouncing to another pane between clicks is
        // what cost a full round trip per click — and it is why this terminal
        // used to be the slowest one in the room. Deliberately ignores
        // [blocked]: not waiting for the server is the entire point.
        if (lastSlot >= 0 && lastSlot in TerminalSolver.RUBIX_SLOTS) {
            val here = rubixPredicted[lastSlot]
            if (here != null && here != target) {
                return Click(lastSlot, TerminalSolver.rubixButton(here, target))
            }
        }

        val pending = TerminalSolver.RUBIX_SLOTS.filter { slot ->
            val predicted = rubixPredicted[slot] ?: return@filter false
            slot !in blocked && predicted != target
        }
        if (pending.isEmpty()) return null
        val slot = choose(pending, kind) ?: return null
        val here = rubixPredicted[slot] ?: return null
        return Click(slot, TerminalSolver.rubixButton(here, target))
    }

    private fun melodyClick(items: List<ItemStack>, now: Long): Click? {
        val slot = TerminalSolver.melodyCandidate(items) ?: return null
        if (!melodyRowReady(slot, now)) return null
        return Click(slot)
    }

    internal fun melodyRowReady(slot: Int, now: Long): Boolean =
        slot / 9 != lastMelodyRow || now - lastMelodyRowClickAt >= 250L

    internal fun melodyAimSlot(items: List<ItemStack>, now: Long): Int? {
        val active = TerminalSolver.melodyActiveButton(items)
        if (lastMelodyRow >= 0 && now - lastMelodyRowClickAt < 250L && (active == null || active / 9 == lastMelodyRow)) {
            return TerminalSolver.melodyNextButton(items, lastMelodyRow * 9 + 7)
        }
        return active ?: TerminalSolver.melodyRows(items).firstOrNull { !it.completed }?.buttonSlot
    }

    /** Commit debounce and skip bookkeeping only after an input was actually sent. */
    internal fun recordMelodyClick(items: List<ItemStack>, slot: Int, now: Long, skip: Boolean, dontSkipFirst: Boolean) {
        lastMelodyRow = slot / 9
        lastMelodyRowClickAt = now
        melodySkipQueue.clear()
        val rows = TerminalSolver.melodyRows(items)
        val firstRow = rows.firstOrNull()?.row
        if (skip && !(lastMelodyRow == firstRow && dontSkipFirst)) {
            rows.filter { !it.completed && it.row > lastMelodyRow }.forEach { melodySkipQueue.add(it.buttonSlot) }
        }
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
        rubixPredicted.clear()
        lastMelodyRow = -1
        lastMelodyRowClickAt = 0L
    }

    private fun reset() {
        TerminalCursor.reset()
        resetSolver()
    }
}
