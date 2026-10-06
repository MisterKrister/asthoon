package com.asthoonlite.dungeon

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pure flight state. Sampling and retargeting use the same position calculation. */
internal class CursorMotion {
    data class Point(val x: Float, val y: Float)
    data class Ease(val x1: Float = 0.2f, val y1: Float = 0f, val x2: Float = 0f, val y2: Float = 1f)
    private data class Flight(
        val from: Point, val control: Point, val to: Point, val start: Long, val duration: Long,
        val ease: Ease, val tremor: Float, val phaseX: Float, val phaseY: Float
    )

    private var resting = Point(0f, 0f)
    private var flight: Flight? = null

    fun reset(point: Point) { resting = point; flight = null }
    fun moving(now: Long): Boolean = flight?.let { now - it.start < it.duration } == true

    fun position(now: Long): Point {
        val f = flight ?: return resting
        val t = TerminalCursor.progressAt(now - f.start, f.duration, f.ease.x1, f.ease.y1, f.ease.x2, f.ease.y2)
        // Zero at both ends, including the first frame of a retarget.
        val tremor = f.tremor * (1f - t) * (t * 8f).coerceAtMost(1f)
        return Point(
            TerminalCursor.bezier(f.from.x, f.control.x, f.to.x, t) + sin(t * 9f + f.phaseX) * 1.4f * tremor,
            TerminalCursor.bezier(f.from.y, f.control.y, f.to.y, t) + cos(t * 7f + f.phaseY) * 1.4f * tremor
        )
    }

    /** Repeated observations of the same target do not restart a flight. */
    fun glideTo(target: Point, now: Long, duration: Long, bow: Float, ease: Ease, tremor: Float, phaseX: Float, phaseY: Float): Boolean {
        if (flight?.to == target) return false
        val from = position(now)
        val dx = target.x - from.x
        val dy = target.y - from.y
        val distance = hypot(dx, dy)
        val control = if (distance < 0.5f) from else Point(
            (from.x + target.x) / 2f - dy * bow,
            (from.y + target.y) / 2f + dx * bow
        )
        flight = Flight(from, control, target, now, duration.coerceAtLeast(1L), ease, tremor, phaseX, phaseY)
        return true
    }
}
