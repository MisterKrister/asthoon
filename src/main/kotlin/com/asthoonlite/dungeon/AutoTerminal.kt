package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Advanced automatic terminal clicker with RSM-compatible preset support,
 * safe Gaussian random delay intervals, break threshold protection,
 * and Melody skip / party announce features.
 */
object AutoTerminal {
    private var lastClickAt = 0L
    private var terminalOpenedAt = 0L
    private var suppressReopenUntil = 0L
    private var lastTerminalTitle: String? = null
    private var firstClickPending = true
    private var currentClickDelayMs = 0L
    private val melodySkipQueue = ArrayDeque<Int>()
    private val clickedSlotsWithTime = HashMap<Int, Long>()
    private var lastRubixTarget: Int? = null
    private var lastMelodyRow = -1
    private var lastMelodyRowClickAt = 0L

    private const val CLICK_TIMEOUT_MS = 350L
    private const val RUBIX_REPEAT_GUARD_MS = 70L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun isRecentlyClicked(slot: Int, now: Long): Boolean {
        val last = clickedSlotsWithTime[slot] ?: return false
        return now - last < CLICK_TIMEOUT_MS
    }

    private fun tick() {
        if (!Config.autoTerminalEnabled || !DungeonContext.inDungeon) {
            reset()
            return
        }

        val mc = Minecraft.getInstance()
        val screen = mc.screen as? AbstractContainerScreen<*> ?: run { reset(); return }
        val title = screen.title.string
        val cleanTitle = ChatFormatting.stripFormatting(title)?.trim() ?: title.trim()
        if (suppressReopenUntil > System.currentTimeMillis() && typeFor(cleanTitle) != null) {
            mc.setScreen(null)
            return
        }
        val type = typeFor(cleanTitle) ?: run { reset(); return }
        if (!isTypeEnabled(type)) { reset(); return }

        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val now = System.currentTimeMillis()
        val windowId = screen.menu.containerId

        // Hypixel replaces the window ID after clicks; the terminal session continues.
        if (beginTerminal(cleanTitle, now)) {
            currentClickDelayMs = if (type == Type.MELODY) {
                Config.autoTerminalMelodyFirstClickDelayMs.toLong()
            } else {
                nextFirstClickDelayMs()
            }

            // Melody party announcement
            if (type == Type.MELODY && Config.autoTerminalAnnounceMelody) {
                val msg = Config.autoTerminalMelodyMessage.trim()
                if (msg.isNotEmpty()) {
                    mc.player?.connection?.sendCommand("pc $msg")
                }
            }
        }

        // Dedicated Melody handling: does not stall behind standard click delays
        if (type == Type.MELODY) {
            if (firstClickPending && now - terminalOpenedAt < currentClickDelayMs) {
                return
            }
            firstClickPending = false

            // Process queued Melody skip clicks first if available
            if (melodySkipQueue.isNotEmpty()) {
                if (now - lastClickAt >= 40L) {
                    val nextSlot = melodySkipQueue.removeFirst()
                    gameMode.handleContainerInput(windowId, nextSlot, 2, ContainerInput.CLONE, player)
                    recordClick(now, nextSlot, 40L)
                }
                return
            }

            val slots = screen.menu.slots
            val size = type.slotCount
            if (slots.size < size) return
            val items = slots.take(size).map { it.item }
            val click = melodyClick(items, now) ?: return

            gameMode.handleContainerInput(windowId, click.slot, 2, ContainerInput.CLONE, player)
            recordClick(now, click.slot, 40L)
            return
        }

        // Delay timer check: first click delay applies ONLY to the first click from opening
        if (!canClick(now)) return

        val slots = screen.menu.slots
        val size = type.slotCount
        if (slots.size < size) return
        val items = slots.take(size).map { it.item }
        val click = nextClick(type, cleanTitle, items, now) ?: return

        // Rubix repeat guard
        if (click.slot == lastSlot && type == Type.RUBIX && now - lastClickAt < RUBIX_REPEAT_GUARD_MS) return

        // Rubix accepts left-click (0) and right-click (1) with PICKUP.
        // Other terminals use middle-click (CLONE) to prevent client inventory desyncs.
        if (type == Type.RUBIX) {
            gameMode.handleContainerInput(windowId, click.slot, click.button, ContainerInput.PICKUP, player)
        } else {
            gameMode.handleContainerInput(windowId, click.slot, 2, ContainerInput.CLONE, player)
        }

        recordClick(now, click.slot, nextClickDelayMs())
    }

    private var lastSlot = -1

    internal fun beginTerminal(title: String, now: Long): Boolean {
        if (lastTerminalTitle == title) return false
        reset()
        lastTerminalTitle = title
        terminalOpenedAt = now
        lastClickAt = now
        return true
    }

    internal fun canClick(now: Long): Boolean =
        now - (if (firstClickPending) terminalOpenedAt else lastClickAt) >= currentClickDelayMs

    internal fun recordClick(now: Long, slot: Int, delayMs: Long) {
        lastClickAt = now
        lastSlot = slot
        clickedSlotsWithTime[slot] = now
        firstClickPending = false
        currentClickDelayMs = delayMs
    }

    private data class Click(val slot: Int, val button: Int = 0)
    private enum class Type(val slotCount: Int) { COLORS(54), MELODY(54), NUMBERS(36), REDGREEN(45), RUBIX(45), STARTWITH(45) }

    fun isTerminalTitle(title: String): Boolean {
        val clean = ChatFormatting.stripFormatting(title)?.trim() ?: title.trim()
        return typeFor(clean) != null
    }

    private fun isTypeEnabled(type: Type): Boolean = when (type) {
        Type.COLORS -> Config.autoTermColors
        Type.MELODY -> Config.autoTermMelody
        Type.NUMBERS -> Config.autoTermNumbers
        Type.REDGREEN -> Config.autoTermRedGreen
        Type.RUBIX -> Config.autoTermRubix
        Type.STARTWITH -> Config.autoTermStartsWith
    }

    private fun typeFor(title: String): Type? = when {
        title.startsWith("Select all the ", true) -> Type.COLORS
        title.startsWith("Click the button on time!", true) -> Type.MELODY
        title.startsWith("Click in order!", true) -> Type.NUMBERS
        title.startsWith("Correct all the panes!", true) -> Type.REDGREEN
        title.startsWith("Change all to same color!", true) -> Type.RUBIX
        title.startsWith("What starts with:", true) -> Type.STARTWITH
        else -> null
    }

    private fun nextClick(type: Type, title: String, items: List<ItemStack>, now: Long): Click? = when (type) {
        Type.NUMBERS -> items.mapIndexedNotNull { i, stack ->
            if (isRecentlyClicked(i, now)) null
            else if (stack.`is`(Items.RED_STAINED_GLASS_PANE)) i to stack.count
            else null
        }
            .sortedBy { it.second }
            .take(NUMBER_TERM_COUNT)
            .minByOrNull { it.second }
            ?.let { Click(it.first) }

        Type.REDGREEN -> items.indices.firstOrNull { i ->
            if (isRecentlyClicked(i, now)) false
            else items[i].`is`(Items.RED_STAINED_GLASS_PANE)
        }?.let { Click(it) }

        Type.COLORS -> {
            val match = Regex("Select all the (.+?) items!?", RegexOption.IGNORE_CASE).find(title)
                ?: return null
            val wanted = match.groupValues[1]
            items.indices.firstOrNull { i ->
                if (isRecentlyClicked(i, now)) return@firstOrNull false
                val stack = items[i]
                if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@firstOrNull false
                if (TerminalHelper.isSelected(stack)) return@firstOrNull false
                TerminalHelper.matchesColor(stack, wanted)
            }?.let { Click(it) }
        }

        Type.STARTWITH -> {
            val match = Regex("What starts with: '(.+?)'\\??", RegexOption.IGNORE_CASE).find(title)
                ?: return null
            val wanted = match.groupValues[1].lowercase(java.util.Locale.ROOT)
            items.indices.firstOrNull { i ->
                if (isRecentlyClicked(i, now)) return@firstOrNull false
                val stack = items[i]
                if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@firstOrNull false
                if (TerminalHelper.isSelected(stack)) return@firstOrNull false
                val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(java.util.Locale.ROOT) ?: return@firstOrNull false
                name.startsWith(wanted)
            }?.let { Click(it) }
        }

        Type.RUBIX -> rubixClick(items)
        Type.MELODY -> melodyClick(items, now)
    }

    private fun rubixClick(items: List<ItemStack>): Click? {
        val allowed = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        val panes = allowed.mapNotNull { slot ->
            val stack = items.getOrNull(slot) ?: return@mapNotNull null
            val idx = TerminalHelper.rubixColorIndex(stack)
            if (idx >= 0) slot to idx else null
        }
        if (panes.size < 9) return null

        val target: Int
        if (lastRubixTarget != null) {
            target = lastRubixTarget!!
        } else {
            val costs = IntArray(5)
            for (t in 0..4) {
                for (p in panes) {
                    val fwd = (t - p.second + 5) % 5
                    val bwd = (p.second - t + 5) % 5
                    costs[t] += minOf(fwd, bwd)
                }
            }
            target = costs.indices.minByOrNull { costs[it] } ?: return null
            lastRubixTarget = target
        }

        val mismatch = panes.firstOrNull { it.second != target } ?: return null
        val current = mismatch.second
        val fwd = (target - current + 5) % 5
        val bwd = (current - target + 5) % 5

        // Left click (button 0) cycles forward (+1). Right click (button 1) cycles backward (-1).
        val button = if (fwd <= bwd) 0 else 1
        return Click(mismatch.first, button)
    }

    private fun melodyClick(items: List<ItemStack>, now: Long): Click? {
        val magenta = items.indexOfFirst { it.`is`(Items.MAGENTA_STAINED_GLASS_PANE) }
        val lime = items.indexOfFirst { it.`is`(Items.LIME_STAINED_GLASS_PANE) }
        if (magenta < 0 || lime < 0) return null
        val correct = (magenta % 9) - 1
        val current = (lime % 9) - 1
        val buttonRow = floor(lime / 9.0).toInt() - 1
        if (current != correct || buttonRow !in 0..3) return null

        // Prevent spam-clicking the same row during a single alignment window (debounce 300ms)
        if (buttonRow == lastMelodyRow && now - lastMelodyRowClickAt < 300L) return null

        val clickedSlot = buttonRow * 9 + 16

        // Melody Skip feature
        if (Config.autoTerminalMelodySkip) {
            val skipAllowed = !(buttonRow == 0 && Config.autoTerminalDontSkipFirst)
            if (skipAllowed && buttonRow < 3) {
                melodySkipQueue.clear()
                for (r in (buttonRow + 1)..3) {
                    melodySkipQueue.add(r * 9 + 16)
                }
            }
        }

        lastMelodyRow = buttonRow
        lastMelodyRowClickAt = now
        return Click(clickedSlot)
    }

    private const val NUMBER_TERM_COUNT = 10

    private fun gaussianRandom(minimum: Int, maximum: Int): Int {
        val minValue = min(minimum, maximum)
        val maxValue = max(minimum, maximum)
        if (minValue == maxValue) return minValue

        val u1 = 1.0 - Random.nextDouble()
        val u2 = 1.0 - Random.nextDouble()
        val gaussian = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)

        val mean = minValue + (maxValue - minValue) / 2.0
        val stdDev = (maxValue - minValue) / 6.0
        val result = gaussian * stdDev + mean

        return result.coerceIn(minValue.toDouble(), maxValue.toDouble()).toInt()
    }

    private fun nextFirstClickDelayMs(): Long {
        val base = Config.autoTerminalFirstClickDelayMs.coerceAtLeast(0)
        if (!Config.autoTerminalRandomDelay) return base.toLong()
        val min = (base - 30).coerceAtLeast(50)
        val max = base + 30
        return gaussianRandom(min, max).toLong()
    }

    private fun nextClickDelayMs(): Long {
        if (!Config.autoTerminalRandomDelay) {
            return Config.autoTerminalClickDelayMs.coerceAtLeast(0).toLong()
        }

        val target = Config.autoTerminalClickDelayMs.coerceAtLeast(0)
        val minDelay = Config.autoTerminalMinRandomDelayMs.coerceAtLeast(0)
        val maxDelay = Config.autoTerminalMaxRandomDelayMs.coerceAtLeast(0)

        val lower = if (minDelay > 0 && maxDelay >= minDelay) minDelay else (target - 20).coerceAtLeast(50)
        val upper = if (maxDelay > 0 && maxDelay >= lower) maxDelay else (target + 20).coerceAtLeast(lower)

        return gaussianRandom(lower, upper).toLong()
    }

    fun onEscape() {
        suppressReopenUntil = System.currentTimeMillis() + 750L
        reset()
    }

    private fun reset() {
        lastClickAt = 0L
        terminalOpenedAt = 0L
        lastSlot = -1
        lastTerminalTitle = null
        firstClickPending = true
        currentClickDelayMs = 0L
        melodySkipQueue.clear()
        clickedSlotsWithTime.clear()
        lastRubixTarget = null
        lastMelodyRow = -1
        lastMelodyRowClickAt = 0L
    }
}
