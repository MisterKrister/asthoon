package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
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
    val oldConfig = Gson().fromJson("{\"autoTerminalClickDelayMs\":95,\"autoTerminalCursorArc\":60}", Config.Data::class.java)
    check(oldConfig.autoTerminalClickDelayMs == 95 && oldConfig.autoTerminalCursorArc == 60)
    check(!oldConfig.termGuiEnabled && oldConfig.termGuiSize == 2.0f)
    check(oldConfig.autoTerminalEaseX1 == 20 && oldConfig.autoTerminalEaseY2 == 100)
}
