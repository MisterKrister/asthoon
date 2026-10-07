package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.config.TerminalMode
import com.asthoonlite.config.spreadWindow
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.google.gson.Gson
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.abs

/** Runs inside the existing bootstrapped regression harness. */
internal fun terminalMotionAndGuiChecks() {
    fun close(actual: Float, expected: Float, tolerance: Float = 0.0001f) {
        check(abs(actual - expected) < tolerance) { "$actual != $expected" }
    }
    fun ease(t: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
        TerminalCursor.cubicBezierEase(t, x1, y1, x2, y2)

    // Independent reference values and degenerate derivatives exercise x inversion.
    close(ease(0.5f, 0.25f, 0.1f, 0.25f, 1f), 0.8024034f)
    close(ease(0.001f, 0f, 0f, 0f, 1f), 0.028f)
    close(ease(0.999f, 1f, 0f, 1f, 1f), 0.972f)
    close(ease(0.5f, 1f, 0f, 0f, 1f), 0.5f) // flat x derivative
    close(ease(-1f, 0.2f, 0f, 0f, 1f), 0f)
    close(ease(2f, 0.2f, 0f, 0f, 1f), 1f)
    close(TerminalCursor.progressAt(-50, 100), 0f)
    check(runCatching { ease(0.5f, -0.1f, 0f, 0f, 1f) }.isFailure)
    check(runCatching { ease(Float.NaN, 0f, 0f, 0f, 1f) }.isFailure)
    for (i in 0..100) {
        val t = i / 100f
        close(ease(t, 0f, 0f, 1f, 1f), t)
        close(ease(t, 0.7f, 0.7f, 0.2f, 0.2f), t)
    }
    // Reference evaluation is in parameter space: each pair tests the inverse.
    for ((x1, x2) in listOf(0f to 0f, 1f to 1f, 1f to 0f, 0.2f to 0f, 0.25f to 0.25f)) {
        for (i in 1 until 100) {
            val u = i / 100.0
            val v = 1.0 - u
            val x = (3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u).toFloat()
            val y = (3 * v * v * u * -0.2 + 3 * v * u * u * 1.2 + u * u * u).toFloat()
            close(ease(x, x1, -0.2f, x2, 1.2f), y, 0.001f)
        }
    }
    check(ease(0.7f, 0.2f, 1.5f, 0.8f, 1.5f) > 1f) { "CSS permits Y overshoot" }

    val motion = CursorMotion()
    motion.reset(CursorMotion.Point(10f, 20f))
    val end = CursorMotion.Point(250f, 100f)
    check(motion.glideTo(end, 1_000L, 200L, 0.1f, CursorMotion.Ease(), 0.35f, 1f, 2f))
    check(motion.position(1_000L) == CursorMotion.Point(10f, 20f))
    val before = motion.position(1_040L)
    val next = CursorMotion.Point(80f, 200f)
    check(motion.glideTo(next, 1_040L, 200L, -0.1f, CursorMotion.Ease(), 0.35f, 3f, 4f))
    check(motion.position(1_040L) == before) { "retargeting must not jump to the previous origin or endpoint" }
    check(!motion.glideTo(next, 1_080L, 200L, 0f, CursorMotion.Ease(), 0f, 0f, 0f))
    check(motion.position(1_240L) == next && !motion.moving(1_240L)) { "same-target observations must not restart travel" }

    AutoTerminal.beginTerminal("Click the button on time!", 1_000L)
    AutoTerminal.recordClick(1_000L, 16, 40L)
    check(!AutoTerminal.canClick(1_039L))
    check(motion.moving(1_040L) && AutoTerminal.canClick(1_040L)) { "a live flight cannot delay a due click" }
    AutoTerminal.recordClick(1_040L, 25, 40L)
    check(!AutoTerminal.canClick(1_040L) && AutoTerminal.canClick(1_080L)) { "a due click is consumed once" }

    // Two separate questions. The clock says the beat has come round; the gate
    // says whether the packet leaves while the hand is still crossing the grid.
    // Only an on-screen pointer can hold anything back, and only while it is
    // travelling — a landed pointer, or no pointer at all, sends at once.
    check(motion.moving(1_080L)) { "the flight is still running at 1_080" }
    check(AutoTerminal.pointerBlocksClick(glideOn = true, pointerMoving = motion.moving(1_080L))) {
        "a travelling pointer holds the packet"
    }
    check(!AutoTerminal.pointerBlocksClick(glideOn = false, pointerMoving = true)) {
        "nothing drawn on screen means there is nothing to wait for"
    }
    check(!AutoTerminal.pointerBlocksClick(glideOn = true, pointerMoving = false)) {
        "a pointer that has landed never holds a click back"
    }

    fun melody(count: Int, row: Int): MutableList<ItemStack> = MutableList(54) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }.also { all ->
        all[3] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
        for (r in 1..count) {
            for (col in 1..5) all[r * 9 + col] = ItemStack(Items.WHITE_STAINED_GLASS_PANE)
            all[r * 9 + 7] = ItemStack(Items.LIME_TERRACOTTA)
        }
        all[row * 9 + 3] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    }
    // Melody ships three content rows. The loop used to run 3..4 when the game
    // had a fourth; it is pinned to three now, and the four-row fixtures below
    // are the stale-data probes rather than the normal case.
    for (count in listOf(3)) for (r in 1..count) {
        val all = melody(count, r)
        val slot = r * 9 + 7
        check(TerminalSolver.melodyRows(all).map { it.buttonSlot } == (1..count).map { it * 9 + 7 })
        check(TerminalSolver.melodyCandidate(all) == slot)
        check(TerminalSolver.clickCandidates("Click the button on time!", all, setOf(slot)).isEmpty())
        check(TerminalSolver.melodyNextButton(all, slot) == if (r < count) slot + 9 else null)
        check(TermGui.canClick(Kind.MELODY, "Click the button on time!", all, slot))
        check(!TermGui.canClick(Kind.MELODY, "Click the button on time!", all, (count + 1) * 9 + 7))
    }
    val four = melody(4, 4)
    four[3] = ItemStack(Items.BLACK_STAINED_GLASS_PANE)
    four[48] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
    check(TerminalSolver.melodyCandidate(four) == null) {
        "melody ships three rows: a fourth row's note is the indicator, never a candidate"
    }
    check(TerminalSolver.melodyRows(four).map { it.buttonSlot } == listOf(16, 25, 34)) {
        "a row-4 button must not be read as a fourth content row"
    }
    val three = melody(3, 3)
    three[3] = ItemStack(Items.BLACK_STAINED_GLASS_PANE)
    three[48] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
    check(TerminalSolver.melodyCandidate(three) == null) { "row five is filler in three-row melody" }
    check(TerminalSolver.melodyRows(four.take(16)).isEmpty())
    check(TerminalSolver.melodyCandidate(emptyList()) == null)
    val done = melody(4, 4)
    for (col in 1..5) done[36 + col] = ItemStack(Items.GREEN_STAINED_GLASS_PANE)
    check(TerminalSolver.melodyCandidate(done) == null)
    check(TerminalSolver.melodyNextButton(done, 34) == null)
    val withInventory = melody(3, 1) + MutableList(36) { ItemStack(Items.LIME_TERRACOTTA) }
    check(TerminalSolver.melodyRows(withInventory).size == 3)

    val pending = melody(4, 1)
    run {
        AutoTerminal.recordMelodyClick(pending, 16, 2_000L, skip = true, dontSkipFirst = false)
        check(AutoTerminal.melodyAimSlot(pending, 2_001L) == 25) { "premove must precede the server update" }
        // The row gets its ticks to arrive before the aim or the click answers:
        // inside the window the pointer holds the row below, and only past it
        // does the still-unacknowledged note get its retry.
        check(AutoTerminal.melodyAimSlot(pending, 2_250L) == 25) { "the grace keeps the pointer down while the row catches up" }
        check(AutoTerminal.melodyAimSlot(pending, 2_399L) == 25) { "the grace is not a tick shorter than it is set to" }
        check(!AutoTerminal.melodyRowReady(16, 2_399L) && AutoTerminal.melodyRowReady(16, 2_400L))
        check(AutoTerminal.melodyAimSlot(pending, 2_400L) == 16) { "unacknowledged notes must remain retryable" }
        check(AutoTerminal.MELODY_RETRY_TOTAL_MS == AutoTerminal.MELODY_ROW_RETRY_MS + AutoTerminal.MELODY_UPDATE_GRACE_MS)
        check(AutoTerminal.MELODY_UPDATE_TICKS == 3L && AutoTerminal.MELODY_UPDATE_GRACE_MS == 150L)
        val queue = AutoTerminal::class.java.getDeclaredField("melodySkipQueue").apply { isAccessible = true }
        check((queue.get(AutoTerminal) as Collection<*>).toList() == listOf(25, 34)) {
            "skipping queues the two content rows below the one clicked, and no phantom third"
        }
        AutoTerminal.recordMelodyClick(pending, 16, 3_000L, skip = true, dontSkipFirst = true)
        check((queue.get(AutoTerminal) as Collection<*>).isEmpty())
        AutoTerminal.recordMelodyClick(pending, 34, 3_100L, skip = true, dontSkipFirst = true)
        check(AutoTerminal.melodyAimSlot(melody(4, 3), 3_101L) == 34) {
            "row three is the last content row: with no row below, the pointer stays put"
        }
        AutoTerminal.recordMelodyClick(pending, 43, 3_200L, skip = true, dontSkipFirst = true)
        check((queue.get(AutoTerminal) as Collection<*>).isEmpty())
        AutoTerminal.onEscape()
        check(AutoTerminal.melodyRowReady(43, 3_201L))
        check(AutoTerminal.melodyAimSlot(pending, 3_201L) == 16)
    }

    val expectedCounts = mapOf(Kind.ORDER to 10, Kind.RUBIX to 9, Kind.PANES to 15, Kind.STARTS to 21, Kind.SELECT to 28)
    for (kind in Kind.entries) for (scale in listOf(50, 100, 175, 200)) for (gap in listOf(0, 4, 12)) {
        val grid = TermGui.layout(kind, 640, 360, scale, gap, listOf(1, 2, 3, 4))
        expectedCounts[kind]?.let { check(grid.tiles.size == it) }
        close(grid.originX + grid.width * grid.scale / 2, 320f)
        close(grid.originY + grid.height * grid.scale / 2, 180f)
        for (tile in grid.tiles) {
            val center = grid.center(tile.slot)!!
            check(grid.hitTest(center.first.toDouble(), center.second.toDouble()) == tile.slot)
            check(tile.slot in 0 until kind.slotCount)
        }
        check(grid.hitTest((grid.originX - 1).toDouble(), grid.originY.toDouble()) == null)
        check(grid.center(kind.slotCount) == null)
        if (gap > 0) {
            val tile = grid.tiles.first()
            check(grid.hitTest((grid.originX + (tile.x + 24 + gap / 2f) * grid.scale).toDouble(),
                (grid.originY + (tile.y + 12) * grid.scale).toDouble()) == null)
        }
    }
    // A screen too small for the grid still lands it inside the screen, and
    // the last slot it can draw is the indicator strip — the row under the
    // three content rows, which has no button column of its own.
    val small = TermGui.layout(Kind.MELODY, 160, 120, 200, 12, listOf(1, 2, 3, 4))
    check(small.originX >= 0 && small.originY >= 0 && small.center(41) != null) {
        "the shrunk melody grid still reaches its last indicator pane"
    }
    check(small.center(43) == null) { "the indicator row has no button column to draw" }
    check(TermGui.layout(Kind.MELODY, 640, 360, 100, 4).center(43) == null)

    // ── The drawn grid must cover whatever screen it is on ─────────────────
    run {
        // The requirement is the slots the tiles land on, not the terminal's
        // own row count. The real terminal ships a full chest under its rows
        // and a simulator's window ships the panes and nothing else, so the
        // two numbers part company there — and demanding the larger one turns
        // the grid off on a screen that can draw every tile it wants to draw.
        // The numbers below are counted by hand from each layout's shape,
        // which is the point: the assertion has to survive a change to the
        // tile code it is checking.
        check(TermGui.requiredSlots(Kind.PANES) == 34)   // rows 1..3, cols 2..6
        check(TermGui.requiredSlots(Kind.ORDER) == 25)   // rows 1..2, cols 2..6
        check(TermGui.requiredSlots(Kind.RUBIX) == 33)   // rows 1..3, cols 3..5
        check(TermGui.requiredSlots(Kind.SELECT) == 44)  // rows 1..4, cols 1..7
        check(TermGui.requiredSlots(Kind.STARTS) == 35)  // rows 1..3, cols 1..7
        check(TermGui.requiredSlots(Kind.MELODY) == 42)  // three rows, col 6 and the tail's col 7 skipped
        check(TermGui.requiredSlots(Kind.MELODY, listOf(1, 2, 3, 4)) == 42) {
            "a stale fourth content row must not inflate the grid — melody ships three"
        }
        for (kind in Kind.entries) {
            check(TermGui.requiredSlots(kind) <= kind.slotCount) {
                "$kind must never ask for more than the terminal itself ships"
            }
        }

        check(TermGui.covers(Kind.PANES, 45)) { "the terminal's own rows are enough" }
        check(TermGui.covers(Kind.PANES, 45 + 36)) { "a real chest still counts" }
        check(TermGui.covers(Kind.PANES, 34)) { "a window holding every pane is this terminal" }
        check(!TermGui.covers(Kind.PANES, 33)) { "a window missing the last pane is not" }
        check(TermGui.covers(Kind.ORDER, 25) && !TermGui.covers(Kind.ORDER, 24))
        check(TermGui.covers(Kind.RUBIX, 33) && !TermGui.covers(Kind.RUBIX, 32))
        check(TermGui.covers(Kind.SELECT, 44) && !TermGui.covers(Kind.SELECT, 43))
        check(TermGui.covers(Kind.STARTS, 35) && !TermGui.covers(Kind.STARTS, 34))
        check(TermGui.covers(Kind.MELODY, 54) && !TermGui.covers(Kind.MELODY, 41))
        check(TermGui.covers(Kind.MELODY, 42, listOf(1, 2, 3, 4))) {
            "a stale fourth row must not raise what the menu has to reach"
        }
        check(!TermGui.covers(Kind.MELODY, 41, listOf(1, 2, 3, 4))) { "one slot short of the drawn rows is not covered" }
    }

    // ── The progress readout's denominator ───────────────────────────────
    run {
        // Numbers is a chain: only the lowest count is ever a candidate, so
        // the candidate list is one pane wide while the terminal wants every
        // numbered pane on the board. Counting candidates here would show a
        // readout pinned at 1/1 for the whole terminal.
        val order = MutableList(36) { ItemStack(Items.WHITE_STAINED_GLASS_PANE) }
        val reds = listOf(0, 4, 7, 13, 18, 20, 24, 28, 30, 33)
        check(reds.size == TerminalSolver.NUMBER_TERM_COUNT) { "the practice fixture has ten panes" }
        reds.forEachIndexed { i, slot -> order[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE, i + 1) }
        check(TerminalSolver.clickCandidates("Click in order!", order).size == 1) { "numbers is a chain" }
        check(TerminalSolver.goalFor("Click in order!", order) == reds.size) {
            "all ten numbered panes count, not the one the chain happens to offer"
        }
        check(TerminalSolver.goalFor("Chest", order) == 0) { "a non-terminal wants nothing" }

        // Live menus can use two seven-wide rows and put numbers in formatted
        // names while every stack has count one. The same values must drive
        // text, candidates and the grid the pointer uses.
        val liveSlots = (1..2).flatMap { row -> (1..7).map { col -> row * 9 + col } }
        val named = MutableList(36) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
        liveSlots.forEachIndexed { index, slot ->
            val number = 14 - index
            named[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE).apply {
                set(DataComponents.CUSTOM_NAME, Component.literal("§c$number"))
            }
            check(TerminalSolver.labelFor(slot, named[slot], Kind.ORDER) == number.toString())
        }
        check(TerminalSolver.clickCandidates("Click in order!", named) == listOf(liveSlots.last())) {
            "formatted numeric names must order count-one stacks"
        }
        check(TerminalSolver.goalFor("Click in order!", named) == 10) {
            "real numbers terminals display 10, not 14"
        }
        check(TerminalSolver.numberSlots(named) == liveSlots)
        check(TermGui.requiredSlotsFor(Kind.ORDER, named) == 26) { "the live rightmost number is slot 25" }
        val liveGrid = TermGui.layout(Kind.ORDER, 640, 360, 150, 4, orderSlots = TerminalSolver.numberSlots(named))
        check(liveGrid.tiles.map { it.slot } == liveSlots)
        check(liveGrid.width == 7 * 24 + 6 * 4 && liveGrid.height == 2 * 24 + 4)
        for (slot in liveSlots) {
            val center = liveGrid.center(slot)!!
            check(liveGrid.hitTest(center.first.toDouble(), center.second.toDouble()) == slot)
        }
        val completedSlot = liveSlots.last()
        named[completedSlot] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
        check(TerminalSolver.numberSlots(named) == liveSlots) { "completed panes must preserve the grid geometry" }
        check(TerminalSolver.labelFor(completedSlot, named[completedSlot], Kind.ORDER) == null)
        check(TerminalSolver.clickCandidates("Click in order!", named) == listOf(liveSlots[liveSlots.lastIndex - 1]))
        check(TerminalSolver.goalFor("Click in order!", named) == 10) { "remaining count capped at 10" }

        // When fewer than 10 are left, exact count is returned
        for (i in 0 until 6) {
            named[liveSlots[i]] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
        }
        check(TerminalSolver.goalFor("Click in order!", named) == 7) { "below 10 remaining returns exact count" }

        val counted = MutableList(36) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
        liveSlots.forEachIndexed { index, slot -> counted[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE, index + 1) }
        check(TerminalSolver.clickCandidates("Click in order!", counted) == listOf(liveSlots.first()))
        check(TerminalSolver.labelFor(liveSlots.last(), counted[liveSlots.last()], Kind.ORDER) == "14")
        check(TerminalSolver.numberSlots(counted + List(36) { ItemStack(Items.RED_STAINED_GLASS_PANE, 64) }) == liveSlots) {
            "inventory panes must not grow the number grid"
        }
        val practiceSlots = (1..2).flatMap { row -> (2..6).map { col -> row * 9 + col } }
        val practice = MutableList(36) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
        practiceSlots.forEachIndexed { index, slot -> practice[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE, index + 1) }
        val practiceGrid = TermGui.layout(Kind.ORDER, 640, 360, 100, 4, orderSlots = TerminalSolver.numberSlots(practice))
        check(practiceGrid.tiles.map { it.slot } == practiceSlots && practiceGrid.tiles.size == 10)
        check(TermGui.requiredSlotsFor(Kind.ORDER, practice) == 25)
        check(TerminalSolver.goalFor("Click in order!", practice) == 10)

        // Rubix counts clicks, not panes: a pane two rings from the target is
        // two clicks of work, which no candidate list says.
        val rubix = MutableList(45) { ItemStack(Items.WHITE_STAINED_GLASS_PANE) }
        val rubixSlots = TerminalSolver.RUBIX_SLOTS
        // Eight panes already at the cheapest colour and one sitting two rings
        // from it: one candidate, two clicks of work.
        rubixSlots.forEachIndexed { i, slot ->
            rubix[slot] = ItemStack(if (i == rubixSlots.lastIndex) Items.GREEN_STAINED_GLASS_PANE else Items.ORANGE_STAINED_GLASS_PANE)
        }
        val rubixTarget = TerminalSolver.optimalRubixTarget(rubix)!!
        val clicksNeeded = rubixSlots.sumOf {
            TerminalSolver.rubixDistance(TerminalHelper.rubixColorIndex(rubix[it]), rubixTarget)
        }
        check(clicksNeeded > 0) { "the rubix fixture must actually want clicks" }
        check(clicksNeeded == 2) { "one pane two rings from the target is two clicks" }
        check(TerminalSolver.goalFor("Change all to same color!", rubix, rubixTarget) == clicksNeeded)
        check(TerminalSolver.clickCandidates("Change all to same color!", rubix, rubixTarget = rubixTarget).size == 1) {
            "the candidate list counts panes, the goal counts clicks"
        }
        check(TermGui.rubixLabel(0, 4, simulation = true) == "1") {
            "one backward practice click must display one, rather than four forward clicks"
        }
        check(TermGui.rubixLabel(0, 4, simulation = false) == "1") {
            "live labels must display one backward click, rather than four forward clicks"
        }
        for (current in 0..4) for (wanted in 0..4) {
            val shortest = minOf((wanted - current + 5) % 5, (current - wanted + 5) % 5)
            check(TermGui.rubixLabel(current, wanted, simulation = true) == shortest.takeIf { it > 0 }?.toString())
            check(TermGui.rubixLabel(current, wanted, simulation = false) == shortest.takeIf { it > 0 }?.toString())
        }

        // The two kinds whose candidate list is already the answer.
        val panes = MutableList(45) { ItemStack(Items.WHITE_STAINED_GLASS_PANE) }
        listOf(10, 11, 19).forEach { panes[it] = ItemStack(Items.RED_STAINED_GLASS_PANE) }
        check(TerminalSolver.goalFor("Correct all the panes!", panes) == 3)

        val select = MutableList(54) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
        listOf(1, 20, 44).forEach { select[it] = ItemStack(Items.RED_STAINED_GLASS_PANE) }
        select[5] = ItemStack(Items.WHITE_STAINED_GLASS_PANE) // wrong colour, never a target
        check(TerminalSolver.goalFor("Select all the red items!", select) == 3)

        check(TerminalSolver.goalFor("Click the button on time!", melody(3, 1)) == 3) {
            "melody's goal is its three content rows"
        }

        check(TerminalSolver.displayName(Kind.SELECT) == "Colors")
        check(TerminalSolver.displayName(Kind.ORDER) == "Numbers")
        check(TerminalSolver.displayName(Kind.PANES) == "Red Green")
        check(TerminalSolver.displayName(Kind.MELODY) == "Melody")
    }

    // ── Click pacing: the settings have to be the settings ────────────────
    run {
        // Click Delay is the mean of the next beat, not a hint printed beside
        // a range that swallows it whole.
        check(AutoTerminal.delayFor(135L, 15L, 15L, 0.0) == 135L) { "the mean must be the Click Delay" }
        check(AutoTerminal.delayFor(135L, 15L, 15L, 3.0) == 150L) { "three sigma is the slow bound" }
        check(AutoTerminal.delayFor(135L, 15L, 15L, -3.0) == 120L) { "three sigma is also the fast bound" }
        check(AutoTerminal.delayFor(135L, 15L, 15L, 999.0) == 150L) { "an outlier clamps instead of escaping" }
        // An asymmetric window is still centred on the setting — the shape the
        // old range-based policy could not produce at all.
        check(AutoTerminal.delayFor(135L, 15L, 65L, 0.0) == 135L) { "an off-centre window still centres on the mean" }
        check(AutoTerminal.delayFor(135L, 15L, 65L, 6.0) == 200L) { "the slow side reaches its own bound" }
        check(AutoTerminal.delayFor(180L, 0L, 0L, 42.0) == 180L) { "zero spread must be deterministic" }

        // The window is read as distances from the mean. A window that did not
        // contain the mean used to be a range that ignored it entirely.
        check(AutoTerminal.delaySpread(135, 120, 150) == (15L to 15L)) { "a window around the mean is its own spread" }
        check(AutoTerminal.delaySpread(180, 160, 200) == (20L to 20L)) { "the default window spread" }
        check(AutoTerminal.delaySpread(250, 120, 150) == (130L to 20L)) {
            "a Click Delay above the window still moves every click"
        }
        check(AutoTerminal.delaySpread(100, 120, 150) == (20L to 50L)) {
            "a Click Delay below the window still moves every click"
        }
        // The one Delay Spread slider writes a pair centred on the Click
        // Delay, and zero means zero rather than a default spread nobody set.
        check(AutoTerminal.delaySpread(135, 135, 135) == (0L to 0L)) {
            "a spread of zero must be deterministic"
        }
        // The shipped window sits around the shipped Click Delay, weighted
        // slower than faster. Read off a fresh Data rather than off Config:
        // initialising Config asks FabricLoader for a config directory this
        // harness does not have.
        val shipped = Config.Data()
        check(shipped.autoTerminalRandomDelay) {
            "random delay ships on — a fixed interval is a metronome, not a hand"
        }
        check(AutoTerminal.delaySpread(
            shipped.autoTerminalClickDelayMs,
            shipped.autoTerminalMinRandomDelayMs,
            shipped.autoTerminalMaxRandomDelayMs
        ) == (30L to 60L)) {
            "the shipped window is 150..240 around a Click Delay of 180"
        }
        // One control writes the pair the two sliders used to own, centred on
        // the Click Delay — through the pure helper, so this never saves over
        // a real settings file.
        check(spreadWindow(135, 15) == (120 to 150)) { "the spread centres the window on the Click Delay" }
        check(spreadWindow(180, 0) == (180 to 180)) { "zero spread is a point on the mean" }
        check(spreadWindow(10, 500) == (0 to 510)) { "the window cannot go negative" }
        check(spreadWindow(-5, 15) == (0 to 15)) { "nor can the mean" }

        // The per-slot guard. Numbers is the one terminal whose round trip is
        // its cadence, so its guard follows the Click Delay instead of the
        // flat safety window the others can afford.
        check(AutoTerminal.clickGuardMs(Kind.SELECT, 135L) == 350L) { "the panes keep the round-trip ceiling" }
        check(AutoTerminal.clickGuardMs(Kind.STARTS, 135L) == 350L)
        check(AutoTerminal.clickGuardMs(Kind.RUBIX, 135L) == 350L)
        check(AutoTerminal.clickGuardMs(Kind.ORDER, 135L) == 135L) {
            "the number terminal must not be capped slower than its own beat"
        }
        check(AutoTerminal.clickGuardMs(Kind.ORDER, 40L) == 40L) {
            "and a fast beat stays fast"
        }
    }

    // ── Humanize: one slider over every trait ──────────────────────────────
    run {
        // Timing. At zero the jitter input is gone, so two identical hops are
        // identical — the mechanical end of the slider in one line.
        check(TerminalCursor.timingJitter(1f, 0f) == 0f) { "humanize 0 must not spread timing" }
        check(TerminalCursor.timingJitter(-1f, 0f) == 0f) { "humanize 0 must not spread timing either way" }
        check(TerminalCursor.timingJitter(1f, 1f) == 1f) { "humanize 100 must not attenuate timing" }
        check(TerminalCursor.timingJitter(-1f, 0.5f) == -0.5f) { "half human is half the spread" }
        check(
            TerminalCursor.travelDurationMs(120f, 100, TerminalCursor.timingJitter(1f, 0f)) ==
                TerminalCursor.travelDurationMs(120f, 100, TerminalCursor.timingJitter(-1f, 0f))
        ) { "at humanize 0 a trip is a pure function of distance and speed" }

        // The pause before moving, and the correction after carrying past.
        // Both are zero for a machine and bounded for a hand.
        check(TerminalCursor.dwellMs(0f) == 0L) { "a machine does not hesitate" }
        check(TerminalCursor.dwellMs(-1f) == 0L) { "an out-of-range setting must not hesitate" }
        val fullDwell = TerminalCursor.dwellMs(1f)
        check(fullDwell in 1L..55L) { "full humanize hesitates, but briefly: $fullDwell" }
        check(TerminalCursor.dwellMs(0.5f) < fullDwell) { "half human is a shorter pause" }

        check(TerminalCursor.settleMs(200L, 0f) == 0L) { "a machine lands and stops" }
        check(TerminalCursor.settleMs(0L, 1f) == 0L) { "nothing to travel is nothing to correct" }
        check(TerminalCursor.settleMs(200L, 1f) in 30L..120L) { "correction time out of range" }

        check(TerminalCursor.overshootPx(20f, 1f) == 0f) { "a neighbouring pane has nothing to carry past" }
        check(TerminalCursor.overshootPx(400f, 0f) == 0f) { "a machine lands on the pixel" }
        val flick = TerminalCursor.overshootPx(400f, 1f)
        check(flick > 0f && flick <= 8f) { "overshoot out of range: $flick" }
        check(TerminalCursor.overshootPx(400f, 0.5f) <= flick) { "less human, less overshoot" }
        check(TerminalCursor.overshootPx(1000f, 1f) <= 8f) { "never a pane's worth of overshoot" }
    }

    // ── The trip itself: hesitate, carry past, correct back ────────────────
    run {
        val hand = CursorMotion()
        hand.reset(CursorMotion.Point(0f, 0f))
        hand.glideTo(
            CursorMotion.Point(300f, 0f), 1_000L, 200L, 0f, CursorMotion.Ease(), 0f, 0f, 0f,
            overshootPx = 8f, settleMs = 60L, dwellMs = 50L
        )
        // The pause is part of the flight: nothing moves, and the trip is
        // still one trip — the clock and the pointer are independent, so a
        // click leaves on time regardless of where the hand is.
        check(hand.moving(1_000L)) { "a hesitation is still a trip" }
        check(hand.position(1_049L) == CursorMotion.Point(0f, 0f)) { "the pointer must wait out its pause" }
        check(hand.position(1_250L).x > 300f) { "an overshoot must carry past the pane" }
        check(hand.position(1_310L) == CursorMotion.Point(300f, 0f)) { "and settle back onto it" }
        check(!hand.moving(1_310L)) { "the settle ends the trip" }
    }

    // ── One beat, one trip: the pointer never sets the pace ───────────────
    run {
        // With time to spare the trip keeps its hesitation and flies at the
        // speed Pointer Speed asked for.
        check(TerminalCursor.fitTrip(300L, 400L, 50L) == (50L to 300L)) { "a roomy beat keeps the pause" }
        // Squeezed, it spends what is left on travel rather than on waiting.
        check(TerminalCursor.fitTrip(300L, 150L, 50L) == (50L to 100L)) { "the pause shrinks before the flight does" }
        check(TerminalCursor.fitTrip(300L, 100L, 50L) == (0L to 100L)) {
            "and is dropped entirely when a real flight would not fit beside it"
        }
        // The floor is the only way past the window, and only under a beat
        // faster than motion can honestly be drawn.
        check(TerminalCursor.fitTrip(120L, 60L, 0L) == (0L to 70L)) { "a trip still has to be visible" }
        check(TerminalCursor.fitTrip(300L, -40L, 50L) == (0L to 70L)) { "an overdue click still gets a flight" }
        // Whatever the split, travel never exceeds what distance asked for.
        for (window in listOf(-10L, 0L, 40L, 80L, 130L, 250L, 900L)) {
            val (dwell, travel) = TerminalCursor.fitTrip(300L, window, 50L)
            check(travel in 70L..300L) { "travel out of range at window $window: $travel" }
            check(dwell in 0L..50L) { "dwell out of range at window $window: $dwell" }
            check(dwell + travel <= window || travel == 70L) { "the beat must hold the trip at window $window" }
        }
    }

    val oldConfig = Gson().fromJson("{\"autoTerminalClickDelayMs\":95,\"autoTerminalCursorArc\":60}", Config.Data::class.java)
    check(oldConfig.autoTerminalClickDelayMs == 95 && oldConfig.autoTerminalCursorArc == 60)
    // Fields the old file never had keep the shipped default, and the shipped
    // default is the look the clicker and the pointer are built on: the grid
    // and the pointer on, Click Order on Human.
    check(oldConfig.termGuiEnabled && oldConfig.termGuiSize == 2.0f) {
        "a config written before the grid existed must still get the grid"
    }
    check(oldConfig.autoTerminalCursorMelody && oldConfig.autoTerminalClickOrder == TerminalClickOrder.ORDER_HUMAN) {
        "the pointer ships on for melody and Click Order ships on Human"
    }
    check(oldConfig.autoTerminalEaseX1 == 20 && oldConfig.autoTerminalEaseY2 == 100)

    // ── Cursor pointer sprite geometry ───────────────────────────────────
    val pointerRuns = TerminalCursor.arrowRuns()
    check(pointerRuns.size == 16) { "pointer sprite must have 16 rows, got ${pointerRuns.size}" }
    check(pointerRuns[0].x0 == 0 && pointerRuns[0].x1 == 0) { "tip of pointer must be at (0,0)" }
    check(pointerRuns[15].x0 == 7 && pointerRuns[15].x1 == 10) { "tail of pointer must end at col 7..10" }

    // ── Terminal Modes (Normal, Human, Legit) ────────────────────────────
    check(TerminalMode.MODE_COUNT == 3)
    check(TerminalMode.modeName(TerminalMode.NORMAL) == "Normal")
    check(TerminalMode.modeName(TerminalMode.HUMAN) == "Human")
    check(TerminalMode.modeName(TerminalMode.LEGIT) == "Legit")
    val modeData = Gson().fromJson("{\"autoTerminalMode\":2}", Config.Data::class.java)
    check(modeData.autoTerminalMode == 2)

    // ── F7Devices Simon Says Math ────────────────────────────────────────
    check(abs(F7Devices.shortestAngleDist(0f, 90f) - 90f) < 0.001f)
    check(abs(F7Devices.shortestAngleDist(350f, 10f) - 20f) < 0.001f)
    check(abs(F7Devices.shortestAngleDist(10f, 350f) - (-20f)) < 0.001f)
    check(abs(F7Devices.shortestAngleDist(-90f, -132f) - (-42f)) < 0.001f)

    check(abs(F7Devices.easeInOutCubic(0f) - 0f) < 0.001f)
    check(abs(F7Devices.easeInOutCubic(1f) - 1f) < 0.001f)
    check(abs(F7Devices.easeInOutCubic(0.5f) - 0.5f) < 0.001f)
    check(F7Devices.easeInOutCubic(0.2f) < 0.2f) { "ease-in must start slow" }
    check(F7Devices.easeInOutCubic(0.8f) > 0.8f) { "ease-out must end slow" }

    // ── Legit & Human Delay empirical checks ──
    val legitPanes = (1..50).map {
        AutoTerminal.computeClickDelay(TerminalMode.LEGIT, 180, 20, 160, 200, true, 50, 10, 11, Kind.PANES)
    }
    check(legitPanes.all { it in 130L..250L }) { "Legit PANES delay must stay in empirical range: $legitPanes" }
    val legitOrder = (1..50).map {
        AutoTerminal.computeClickDelay(TerminalMode.LEGIT, 180, 20, 160, 200, true, 50, 11, 24, Kind.ORDER)
    }
    check(legitOrder.all { it in 160L..310L }) { "Legit ORDER delay must stay in empirical range: $legitOrder" }

    val legitFirst = (1..50).map {
        AutoTerminal.computeFirstClickDelay(TerminalMode.LEGIT, 450, true, 50)
    }
    check(legitFirst.all { it in 430L..620L }) { "Legit first click delay must stay in empirical range: $legitFirst" }

    val humanDelays = (1..50).map {
        AutoTerminal.computeClickDelay(TerminalMode.HUMAN, 180, 20, 160, 200, true, 50, 10, 19, Kind.RUBIX)
    }
    check(humanDelays.all { it in 120L..280L }) { "Human delays must stay in sensible human range: $humanDelays" }
}

