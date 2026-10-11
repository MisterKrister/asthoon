package com.asthoonlite.dungeon

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun terminalOrderGoalRegressionChecks() {
    val title = "Click in order!"
    val kind = TerminalSolver.Kind.ORDER
    val board = MutableList(kind.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    // Live P3 capture 20261011_183317: two seven-wide rows, with the
    // number carried by stack count and no numeric display name.
    val slots = (10..16).toList() + (19..25).toList()
    val numbers = listOf(2, 12, 3, 8, 7, 6, 14, 5, 4, 11, 9, 10, 13, 1)
    slots.zip(numbers).forEach { (slot, number) ->
        board[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE, number)
    }

    check(TerminalSolver.goalFor(title, board) == 14) {
        "The live fourteen-pane board must not inherit the ten-pane practice goal"
    }
    check(TerminalSolver.numberSlots(board) == slots)
    for (number in 1..14) {
        val slot = slots[numbers.indexOf(number)]
        check(TerminalSolver.goalFor(title, board) == 15 - number)
        check(TerminalSolver.clickCandidates(title, board) == listOf(slot))
        check(TerminalSolver.labelFor(slot, board[slot], kind) == number.toString())
        board[slot] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    }
    check(TerminalSolver.goalFor(title, board) == 0)
    check(TerminalSolver.clickCandidates(title, board).isEmpty())
    check(TerminalSolver.numberSlots(board) == slots) {
        "Completed number tiles must retain their positions"
    }

    val practice = MutableList(kind.slotCount) { ItemStack.EMPTY }
    val practiceSlots = (1..2).flatMap { row -> (2..6).map { col -> row * 9 + col } }
    practiceSlots.forEachIndexed { index, slot ->
        practice[slot] = ItemStack(Items.RED_STAINED_GLASS_PANE, index + 1)
    }
    check(TerminalSolver.goalFor(title, practice) == 10) {
        "The ten-pane practice board keeps its actual goal"
    }
    check(TerminalSolver.goalFor(title, practice + List(36) { ItemStack(Items.RED_STAINED_GLASS_PANE) }) == 10) {
        "Player inventory panes must not increase the terminal goal"
    }
    check(TerminalSolver.goalFor(title, emptyList()) == 0)
}
