package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.TerminalCapture;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Snapshot every applied slot update, including multiple updates inside one tick. */
@Mixin(AbstractContainerMenu.class)
public class MixinTerminalCaptureMenu {
    @Inject(method = "setItem(IILnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
    private void asthoonlite$captureSlot(int slot, int stateId, ItemStack stack, CallbackInfo ci) {
        TerminalCapture.menuChanged((AbstractContainerMenu) (Object) this, "set_item", slot, System.nanoTime());
    }

    @Inject(method = "initializeContents(ILjava/util/List;Lnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
    private void asthoonlite$captureContents(int stateId, List<ItemStack> stacks, ItemStack carried, CallbackInfo ci) {
        TerminalCapture.menuChanged((AbstractContainerMenu) (Object) this, "initialize_contents", -1, System.nanoTime());
    }
}
