package com.asthoonlite.dungeon

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.asthoonlite.render.fillRoundedRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW

/** Centered terminal grids adapted from Odin's CustomTermGui and TerminalTypes.
 * Copyright (c) 2025, odtheking. BSD-3-Clause; see META-INF/licenses/Odin.txt.
 * The live menu is retained, so server updates and container IDs remain vanilla. */
object TermGui {
    internal const val TILE_SIZE = 24
    private const val BACKGROUND = 0xF0090B10.toInt()
    private const val NEUTRAL = 0xFF292F3B.toInt()
    /** Same dim MixinContainerScreen paints over a chest, for screens it cannot reach. */
    private const val DIM_BACKGROUND = 0x88000000.toInt()

    data class Tile(val slot: Int, val x: Int, val y: Int)

    data class Grid(
        val originX: Float, val originY: Float, val scale: Float,
        val width: Int, val height: Int, val tiles: List<Tile>
    ) {
        fun center(slot: Int): Pair<Float, Float>? = tiles.firstOrNull { it.slot == slot }?.let {
            (originX + (it.x + TILE_SIZE / 2f) * scale) to (originY + (it.y + TILE_SIZE / 2f) * scale)
        }

        fun hitTest(screenX: Double, screenY: Double): Int? {
            val x = (screenX - originX) / scale
            val y = (screenY - originY) / scale
            return tiles.firstOrNull { x >= it.x && y >= it.y && x < it.x + TILE_SIZE && y < it.y + TILE_SIZE }?.slot
        }
    }

    /** One layout feeds rendering, manual input and the animated cursor. */
    internal fun layout(
        kind: Kind, width: Int, height: Int, scalePercent: Int, gapPixels: Int,
        melodyRows: List<Int> = listOf(1, 2, 3)
    ): Grid {
        val (rows, cols, startRow, startCol) = when (kind) {
            Kind.PANES -> listOf(3, 5, 1, 2)
            Kind.RUBIX -> listOf(3, 3, 1, 3)
            Kind.ORDER -> listOf(2, 5, 1, 2)
            Kind.STARTS -> listOf(3, 7, 1, 1)
            Kind.SELECT -> listOf(4, 7, 1, 1)
            Kind.MELODY -> listOf((melodyRows.maxOrNull() ?: 3).coerceIn(1, 4) + 2, 7, 0, 1)
        }
        val gap = gapPixels.coerceIn(0, 12)
        val w = cols * TILE_SIZE + (cols - 1) * gap
        val h = rows * TILE_SIZE + (rows - 1) * gap
        val scale = minOf(scalePercent.coerceIn(50, 300) / 100f,
            (width - 16).coerceAtLeast(1) / w.toFloat(), (height - 48).coerceAtLeast(1) / h.toFloat())
        val tiles = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                val row = startRow + r
                val col = startCol + c
                if (kind == Kind.MELODY && (col == 6 ||
                    (row !in melodyRows && (row != 0 && row != rows - 1 || col == 7)))) continue
                add(Tile(row * 9 + col, c * (TILE_SIZE + gap), r * (TILE_SIZE + gap)))
            }
        }
        return Grid((width - w * scale) / 2f, (height - h * scale) / 2f, scale, w, h, tiles)
    }

    /**
     * Whether the drawn grid covers this screen.
     *
     * The only thing the grid needs from a menu is the terminal's own rows —
     * everything it draws comes from `slots.take(kind.slotCount)` — so that is
     * the test. The `+ 36` this replaces asked for the player's inventory
     * below the rows as well, which is how a screen with the terminal and
     * nothing else (a simulator's window, a practice world's shorter chest)
     * went undrawn while the clicker, which never asked for the inventory,
     * kept working on the very same screen. The title stays the real gate: it
     * is what says "this is a terminal" at all, and it is matched on the
     * stripped text, so a wrapper like "P3 · Click in order!" still counts.
     *
     * [covers] is split out because the count test is the part worth pinning,
     * and pinning it does not want a screen.
     */
    internal fun covers(kind: Kind, slotCount: Int): Boolean = slotCount >= kind.slotCount

    fun active(screen: AbstractContainerScreen<*>): Boolean =
        Config.termGuiEnabled && !QuietMode.suppressing() &&
            TerminalSolver.kindOf(screen.title.string)?.let { covers(it, screen.menu.slots.size) } == true

    fun gridFor(screen: AbstractContainerScreen<*>): Grid? {
        if (!active(screen)) return null
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return null
        val rows = if (kind == Kind.MELODY) TerminalSolver.melodyRows(items(screen, kind)).map { it.row } else emptyList()
        // Melody carries its own scale — seven columns of six rows does not fit
        // at the pane size, which is the entire reason the setting is separate.
        val tileScale = if (kind == Kind.MELODY) Config.termGuiMelodySize else Config.termGuiSize
        return layout(kind, screen.width, screen.height,
            (tileScale * 100f).toInt(), Config.termGuiGap, rows)
    }

    private fun items(screen: AbstractContainerScreen<*>, kind: Kind) = screen.menu.slots.take(kind.slotCount).map { it.item }

    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val grid = gridFor(screen) ?: return
        // A chest dims itself through MixinContainerScreen, which mixes into
        // ContainerScreen and nowhere else. A screen that is not one of those
        // — a simulator's own window — would leave its background sitting
        // there, untrimmed, underneath a grid drawn on top of it. So this dims
        // it here instead, and only when the hook could not have run, because
        // dimming twice would take the grid down with the background.
        if (screen !is ContainerScreen) graphics.fill(0, 0, screen.width, screen.height, DIM_BACKGROUND)
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return
        val all = items(screen, kind)
        val title = TerminalSolver.cleanTitle(screen.title.string)
        val target = AutoTerminal.rubixTargetOrNull()
        val next = if (Config.terminalSolverEnabled)
            TerminalSolver.nextClickSlot(title, all, AutoTerminal.unsettledSlots(), target, AutoTerminal.lastClickedSlot()) else null
        val hovered = if (TerminalCursor.ownsCursor()) null else grid.hitTest(mouseX.toDouble(), mouseY.toDouble())
        val font = Minecraft.getInstance().font
        graphics.centeredText(font, title, screen.width / 2, (grid.originY - 18).toInt(), 0xFFFFFFFF.toInt())
        graphics.pose().pushMatrix()
        graphics.pose().translate(grid.originX, grid.originY)
        graphics.pose().scale(grid.scale, grid.scale)
        fillRoundedRect(graphics, -4, -4, grid.width + 4, grid.height + 4, BACKGROUND, 5)
        for (tile in grid.tiles) {
            val stack = all.getOrNull(tile.slot) ?: continue
            val color = if (kind == Kind.MELODY) TerminalSolver.melodyColor(tile.slot, stack, all)
                else TerminalSolver.colorFor(title, tile.slot, stack, all, target)
            if (tile.slot == next || tile.slot == hovered) {
                fillRoundedRect(graphics, tile.x - 1, tile.y - 1, tile.x + TILE_SIZE + 1, tile.y + TILE_SIZE + 1,
                    if (tile.slot == next) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt(), 4)
            }
            fillRoundedRect(graphics, tile.x, tile.y, tile.x + TILE_SIZE, tile.y + TILE_SIZE, color ?: NEUTRAL,
                Config.termGuiRoundness.coerceIn(0, TILE_SIZE / 2))
            // Item/name puzzles stay legible even with the solver switched off.
            if (kind == Kind.SELECT || kind == Kind.STARTS || (!Config.terminalSolverEnabled && kind != Kind.MELODY)) {
                graphics.item(stack, tile.x + 4, tile.y + 4)
            }
            val label = TerminalSolver.labelFor(tile.slot, stack, kind)
                ?: if (kind == Kind.RUBIX && Config.terminalSolverEnabled) {
                    val current = TerminalHelper.rubixColorIndex(stack)
                    val wanted = target ?: TerminalSolver.optimalRubixTarget(all)
                    if (current >= 0 && wanted != null) ((wanted - current + 5) % 5).toString() else null
                } else null
            if (label != null) graphics.text(font, label, tile.x + (TILE_SIZE - font.width(label)) / 2,
                tile.y + (TILE_SIZE - font.lineHeight) / 2, 0xFFFFFFFF.toInt())
        }
        graphics.pose().popMatrix()
    }

    /** Gaps and padding consume input, but never become hidden vanilla slot clicks. */
    fun click(screen: AbstractContainerScreen<*>, x: Double, y: Double, button: Int) {
        val slot = gridFor(screen)?.hitTest(x, y) ?: return
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return
        val all = items(screen, kind)
        if (button !in 0..2 || !canClick(kind, screen.title.string, all, slot)) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // Same door the clicker uses: the player's window sends the packet, a
        // screen holding its own menu gets the click through slotClicked —
        // which is where a hand's click lands on such a screen too, and
        // therefore the only way a manual click can reach it at all.
        val sent = TerminalInput.send(
            screen, slot,
            if (kind == Kind.RUBIX) button.coerceAtMost(1) else 2,
            if (kind == Kind.RUBIX) ContainerInput.PICKUP else ContainerInput.CLONE
        )
        if (!sent) return
        screen.menu.carried = ItemStack.EMPTY
        player.containerMenu.carried = ItemStack.EMPTY
    }

    internal fun canClick(kind: Kind, title: String, all: List<ItemStack>, slot: Int): Boolean =
        if (kind == Kind.MELODY) TerminalSolver.melodyRows(all).any { it.buttonSlot == slot && !it.completed }
        else slot in TerminalSolver.clickCandidates(title, all, rubixTarget = AutoTerminal.rubixTargetOrNull())

    fun keyPressed(screen: AbstractContainerScreen<*>, event: KeyEvent) {
        val mc = Minecraft.getInstance()
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(event)) {
            AutoTerminal.onEscape()
            screen.onClose()
        } else if (mc.options.keyDrop.matches(event) || mc.options.keyHotbarSlots.any { it.matches(event) }) {
            click(screen, mc.mouseHandler.getScaledXPos(mc.window), mc.mouseHandler.getScaledYPos(mc.window),
                if (event.hasControlDown()) 1 else 0)
        }
    }
}
