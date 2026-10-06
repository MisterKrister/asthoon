package com.asthoonlite.dungeon

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure flight state. Sampling and retargeting use the same position calculation.
 *
 * A flight is three stretches, because that is how a hand crosses a terminal:
 *
 *  1. **dwell** — nothing moves. The hand is still looking at the pane it just
 *     clicked. Implemented as an offset on the start timestamp rather than as a
 *     phase, so [position] before the start simply reads as progress zero and
 *     there is no second branch to get wrong.
 *  2. **travel** — the bezier runs from where the pointer was to [Flight.over],
 *     which is the target itself, or a little past it when the feel calls for
 *     an overshoot. The arc, the tremor and the easing all live here.
 *  3. **settle** — a short correction back from the overshoot point onto
 *     [Flight.to]. Eased with a smoothstep rather than the configurable curve:
 *     the landing curve describes how a pointer *arrives*, and a correction is
 *     already arriving, so replaying it would start slow and creep.
 *
 * [Flight.to] is where the pointer finally rests; [Flight.over] is an
 * implementation detail of the middle stretch and is never handed out.
 */
internal class CursorMotion {
    data class Point(val x: Float, val y: Float)
    data class Ease(val x1: Float = 0.2f, val y1: Float = 0f, val x2: Float = 0f, val y2: Float = 1f)

    private data class Flight(
        val from: Point,
        /** Where the travel bezier actually ends — past the target when overshooting. */
        val over: Point,
        /** Where the pointer comes to rest. */
        val to: Point,
        val control: Point,
        /** Absolute time the flight starts, including any dwell. */
        val start: Long,
        val travelMs: Long,
        val settleMs: Long,
        val ease: Ease,
        val tremor: Float,
        val phaseX: Float,
        val phaseY: Float
    ) {
        val duration: Long get() = travelMs + settleMs
    }

    private var resting = Point(0f, 0f)
    private var flight: Flight? = null

    fun reset(point: Point) { resting = point; flight = null }

    fun moving(now: Long): Boolean = flight?.let { now - it.start < it.duration } == true

    fun position(now: Long): Point {
        val f = flight ?: return resting
        val elapsed = (now - f.start).coerceAtLeast(0L)
        if (elapsed >= f.duration) return f.to
        if (elapsed >= f.travelMs) {
            val s = smoothstep(((elapsed - f.travelMs).toFloat() / f.settleMs.coerceAtLeast(1L)).coerceIn(0f, 1f))
            return Point(f.over.x + (f.to.x - f.over.x) * s, f.over.y + (f.to.y - f.over.y) * s)
        }
        val t = TerminalCursor.progressAt(elapsed, f.travelMs, f.ease.x1, f.ease.y1, f.ease.x2, f.ease.y2)
        // Zero at both ends, including the first frame of a retarget.
        val tremor = f.tremor * (1f - t) * (t * 8f).coerceAtMost(1f)
        return Point(
            TerminalCursor.bezier(f.from.x, f.control.x, f.over.x, t) + sin(t * 9f + f.phaseX) * 1.4f * tremor,
            TerminalCursor.bezier(f.from.y, f.control.y, f.over.y, t) + cos(t * 7f + f.phaseY) * 1.4f * tremor
        )
    }

    /** 0..1, flat at both ends: the correction starts from rest and stops on it. */
    private fun smoothstep(s: Float): Float = s * s * (3f - 2f * s)

    /**
     * Repeated observations of the same target do not restart a flight.
     *
     * [overshootPx] carries the pointer past the target along the line of
     * travel, [settleMs] is how long the correction back takes (and is ignored
     * when there is nothing to correct), and [dwellMs] holds the pointer where
     * it is before anything moves.
     */
    fun glideTo(
        target: Point,
        now: Long,
        duration: Long,
        bow: Float,
        ease: Ease,
        tremor: Float,
        phaseX: Float,
        phaseY: Float,
        overshootPx: Float = 0f,
        settleMs: Long = 0L,
        dwellMs: Long = 0L
    ): Boolean {
        if (flight?.to == target) return false
        val from = position(now)
        val dx = target.x - from.x
        val dy = target.y - from.y
        val distance = hypot(dx, dy)
        val over = if (overshootPx > 0f && distance > 0.5f) {
            Point(target.x + dx / distance * overshootPx, target.y + dy / distance * overshootPx)
        } else {
            target
        }
        val odx = over.x - from.x
        val ody = over.y - from.y
        val control = if (hypot(odx, ody) < 0.5f) from else Point(
            (from.x + over.x) / 2f - ody * bow,
            (from.y + over.y) / 2f + odx * bow
        )
        flight = Flight(
            from, over, target, control,
            now + dwellMs.coerceAtLeast(0L),
            duration.coerceAtLeast(1L),
            settleMs.coerceAtLeast(0L).let { if (over === target) 0L else it },
            ease, tremor, phaseX, phaseY
        )
        return true
    }
}
