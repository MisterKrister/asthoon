package com.asthoonlite.dungeon

/**
 * Which pane a terminal solver should click *next*.
 *
 * The naive answer is "the first one in slot order", and that is what the
 * clicker used to do. It solves the terminal just as fast on paper and looks
 * wrong in practice: the pointer sweeps the whole pane, backtracks, sweeps
 * again. A human works outwards from wherever the mouse already is.
 *
 * So: nearest candidate first, ties broken in favour of the candidate with
 * other candidates hugging it (people clear a cluster before moving on), then
 * by slot index so the result is stable. Same policy as NoammAddons'
 * `HumanClickOrder`, expressed on the 9-column grid instead of raw pixel
 * distances — every terminal is a uniform 18 px grid, so the two agree, and
 * this one needs no container menu to evaluate, which is what makes it
 * testable.
 *
 * Every terminal layout in the game is a multiple of 9 wide (45 = 5×9,
 * 36 = 4×9, 54 = 6×9), so [cols] never needs to vary per type.
 */
object TerminalClickOrder {

    /** Grid width of every terminal container. */
    const val COLS = 9

    /** Neighbourhood used to prefer clusters, in grid pixels. One cell of
     *  slack, so orthogonal neighbours count and diagonal ones do not — which
     *  is how people actually group their clicks. */
    private const val NEIGHBOR_RADIUS_SQR = 20.0 * 20.0

    /**
     * The candidate the pointer should travel to next.
     *
     * [lastSlot] is where the pointer is coming from; null means it has not
     * clicked yet, in which case the centre of the grid is used so a fresh
     * terminal starts from somewhere sensible instead of slot 0.
     *
     * Returns null only when there is nothing to click.
     */
    fun pickNearest(candidates: Collection<Int>, lastSlot: Int?, slotCount: Int): Int? {
        val list = candidates.distinct().filter { it >= 0 }
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]

        val from = lastSlot ?: (slotCount / 2)
        var best: Int = list[0]
        var bestDistance = Int.MAX_VALUE
        var bestNeighbors = Int.MIN_VALUE

        for (candidate in list) {
            val distance = distanceSqr(from, candidate)
            if (distance > bestDistance) continue
            val neighbors = countNeighbors(candidate, list)
            if (distance < bestDistance || neighbors > bestNeighbors) {
                best = candidate
                bestDistance = distance
                bestNeighbors = neighbors
            }
        }
        return best
    }

    /** Candidates within [NEIGHBOR_RADIUS_SQR] of [target], excluding itself. */
    fun countNeighbors(target: Int, all: Collection<Int>): Int {
        var count = 0
        for (other in all) {
            if (other == target) continue
            if (distanceSqr(target, other) <= NEIGHBOR_RADIUS_SQR) count++
        }
        return count
    }

    /** Squared distance in grid pixels between two slot indices. */
    fun distanceSqr(a: Int, b: Int): Int {
        val dx = (a % COLS) - (b % COLS)
        val dy = (a / COLS) - (b / COLS)
        val px = dx * GRID_PX
        val py = dy * GRID_PX
        return px * px + py * py
    }

    private const val GRID_PX = 18
}
