package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.TerminalCapture;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Numeric key events only; text entry and clipboard paths are never recorded. */
@Mixin(KeyboardHandler.class)
public class MixinTerminalCaptureKeyboardHandler {
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
    private void asthoonlite$captureKey(long window, int action, KeyEvent key, CallbackInfo ci) {
        TerminalCapture.rawKey(window, key.key(), key.scancode(), key.modifiers(), action, System.nanoTime());
    }
}
