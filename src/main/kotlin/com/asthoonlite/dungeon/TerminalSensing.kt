package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.TerminalSolver.Kind
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal Sensing component based on Noamm and RSM (RoyalSkyblockMod).
 *
 * Senses container opening and slot updates directly via network packets so the
 * auto-terminal clicker and solver never act on partially-loaded containers.
 * This eliminates the root cause of "wrong click" chat warnings across all terminals.
 *
 * For Melody, strictly tracks the active row, moving lime pane column, and magenta
 * target marker column from incoming packets to prevent premature clicks and back-and-forth oscillation.
 */
object TerminalSensing {
    @Volatile var inTerminal: Boolean = false
    @Volatile var isLoaded: Boolean = false
    @Volatile var containerId: Int = -1
    @Volatile var currentKind: Kind? = null
    @Volatile var currentTitle: String = ""
    @Volatile var slotCount: Int = 0
    @Volatile var openedAt: Long = 0L

    private val packetSlots = ConcurrentHashMap<Int, ItemStack>()

    // Melody sensing state
    @Volatile var melodyButtonRow: Int? = null     // 0, 1, 2
    @Volatile var melodyCurrentCol: Int? = null     // 0..4
    @Volatile var melodyCorrectCol: Int? = null     // 0..4
    @Volatile var melodyLastLimeSlot: Int = -1

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    fun getItems(): Map<Int, ItemStack> = packetSlots

    fun onOpenScreen(packet: ClientboundOpenScreenPacket) {
        val title = TerminalSolver.cleanTitle(packet.title.string)
        val kind = TerminalSolver.kindOf(title)
        if (kind == null) {
            reset()
            return
        }
        val slots = when (packet.type) {
            MenuType.GENERIC_9x1 -> 9
            MenuType.GENERIC_9x2 -> 18
            MenuType.GENERIC_9x3 -> 27
            MenuType.GENERIC_9x4 -> 36
            MenuType.GENERIC_9x5 -> 45
            MenuType.GENERIC_9x6 -> 54
            else -> kind.slotCount
        }
        inTerminal = true
        isLoaded = false
        containerId = packet.containerId
        currentKind = kind
        currentTitle = title
        slotCount = slots
        openedAt = 0L
        packetSlots.clear()
        melodyButtonRow = null
        melodyCurrentCol = null
        melodyCorrectCol = null
        melodyLastLimeSlot = -1
    }

    fun onSetContent(packet: ClientboundContainerSetContentPacket) {
        if (!inTerminal || packet.containerId != containerId) return
        val count = slotCount.takeIf { it > 0 } ?: return
        for (i in packet.items.indices) {
            if (i in 0 until count) {
                packetSlots[i] = packet.items[i]
            }
        }
        if (packet.items.size >= count || packetSlots.size >= count) {
            markLoaded()
        }
        if (currentKind == Kind.MELODY) {
            updateMelodyMarkers()
        }
    }

    fun onSetSlot(packet: ClientboundContainerSetSlotPacket) {
        if (!inTerminal || packet.containerId != containerId) return
        val count = slotCount.takeIf { it > 0 } ?: return
        if (packet.slot !in 0 until count) return
        packetSlots[packet.slot] = packet.item

        if (!isLoaded) {
            if (packet.slot == count - 1 || packetSlots.size >= count) {
                markLoaded()
            }
        }

        if (currentKind == Kind.MELODY) {
            if (packet.item.`is`(Items.LIME_STAINED_GLASS_PANE) && packet.slot in 9..35) {
                val row = packet.slot / 9 - 1
                val col = (packet.slot % 9) - 1
                if (row in 0..2 && col in 0..4) {
                    melodyButtonRow = row
                    melodyCurrentCol = col
                    melodyLastLimeSlot = packet.slot
                    updateMelodyMarkers()
                    AutoTerminal.onMelodyLimeMoved(row, col)
                }
            } else if (packet.item.`is`(Items.MAGENTA_STAINED_GLASS_PANE)) {
                updateMelodyMarkers()
            }
        }
    }

    private fun markLoaded() {
        if (!isLoaded) {
            isLoaded = true
            openedAt = System.currentTimeMillis()
        }
    }

    private fun updateMelodyMarkers() {
        if (currentKind != Kind.MELODY) return
        var foundMagentaCol: Int? = null
        var foundLimeSlot: Int = -1
        for ((slot, item) in packetSlots) {
            if (item.`is`(Items.MAGENTA_STAINED_GLASS_PANE)) {
                foundMagentaCol = (slot % 9) - 1
            } else if (item.`is`(Items.LIME_STAINED_GLASS_PANE) && slot in 9..35) {
                foundLimeSlot = slot
            }
        }
        if (foundMagentaCol != null) {
            melodyCorrectCol = foundMagentaCol
        }
        if (foundLimeSlot != -1) {
            val row = foundLimeSlot / 9 - 1
            val col = (foundLimeSlot % 9) - 1
            if (row in 0..2 && col in 0..4) {
                if (melodyButtonRow != row || melodyCurrentCol != col) {
                    melodyButtonRow = row
                    melodyCurrentCol = col
                    melodyLastLimeSlot = foundLimeSlot
                    AutoTerminal.onMelodyLimeMoved(row, col)
                }
            }
        }
    }

    fun onClose(id: Int) {
        if (id == containerId || id == -1) {
            reset()
        }
    }

    fun reset() {
        inTerminal = false
        isLoaded = false
        containerId = -1
        currentKind = null
        currentTitle = ""
        slotCount = 0
        openedAt = 0L
        packetSlots.clear()
        melodyButtonRow = null
        melodyCurrentCol = null
        melodyCorrectCol = null
        melodyLastLimeSlot = -1
    }
}
