package com.asthoonlite.mixin;

import com.asthoonlite.QuietMode;
import com.asthoonlite.config.Config;
import com.asthoonlite.dungeon.AutoTerminal;
import com.asthoonlite.dungeon.TermGui;
import com.asthoonlite.dungeon.TerminalCapture;
import com.asthoonlite.dungeon.TerminalCursor;
import com.asthoonlite.dungeon.TerminalHelper;
import com.asthoonlite.dungeon.TerminalSolver;
import com.asthoonlite.funny.InventoryAutoClicker;
import com.asthoonlite.pet.PetTracker;
import com.asthoonlite.render.RoundedRectKt;
import com.asthoonlite.utils.InputCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinHandledScreen {

    private static final int HIGHLIGHT_COLOR = 0x881E90FF;
    private static final int BORDER_COLOR    = 0xFF1E90FF;

    private static String markerTitle = "";
    private static Integer markerNext = null;
    private static long markerAt = 0L;

    private static final int TILE_INSET = 1;
    private static final int TILE_SIZE = 14;
    private static final int TILE_RADIUS = 3;

    private static final int PANEL_PAD = 3;
    private static final int PANEL_RADIUS = 6;

    private static final int PANEL_COLOR = 0xF0090B10;
    private static final int NEUTRAL_COLOR = 0xE6262B36;
    private static final int LABEL_COLOR = 0xFFFFFFFF;

    @Inject(
        method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_onContainerKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (TermGui.INSTANCE.active(self)) {
            TermGui.INSTANCE.keyPressed(self, event);
            cir.setReturnValue(true);
            return;
        }
        if (InventoryAutoClicker.INSTANCE.handleScreenKeyPressed(event.key())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
        method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_onContainerMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (InputCapture.INSTANCE.isCapturing()) {
            InputCapture.INSTANCE.onTerminalMousePress(event.button(), event.x(), event.y());
        }
        if (TermGui.INSTANCE.active(self)) {
            TermGui.INSTANCE.click(self, event.x(), event.y(), event.button(), "manual");
            cir.setReturnValue(true);
            return;
        }
        String title = self.getTitle().getString();

        if (title.toLowerCase().contains("stash") || AutoTerminal.INSTANCE.isTerminalTitle(title)) {
            if (title.toLowerCase().contains("stash")) {
                InventoryAutoClicker.INSTANCE.clearSkymyceWorthlessItems();
            }
            if (!self.getMenu().getCarried().isEmpty()) {
                self.getMenu().setCarried(ItemStack.EMPTY);
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && !mc.player.containerMenu.getCarried().isEmpty()) {
                mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
        if (InventoryAutoClicker.INSTANCE.handleScreenMouseClicked(event.button())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_customTerminal(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!TermGui.INSTANCE.active(self)) return;
        TermGui.INSTANCE.render(self, graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", at = @At("HEAD"), cancellable = true)
    private void asthoonlite_customRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TermGui.INSTANCE.active((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", at = @At("HEAD"), cancellable = true)
    private void asthoonlite_customDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (TermGui.INSTANCE.active((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void asthoonlite_customScroll(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (TermGui.INSTANCE.active((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(
        method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_onSlotClick(
        Slot slot,
        int slotId,
        int button,
        ContainerInput actionType,
        CallbackInfo ci
    ) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (self.getTitle().getString().startsWith("Pets")) {
            if (slot != null) {
                PetTracker.INSTANCE.handleSlotClick(self, slot, slotId);
            }
            return;
        }

        String title = self.getTitle().getString();
        if (title.toLowerCase().contains("stash") && slot != null && slot.hasItem()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && InventoryAutoClicker.INSTANCE.isStashSlot(slot, title, mc.player)) {
                ci.cancel();
                if (mc.gameMode != null) {
                    int targetButton = button;
                    ItemStack savedItem = slot.getItem().copy();
                    mc.gameMode.handleContainerInput(self.getMenu().containerId, slot.index, targetButton, ContainerInput.PICKUP, mc.player);
                    slot.set(savedItem);
                    if (!self.getMenu().getCarried().isEmpty()) {
                        self.getMenu().setCarried(ItemStack.EMPTY);
                    }
                    if (!mc.player.containerMenu.getCarried().isEmpty()) {
                        mc.player.containerMenu.setCarried(ItemStack.EMPTY);
                    }
                }
            }
        }

        if (AutoTerminal.INSTANCE.isTerminalTitle(title)) {
            TerminalCapture.INSTANCE.onVanillaClick(self, slot != null ? slot.index : slotId, button, actionType);
            if (slot != null && InputCapture.INSTANCE.isCapturing()) {
                InputCapture.INSTANCE.onTerminalClick(self, slot.index, button, "SLOT_CLICK");
            }

            if (Config.INSTANCE.getTerminalSolverEnabled() && slot != null &&
                TerminalSolver.INSTANCE.kindOf(title) == TerminalSolver.Kind.RUBIX &&
                TerminalSolver.RUBIX_SLOTS.contains(slot.index) && actionType == ContainerInput.PICKUP) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.gameMode != null) {
                    List<ItemStack> all = new ArrayList<>();
                    int checkSlots = Math.min(45, self.getMenu().slots.size());
                    for (int i = 0; i < checkSlots; i++) {
                        all.add(self.getMenu().slots.get(i).getItem());
                    }
                    Integer current = AutoTerminal.INSTANCE.rubixPredicted(slot.index);
                    if (current == null) {
                        int idx = TerminalHelper.INSTANCE.rubixColorIndex(slot.getItem());
                        if (idx >= 0) current = idx;
                    }
                    Integer target = AutoTerminal.rubixTargetOrNull();
                    if (target == null) target = TerminalSolver.INSTANCE.optimalRubixTarget(all);

                    if (current != null && target != null && current >= 0 && current <= 4 && target >= 0 && target <= 4) {
                        int effective = button == 0 ? TerminalSolver.INSTANCE.rubixButton(current, target) : button;
                        if (button == 0 && effective == 1) {
                            ci.cancel();
                            mc.gameMode.handleContainerInput(self.getMenu().containerId, slot.index, 1, ContainerInput.PICKUP, mc.player);
                            int nextColor = TerminalSolver.INSTANCE.rubixAdvance(current, 1);
                            AutoTerminal.INSTANCE.setRubixPredicted(slot.index, nextColor);
                            Item nextItem = switch (nextColor) {
                                case 0 -> Items.ORANGE_STAINED_GLASS_PANE;
                                case 1 -> Items.YELLOW_STAINED_GLASS_PANE;
                                case 2 -> Items.LIME_STAINED_GLASS_PANE;
                                case 3 -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
                                case 4 -> Items.RED_STAINED_GLASS_PANE;
                                default -> null;
                            };
                            if (nextItem != null) slot.set(new ItemStack(nextItem));
                            if (!self.getMenu().getCarried().isEmpty()) self.getMenu().setCarried(ItemStack.EMPTY);
                            if (!mc.player.containerMenu.getCarried().isEmpty()) mc.player.containerMenu.setCarried(ItemStack.EMPTY);
                            return;
                        } else if (effective >= 0 && effective <= 1) {
                            int nextColor = TerminalSolver.INSTANCE.rubixAdvance(current, effective);
                            AutoTerminal.INSTANCE.setRubixPredicted(slot.index, nextColor);
                        }
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            if (!self.getMenu().getCarried().isEmpty()) {
                self.getMenu().setCarried(ItemStack.EMPTY);
            }
            if (mc.player != null && !mc.player.containerMenu.getCarried().isEmpty()) {
                mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
    }

    @Inject(
        method = "extractTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_suppressTooltip(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        CallbackInfo ci
    ) {
        if (TerminalCursor.INSTANCE.ownsCursor()) ci.cancel();
    }

    @Inject(
        method = "extractSlotHighlightBack(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_suppressHighlightBack(
        GuiGraphicsExtractor graphics,
        CallbackInfo ci
    ) {
        if (TerminalCursor.INSTANCE.ownsCursor()) ci.cancel();
    }

    @Inject(
        method = "extractSlotHighlightFront(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void asthoonlite_suppressHighlightFront(
        GuiGraphicsExtractor graphics,
        CallbackInfo ci
    ) {
        if (TerminalCursor.INSTANCE.ownsCursor()) ci.cancel();
    }

    @Inject(
        method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
        at = @At("TAIL")
    )
    private void asthoonlite_onSlotRendered(
        GuiGraphicsExtractor graphics,
        Slot slot,
        int mouseX,
        int mouseY,
        CallbackInfo ci
    ) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (self.getTitle().getString().toLowerCase().contains("stash")) {
            InventoryAutoClicker.INSTANCE.clearSkymyceWorthlessItems();
        }
        if (!Config.INSTANCE.getPetMenuHighlightEnabled()) return;
        if (!self.getTitle().getString().startsWith("Pets")) return;
        if (QuietMode.INSTANCE.suppressing()) return;

        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !PetTracker.INSTANCE.isPetItem(stack)) return;
        if (!PetTracker.INSTANCE.hasDesawnLore(stack)) return;

        int sx = slot.x;
        int sy = slot.y;

        graphics.fill(sx, sy, sx + 16, sy + 16, HIGHLIGHT_COLOR);
        graphics.fill(sx, sy, sx + 16, sy + 1, BORDER_COLOR);
        graphics.fill(sx, sy + 15, sx + 16, sy + 16, BORDER_COLOR);
        graphics.fill(sx, sy, sx + 1, sy + 16, BORDER_COLOR);
        graphics.fill(sx + 15, sy, sx + 16, sy + 16, BORDER_COLOR);
    }

    @Inject(
        method = "extractSlots(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
        at = @At("TAIL")
    )
    private void asthoonlite_terminalGrid(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        CallbackInfo ci
    ) {
        if (!Config.INSTANCE.getTerminalSolverEnabled()) return;
        if (QuietMode.INSTANCE.suppressing()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        String title = self.getTitle().getString();
        TerminalSolver.Kind kind = TerminalSolver.INSTANCE.kindOf(title);
        if (kind == null) return;

        List<Slot> slots = self.getMenu().slots;
        List<ItemStack> all = new ArrayList<>(slots.size());
        for (Slot s : slots) all.add(s.getItem());

        Integer[] paints = new Integer[kind.getSlotCount()];
        boolean anyPainted = false;
        for (int i = 0; i < kind.getSlotCount(); i++) {
            if (i >= all.size()) continue;
            ItemStack stack = all.get(i);
            Integer color = TerminalSolver.INSTANCE.colorFor(
                title, i, stack, all, AutoTerminal.rubixTargetOrNull()
            );
            if (color != null) {
                paints[i] = color;
                anyPainted = true;
            }
        }
        if (!anyPainted) return;

        int termCount = Math.min(slots.size(), kind.getSlotCount());
        List<Slot> termSlots = slots.subList(0, termCount);
        if (termSlots.isEmpty()) return;

        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Slot s : termSlots) {
            if (s.x < left) left = s.x;
            if (s.y < top) top = s.y;
            if (s.x > right) right = s.x;
            if (s.y > bottom) bottom = s.y;
        }

        RoundedRectKt.fillRoundedRect(
            graphics,
            left - PANEL_PAD, top - PANEL_PAD,
            right + 16 + PANEL_PAD, bottom + 16 + PANEL_PAD,
            PANEL_COLOR, PANEL_RADIUS
        );

        Font font = Minecraft.getInstance().font;
        Integer marker = (kind == TerminalSolver.Kind.ORDER || kind == TerminalSolver.Kind.MELODY)
            ? nextSlotFor(title, all) : null;
        for (int i = 0; i < termSlots.size(); i++) {
            Slot s = termSlots.get(i);
            int x = s.x + TILE_INSET;
            int y = s.y + TILE_INSET;

            if (marker != null && i == marker) {
                RoundedRectKt.fillRoundedRect(graphics, s.x, s.y, s.x + 16, s.y + 16, nextClickColor(), TILE_RADIUS + 1);
            }

            int tileColor = paints[i] != null ? paints[i] : NEUTRAL_COLOR;
            RoundedRectKt.fillRoundedRect(graphics, x, y, x + TILE_SIZE, y + TILE_SIZE, tileColor, TILE_RADIUS);

            ItemStack itemStack = i < all.size() ? all.get(i) : null;
            String label = TerminalSolver.INSTANCE.labelFor(i, itemStack, kind);
            if (label != null) {
                graphics.text(
                    font, label,
                    s.x + (16 - font.width(label)) / 2,
                    s.y + (16 - font.lineHeight) / 2,
                    LABEL_COLOR
                );
            }
        }
    }

    private static Integer nextSlotFor(String title, List<ItemStack> all) {
        long now = System.currentTimeMillis();
        if (now - markerAt < 50L && markerTitle.equals(title)) return markerNext;
        markerTitle = title;
        markerAt = now;
        markerNext = TerminalSolver.INSTANCE.nextClickSlot(
            title,
            all,
            AutoTerminal.unsettledSlots(now),
            AutoTerminal.rubixTargetOrNull(),
            AutoTerminal.lastClickedSlot(),
            Config.INSTANCE.getAutoTerminalClickOrder()
        );
        return markerNext;
    }

    private static int nextClickColor() {
        double phase = (System.currentTimeMillis() % 800L) / 800.0;
        double pulse = (Math.sin(phase * 2 * Math.PI) * 0.5 + 0.5);
        int alpha = Math.clamp((int) (160 + 95 * pulse), 0, 255);
        return (alpha << 24) | 0x0000E676;
    }
}
