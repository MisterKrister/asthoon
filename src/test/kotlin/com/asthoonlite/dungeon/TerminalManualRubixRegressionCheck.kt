package com.asthoonlite.dungeon

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun terminalManualRubixRegressionChecks() {
    AutoTerminal.reset()
    val ring = listOf(
        Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
        Items.LIME_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
        Items.RED_STAINED_GLASS_PANE
    )
    val title = "Change all to same color!"
    val kind = TerminalSolver.Kind.RUBIX
    val board = MutableList(kind.slotCount) { ItemStack.EMPTY }
    val colors = listOf(1, 2, 3, 3, 3, 4, 4, 4, 4) // Counts [0, 1, 1, 3, 4].
    TerminalSolver.RUBIX_SLOTS.zip(colors).forEach { (slot, color) -> board[slot] = ItemStack(ring[color]) }

    val cached = requireNotNull(TermGui.rubixTargetFor(board, committed = null, cached = null))
    check(cached == 4) { "The manual board initially chooses red" }
    val limeSlot = TerminalSolver.RUBIX_SLOTS[1]
    check(TermGui.canClick(kind, title, board, limeSlot))
    val first = TermGui.rubixClickPlan(board[limeSlot], predicted = null, target = cached, requestedButton = 0)
    check(first.button == 0 && first.nextColor == 3)
    board[limeSlot] = ItemStack(ring[requireNotNull(first.nextColor)])

    // A tie now selects blue when recomputed, although this pane still needs
    // its second click to the red target already shown by the manual grid.
    val recomputed = TerminalSolver.optimalRubixTarget(board)
    check(recomputed == 3) { "The first lime click must reproduce the changed optimal target" }
    val fixed = requireNotNull(TermGui.rubixTargetFor(board, committed = null, cached = cached))
    check(fixed == 4)
    check(limeSlot !in TerminalSolver.clickCandidates(title, board, rubixTarget = recomputed)) {
        "Recomputing the target would remove the newly blue pane from the solver candidates"
    }
    check(TermGui.canClick(kind, title, board, limeSlot)) {
        "Manual input must allow the second click toward the cached red target"
    }
    val second = TermGui.rubixClickPlan(board[limeSlot], predicted = null, target = fixed, requestedButton = 0)
    check(second.button == 0 && second.nextColor == 4)
    board[limeSlot] = ItemStack(ring[requireNotNull(second.nextColor)])
    check(TermGui.canClick(kind, title, board, limeSlot)) {
        "A target-colour pane remains interactive for an explicit manual input"
    }

    for (slot in TerminalSolver.RUBIX_SLOTS) {
        var current = TerminalHelper.rubixColorIndex(board[slot])
        repeat(TerminalSolver.rubixDistance(current, fixed)) {
            val target = requireNotNull(TermGui.rubixTargetFor(board, committed = null, cached = cached))
            check(target == fixed && TermGui.canClick(kind, title, board, slot))
            val plan = TermGui.rubixClickPlan(board[slot], predicted = null, target = target, requestedButton = 0)
            current = requireNotNull(plan.nextColor)
            board[slot] = ItemStack(ring[current])
        }
        check(current == fixed)
    }
    check(TerminalSolver.clickCandidates(title, board, rubixTarget = fixed).isEmpty())
    check(TerminalSolver.RUBIX_SLOTS.all { TermGui.canClick(kind, title, board, it) }) {
        "Solver completion must not disable the manual controls"
    }
    check(TermGui.rubixTargetFor(board, committed = 3, cached = 4) == 3) {
        "Automation's committed target takes precedence over the manual cache"
    }
    check(TermGui.rubixTargetFor(emptyList(), committed = null, cached = null) == null)
    AutoTerminal.reset()
}
