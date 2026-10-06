package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.config.spreadWindow
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.google.gson.Gson
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

    fun melody(count: Int, row: Int): MutableList<ItemStack> = MutableList(54) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }.also { all ->
        all[3] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
        for (r in 1..count) {
            for (col in 1..5) all[r * 9 + col] = ItemStack(Items.WHITE_STAINED_GLASS_PANE)
            all[r * 9 + 7] = ItemStack(Items.LIME_TERRACOTTA)
        }
        all[row * 9 + 3] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    }
    for (count in 3..4) for (r in 1..count) {
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
    check(TerminalSolver.melodyCandidate(four) == 43) { "four rows may put the lower marker in row five" }
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
        check(!AutoTerminal.melodyRowReady(16, 2_249L) && AutoTerminal.melodyRowReady(16, 2_250L))
        check(AutoTerminal.melodyAimSlot(pending, 2_250L) == 16) { "unacknowledged notes must remain retryable" }
        val queue = AutoTerminal::class.java.getDeclaredField("melodySkipQueue").apply { isAccessible = true }
        check((queue.get(AutoTerminal) as Collection<*>).toList() == listOf(25, 34, 43))
        AutoTerminal.recordMelodyClick(pending, 16, 3_000L, skip = true, dontSkipFirst = true)
        check((queue.get(AutoTerminal) as Collection<*>).isEmpty())
        AutoTerminal.recordMelodyClick(pending, 34, 3_100L, skip = true, dontSkipFirst = true)
        check(AutoTerminal.melodyAimSlot(melody(4, 3), 3_101L) == 43)
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
    val small = TermGui.layout(Kind.MELODY, 160, 120, 200, 12, listOf(1, 2, 3, 4))
    check(small.originX >= 0 && small.originY >= 0 && small.center(43) != null)
    check(TermGui.layout(Kind.MELODY, 640, 360, 100, 4).center(43) == null)

    // ── The drawn grid must cover whatever screen it is on ─────────────────
    run {
        // The terminal's own rows are the whole requirement: everything the
        // grid draws comes from `slots.take(slotCount)`, and it never needed
        // the player's inventory below them. Asking for that as well is what
        // left a screen holding the terminal and nothing else blank — its own
        // windows, in the p3 simulator — while the clicker, which never asked
        // for it, kept working on the very same screen.
        check(TermGui.covers(Kind.PANES, 45)) { "the terminal's own rows are enough" }
        check(TermGui.covers(Kind.PANES, 45 + 36)) { "a real chest still counts" }
        check(!TermGui.covers(Kind.PANES, 44)) { "a screen one row short is not this terminal" }
        check(TermGui.covers(Kind.SELECT, 54) && !TermGui.covers(Kind.SELECT, 53))
        check(TermGui.covers(Kind.MELODY, 54) && !TermGui.covers(Kind.MELODY, 53))
        check(TermGui.covers(Kind.ORDER, 36) && !TermGui.covers(Kind.ORDER, 35))
        check(TermGui.covers(Kind.STARTS, 45) && !TermGui.covers(Kind.STARTS, 44))
        check(TermGui.covers(Kind.RUBIX, 45) && !TermGui.covers(Kind.RUBIX, 44))
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
        // The shipped window is ±20 ms around the shipped Click Delay. Read
        // off a fresh Data rather than off Config: initialising Config asks
        // FabricLoader for a config directory this harness does not have.
        val shipped = Config.Data()
        check((shipped.autoTerminalMaxRandomDelayMs - shipped.autoTerminalMinRandomDelayMs) / 2 == 20) {
            "the default window is ±20 ms"
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
    check(!oldConfig.termGuiEnabled && oldConfig.termGuiSize == 2.0f)
    check(oldConfig.autoTerminalEaseX1 == 20 && oldConfig.autoTerminalEaseY2 == 100)
}
