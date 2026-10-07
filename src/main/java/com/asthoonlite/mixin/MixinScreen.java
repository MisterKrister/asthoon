package com.asthoonlite.mixin;

import com.asthoonlite.config.Config;
import com.asthoonlite.dungeon.AutoTerminal;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class MixinScreen {
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"))
    private void asthoonlite_terminalEscape(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.INSTANCE.getAutoTerminalEnabled() || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        Object self = this;
        if (self instanceof AbstractContainerScreen<?> container) {
            String title = container.getTitle().getString();
            if (AutoTerminal.INSTANCE.isTerminalTitle(title)) {
                AutoTerminal.INSTANCE.onEscape();
            }
        }
    }
}
