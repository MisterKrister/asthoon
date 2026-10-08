package com.asthoonlite.mixin;

import com.asthoonlite.pathfinding.PathExecutor;
import com.asthoonlite.render.EtherwarpOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Inject(method = "close()V", at = @At("RETURN"))
    private void asthoonlite_onClose(CallbackInfo ci) {
        EtherwarpOverlay.INSTANCE.close();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void asthoonlite_onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        PathExecutor.INSTANCE.onRenderFrame(deltaTracker);
    }
}
