package com.asthoonlite.dungeon

import com.google.gson.GsonBuilder
import java.util.ArrayDeque

/** Immutable records let the input thread hand work to the file writer. */
internal data class TerminalCaptureEvent(
    val schema: Int = 1,
    val sessionId: String,
    val sequence: Long,
    val type: String,
    val monotonicNs: Long,
    val elapsedNs: Long,
    val fields: Map<String, Any?>
)

/** Bounded, nonblocking input queue; lost ranges are explicit records. */
internal class TerminalCaptureStream(
    private val sessionId: String,
    private val startedNs: Long,
    private val capacity: Int = 8192
) {
    init { require(capacity >= 2) }

    private val pending = ArrayDeque<TerminalCaptureEvent>()
    private var sequence = 0L
    private var firstLost: TerminalCaptureEvent? = null
    private var lastLost: TerminalCaptureEvent? = null
    private var lossCount = 0L
    private var closed = false
    var totalLosses: Long = 0L
        private set

    @Synchronized
    fun offer(type: String, atNs: Long, fields: Map<String, Any?>): Long {
        if (closed) return -1
        val record = TerminalCaptureEvent(sessionId = sessionId, sequence = ++sequence,
            type = type, monotonicNs = atNs, elapsedNs = atNs - startedNs, fields = fields)
        if (firstLost != null && pending.size < capacity) pending.addLast(lossRecord())
        if (pending.size < capacity) pending.addLast(record) else {
            if (firstLost == null) firstLost = record
            lastLost = record
            lossCount++
            totalLosses++
        }
        return record.sequence
    }

    @Synchronized
    fun poll(): TerminalCaptureEvent? {
        if (pending.isNotEmpty()) return pending.removeFirst()
        // Closing never discards the last overflow range just because no
        // subsequent input arrived to make room for its marker.
        if (closed && firstLost != null) return lossRecord()
        return null
    }

    @Synchronized
    fun close() { closed = true }

    @Synchronized
    fun drained(): Boolean = closed && pending.isEmpty() && firstLost == null

    private fun lossRecord(): TerminalCaptureEvent {
        val first = requireNotNull(firstLost)
        val last = requireNotNull(lastLost)
        val result = first.copy(type = "events_lost", fields = linkedMapOf(
            "count" to lossCount, "firstSequence" to first.sequence,
            "lastSequence" to last.sequence, "firstMonotonicNs" to first.monotonicNs,
            "lastMonotonicNs" to last.monotonicNs
        ))
        firstLost = null
        lastLost = null
        lossCount = 0
        return result
    }
}

internal object TerminalCaptureFormat {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    fun jsonLine(event: TerminalCaptureEvent): String = gson.toJson(event)
}
