package com.asthoonlite.overlay

import com.asthoonlite.config.Config
import com.asthoonlite.render.MapCanvas
import com.asthoonlite.render.MapOp
import com.asthoonlite.render.drawArrow
import com.asthoonlite.render.drawFaceFallback
import com.asthoonlite.render.replay
import net.minecraft.world.entity.player.PlayerSkin
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.AffineTransform
import javax.swing.JPanel
import javax.swing.JWindow

/**
 * A separate, always-on-top window that draws the dungeon map outside the
 * game window.
 *
 * Why this exists: a window capture (Discord screen share, OBS window
 * capture) records exactly one window. Anything drawn inside the game window
 * is in the recording. This window is a different window, so it is not.
 *
 * Threading: the game's render thread publishes a finished frame as a single
 * reference write; only the Swing thread ever touches AWT state. [publish]
 * and [hide] are called from the render thread and both bounce onto the EDT.
 *
 * Known limits (deliberate, for now): the window is not click-through, so it
 * sits at the top-left of the screen rather than under the crosshair, and
 * player faces are drawn as labelled boxes rather than skin textures — skin
 * pixels live on the GPU and would need a readback to reach Java2D.
 */
object MapOverlayWindow {

    /** Left/top inset for the map inside the window. */
    const val PAD = 8
    /** Extra room below the map so teammate names do not clip. */
    const val PAD_BOTTOM = 16

    /** Replaced wholesale by the render thread, read wholesale by the EDT. */
    @Volatile private var ops: List<MapOp> = emptyList()

    /** Created on the EDT, published as a volatile reference. */
    @Volatile private var window: OverlayWindow? = null

    fun enabled(): Boolean = Config.dungeonMapExternalWindow

    /**
     * Publish a finished frame. [mapW] / [mapH] are the map's own dimensions;
     * the window is sized to those plus the padding drawn around them.
     * Safe to call from the render thread every frame.
     */
    fun publish(frame: List<MapOp>, mapW: Int, mapH: Int) {
        ops = frame
        val wantW = mapW + PAD * 2
        val wantH = mapH + PAD + PAD_BOTTOM

        val existing = window
        if (existing != null && existing.width == wantW && existing.height == wantH) {
            if (!existing.isVisible) placeAndShow(existing, wantW, wantH)
            else existing.panel.repaint()   // repaint() is legal from any thread
            return
        }
        EventQueue.invokeLater {
            val current = window ?: OverlayWindow(wantW, wantH).also { window = it }
            placeAndShow(current, wantW, wantH)
        }
    }

    /** Hide the window — the map is not drawing right now. */
    fun hide() {
        val current = window ?: return          // volatile read: no lock, no EDT hop
        EventQueue.invokeLater { if (current.isVisible) current.isVisible = false }
    }

    private fun placeAndShow(w: OverlayWindow, width: Int, height: Int) {
        if (w.width != width || w.height != height) w.setSize(width, height)
        if (!w.isVisible) {
            w.location = defaultLocation(width, height)
            w.isVisible = true
        }
    }

    private fun defaultLocation(w: Int, h: Int): Point {
        val screen = Toolkit.getDefaultToolkit().screenSize
        // Top-left: well clear of the crosshair at the centre of the screen.
        return Point(
            20.coerceAtMost(screen.width - w - 20).coerceAtLeast(0),
            20.coerceAtMost(screen.height - h - 20).coerceAtLeast(0)
        )
    }

    // ── AWT side ───────────────────────────────────────────────────────────

    private class OverlayPanel : JPanel() {
        init {
            isOpaque = true
            background = Color.BLACK
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val frame = ops
            if (frame.isEmpty()) return
            val g2 = g as Graphics2D
            val saved = g2.transform
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                replay(frame, J2dMapCanvas(g2, g2.fontMetrics))
            } finally {
                // The frame is balanced push/pop, but a half-written or future
                // version must not leave the surface transform rotated.
                g2.transform = saved
            }
        }
    }

    private class OverlayWindow(w: Int, h: Int) : JWindow(null as java.awt.Frame?) {
        val panel = OverlayPanel()

        init {
            contentPane.add(panel)
            focusableWindowState = false   // clicking it must not pull focus from the game
            isAlwaysOnTop = true
            background = Color.BLACK
            setSize(w, h)
        }
    }
}

/**
 * Replays recorded map ops onto a Java2D surface.
 *
 * The transform calls mirror `Matrix3x2fStack` semantics: JOML's `rotate` and
 * Java2D's `AffineTransform.rotate` both apply the same [[cos, -sin], [sin,
 * cos]] matrix, so arrows come out pointing the same way they do in game.
 *
 * One deviation from the in-game path, by design: player skins cannot be read
 * back from the GPU cheaply, so a face becomes a bordered box with the
 * player's initial. Layout, position and colour still match exactly.
 */
class J2dMapCanvas(
    private val g: Graphics2D,
    private val metrics: FontMetrics
) : MapCanvas {

    private val stack = ArrayDeque<AffineTransform>(16)
    private val font = Font(Font.SANS_SERIF, Font.PLAIN, FONT_PX)

    override fun push() { stack.addLast(g.transform) }
    override fun pop() { if (stack.isNotEmpty()) g.transform = stack.removeLast() }
    override fun translate(x: Float, y: Float) { g.translate(x.toDouble(), y.toDouble()) }
    override fun scale(sx: Float, sy: Float) { g.scale(sx.toDouble(), sy.toDouble()) }
    override fun rotate(rad: Float) { g.rotate(rad.toDouble()) }

    override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        g.color = argbOf(argb)
        g.fillRect(
            minOf(x1, x2), minOf(y1, y2),
            kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)
        )
    }

    override fun text(text: String, x: Int, y: Int, argb: Int, centered: Boolean) {
        g.font = font
        g.color = argbOf(argb)
        val drawX = if (centered) x - metrics.stringWidth(text) / 2 else x
        g.drawString(text, drawX, y + metrics.ascent)
    }

    override fun marker(isSelf: Boolean, w: Int, h: Int, markerScale: Float, tint: Int) {
        // No atlas on this backend — the shared hand-built arrow, identical
        // geometry to the in-game fallback.
        drawArrow(this, isSelf, markerScale, tint)
    }

    override fun face(label: String, skin: PlayerSkin?, x: Int, y: Int, size: Int, borderArgb: Int) {
        // skin is deliberately unused here: see the class doc.
        g.color = argbOf(borderArgb)
        g.fillRect(x - 2, y - 2, size + 4, size + 4)
        drawFaceFallback(this, label, x, y, size)
    }

    private fun argbOf(argb: Int): Color =
        Color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, argb ushr 24)

    private companion object {
        /** Matches `Font#lineHeight`, which drives the map's own layout. */
        const val FONT_PX = 9
    }
}
