package com.asthoonlite.mixin

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalSolver
import com.asthoonlite.pet.PetTracker
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

        // Next-click marker cache: which pane, as of when, for which terminal.
        private var markerTitle = ""
        private var markerNext: Int? = null
        private var markerAt = 0L
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
        if (QuietMode.suppressing()) return

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
        if (QuietMode.suppressing()) return
        val self = (this as Any) as AbstractContainerScreen<*>
        val title = self.title.string
        // Gate on the solver's own title test: it strips formatting, so a
        // coloured terminal title still gets its highlight instead of failing
        // a raw startsWith and never reaching colorFor.
        val kind = TerminalSolver.kindOf(title) ?: return

        // The player's own inventory sits in the same slot list *after* the
        // terminal's slots, and its Slot.containerSlot counts from zero all
        // over again. A red pane in the hotbar would therefore be read as
        // terminal slot 3, tinted, and ringed as the next click. Slot.index
        // does not restart, so it is the one that tells the two apart.
        if (slot.index >= kind.slotCount) return

        val all = self.menu.slots.map { it.item }
        val color = TerminalSolver.colorFor(
            title, slot.index, slot.item, all, AutoTerminal.rubixTargetOrNull()
        ) ?: return
        val sx = slot.x
        val sy = slot.y
        graphics.fill(sx, sy, sx + 16, sy + 16, color)
        graphics.fill(sx, sy, sx + 16, sy + 1, 0xFFFFFFFF.toInt())
        graphics.fill(sx, sy + 15, sx + 16, sy + 16, 0xFFFFFFFF.toInt())
        graphics.fill(sx, sy, sx + 1, sy + 16, 0xFFFFFFFF.toInt())
        graphics.fill(sx + 15, sy, sx + 16, sy + 16, 0xFFFFFFFF.toInt())

        // One ring, around one slot: the pane the clicker is about to send a
        // packet for. Tint says "this matters", the ring says "this *next*",
        // which is the distinction a screen full of coloured panes cannot
        // make on its own.
        if (slot.index == nextSlotFor(title, all)) {
            drawNextClickRing(graphics, sx, sy)
        }
    }

    /**
     * The slot the ring belongs to, recomputed at most every 50 ms.
     *
     * Per-slot it would be computed 54 times per frame; the clicker itself
     * only commits to a pane on a click cadence, so a marker a frame or two
     * behind is invisible and the saving is not.
     */
    private fun nextSlotFor(title: String, all: List<ItemStack>): Int? {
        val now = System.currentTimeMillis()
        if (now - markerAt < 50L && markerTitle == title) return markerNext
        markerTitle = title
        markerAt = now
        markerNext = TerminalSolver.nextClickSlot(
            screenTitle = title,
            items = all,
            blocked = AutoTerminal.unsettledSlots(now),
            rubixTarget = AutoTerminal.rubixTargetOrNull(),
            lastSlot = AutoTerminal.lastClickedSlot()
        )
        return markerNext
    }

    /** Pulsing outline outside the slot's own border, on the pane to hit. */
    private fun drawNextClickRing(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val phase = (System.currentTimeMillis() % 800L) / 800.0
        val pulse = (kotlin.math.sin(phase * 2 * kotlin.math.PI) * 0.5 + 0.5)
        val alpha = (160 + 95 * pulse).toInt().coerceIn(0, 255)
        val color = (alpha shl 24) or 0x0000E676

        val l = x - 1
        val t = y - 1
        val r = x + 17
        val b = y + 17
        graphics.fill(l, t, r, t + 2, color)
        graphics.fill(l, b - 2, r, b, color)
        graphics.fill(l, t, l + 2, b, color)
        graphics.fill(r - 2, t, r, b, color)
    }
}
