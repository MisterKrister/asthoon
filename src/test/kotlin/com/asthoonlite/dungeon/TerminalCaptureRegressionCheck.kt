package com.asthoonlite.dungeon

import com.google.gson.JsonParser

/** Input ordering, overflow and JSON precision must survive dense sessions. */
internal fun terminalCaptureRegressionChecks() {
    val start = 9_007_199_254_740_993L
    val stream = TerminalCaptureStream("practice", start, 2)
    stream.offer("mouse_move", start, mapOf("x" to 0.0))
    stream.offer("mouse_button", start + 1, mapOf("button" to 0))
    stream.offer("mouse_button", start + 2, mapOf("button" to 0))
    stream.offer("key", start + 3, mapOf("key" to 256))
    check(stream.totalLosses == 2L) { "a bounded capture queue must count every lost event" }
    val first = requireNotNull(stream.poll())
    check(first.sequence == 1L && first.elapsedNs == 0L)
    check(requireNotNull(stream.poll()).sequence == 2L) { "rapid same-button events must not be deduplicated" }
    stream.offer("mouse_move", start + 4, mapOf("x" to 0.5))
    val lost = requireNotNull(stream.poll())
    check(lost.type == "events_lost" && lost.sequence == 3L)
    check(lost.fields["count"] == 2L && lost.fields["lastSequence"] == 4L)
    check(requireNotNull(stream.poll()).sequence == 5L) { "overflow markers must precede later input" }
    val json = JsonParser.parseString(TerminalCaptureFormat.jsonLine(first)).asJsonObject
    check(json["monotonicNs"].asLong == start) { "nanosecond timestamps must remain exact JSON integers" }
    check(json["fields"].asJsonObject["x"].asDouble == 0.0)
    check(TerminalCaptureFormat.jsonLine(first.copy(fields = mapOf("label" to "a\nb\"c\\d", "slot" to null)))
        .count { it == '\n' } == 0) { "JSONL strings must never introduce a second record line" }

    val stopped = TerminalCaptureStream("stopped", 0L, 2)
    repeat(4) { stopped.offer("move", it.toLong(), emptyMap()) }
    stopped.close()
    check(stopped.offer("after_close", 5L, emptyMap()) == -1L)
    val tail = generateSequence { stopped.poll() }.toList()
    check(tail.map { it.sequence } == listOf(1L, 2L, 3L))
    check(tail.last().type == "events_lost" && tail.last().fields["count"] == 2L)
    check(stopped.drained()) { "stop must drain queued input and its final loss marker" }
}
