package com.asthoonlite.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.sqrt

/**
 * Filled rounded rectangles, built out of one `fill` per row.
 *
 * The GUI layer has no rounded primitive and no sprite for one, but a rounded
 * rectangle is just a rectangle whose corner rows are cut back by the chord of
 * a circle — and a chord is a `sqrt`, not a texture. Doing it this way means
 * the terminal solver's tiles are the same colour on every machine and cost
 * nothing to load, and it means the radius is a real arc rather than the
 * chamfer that two extra `fill` calls per corner would give you.
 *
 * Everything about the shape lives in [roundedRectRowInset], which is pure and
 * pinned by the regression harness; this file only turns those insets into
 * draw calls.
 */

/**
 * How far row [row] of a rounded rectangle is cut back on each side.
 *
 * [height] and [radius] are in pixels; the result is a non-negative number of
 * pixels of inset. Rows in the middle of the rectangle are not cut at all, and
 * the first and last rows are cut back by the full radius, which is what puts
 * the arc on the corners instead of on the edges.
 *
 * The radius is clamped to half the height so a rectangle that is shorter than
 * it is wide still produces something convex rather than two disjoint arcs —
 * a caller handing in a radius of 40 for an 8px tile gets a capsule, not a
 * crash and not a hole.
 */
fun roundedRectRowInset(row: Int, height: Int, radius: Int): Int {
    if (height <= 0) return 0
    val r = radius.coerceIn(0, height / 2)
    if (r <= 0) return 0
    if (row < 0 || row >= height) return 0

    val dy = when {
        row < r -> r - row
        row >= height - r -> row - (height - 1 - r)
        else -> return 0
    }
    if (dy >= r) return r

    // Chord half-width at this height, floored: rounding the cut *out* leaves
    // one extra pixel rather than one short, so the arc never eats into the
    // rectangle's own fill.
    val halfWidth = sqrt((r * r - dy * dy).toDouble()).toInt()
    return r - halfWidth
}

/**
 * Fills the rectangle (x0, y0) to (x1, y1) with rounded corners of [radius].
 *
 * [radius] 0 is the plain rectangle, so callers do not need to branch. The
 * rectangle is cut row by row; x is never cut on the flat middle rows, which
 * is what keeps a tile's left and right edges aligned with its neighbours.
 */
fun fillRoundedRect(
    graphics: GuiGraphicsExtractor,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    argb: Int,
    radius: Int
) {
    val w = x1 - x0
    val h = y1 - y0
    if (w <= 0 || h <= 0) return
    if (radius <= 0) {
        graphics.fill(x0, y0, x1, y1, argb)
        return
    }
    val alpha = (argb ushr 24) and 0xFF
    val rgb = argb and 0x00FFFFFF
    val aaColor = (((alpha * 38) / 100) shl 24) or rgb

    for (row in 0 until h) {
        val inset = roundedRectRowInset(row, h, radius)
        val left = x0 + inset
        val right = x1 - inset
        if (right <= left) continue
        graphics.fill(left, y0 + row, right, y0 + row + 1, argb)
        if (inset > 0) {
            // Anti-aliased corner edge pixels to soften stair-step cuts
            graphics.fill(left - 1, y0 + row, left, y0 + row + 1, aaColor)
            graphics.fill(right, y0 + row, right + 1, y0 + row + 1, aaColor)
        }
    }
}
