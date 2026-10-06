package com.asthoonlite.dungeon

import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput

/**
 * One door into a terminal's menu, used by both the clicker and the drawn
 * grid so that a manual click and a scheduled one cannot behave differently.
 *
 * There are two doors and which one opens depends on whose menu it is:
 *
 *  - **the player's window** (`player.containerMenu`) goes through
 *    `MultiPlayerGameMode.handleContainerInput` — packets, server round trip,
 *    carried-stack bookkeeping, exactly what a hand click does. This is the
 *    real-dungeon path and it is untouched.
 *
 *  - **a screen holding a menu that is not the player's** never gets there.
 *    `handleContainerInput` compares the container id against
 *    `player.containerMenu`, logs `Ignoring click in mismatching container`
 *    and drops the click on the floor. A client-side simulator keeps working
 *    for a hand because the hand arrives through `slotClicked` instead, which
 *    is the door this falls back to — and because the accessor is a virtual
 *    call, a screen that overrides `slotClicked` to handle clicks locally
 *    still gets its own logic, which is exactly what it does for a hand.
 *
 * Returns false only when nothing could be sent: no player, not the live
 * screen, or a menu with no such slot.
 */
object TerminalInput {
    fun send(
        screen: AbstractContainerScreen<*>,
        slotIndex: Int,
        button: Int,
        input: ContainerInput
    ): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (mc.screen !== screen) return false
        if (player.containerMenu === screen.menu) {
            val gameMode = mc.gameMode ?: return false
            gameMode.handleContainerInput(screen.menu.containerId, slotIndex, button, input, player)
            return true
        }
        val slot = screen.menu.slots.getOrNull(slotIndex) ?: return false
        val accessor = screen as? AbstractContainerScreenAccessor ?: return false
        // `slotClicked` re-derives the id from the slot when one is passed;
        // what it wants is the slot's own container index.
        accessor.invokeSlotClicked(slot, slot.index, button, input)
        return true
    }
}
