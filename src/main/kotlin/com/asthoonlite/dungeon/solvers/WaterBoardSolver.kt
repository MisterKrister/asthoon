package com.asthoonlite.dungeon.solvers

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.render.WorldBoxRenderer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

import com.asthoonlite.AsthoonLite
import com.asthoonlite.render.WorldTextRenderer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

object WaterBoardSolver : HudElement {

    private val waterSolutions: Map<String, Map<String, Map<String, Map<String, List<Double>>>>> by lazy {
        runCatching {
            WaterBoardSolver::class.java.getResourceAsStream("/assets/asthoonlite/dungeons/puzzles/waterSolutions.json")
                ?.bufferedReader()
                ?.use { it.readText() }?.let { json ->
                    val type = object : TypeToken<Map<String, Map<String, Map<String, Map<String, List<Double>>>>>>() {}.type
                    Gson().fromJson<Map<String, Map<String, Map<String, Map<String, List<Double>>>>>>(json, type)
                }
        }.getOrNull() ?: emptyMap()
    }

    private var solution = ConcurrentHashMap<LEVER, List<Double>>()
    private var patternId = -1
    private var waterLeverStartMs: Long = -1L
    private var lastClickMs: Long = 0L

    private var center: BlockPos? = null
    private var rotation: Int = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { queueRender() }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "water_board_hud"),
            this
        )
        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (patternId != -1 && Config.waterBoardSolverEnabled && DungeonContext.inDungeon) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                    val pos = hitResult.blockPos
                    val block = world.getBlockState(pos).block
                    val lever = LEVER.entries.find { it.getPos(center, rotation) == pos }
                    if (lever != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastClickMs > 250L) {
                            lastClickMs = now
                            if (lever == LEVER.WATER && waterLeverStartMs == -1L) {
                                waterLeverStartMs = now
                            }
                            lever.clickCount++
                        }
                    }
                    if (block is ChestBlock) {
                        reset()
                    }
                }
            }
            InteractionResult.PASS
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun tick() {
        if (!Config.waterBoardSolverEnabled || !DungeonContext.inDungeon) {
            if (patternId != -1) reset()
            return
        }

        val room = DungeonScanner.currentRoom
        if (room == null || room.name != "Water Board") {
            if (patternId != -1) reset()
            return
        }

        if (center == null) {
            center = PuzzleUtils.getRoomCenter(room)
            rotation = if (room.rotation >= 0) (360 - room.rotation) % 360 else 0
        }

        if (patternId == -1) {
            solve()
        }

        // Auto-detect if water lever was powered by a teammate or game event
        val c = center
        if (c != null && waterLeverStartMs == -1L && patternId != -1) {
            val level = Minecraft.getInstance().level
            if (level != null) {
                val waterPos = LEVER.WATER.getPos(c, rotation)
                if (level.isLoaded(waterPos)) {
                    val state = level.getBlockState(waterPos)
                    if (state.block == Blocks.LEVER && state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
                        waterLeverStartMs = System.currentTimeMillis()
                        if (LEVER.WATER.clickCount == 0) LEVER.WATER.clickCount = 1
                    }
                }
            }
        }
    }

    private fun queueRender() {
        if (patternId == -1 || solution.isEmpty() || !Config.waterBoardSolverEnabled || !DungeonContext.inDungeon) return
        val c = center ?: return
        val rot = rotation

        val clicks = solution.entries
            .flatMap { (lever, times) -> times.drop(lever.clickCount).map { lever to it } }
            .sortedBy { (lever, time) -> time + if (lever == LEVER.WATER) 0.01 else 0.0 }

        val nextEntry = clicks.firstOrNull()
        val nextClick = nextEntry?.first
        val nextTime = nextEntry?.second ?: 0.0

        val now = System.currentTimeMillis()
        val elapsed = if (waterLeverStartMs == -1L) 0.0 else (now - waterLeverStartMs) / 1000.0
        val isReady = if (waterLeverStartMs == -1L) nextTime <= 0.0 else (nextTime - elapsed) <= 0.0

        if (nextClick != null) {
            val nextPos = nextClick.getPos(c, rot)

            // Red when waiting for time, lights up green when ready to click
            val boxR = if (isReady) 0f else 1f
            val boxG = if (isReady) 1f else 0f
            val boxB = 0f

            WorldBoxRenderer.queueFilled(
                nextPos.x.toDouble(), nextPos.y.toDouble(), nextPos.z.toDouble(),
                nextPos.x + 1.0, nextPos.y + 1.0, nextPos.z + 1.0,
                boxR, boxG, boxB, 0.4f, throughWalls = true
            )
            WorldBoxRenderer.queueOutline(
                nextPos.x.toDouble(), nextPos.y.toDouble(), nextPos.z.toDouble(),
                nextPos.x + 1.0, nextPos.y + 1.0, nextPos.z + 1.0,
                boxR, boxG, boxB, 1f, thickness = 0.03, throughWalls = true
            )

            // Tracer from player eye to next click: RED when waiting, GREEN when ready
            val player = Minecraft.getInstance().player
            if (player != null) {
                val eye = player.eyePosition
                WorldBoxRenderer.queueLine(
                    eye.x, eye.y - 0.2, eye.z,
                    nextPos.x + 0.5, nextPos.y + 0.5, nextPos.z + 0.5,
                    boxR, boxG, boxB, 0.85f,
                    thickness = 0.04,
                    throughWalls = true
                )
            }

            // Line from next click to second click (Noamm style yellow/orange line)
            if (clicks.size > 1) {
                val secondClick = clicks[1].first
                if (nextClick != secondClick) {
                    val secondPos = secondClick.getPos(c, rot)
                    WorldBoxRenderer.queueLine(
                        nextPos.x + 0.5, nextPos.y + 0.5, nextPos.z + 0.5,
                        secondPos.x + 0.5, secondPos.y + 0.5, secondPos.z + 0.5,
                        1f, 0.65f, 0f, 0.8f,
                        thickness = 0.03,
                        throughWalls = true
                    )
                }
            }
        }

        // Render countdown timers directly on the levers in 3D world space (Noamm style)
        for ((mech, times) in solution) {
            val lpos = mech.getPos(c, rot)
            times.drop(mech.clickCount).forEachIndexed { index, timeSeconds ->
                val displayText = if (waterLeverStartMs == -1L) {
                    if (timeSeconds <= 0.0) "§a§lCLICK"
                    else {
                        val color = if (timeSeconds < 2.0) "§c" else if (timeSeconds < 6.0) "§e" else "§a"
                        "$color${"%.1f".format(java.util.Locale.US, timeSeconds)}s"
                    }
                } else {
                    val remainingSec = timeSeconds - elapsed
                    if (remainingSec > 0.0) {
                        val color = if (remainingSec < 2.0) "§c" else if (remainingSec < 6.0) "§e" else "§a"
                        "$color${"%.1f".format(java.util.Locale.US, remainingSec)}s"
                    } else {
                        "§a§lCLICK"
                    }
                }

                val renderY = lpos.y + 1.5 + (index + mech.clickCount) * 0.5
                WorldTextRenderer.queueText(
                    displayText,
                    lpos.x + 0.5,
                    renderY,
                    lpos.z + 0.5,
                    scale = 1.35f,
                    throughWalls = true
                )
            }
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.waterBoardSolverEnabled || !DungeonContext.inDungeon || patternId == -1 || solution.isEmpty()) return
        val mc = Minecraft.getInstance()
        val elapsed = if (waterLeverStartMs == -1L) 0.0 else (System.currentTimeMillis() - waterLeverStartMs) / 1000.0

        val clicks = solution.entries
            .flatMap { (lever, times) -> times.drop(lever.clickCount).map { lever to it } }
            .sortedBy { (lever, time) -> time + if (lever == LEVER.WATER) 0.01 else 0.0 }

        if (clicks.isEmpty()) return

        val (nextLever, nextTime) = clicks.first()
        val remaining = nextTime - elapsed
        val leverName = nextLever.name.lowercase().replaceFirstChar { it.uppercase() }

        val text = if (waterLeverStartMs == -1L) {
            if (nextTime <= 0.0) "§bWater Board: §a§lCLICK $leverName"
            else "§bWater Board: §fClick §e$leverName §fin §6${"%.1f".format(java.util.Locale.US, nextTime)}s"
        } else {
            if (remaining <= 0.0) "§bWater Board: §a§lCLICK $leverName NOW!"
            else "§bWater Board: §fClick §e$leverName §fin §6${"%.1f".format(java.util.Locale.US, remaining)}s"
        }

        val x = 10
        val y = 140
        val w = mc.font.width(text) + 8
        val h = mc.font.lineHeight + 6

        context.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x88000000.toInt())
        context.fill(x, y, x + w, y + h, 0xDD0D1F35.toInt())
        context.text(mc.font, text, x + 4, y + 3, 0xFFFFFF)
    }

    private fun solve() {
        val c = center ?: return
        val rot = rotation

        val gates = Gate.entries.joinToString("") {
            if (it.isGateClosed(c, rot)) it.ordinal.toString() else ""
        }
        if (gates.length != 3) return

        patternId = when {
            checkBlock(BlockPos(-1, 77, 12), Blocks.TERRACOTTA) -> 0
            checkBlock(BlockPos(1, 78, 12), Blocks.EMERALD_BLOCK) -> 1
            checkBlock(BlockPos(-1, 78, 12), Blocks.DIAMOND_BLOCK) -> 2
            checkBlock(BlockPos(-1, 78, 12), Blocks.QUARTZ_BLOCK) -> 3
            else -> return
        }

        solution.clear()
        val falseSolutions = waterSolutions["false"]?.get(patternId.toString())?.get(gates)
        falseSolutions?.forEach { (key, times) ->
            LEVER.fromKey(key)?.let { solution[it] = times }
        }
    }

    private fun checkBlock(rel: BlockPos, expected: Block): Boolean {
        val c = center ?: return false
        val rot = rotation
        val realPos = PuzzleUtils.getRealCoord(rel, c, rot)
        val level = Minecraft.getInstance().level ?: return false
        return level.getBlockState(realPos).block == expected
    }

    fun reset() {
        LEVER.entries.forEach { it.clickCount = 0 }
        patternId = -1
        solution.clear()
        waterLeverStartMs = -1L
        lastClickMs = 0L
        center = null
        rotation = 0
    }

    private enum class Gate(val offset: BlockPos) {
        PURPLE(BlockPos(0, 56, 4)),
        ORANGE(BlockPos(0, 56, 3)),
        BLUE(BlockPos(0, 56, 2)),
        GREEN(BlockPos(0, 56, 1)),
        RED(BlockPos(0, 56, 0));

        fun isGateClosed(c: BlockPos, rot: Int): Boolean {
            val pos = PuzzleUtils.getRealCoord(offset, c, rot)
            val level = Minecraft.getInstance().level ?: return false
            val state = level.getBlockState(pos)
            return state.`is`(BlockTags.WOOL)
        }
    }

    private enum class LEVER(val offset: BlockPos, var clickCount: Int = 0) {
        QUARTZ(BlockPos(5, 61, 5)),
        GOLD(BlockPos(5, 61, 0)),
        COAL(BlockPos(5, 61, -5)),
        DIAMOND(BlockPos(-5, 61, 5)),
        EMERALD(BlockPos(-5, 61, 0)),
        CLAY(BlockPos(-5, 61, -5)),
        WATER(BlockPos(0, 60, -10));

        fun getPos(c: BlockPos?, rot: Int): BlockPos {
            if (c == null) return BlockPos.ZERO
            return PuzzleUtils.getRealCoord(offset, c, rot)
        }

        companion object {
            fun fromKey(str: String) = when (str) {
                "diamond_block" -> DIAMOND
                "emerald_block" -> EMERALD
                "hardened_clay" -> CLAY
                "quartz_block" -> QUARTZ
                "gold_block" -> GOLD
                "coal_block" -> COAL
                "water" -> WATER
                else -> null
            }
        }
    }
}
