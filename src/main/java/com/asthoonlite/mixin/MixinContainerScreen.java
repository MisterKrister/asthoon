package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.TermGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContainerScreen.class)
public abstract class MixinContainerScreen {
    // Verified with javap: this runs separately from extractRenderState.
    @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), cancellable = true)
    private void asthoonlite_terminalBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float delta, CallbackInfo ci) {
        ContainerScreen screen = (ContainerScreen) (Object) this;
        if (!TermGui.INSTANCE.active(screen)) return;
        graphics.fill(0, 0, screen.width, screen.height, 0x88000000);
        ci.cancel();
    }
}
