package com.asthoonlite.dungeon

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun terminalPanePresentationRegressionChecks() {
    // Live capture 20261011_184714: twelve unfinished red panes and three lime.
    val board = MutableList(45) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    val redSlots = setOf(12, 13, 14, 21, 22, 23, 24, 29, 30, 31, 32, 33)
    val limeSlots = setOf(11, 15, 20)
    redSlots.forEach { board[it] = ItemStack(Items.RED_STAINED_GLASS_PANE) }
    limeSlots.forEach { board[it] = ItemStack(Items.LIME_STAINED_GLASS_PANE) }
    val red = requireNotNull(TermGui.paneColor(board[12], solver = false, hintColor = null))
    val lime = requireNotNull(TermGui.paneColor(board[11], solver = false, hintColor = null))
    check(red != lime && TermGui.tileBackground(red) != TermGui.tileBackground(null)) {
        "Manual players must distinguish unfinished red panes from lime and neutral tiles"
    }
    check(red ushr 24 == 255 && lime ushr 24 == 255) { "Actual pane colors must remain opaque" }
    check(TermGui.tiles(TerminalSolver.Kind.PANES).map { it.slot }.toSet() == redSlots + limeSlots)
    check(board.count { TermGui.paneColor(it, false, null) == red } == 12)
    check(board.count { TermGui.paneColor(it, false, null) == lime } == 3)
    board[12] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    check(TermGui.paneColor(board[12], false, null) == lime) {
        "A completed live pane must visibly change from red to lime without solver hints"
    }

    val hint = 0xCC55FFFF.toInt()
    val pending = ItemStack(Items.RED_STAINED_GLASS_PANE)
    check(TermGui.paneColor(pending, solver = true, hintColor = hint) == hint) {
        "Enabled solver hints retain their existing presentation"
    }
    check(TermGui.paneColor(board[11], solver = true, hintColor = null) == null)
    check(TermGui.paneColor(pending, solver = false, hintColor = hint) == red)
    check(TermGui.paneColor(ItemStack(Items.GREEN_STAINED_GLASS_PANE), false, null) != null)
    check(TermGui.paneColor(ItemStack.EMPTY, false, null) == null)
    check(TermGui.paneColor(ItemStack(Items.BLACK_STAINED_GLASS_PANE), false, null) == null)
}
