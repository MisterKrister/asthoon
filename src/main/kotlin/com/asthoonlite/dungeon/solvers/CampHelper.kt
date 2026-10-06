package com.asthoonlite.dungeon.solvers

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.DungeonServerTick
import com.asthoonlite.dungeon.api.*
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.hud.AlertHud
import com.asthoonlite.render.WorldBoxRenderer
import com.asthoonlite.utils.MathUtils
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
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object CampHelper : HudElement {
    private var bloodComp: ComponentPosition? = null
    private val bloodStands = ConcurrentHashMap<Int, UndeadGuesser>()
    var watcherDialogFinished = false
    private var watcherKillAlertScheduled = false

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("asthoonlite", "camp_helper"),
            this
        )
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { render() }
        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay && DungeonContext.inDungeon) {
                val msg = ChatFormatting.stripFormatting(text.string) ?: ""
                if (msg == "[BOSS] The Watcher: Let's see how you can handle this.") {
                    watcherDialogFinished = true
                    if (!watcherKillAlertScheduled) {
                        watcherKillAlertScheduled = true
                        AlertHud.show("Watcher: Dialogue Started", 0xFFFF5555.toInt(), SoundEvents.NOTE_BLOCK_PLING, 1f)
                    }
                } else if (msg.contains("The Watcher: You have proven yourself", ignoreCase = true)) {
                    reset()
                }
            }
            true
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun addStand(id: Int) {
        bloodStands.putIfAbsent(id, UndeadGuesser(id))
    }

    fun onEquipmentPacket(packet: ClientboundSetEquipmentPacket) {
        if (!Config.bloodRoomSolverEnabled || !DungeonContext.inDungeon) return
        val level = Minecraft.getInstance().level ?: return
        val entity = level.getEntity(packet.entity) as? ArmorStand ?: return

        val headSlot = packet.slots.find { it.first == EquipmentSlot.HEAD } ?: return
        if (headSlot.second.item != Items.PLAYER_HEAD) return

        val pos = WorldPosition(entity.x.toInt(), entity.z.toInt()).toComponent().toRoom()
        if (bloodComp == null || pos == bloodComp) {
            addStand(packet.entity)
        }
    }

    fun onMovePacket(packet: ClientboundMoveEntityPacket, entityId: Int) {
        if (!Config.bloodRoomSolverEnabled || !DungeonContext.inDungeon) return
        bloodStands[entityId]?.update(
            packet.xa,
            packet.ya,
            packet.za,
            if (watcherDialogFinished) 40 else 80
        )
    }

    private fun tick() {
        if (!Config.bloodRoomSolverEnabled || !DungeonContext.inDungeon) {
            bloodStands.clear()
            bloodComp = null
            return
        }

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        if (bloodComp == null) {
            bloodComp = DungeonScanner.rooms.find { it?.type == RoomTypes.BLOOD }?.comps?.getOrNull(0)?.toComponent()
        }

        bloodStands.forEach { (id, v) ->
            if (v.ent?.isAlive != false) return@forEach
            v.ent = level.getEntity(id)
        }

        // Scan nearby armor stands wearing heads
        level.entitiesForRendering().forEach {
            if (it !is ArmorStand) return@forEach
            val head = it.getItemBySlot(EquipmentSlot.HEAD)
            if (head.isEmpty || head.item != Items.PLAYER_HEAD) return@forEach

            val pos = WorldPosition(it.x.toInt(), it.z.toInt()).toComponent().toRoom()
            if (bloodComp == null || pos == bloodComp) {
                addStand(it.id)
            }
        }
    }

    private fun render() {
        if (!Config.bloodRoomSolverEnabled || !DungeonContext.inDungeon) return
        val t = System.currentTimeMillis()
        val st = DungeonServerTick.current.toInt()

        bloodStands.forEach { (_, v) ->
            if (!v.shouldShow()) return@forEach

            val f = if (v.guessTimeOld == 0L) 1.0 else (t - v.guessTime).toDouble() / (v.guessTime - v.guessTimeOld).coerceAtLeast(1L)
            val ttl = v.ttl(st)

            if (ttl <= -3) return@forEach

            val x = MathUtils.lerp(f, v.guessXOld, v.guessX)
            val y = MathUtils.lerp(f, v.guessYOld, v.guessY) + 1.0
            val z = MathUtils.lerp(f, v.guessZOld, v.guessZ)

            val (r, g, b) = colorForNumberRgb(ttl, v.maxTTL)
            if (Config.campHelperPlaySound && ttl < Config.campHelperSoundThreshold && !v.triggeredSound) {
                v.triggeredSound = true
                Minecraft.getInstance().player?.playSound(SoundEvents.NOTE_BLOCK_COW_BELL.value())
            }

            // Wireframe landing prediction box
            WorldBoxRenderer.queueOutline(
                x - 0.5, y + 0.5, z - 0.5,
                x + 0.5, y + 1.5, z + 0.5,
                r, g, b, 1.0f,
                thickness = 0.03 * (Config.campHelperLineWidth / 3.0).coerceIn(0.5, 3.0),
                throughWalls = true
            )

            // Entity box & connecting line
            v.ent?.let { ent ->
                WorldBoxRenderer.queueOutline(
                    ent.x - 0.5, ent.y + 1.2, ent.z - 0.5,
                    ent.x + 0.5, ent.y + 2.2, ent.z + 0.5,
                    1.0f, 0.0f, 0.0f, 1.0f,
                    thickness = 0.02,
                    throughWalls = true
                )
            }
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.bloodRoomSolverEnabled || !Config.campHelperShowTimer || bloodStands.isEmpty()) return
        val mc = Minecraft.getInstance()
        val st = DungeonServerTick.current.toInt()

        var y = 170
        var index = 1
        for ((_, v) in bloodStands) {
            if (!v.shouldShow()) continue
            val ttl = v.ttl(st)
            if (ttl <= 0) continue
            val (r, g, b) = colorForNumberRgb(ttl, v.maxTTL)
            val argb = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
            val timeSec = (ttl * 0.05).coerceAtLeast(0.0)
            context.text(mc.font, "Blood $index: %.2fs".format(timeSec), 4, y, argb)
            y += mc.font.lineHeight + 2
            index++
            if (index > 6) break
        }
    }

    private fun colorForNumberRgb(num: Int, max: Int): Triple<Float, Float, Float> = when {
        num >= max * 0.75 -> Triple(1f, 0.2f, 0.2f)       // Red
        num >= max * 0.50 -> Triple(1f, 0.9f, 0.1f)       // Yellow
        num >= max * 0.25 -> Triple(0.2f, 1f, 0.2f)       // Green
        num >= 1          -> Triple(0.2f, 1f, 0.2f)       // Green
        else              -> Triple(0.2f, 0.8f, 1f)       // Aqua
    }

    fun reset() {
        bloodComp = null
        bloodStands.clear()
        watcherDialogFinished = false
        watcherKillAlertScheduled = false
    }
}

class UndeadGuesser(val id: Int) {
    var maxTTL = -1
    var ent: Entity? = null
    val knownT = mutableListOf<Double>()
    val knownX = mutableListOf<Double>()
    val knownY = mutableListOf<Double>()
    val knownZ = mutableListOf<Double>()

    var dead = false

    var guessX = 0.0
    var guessY = 0.0
    var guessZ = 0.0
    var guessXOld = 0.0
    var guessYOld = 0.0
    var guessZOld = 0.0
    var guessTime = 0L
    var guessTimeOld = 0L

    var startTick = -1
    var calcStart = false

    var hasSpawn = false
    var triggeredSound = false

    fun shouldShow() = hasSpawn && (ent?.isAlive == true) && startTick != -1 && !dead && guessTime > 0L

    fun ttl(st: Int) = if (startTick == -1 || maxTTL == -1) 0 else startTick + maxTTL - st

    fun update(dxRaw: Short, dyRaw: Short, dzRaw: Short, maxTtl: Int) {
        if (dead) return
        val tick = DungeonServerTick.current.toInt()

        val dx = dxRaw / 4096.0
        val dy = dyRaw / 4096.0
        val dz = dzRaw / 4096.0

        val level = Minecraft.getInstance().level ?: return
        if (ent == null) ent = level.getEntity(id) ?: return

        val x = ent!!.x
        val y = ent!!.y
        val z = ent!!.z

        if (!hasSpawn) {
            hasSpawn = true
            if (fract(x) != 0.5 || fract(z) != 0.5 || !spawnY.contains(y)) {
                calcStart = true
            }
        }

        if (dxRaw == 0.toShort() && dyRaw == 0.toShort() && dzRaw == 0.toShort()) return

        if (maxTTL == -1) maxTTL = maxTtl

        if (calcStart) {
            calcStart = false
            for (sy in spawnY) {
                if (dy == 0.0) continue
                val t = (y - sy) / dy
                if (t < 0.0) continue
                if (!eq(fract(x - t * dx), 0.5)) continue
                if (!eq(fract(z - t * dz), 0.5)) continue
                startTick = tick - (t * 3).roundToInt()
                break
            }
            if (startTick == -1) {
                dead = true
                return
            }
        }

        if (startTick == -1) startTick = tick - 3

        val t = ttl(tick)
        if (t < -3) {
            dead = true
            return
        }
        knownT.add(tick.toDouble())
        knownX.add(x + dx)
        knownY.add(y + dy)
        knownZ.add(z + dz)

        if (knownT.size < 2) return

        val linX = MathUtils.linReg(knownT, knownX) ?: return
        val linY = MathUtils.linReg(knownT, knownY) ?: return
        val linZ = MathUtils.linReg(knownT, knownZ) ?: return

        val x0 = linX.b * t + x + dx
        val y0 = linY.b * t + y + dy
        val z0 = linZ.b * t + z + dz

        guessXOld = guessX
        guessYOld = guessY
        guessZOld = guessZ
        guessTimeOld = guessTime
        guessX = x0
        guessY = y0
        guessZ = z0
        guessTime = System.currentTimeMillis()
    }

    companion object {
        private val spawnY = listOf(71.75, 75.75, 79.75)

        private fun eq(a: Double, b: Double) = abs(a - b) < 1e-6
        private fun fract(v: Double) = v - floor(v)
    }
}
