package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import java.util.LinkedHashMap

/**
 * M7 dragon phase visuals: fixed dragon hitboxes, spawn/elapsed timers,
 * priority, health, and local arrow/ice-spray reporting.
 *
 * The fixed boxes/priority order mirror the consolidated Noamm/Devonian
 * behavior without copying their rendering/UI dependencies.
 */
object DragonPhase : HudElement {
    enum class Dragon(
        val display: String,
        val box: AABB,
        val color: Int
    ) {
        RED("Red", AABB(14.5, 13.0, 45.5, 39.5, 28.0, 70.5), 0xFFFF5555.toInt()),
        ORANGE("Orange", AABB(72.0, 8.0, 47.0, 102.0, 28.0, 77.0), 0xFFFFAA00.toInt()),
        GREEN("Green", AABB(7.0, 8.0, 80.0, 37.0, 28.0, 110.0), 0xFF55FF55.toInt()),
        BLUE("Blue", AABB(71.5, 16.0, 82.5, 96.5, 26.0, 107.5), 0xFF55FFFF.toInt()),
        PURPLE("Purple", AABB(45.5, 13.0, 113.5, 68.5, 23.0, 136.5), 0xFFAA55FF.toInt())
    }

    private data class State(
        var entityId: Int = -1,
        var spawnedAt: Long = 0L,
        var health: Float = 1_000_000_000f,
        var arrows: Int = 0,
        var sprayed: Boolean = false,
        var lastReported: Boolean = false
    )

    private val states = Dragon.entries.associateWith { State() }.toMutableMap()
    private var phase = false
    private var priority = Dragon.RED
    private var phaseStart = 0L

    // Conservative Noamm/Devonian-compatible default ordering.
    private val safePriority = listOf(Dragon.RED, Dragon.ORANGE, Dragon.BLUE, Dragon.PURPLE, Dragon.GREEN)
    private val powerPriority = listOf(Dragon.ORANGE, Dragon.GREEN, Dragon.RED, Dragon.BLUE, Dragon.PURPLE)

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "dragon_phase"), this
        )
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) {
                val msg = ChatFormatting.stripFormatting(text.string) ?: ""
                if (msg == "[BOSS] Necron: I'M AFRAID, YOUR JOURNEY ENDS NOW.") {
                    phase = true
                    phaseStart = System.currentTimeMillis()
                    states.values.forEach { it.entityId = -1; it.spawnedAt = 0; it.health = 1_000_000_000f; it.arrows = 0; it.sprayed = false }
                    priority = safePriority.first()
                }
            }
            true
        }
        ClientTickEvents.END_CLIENT_TICK.register { scanEntities() }
        LevelRenderEvents.END_EXTRACTION.register { renderWorld() }
    }

    private fun scanEntities() {
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        val level = Minecraft.getInstance().level ?: return

        for (dragon in Dragon.entries) {
            val s = states[dragon] ?: continue
            if (s.entityId != -1 && level.getEntity(s.entityId) == null && !s.lastReported) {
                s.lastReported = true
                if (s.arrows > 0 || s.sprayed) {
                    val spray = if (s.sprayed) "Ice Spray: yes" else "Ice Spray: no"
                    showLocal("${dragon.display}: ${s.arrows} arrows | $spray", false)
                }
                s.entityId = -1
                s.health = 0f
            }
        }

        for (dragonEntity in level.entitiesForRendering().filterIsInstance<EnderDragon>()) {
            val pos = dragonEntity.position()
            val dragon = Dragon.entries.minByOrNull { ((it.box.minX + it.box.maxX) / 2.0 - pos.x) * ((it.box.minX + it.box.maxX) / 2.0 - pos.x) + ((it.box.minZ + it.box.maxZ) / 2.0 - pos.z) * ((it.box.minZ + it.box.maxZ) / 2.0 - pos.z) } ?: continue
            val state = states[dragon] ?: continue
            if (state.entityId != dragonEntity.id) {
                state.entityId = dragonEntity.id
                state.spawnedAt = System.currentTimeMillis()
                state.arrows = 0
                state.sprayed = false
                state.lastReported = false
                if (priority == dragon || !hasLiveDragon()) priority = choosePriority()
            }
            state.health = dragonEntity.health
            if (state.health <= 0f && !state.lastReported) {
                state.lastReported = true
                val spray = if (state.sprayed) "Ice Spray: yes" else "Ice Spray: no"
                showLocal("${dragon.display}: ${state.arrows} arrows | $spray", false)
            }
        }
    }

    private fun hasLiveDragon(): Boolean =
        states.values.any { it.entityId != -1 && it.health > 0f }

    private fun choosePriority(): Dragon {
        val order = if (Config.dragonPowerPriority) powerPriority else safePriority
        return order.firstOrNull { states[it]?.entityId != -1 && (states[it]?.health ?: 0f) > 0f } ?: order.first()
    }

    private fun renderWorld() {
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        for (dragon in Dragon.entries) {
            val state = states[dragon] ?: continue
            val color = dragon.color
            val r = ((color shr 16) and 255) / 255f
            val g = ((color shr 8) and 255) / 255f
            val b = (color and 255) / 255f
            WorldBoxRenderer.queueOutline(
                dragon.box.minX, dragon.box.minY, dragon.box.minZ,
                dragon.box.maxX, dragon.box.maxY, dragon.box.maxZ,
                r, g, b, if (dragon == priority) 1f else 0.8f
            )
            if (state.entityId != -1 && state.health > 0f) {
                WorldBoxRenderer.queueFilled(
                    dragon.box.minX, dragon.box.minY, dragon.box.minZ,
                    dragon.box.maxX, dragon.box.maxY, dragon.box.maxZ,
                    r, g, b, if (dragon == priority) 0.12f else 0.05f
                )
            }
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        val mc = Minecraft.getInstance()
        val lines = mutableListOf<String>()
        lines += "Dragon Priority: ${priority.display}"
        val phaseTime = (System.currentTimeMillis() - phaseStart) / 1000.0
        lines += String.format("Phase %.2fs", phaseTime)

        for (dragon in Dragon.entries) {
            val state = states[dragon] ?: continue
            if (state.entityId == -1) continue
            val ageTicks = ((System.currentTimeMillis() - state.spawnedAt) / 50L).toInt()
            val spawnTimer = (100 - ageTicks).coerceAtLeast(0)
            val hp = formatHealth(state.health)
            val flags = buildString {
                if (state.sprayed) append(" IceSpray")
                if (state.arrows > 0) append(" ${state.arrows}a")
            }
            lines += "${dragon.display}: ${String.format("%.2fs", spawnTimer / 20.0)} $hp$flags"
        }

        var y = Config.dragonHudY
        for (line in lines.take(7)) {
            context.text(mc.font, line, Config.dragonHudX, y, 0xFFE0EEFF.toInt())
            y += mc.font.lineHeight + 2
        }
    }

    fun onDragonPacket(packet: ClientboundAddEntityPacket) {
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        if (packet.type != net.minecraft.world.entity.EntityType.ENDER_DRAGON) return
        val entityPos = packet.x to packet.z
        val dragon = Dragon.entries.minByOrNull {
            val cx = (it.box.minX + it.box.maxX) / 2.0
            val cz = (it.box.minZ + it.box.maxZ) / 2.0
            val dx = cx - entityPos.first
            val dz = cz - entityPos.second
            dx * dx + dz * dz
        } ?: return
        val state = states[dragon] ?: return
        state.entityId = packet.id
        state.spawnedAt = System.currentTimeMillis()
        state.health = 1_000_000_000f
        priority = choosePriority()
    }

    fun onArrowHit() {
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        val d = priority
        val s = states[d] ?: return
        if (s.entityId == -1) return
        s.arrows++
    }

    fun onIceSpray(entityId: Int) {
        if (!Config.dragonPhaseEnabled || !DungeonContext.inDungeon || !phase) return
        val level = Minecraft.getInstance().level ?: return
        val sprayedEntity = level.getEntity(entityId) ?: return
        for ((dragon, s) in states) {
            if (s.entityId == -1 || s.health <= 0f) continue
            val dragonEntity = level.getEntity(s.entityId) ?: continue
            if (sprayedEntity.distanceToSqr(dragonEntity) <= 64.0) {
                s.sprayed = true
                showLocal("${dragon.display}: Ice Spray landed", true)
                return
            }
        }
    }

    private fun showLocal(message: String, spray: Boolean) {
        Minecraft.getInstance().player?.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("§b[AsthoonLite] §f$message")
        )
    }

    private fun formatHealth(health: Float): String {
        val color = when {
            health >= 750_000_000f -> "§a"
            health >= 500_000_000f -> "§e"
            health >= 250_000_000f -> "§6"
            else -> "§c"
        }
        val value = when {
            health >= 1_000_000_000f -> String.format("%.1fb", health / 1_000_000_000f)
            health >= 1_000_000f -> "${(health / 1_000_000f).toInt()}m"
            health >= 1_000f -> "${(health / 1_000f).toInt()}k"
            else -> health.toInt().toString()
        }
        return color + value
    }

    fun reset() {
        phase = false
        phaseStart = 0
        priority = safePriority.first()
        states.values.forEach {
            it.entityId = -1; it.spawnedAt = 0; it.health = 1_000_000_000f
            it.arrows = 0; it.sprayed = false; it.lastReported = false
        }
    }
}
