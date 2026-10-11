package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.TerminalSolver.Kind
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal fun terminalManualInputRegressionChecks() {
    AutoTerminal.reset()
    val orderTitle = "Click in order!"
    val numbers = MutableList(Kind.ORDER.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    numbers[11] = ItemStack(Items.RED_STAINED_GLASS_PANE, 1)
    numbers[12] = ItemStack(Items.RED_STAINED_GLASS_PANE, 2)
    check(TermGui.canClick(Kind.ORDER, orderTitle, numbers, 11))
    // The client board is unchanged while the server acknowledges the first
    // input. A second physical click must still be sent for server validation.
    check(TerminalSolver.clickCandidates(orderTitle, numbers) == listOf(11))
    check(TermGui.canClick(Kind.ORDER, orderTitle, numbers, 12)) {
        "A manual 1 -> 2 burst must not wait for the first pane acknowledgement"
    }
    check(!TermGui.canClick(Kind.ORDER, orderTitle, numbers, 10))
    check(!TermGui.canClick(Kind.ORDER, orderTitle, numbers, -1))
    check(!TermGui.canClick(Kind.ORDER, orderTitle, numbers, Kind.ORDER.slotCount))

    val rubixTitle = "Change all to same color!"
    val ring = listOf(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
        Items.LIME_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS_PANE)
    val rubix = MutableList(Kind.RUBIX.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    TerminalSolver.RUBIX_SLOTS.forEachIndexed { index, slot -> rubix[slot] = ItemStack(ring[index % 5]) }
    check(TerminalSolver.RUBIX_SLOTS.all { TermGui.canClick(Kind.RUBIX, rubixTitle, rubix, it) })
    val redSlot = TerminalSolver.RUBIX_SLOTS[4]
    check(redSlot !in TerminalSolver.clickCandidates(rubixTitle, rubix, rubixTarget = 4))
    check(TermGui.canClick(Kind.RUBIX, rubixTitle, rubix, redSlot)) {
        "The solver target cannot block an explicit right click"
    }
    val right = TermGui.rubixClickPlan(rubix[redSlot], predicted = null, target = 4, requestedButton = 1)
    check(right.button == 1 && right.nextColor == 3)

    // Vanilla PICKUP removes the stack locally before TerminalInput.send
    // returns. The recorded plan must retain red -> orange, not assume that
    // the now-empty slot started at orange and produce yellow instead.
    val beforeSend = TermGui.rubixClickPlan(rubix[redSlot], predicted = null, target = 1, requestedButton = 0)
    check(beforeSend.button == 0 && beforeSend.nextColor == 0)
    rubix[redSlot] = ItemStack.EMPTY
    check(TerminalHelper.rubixColorIndex(rubix[redSlot]) == -1)
    check(beforeSend.nextColor == 0) { "Post-send slot removal cannot change the planned next colour" }
    val second = TermGui.rubixClickPlan(rubix[redSlot], predicted = beforeSend.nextColor, target = 1, requestedButton = 0)
    check(second.button == 0 && second.nextColor == 1) { "Rapid Rubix inputs use the previous prediction" }
    val backward = TermGui.rubixClickPlan(ItemStack(ring[0]), predicted = null, target = 4, requestedButton = 0)
    check(backward.button == 1 && backward.nextColor == 4) { "Manual left still chooses the shortest direction" }
    check(!TermGui.canClick(Kind.RUBIX, rubixTitle, rubix, redSlot))
    rubix[11] = ItemStack(Items.RED_STAINED_GLASS_PANE)
    check(!TermGui.canClick(Kind.RUBIX, rubixTitle, rubix, 11)) { "A pane outside the Rubix grid is not a control" }

    val panes = MutableList(Kind.PANES.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    listOf(Items.RED_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE)
        .forEachIndexed { index, item -> panes[11 + index] = ItemStack(item) }
    check((11..13).all { TermGui.canClick(Kind.PANES, "Correct all the panes!", panes, it) })
    check(!TermGui.canClick(Kind.PANES, "Correct all the panes!", panes, 14))
    panes[14] = ItemStack(Items.APPLE)
    check(!TermGui.canClick(Kind.PANES, "Correct all the panes!", panes, 14))

    for ((kind, title) in listOf(Kind.SELECT to "Select all the RED items!", Kind.STARTS to "What starts with: 'a'?")) {
        val choices = MutableList(kind.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
        choices[10] = ItemStack(Items.STONE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("Stone")) }
        choices[11] = choices[10].copy().apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
        check(TerminalSolver.clickCandidates(title, choices).isEmpty())
        check(TermGui.canClick(kind, title, choices, 10) && TermGui.canClick(kind, title, choices, 11)) {
            "The player may select or undo a displayed choice independently of solver matching"
        }
        check(!TermGui.canClick(kind, title, choices, 12))
        choices[12] = ItemStack.EMPTY
        check(!TermGui.canClick(kind, title, choices, 12))
        choices[0] = choices[10].copy()
        check(!TermGui.canClick(kind, title, choices, 0)) { "Choice filler outside the rendered grid is not interactive" }
    }

    val melody = MutableList(Kind.MELODY.slotCount) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    melody[3] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
    melody[16] = ItemStack(Items.LIME_TERRACOTTA)
    melody[11] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    check(TerminalSolver.clickCandidates("Click the button on time!", melody).isEmpty()) {
        "The fixture note in column 2 must not align with the marker in column 3"
    }
    check(TermGui.canClick(Kind.MELODY, "Click the button on time!", melody, 16)) {
        "An evidenced Melody button remains clickable while its note is unaligned"
    }
    check(!TermGui.canClick(Kind.MELODY, "Click the button on time!", melody, 3))
    check(!TermGui.canClick(Kind.MELODY, "Click the button on time!", melody, 11))
    check(!TermGui.canClick(Kind.MELODY, "Click the button on time!", melody, 25))

    val grid = TermGui.layout(Kind.ORDER, 640, 360, 150, 4, orderSlots = listOf(11, 12))
    val firstCenter = requireNotNull(grid.center(11))
    check(grid.hitTest(firstCenter.first.toDouble(), firstCenter.second.toDouble()) == 11)
    val gapX = grid.originX + (TermGui.TILE_SIZE + 2) * grid.scale
    check(grid.hitTest(gapX.toDouble(), firstCenter.second.toDouble()) == null) {
        "The spacing between interactive tiles cannot become a hidden slot input"
    }
    AutoTerminal.reset()
}
