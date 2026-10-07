package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.asthoonlite.render.fillRoundedRect
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
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
    private val DEFAULT_NUMBER_SLOTS = (1..2).flatMap { row -> (2..6).map { col -> row * 9 + col } }
    /** How long a click leaves its mark on the pane it was for. */
    private const val CLICK_FLASH_MS = 220L
    private var cachedRubixTarget: Int? = null
    private var lastRubixContainerId: Int = -1

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
        melodyRows: List<Int> = listOf(1, 2, 3), orderSlots: List<Int> = DEFAULT_NUMBER_SLOTS
    ): Grid {
        val (rows, cols, startRow, startCol) = shape(kind, melodyRows, orderSlots)
        val gap = gapPixels.coerceIn(0, 12)
        val w = cols * TILE_SIZE + (cols - 1) * gap
        val h = rows * TILE_SIZE + (rows - 1) * gap
        val scale = minOf(scalePercent.coerceIn(50, 300) / 100f,
            (width - 16).coerceAtLeast(1) / w.toFloat(), (height - 48).coerceAtLeast(1) / h.toFloat())
        return Grid((width - w * scale) / 2f, (height - h * scale) / 2f, scale, w, h, tiles(kind, melodyRows, gap, orderSlots))
    }

    /**
     * The content rows melody actually ships: 1, 2, 3.
     *
     * Row 0 above them is the marker strip and row 4 below them is the
     * indicator, so anything outside this range in the data is either one of
     * those or a stale leftover from the four-row terminal the game used to
     * run. Filtering here rather than in each caller is what keeps a stale
     * row from growing the grid, from adding tiles, and from raising the slot
     * count the menu has to reach before the grid is allowed to draw.
     */
    private fun contentRows(melodyRows: List<Int>): List<Int> = melodyRows.filter { it in 1..3 }

    /** rows, columns, and where the first one sits in the 9-wide menu list. */
    private fun shape(kind: Kind, melodyRows: List<Int>, orderSlots: List<Int>): IntArray = when (kind) {
        Kind.PANES -> intArrayOf(3, 5, 1, 2)
        Kind.RUBIX -> intArrayOf(3, 3, 1, 3)
        Kind.ORDER -> {
            val slots = numberLayoutSlots(orderSlots)
            val firstRow = slots.minOf { it / 9 }
            val firstCol = slots.minOf { it % 9 }
            intArrayOf(slots.maxOf { it / 9 } - firstRow + 1, slots.maxOf { it % 9 } - firstCol + 1, firstRow, firstCol)
        }
        Kind.STARTS -> intArrayOf(3, 7, 1, 1)
        Kind.SELECT -> intArrayOf(4, 7, 1, 1)
        // Marker row, the three content rows, indicator row: five rows, seven
        // columns, starting at the left of the menu's own columns.
        Kind.MELODY -> intArrayOf((contentRows(melodyRows).maxOrNull() ?: 3) + 2, 7, 0, 1)
    }

    /** The tiles themselves — independent of the screen they will be drawn on,
     *  which is what lets the slot requirement be answered without one. */
    internal fun tiles(
        kind: Kind, melodyRows: List<Int> = listOf(1, 2, 3), gapPixels: Int = 0,
        orderSlots: List<Int> = DEFAULT_NUMBER_SLOTS
    ): List<Tile> {
        val rows = contentRows(melodyRows)
        val (rowCount, cols, startRow, startCol) = shape(kind, rows, orderSlots)
        val numbers = if (kind == Kind.ORDER) numberLayoutSlots(orderSlots).toSet() else emptySet()
        val gap = gapPixels.coerceIn(0, 12)
        return buildList {
            for (r in 0 until rowCount) for (c in 0 until cols) {
                val row = startRow + r
                val col = startCol + c
                if (kind == Kind.ORDER && row * 9 + col !in numbers) continue
                if (kind == Kind.MELODY && (col == 6 ||
                    (row !in rows && (row != 0 && row != rowCount - 1 || col == 7)))) continue
                add(Tile(row * 9 + col, c * (TILE_SIZE + gap), r * (TILE_SIZE + gap)))
            }
        }
    }

    private fun numberLayoutSlots(slots: List<Int>): List<Int> =
        slots.filter { it in 0 until Kind.ORDER.slotCount }.distinct().ifEmpty { DEFAULT_NUMBER_SLOTS }

    /**
     * How many slots the menu has to have for this terminal's grid to be
     * drawable: one past the last tile the layout would produce.
     *
     * Deliberately not [Kind.slotCount]. The real terminal ships a full chest
     * — its own rows plus the player's inventory under them — so a menu that
     * holds every pane the grid draws is enough, whether or not it also
     * carries the inventory. A simulator's window or a practice world's
     * shorter chest is exactly the case where the two numbers part company,
     * and demanding the larger one turns the grid off on a screen that can
     * draw it. Melody is the one kind whose rows are live, so its callers pass
     * what the menu actually shows.
     */
    internal fun requiredSlots(
        kind: Kind, melodyRows: List<Int> = listOf(1, 2, 3), orderSlots: List<Int> = DEFAULT_NUMBER_SLOTS
    ): Int = (tiles(kind, melodyRows, orderSlots = orderSlots).maxOfOrNull { it.slot } ?: -1) + 1

    /** [requiredSlots] for a live menu: melody's answer depends on its rows. */
    internal fun requiredSlotsFor(kind: Kind, items: List<ItemStack>): Int =
        when (kind) {
            Kind.MELODY -> requiredSlots(kind, TerminalSolver.melodyRows(items).map { it.row })
            Kind.ORDER -> requiredSlots(kind, orderSlots = TerminalSolver.numberSlots(items))
            else -> requiredSlots(kind)
        }

    internal fun covers(kind: Kind, slotCount: Int, melodyRows: List<Int> = listOf(1, 2, 3)): Boolean =
        slotCount >= requiredSlots(kind, melodyRows)

    fun active(screen: AbstractContainerScreen<*>): Boolean {
        if (!Config.termGuiEnabled || QuietMode.suppressing()) return false
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return false
        val slots = screen.menu.slots.size
        return slots >= requiredSlotsFor(kind, items(screen, kind))
    }

    /** Melody's rows as the menu currently shows them; nothing else asks. */
    private fun liveMelodyRows(screen: AbstractContainerScreen<*>, kind: Kind): List<Int> =
        if (kind == Kind.MELODY) TerminalSolver.melodyRows(items(screen, kind)).map { it.row } else emptyList()

    fun gridFor(screen: AbstractContainerScreen<*>): Grid? {
        if (!active(screen)) return null
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return null
        val rows = liveMelodyRows(screen, kind)
        // Melody carries its own scale — seven columns of six rows does not fit
        // at the pane size, which is the entire reason the setting is separate.
        val tileScale = if (kind == Kind.MELODY) Config.termGuiMelodySize else Config.termGuiSize
        return layout(kind, screen.width, screen.height,
            (tileScale * 100f).toInt(), Config.termGuiGap, rows,
            if (kind == Kind.ORDER) TerminalSolver.numberSlots(items(screen, kind)) else DEFAULT_NUMBER_SLOTS)
    }

    private fun items(screen: AbstractContainerScreen<*>, kind: Kind) = screen.menu.slots.take(kind.slotCount).map { it.item }

    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val grid = gridFor(screen) ?: return
        // No dim of our own, and the reason is the extraction order rather
        // than taste: extractRenderStateWithTooltipAndSubtitles calls
        // extractBackground *before* extractRenderState, and
        // AbstractContainerScreen.isInGameUi() is true, so every container
        // screen — chest or otherwise — has already been dimmed by
        // extractTransparentBackground by the time the mixin up here fires.
        // A chest is the one case where that did not happen, because
        // MixinContainerScreen cancels extractBackground to trim its texture
        // and puts its own dim back. Filling here too would draw a second
        // layer over the first and take the grid down with the background.
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return
        val all = items(screen, kind)
        val title = TerminalSolver.cleanTitle(screen.title.string)
        val simulation = com.asthoonlite.dungeon.simulator.TerminalSimulator.isSimulation(screen)
        if (kind == Kind.RUBIX) {
            if (cachedRubixTarget == null || screen.menu.containerId != lastRubixContainerId) {
                lastRubixContainerId = screen.menu.containerId
                cachedRubixTarget = AutoTerminal.rubixTargetOrNull() ?: TerminalSolver.optimalRubixTarget(all)
            }
        } else {
            cachedRubixTarget = null
            lastRubixContainerId = -1
        }
        val target = if (kind == Kind.RUBIX) (AutoTerminal.rubixTargetOrNull() ?: cachedRubixTarget) else null
        val next = if (Config.terminalSolverEnabled && (kind == Kind.ORDER || kind == Kind.MELODY))
            TerminalSolver.nextClickSlot(title, all, AutoTerminal.unsettledSlots(), target,
                AutoTerminal.lastClickedSlot(), Config.autoTerminalClickOrder) else null
        val hovered = if (TerminalCursor.ownsCursor()) null else grid.hitTest(mouseX.toDouble(), mouseY.toDouble())
        val font = Minecraft.getInstance().font
        graphics.centeredText(font, title, screen.width / 2, (grid.originY - 18).toInt(), 0xFFFFFFFF.toInt())
        val originX = grid.originX
        val originY = grid.originY
        val scale = grid.scale
        val roundness = Config.termGuiRoundness.coerceIn(0, 8)

        // Background panel rendered in true screen pixels
        val bgPad = (4 * scale).toInt()
        val bgX0 = (originX - bgPad).toInt()
        val bgY0 = (originY - bgPad).toInt()
        val bgX1 = (originX + grid.width * scale + bgPad).toInt()
        val bgY1 = (originY + grid.height * scale + bgPad).toInt()
        val bgRadius = if (roundness > 0) ((roundness + 2) * scale).toInt().coerceAtMost(10) else 0
        fillRoundedRect(graphics, bgX0, bgY0, bgX1, bgY1, BACKGROUND, bgRadius)

        for (tile in grid.tiles) {
            val sx0 = (originX + tile.x * scale).toInt()
            val sy0 = (originY + tile.y * scale).toInt()
            val sx1 = (originX + (tile.x + TILE_SIZE) * scale).toInt()
            val sy1 = (originY + (tile.y + TILE_SIZE) * scale).toInt()
            val sw = sx1 - sx0
            val sh = sy1 - sy0
            val tileRadius = if (roundness > 0) (roundness * scale).toInt().coerceIn(0, minOf(sw, sh) / 2) else 0

            val slotObj = screen.menu.slots.getOrNull(tile.slot)
            val stack = slotObj?.item ?: ItemStack.EMPTY
            val color = if (kind == Kind.MELODY) {
                TerminalSolver.melodyColor(tile.slot, stack, all)
            } else if (kind == Kind.RUBIX && Config.terminalSolverEnabled) {
                val current = AutoTerminal.rubixPredicted(tile.slot)
                    ?: TerminalHelper.rubixColorIndex(stack).takeIf { it >= 0 }
                val wanted = target ?: cachedRubixTarget ?: TerminalSolver.optimalRubixTarget(all)
                if (wanted != null && current != null && current != wanted) 0xCC55FFFF.toInt() else null
            } else {
                TerminalSolver.colorFor(title, tile.slot, stack, all, target)
            }

            if (tile.slot == next || tile.slot == hovered) {
                val borderThickness = maxOf(1, (1.5f * (scale / 2f)).toInt())
                val borderCol = if (tile.slot == next) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt()
                val bRadius = if (tileRadius > 0) tileRadius + borderThickness else 0
                fillRoundedRect(graphics, sx0 - borderThickness, sy0 - borderThickness,
                    sx1 + borderThickness, sy1 + borderThickness, borderCol, bRadius)
            }
            fillRoundedRect(graphics, sx0, sy0, sx1, sy1, color ?: NEUTRAL, tileRadius)

            if (simulation && kind != Kind.SELECT && kind != Kind.STARTS && !Config.terminalSolverEnabled && !stack.isEmpty) {
                graphics.pose().pushMatrix()
                graphics.pose().translate(sx0.toFloat(), sy0.toFloat())
                graphics.pose().scale(scale, scale)
                graphics.item(stack, 1, 1)
                graphics.pose().popMatrix()
            }
            if (simulation && tile.slot == hovered && !stack.isEmpty &&
                (kind == Kind.SELECT || kind == Kind.STARTS || !Config.terminalSolverEnabled)) {
                graphics.setTooltipForNextFrame(font, stack.hoverName, mouseX, mouseY)
            }

            val label = TerminalSolver.labelFor(tile.slot, stack, kind)
                ?: if (kind == Kind.RUBIX && Config.terminalSolverEnabled) {
                    val current = AutoTerminal.rubixPredicted(tile.slot)
                        ?: TerminalHelper.rubixColorIndex(stack).takeIf { it >= 0 }
                    val wanted = target ?: cachedRubixTarget ?: TerminalSolver.optimalRubixTarget(all)
                    if (current != null && wanted != null) {
                        rubixLabel(current, wanted, simulation)
                    } else null
                } else null

            if (label != null) {
                val fontScale = if (scale >= 1.7f) 1.5f else 1.0f
                if (fontScale > 1.0f) {
                    graphics.pose().pushMatrix()
                    graphics.pose().translate(sx0 + sw / 2f, sy0 + sh / 2f)
                    graphics.pose().scale(fontScale, fontScale)
                    graphics.text(font, label, (-font.width(label) / 2f).toInt(), (-font.lineHeight / 2f).toInt(), 0xFFFFFFFF.toInt())
                    graphics.pose().popMatrix()
                } else {
                    graphics.text(font, label, sx0 + (sw - font.width(label)) / 2, sy0 + (sh - font.lineHeight) / 2, 0xFFFFFFFF.toInt())
                }
            }
        }

        // The click leaves a subtle mark on the tile in screen space
        val flash = AutoTerminal.lastClickFlash()
        if (Config.termGuiClickFlash && flash != null) {
            val age = System.currentTimeMillis() - flash.second
            if (age in 0 until CLICK_FLASH_MS) {
                val tile = grid.tiles.firstOrNull { it.slot == flash.first }
                if (tile != null) {
                    val fade = (1f - age / CLICK_FLASH_MS.toFloat())
                    val alpha = (fade * 0x55).toInt().coerceIn(0, 0x55)
                    val fsx0 = (originX + tile.x * scale).toInt()
                    val fsy0 = (originY + tile.y * scale).toInt()
                    val fsx1 = (originX + (tile.x + TILE_SIZE) * scale).toInt()
                    val fsy1 = (originY + (tile.y + TILE_SIZE) * scale).toInt()
                    val fr = if (roundness > 0) (roundness * scale).toInt().coerceIn(0, minOf(fsx1 - fsx0, fsy1 - fsy0) / 2) else 0
                    fillRoundedRect(graphics, fsx0, fsy0, fsx1, fsy1, (alpha shl 24) or 0x00FFFFFF, fr)
                }
            }
        }
    }

    /** Both live and practice clicks take the shortest direction (positive or negative). */
    internal fun rubixLabel(current: Int, wanted: Int, simulation: Boolean = false): String? {
        val clicks = TerminalSolver.rubixDistance(current, wanted)
        return clicks.takeIf { it > 0 }?.toString()
    }

    /** Gaps and padding consume input, but never become hidden vanilla slot clicks. */
    fun click(screen: AbstractContainerScreen<*>, x: Double, y: Double, button: Int, source: String = "manual") {
        val slot = gridFor(screen)?.hitTest(x, y) ?: return
        val kind = TerminalSolver.kindOf(screen.title.string) ?: return
        val all = items(screen, kind)
        val simulation = com.asthoonlite.dungeon.simulator.TerminalSimulator.isSimulation(screen)
        if (button !in 0..2 || (!simulation && !canClick(kind, screen.title.string, all, slot))) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        val effectiveButton = if (kind == Kind.RUBIX) {
            val slotObj = screen.menu.slots.getOrNull(slot)
            val currentIdx = AutoTerminal.rubixPredicted(slot)
                ?: TerminalHelper.rubixColorIndex(slotObj?.item ?: ItemStack.EMPTY).takeIf { it >= 0 }
            val target = AutoTerminal.rubixTargetOrNull() ?: cachedRubixTarget ?: TerminalSolver.optimalRubixTarget(all)
            when (button) {
                0 -> if (currentIdx != null && target != null && currentIdx in 0..4 && target in 0..4) {
                    TerminalSolver.rubixButton(currentIdx, target)
                } else 0
                1 -> 1
                else -> 2
            }
        } else if (simulation) button else 2

        // Same door the clicker uses: the player's window sends the packet, a
        // screen holding its own menu gets the click through slotClicked —
        // which is where a hand's click lands on such a screen too, and
        // therefore the only way a manual click can reach it at all.
        val sent = TerminalInput.send(
            screen, slot,
            effectiveButton,
            if (kind == Kind.RUBIX) ContainerInput.PICKUP else ContainerInput.CLONE,
            source
        )
        if (!sent) return
        screen.menu.carried = ItemStack.EMPTY
        if (!simulation) player.containerMenu.carried = ItemStack.EMPTY
        if (kind == Kind.RUBIX && !simulation) {
            val slotObj = screen.menu.slots.getOrNull(slot)
            val currentIdx = AutoTerminal.rubixPredicted(slot)
                ?: TerminalHelper.rubixColorIndex(slotObj?.item ?: ItemStack.EMPTY).takeIf { it >= 0 }
                ?: 0
            val nextColor = TerminalSolver.rubixAdvance(currentIdx, effectiveButton)
            AutoTerminal.setRubixPredicted(slot, nextColor)
            val nextItem = when (nextColor) {
                0 -> net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE
                1 -> net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE
                2 -> net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE
                3 -> net.minecraft.world.item.Items.LIGHT_BLUE_STAINED_GLASS_PANE
                4 -> net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE
                else -> null
            }
            if (nextItem != null && slotObj != null) {
                slotObj.set(ItemStack(nextItem))
            }
        }
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
                if (event.hasControlDown()) 1 else 0, "keyboard")
        }
    }

    /**
     * One line, once per screen type, saying which of the gates refused.
     *
     * A grid that does not appear is otherwise indistinguishable from a grid
     * that was never asked for: four separate conditions have to be true at
     * once and they all fail the same way, silently. This prints the answer
     * into the log where it can be read after one run, instead of guessed at.
     */
    fun register() {
        val seen = HashSet<String>()
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            val title = screen.title.string
            val kind = TerminalSolver.kindOf(title)
            if (kind == null && !looksLikeTerminal(title, screen)) return@register
            if (!seen.add(screen.javaClass.simpleName + "|" + title)) return@register
            val container = screen as? AbstractContainerScreen<*> ?: return@register
            AsthoonLite.LOGGER.info("[AsthoonLite] TermGui {} {} -> {}",
                screen.javaClass.simpleName, "'$title'", diagnose(container, kind))
        }
    }

    /** Title or class that reads like a terminal even when kindOf does not match it. */
    private fun looksLikeTerminal(title: String, screen: Screen): Boolean {
        val t = title.lowercase()
        val cls = screen.javaClass.simpleName.lowercase()
        return "p3" in t || "terminal" in t || "sim" in t || "sim" in cls || "terminal" in cls
    }

    internal fun diagnose(screen: AbstractContainerScreen<*>, kind: Kind?): String {
        val slots = screen.menu.slots.size
        val quiet = QuietMode.suppressing()
        val enabled = Config.termGuiEnabled
        if (kind == null) return "kind=null slots=$slots termGui=$enabled quiet=$quiet reason=no kind for this title"
        val required = requiredSlotsFor(kind, items(screen, kind))
        val reason = when {
            !enabled -> "termGuiEnabled is off"
            quiet -> "quiet mode is on"
            slots < required -> "menu has $slots slots, grid needs $required"
            else -> "open"
        }
        return "kind=$kind slots=$slots need=$required termGui=$enabled quiet=$quiet active=" +
            "${active(screen)} reason=$reason"
    }
}
