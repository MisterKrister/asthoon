package com.asthoonlite.dungeon.simulator

/** One practice screen's delayed inputs and one-second restart, on a monotonic clock. */
internal class TerminalPracticeClock {
    data class PendingClick(val id: Long, val slot: Int, val button: Int, val source: String,
                            val requestedAtNs: Long, val dueAtMs: Long)
    private val pending = ArrayDeque<PendingClick>()
    private var restartAtMs: Long? = null
    private var cancelled = false
    private var nextId = 0L
    var lastQueued: PendingClick? = null
        private set

    fun enqueue(slot: Int, button: Int, source: String, nowMs: Long, pingMs: Int,
                requestedAtNs: Long = nowMs * 1_000_000L): Boolean {
        if (cancelled || restartAtMs != null) return false
        val click = PendingClick(++nextId, slot, button, source, requestedAtNs, nowMs + pingMs.coerceIn(0, 500))
        lastQueued = click
        pending.addLast(click)
        return true
    }

    fun poll(nowMs: Long): PendingClick? =
        if (!cancelled && pending.firstOrNull()?.dueAtMs?.let { it <= nowMs } == true) pending.removeFirst() else null

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

    companion object { const val RESTART_DELAY_MS = 1000L }
}
