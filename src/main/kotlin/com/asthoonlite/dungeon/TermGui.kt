package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import com.asthoonlite.render.fillRoundedRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack

/**
 * The custom terminal GUI: a grid of tiles sized, gapped and centred in
 * *screen* space, rather than highlights painted one slot at a time onto
 * Hypixel's own chest layout.
 *
 * A terminal is a chest inventory — sixteen-pixel slots on an eighteen-pixel
 * pitch, parked wherever the chest panel happens to sit. A solver working
 * inside that has to accept the panel's geometry as given: tiles the panel
 * sizes, a gap the panel chooses, an origin the panel decides. This drops
 * all three. The container contributes nothing but a list of slot indices;
 * where a pane sits on screen is decided here.
 *
 * That is also why this file exists at all. The renderer and the click
 * handler have to agree about a tile's position to the pixel — a hit-test
 * that drifts from the drawing is a terminal that highlights one pane and
 * clicks another. So neither side does arithmetic of its own: the render
 * path asks [cellRect], the click path asks [slotAtPoint], and both read the
 * same [Metrics]. The regression harness checks the round trip between them,
 * which is the only check that actually matters for this.
 */
object TermGui {

    /**
     * The rectangle of container slots a terminal's content occupies, as
     * rows and columns of a nine-wide container.
     *
     * These are what the terminals are, not a guess from the rendered panel.
     * Two of the six are pinned by the solver itself: RUBIX is exactly
     * [TerminalSolver.RUBIX_SLOTS] (rows 1-3, columns 3-5) and ORDER is the
     * ten panes the number terminal ships with (rows 1-2, columns 2-6) —
     * both checked in the regression harness. MELODY is rows 0-4, columns
     * 1-7, which is where its marker strip, its four pad rows and its four
     * buttons live; row 5 is the container's filler row and is deliberately
     * outside the grid.
     */
    data class Layout(val rows: Int, val cols: Int, val startRow: Int, val startCol: Int) {

        /** Container slot at grid position ([row], [col]). */
        fun slotAt(row: Int, col: Int): Int = (startRow + row) * 9 + (startCol + col)

        /** Every container slot the grid draws, in reading order. */
        fun slots(): List<Int> = buildList {
            for (row in 0 until rows) for (col in 0 until cols) add(slotAt(row, col))
        }
    }

    /** Slot edge, in unscaled units. The chest's own 16/18 pitch is not involved. */
    private const val BASE_CELL = 24

    /**
     * The grid a terminal type draws.
     *
     * Fixed rather than derived from the slots on screen: a terminal's
     * content sits in the same rectangle whether the panes have been solved
     * yet or not, and a grid that shrank while the terminal was solved would
     * move the panes under the pointer mid-click.
     */
    fun layoutFor(kind: Kind): Layout = when (kind) {
        Kind.ORDER  -> Layout(2, 5, 1, 2)
        Kind.PANES  -> Layout(3, 5, 1, 2)
        Kind.RUBIX  -> Layout(3, 3, 1, 3)
        Kind.STARTS -> Layout(3, 7, 1, 1)
        Kind.SELECT -> Layout(4, 7, 1, 1)
        Kind.MELODY -> Layout(5, 7, 0, 1)
    }

    /**
     * One frame's resolved geometry — every number the renderer and the
     * hit-test share, computed once so the two can never diverge.
     *
     * [cell] is the tile edge in real pixels, [gap] the space between tiles
     * and [radius] the corner round, all after [scale]. [originX]/[originY]
     * are the grid's top-left on screen; [width]/[height] bound it, gutter
     * and all.
     */
    data class Metrics(
        val cell: Int,
        val gap: Int,
        val radius: Int,
        val originX: Int,
        val originY: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Resolves [layout] against a screen of [screenW] x [screenH].
     *
     * The grid is centred, which is the one thing the chest layout could
     * never be: a terminal always lands in the middle of the display,
     * whatever size the window is and whichever scale the GUI is running at.
     */
    fun metrics(
        layout: Layout,
        screenW: Int,
        screenH: Int,
        scale: Float,
        gap: Int,
        roundness: Int
    ): Metrics {
        val s = scale.coerceIn(0.5f, 4f)
        val cell = (BASE_CELL * s).toInt().coerceAtLeast(1)
        val g = (gap * s).toInt().coerceIn(0, cell)
        // A corner cannot be wider than half the thing it rounds; past that
        // the arc maths starts eating the rectangle's own fill.
        val radius = (roundness * s).toInt().coerceIn(0, cell / 2)
        val width = layout.cols * cell + (layout.cols - 1) * g
        val height = layout.rows * cell + (layout.rows - 1) * g
        return Metrics(
            cell = cell,
            gap = g,
            radius = radius,
            originX = (screenW - width) / 2,
            originY = (screenH - height) / 2,
            width = width,
            height = height
        )
    }

    /** Screen-space rectangle of the tile at grid position ([row], [col]). */
    fun cellRect(m: Metrics, row: Int, col: Int): IntArray {
        val pitch = m.cell + m.gap
        val x = m.originX + col * pitch
        val y = m.originY + row * pitch
        return intArrayOf(x, y, x + m.cell, y + m.cell)
    }

    /**
     * The container slot a screen coordinate lands on, or null for the gutter,
     * the padding, and everything outside the grid.
     *
     * The gutter is the reason the gap exists at all: a click that falls
     * between two tiles belongs to no pane, and answering with the nearest
     * one instead is how a grid ends up clicking something the pointer was
     * never on.
     */
    fun slotAtPoint(m: Metrics, layout: Layout, screenX: Float, screenY: Float): Int? {
        val x = screenX - m.originX
        val y = screenY - m.originY
        if (x < 0f || y < 0f || x >= m.width || y >= m.height) return null

        val pitch = m.cell + m.gap
        val lx = x.toInt()
        val ly = y.toInt()
        val col = lx / pitch
        val row = ly / pitch
        if (row >= layout.rows || col >= layout.cols) return null

        // Inside the pitch but in the gap between two tiles.
        if (lx - col * pitch >= m.cell) return null
        if (ly - row * pitch >= m.cell) return null
        return layout.slotAt(row, col)
    }

    // ── Wiring ──────────────────────────────────────────────────────────────

    /** [layout] and [metrics] for a terminal of this size, resolved once. */
    data class Resolved(val layout: Layout, val metrics: Metrics)

    /**
     * The grid for [kind] on a screen of [screenW] x [screenH], at the scale
     * the settings have it at.
     *
     * Melody carries its own scale rather than sharing the term size: it is
     * five rows of seven where the others are at most four, and the one size
     * that fits both either overflows the window on melody or wastes half
     * the screen on rubix.
     */
    fun resolve(kind: Kind, screenW: Int, screenH: Int): Resolved {
        val layout = layoutFor(kind)
        val scale = if (kind == Kind.MELODY) Config.termGuiMelodySize else Config.termGuiSize
        return Resolved(
            layout,
            metrics(layout, screenW, screenH, scale, Config.termGuiGap, Config.termGuiRoundness)
        )
    }

    /**
     * Whether the custom grid is what should be on screen for [screen]: the
     * switch is on and the screen is a terminal.
     *
     * Every suppression this GUI needs is keyed off this one test — the
     * background, the labels, the slots, the tooltip and the highlight all
     * ask it — which is what keeps the vanilla chest and the grid from ever
     * ending up half-overlapping because two of them were told different
     * things. Off means the mod behaves exactly as it did before this file
     * existed.
     */
    fun isActive(screen: Any?): Boolean {
        if (!Config.termGuiEnabled) return false
        val container = screen as? AbstractContainerScreen<*> ?: return false
        return TerminalSolver.isTerminalTitle(container.title.string)
    }

    /**
     * The container slot a click lands on, or null for everything off the
     * grid — the gutter, the padding, and the rest of the window.
     *
     * Null has to mean *hand the click back to vanilla*: the close button and
     * the drop-carried-slot click both live outside the grid, and swallowing
     * those would leave the screen with no way out but the Escape key.
     */
    fun slotAt(screen: AbstractContainerScreen<*>, kind: Kind, screenX: Float, screenY: Float): Int? {
        val resolved = resolve(kind, screen.width, screen.height)
        return slotAtPoint(resolved.metrics, resolved.layout, screenX, screenY)
    }

    // ── Presentation ────────────────────────────────────────────────────────

    /** How far the backing panel reaches past the grid on every side. */
    private const val PANEL_PAD = 6

    /** Corner round of the panel, in addition to the tiles' own round. */
    private const val PANEL_RADIUS_PAD = 3

    private const val PANEL_COLOR = 0xF0090B10.toInt()

    /**
     * Tiles the solver has nothing to say about — a pane that is already
     * correct, or a terminal whose solver switch is off. Lighter than the
     * panel so the grid still reads as a grid, dark enough that a tile
     * carrying a real colour is obviously the one that matters.
     */
    private const val NEUTRAL_COLOR = 0xE6262B36.toInt()

    private const val LABEL_COLOR = 0xFFFFFFFF.toInt()

    private const val MARKER_COLOR = 0x0000E676

    /**
     * Draws the terminal as its own grid.
     *
     * Called from inside the slot pass, where the pose still carries the
     * container's origin — so every coordinate headed for [graphics] comes
     * back out by that origin first. Get it wrong and the grid lands off
     * centre by exactly the chest's offset: visible, and baffling to read
     * from the numbers alone, which is why the adjustment lives here and not
     * at the call site.
     */
    fun draw(
        graphics: GuiGraphicsExtractor,
        screen: AbstractContainerScreen<*>,
        kind: Kind,
        title: String,
        items: List<ItemStack>,
        marker: Int?
    ) {
        val origin = screen as? AbstractContainerScreenAccessor ?: return
        val resolved = resolve(kind, graphics.guiWidth(), graphics.guiHeight())
        val m = resolved.metrics
        val layout = resolved.layout
        val dx = -origin.leftPos
        val dy = -origin.topPos

        // One surface under the whole grid. Without it the tiles are squares
        // floating over the world behind the screen.
        fillRoundedRect(
            graphics,
            m.originX + dx - PANEL_PAD,
            m.originY + dy - PANEL_PAD,
            m.originX + dx + m.width + PANEL_PAD,
            m.originY + dy + m.height + PANEL_PAD,
            PANEL_COLOR,
            m.radius + PANEL_RADIUS_PAD
        )

        val font = Minecraft.getInstance().font
        for (row in 0 until layout.rows) {
            for (col in 0 until layout.cols) {
                val slot = layout.slotAt(row, col)
                val rect = cellRect(m, row, col)
                val x0 = rect[0] + dx
                val y0 = rect[1] + dy
                val x1 = rect[2] + dx
                val y1 = rect[3] + dy

                // Under the tile so the frame reads as a ring in the gap
                // around it rather than a box drawn over the pane.
                if (slot == marker) {
                    fillRoundedRect(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1, markerColor(), m.radius + 1)
                }

                val stack = items.getOrNull(slot)
                val color = TerminalSolver.colorFor(
                    title,
                    slot,
                    stack ?: ItemStack.EMPTY,
                    items,
                    AutoTerminal.rubixTargetOrNull()
                ) ?: NEUTRAL_COLOR
                fillRoundedRect(graphics, x0, y0, x1, y1, color, m.radius)

                val label = TerminalSolver.labelFor(slot, stack, kind) ?: continue
                graphics.text(
                    font, label,
                    x0 + (m.cell - font.width(label)) / 2,
                    y0 + (m.cell - font.lineHeight) / 2,
                    LABEL_COLOR
                )
            }
        }
    }

    /**
     * Pulsing colour for the tile the clicker is about to send a packet for.
     * Pulled out of the drawing so the ring is one number the whole grid
     * agrees on for the frame rather than one computed per tile.
     */
    private fun markerColor(): Int {
        val phase = (System.currentTimeMillis() % 800L) / 800.0
        val pulse = kotlin.math.sin(phase * 2 * kotlin.math.PI) * 0.5 + 0.5
        val alpha = (160 + 95 * pulse).toInt().coerceIn(0, 255)
        return (alpha shl 24) or MARKER_COLOR
    }
}
