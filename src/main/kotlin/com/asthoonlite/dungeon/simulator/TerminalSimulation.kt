package com.asthoonlite.dungeon.simulator

import com.asthoonlite.dungeon.TerminalSolver.Kind
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import kotlin.random.Random

/**
 * The six local practice puzzles, adapted from Odin's termsim handlers at
 * 833e0533. Odin's BSD notice is in META-INF/licenses/Odin.txt.
 *
 * Randomness and time come from the caller. This object owns the board and
 * validates input independently of the solver; it needs no player, world,
 * client singleton or configuration. A kind and seed reproduce a board.
 */
class TerminalSimulation(
    val kind: Kind,
    val seed: Long,
    val openedAtMs: Long,
    val melodyStepMs: Long = 500L,
    private val random: Random = Random(seed)
) {
    data class ClickResult(
        val accepted: Boolean,
        val completed: Boolean,
        val reason: String,
        val changedSlots: List<Int>
    )

    val title: String
    /** The initially required panes/buttons, before any input is applied. */
    val correctSlots: Set<Int>
    /** Minimum initial work; Rubix may also finish at any other uniform color. */
    val goalClicks: Int
    val items: List<ItemStack> get() = stacks.map { it.copy() }
    var completed: Boolean = false
        private set
    var revision: Long = 0
        private set
    var completedAtMs: Long? = null
        private set

    val melodyRow: Int get() = currentRow
    val melodyColumn: Int get() = movingColumn
    val melodyTargetColumn: Int get() = markerColumn

    private val stacks = MutableList(kind.slotCount) { pane(Items.BLACK_STAINED_GLASS_PANE) }
    private val selected = mutableSetOf<Int>()
    private var wantedItems: Set<Item> = emptySet()
    private var wantedLetter = ""
    private var currentRow = 1
    private var movingColumn = 1
    private var movingDirection = 1
    private var markerColumn = 1
    private var nextMelodyMoveAtMs = openedAtMs

    init {
        require(melodyStepMs > 0L) { "Melody movement interval must be positive" }
        title = when (kind) {
            Kind.PANES -> {
                for (slot in grid(1..3, 2..6)) stacks[slot] = pane(
                    if (random.nextDouble() > 0.75) Items.LIME_STAINED_GLASS_PANE else Items.RED_STAINED_GLASS_PANE
                )
                // A practice round must offer at least one input, even at the
                // rare seed where Odin's random board would already be done.
                if (stacks.none { it.`is`(Items.RED_STAINED_GLASS_PANE) }) stacks[11] = pane(Items.RED_STAINED_GLASS_PANE)
                "Correct all the panes!"
            }
            Kind.ORDER -> {
                val numbers = (1..10).shuffled(random)
                grid(1..2, 2..6).forEachIndexed { index, slot ->
                    stacks[slot] = pane(Items.RED_STAINED_GLASS_PANE, numbers[index], "§a${numbers[index]}")
                }
                "Click in order!"
            }
            Kind.RUBIX -> {
                for (slot in RUBIX_SLOTS) stacks[slot] = pane(RUBIX_ITEMS[random.nextInt(RUBIX_ITEMS.size)])
                if (RUBIX_SLOTS.map { stacks[it].item }.distinct().size == 1) {
                    val first = RUBIX_ITEMS.indexOf(stacks[RUBIX_SLOTS.first()].item)
                    stacks[RUBIX_SLOTS.last()] = pane(RUBIX_ITEMS[(first + 1) % RUBIX_ITEMS.size])
                }
                "Change all to same color!"
            }
            Kind.SELECT -> {
                val color = DyeColor.entries.random(random)
                wantedItems = possibleColorItems(color).toSet()
                val slots = grid(1..4, 1..7)
                val guaranteed = slots.random(random)
                val otherColors = DyeColor.entries.filter { it != color }
                for (slot in slots) {
                    val actual = if (slot == guaranteed || random.nextDouble() > 0.75) color else otherColors.random(random)
                    stacks[slot] = ItemStack(possibleColorItems(actual).random(random))
                }
                "Select all the ${color.name.replace("LIGHT_GRAY", "SILVER").replace('_', ' ')} items!"
            }
            Kind.STARTS -> {
                // Odin samples registry identifiers, then accepts according to
                // the displayed name. Restrict its initials to ones available
                // in the current language; when there is no shared initial,
                // build the puzzle from displayed names instead.
                val catalog = BuiltInRegistries.ITEM.filter {
                    it != Items.AIR && !BuiltInRegistries.ITEM.getKey(it).path.contains("pane", true)
                }.sortedBy { BuiltInRegistries.ITEM.getKey(it).toString() }
                val choice = startsCatalogChoice(catalog.map {
                    BuiltInRegistries.ITEM.getKey(it).path to ItemStack(it).hoverName.string
                }, random)
                wantedLetter = choice.letter
                val matches = choice.matches.map { catalog[it] }
                val other = choice.other.map { catalog[it] }.ifEmpty { matches }
                val guaranteed = grid(1..1, 1..7).random(random)
                for (slot in grid(1..3, 1..7)) {
                    val pool = if (slot == guaranteed || random.nextDouble() > 0.7) matches else other
                    stacks[slot] = ItemStack(pool.random(random)).apply {
                        // Odin tracks naturally glowing items separately. An
                        // explicit initial override makes selection visible to
                        // the practice screen and ASL's normal solver alike.
                        set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
                    }
                }
                "What starts with: '$wantedLetter'?"
            }
            Kind.MELODY -> {
                markerColumn = random.nextInt(1, 6)
                renderMelody()
                // Odin moves on its first container tick, then every ten
                // ticks. Time-based advancement also handles missed frames.
                nextMelodyMoveAtMs = openedAtMs + minOf(50L, melodyStepMs)
                "Click the button on time!"
            }
        }
        correctSlots = when (kind) {
            Kind.PANES, Kind.ORDER -> stacks.indices.filter { stacks[it].`is`(Items.RED_STAINED_GLASS_PANE) }.toSet()
            Kind.SELECT -> stacks.indices.filter { stacks[it].item in wantedItems }.toSet()
            Kind.STARTS -> stacks.indices.filter { stacks[it].hoverName.string.startsWith(wantedLetter, true) }.toSet()
            Kind.RUBIX -> RUBIX_SLOTS.toSet()
            Kind.MELODY -> setOf(16, 25, 34)
        }
        goalClicks = if (kind == Kind.RUBIX) {
            (0..4).minOf { target -> RUBIX_SLOTS.sumOf { slot ->
                val from = RUBIX_ITEMS.indexOf(stacks[slot].item)
                minOf((target - from + 5) % 5, (from - target + 5) % 5)
            } }
        } else correctSlots.size
    }

    /** Advance melody against the caller's clock and return the changed slots. */
    fun tick(nowMs: Long): List<Int> {
        if (kind != Kind.MELODY || completed || nowMs < nextMelodyMoveAtMs) return emptyList()
        val old = stacks.map { it.item }
        var steps = (nowMs - nextMelodyMoveAtMs) / melodyStepMs + 1L
        // Column and direction repeat every eight moves. Skip complete
        // cycles rather than looping over a long background pause.
        nextMelodyMoveAtMs += steps * melodyStepMs
        steps %= 8L
        repeat(steps.toInt()) {
            movingColumn += movingDirection
            if (movingColumn == 1 || movingColumn == 5) movingDirection *= -1
        }
        renderMelody()
        val changed = stacks.indices.filter { old[it] != stacks[it].item }
        if (changed.isNotEmpty()) revision++
        return changed
    }

    /** Validate and apply exactly one slot input at the supplied time. */
    fun applyClick(slot: Int, button: Int, nowMs: Long): ClickResult {
        val timedChanges = tick(nowMs)
        fun rejected(reason: String) = ClickResult(false, completed, reason, timedChanges)
        if (completed) return rejected("completed")
        if (nowMs < openedAtMs) return rejected("before_open")
        if (slot !in stacks.indices) return rejected("outside_terminal")
        if (button !in 0..2) return rejected("unsupported_button")
        if (stacks[slot].`is`(Items.BLACK_STAINED_GLASS_PANE)) return rejected("background")

        val changed = mutableListOf<Int>()
        when (kind) {
            Kind.PANES -> {
                stacks[slot] = pane(if (stacks[slot].`is`(Items.RED_STAINED_GLASS_PANE)) Items.LIME_STAINED_GLASS_PANE else Items.RED_STAINED_GLASS_PANE)
                changed += slot
                completed = stacks.none { it.`is`(Items.RED_STAINED_GLASS_PANE) }
            }
            Kind.ORDER -> {
                val next = stacks.indices.filter { stacks[it].`is`(Items.RED_STAINED_GLASS_PANE) }.minByOrNull { stacks[it].count }
                if (next != slot) return rejected("out_of_order")
                stacks[slot] = pane(Items.LIME_STAINED_GLASS_PANE, stacks[slot].count)
                changed += slot
                completed = stacks.none { it.`is`(Items.RED_STAINED_GLASS_PANE) }
            }
            Kind.RUBIX -> {
                if (slot !in RUBIX_SLOTS) return rejected("background")
                val current = RUBIX_ITEMS.indexOf(stacks[slot].item)
                stacks[slot] = pane(RUBIX_ITEMS[(current + if (button == 1) 4 else 1) % 5])
                changed += slot
                completed = RUBIX_SLOTS.all { stacks[it].item == stacks[RUBIX_SLOTS.first()].item }
            }
            Kind.SELECT, Kind.STARTS -> {
                if (slot !in correctSlots) return rejected(if (kind == Kind.SELECT) "wrong_color" else "wrong_letter")
                if (!selected.add(slot)) return rejected("already_selected")
                stacks[slot] = stacks[slot].copy().apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
                changed += slot
                completed = selected.containsAll(correctSlots)
            }
            Kind.MELODY -> {
                if (slot != currentRow * 9 + 7) return rejected("inactive_row")
                if (movingColumn != markerColumn) return rejected("off_beat")
                selected += slot
                currentRow++
                // Odin's later rows draw targets in columns 1..4.
                markerColumn = random.nextInt(1, 5)
                val old = stacks.map { it.item }
                renderMelody()
                changed += stacks.indices.filter { old[it] != stacks[it].item }
                completed = currentRow > 3
            }
        }
        if (completed) completedAtMs = nowMs
        revision++
        return ClickResult(true, completed, "accepted", (timedChanges + changed).distinct())
    }

    private fun renderMelody() {
        for (slot in stacks.indices) {
            val row = slot / 9
            val col = slot % 9
            val item = when {
                (row == 0 || row == 4) && col == markerColumn -> Items.MAGENTA_STAINED_GLASS_PANE
                row in 1..3 && col == 7 && slot in selected -> Items.LIME_STAINED_GLASS_PANE
                row in 1..3 && row == currentRow && col == movingColumn -> Items.LIME_STAINED_GLASS_PANE
                row in 1..3 && row == currentRow && col in 1..5 -> Items.RED_STAINED_GLASS_PANE
                row in 1..3 && row == currentRow && col == 7 -> Items.LIME_TERRACOTTA
                row in 1..3 && col == 7 -> Items.RED_TERRACOTTA
                row in 1..3 && col in 1..5 -> Items.WHITE_STAINED_GLASS_PANE
                else -> Items.BLACK_STAINED_GLASS_PANE
            }
            stacks[slot] = pane(item)
        }
    }

    private fun possibleColorItems(color: DyeColor): List<Item> {
        val prefix = color.name.lowercase(Locale.ROOT)
        fun item(suffix: String) = requireNotNull(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("${prefix}_$suffix")))
        return listOf(item("stained_glass"), item("wool"), item("concrete"), when (color) {
            DyeColor.WHITE -> Items.BONE_MEAL
            DyeColor.BLUE -> Items.LAPIS_LAZULI
            DyeColor.BLACK -> Items.INK_SAC
            DyeColor.BROWN -> Items.COCOA_BEANS
            else -> item("dye")
        })
    }

    companion object {
        internal data class StartsCatalogChoice(val letter: String, val matches: List<Int>, val other: List<Int>)

        private val START_LETTERS = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W")
        private val RUBIX_SLOTS = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        private val RUBIX_ITEMS = listOf(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
            Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS_PANE)

        fun random(seed: Long, openedAtMs: Long): TerminalSimulation =
            TerminalSimulation(Kind.entries.random(Random(seed)), seed, openedAtMs)

        /** Registry path and displayed name in the same stable item order. */
        internal fun startsCatalogChoice(catalog: List<Pair<String, String>>, random: Random): StartsCatalogChoice {
            val supported = START_LETTERS.filter { letter ->
                catalog.any { (id, name) -> id.startsWith(letter, true) && name.startsWith(letter, true) }
            }
            if (supported.isNotEmpty()) {
                val letter = supported.random(random)
                return StartsCatalogChoice(letter,
                    catalog.indices.filter { catalog[it].first.startsWith(letter, true) && catalog[it].second.startsWith(letter, true) },
                    catalog.indices.filter { !catalog[it].first.startsWith(letter, true) })
            }
            val displayed = catalog.map { it.second }.filter { it.isNotEmpty() }
            require(displayed.isNotEmpty()) { "An item catalog must contain a displayed name" }
            val name = displayed.random(random)
            val letter = name.substring(0, name.offsetByCodePoints(0, 1))
            return StartsCatalogChoice(letter,
                catalog.indices.filter { catalog[it].second.startsWith(letter, true) },
                catalog.indices.filter { !catalog[it].second.startsWith(letter, true) })
        }

        private fun grid(rows: IntRange, columns: IntRange) = rows.flatMap { row -> columns.map { row * 9 + it } }
        private fun pane(item: Item, count: Int = 1, name: String = "") = ItemStack(item, count).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal(name))
        }
    }
}
