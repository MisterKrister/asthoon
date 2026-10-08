package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * Floor 7 / Master Mode 7 Terminal interaction suite:
 * - Terminal Highlight: Highlights the clickable interaction hitbox (ItemFrame / CommandBlock)
 *   in front of terminals, specifically filtering out Arrow Align puzzle frames (which contain Items.ARROW).
 * - Terminal Triggerbot: Automatically clicks the terminal interaction hitbox when looking at it within range.
 * - Terminal Aura: Automatically opens/interacts with terminals within reach and FOV.
 */
object TerminalInteraction {
    private val lastEntityClicks = ConcurrentHashMap<Int, Long>()
    private val lastBlockClicks = ConcurrentHashMap<BlockPos, Long>()
    private var lastActionTime = 0L

    private val cachedTerminalBlocks = ArrayList<BlockPos>()
    private var lastBlockScanTime = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { render() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    fun reset() {
        lastEntityClicks.clear()
        lastBlockClicks.clear()
        cachedTerminalBlocks.clear()
        lastActionTime = 0L
        lastBlockScanTime = 0L
    }

    /**
     * Determines whether an ItemFrame entity is the clickable interaction target of a terminal.
     * Excludes Arrow Align frames (holding Items.ARROW at the puzzle wall) and completed terminals.
     */
    fun isTerminalItemFrame(level: Level, frame: ItemFrame): Boolean {
        // Arrow Align frames hold arrows and sit at grid corner x = -2
        if (frame.item.`is`(Items.ARROW)) return false
        val bp = frame.blockPosition()
        if (bp.x == -2 && bp.y in 120..124 && bp.z in 75..79) return false

        // Check if an armor stand hologram nearby marks this terminal as already completed
        val nearbyStands = level.getEntitiesOfClass(ArmorStand::class.java, frame.boundingBox.inflate(3.0))
        for (stand in nearbyStands) {
            val name = stand.customName?.string ?: continue
            if (name.contains("Completed", ignoreCase = true)) return false
            if (name.contains("Active", ignoreCase = true) && !name.contains("Inactive", ignoreCase = true)) {
                if (DungeonContext.inDungeon && !lastEntityClicks.containsKey(frame.id)) return false
            }
        }

        // Check if mounted on / adjacent to a command block
        val box = frame.boundingBox.inflate(1.5)
        val minX = floor(box.minX).toInt()
        val maxX = ceil(box.maxX).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = ceil(box.maxY).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = ceil(box.maxZ).toInt()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val b = level.getBlockState(BlockPos(x, y, z)).block
                    if (b == Blocks.COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK) {
                        return true
                    }
                }
            }
        }

        // Check if nearby armor stand is labeled as a terminal
        for (stand in nearbyStands) {
            val name = stand.customName?.string ?: continue
            if (name.contains("Terminal", ignoreCase = true) || name.contains("Click Here", ignoreCase = true)) {
                return true
            }
        }

        // In F7 / M7 dungeon boss rooms, any non-arrow item frame is a terminal interaction target
        if (DungeonContext.inDungeon || Config.autoTerminalAnywhere) {
            return true
        }

        return false
    }

    /**
     * Determines whether an ArmorStand entity is an interactable terminal target.
     */
    fun isTerminalArmorStand(stand: ArmorStand): Boolean {
        val name = stand.customName?.string ?: return false
        if (name.contains("Completed", ignoreCase = true)) return false
        if (name.contains("Active", ignoreCase = true) && !name.contains("Inactive", ignoreCase = true)) {
            if (DungeonContext.inDungeon && !lastEntityClicks.containsKey(stand.id)) return false
        }
        return name.contains("Terminal", ignoreCase = true) || name.contains("Click Here", ignoreCase = true)
    }

    /**
     * Determines whether a block position is an uncompleted command block terminal.
     */
    fun isTerminalBlock(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        val b = state.block
        if (b != Blocks.COMMAND_BLOCK && b != Blocks.CHAIN_COMMAND_BLOCK && b != Blocks.REPEATING_COMMAND_BLOCK) return false

        // Exclude if a nearby hologram indicates completion
        val nearbyStands = level.getEntitiesOfClass(ArmorStand::class.java, AABB(pos).inflate(3.0))
        for (stand in nearbyStands) {
            val name = stand.customName?.string ?: continue
            if (name.contains("Completed", ignoreCase = true)) return false
            if (name.contains("Active", ignoreCase = true) && !name.contains("Inactive", ignoreCase = true)) {
                if (DungeonContext.inDungeon && !lastBlockClicks.containsKey(pos)) return false
            }
        }
        return true
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!DungeonContext.inDungeon && !Config.autoTerminalAnywhere) return

        val now = System.currentTimeMillis()
        val cooldownMs = (Config.terminalTriggerBotCooldown * 1000.0).toLong().coerceAtLeast(200L)
        val cleanupWindow = maxOf(cooldownMs * 2, 60000L)
        lastEntityClicks.entries.removeIf { now - it.value > cleanupWindow }
        lastBlockClicks.entries.removeIf { now - it.value > cleanupWindow }

        // Update cached terminal command blocks periodically (every 500ms)
        if (now - lastBlockScanTime > 500L) {
            lastBlockScanTime = now
            cachedTerminalBlocks.clear()
            val r = 24
            val px = player.blockX
            val py = player.blockY
            val pz = player.blockZ
            for (x in (px - r)..(px + r)) {
                for (y in (py - 8)..(py + 8)) {
                    for (z in (pz - r)..(pz + r)) {
                        val pos = BlockPos(x, y, z)
                        val b = level.getBlockState(pos).block
                        if (b == Blocks.COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK || b == Blocks.REPEATING_COMMAND_BLOCK) {
                            if (isTerminalBlock(level, pos)) {
                                cachedTerminalBlocks.add(pos)
                            }
                        }
                    }
                }
            }
        }

        // Never trigger or aura click when a GUI menu is already open
        if (mc.screen != null) return

        if (Config.terminalTriggerBotEnabled) {
            tickTriggerBot(mc, level, player, now)
        }

        if (Config.terminalAuraEnabled) {
            tickAura(mc, level, player, now)
        }
    }

    private fun tickTriggerBot(mc: Minecraft, level: Level, player: LocalPlayer, now: Long) {
        if (now - lastActionTime < 120L) return
        val hit = mc.hitResult ?: return
        val cooldownMs = (Config.terminalTriggerBotCooldown * 1000.0).toLong().coerceAtLeast(200L)

        if (hit is EntityHitResult) {
            val entity = hit.entity
            val isTerm = (entity is ItemFrame && isTerminalItemFrame(level, entity)) ||
                         (entity is ArmorStand && isTerminalArmorStand(entity))
            if (!isTerm) return
            if (player.eyePosition.distanceTo(entity.position()) > 5.0) return
            if (now - (lastEntityClicks[entity.id] ?: 0L) < cooldownMs) return

            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.interact(player, entity, hit, InteractionHand.MAIN_HAND)
            lastEntityClicks[entity.id] = now
            lastActionTime = now
        } else if (hit is BlockHitResult) {
            val pos = hit.blockPos
            if (!isTerminalBlock(level, pos)) return
            if (player.eyePosition.distanceTo(Vec3.atCenterOf(pos)) > 5.0) return
            if (now - (lastBlockClicks[pos] ?: 0L) < cooldownMs) return

            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            lastBlockClicks[pos] = now
            lastActionTime = now
        }
    }

    private fun tickAura(mc: Minecraft, level: Level, player: LocalPlayer, now: Long) {
        if (now - lastActionTime < 200L) return
        val maxRange = Config.terminalAuraRange
        val eye = player.eyePosition
        val look = player.lookAngle
        val cooldownMs = (Config.terminalTriggerBotCooldown * 1000.0).toLong().coerceAtLeast(200L)

        // 1. Scan for nearby terminal entities (ItemFrames / ArmorStands)
        val searchBox = player.boundingBox.inflate(maxRange)
        val entities = level.getEntitiesOfClass(Entity::class.java, searchBox) {
            (it is ItemFrame && isTerminalItemFrame(level, it)) ||
            (it is ArmorStand && isTerminalArmorStand(it))
        }

        var bestEntity: Pair<Entity, Double>? = null
        for (entity in entities) {
            if (now - (lastEntityClicks[entity.id] ?: 0L) < cooldownMs) continue
            val center = entity.position().add(0.0, entity.bbHeight / 2.0, 0.0)
            val dist = eye.distanceTo(center)
            if (dist > maxRange) continue
            val to = center.subtract(eye)
            if (to.lengthSqr() <= 1e-6) continue
            val dot = look.dot(to.normalize()).coerceIn(-1.0, 1.0)
            val angle = Math.toDegrees(acos(dot))
            if (angle > 90.0) continue // Within 180° front FOV cone

            val current = bestEntity
            if (current == null || angle < current.second) {
                bestEntity = entity to angle
            }
        }

        if (bestEntity != null) {
            val entity = bestEntity.first
            val hit = EntityHitResult(entity, entity.position())
            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.interact(player, entity, hit, InteractionHand.MAIN_HAND)
            lastEntityClicks[entity.id] = now
            lastActionTime = now
            return
        }

        // 2. Scan for command blocks if no entity target was found
        var bestBlock: Pair<BlockPos, Double>? = null
        for (pos in cachedTerminalBlocks) {
            if (now - (lastBlockClicks[pos] ?: 0L) < cooldownMs) continue
            val center = Vec3.atCenterOf(pos)
            val dist = eye.distanceTo(center)
            if (dist > maxRange) continue
            val to = center.subtract(eye)
            if (to.lengthSqr() <= 1e-6) continue
            val dot = look.dot(to.normalize()).coerceIn(-1.0, 1.0)
            val angle = Math.toDegrees(acos(dot))
            if (angle > 90.0) continue

            val current = bestBlock
            if (current == null || angle < current.second) {
                bestBlock = pos to angle
            }
        }

        if (bestBlock != null) {
            val pos = bestBlock.first
            val hit = BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
            player.swing(InteractionHand.MAIN_HAND)
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            lastBlockClicks[pos] = now
            lastActionTime = now
        }
    }

    private fun render() {
        if (!Config.terminalHighlightEnabled) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!DungeonContext.inDungeon && !Config.autoTerminalAnywhere) return

        val searchBox = player.boundingBox.inflate(28.0)
        val frames = level.getEntitiesOfClass(ItemFrame::class.java, searchBox)
        val highlightedPositions = HashSet<BlockPos>()

        // Render emerald cyan box over each terminal ItemFrame
        for (frame in frames) {
            if (isTerminalItemFrame(level, frame)) {
                val b = frame.boundingBox
                WorldBoxRenderer.queueOutline(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0.15f, 0.95f, 0.75f, 1f, throughWalls = true)
                WorldBoxRenderer.queueFilled(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0.15f, 0.95f, 0.75f, 0.18f, throughWalls = true)
                highlightedPositions.add(frame.blockPosition())
            }
        }

        // Render cached command blocks that don't have an item frame covering them
        for (pos in cachedTerminalBlocks) {
            if (highlightedPositions.contains(pos)) continue
            WorldBoxRenderer.queueOutline(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0, 0.15f, 0.95f, 0.75f, 1f, throughWalls = true)
            WorldBoxRenderer.queueFilled(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0, 0.15f, 0.95f, 0.75f, 0.12f, throughWalls = true)
        }
    }
}
