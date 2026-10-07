package com.asthoonlite.mixin

import com.asthoonlite.AsthoonLite
import com.asthoonlite.dungeon.DragonPhase
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.DungeonTimers
import com.asthoonlite.dungeon.StarMobESP
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.solvers.CampHelper
import com.asthoonlite.dungeon.solvers.TicTacToeSolver
import com.asthoonlite.dungeon.solvers.TeleportMazeSolver
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPacketListener::class)
abstract class MixinClientPacketListener {
    @Inject(method = ["handleSetTime"], at = [At("TAIL")])
    private fun asthoonlite_onSetTime(packet: ClientboundSetTimePacket, ci: CallbackInfo) {
        DungeonTimers.onServerTime(packet)
    }

    @Inject(method = ["handleAddEntity"], at = [At("TAIL")])
    private fun asthoonlite_onAddEntity(packet: ClientboundAddEntityPacket, ci: CallbackInfo) {
        DragonPhase.onDragonPacket(packet)
        StarMobESP.onAddEntity(packet)
        TicTacToeSolver.onAddEntity(packet)
    }

    @Inject(method = ["handleMovePlayer"], at = [At("TAIL")])
    private fun asthoonlite_onMovePlayer(packet: ClientboundPlayerPositionPacket, ci: CallbackInfo) {
        TeleportMazeSolver.onPlayerPositionPacket(packet)
    }

    @Inject(method = ["handleSoundEvent"], at = [At("TAIL")])
    private fun asthoonlite_onSound(packet: ClientboundSoundPacket, ci: CallbackInfo) {
        if (packet.sound.value() == net.minecraft.sounds.SoundEvents.ARROW_HIT_PLAYER) {
            DragonPhase.onArrowHit()
        }
        if (packet.sound.value() == net.minecraft.sounds.SoundEvents.BAT_DEATH) {
            com.asthoonlite.dungeon.SecretSounds.onBatDeath(packet.x, packet.y, packet.z)
        }
    }

    @Inject(method = ["handleSetEquipment"], at = [At("TAIL")])
    private fun asthoonlite_onEquipment(packet: ClientboundSetEquipmentPacket, ci: CallbackInfo) {
        if (packet.slots.any { it.second.item == net.minecraft.world.item.Items.PACKED_ICE }) {
            DragonPhase.onIceSpray(packet.entity)
        }
        CampHelper.onEquipmentPacket(packet)
    }

    @Inject(method = ["handleMoveEntity"], at = [At("TAIL")])
    private fun asthoonlite_onMoveEntity(packet: ClientboundMoveEntityPacket, ci: CallbackInfo) {
        val entityId = (packet as? ClientboundMoveEntityPacketAccessor)?.entityId ?: return
        CampHelper.onMovePacket(packet, entityId)
    }

    @Inject(method = ["handleMapItemData"], at = [At("TAIL")])
    private fun asthoonlite_onMapItemData(packet: ClientboundMapItemDataPacket, ci: CallbackInfo) {
        DungeonMapScanner.onMapPacket(packet)
    }

    @Inject(method = ["handleSetEntityData"], at = [At("TAIL")])
    private fun asthoonlite_onSetEntityData(packet: ClientboundSetEntityDataPacket, ci: CallbackInfo) {
        StarMobESP.onEntityData(packet)
    }

    @Inject(method = ["handlePlayerInfoUpdate"], at = [At("TAIL")])
    private fun asthoonlite_onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket, ci: CallbackInfo) {
        DungeonContext.onPlayerInfoUpdate(packet)
        StarMobESP.onPlayerInfoUpdate(packet)
    }

    @Inject(method = ["handleSetPlayerTeamPacket"], at = [At("TAIL")])
    private fun asthoonlite_onSetPlayerTeam(packet: ClientboundSetPlayerTeamPacket, ci: CallbackInfo) {
        DungeonContext.onSetPlayerTeam(packet)
    }

    @Inject(method = ["handleOpenScreen"], at = [At("HEAD")], cancellable = true)
    private fun asthoonlite_onOpenScreen(packet: net.minecraft.network.protocol.game.ClientboundOpenScreenPacket, ci: CallbackInfo) {
        if (!com.asthoonlite.config.Config.autoCloseSecretChest || !com.asthoonlite.dungeon.DungeonContext.inDungeon || com.asthoonlite.dungeon.DungeonContext.inBoss) return
        val type = packet.type
        if (type != net.minecraft.world.inventory.MenuType.GENERIC_9x3 && type != net.minecraft.world.inventory.MenuType.GENERIC_9x6) return
        val title = net.minecraft.ChatFormatting.stripFormatting(packet.title.string) ?: ""
        if (title == "Chest" || title == "Large Chest") {
            val mc = net.minecraft.client.Minecraft.getInstance()
            mc.connection?.send(net.minecraft.network.protocol.game.ServerboundContainerClosePacket(packet.containerId))
            ci.cancel()
        }
    }

    @Inject(method = ["handleOpenScreen"], at = [At("TAIL")])
    private fun asthoonlite_onOpenScreenTerminal(packet: net.minecraft.network.protocol.game.ClientboundOpenScreenPacket, ci: CallbackInfo) {
        com.asthoonlite.dungeon.TerminalSensing.onOpenScreen(packet)
    }

    @Inject(method = ["handleContainerContent"], at = [At("TAIL")])
    private fun asthoonlite_onContainerContent(packet: net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket, ci: CallbackInfo) {
        com.asthoonlite.dungeon.TerminalSensing.onSetContent(packet)
    }

    @Inject(method = ["handleContainerSetSlot"], at = [At("TAIL")])
    private fun asthoonlite_onContainerSetSlot(packet: net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket, ci: CallbackInfo) {
        com.asthoonlite.dungeon.TerminalSensing.onSetSlot(packet)
    }

    @Inject(method = ["handleContainerClose"], at = [At("TAIL")])
    private fun asthoonlite_onContainerClose(packet: net.minecraft.network.protocol.game.ClientboundContainerClosePacket, ci: CallbackInfo) {
        com.asthoonlite.dungeon.TerminalSensing.onClose(packet.containerId)
    }

    @Inject(method = ["handleBlockUpdate"], at = [At("TAIL")])
    private fun asthoonlite_onBlockUpdate(packet: ClientboundBlockUpdatePacket, ci: CallbackInfo) {
        com.asthoonlite.dungeon.F7Devices.onBlockUpdate(packet.pos, packet.blockState)
    }

    @Inject(method = ["handleChunkBlocksUpdate"], at = [At("TAIL")])
    private fun asthoonlite_onChunkBlocksUpdate(packet: ClientboundSectionBlocksUpdatePacket, ci: CallbackInfo) {
        packet.runUpdates { pos, state ->
            com.asthoonlite.dungeon.F7Devices.onBlockUpdate(pos, state)
        }
    }
}


