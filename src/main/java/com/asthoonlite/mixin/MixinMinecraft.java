package com.asthoonlite.mixin;

import com.asthoonlite.config.Config;
import com.asthoonlite.funny.InventoryAutoClicker;
import com.asthoonlite.dungeon.ArrowAlignSolver;
import com.asthoonlite.dungeon.DungeonContext;
import com.asthoonlite.dungeon.F7Devices;
import com.asthoonlite.dungeon.StarMobESP;
import com.asthoonlite.dungeon.SecretHitboxes;
import com.asthoonlite.utils.InputCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow @Nullable public HitResult hitResult;
    @Shadow @Nullable public LocalPlayer player;
    @Shadow @Nullable public ClientLevel level;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void asthoonlite$guardRightClick(CallbackInfo ci) {
        InputCapture.INSTANCE.onRightClick();
        if (player == null || level == null || hitResult == null) return;
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult block = (BlockHitResult) hitResult;
            var state = level.getBlockState(block.getBlockPos());
            if (DungeonContext.INSTANCE.getInDungeon() &&
                    Config.INSTANCE.getSecretHitboxesEnabled() &&
                    ((state.getBlock() instanceof ButtonBlock && Config.INSTANCE.getButtonHitboxEnabled()) ||
                     (state.getBlock() instanceof LeverBlock && Config.INSTANCE.getLeverHitboxEnabled()))) {
                SecretHitboxes.INSTANCE.markPressed(block.getBlockPos());
            }
            if (F7Devices.INSTANCE.shouldBlockSimonClick(block.getBlockPos())) {
                ci.cancel();
                return;
            }
            F7Devices.INSTANCE.onSimonClick(block.getBlockPos());
        } else if (hitResult.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hitResult).getEntity();
            if (entity instanceof ItemFrame frame) {
                if (ArrowAlignSolver.INSTANCE.shouldBlockClick(frame)) {
                    ci.cancel();
                } else {
                    ArrowAlignSolver.INSTANCE.onAllowedClick(frame);
                }
            }
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void asthoonlite$guardLeftClick(CallbackInfoReturnable<Boolean> cir) {
        InputCapture.INSTANCE.onLeftClick();
        if (player == null || level == null || hitResult == null) return;
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult block = (BlockHitResult) hitResult;
            if (F7Devices.INSTANCE.shouldBlockSimonClick(block.getBlockPos())) {
                cir.cancel();
                return;
            }
            F7Devices.INSTANCE.onSimonClick(block.getBlockPos());
        } else if (hitResult.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hitResult).getEntity();
            if (entity instanceof ItemFrame frame) {
                if (ArrowAlignSolver.INSTANCE.shouldBlockClick(frame)) cir.cancel();
                else ArrowAlignSolver.INSTANCE.onAllowedClick(frame);
            }
        }
    }
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void asthoonlite_forceStarGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (StarMobESP.INSTANCE.shouldForceGlow(entity)) cir.setReturnValue(true);
    }

    @Inject(method = "hasControlDown", at = @At("HEAD"), cancellable = true)
    private void asthoonlite$stashControlDown(CallbackInfoReturnable<Boolean> cir) {
        if (InventoryAutoClicker.INSTANCE.isControlSimulated()) {
            cir.setReturnValue(true);
        }
    }

}
