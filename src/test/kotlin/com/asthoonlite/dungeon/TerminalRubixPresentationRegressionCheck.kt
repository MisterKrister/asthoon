package com.asthoonlite.dungeon

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun terminalRubixPresentationRegressionChecks() {
    val ring = listOf(
        Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
        Items.LIME_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
        Items.RED_STAINED_GLASS_PANE
    )
    val actual = ring.map { item ->
        requireNotNull(TermGui.rubixTileColor(TerminalHelper.rubixColorIndex(ItemStack(item)), wanted = 4, solver = false))
    }
    check(actual.toSet().size == 5 && actual.all { it ushr 24 == 255 }) {
        "Manual Rubix needs five distinct opaque ring colors"
    }
    check(actual.none { TermGui.tileBackground(it) == TermGui.tileBackground(null) }) {
        "Disabling Rubix hints must not hide its current ring behind neutral tiles"
    }
    check(TermGui.rubixTileColor(3, wanted = 3, solver = false) == actual[3]) {
        "Reaching the cached target does not hide the actual pane color"
    }
    check(TermGui.rubixTileColor(3, wanted = null, solver = false) == actual[3])
    check(TermGui.rubixTileColor(3, wanted = 4, solver = true) == 0xCC55FFFF.toInt())
    check(TermGui.rubixTileColor(4, wanted = 4, solver = true) == null) {
        "Enabled Rubix hints retain their completed-pane presentation"
    }
    check(TermGui.rubixTileColor(3, wanted = null, solver = true) == null)
    check(TermGui.rubixTileColor(null, wanted = 4, solver = false) == null)
    check(TermGui.rubixTileColor(-1, wanted = 4, solver = false) == null)
    check(TermGui.drawItems(TerminalSolver.Kind.RUBIX, simulation = true, solver = false, empty = false)) {
        "Manual practice Rubix retains its item icons over the actual color"
    }
    check(!TermGui.drawItems(TerminalSolver.Kind.RUBIX, simulation = true, solver = true, empty = false))
    check(TermGui.rubixLabel(3, 4, simulation = false) == "1")
    check(TermGui.rubixLabel(3, 4, simulation = true) == "1")
}
