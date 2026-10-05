package com.asthoonlite.render

import net.minecraft.world.level.material.MapColor

/** One horizontal run of vanilla map pixels, already in panel coordinates. */
data class BaseSpan(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val argb: Int)

private const val VANILLA_MAP_SIZE = 128

/**
 * Run-length decode of the vanilla 128×128 map colour array into panel-space
 * rectangles — the "legit base" the dungeon map draws underneath its own
 * overlay, so the panel is the real map item's image instead of a redrawn
 * approximation of it.
 *
 * The source rect is the dungeon as it sits inside the map image
 * (`mapOffsetX/Z`, six room-gaps wide) and the destination rect is the map
 * panel's grid area. Those two are the exact same endpoints the marker
 * transform rescales between, so a head drawn on the panel lands on the same
 * pixel of the image as the decoration that produced it — there is deliberately
 * no half-cell term here, unlike the marker path's `+ cellW / 2`, because a
 * span is an area between two edges rather than a point at a cell centre.
 *
 * Rows are scaled with the same rounding on both edges (`round(z * k)` for the
 * top, `round((z + 1) * k)` for the bottom), so consecutive rows always meet
 * exactly: scaling below 1× would otherwise collapse some rows to zero height
 * and leave hairline gaps. Horizontal runs are rounded the same way. Empty
 * pixels (packed id 0) are skipped — the panel background shows through,
 * exactly like a real map item. A source rect that runs off the 128×128 image
 * is clipped to the pixels that exist rather than discarded, so the scale stays
 * anchored to the rect's own origin.
 *
 * Pure maths on a byte array; no game state, so the regression harness
 * exercises it directly.
 *
 * @param colors full 128×128 packed map colour array
 * @param srcX0  dungeon left edge in map pixels
 * @param srcZ0  dungeon top edge in map pixels
 * @param srcSize dungeon extent in map pixels (six room-gaps)
 * @param dstX0  panel x the dungeon's left edge maps to
 * @param dstY0  panel y the dungeon's top edge maps to
 * @param dstSize panel extent the dungeon stretches across
 */
fun mapBaseSpans(
    colors: ByteArray,
    srcX0: Int,
    srcZ0: Int,
    srcSize: Int,
    dstX0: Int,
    dstY0: Int,
    dstSize: Int
): List<BaseSpan> {
    val spans = ArrayList<BaseSpan>(512)
    if (srcSize <= 0 || dstSize <= 0 || colors.size < VANILLA_MAP_SIZE * VANILLA_MAP_SIZE) return spans

    // The source rect is the six-room-gap lattice anchored at the floor's
    // entrance, and it can hang off the right and bottom edges of the 128×128
    // image — the lattice origin is wherever the entrance happened to land, so
    // plenty of floors start at x=16 or x=26 and run past the edge. Clipping
    // to the pixels that actually exist keeps the scale anchored to the
    // *unclipped* origin, so what is there still lands exactly where the
    // marker transform puts it. Rejecting the whole rect (the old behaviour)
    // meant those floors silently lost the image and fell back to the redrawn
    // grid.
    val xBegin = srcX0.coerceAtLeast(0)
    val zBegin = srcZ0.coerceAtLeast(0)
    val xEnd = (srcX0 + srcSize).coerceAtMost(VANILLA_MAP_SIZE)
    val zEnd = (srcZ0 + srcSize).coerceAtMost(VANILLA_MAP_SIZE)
    if (xEnd <= xBegin || zEnd <= zBegin) return spans

    val k = dstSize.toDouble() / srcSize

    for (z in zBegin until zEnd) {
        // Distance from the rect's own top edge, which is what maps to panel y.
        val zr = z - srcZ0
        val mapRow = z * VANILLA_MAP_SIZE
        // Math.round(Double) is Long — spans are panel pixels, which are Int.
        val y0 = dstY0 + Math.round(zr * k).toInt()
        val y1 = dstY0 + Math.round((zr + 1) * k).toInt()
        if (y1 <= y0) continue

        var x = xBegin
        while (x < xEnd) {
            val packed = colors[mapRow + x].toInt() and 0xFF
            var xe = x + 1
            while (xe < xEnd && (colors[mapRow + xe].toInt() and 0xFF) == packed) {
                xe++
            }
            if (packed != 0) {
                val px0 = dstX0 + Math.round((x - srcX0) * k).toInt()
                val px1 = dstX0 + Math.round((xe - srcX0) * k).toInt()
                if (px1 > px0) {
                    val argb = MapColor.getColorFromPackedId(packed)
                    if (argb != 0) spans.add(BaseSpan(px0, y0, px1, y1, argb))
                }
            }
            x = xe
        }
    }
    return spans
}
