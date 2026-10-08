package com.asthoonlite.overlay

import com.asthoonlite.AsthoonLite
import com.mojang.blaze3d.platform.Window
import com.asthoonlite.render.*
import net.minecraft.world.entity.player.PlayerSkin
import org.lwjgl.glfw.GLFW
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.JWindow
import kotlin.math.ceil
import kotlin.math.floor

/** The render thread publishes a complete frame; all AWT access stays on the EDT. */
object MapOverlayWindow {
    data class Placement(val x: Double, val y: Double, val width: Double, val height: Double,
                         val monitorX: Int = 0, val monitorY: Int = 0,
                         val monitorWidth: Int = 0, val monitorHeight: Int = 0,
                         val physical: Boolean = true, val positionAvailable: Boolean = true)
    internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Frame(val ops: List<MapOp>, val size: Int, val placement: Placement)
    @Volatile private var pending: Frame? = null
    private val queued = AtomicBoolean()
    private val warned = AtomicBoolean()
    private var window: OverlayWindow? = null // EDT only
    private var placementRejected = false // EDT only

    internal fun desktopRect(clientX: Int, clientY: Int, windowWidth: Int, windowHeight: Int,
                             guiWidth: Int, guiHeight: Int, x: Int, y: Int, size: Int): Placement {
        val sx = windowWidth.toDouble() / guiWidth.coerceAtLeast(1)
        val sy = windowHeight.toDouble() / guiHeight.coerceAtLeast(1)
        return Placement(clientX + x * sx, clientY + y * sy, size * sx, size * sy)
    }

    internal fun awtBounds(p: Placement, monitorX: Int, monitorY: Int, dpiX: Double, dpiY: Double): Bounds {
        val sx = if (p.physical) dpiX else 1.0
        val sy = if (p.physical) dpiY else 1.0
        val x = monitorX + (p.x - p.monitorX) / sx
        val y = monitorY + (p.y - p.monitorY) / sy
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        return Bounds(left, top, (ceil(x + p.width / sx).toInt() - left).coerceAtLeast(1),
            (ceil(y + p.height / sy).toInt() - top).coerceAtLeast(1))
    }

    fun placement(w: Window, x: Int, y: Int, size: Int): Placement {
        val platform = GLFW.glfwGetPlatform()
        if (platform == GLFW.GLFW_PLATFORM_WAYLAND) {
            warn("Native Wayland does not expose global window coordinates; using top-left placement")
            return desktopRect(0, 0, w.screenWidth, w.screenHeight, w.guiScaledWidth, w.guiScaledHeight, 0, 0, size)
                .copy(positionAvailable = false)
        }
        val px = IntArray(1)
        val py = IntArray(1)
        // GLFW returns the content area's origin already; adding decoration insets again offsets the map.
        GLFW.glfwGetWindowPos(w.handle(), px, py)
        var bestArea = -1L
        var monitorX = 0
        var monitorY = 0
        var monitorWidth = 0
        var monitorHeight = 0
        val monitors = GLFW.glfwGetMonitors()
        if (monitors != null) for (i in 0 until monitors.limit()) {
            val handle = monitors[i]
            val mx = IntArray(1)
            val my = IntArray(1)
            GLFW.glfwGetMonitorPos(handle, mx, my)
            val mode = GLFW.glfwGetVideoMode(handle) ?: continue
            val area = (minOf(px[0] + w.screenWidth, mx[0] + mode.width()) - maxOf(px[0], mx[0])).coerceAtLeast(0).toLong() *
                (minOf(py[0] + w.screenHeight, my[0] + mode.height()) - maxOf(py[0], my[0])).coerceAtLeast(0)
            if (area > bestArea) {
                bestArea = area
                monitorX = mx[0]; monitorY = my[0]
                monitorWidth = mode.width(); monitorHeight = mode.height()
            }
        }
        return desktopRect(px[0], py[0], w.screenWidth, w.screenHeight, w.guiScaledWidth, w.guiScaledHeight, x, y, size)
            .copy(monitorX = monitorX, monitorY = monitorY, monitorWidth = monitorWidth, monitorHeight = monitorHeight,
                physical = platform != GLFW.GLFW_PLATFORM_COCOA)
    }

    fun publish(ops: List<MapOp>, size: Int, placement: Placement) {
        pending = Frame(ops, size, placement)
        schedule()
    }

    fun hide() {
        if (pending == null) return
        pending = null
        schedule()
    }

    private fun schedule() {
        if (!queued.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            queued.set(false)
            val frame = pending
            if (frame == null) { window?.isVisible = false; return@invokeLater }
            try {
                val current = window ?: OverlayWindow().also { window = it }
                val p = frame.placement
                val configs = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
                val config = configs.minByOrNull { c ->
                    val scale = c.defaultTransform
                    kotlin.math.abs(c.bounds.width * scale.scaleX - p.monitorWidth) +
                        kotlin.math.abs(c.bounds.height * scale.scaleY - p.monitorHeight) +
                        kotlin.math.abs(c.bounds.x - p.monitorX) + kotlin.math.abs(c.bounds.y - p.monitorY)
                } ?: current.graphicsConfiguration
                val dpi = config.defaultTransform
                val bounds = awtBounds(p, config.bounds.x, config.bounds.y, dpi.scaleX, dpi.scaleY)
                val followGame = p.positionAvailable && !placementRejected && current.isAlwaysOnTop
                val location = if (followGame) Point(bounds.x, bounds.y) else defaultLocation(bounds.width, bounds.height)
                current.setBounds(location.x, location.y, bounds.width, bounds.height)
                if (pending !== frame) return@invokeLater
                current.panel.frame = frame
                current.isVisible = true
                if (followGame && current.locationOnScreen != location) {
                    placementRejected = true
                    warn("The window manager refused the dungeon map position; using top-left placement")
                    current.location = defaultLocation(bounds.width, bounds.height)
                }
                current.panel.repaint()
            } catch (e: Exception) {
                warn("Dungeon map overlay unavailable: " + e.javaClass.simpleName + ": " + e.message)
                window?.isVisible = false
            }
        }
    }

    private fun warn(message: String) {
        if (warned.compareAndSet(false, true)) AsthoonLite.LOGGER.warn("[AsthoonLite] {}", message)
    }

    private fun defaultLocation(w: Int, h: Int): Point {
        val screen = Toolkit.getDefaultToolkit().screenSize
        return Point(20.coerceAtMost(screen.width - w - 20).coerceAtLeast(0),
            20.coerceAtMost(screen.height - h - 20).coerceAtLeast(0))
    }

    private class OverlayWindow : JWindow(null as java.awt.Frame?) {
        val panel = OverlayPanel()
        init {
            contentPane.add(panel)
            focusableWindowState = false
            if (isAlwaysOnTopSupported) isAlwaysOnTop = true
            else warn("Always-on-top is unsupported; using top-left placement")
            background = Color.BLACK
        }
    }

    private class OverlayPanel : JPanel() {
        var frame: MapOverlayWindow.Frame? = null
        init { isOpaque = true; background = Color.BLACK }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val frame = frame ?: return
            val g2 = g.create() as Graphics2D
            try {
                g2.scale(width.toDouble() / frame.size, height.toDouble() / frame.size)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                replay(frame.ops, J2dMapCanvas(g2, g2.fontMetrics))
            } finally { g2.dispose() }
        }
    }
}

/** Java2D replay uses labelled skin fallbacks, retaining the class border and yaw. */
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
        val textMetrics = g.fontMetrics
        val drawX = if (centered) x - textMetrics.stringWidth(text) / 2 else x
        g.drawString(text, drawX, y + textMetrics.ascent)
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

    override fun image(path: String, x: Int, y: Int, size: Int, textureSize: Int, tint: Int) {
        val image = images.getOrPut(path to tint) {
            val original = requireNotNull(javaClass.getResourceAsStream("/assets/asthoonlite/$path")) { "Missing map image: $path" }
                .use { ImageIO.read(it) }
            if (tint == -1) original else {
                val rgba = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = rgba.createGraphics()
                try { graphics.drawImage(original, 0, 0, null) } finally { graphics.dispose() }
                val factors = floatArrayOf((tint shr 16 and 255) / 255f, (tint shr 8 and 255) / 255f,
                    (tint and 255) / 255f, (tint ushr 24) / 255f)
                java.awt.image.RescaleOp(factors, FloatArray(4), null).filter(rgba, null)
            }
        }
        g.drawImage(image, x, y, size, size, null)
    }

    private fun argbOf(argb: Int): Color =
        Color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, argb ushr 24)

    private companion object {
        /** Matches `Font#lineHeight`, which drives the map's own layout. */
        const val FONT_PX = 9
        val images = HashMap<Pair<String, Int>, BufferedImage>()
    }
}
