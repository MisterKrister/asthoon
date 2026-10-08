package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.F7Devices;
import com.asthoonlite.dungeon.SecretSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void asthoonlite$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (hitResult != null && F7Devices.INSTANCE.shouldBlockSimonClick(hitResult.getBlockPos())) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && hitResult != null) {
            BlockState state = mc.level.getBlockState(hitResult.getBlockPos());
            SecretSounds.INSTANCE.onSecretInteract(hitResult.getBlockPos(), state.getBlock());
        }
    }
}
