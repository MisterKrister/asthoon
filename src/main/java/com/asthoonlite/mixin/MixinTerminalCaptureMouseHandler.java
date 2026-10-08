package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.TerminalCapture;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raw callback records retain releases and movements between rendered frames. */
@Mixin(MouseHandler.class)
public class MixinTerminalCaptureMouseHandler {
    @Inject(method = "onMove(JDD)V", at = @At("HEAD"))
    private void asthoonlite$captureMove(long window, double x, double y, CallbackInfo ci) {
        TerminalCapture.rawMouseMove(window, x, y, System.nanoTime());
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void asthoonlite$captureButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        TerminalCapture.rawMouseButton(window, button.button(), button.modifiers(), action, System.nanoTime());
    }

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void asthoonlite$lockCursorDuringRoute(double delta, CallbackInfo ci) {
        if (com.asthoonlite.pathfinding.PathExecutor.INSTANCE.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"))
    private void asthoonlite$captureScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        TerminalCapture.rawMouseScroll(window, horizontal, vertical, System.nanoTime());
    }
}
