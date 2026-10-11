package com.asthoonlite.dungeon

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Runs with the existing registry bootstrap, without a client or connection. */
internal fun terminalSensingLifecycleRegressionChecks() {
    val title = "Click the button on time!"
    val menuId = 41
    val items = MutableList(45) { ItemStack(Items.BLACK_STAINED_GLASS_PANE) }
    items[3] = ItemStack(Items.MAGENTA_STAINED_GLASS_PANE)
    items[12] = ItemStack(Items.LIME_STAINED_GLASS_PANE)
    items[16] = ItemStack(Items.LIME_TERRACOTTA)

    AutoTerminal.reset()
    TerminalSensing.reset()
    try {
        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(menuId, MenuType.GENERIC_9x5, Component.literal(title)))
        check(TerminalSensing.inTerminal && !TerminalSensing.isLoaded && TerminalSensing.getItems().isEmpty())
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(menuId, 1, items, ItemStack.EMPTY))
        val loadedAt = TerminalSensing.openedAt
        check(TerminalSensing.isLoaded && loadedAt > 0L && TerminalSensing.getItems().size == 45)
        check(TerminalSensing.melodyButtonRow == 0 && TerminalSensing.melodyCurrentCol == 2 && TerminalSensing.melodyCorrectCol == 2)

        check(AutoTerminal.beginTerminal(title, loadedAt))
        check(TerminalSensing.inTerminal && TerminalSensing.isLoaded && TerminalSensing.containerId == menuId) {
            "Starting the solver must retain the live menu's already-loaded packet state"
        }
        check(TerminalSensing.currentTitle == title && TerminalSensing.openedAt == loadedAt && TerminalSensing.getItems().size == 45)
        check(TerminalSensing.melodyButtonRow == 0 && TerminalSensing.melodyCurrentCol == 2 && TerminalSensing.melodyCorrectCol == 2) {
            "Starting the solver must retain the packet-tracked Melody row and columns"
        }

        // Further packets must still match this menu after solver initialization.
        TerminalSensing.onSetSlot(ClientboundContainerSetSlotPacket(menuId, 2, 12, ItemStack(Items.WHITE_STAINED_GLASS_PANE)))
        TerminalSensing.onSetSlot(ClientboundContainerSetSlotPacket(menuId, 3, 13, ItemStack(Items.LIME_STAINED_GLASS_PANE)))
        check(TerminalSensing.getItems()[12]?.`is`(Items.WHITE_STAINED_GLASS_PANE) == true)
        check(TerminalSensing.melodyCurrentCol == 3 && TerminalSensing.melodyLastLimeSlot == 13)
        AutoTerminal.recordClick(loadedAt + 500L, 16, 180L)
        AutoTerminal.reset()
        check(TerminalSensing.isLoaded && TerminalSensing.openedAt == loadedAt && TerminalSensing.melodyCurrentCol == 3) {
            "Stopping or restarting automation must leave current menu sensing to its own lifecycle"
        }
        check(AutoTerminal.beginTerminal(title, loadedAt))
        check(!AutoTerminal.beginTerminal(title, loadedAt + 1L) && TerminalSensing.isLoaded)

        TerminalSensing.onClose(menuId - 1)
        check(TerminalSensing.isLoaded) { "A different menu's close must not discard the active terminal" }
        TerminalSensing.onClose(menuId)
        check(!TerminalSensing.inTerminal && !TerminalSensing.isLoaded && TerminalSensing.containerId == -1)
        check(TerminalSensing.getItems().isEmpty() && TerminalSensing.melodyButtonRow == null && TerminalSensing.melodyCurrentCol == null)
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(menuId, 4, items, ItemStack.EMPTY))
        check(TerminalSensing.getItems().isEmpty()) { "Packets after a matching close must not revive the terminal" }

        val nextId = menuId + 1
        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(nextId, MenuType.GENERIC_9x5, Component.literal(title)))
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(menuId, 5, items, ItemStack.EMPTY))
        check(!TerminalSensing.isLoaded && TerminalSensing.getItems().isEmpty()) { "Old menu packets must not load a newly opened terminal" }
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(nextId, 1, items, ItemStack.EMPTY))
        check(TerminalSensing.isLoaded)
        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(nextId + 1, MenuType.GENERIC_9x3, Component.literal("Chest")))
        check(!TerminalSensing.inTerminal && TerminalSensing.getItems().isEmpty()) { "Opening a different screen ends terminal sensing" }

        TerminalSensing.onOpenScreen(ClientboundOpenScreenPacket(nextId + 2, MenuType.GENERIC_9x5, Component.literal(title)))
        TerminalSensing.onSetContent(ClientboundContainerSetContentPacket(nextId + 2, 1, items, ItemStack.EMPTY))
        TerminalSensing.reset() // The same reset invoked by the connection's DISCONNECT listener.
        check(!TerminalSensing.inTerminal && !TerminalSensing.isLoaded && TerminalSensing.openedAt == 0L)
        check(TerminalSensing.getItems().isEmpty() && TerminalSensing.melodyCorrectCol == null && TerminalSensing.melodyLastLimeSlot == -1)
    } finally {
        AutoTerminal.reset()
        TerminalSensing.reset()
    }
    terminalLiveMelodyDecisionRegressionChecks()
}
