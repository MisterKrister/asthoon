package com.asthoonlite.render

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin

/**
 * The drawing vocabulary the dungeon map speaks.
 *
 * The map's layout maths lives in one place ([com.asthoonlite.dungeon.DungeonMap])
 * and never knows where its output goes. Two things consume it:
 *
 *  - [HudMapCanvas] — straight into the game's own HUD, which is what a
 *    window capture of the game window will see.
 *  - [RecordMapCanvas] — an ordered list of [MapOp]s, which the external
 *    overlay window replays through `J2dMapCanvas` on the Swing thread. A
 *    window capture never sees that window, because it is a different window.
 *
 * One layout implementation, two painters: there is no second copy of the map
 * geometry to fall out of date.
 */
interface MapCanvas {
    fun push()
    fun pop()
    fun translate(x: Float, y: Float)
    fun scale(sx: Float, sy: Float)
    fun rotate(rad: Float)
    fun fill(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int)

    /** [centered] matches `GuiGraphicsExtractor#centeredText`. */
    fun text(text: String, x: Int, y: Int, argb: Int, centered: Boolean)

    /**
     * Direction marker drawn at the current origin — already rotated by the
     * enclosing transform, so callers push/translate/rotate first.
     * [markerScale] is kept so a backend with no atlas can rebuild the exact
     * same arrow the atlas sprite would have shown.
     */
    fun marker(isSelf: Boolean, w: Int, h: Int, markerScale: Float, tint: Int)

    /** A player's face box: top-left (x, y), [size] square, [borderArgb] frame. */
    fun face(label: String, skin: PlayerSkin?, x: Int, y: Int, size: Int, borderArgb: Int)
    /** Bundled map art, with its native texture dimensions. */
    fun image(path: String, x: Int, y: Int, size: Int, textureSize: Int, tint: Int = -1)
}

// ── Recorded form ──────────────────────────────────────────────────────────

/**
 * One drawing step, frozen as data. Deliberately a flat list rather than a
 * tree: the map draws a few hundred primitives a frame and the whole list is
 * published to the Swing thread as a single reference write, so the EDT can
 * never observe a half-built frame.
 */
sealed interface MapOp {
    fun apply(target: MapCanvas)

    data object Push : MapOp { override fun apply(t: MapCanvas) = t.push() }
    data object Pop : MapOp { override fun apply(t: MapCanvas) = t.pop() }
    data class Translate(val x: Float, val y: Float) : MapOp { override fun apply(t: MapCanvas) = t.translate(x, y) }
    data class Scale(val sx: Float, val sy: Float) : MapOp { override fun apply(t: MapCanvas) = t.scale(sx, sy) }
    data class Rotate(val rad: Float) : MapOp { override fun apply(t: MapCanvas) = t.rotate(rad) }
    data class Fill(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val argb: Int) : MapOp {
        override fun apply(t: MapCanvas) = t.fill(x1, y1, x2, y2, argb)
    }
    data class Text(val text: String, val x: Int, val y: Int, val argb: Int, val centered: Boolean) : MapOp {
        override fun apply(t: MapCanvas) = t.text(text, x, y, argb, centered)
    }
    data class Marker(
        val isSelf: Boolean, val w: Int, val h: Int,
        val markerScale: Float, val tint: Int
    ) : MapOp {
        override fun apply(t: MapCanvas) = t.marker(isSelf, w, h, markerScale, tint)
    }
    data class Face(
        val label: String, val skin: PlayerSkin?,
        val x: Int, val y: Int, val size: Int, val borderArgb: Int
    ) : MapOp {
        override fun apply(t: MapCanvas) = t.face(label, skin, x, y, size, borderArgb)
    }
    data class Image(val path: String, val x: Int, val y: Int, val size: Int, val textureSize: Int, val tint: Int) : MapOp {
        override fun apply(t: MapCanvas) = t.image(path, x, y, size, textureSize, tint)
    }
}

/** Replays a recorded frame into any other canvas. */
fun replay(ops: List<MapOp>, target: MapCanvas) {
    for (op in ops) op.apply(target)
}

/** Records every call as a [MapOp] instead of drawing it. */
class RecordMapCanvas : MapCanvas {
    val ops = ArrayList<MapOp>(256)
    override fun push() { ops.add(MapOp.Push) }
    override fun pop() { ops.add(MapOp.Pop) }
    override fun translate(x: Float, y: Float) { ops.add(MapOp.Translate(x, y)) }
    override fun scale(sx: Float, sy: Float) { ops.add(MapOp.Scale(sx, sy)) }
    override fun rotate(rad: Float) { ops.add(MapOp.Rotate(rad)) }
    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) { ops.add(MapOp.Fill(x1, y1, x2, y2, argb)) }
    override fun text(text: String, x: Int, y: Int, argb: Int, centered: Boolean) { ops.add(MapOp.Text(text, x, y, argb, centered)) }
    override fun marker(isSelf: Boolean, w: Int, h: Int, markerScale: Float, tint: Int) { ops.add(MapOp.Marker(isSelf, w, h, markerScale, tint)) }
    override fun face(label: String, skin: PlayerSkin?, x: Int, y: Int, size: Int, borderArgb: Int) {
        ops.add(MapOp.Face(label, skin, x, y, size, borderArgb))
    }
    override fun image(path: String, x: Int, y: Int, size: Int, textureSize: Int, tint: Int) {
        ops.add(MapOp.Image(path, x, y, size, textureSize, tint))
    }
}

// ── In-game backend ────────────────────────────────────────────────────────

/**
 * Thin passthrough to the HUD's own extractor. Every method is a direct
 * translation of what the map used to call on `GuiGraphicsExtractor`, so the
 * in-game output is byte-for-byte what it was before the canvas existed.
 */
class HudMapCanvas(private val context: GuiGraphicsExtractor) : MapCanvas {

    private val font: Font get() = Minecraft.getInstance().font
    private val markerAtlas = Identifier.fromNamespaceAndPath("asthoonlite", "textures/map/marker_atlas.png")

    override fun push() { context.pose().pushMatrix() }
    override fun pop() { context.pose().popMatrix() }
    override fun translate(x: Float, y: Float) { context.pose().translate(x, y) }
    override fun scale(sx: Float, sy: Float) { context.pose().scale(sx, sy) }
    override fun rotate(rad: Float) { context.pose().rotate(rad) }
    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) { context.fill(x1, y1, x2, y2, argb) }

    override fun text(text: String, x: Int, y: Int, argb: Int, centered: Boolean) {
        if (centered) context.centeredText(font, text, x, y, argb)
        else context.text(font, text, x, y, argb)
    }

    override fun marker(isSelf: Boolean, w: Int, h: Int, markerScale: Float, tint: Int) {
        val u = if (isSelf) 0f else 20f
        try {
            context.blit(
                RenderPipelines.GUI_TEXTURED,
                markerAtlas,
                -w / 2, -h / 2,
                u, 0f,
                w, h,
                20, 28,
                40, 56,
                tint
            )
        } catch (_: Throwable) {
            // Atlas missing or the pipeline rejected it — same hand-drawn
            // arrow the external window uses, so both backends match.
            drawArrow(this, isSelf, markerScale, tint)
        }
    }

    override fun face(label: String, skin: PlayerSkin?, x: Int, y: Int, size: Int, borderArgb: Int) {
        context.fill(x - 2, y - 2, x + size + 2, y + size + 2, borderArgb)
        if (skin != null) {
            net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(context, skin, x, y, size)
        } else {
            drawFaceFallback(this, label, x, y, size)
        }
    }
    override fun image(path: String, x: Int, y: Int, size: Int, textureSize: Int, tint: Int) {
        context.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("asthoonlite", path),
            x, y, 0f, 0f, size, size, textureSize, textureSize, textureSize, textureSize, tint)
    }
}

// ── Shared primitives (backend-agnostic) ───────────────────────────────────

/**
 * Hand-built arrow: a dark outline under a coloured triangle, apex pointing
 * along -Y. This is what a backend without access to the atlas sprite draws,
 * and what the in-game canvas falls back to if the blit throws.
 *
 * Geometry is driven purely by [markerScale] so it tracks the same value the
 * atlas path was given — no independent tuning constants.
 */
fun drawArrow(target: MapCanvas, isSelf: Boolean, markerScale: Float, color: Int) {
    val arrowColor = color
    val arrow = (markerScale * 4.0).coerceIn(2.0, 5.0)
    for (row in -arrow.toInt()..arrow.toInt()) {
        val half = ((row + arrow) * 0.45).toInt() + 1
        target.fill(-half, row, half + 1, row + 1, 0xFF000000.toInt())
    }
    for (row in (-arrow * 0.85).toInt()..(arrow * 0.85).toInt()) {
        val half = ((row + arrow) * 0.38).toInt()
        target.fill(-half, row, half + 1, row + 1, arrowColor)
    }
}

/** Solid box plus initial, used when there is no skin to show. */
fun drawFaceFallback(target: MapCanvas, label: String, x: Int, y: Int, size: Int) {
    target.fill(x, y, x + size, y + size, 0xFF3B4658.toInt())
    val letter = label.take(1).uppercase()
    if (letter.isNotEmpty() && letter.isNotBlank()) {
        target.text(letter, x + size / 2, y + size / 3, 0xFFFFFFFF.toInt(), centered = true)
    }
}
