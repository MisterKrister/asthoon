package com.asthoonlite.dungeon.solvers

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import kotlin.math.floor

/**
 * TicTacToe solver based on Odin/Devonian:
 * - Continuously scans the 9 item frames on tick
 * - Decodes board state via map color indices
 * - Uses minimax with alpha-beta pruning and preferred move order [4, 0, 2, 6, 8, 1, 3, 5, 7]
 * - Highlights strictly ONE button (the single best move) using room.fromComp
 * - Blocks clicks on any button other than the solution button
 */
object TicTacToeSolver {

    private val boardPos = listOf(
        Triple(8, 72, 17), Triple(8, 72, 16), Triple(8, 72, 15),
        Triple(8, 71, 17), Triple(8, 71, 16), Triple(8, 71, 15),
        Triple(8, 70, 17), Triple(8, 70, 16), Triple(8, 70, 15)
    )

    private var currentBoard = mutableListOf<String?>(
        null, null, null,
        null, null, null,
        null, null, null
    )

    var inTTT = false
        private set
    var currentBestMove = -1
        private set
    private var currentBestButtonPos: BlockPos? = null

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { queueBoxes() }
        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (inTTT && Config.ticTacToeSolverEnabled && DungeonContext.inDungeon && !DungeonContext.inBoss) {
                val pos = hitResult.blockPos
                val state = world.getBlockState(pos)
                val targetPos = currentBestButtonPos
                if (state.`is`(BlockTags.BUTTONS) && targetPos != null && pos != targetPos) {
                    return@register InteractionResult.FAIL
                }
            }
            InteractionResult.PASS
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    fun onAddEntity(packet: net.minecraft.network.protocol.game.ClientboundAddEntityPacket) {
        if (inTTT && packet.type == net.minecraft.world.entity.EntityType.ITEM_FRAME) {
            tick()
        }
    }

    fun reset() {
        inTTT = false
        currentBoard.fill(null)
        currentBestMove = -1
        currentBestButtonPos = null
    }

    private fun tick() {
        if (!Config.ticTacToeSolverEnabled || !DungeonContext.inDungeon || DungeonContext.inBoss) {
            if (inTTT) reset()
            return
        }

        val room = DungeonScanner.currentRoom
        if (room == null || room.name != "Tic Tac Toe" || !room.hasRotation()) {
            if (inTTT) reset()
            return
        }

        inTTT = true
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val start = Triple(8, 70, 15)
        val end = Triple(8, 72, 17)
        val (startX, startZ) = room.fromComp(start.first - 1, start.third - 1) ?: return
        val (endX, endZ) = room.fromComp(end.first + 1, end.third + 1) ?: return

        val minX = minOf(startX, endX).toDouble()
        val maxX = maxOf(startX, endX).toDouble()
        val minZ = minOf(startZ, endZ).toDouble()
        val maxZ = maxOf(startZ, endZ).toDouble()

        val itemMaps = level.getEntitiesOfClass(
            ItemFrame::class.java,
            AABB(
                minX - 1.0, start.second.toDouble() - 1, minZ - 1.0,
                maxX + 1.0, end.second.toDouble() + 1, maxZ + 1.0
            )
        )

        val board = mutableListOf<String?>(
            null, null, null,
            null, null, null,
            null, null, null
        )

        for (entity in itemMaps) {
            val compPos = room.fromPos(floor(entity.x).toInt(), floor(entity.z).toInt()) ?: continue
            if (compPos.first !in 6..9) continue
            val idx = boardPos.indexOf(Triple(compPos.first, entity.y.toInt(), compPos.second))
            if (idx == -1) continue

            val mapId = entity.item.get(DataComponents.MAP_ID) ?: continue
            val map = level.getMapData(mapId) ?: continue
            val colors = map.colors
            if (colors.size < 16384) continue

            // Determine dominant background color
            val freq = IntArray(256)
            for (b in colors) {
                freq[b.toInt() and 0xFF]++
            }
            var bgInt = 0
            var maxCount = 0
            for (i in 0..255) {
                if (freq[i] > maxCount) {
                    maxCount = freq[i]
                    bgInt = i
                }
            }
            val bgByte = bgInt.toByte()
            val nonBgCount = 16384 - maxCount

            // If almost all pixels are background, slot is empty
            if (nonBgCount < 300) {
                board[idx] = null
                continue
            }

            // Distinguish X vs O:
            // In X, the two diagonal lines cross directly at the center (64, 64).
            // In O, the center is completely hollow (background color).
            var centerForeground = 0
            for (cy in 58..69) {
                for (cx in 58..69) {
                    if (colors[cy * 128 + cx] != bgByte) {
                        centerForeground++
                    }
                }
            }

            val status = if (centerForeground >= 20) "X" else "O"
            board[idx] = status
        }

        if (board != currentBoard) {
            currentBoard = board
            val countX = currentBoard.count { it == "X" }
            val countO = currentBoard.count { it == "O" }

            // Hypixel AI plays X, human player plays O.
            // It is O's turn when AI has played (countX > countO) and game is not finished.
            val isPlayerTurn = countX > countO && !isWinner(currentBoard, "X") && !isWinner(currentBoard, "O") && currentBoard.any { it == null }

            if (isPlayerTurn) {
                currentBestMove = bestMove(currentBoard, "O")
                currentBestButtonPos = if (currentBestMove != -1) {
                    val best = boardPos.getOrNull(currentBestMove)
                    if (best != null) {
                        val roomPos = room.fromComp(best.first - 1, best.third)
                        if (roomPos != null) BlockPos(roomPos.first, best.second, roomPos.second) else null
                    } else null
                } else null
            } else {
                currentBestMove = -1
                currentBestButtonPos = null
            }
        }
    }

    private fun queueBoxes() {
        if (!inTTT || !Config.ticTacToeSolverEnabled || currentBestButtonPos == null) return
        val pos = currentBestButtonPos ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val state = level.getBlockState(pos)
        val shape = if (state.`is`(BlockTags.BUTTONS)) state.getShape(level, pos) else null
        val bounds = if (shape != null && !shape.isEmpty) {
            shape.bounds().move(pos)
        } else {
            AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
        }

        WorldBoxRenderer.queueFilled(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, 0f, 1f, 0f, 0.65f, throughWalls = true)
        WorldBoxRenderer.queueOutline(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, 0f, 1f, 0f, 1f, thickness = 0.03, throughWalls = true)
    }

    // ── Minimax Algorithm matching Odin / Devonian ──────────────────────

    private val boardOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
    private val winningSides = listOf(
        Triple(0, 1, 2), Triple(3, 4, 5), Triple(6, 7, 8),
        Triple(0, 3, 6), Triple(1, 4, 7), Triple(2, 5, 8),
        Triple(0, 4, 8), Triple(2, 4, 6)
    )

    private fun isWinner(board: List<String?>, player: String): Boolean {
        return winningSides.any { (a, b, c) ->
            board[a] == player && board[b] == player && board[c] == player
        }
    }

    private fun minMax(
        board: List<String?>,
        depth: Int,
        alpha: Int,
        beta: Int,
        isPlayer: Boolean
    ): Int {
        if (isWinner(board, "X")) return 10 - depth
        if (isWinner(board, "O")) return depth - 10
        if (board.all { it != null }) return 0

        var a = alpha
        var b = beta

        if (isPlayer) {
            var best = Int.MIN_VALUE
            for (idx in boardOrder) {
                if (board[idx] != null) continue
                val tempBoard = board.mapIndexed { jdx, cell -> if (idx == jdx) "X" else cell }
                val score = minMax(tempBoard, depth + 1, a, b, false)
                best = maxOf(best, score)
                a = maxOf(a, score)
                if (b <= a) break
            }
            return best
        }

        var best = Int.MAX_VALUE
        for (idx in boardOrder) {
            if (board[idx] != null) continue
            val tempBoard = board.mapIndexed { jdx, cell -> if (idx == jdx) "O" else cell }
            val score = minMax(tempBoard, depth + 1, a, b, true)
            best = minOf(best, score)
            b = minOf(b, score)
            if (b <= a) break
        }
        return best
    }

    internal fun bestMove(board: List<String?>, player: String): Int {
        val maximizing = player == "X"
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var best = -1

        // If only 1 move on board, prioritize center or top-left
        if (board.filterNotNull().size == 1) {
            if (board[4] == null) return 4
            return 0
        }

        for (idx in boardOrder) {
            if (board[idx] != null) continue
            val tempBoard = board.mapIndexed { jdx, cell -> if (idx == jdx) player else cell }
            val score = minMax(tempBoard, 0, Int.MIN_VALUE, Int.MAX_VALUE, player != "X")

            if (maximizing) {
                if (score > bestScore) {
                    bestScore = score
                    best = idx
                }
            } else {
                if (score < bestScore) {
                    bestScore = score
                    best = idx
                }
            }
        }

        return best
    }
}
