package com.asthoonlite.mixin

import com.asthoonlite.config.Config
import com.asthoonlite.pet.PetTracker
import com.asthoonlite.dungeon.TerminalSolver
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.ContainerInput
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

import com.asthoonlite.dungeon.AutoTerminal
import com.asthoonlite.funny.InventoryAutoClicker
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

// Confirmed against real 26.1.2 Mojang-mapped sources via javap:
// protected void slotClicked(Slot, int, int, ContainerInput)
// ClickType no longer exists; it was replaced by ContainerInput.
@Mixin(AbstractContainerScreen::class)
abstract class MixinHandledScreen {

    @Inject(
        method = ["keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_onContainerKeyPressed(event: KeyEvent, cir: CallbackInfoReturnable<Boolean>) {
        if (InventoryAutoClicker.handleScreenKeyPressed(event.key())) {
            cir.returnValue = true
        }
    }

    @Inject(
        method = ["mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_onContainerMouseClicked(event: MouseButtonEvent, doubleClick: Boolean, cir: CallbackInfoReturnable<Boolean>) {
        val self = (this as Any) as AbstractContainerScreen<*>
        val title = self.title.string
        if (title.contains("Stash", ignoreCase = true) || AutoTerminal.isTerminalTitle(title)) {
            if (title.contains("Stash", ignoreCase = true)) {
                InventoryAutoClicker.clearSkymyceWorthlessItems()
            }
            if (!self.menu.carried.isEmpty) {
                self.menu.carried = ItemStack.EMPTY
            }
            val mc = Minecraft.getInstance()
            if (mc.player != null && !mc.player!!.containerMenu.carried.isEmpty) {
                mc.player!!.containerMenu.carried = ItemStack.EMPTY
            }
        }
        if (InventoryAutoClicker.handleScreenMouseClicked(event.button())) {
            cir.returnValue = true
        }
    }

    @Inject(
        method = ["slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_onSlotClick(
        slot: Slot?,
        slotId: Int,
        button: Int,
        actionType: ContainerInput,
        ci: CallbackInfo
    ) {
        val self = (this as Any) as AbstractContainerScreen<*>
        if (self.title.string.startsWith("Pets")) {
            if (slot != null) {
                PetTracker.handleSlotClick(self, slot, slotId)
            }
            return
        }

        // Fix for Hypixel Stash (e.g. View Stash): prevents vanilla from clearing the slot item
        // and desyncing the cursor with a ghost item when manual clicking or picking up items.
        val title = self.title.string
        if (title.contains("Stash", ignoreCase = true) && slot != null && slot.hasItem()) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            if (InventoryAutoClicker.isStashSlot(slot, title, player)) {
                ci.cancel()
                val gameMode = mc.gameMode ?: return

                // Preserve exact button: button 0 (left-click) fills inventory, button 1 (right-click) grabs 1 stack
                val targetButton = button
                val savedItem = slot.item.copy()

                gameMode.handleContainerInput(self.menu.containerId, slot.index, targetButton, ContainerInput.PICKUP, player)

                // Restore slot item so it never disappears on client
                slot.set(savedItem)

                // Clear client-side carried stack immediately so subsequent clicks don't desync
                if (!self.menu.carried.isEmpty) {
                    self.menu.carried = ItemStack.EMPTY
                }
                if (!player.containerMenu.carried.isEmpty) {
                    player.containerMenu.carried = ItemStack.EMPTY
                }
            }
        }

        if (AutoTerminal.isTerminalTitle(title)) {
            val mc = Minecraft.getInstance()
            if (!self.menu.carried.isEmpty) {
                self.menu.carried = ItemStack.EMPTY
            }
            if (mc.player != null && !mc.player!!.containerMenu.carried.isEmpty) {
                mc.player!!.containerMenu.carried = ItemStack.EMPTY
            }
        }
    }

    // Draws the pet-menu slot highlight right after each slot's item/overlay
    // is rendered. Piggybacks on the per-slot render call (Mojang's
    // `extractSlot`) instead of `ScreenEvents.afterRender`, which — despite
    // being documented in older Fabric API versions — doesn't resolve
    // against this 26.1.2 fabric-screen-api-v1 build. NoammAddons hits the
    // same wall and also mixes into AbstractContainerScreen directly for
    // its per-slot/per-screen render hooks (see its MixinAbstractContainerScreen).
    private companion object {
        private const val HIGHLIGHT_COLOR = 0x881E90FF.toInt() // translucent blue fill
        private const val BORDER_COLOR    = 0xFF1E90FF.toInt() // solid blue border
    }

    @Inject(
        method = ["extractSlot"],
        at = [At("TAIL")]
    )
    private fun asthoonlite_onSlotRendered(
        graphics: GuiGraphicsExtractor,
        slot: Slot,
        mouseX: Int,
        mouseY: Int,
        ci: CallbackInfo
    ) {
        val self = (this as Any) as AbstractContainerScreen<*>
        if (self.title.string.contains("Stash", ignoreCase = true)) {
            InventoryAutoClicker.clearSkymyceWorthlessItems()
        }
        if (!Config.petMenuHighlightEnabled) return
        if (!self.title.string.startsWith("Pets")) return

        val stack = slot.item
        if (stack.isEmpty || !PetTracker.isPetItem(stack)) return
        if (!PetTracker.hasDesawnLore(stack)) return

        // extractSlot fires with the container's pose translation already
        // applied (Mojang pushes the leftPos/topPos translation once before
        // iterating slots, same as vanilla's own per-slot rendering), so
        // slot.x/slot.y alone are already in the right space here. Confirmed
        // against NoammAddons' own extractSlot-tail hook (ProtectItem.kt
        // draws at plain `slot.x + 1` / `slot.y + 1`, no leftPos/topPos
        // addition). Adding leftPos/topPos again double-offsets the
        // highlight off the actual slot — that was the bug.
        val sx = slot.x
        val sy = slot.y

        graphics.fill(sx, sy, sx + 16, sy + 16, HIGHLIGHT_COLOR)
        graphics.fill(sx, sy, sx + 16, sy + 1, BORDER_COLOR)
        graphics.fill(sx, sy + 15, sx + 16, sy + 16, BORDER_COLOR)
        graphics.fill(sx, sy, sx + 1, sy + 16, BORDER_COLOR)
        graphics.fill(sx + 15, sy, sx + 16, sy + 16, BORDER_COLOR)
    }

    @Inject(
        method = ["extractSlot"],
        at = [At("TAIL")]
    )
    private fun asthoonlite_terminalSolver(
        graphics: GuiGraphicsExtractor,
        slot: Slot,
        mouseX: Int,
        mouseY: Int,
        ci: CallbackInfo
    ) {
        if (!Config.terminalSolverEnabled) return
        val self = (this as Any) as AbstractContainerScreen<*>
        val title = self.title.string
        if (!title.startsWith("Correct all the panes!") &&
            !title.startsWith("Change all to same color!") &&
            !title.startsWith("Click in order!") &&
            !title.startsWith("What starts with:") &&
            !title.startsWith("Select all the") &&
            !title.startsWith("Click the button on time!")) return

        val all = self.menu.slots.map { it.item }
        val color = TerminalSolver.colorFor(title, slot.containerSlot, slot.item, all) ?: return
        val sx = slot.x
        val sy = slot.y
        graphics.fill(sx, sy, sx + 16, sy + 16, color)
        graphics.fill(sx, sy, sx + 16, sy + 1, 0xFFFFFFFF.toInt())
        graphics.fill(sx, sy + 15, sx + 16, sy + 16, 0xFFFFFFFF.toInt())
        graphics.fill(sx, sy, sx + 1, sy + 16, 0xFFFFFFFF.toInt())
        graphics.fill(sx + 15, sy, sx + 16, sy + 16, 0xFFFFFFFF.toInt())
    }
}
