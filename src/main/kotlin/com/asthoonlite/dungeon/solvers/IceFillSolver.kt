package com.asthoonlite.dungeon.solvers

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object IceFillSolver {

    private var inIceFill = false
    private var roomCenter: BlockPos? = null
    private var rotation: Int = 0
    private val paths = CopyOnWriteArrayList<List<BlockPos>>()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { queueLines() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun tick() {
        if (!Config.iceFillSolverEnabled || !DungeonContext.inDungeon) {
            if (inIceFill) reset()
            return
        }

        val room = DungeonScanner.currentRoom
        if (room == null || room.name != "Ice Fill") {
            if (inIceFill) reset()
            return
        }

        if (!inIceFill) {
            inIceFill = true
            roomCenter = PuzzleUtils.getRoomCenter(room)
            rotation = if (room.rotation >= 0) (360 - room.rotation) % 360 else 0
            thread(name = "AsthoonLite-IceFillSolver") {
                solve()
            }
        }
    }

    private fun queueLines() {
        if (!inIceFill || !Config.iceFillSolverEnabled || !DungeonContext.inDungeon) return

        for (path in paths) {
            for (i in 1 until path.size) {
                val p1 = path[i - 1]
                val p2 = path[i]
                WorldBoxRenderer.queueLine(
                    p1.x + 0.5, p1.y + 0.05, p1.z + 0.5,
                    p2.x + 0.5, p2.y + 0.05, p2.z + 0.5,
                    0f, 0.9f, 1f, 0.95f,
                    thickness = 0.08,
                    throughWalls = true
                )
            }
        }
    }

    fun solve() {
        val center = roomCenter ?: return
        val level = Minecraft.getInstance().level ?: return

        val checkpoints = listOf(
            PuzzleUtils.getRealCoord(BlockPos(0, 69, -8), center, rotation),
            PuzzleUtils.getRealCoord(BlockPos(0, 70, -3), center, rotation),
            PuzzleUtils.getRealCoord(BlockPos(0, 71, 4), center, rotation),
            PuzzleUtils.getRealCoord(BlockPos(0, 74, 15), center, rotation)
        )

        val allIceBlocks = mutableSetOf<BlockPos>()
        for (dx in -22..22) for (dz in -22..22) for (dy in 66..76) {
            val pos = center.offset(dx, dy - center.y, dz)
            val state = level.getBlockState(pos)
            if (!state.`is`(Blocks.ICE) && !state.`is`(Blocks.PACKED_ICE)) continue
            if (!level.getBlockState(pos.above()).isAir) continue
            allIceBlocks.add(pos.above())
        }

        if (allIceBlocks.isEmpty()) return

        val clusters = mutableListOf<MutableSet<BlockPos>>()
        val visited = mutableSetOf<BlockPos>()

        for (pos in allIceBlocks) {
            if (pos in visited) continue
            val cluster = mutableSetOf<BlockPos>()
            val queue = ArrayDeque<BlockPos>()
            queue.add(pos)
            visited.add(pos)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cluster.add(current)
                for (dir in Direction.Plane.HORIZONTAL) {
                    val next = current.relative(dir)
                    if (next in allIceBlocks && next !in visited) {
                        visited.add(next)
                        queue.add(next)
                    }
                }
            }
            clusters.add(cluster)
        }

        clusters.sortBy { cluster -> cluster.minOf { it.distSqr(checkpoints[0]) } }

        val newPaths = mutableListOf<List<BlockPos>>()
        for ((i, cluster) in clusters.withIndex()) {
            if (i >= 3) break
            val spaces = cluster.toHashSet()
            val start = spaces.minByOrNull { it.distSqr(checkpoints[i]) } ?: continue
            val endCandidate = spaces.minByOrNull { it.distSqr(checkpoints[i + 1]) } ?: continue

            var puzzle = IceFillPuzzle(spaces, start, endCandidate).solve()

            // If the initial checkpoint end didn't yield a path, try candidate exit blocks
            // (the blocks in spaces furthest from start)
            if (puzzle.path.isEmpty()) {
                val candidateEnds = spaces.sortedByDescending { it.distSqr(start) }.take(5)
                for (cand in candidateEnds) {
                    if (cand == start || cand == endCandidate) continue
                    val testPuzzle = IceFillPuzzle(spaces, start, cand).solve()
                    if (testPuzzle.path.isNotEmpty()) {
                        puzzle = testPuzzle
                        break
                    }
                }
            }

            if (puzzle.path.isNotEmpty()) {
                newPaths.add(puzzle.path)
            }
        }

        paths.clear()
        paths.addAll(newPaths)
    }

    fun reset() {
        inIceFill = false
        roomCenter = null
        rotation = 0
        paths.clear()
    }

    private class IceFillPuzzle(val spaces: HashSet<BlockPos>, val start: BlockPos, val end: BlockPos) {
        var path = mutableListOf<BlockPos>()
            private set

        private val graph = spaces.associateWith { pos ->
            Direction.Plane.HORIZONTAL.filter { pos.relative(it) in spaces }
        }

        private val NO_WALL_PENALTY = 5
        private val DOUBLE_TURN_PENALTY = 2
        private val TURN_PENALTY = 1

        private fun dirBetween(a: BlockPos, b: BlockPos) = when {
            b.x > a.x -> Direction.EAST
            b.x < a.x -> Direction.WEST
            b.z > a.z -> Direction.SOUTH
            else -> Direction.NORTH
        }

        private fun isBackedByWall(pos: BlockPos, dir: Direction) = pos.relative(dir) !in spaces

        private fun remainingConnected(current: BlockPos, visited: Set<BlockPos>): Boolean {
            val remaining = spaces.size - visited.size + 1
            if (remaining <= 1) return true

            val seen = HashSet<BlockPos>(remaining)
            val stack = ArrayDeque<BlockPos>()
            stack.add(current)
            seen.add(current)

            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                for (dir in graph[cur] ?: emptyList()) {
                    val n = cur.relative(dir)
                    if ((n == current || n !in visited) && seen.add(n)) stack.add(n)
                }
            }
            return seen.size == remaining
        }

        private fun pathCost(fullPath: List<BlockPos>): Int {
            if (fullPath.size < 3) return 0
            var total = 0
            var prevDir: Direction? = null
            var prevWasTurn = false
            for (i in 1 until fullPath.size) {
                val dir = dirBetween(fullPath[i - 1], fullPath[i])
                if (prevDir != null && dir != prevDir) {
                    total += TURN_PENALTY
                    if (!isBackedByWall(fullPath[i - 1], prevDir)) total += NO_WALL_PENALTY
                    if (prevWasTurn) total += DOUBLE_TURN_PENALTY
                    prevWasTurn = true
                } else prevWasTurn = false
                prevDir = dir
            }
            return total
        }

        private fun search(stopAtFirst: Boolean, costBound: Int): List<BlockPos>? {
            var bestCost = costBound
            var bestPath: List<BlockPos>? = null

            val visited = mutableSetOf(start)
            val tempPath = ArrayList<BlockPos>(spaces.size).apply { add(start) }

            fun dfs(current: BlockPos, lastDir: Direction?, lastWasTurn: Boolean, cost: Int): Boolean {
                if (cost >= bestCost) return false

                if (visited.size == spaces.size) {
                    if (current == end) {
                        bestCost = cost
                        bestPath = tempPath.toList()
                        return stopAtFirst
                    }
                    return false
                }
                if (current == end) return false

                val candidates = (graph[current] ?: emptyList())
                    .map { it to current.relative(it) }
                    .filter { (_, pos) -> pos !in visited }
                    .sortedWith(
                        compareBy(
                            { (_, pos) -> (graph[pos] ?: emptyList()).count { pos.relative(it) !in visited } },
                            { (dir, _) -> if (dir == lastDir) 0 else 1 }
                        )
                    )

                for ((dir, nextPos) in candidates) {
                    var stepCost = cost
                    var turned = false
                    if (lastDir != null && dir != lastDir) {
                        turned = true
                        stepCost += TURN_PENALTY
                        if (!isBackedByWall(current, lastDir)) stepCost += NO_WALL_PENALTY
                        if (lastWasTurn) stepCost += DOUBLE_TURN_PENALTY
                    }
                    if (stepCost >= bestCost) continue

                    visited.add(nextPos)
                    tempPath.add(nextPos)

                    val stop = remainingConnected(nextPos, visited) && dfs(nextPos, dir, turned, stepCost)

                    tempPath.removeAt(tempPath.size - 1)
                    visited.remove(nextPos)

                    if (stop) return true
                }
                return false
            }

            dfs(start, null, false, 0)
            return bestPath
        }

        fun solve(): IceFillPuzzle {
            if (start !in spaces || end !in spaces) return this
            val fallback = search(stopAtFirst = true, costBound = Int.MAX_VALUE) ?: return this
            // Only perform full cost-minimizing search on small clusters (<= 18 blocks)
            // to avoid combinatorial explosion on section 3 (~30 blocks)
            val optimized = if (spaces.size <= 18) {
                search(stopAtFirst = false, costBound = pathCost(fallback))
            } else null
            path = (optimized ?: fallback).toMutableList()
            return this
        }
    }
}
