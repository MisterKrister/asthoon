package com.asthoonlite.mixin

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalCursor
import com.asthoonlite.dungeon.TermGui
import com.asthoonlite.dungeon.TerminalSolver
import com.asthoonlite.render.fillRoundedRect
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
        val self = (this as Any) as AbstractContainerScreen<*>
        if (TermGui.active(self)) {
            TermGui.keyPressed(self, event)
            cir.returnValue = true
            return
        }
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
        if (com.asthoonlite.utils.InputCapture.isCapturing) {
            com.asthoonlite.utils.InputCapture.onTerminalMousePress(event.button(), event.x(), event.y())
        }
        if (TermGui.active(self)) {
            TermGui.click(self, event.x(), event.y(), event.button())
            cir.returnValue = true
            return
        }
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

    // Verified with javap against Minecraft 26.1.2. The background is a separate
    // ContainerScreen hook; this replaces contents, carried items and tooltips.
    @Inject(method = ["extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"],
        at = [At("HEAD")], cancellable = true)
    private fun asthoonlite_customTerminal(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, ci: CallbackInfo) {
        val self = (this as Any) as AbstractContainerScreen<*>
        if (!TermGui.active(self)) return
        TermGui.render(self, graphics, mouseX, mouseY)
        ci.cancel()
    }

    @Inject(method = ["mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z"], at = [At("HEAD")], cancellable = true)
    private fun asthoonlite_customRelease(event: MouseButtonEvent, cir: CallbackInfoReturnable<Boolean>) {
        if (TermGui.active((this as Any) as AbstractContainerScreen<*>)) cir.returnValue = true
    }

    @Inject(method = ["mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"], at = [At("HEAD")], cancellable = true)
    private fun asthoonlite_customDrag(event: MouseButtonEvent, dx: Double, dy: Double, cir: CallbackInfoReturnable<Boolean>) {
        if (TermGui.active((this as Any) as AbstractContainerScreen<*>)) cir.returnValue = true
    }

    @Inject(method = ["mouseScrolled(DDDD)Z"], at = [At("HEAD")], cancellable = true)
    private fun asthoonlite_customScroll(x: Double, y: Double, horizontal: Double, vertical: Double, cir: CallbackInfoReturnable<Boolean>) {
        if (TermGui.active((this as Any) as AbstractContainerScreen<*>)) cir.returnValue = true
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
            com.asthoonlite.dungeon.TerminalCapture.onVanillaClick(self, slot?.index ?: slotId, button, actionType)
            if (slot != null && com.asthoonlite.utils.InputCapture.isCapturing) {
                com.asthoonlite.utils.InputCapture.onTerminalClick(self, slot.index, button, "SLOT_CLICK")
            }
            val mc = Minecraft.getInstance()
            if (!self.menu.carried.isEmpty) {
                self.menu.carried = ItemStack.EMPTY
            }
            if (mc.player != null && !mc.player!!.containerMenu.carried.isEmpty) {
                mc.player!!.containerMenu.carried = ItemStack.EMPTY
            }
        }
    }

    // --- What the drawn pointer owns, vanilla stops drawing ------------------
    //
    // While AutoTerminal is gliding, the arrow on screen sits at the tip of
    // TerminalCursor and GLFW's idea of where the mouse is is somewhere else
    // entirely — parked wherever it was left when the real cursor went away.
    // Vanilla still reads that stale position once a frame: `extractContents`
    // recomputes `hoveredSlot` from it, and everything derived from
    // `hoveredSlot` then points at a slot nobody is looking at. An item
    // tooltip popping up over an unrelated pane while an arrow clicks
    // somewhere else is the one thing that says the mouse is not really
    // there.
    //
    // So while TerminalCursor.ownsCursor() is set the three pieces vanilla
    // draws off that stale position are cancelled: the tooltip itself, and
    // the highlight sprite behind and in front of the hovered slot. None of
    // them mutate state — they just do not draw — so the moment the pointer
    // leaves the screen vanilla comes back on its own with `hoveredSlot`
    // still holding whatever the physical mouse last landed on.

    @Inject(
        method = ["extractTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_suppressTooltip(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        ci: CallbackInfo
    ) {
        if (TerminalCursor.ownsCursor()) ci.cancel()
    }

    @Inject(
        method = ["extractSlotHighlightBack(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_suppressHighlightBack(
        graphics: GuiGraphicsExtractor,
        ci: CallbackInfo
    ) {
        if (TerminalCursor.ownsCursor()) ci.cancel()
    }

    @Inject(
        method = ["extractSlotHighlightFront(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_suppressHighlightFront(
        graphics: GuiGraphicsExtractor,
        ci: CallbackInfo
    ) {
        if (TerminalCursor.ownsCursor()) ci.cancel()
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

        // ── Terminal overlay geometry ───────────────────────────────────────
        //
        // Vanilla gives every slot 16px at an 18px pitch, so tiles are cut to
        // 14px and centred: that leaves the 2px gutter the panel shows through,
        // which is what turns forty-four separate squares into one grid.
        private const val TILE_INSET = 1
        private const val TILE_SIZE = 14
        private const val TILE_RADIUS = 3

        private const val PANEL_PAD = 3
        private const val PANEL_RADIUS = 6

        /** Under the whole grid. */
        private const val PANEL_COLOR = 0xF0090B10.toInt()

        /**
         * Tiles the solver has nothing to say about. Lighter than the panel so
         * the grid still reads as a grid, dark enough that a pane carrying a
         * real colour is obviously the one that matters.
         */
        private const val NEUTRAL_COLOR = 0xE6262B36.toInt()

        private const val LABEL_COLOR = 0xFFFFFFFF.toInt()
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

    /**
     * The terminal solver's whole overlay: panel, tiles, labels, next marker.
     *
     * Draws from `extractSlots`' tail rather than from each `extractSlot`,
     * because the shape it draws is one object and not forty-four. A panel has
     * to sit *behind* every slot, and the only place with all of them already
     * on screen is after the last one — per-slot there is no point in the pass
     * where a background could go without covering slots the pass has not
     * drawn yet. Doing it here also keeps the ordering vanilla wants: slots,
     * then this, then the tooltip that sits on top of everything.
     *
     * The pose is still translated to the container's origin at this point
     * (Mojang pushes `leftPos`/`topPos` once around the whole slot pass), so
     * `slot.x`/`slot.y` are the right space exactly as they were when this ran
     * per slot.
     */
    @Inject(
        method = ["extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"],
        at = [At("TAIL")]
    )
    private fun asthoonlite_terminalGrid(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        ci: CallbackInfo
    ) {
        // The custom grid stands on its own: it does not need the highlight
        // pass, but it does need the screen to be drawn — so it opens the
        // gate the solver switch used to hold on its own.
        if (!Config.terminalSolverEnabled) return
        if (QuietMode.suppressing()) return
        val self = (this as Any) as AbstractContainerScreen<*>
        val title = self.title.string
        // Gate on the solver's own title test: it strips formatting, so a
        // coloured terminal title still gets its overlay instead of failing a
        // raw startsWith and never reaching colorFor.
        val kind = TerminalSolver.kindOf(title) ?: return

        val slots = self.menu.slots
        val all = slots.map { it.item }


        // One pass decides both what to draw and whether to draw at all. A
        // terminal the solver cannot parse answers "nothing to say" for every
        // slot, and the right response to nothing to say is to leave the
        // vanilla slots exactly as they are — painting a grid of blank tiles
        // over them would take information away instead of adding any.
        val paints = arrayOfNulls<Int>(kind.slotCount)
        var anyPainted = false
        for (i in 0 until kind.slotCount) {
            val stack = all.getOrNull(i) ?: continue
            val color = TerminalSolver.colorFor(
                title, i, stack, all, AutoTerminal.rubixTargetOrNull()
            ) ?: continue
            paints[i] = color
            anyPainted = true
        }
        if (!anyPainted) return

        // The terminal's own slots only. Everything after them is the player's
        // inventory, sitting in the same list with its own numbering — pulling
        // that into the grid would put a panel over the hotbar.
        val termSlots = slots.take(kind.slotCount)
        if (termSlots.isEmpty()) return
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (s in termSlots) {
            if (s.x < left) left = s.x
            if (s.y < top) top = s.y
            if (s.x > right) right = s.x
            if (s.y > bottom) bottom = s.y
        }

        // One surface under the whole grid. This is the piece that makes the
        // overlay read as a terminal built for the solver rather than as
        // highlights painted onto the vanilla chest: without it the tiles are
        // forty-four squares floating over somebody else's panel.
        fillRoundedRect(
            graphics,
            left - PANEL_PAD, top - PANEL_PAD,
            right + 16 + PANEL_PAD, bottom + 16 + PANEL_PAD,
            PANEL_COLOR, PANEL_RADIUS
        )

        val font = Minecraft.getInstance().font
        val marker = if (kind == TerminalSolver.Kind.ORDER || kind == TerminalSolver.Kind.MELODY) nextSlotFor(title, all) else null
        for ((i, s) in termSlots.withIndex()) {
            val x = s.x + TILE_INSET
            val y = s.y + TILE_INSET

            // The marker goes down *under* the tile so that it reads as a
            // frame in the gap around it rather than as a box drawn over the
            // pane. Drawing it afterwards would either cover the tile or need
            // an outline pass that the flat fills have no room for.
            if (i == marker) {
                fillRoundedRect(graphics, s.x, s.y, s.x + 16, s.y + 16, nextClickColor(), TILE_RADIUS + 1)
            }

            fillRoundedRect(
                graphics, x, y, x + TILE_SIZE, y + TILE_SIZE,
                paints[i] ?: NEUTRAL_COLOR, TILE_RADIUS
            )

            val label = TerminalSolver.labelFor(i, all.getOrNull(i), kind) ?: continue
            graphics.text(
                font, label,
                s.x + (16 - font.width(label)) / 2,
                s.y + (16 - font.lineHeight) / 2,
                LABEL_COLOR
            )
        }
    }

    /**
     * The slot the marker belongs to, recomputed at most every 50 ms.
     *
     * The grid asks once per frame rather than once per slot, but 50ms still
     * buys something: the clicker only commits to a pane on a click cadence,
     * so a marker a frame or two behind is invisible and the saving is not.
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
            lastSlot = AutoTerminal.lastClickedSlot(),
            clickOrder = Config.autoTerminalClickOrder
        )
        return markerNext
    }

    /** Pulsing colour for the tile the clicker is about to send a packet for. */
    private fun nextClickColor(): Int {
        val phase = (System.currentTimeMillis() % 800L) / 800.0
        val pulse = (kotlin.math.sin(phase * 2 * kotlin.math.PI) * 0.5 + 0.5)
        val alpha = (160 + 95 * pulse).toInt().coerceIn(0, 255)
        return (alpha shl 24) or 0x0000E676
    }
}
