package com.asthoonlite.dungeon

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Exercise the same production decision used by live and practice menus. */
internal fun terminalLiveMelodyDecisionRegressionChecks() {
    val title = "Click the button on time!"
    val id = 61
    val board = MutableList(45) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }.also { items ->
        items[3] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
        items[39] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
        for (row in 1..3) {
            for (col in 1..5) items[row * 9 + col] = ItemStack(Items.WHITE_STAINED_GLASS_PANE)
            items[row * 9 + 7] = ItemStack(Items.RED_TERRACOTTA)
        }
        items[12] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
        items[16] = ItemStack(Items.LIME_TERRACOTTA)
    }
    fun liveItems(): List<ItemStack> = (0 until TerminalSensing.slotCount).map { TerminalSensing.getItems()[it] ?: ItemStack.EMPTY }
    fun update(slot: Int, stack: ItemStack) = TerminalSensing.onSetSlot(ClientboundContainerSetSlotPacket(id, 2, slot, stack))
    fun decision(now: Long) = AutoTerminal.melodyDecision(liveItems(), now)
    fun commit(slot: Int, now: Long, delay: Long, skip: Boolean) {
        AutoTerminal.recordClick(now, slot, delay)
        AutoTerminal.recordMelodyClick(liveItems(), slot, now, skip, dontSkipFirst = false)
    }

    AutoTerminal.reset()
    TerminalSensing.reset()
    try {
        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(id, MenuType.GENERIC_9x5, Component.literal(title)))
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(id, 1, board, ItemStack.EMPTY))
        check(TerminalSensing.isLoaded && TerminalSensing.containerId == id)
        check(AutoTerminal.beginTerminal(title, 1_000L))
        val firstDelay = AutoTerminal::class.java.getDeclaredField("currentClickDelayMs").apply { isAccessible = true }
        firstDelay.setLong(null, 430L)
        check(decision(1_429L) == AutoTerminal.MelodyDecision(16, null))
        check(decision(1_430L).clickSlot == 16) { "Loaded live Melody must honor the first-click delay" }

        commit(16, 1_430L, 550L, skip = false)
        check(decision(1_431L) == AutoTerminal.MelodyDecision(25, null)) { "Live Melody must premove before the server acknowledges row one" }
        check(decision(1_829L) == AutoTerminal.MelodyDecision(25, null)) { "An unchanged live row must retain the acknowledgement grace" }
        check(decision(1_830L) == AutoTerminal.MelodyDecision(16, null)) { "A retry after the grace must still wait for the click clock" }

        // The server advances the note before the next configured beat.
        update(12, ItemStack(Items.WHITE_STAINED_GLASS_PANE))
        update(16, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        update(21, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        update(25, ItemStack(Items.LIME_TERRACOTTA))
        check(decision(1_831L) == AutoTerminal.MelodyDecision(25, null))
        check(decision(1_979L).clickSlot == null && decision(1_980L).clickSlot == 25) {
            "A live packet row change must not bypass the configured 550ms cadence"
        }

        // A queued skip remains pending while the pointer is travelling, then
        // successful click bookkeeping consumes it and offers the next row.
        AutoTerminal.reset()
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(id, 3, board, ItemStack.EMPTY))
        check(AutoTerminal.beginTerminal(title, 3_000L))
        commit(16, 3_000L, 180L, skip = true)
        update(12, ItemStack(Items.WHITE_STAINED_GLASS_PANE))
        update(16, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        check(decision(3_179L) == AutoTerminal.MelodyDecision(25, null))
        check(decision(3_180L).clickSlot == 25 && decision(3_181L).clickSlot == 25) {
            "Observing a due skip must not consume it before the input is sent"
        }
        commit(25, 3_181L, 180L, skip = true)
        update(25, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        check(decision(3_360L).clickSlot == null && decision(3_361L).clickSlot == 34) {
            "The sent skip must consume row two and schedule row three on the next beat"
        }
        commit(34, 3_361L, 180L, skip = true)
        update(34, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        check(decision(3_541L) == AutoTerminal.MelodyDecision(null, null)) { "Completed packet rows must not leave duplicate skip inputs" }

        AutoTerminal.reset()
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(id, 4, board, ItemStack.EMPTY))
        check(AutoTerminal.beginTerminal(title, 4_000L))
        commit(16, 4_000L, 180L, skip = true)
        update(16, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        update(25, ItemStack(Items.LIME_STAINED_GLASS_PANE))
        check(decision(4_180L).clickSlot == 34) { "A queued row completed by a packet must be skipped during revalidation" }

        // No acknowledgement arrives: the old aligned row must not overtake
        // later queued rows, but it remains recoverable after their grace.
        AutoTerminal.reset()
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(id, 5, board, ItemStack.EMPTY))
        check(AutoTerminal.beginTerminal(title, 5_000L))
        commit(16, 5_000L, 180L, skip = true)
        check(decision(5_180L).clickSlot == 25)
        commit(25, 5_180L, 180L, skip = true)
        check(decision(5_181L) == AutoTerminal.MelodyDecision(34, null)) {
            "An earlier aligned packet row must not pull the premove back after a later queued click"
        }
        check(decision(5_360L) == AutoTerminal.MelodyDecision(34, 34)) {
            "The remaining queued row must keep priority while earlier packets await acknowledgement"
        }
        commit(34, 5_360L, 180L, skip = true)
        check(decision(5_759L) == AutoTerminal.MelodyDecision(34, null)) {
            "The last row must hold its aim during the grace instead of jumping to an older packet row"
        }
        check(decision(5_760L).clickSlot == 16) {
            "A sent skip is not proof of completion: an unchanged earlier row must become retryable after the grace"
        }

        // Captured from the live four-row board: earlier red buttons and white
        // tracks, a fourth lime button/note, and an indicator in the sixth row.
        val captured = MutableList(54) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }.also { items ->
            items[5] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
            items[50] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
            for (row in 1..4) {
                items[row * 9 + 7] = ItemStack(if (row == 4) Items.LIME_TERRACOTTA else Items.RED_TERRACOTTA)
                for (col in 1..5) items[row * 9 + col] = ItemStack(if (row == 4) Items.RED_STAINED_GLASS_PANE else Items.WHITE_STAINED_GLASS_PANE)
            }
            items[41] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
        }
        val rows = TerminalSolver.melodyRows(captured)
        check(rows.map { it.buttonSlot } == listOf(16, 25, 34, 43))
        check(rows.map { it.completed } == listOf(true, true, true, false)) { "Observed fourth-row progress confirms earlier red buttons completed" }
        check(TerminalSolver.melodyCandidate(captured) == 43 && TerminalSolver.melodyMarker(captured) == 5)
        check(TerminalSolver.goalFor(title, captured) == 4)
        check(TermGui.canClick(TerminalSolver.Kind.MELODY, title, captured, 43))
        check(TermGui.canClick(TerminalSolver.Kind.MELODY, title, captured, 34)) {
            "An evidenced earlier Melody button remains a manual control"
        }
        val contentRows = rows.map { it.row }
        val grid = TermGui.layout(TerminalSolver.Kind.MELODY, 960, 601, 150, 2, contentRows)
        val fourthButton = checkNotNull(grid.center(43))
        check(grid.hitTest(fourthButton.first.toDouble(), fourthButton.second.toDouble()) == 43) { "Drawing, manual input and pointer targeting must share the fourth button" }
        check(grid.center(50) != null && grid.center(52) == null && grid.tiles.all { it.slot < 54 })
        check(TermGui.requiredSlotsFor(TerminalSolver.Kind.MELODY, captured) == 51)

        AutoTerminal.reset()
        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(id + 1, MenuType.GENERIC_9x6, Component.literal(title)))
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(id + 1, 1,
            captured + List(36) { ItemStack(Items.LIME_TERRACOTTA) }, ItemStack.EMPTY))
        check(TerminalSensing.isLoaded && TerminalSensing.melodyButtonRow == 3 && TerminalSensing.melodyCurrentCol == 4)
        check(TerminalSolver.melodyRows(liveItems()).size == 4) { "Inventory items must not add another Melody content row" }
        check(AutoTerminal.beginTerminal(title, 6_000L))
        check(decision(6_000L) == AutoTerminal.MelodyDecision(43, 43)) { "A loaded live fourth row must reach the shared click driver" }
    } finally {
        AutoTerminal.reset()
        TerminalSensing.reset()
    }
}
