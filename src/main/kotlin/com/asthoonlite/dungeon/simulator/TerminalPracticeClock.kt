package com.asthoonlite.dungeon.simulator

import com.asthoonlite.dungeon.TerminalSolver

/** One practice screen's delayed inputs and one-second restart, on a monotonic clock. */
internal class TerminalPracticeClock {
    data class PendingClick(val id: Long, val slot: Int, val button: Int, val source: String,
                            val requestedAtNs: Long, val dueAtMs: Long,
                            val requestedButton: Int = button, val rubixTarget: Int? = null,
                            val rubixPredictedBefore: Int? = null)
    private val pending = ArrayDeque<PendingClick>()
    private var restartAtMs: Long? = null
    private var cancelled = false
    private var nextId = 0L
    var lastQueued: PendingClick? = null
        private set

    fun enqueue(slot: Int, button: Int, source: String, nowMs: Long, pingMs: Int,
                requestedAtNs: Long = nowMs * 1_000_000L, requestedButton: Int = button,
                rubixTarget: Int? = null, rubixPredictedBefore: Int? = null): Boolean {
        if (cancelled || restartAtMs != null) return false
        val click = PendingClick(++nextId, slot, button, source, requestedAtNs, nowMs + pingMs.coerceIn(0, 500),
            requestedButton, rubixTarget, rubixPredictedBefore)
        lastQueued = click
        pending.addLast(click)
        return true
    }

    fun poll(nowMs: Long): PendingClick? =
        if (!cancelled && pending.firstOrNull()?.dueAtMs?.let { it <= nowMs } == true) pending.removeFirst() else null

    /** Include earlier queued inputs before choosing the next Rubix direction. */
    fun rubixPredictedColor(slot: Int, currentColor: Int): Int {
        if (currentColor !in 0..4) return currentColor
        return pending.filter { it.slot == slot && it.button in 0..2 }.fold(currentColor) { color, click ->
            TerminalSolver.rubixAdvance(color, if (click.button == 1) 1 else 0)
        }
    }

    fun complete(nowMs: Long): List<PendingClick> {
        if (cancelled || restartAtMs != null) return emptyList()
        val discarded = pending.toList()
        pending.clear()
        restartAtMs = nowMs + RESTART_DELAY_MS
        return discarded
    }

    fun restartDue(nowMs: Long): Boolean = !cancelled && restartAtMs?.let { nowMs >= it } == true
    fun remainingMs(nowMs: Long): Long? = restartAtMs?.let { (it - nowMs).coerceAtLeast(0) }
    fun cancel(): List<PendingClick> {
        val discarded = pending.toList()
        cancelled = true; pending.clear(); restartAtMs = null; lastQueued = null
        return discarded
    }

    companion object {
        const val RESTART_DELAY_MS = 1000L

        /** Latch an initial target from actual board colors, without global predictions. */
        fun initialRubixTarget(colors: List<Int>): Int? {
            if (colors.size != 9 || colors.any { it !in 0..4 }) return null
            return (0..4).minByOrNull { target -> colors.sumOf { TerminalSolver.rubixDistance(it, target) } }
        }

        /** Practice left clicks take the shortest route; explicit inputs keep their direction. */
        fun effectiveRubixButton(requestedButton: Int, source: String, currentColor: Int, targetColor: Int?): Int {
            if (requestedButton != 0 || (source != "manual" && source != "keyboard") ||
                currentColor !in 0..4 || targetColor == null || targetColor !in 0..4) return requestedButton
            return TerminalSolver.rubixButton(currentColor, targetColor)
        }
    }
}
