package com.asthoonlite.mixin;

import com.asthoonlite.config.Config;
import com.asthoonlite.dungeon.DragonPhase;
import com.asthoonlite.dungeon.DungeonContext;
import com.asthoonlite.dungeon.DungeonTimers;
import com.asthoonlite.dungeon.F7Devices;
import com.asthoonlite.dungeon.SecretSounds;
import com.asthoonlite.dungeon.StarMobESP;
import com.asthoonlite.dungeon.TerminalSensing;
import com.asthoonlite.dungeon.map.DungeonMapScanner;
import com.asthoonlite.dungeon.solvers.CampHelper;
import com.asthoonlite.dungeon.solvers.TicTacToeSolver;
import com.asthoonlite.dungeon.solvers.TeleportMazeSolver;
import com.asthoonlite.pathfinding.GoldorRouteShortcut;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handleSetTime(Lnet/minecraft/network/protocol/game/ClientboundSetTimePacket;)V", at = @At("TAIL"))
    private void asthoonlite_onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        DungeonTimers.INSTANCE.onServerTime(packet);
    }

    @Inject(method = "handleAddEntity(Lnet/minecraft/network/protocol/game/ClientboundAddEntityPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        DragonPhase.INSTANCE.onDragonPacket(packet);
        StarMobESP.INSTANCE.onAddEntity(packet);
        TicTacToeSolver.INSTANCE.onAddEntity(packet);
    }

    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        TeleportMazeSolver.INSTANCE.onPlayerPositionPacket(packet);
        GoldorRouteShortcut.INSTANCE.onPlayerPositionPacket();
    }

    @Inject(method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (packet.getSound().value() == SoundEvents.ARROW_HIT_PLAYER) {
            DragonPhase.INSTANCE.onArrowHit();
        }
        if (packet.getSound().value() == SoundEvents.BAT_DEATH) {
            SecretSounds.INSTANCE.onBatDeath(packet.getX(), packet.getY(), packet.getZ());
        }
    }

    @Inject(method = "handleSetEquipment(Lnet/minecraft/network/protocol/game/ClientboundSetEquipmentPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        for (var slot : packet.getSlots()) {
            if (slot.getSecond().getItem() == Items.PACKED_ICE) {
                DragonPhase.INSTANCE.onIceSpray(packet.getEntity());
                break;
            }
        }
        CampHelper.INSTANCE.onEquipmentPacket(packet);
    }

    @Inject(method = "handleMoveEntity(Lnet/minecraft/network/protocol/game/ClientboundMoveEntityPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        Object obj = packet;
        if (obj instanceof ClientboundMoveEntityPacketAccessor accessor) {
            CampHelper.INSTANCE.onMovePacket(packet, accessor.getEntityId());
        }
    }

    @Inject(method = "handleMapItemData(Lnet/minecraft/network/protocol/game/ClientboundMapItemDataPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onMapItemData(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        DungeonMapScanner.INSTANCE.onMapPacket(packet);
    }

    @Inject(method = "handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        StarMobESP.INSTANCE.onEntityData(packet);
    }

    @Inject(method = "handlePlayerInfoUpdate(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;)V", at = @At("TAIL"))
    private void asthoonlite_onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        DungeonContext.INSTANCE.onPlayerInfoUpdate(packet);
        StarMobESP.INSTANCE.onPlayerInfoUpdate(packet);
    }

    @Inject(method = "handleSetPlayerTeamPacket(Lnet/minecraft/network/protocol/game/ClientboundSetPlayerTeamPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onSetPlayerTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
        DungeonContext.INSTANCE.onSetPlayerTeam(packet);
    }

    @Inject(method = "handleOpenScreen(Lnet/minecraft/network/protocol/game/ClientboundOpenScreenPacket;)V", at = @At("HEAD"), cancellable = true)
    private void asthoonlite_onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (!Config.INSTANCE.getAutoCloseSecretChest() || !DungeonContext.INSTANCE.getInDungeon() || DungeonContext.INSTANCE.getInBoss()) return;
        MenuType<?> type = packet.getType();
        if (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6) return;
        String rawTitle = packet.getTitle().getString();
        String title = ChatFormatting.stripFormatting(rawTitle);
        if (title == null) title = "";
        if (title.equals("Chest") || title.equals("Large Chest")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundContainerClosePacket(packet.getContainerId()));
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleOpenScreen(Lnet/minecraft/network/protocol/game/ClientboundOpenScreenPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onOpenScreenTerminal(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        TerminalSensing.INSTANCE.onOpenScreen(packet);
    }

    @Inject(method = "handleContainerContent(Lnet/minecraft/network/protocol/game/ClientboundContainerSetContentPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        TerminalSensing.INSTANCE.onSetContent(packet);
    }

    @Inject(method = "handleContainerSetSlot(Lnet/minecraft/network/protocol/game/ClientboundContainerSetSlotPacket;)V", at = @At("TAIL"))
    private void asthoonlite_onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        TerminalSensing.INSTANCE.onSetSlot(packet);
    }

    @Inject(method = "handleContainerClose(Lnet/minecraft/network/protocol/game/ClientboundContainerClosePacket;)V", at = @At("TAIL"))
    private void asthoonlite_onContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        TerminalSensing.INSTANCE.onClose(packet.getContainerId());
    }

    @Inject(method = "handleBlockUpdate(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V", at = @At("TAIL"))
    private void asthoonlite_onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        F7Devices.INSTANCE.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V", at = @At("TAIL"))
    private void asthoonlite_onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> F7Devices.INSTANCE.onBlockUpdate(pos, state));
    }
}
