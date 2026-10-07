package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.config.TerminalMode
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
    private var lastContainerId = -1
    private var firstClickPending = true
    private var currentClickDelayMs = 0L
    private val melodySkipQueue = ArrayDeque<Int>()
    private val clickedSlotsWithTime = HashMap<Int, Long>()
    private val sessionClickedSlots = HashSet<Int>()
    private var lastRubixTarget: Int? = null
    /** Each pane's ring position as *we* last knew it. Advanced the moment a
     *  packet leaves, not when the reply lands — see [fireClick]. */
    private val rubixPredicted = HashMap<Int, Int>()
    private var lastMelodyRow = -1
    private var lastMelodyRowClickAt = 0L
    private var lastMelodyClickedSlot: Int? = null
    private var lastMelodyClickTime = 0L
    /** Clicks this terminal has taken since it opened, and how many it wants.
     *  Latched once on the first tick so a pane the server has already
     *  settled cannot quietly lower the denominator halfway through. */
    private var clicksMade = 0
    private var clickGoal = -1

    /**
     * How long a slot stays off limits after a click.
     *
     * The window is really a guess at the server round trip: long enough that
     * a pane whose state we have already changed is not clicked again before
     * the reply lands, because on the panes, colours and starts-with terminals
     * a second click *undoes* the first. It is a ceiling, not a delay —
     * [clickGuardMs] is what decides how much of it a terminal actually gets.
     */
    private const val CLICK_TIMEOUT_MS = 350L
    private const val RUBIX_REPEAT_GUARD_MS = 70L

    /**
     * How long the same Melody row waits before it is clicked again, before
     * [MELODY_UPDATE_GRACE_MS] is added on top.
     *
     * Melody is the one terminal whose pace is the puzzle: the beat is the row
     * moving into place, so this doubles as the retry window for a click the
     * server never acknowledged and as the pointer's travel budget on the way
     * to the next row.
     */
    internal const val MELODY_ROW_RETRY_MS = 250L

    /**
     * Extra ticks a row is given to show up before anything reacts to it.
     *
     * The retry window alone was tight enough that a row arriving a few ticks
     * late read as "never updated": the aim walked back to the row just
     * clicked, the retry fired on it, and the pointer shuttled instead of
     * sitting on the new row. This is time spent waiting rather than answering
     * — three ticks at 20 Hz, 150 ms — and it only costs anything on the one
     * path where the server is already behind. [MELODY_RETRY_TOTAL_MS] is the
     * sum, because the aim and the click have to use the same number or they
     * disagree about where the pointer belongs.
     */
    internal const val MELODY_UPDATE_TICKS = 3L
    internal const val MELODY_UPDATE_GRACE_MS = MELODY_UPDATE_TICKS * 50L
    internal const val MELODY_RETRY_TOTAL_MS = MELODY_ROW_RETRY_MS + MELODY_UPDATE_GRACE_MS

    /**
     * How far the opening beat of a terminal may stray from its setting.
     *
     * One event per terminal, so it gets a fixed spread rather than a window
     * built from the slider that governs the repeating cadence — nobody chose
     * that window, they chose the number.
     */
    private const val FIRST_CLICK_SPREAD_MS = 30L

    /** Side of the mean the jitter reaches when the Min/Max window says
     *  nothing useful about it. Matches the spread `gaussianRandom` used to
     *  synthesise when a bound was missing. */
    private const val DEFAULT_SPREAD_MS = 20L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    /**
     * The per-slot guard a terminal runs on, in milliseconds.
     *
     * Everywhere gets the round-trip ceiling except the number terminal, and
     * the exception is the whole reason this is per kind. Numbers is a chain:
     * the next candidate is *the button just clicked*, still showing the old
     * count until the reply lands, so a fixed 350 ms safety window is not a
     * safety margin there — it is the terminal's entire cadence, and it is
     * why this one ran two and a half times slower than every terminal beside
     * it. Clicking a spent number does nothing server-side, so its guard only
     * has to stop packet spam, which the click delay already does. Equal to
     * the beat, it can never outlast it.
     */
    internal fun clickGuardMs(kind: Kind, clickDelayMs: Long): Long =
        if (kind == Kind.ORDER) clickDelayMs else CLICK_TIMEOUT_MS

    private fun isRecentlyClicked(slot: Int, now: Long, timeoutMs: Long): Boolean {
        val last = clickedSlotsWithTime[slot] ?: return false
        return now - last < timeoutMs
    }

    private fun clearCarried(screen: AbstractContainerScreen<*>, player: net.minecraft.world.entity.player.Player) {
        if (!screen.menu.carried.isEmpty) {
            screen.menu.carried = ItemStack.EMPTY
        }
        if (!com.asthoonlite.dungeon.simulator.TerminalSimulator.isSimulation(screen) && !player.containerMenu.carried.isEmpty) {
            player.containerMenu.carried = ItemStack.EMPTY
        }
    }

    /** Internal rather than private only because [TerminalCursor] drives it per
     *  frame — see the note where it is called. The END_CLIENT_TICK listener
     *  below stays as the floor for frames that do not extract. */
    internal fun tick() {
        val practiceScreen = Minecraft.getInstance().screen
        val simulation = com.asthoonlite.dungeon.simulator.TerminalSimulator.isSimulation(practiceScreen)
        if (!Config.autoTerminalEnabled ||
            (simulation && !com.asthoonlite.dungeon.simulator.TerminalSimulator.allowsAutomation(practiceScreen)) ||
            (!simulation && !DungeonContext.inDungeon && !Config.autoTerminalAnywhere)) {
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
        if (lastContainerId != -1 && lastContainerId != screen.menu.containerId) {
            reset()
        }
        lastContainerId = screen.menu.containerId
        clearCarried(screen, player)
        val glide = Config.autoTerminalCursorGlide && (kind != Kind.MELODY || Config.autoTerminalCursorMelody)
        if (!isTypeEnabled(kind)) {
            resetSolver()
            if (glide) TerminalCursor.show() else TerminalCursor.reset()
            return
        }
        // If we are tracking live network packets for this container, wait until it is fully loaded
        if (TerminalSensing.inTerminal && TerminalSensing.containerId == screen.menu.containerId) {
            if (!TerminalSensing.isLoaded) return
        }
        val openedTime = if (TerminalSensing.inTerminal && TerminalSensing.containerId == screen.menu.containerId && TerminalSensing.openedAt > 0L)
            TerminalSensing.openedAt else now
        if (beginTerminal(title, openedTime)) {
            currentClickDelayMs = if (kind == Kind.MELODY) Config.autoTerminalMelodyFirstClickDelayMs.toLong()
                else nextFirstClickDelayMs()
            if (kind == Kind.MELODY && Config.autoTerminalAnnounceMelody && !simulation) {
                val message = Config.autoTerminalMelodyMessage.trim()
                if (message.isNotEmpty()) player.connection.sendCommand("pc $message")
            }
        }
        if (glide) TerminalCursor.show() else TerminalCursor.reset()
        val items = screen.menu.slots.take(kind.slotCount).map { it.item }
        // The clicker stops at what it actually needs — the slots the tiles
        // land on — not at the full chest the real terminal ships underneath
        // its own rows. Same test TermGui.covers asks, for the same reason.
        if (screen.menu.slots.size < TermGui.requiredSlotsFor(kind, items)) return
        if (clickGoal < 0) clickGoal = TerminalSolver.goalFor(title, items, lastRubixTarget)

        // Resync session clicked slots if stuck/idle for over 1000ms
        if (lastClickAt > 0L && now - lastClickAt > 1000L) {
            sessionClickedSlots.clear()
        }

        if (kind == Kind.MELODY) {
            if (!Config.autoTerminalMelodySkip) melodySkipQueue.clear()

            // When live terminal sensing is available, use exact row & column packet tracking (Noamm style)
            if (TerminalSensing.inTerminal && TerminalSensing.containerId == screen.menu.containerId) {
                val buttonRow = TerminalSensing.melodyButtonRow
                val current = TerminalSensing.melodyCurrentCol
                val correct = TerminalSensing.melodyCorrectCol

                if (buttonRow != null && buttonRow in 0..2) {
                    val targetButtonSlot = buttonRow * 9 + 16 // row 0 -> 16, row 1 -> 25, row 2 -> 34
                    aim(screen, kind, targetButtonSlot, clickNotBeforeAt())

                    if (current != null && correct != null && current == correct) {
                        val melodyDelayOk = if (firstClickPending) canClick(now)
                            else (now - lastMelodyClickTime >= 250L || lastMelodyRow != buttonRow)
                        if (melodyDelayOk && lastMelodyClickedSlot != targetButtonSlot) {
                            if (!pointerBlocksClick(glide, TerminalCursor.isMoving(now))) {
                                if (fireClick(screen, player, screen.menu.containerId, kind, Click(targetButtonSlot), nextClickDelayMs())) {
                                    lastMelodyClickedSlot = targetButtonSlot
                                    lastMelodyRow = buttonRow
                                    lastMelodyClickTime = now
                                    recordMelodyClick(items, targetButtonSlot, now, Config.autoTerminalMelodySkip, Config.autoTerminalDontSkipFirst)
                                    if (Config.autoTerminalMelodySkip && buttonRow < 2) {
                                        if (!(buttonRow == 0 && Config.autoTerminalDontSkipFirst)) {
                                            if (buttonRow < 2) melodySkipQueue.add(targetButtonSlot + 9)
                                            if (buttonRow < 1) melodySkipQueue.add(targetButtonSlot + 18)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return
            }

            // Fallback for offline tests / simulations where no network packets arrive
            val click = melodyClick(items, now)
            (click?.slot ?: melodyAimSlot(items, now))?.let { aim(screen, kind, it, clickNotBeforeAt()) }
            if (!canClick(now)) return
            if (click != null) {
                if (pointerBlocksClick(glide, TerminalCursor.isMoving(now))) return
                // Same clock as every other terminal. This used to be a flat
                // 40 ms, which is 25 clicks a second — the row debounce only
                // guards one row, so two rows ready at once fired back to
                // back and the whole thing read as a machine gun.
                if (fireClick(screen, player, screen.menu.containerId, kind, click, nextClickDelayMs())) {
                    recordMelodyClick(items, click.slot, now, Config.autoTerminalMelodySkip, Config.autoTerminalDontSkipFirst)
                    TerminalSolver.melodyNextButton(items, click.slot)?.let { aim(screen, kind, it, clickNotBeforeAt()) }
                }
                return
            }
            // A real aligned note takes priority. Revalidate queued buttons against the live rows.
            val remaining = TerminalSolver.melodyRows(items).filter { !it.completed }.map { it.buttonSlot }.toSet()
            while (melodySkipQueue.isNotEmpty()) {
                val slot = melodySkipQueue.removeFirst()
                if (slot !in remaining || slot / 9 <= lastMelodyRow) continue
                aim(screen, kind, slot, clickNotBeforeAt())
                // The queue entry is dropped when the pointer is still on its
                // way — put it back, or the row it was for would be skipped
                // without ever being clicked.
                if (pointerBlocksClick(glide, TerminalCursor.isMoving(now))) {
                    melodySkipQueue.addFirst(slot)
                    break
                }
                if (fireClick(screen, player, screen.menu.containerId, kind, Click(slot), nextClickDelayMs())) {
                    recordMelodyClick(items, slot, now, Config.autoTerminalMelodySkip, Config.autoTerminalDontSkipFirst)
                    TerminalSolver.melodyNextButton(items, slot)?.let { aim(screen, kind, it, clickNotBeforeAt()) }
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
        // The packet waits for the hand: a click has to read as the pointer
        // landing on the pane and pressing it, not as a packet that left
        // while the pointer was still crossing the grid.
        if (pointerBlocksClick(glide, TerminalCursor.isMoving(now))) return
        if (kind == Kind.RUBIX && click.slot == lastSlot && now - lastClickAt < RUBIX_REPEAT_GUARD_MS) return
        fireClick(screen, player, screen.menu.containerId, kind, click, nextClickDelayMs(lastSlot.takeIf { it >= 0 }, click.slot, kind))
    }

    /**
     * Whether the drawn pointer is still travelling to the pane this click is
     * for. True means the packet waits one more frame.
     *
     * Split out so the decision can be pinned without a config file behind it:
     * no glide means no pointer on screen, so there is nothing to wait for,
     * and a pointer that has already landed never delays anything.
     */
    internal fun pointerBlocksClick(glideOn: Boolean, pointerMoving: Boolean): Boolean =
        glideOn && pointerMoving

    private fun clickNotBeforeAt(): Long =
        (if (firstClickPending) terminalOpenedAt else lastClickAt) + currentClickDelayMs

    private fun aim(screen: AbstractContainerScreen<*>, kind: Kind, slot: Int, deadline: Long) {
        if (!Config.autoTerminalCursorGlide || (kind == Kind.MELODY && !Config.autoTerminalCursorMelody)) return
        val target = TerminalCursor.targetFor(screen, slot) ?: return
        TerminalCursor.glideTo(target.first, target.second, deadline, firstClickPending)
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
        if (mc.screen !== screen || mc.player !== player || screen.menu.containerId != windowId) return false
        // Rubix clicks use PICKUP (button 0 for left-click / forward, 1 for right-click / backward).
        // Other terminals use middle-click (2, CLONE) to prevent picking items into cursor.
        val btn = if (kind == Kind.RUBIX) click.button else 2
        val input = if (kind == Kind.RUBIX) ContainerInput.PICKUP else ContainerInput.CLONE
        val sent = TerminalInput.send(screen, click.slot, btn, input, "automatic")
        if (!sent) return false
        clearCarried(screen, player)
        recordClick(System.currentTimeMillis(), click.slot, clickDelayMs)
        if (kind == Kind.RUBIX && !com.asthoonlite.dungeon.simulator.TerminalSimulator.isSimulation(screen)) {
            val here = rubixPredicted[click.slot]
                ?: TerminalHelper.rubixColorIndex(screen.menu.slots.getOrNull(click.slot)?.item ?: ItemStack.EMPTY).takeIf { it >= 0 }
                ?: 0
            val nextColor = TerminalSolver.rubixAdvance(here, click.button)
            rubixPredicted[click.slot] = nextColor
            val nextItem = when (nextColor) {
                0 -> net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE
                1 -> net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE
                2 -> net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE
                3 -> net.minecraft.world.item.Items.LIGHT_BLUE_STAINED_GLASS_PANE
                4 -> net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE
                else -> null
            }
            if (nextItem != null) {
                screen.menu.slots.getOrNull(click.slot)?.set(ItemStack(nextItem))
            }
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
        clicksMade++
        clickedSlotsWithTime[slot] = now
        sessionClickedSlots.add(slot)
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
    @JvmStatic
    fun unsettledSlots(now: Long = System.currentTimeMillis()): Set<Int> =
        clickedSlotsWithTime.filterValues { now - it < CLICK_TIMEOUT_MS }.keys.toSet()

    @JvmStatic
    fun lastClickedSlot(): Int? = lastSlot.takeIf { it >= 0 }

    /** The pane that was last clicked and the moment it happened, for the
     *  grid's flash. Null before the first click of a terminal. */
    internal fun lastClickFlash(): Pair<Int, Long>? =
        lastSlot.takeIf { it >= 0 }?.let { it to lastClickAt }

    /** What the progress HUD prints: the terminal by name and how far through
     *  it the clicker is. Null when no terminal is open or the count has not
     *  been latched yet — a HUD that flickers into existence on the first
     *  tick is worse than one that waits for a number to exist. */
    internal fun progress(): Progress? {
        val title = lastTerminalTitle ?: return null
        val kind = TerminalSolver.kindOf(title) ?: return null
        val goal = clickGoal.takeIf { it > 0 } ?: return null
        return Progress(TerminalSolver.displayName(kind), clicksMade.coerceIn(0, goal), goal)
    }

    internal data class Progress(val name: String, val done: Int, val goal: Int)

    @JvmStatic
    fun rubixTargetOrNull(): Int? = lastRubixTarget

    fun rubixPredicted(slot: Int): Int? = rubixPredicted[slot]

    fun setRubixPredicted(slot: Int, colorIndex: Int) {
        rubixPredicted[slot] = colorIndex
    }

    fun clicksCount(): Int = clicksMade

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
        // one means clicking a pane the solver has already dealt with. How
        // long "not come back yet" lasts is per terminal — see clickGuardMs.
        val guard = clickGuardMs(kind, currentClickDelayMs)
        val blocked = buildSet {
            if (kind != Kind.RUBIX) {
                addAll(sessionClickedSlots)
            }
            for (i in items.indices) {
                if (isRecentlyClicked(i, now, guard)) add(i)
            }
        }

        if (kind == Kind.RUBIX) return rubixClick(kind, title, items, blocked, now)

        val candidates = TerminalSolver.clickCandidates(title, items, blocked)
        val slot = choose(candidates, kind) ?: return null
        return Click(slot)
    }

    /**
     * The ready candidate the clicker takes, ordered by where the pointer
     * already is rather than by slot number — unless Click Order says
     * otherwise, in which case all four modes run off this same list.
     * Ignoring that pane itself while anything else is available stays true
     * under every mode: otherwise "nearest" is always "the one I just
     * clicked" and the clicker would sit on it forever.
     */
    private fun choose(candidates: List<Int>, kind: Kind): Int? {
        if (candidates.isEmpty()) return null
        val elsewhere = candidates.filter { it != lastSlot }
        val pool = if (elsewhere.isNotEmpty()) elsewhere else candidates
        val order = if (Config.autoTerminalMode == TerminalMode.LEGIT) TerminalClickOrder.ORDER_HUMAN else Config.autoTerminalClickOrder
        return TerminalClickOrder.pick(
            order,
            pool,
            lastSlot.takeIf { it >= 0 },
            kind.slotCount
        )
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
        slot / 9 != lastMelodyRow || now - lastMelodyRowClickAt >= MELODY_RETRY_TOTAL_MS

    /**
     * Where the pointer sits while Melody runs — and, crucially, the same
     * thing the click is aimed at, so the two can never disagree.
     *
     * Melody's pace is row position, so the aim has exactly one move: after
     * the click it drops to the row below and stays there while the row above
     * is acknowledged. It comes back only when the retry window closes with
     * the server still showing the old row — at which point the click really
     * is due on that row again and the pointer belongs on it. Reading
     * [TerminalSolver.melodyCandidate] (what would be clicked) rather than
     * the moving pointer's row (what the server is drawing) is what stops the
     * hand shuttling back and forth between two rows at once.
     */
    internal fun melodyAimSlot(items: List<ItemStack>, now: Long): Int? {
        val pending = TerminalSolver.melodyCandidate(items)
            ?: TerminalSolver.melodyActiveButton(items)
            ?: TerminalSolver.melodyRows(items).firstOrNull { !it.completed }?.buttonSlot
        val sinceClick = now - lastMelodyRowClickAt
        if (lastMelodyRow >= 0 && sinceClick in 0 until MELODY_RETRY_TOTAL_MS) {
            // Down once, onto the row below — or stay put when there is none.
            return TerminalSolver.melodyNextButton(items, lastMelodyRow * 9 + 7) ?: pending
        }
        return pending
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

    /**
     * One sample from a gaussian in standard deviations, unclamped.
     *
     * [delayFor] owns where the sample lands; this only produces it, so the
     * distribution's shape is pinned here and its *policy* is pinned there.
     */
    private fun gaussianSample(): Double {
        val u1 = 1.0 - Random.nextDouble()
        val u2 = 1.0 - Random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * The next beat of the click clock.
     *
     * [meanMs] is the Click Delay — the number the player actually drags — and
     * it is what the sample centres on. [fasterMs]/[slowerMs] are how far
     * either side of it the jitter may reach; they come from the Min/Max
     * window, which used to *be* an absolute range that swallowed the mean
     * whole. Set Click Delay outside that range and nothing the player typed
     * made any difference, which is the whole reason this is a mean with a
     * spread rather than a range with a midpoint.
     *
     * Sigma spans six deviations across the combined spread, which is what
     * `gaussianRandom` used: a symmetric window around the mean reproduces the
     * old numbers exactly, so presets keep the cadence they shipped with.
     *
     * [sample] is passed in so the harness can pin the policy without
     * waiting on a distribution.
     */
    internal fun delayFor(meanMs: Long, fasterMs: Long, slowerMs: Long, sample: Double): Long {
        val mean = meanMs.coerceAtLeast(0L)
        val faster = fasterMs.coerceAtLeast(0L)
        val slower = slowerMs.coerceAtLeast(0L)
        if (faster == 0L && slower == 0L) return mean
        val sigma = (faster + slower) / 6.0
        return (mean + sample * sigma)
            .coerceIn((mean - faster).toDouble(), (mean + slower).toDouble())
            .toLong()
    }

    /**
     * How far either side of the Click Delay the jitter may reach, read from
     * the stored Min/Max window as *distances* rather than as bounds.
     *
     * A bound on the wrong side of the mean (Max set below the Click Delay, or
     * Min above it) describes a window the player cannot have wanted, so it
     * falls back to a plain default rather than inverting the spread. A bound
     * *on* the mean is the opposite case and reads as no spread at all: the
     * one Delay Spread slider writes exactly that when asked for zero.
     *
     * The window is passed in rather than read here so the whole policy is one
     * function the harness can pin.
     */
    internal fun delaySpread(mean: Int, minDelay: Int, maxDelay: Int): Pair<Long, Long> {
        val faster = if (mean > 0 && minDelay in 1..mean) (mean - minDelay).toLong() else DEFAULT_SPREAD_MS
        val slower = if (maxDelay >= mean) (maxDelay - mean).toLong() else DEFAULT_SPREAD_MS
        return faster to slower
    }

    internal fun computeFirstClickDelay(
        mode: Int,
        firstClickDelayMs: Int,
        randomDelay: Boolean,
        humanize: Int,
        sample: Double = gaussianSample()
    ): Long {
        return when (mode) {
            TerminalMode.LEGIT -> {
                // Strictly mimics recorded user data (median 506ms, mean 518ms, 25% 445ms, 75% 560ms)
                val base = 508L
                (base + sample * 35.0).toLong().coerceIn(430L, 620L)
            }
            TerminalMode.HUMAN -> {
                // Scaled from empirical baseline (508ms) using slider ratio against default (450ms)
                val ratio = firstClickDelayMs.coerceAtLeast(0) / 450.0f
                val base = (508f * ratio).toLong().coerceAtLeast(10L)
                if (!randomDelay) return base
                val spread = (35L * (humanize.coerceIn(0, 100) / 100f)).toLong().coerceAtLeast(4L)
                delayFor(base, spread, spread, sample)
            }
            else -> { // NORMAL (RSM / Noamm standard)
                val base = firstClickDelayMs.coerceAtLeast(0).toLong()
                if (!randomDelay) return base
                delayFor(base, FIRST_CLICK_SPREAD_MS, FIRST_CLICK_SPREAD_MS, sample)
            }
        }
    }

    private fun nextFirstClickDelayMs(): Long = computeFirstClickDelay(
        mode = Config.autoTerminalMode,
        firstClickDelayMs = Config.autoTerminalFirstClickDelayMs,
        randomDelay = Config.autoTerminalRandomDelay,
        humanize = Config.autoTerminalHumanize
    )

    internal fun computeClickDelay(
        mode: Int,
        clickDelayMs: Int,
        spreadMs: Int,
        minDelayMs: Int,
        maxDelayMs: Int,
        randomDelay: Boolean,
        humanize: Int,
        fromSlot: Int? = null,
        toSlot: Int? = null,
        kind: Kind? = null,
        sample: Double = gaussianSample()
    ): Long {
        return when (mode) {
            TerminalMode.LEGIT -> {
                // Strictly mimics recorded user data across 385 clicks:
                // Overall median 186ms, mean 208ms (PANES 166ms, RUBIX 177ms, STARTS 184ms, SELECT 198ms, ORDER 217ms)
                val kindBias = when (kind) {
                    Kind.PANES -> -18L
                    Kind.RUBIX -> -8L
                    Kind.STARTS -> 0L
                    Kind.SELECT -> +12L
                    Kind.ORDER -> +28L
                    null, Kind.MELODY -> 0L
                }
                val base = delayFor(180L + kindBias, 18L, 30L, sample)
                if (fromSlot == null || toSlot == null || fromSlot == toSlot) return base
                val dx = kotlin.math.abs((fromSlot % 9) - (toSlot % 9))
                val dy = kotlin.math.abs((fromSlot / 9) - (toSlot / 9))
                val dist = dx + dy
                val bonus = if (dist > 1) (dist * 4.0f).toLong().coerceIn(4L, 35L) else 0L
                base + bonus
            }
            TerminalMode.HUMAN -> {
                // Scaled from empirical baseline (180ms) using slider ratio against default (180ms)
                val kindBias = when (kind) {
                    Kind.PANES -> -18L
                    Kind.RUBIX -> -8L
                    Kind.STARTS -> 0L
                    Kind.SELECT -> +12L
                    Kind.ORDER -> +28L
                    null, Kind.MELODY -> 0L
                }
                val ratio = clickDelayMs.coerceAtLeast(0) / 180.0f
                val mean = ((180L + kindBias) * ratio).toLong().coerceAtLeast(20L)
                val spreadSlider = spreadMs.coerceAtLeast(0).toLong()
                val human = humanize.coerceIn(0, 100) / 100f
                val effectiveSpread = if (randomDelay) {
                    ((18L + spreadSlider * 0.45f) * human).toLong().coerceAtLeast(4L)
                } else 0L
                val base = if (effectiveSpread > 0L) {
                    delayFor(mean, effectiveSpread, effectiveSpread, sample)
                } else mean

                if (human <= 0f || fromSlot == null || toSlot == null || fromSlot == toSlot) return base
                val dx = kotlin.math.abs((fromSlot % 9) - (toSlot % 9))
                val dy = kotlin.math.abs((fromSlot / 9) - (toSlot / 9))
                val dist = dx + dy
                val bonus = (dist * 4.0f * human).toLong().coerceIn(0L, 35L)
                base + bonus
            }
            else -> { // NORMAL (RSM / Noamm standard)
                val mean = clickDelayMs.coerceAtLeast(0).toLong()
                if (!randomDelay) return mean
                val (faster, slower) = delaySpread(mean.toInt(), minDelayMs, maxDelayMs)
                val base = delayFor(mean, faster, slower, sample)
                val human = humanize.coerceIn(0, 100) / 100f
                if (human <= 0f || fromSlot == null || toSlot == null || fromSlot == toSlot) return base

                val dx = kotlin.math.abs((fromSlot % 9) - (toSlot % 9))
                val dy = kotlin.math.abs((fromSlot / 9) - (toSlot / 9))
                val dist = dx + dy
                val bonus = (dist * 3.5f * human).toLong().coerceIn(0L, 30L)
                base + bonus
            }
        }
    }

    internal fun nextClickDelayMs(fromSlot: Int? = null, toSlot: Int? = null, kind: Kind? = null): Long = computeClickDelay(
        mode = Config.autoTerminalMode,
        clickDelayMs = Config.autoTerminalClickDelayMs,
        spreadMs = Config.autoTerminalDelaySpreadMs,
        minDelayMs = Config.autoTerminalMinRandomDelayMs,
        maxDelayMs = Config.autoTerminalMaxRandomDelayMs,
        randomDelay = Config.autoTerminalRandomDelay,
        humanize = Config.autoTerminalHumanize,
        fromSlot = fromSlot,
        toSlot = toSlot,
        kind = kind
    )

    fun onEscape() {
        suppressReopenUntil = System.currentTimeMillis() + 750L
        reset()
    }

    fun onMelodyLimeMoved(row: Int, col: Int) {
        lastMelodyClickedSlot = null
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
        lastContainerId = -1
        firstClickPending = true
        currentClickDelayMs = 0L
        melodySkipQueue.clear()
        clickedSlotsWithTime.clear()
        sessionClickedSlots.clear()
        lastRubixTarget = null
        rubixPredicted.clear()
        lastMelodyRow = -1
        lastMelodyRowClickAt = 0L
        lastMelodyClickedSlot = null
        lastMelodyClickTime = 0L
        clicksMade = 0
        clickGoal = -1
    }

    internal fun reset() {
        TerminalCursor.reset()
        TerminalSensing.reset()
        resetSolver()
    }
}
