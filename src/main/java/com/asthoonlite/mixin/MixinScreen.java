package com.asthoonlite.mixin;

import com.asthoonlite.config.Config;
import com.asthoonlite.dungeon.AutoTerminal;
import com.asthoonlite.dungeon.TermGui;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Screen.class, priority = 2000)
public abstract class MixinScreen {
    // Verified with javap: every screen, including server ChestMenu and local practice,
    // enters here before its background and contents. Container HEAD hooks can be
    // bypassed by another mod cancelling this outer method (Noamm invwalk does so).
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), cancellable = true)
    private void asthoonlite_terminalFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                         float delta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (!(screen instanceof AbstractContainerScreen<?> container) || !TermGui.INSTANCE.active(container)) return;
        graphics.nextStratum();
        graphics.fill(0, 0, screen.width, screen.height, 0x88000000);
        ScreenEvents.afterBackground(screen).invoker().afterBackground(screen, graphics, mouseX, mouseY, delta);
        graphics.nextStratum();
        TermGui.INSTANCE.render(container, graphics, mouseX, mouseY);
        // Fabric GameRendererMixin wraps this entry point and retains before/afterExtract,
        // including the pointer, progress HUD and input clock. Do not dispatch them again.
        graphics.extractDeferredElements(mouseX, mouseY, delta);
        ci.cancel();
    }
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
