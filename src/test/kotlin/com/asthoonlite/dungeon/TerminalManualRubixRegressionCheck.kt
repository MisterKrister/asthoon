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
    check(TermGui.canClick(kind, title, board, limeSlot, rubixTarget = cached))
    val firstButton = TerminalSolver.rubixButton(2, cached)
    check(firstButton == 0)
    board[limeSlot] = ItemStack(ring[TerminalSolver.rubixAdvance(2, firstButton)])

    // A tie now selects blue when recomputed, although this pane still needs
    // its second click to the red target already shown by the manual grid.
    val recomputed = TerminalSolver.optimalRubixTarget(board)
    check(recomputed == 3) { "The first lime click must reproduce the changed optimal target" }
    val fixed = requireNotNull(TermGui.rubixTargetFor(board, committed = null, cached = cached))
    check(fixed == 4)
    check(!TermGui.canClick(kind, title, board, limeSlot, rubixTarget = recomputed)) {
        "The old live filter rejected the newly blue pane"
    }
    check(TermGui.canClick(kind, title, board, limeSlot, rubixTarget = fixed)) {
        "Manual live filtering must allow the second click toward the cached red target"
    }
    val secondButton = TerminalSolver.rubixButton(3, fixed)
    check(secondButton == 0)
    board[limeSlot] = ItemStack(ring[TerminalSolver.rubixAdvance(3, secondButton)])
    check(!TermGui.canClick(kind, title, board, limeSlot, rubixTarget = fixed)) {
        "A pane that reached the fixed target must stop accepting manual clicks"
    }

    for (slot in TerminalSolver.RUBIX_SLOTS) {
        var current = TerminalHelper.rubixColorIndex(board[slot])
        repeat(TerminalSolver.rubixDistance(current, fixed)) {
            val target = requireNotNull(TermGui.rubixTargetFor(board, committed = null, cached = cached))
            check(target == fixed && TermGui.canClick(kind, title, board, slot, rubixTarget = target))
            current = TerminalSolver.rubixAdvance(current, TerminalSolver.rubixButton(current, target))
            board[slot] = ItemStack(ring[current])
        }
        check(current == fixed)
    }
    check(TerminalSolver.RUBIX_SLOTS.none { TermGui.canClick(kind, title, board, it, rubixTarget = fixed) })
    check(TermGui.rubixTargetFor(board, committed = 3, cached = 4) == 3) {
        "Automation's committed target takes precedence over the manual cache"
    }
    check(TermGui.rubixTargetFor(emptyList(), committed = null, cached = null) == null)
    AutoTerminal.reset()
}
