package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.minecraft.ChatFormatting
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import java.util.regex.Pattern

/**
 * What a terminal is, what needs clicking in it, and how that should look.
 *
 * This object is the single source of truth for terminal identification:
 * [AutoTerminal] reads its candidates and sends them as packets, the slot
 * overlay in `MixinHandledScreen` colours them, and the next-click marker
 * draws around one of them. One title parser, one slot-count table, one
 * candidate list — nothing here can drift away from what the clicker does,
 * which is exactly how a solver that "does nothing" happens: the highlight
 * and the clicker disagree about what a terminal even is.
 *
 * Nothing here sends input. Clicks are AutoTerminal's job and go down the
 * packet path the player would use by hand.
 */
object TerminalSolver {
    /** Odin's practice board has ten panes; live boards are read from their slots. */
    internal const val NUMBER_TERM_COUNT = 10

    /**
     * The six terminals. [slotCount] is the container size Hypixel gives the
     * type, used to clip the slot list before it is read — anything past it is
     * the player's own inventory, not terminal.
     */
    enum class Kind(val slotCount: Int) {
        /** "Select all the red items!" — 6×9. */
        SELECT(54),
        /** "Click the button on time!" — 6×9. */
        MELODY(54),
        /** "Click in order!" — 4×9. */
        ORDER(36),
        /** "Correct all the panes!" — 5×9. */
        PANES(45),
        /** "Change all to same color!" — 5×9. */
        RUBIX(45),
        /** "What starts with: 'a'?" — 5×9. */
        STARTS(45),
    }

    /** Format-stripped, trimmed title. Every comparison goes through this. */
    fun cleanTitle(screenTitle: String): String =
        ChatFormatting.stripFormatting(screenTitle)?.trim() ?: screenTitle.trim()

    /**
     * What kind of terminal this title names, or null.
     *
     * Matched with `contains` rather than `startsWith`: the phrase is what
     * identifies a terminal, and a simulator that wraps it ("P3 · Click in
     * order!") or a plugin that appends to it is still the same terminal. A
     * prefix test throws all of those away silently, which is the failure
     * shape this is written against.
     */
    fun kindOf(screenTitle: String): Kind? {
        val title = cleanTitle(screenTitle)
        if (title.isEmpty()) return null
        val lower = title.lowercase(Locale.ROOT)
        return when {
            lower.contains("select all the") -> Kind.SELECT
            lower.contains("click the button on time") -> Kind.MELODY
            lower.contains("click in order") -> Kind.ORDER
            lower.contains("correct all the panes") -> Kind.PANES
            lower.contains("change all to same color") -> Kind.RUBIX
            lower.contains("what starts with") -> Kind.STARTS
            else -> null
        }
    }

    /**
     * Whether [screenTitle] names a terminal this solver can draw.
     *
     * The title is stripped of formatting first: Hypixel's container titles
     * are not always plain text, and a gate that tests the raw string lets a
     * perfectly ordinary terminal through to nowhere — [colorFor] would strip
     * it and answer, but the gate rejects before it is ever asked.
     */
    fun isTerminalTitle(screenTitle: String): Boolean = kindOf(screenTitle) != null

    /**
     * Tint for one slot, or null when it has no business being tinted.
     *
     * [rubixTarget] is the colour the Rubix clicker has already committed to,
     * if it has one. Without it the tint would keep recomputing the *cheapest*
     * colour as panes change while the clicker drives a fixed one, and the
     * picture and the pointer would end up disagreeing about what "done"
     * looks like — the one thing a two-layer solver must never do.
     */
    fun colorFor(
        screenTitle: String,
        slot: Int,
        stack: ItemStack,
        all: List<ItemStack>,
        rubixTarget: Int? = null
    ): Int? {
        if (!Config.terminalSolverEnabled || slot < 0) return null
        val cleanTitle = cleanTitle(screenTitle)
        val type = kindOf(cleanTitle) ?: return null
        val size = type.slotCount
        if (slot >= size) return null
        val terminalAll = all.take(size)
        return when (type) {
            Kind.PANES -> if (stack.`is`(Items.RED_STAINED_GLASS_PANE)) 0xCC55FFFF.toInt() else null
            Kind.ORDER -> orderColor(slot, stack, terminalAll)
            Kind.SELECT -> selectColor(cleanTitle, stack)
            Kind.STARTS -> startsColor(cleanTitle, stack)
            Kind.RUBIX -> rubixColor(slot, stack, terminalAll, rubixTarget)
            Kind.MELODY -> melodyColor(slot, stack, terminalAll)
        }
    }

    /**
     * Text to print on a slot's tile, or null for none.
     *
     * Only the number terminal gets a label. It is the one whose tiles stop
     * being self-describing the moment the solver paints over the item: the
     * number *is* the item, and without it printed back the tile says "click
     * here" with nothing to say what "here" is. Every other terminal says
     * everything it needs to say in colour alone.
     */
    fun labelFor(slot: Int, stack: ItemStack?, kind: Kind): String? {
        if (kind != Kind.ORDER) return null
        if (stack == null || stack.isEmpty) return null
        if (slot < 0 || slot >= kind.slotCount) return null
        if (!stack.`is`(Items.RED_STAINED_GLASS_PANE)) return null
        return numberValue(stack)?.toString()
    }

    /** Some menus put the number in the formatted name instead of the stack count. */
    internal fun numberValue(stack: ItemStack): Int? {
        if (stack.isEmpty || !isNumberPane(stack)) return null
        val name = cleanTitle(stack.hoverName.string)
        return name.removePrefix("#").trim().toIntOrNull()?.takeIf { it > 0 }
            ?: stack.count.takeIf { it > 0 }
    }

    private fun isNumberPane(stack: ItemStack): Boolean =
        stack.`is`(Items.RED_STAINED_GLASS_PANE) || stack.`is`(Items.LIME_STAINED_GLASS_PANE) ||
            stack.`is`(Items.GREEN_STAINED_GLASS_PANE)

    /** Completed green panes retain their places so the live grid does not shrink. */
    internal fun numberSlots(items: List<ItemStack>): List<Int> =
        items.take(Kind.ORDER.slotCount).mapIndexedNotNull { slot, stack ->
            slot.takeIf { numberValue(stack) != null }
        }

    private fun pendingNumbers(items: List<ItemStack>): List<Pair<Int, Int>> =
        items.take(Kind.ORDER.slotCount).mapIndexedNotNull { slot, stack ->
            if (stack.`is`(Items.RED_STAINED_GLASS_PANE)) numberValue(stack)?.let { slot to it } else null
        }

    /**
     * Every slot in this terminal that still wants a click, unordered.
     *
     * [blocked] is a set of slots whose last click has not come back from the
     * server yet — including them would make the solver pick a pane it has
     * already hit and sit there spinning. [rubixTarget] is the colour the
     * Rubix terminal is being driven to; null means "decide one now".
     *
     * This is what both the clicker and the next-click marker read, so the
     * marker can only ever point at something the clicker would actually do.
     */
    fun clickCandidates(
        screenTitle: String,
        items: List<ItemStack>,
        blocked: Set<Int> = emptySet(),
        rubixTarget: Int? = null
    ): List<Int> {
        val cleanTitle = cleanTitle(screenTitle)
        val type = kindOf(cleanTitle) ?: return emptyList()
        // Read the slots that are there rather than insisting on the full
        // chest. A simulator's window or a practice world's shorter menu stops
        // short of the player's inventory underneath the terminal's own rows,
        // and an empty answer is indistinguishable from a finished puzzle —
        // the clicker would sit on a screen it can otherwise drive and never
        // say why. Everything below already guards each slot it reaches for.
        val all = items.take(type.slotCount)

        return when (type) {
            Kind.PANES -> all.indices.filter { i ->
                i !in blocked && all[i].`is`(Items.RED_STAINED_GLASS_PANE)
            }

            // The number terminal is a chain: the pane whose count is still
            // the lowest is the only one the server will accept next.
            Kind.ORDER -> {
                val reds = pendingNumbers(all).filter { it.first !in blocked }
                if (reds.isEmpty()) return emptyList()
                val next = reds.minOf { it.second }
                reds.filter { it.second == next }.map { it.first }
            }

            Kind.SELECT -> {
                val wanted = selectTarget(cleanTitle) ?: return emptyList()
                all.indices.filter { i ->
                    if (i in blocked) return@filter false
                    val stack = all[i]
                    if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@filter false
                    if (TerminalHelper.isSelected(stack)) return@filter false
                    TerminalHelper.matchesColor(stack, wanted)
                }
            }

            Kind.STARTS -> {
                val wanted = startsTarget(cleanTitle) ?: return emptyList()
                all.indices.filter { i ->
                    if (i in blocked) return@filter false
                    val stack = all[i]
                    if (stack.isEmpty || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return@filter false
                    if (TerminalHelper.isSelected(stack)) return@filter false
                    val name = ChatFormatting.stripFormatting(stack.hoverName.string)
                        ?.lowercase(Locale.ROOT) ?: return@filter false
                    name.startsWith(wanted)
                }
            }

            Kind.RUBIX -> {
                val target = rubixTarget ?: optimalRubixTarget(all) ?: return emptyList()
                val panes = rubixPanes(all)
                if (panes.size < 9) return emptyList()
                panes.filter { it.second != target && it.first !in blocked }.map { it.first }
            }

            // Melody is one button, not a set: whatever candidate exists is
            // the click. The row debounce and the skip queue are the
            // clicker's business, not this list's.
            Kind.MELODY -> listOfNotNull(melodyCandidate(all)?.takeIf { it !in blocked })
        }
    }

    /** The name the progress readout prints for this kind. */
    fun displayName(kind: Kind): String = when (kind) {
        Kind.SELECT -> "Colors"
        Kind.MELODY -> "Melody"
        Kind.ORDER -> "Numbers"
        Kind.PANES -> "Red Green"
        Kind.RUBIX -> "Rubix"
        Kind.STARTS -> "Starts With"
    }

    /**
     * How many clicks this terminal wants, read once when the screen opens.
     *
     * The denominator of the progress readout, and the reason it is not simply
     * "candidates right now": two of the six kinds do not have a candidate
     * list that means what the question asks.
     *
     * Numbers is a chain — only the lowest count is ever a candidate, so its
     * list is one pane wide while the terminal wants every numbered pane on
     * the board. Rubix wants a *colour*, not a pane, and a pane sitting two
     * rings from the target is two clicks of work the candidate list counts as
     * one. Melody has no list at all; its rows are the count. The other three
     * are exactly their candidate list, which at open time is everything still
     * to click.
     */
    fun goalFor(screenTitle: String, items: List<ItemStack>, rubixTarget: Int? = null): Int {
        val type = kindOf(cleanTitle(screenTitle)) ?: return 0
        val all = items.take(type.slotCount)
        return when (type) {
            Kind.ORDER -> pendingNumbers(all).size
            Kind.RUBIX -> {
                val target = rubixTarget ?: optimalRubixTarget(all) ?: return 0
                rubixPanes(all).sumOf { rubixDistance(it.second, target) }
            }
            Kind.MELODY -> melodyRows(all).size
            else -> clickCandidates(screenTitle, items, rubixTarget = rubixTarget).size
        }
    }

    /**
     * The single slot a marker should ring: the candidate the clicker is about
     * to take, so the picture and the pointer agree about where the next
     * click is going. Which candidate that is comes from [clickOrder] — the
     * same setting the clicker reads, defaulting to Human (nearest) so a
     * caller that does not care gets the shipped behaviour.
     * The slot it is already on is skipped while anything
     * else is available — a marker sitting on the pane that was just clicked
     * says nothing about where to go next.
     */
    fun nextClickSlot(
        screenTitle: String,
        items: List<ItemStack>,
        blocked: Set<Int> = emptySet(),
        rubixTarget: Int? = null,
        lastSlot: Int? = null,
        clickOrder: Int = TerminalClickOrder.ORDER_HUMAN
    ): Int? {
        val kind = kindOf(screenTitle) ?: return null
        val candidates = clickCandidates(screenTitle, items, blocked, rubixTarget)
        if (candidates.isEmpty()) return null
        if (kind == Kind.RUBIX && lastSlot != null && lastSlot in candidates) {
            return lastSlot
        }
        val elsewhere = lastSlot?.let { s -> candidates.filter { it != s } }.orEmpty()
        val pool = if (elsewhere.isNotEmpty()) elsewhere else candidates
        return TerminalClickOrder.pick(clickOrder, pool, lastSlot, kind.slotCount)
    }

    // ── Per-type target extraction ───────────────────────────────────────────

    // Compiled once: these are consulted for every slot of every frame the
    // overlay is up, and a fresh Pattern each time is the kind of waste that
    // only ever shows up as a frame hitch nobody can explain.
    private val SELECT_PATTERN = Pattern.compile("Select all the (.+?) items!?", Pattern.CASE_INSENSITIVE)
    private val STARTS_PATTERN = Pattern.compile("What starts with: '(.+?)'\\??", Pattern.CASE_INSENSITIVE)

    private fun selectTarget(title: String): String? =
        SELECT_PATTERN.matcher(title).let { if (it.find()) it.group(1) else null }

    private fun startsTarget(title: String): String? =
        STARTS_PATTERN.matcher(title).let { if (it.find()) it.group(1).lowercase(Locale.ROOT) else null }

    /** Slot indices of the nine Rubix panes. */
    private fun rubixPanes(all: List<ItemStack>): List<Pair<Int, Int>> =
        RUBIX_SLOTS.mapNotNull { slot ->
            val predicted = AutoTerminal.rubixPredicted(slot)
            if (predicted != null && predicted in 0..4) {
                slot to predicted
            } else {
                val stack = all.getOrNull(slot) ?: return@mapNotNull null
                val idx = TerminalHelper.rubixColorIndex(stack)
                if (idx >= 0) slot to idx else null
            }
        }

    /**
     * The colour that costs the fewest clicks to reach, summed over all nine
     * panes. Pure: same pane state, same answer, which is what lets the marker
     * show the target before the clicker has committed to one.
     *
     * A left click walks the ring forward, a right click walks it back one, so
     * the cost of reaching a colour is the *shorter* way round — never the
     * forward distance alone, which is what used to pick a target that needed
     * four clicks a pane when two would do.
     */
    fun optimalRubixTarget(all: List<ItemStack>): Int? {
        val panes = rubixPanes(all)
        if (panes.size < 9) return null
        val costs = IntArray(5)
        for (target in 0..4) {
            for (p in panes) {
                costs[target] += rubixDistance(p.second, target)
            }
        }
        return costs.indices.minWithOrNull(compareBy({ costs[it] }, { -panes.count { p -> p.second == it } }))
    }

    /**
     * Clicks to walk one pane from colour [from] to colour [to] on the
     * five-colour ring, taking whichever direction is shorter.
     */
    fun rubixDistance(from: Int, to: Int): Int {
        val forward = (to - from + 5) % 5
        val backward = (from - to + 5) % 5
        return minOf(forward, backward)
    }

    /**
     * The button that gets a pane from [from] to [to] in [rubixDistance]
     * steps: 0 is a left click (colour +1), 1 is a right click (colour -1).
     * Ties go forward, because a left click is the one every other terminal
     * takes and the packet path for it is already warm.
     */
    fun rubixButton(from: Int, to: Int): Int =
        if ((to - from + 5) % 5 <= (from - to + 5) % 5) 0 else 1

    /**
     * Where a pane stands after [button] on the ring. The clicker applies
     * this the moment a packet goes out rather than when it comes back, which
     * is the whole reason Rubix can be spammed instead of polled.
     */
    fun rubixAdvance(index: Int, button: Int): Int =
        if (button == 0) (index + 1) % 5 else (index + 4) % 5

    data class MelodyRow(val row: Int, val completed: Boolean, val movingSlot: Int?) {
        val buttonSlot: Int get() = row * 9 + 7
    }

    /**
     * Melody ships three content rows: 1, 2, 3. Row 0 above them carries the
     * marker strip, row 4 below them is the indicator — and button evidence is
     * what stops that indicator being read as a fourth note row, which is the
     * mistake that used to park the clicker on a pane the server ignores.
     * Only the terminal's own rows are read; inventory items cannot become notes.
     */
    fun melodyRows(all: List<ItemStack>): List<MelodyRow> = (1..3).mapNotNull { row ->
        val button = all.getOrNull(row * 9 + 7) ?: return@mapNotNull null
        val doneButton = button.`is`(Items.LIME_STAINED_GLASS_PANE) ||
            button.`is`(Items.GREEN_STAINED_GLASS_PANE) || button.`is`(Items.LIME_CONCRETE) ||
            button.`is`(Items.EMERALD_BLOCK)
        val isButton = doneButton || button.`is`(Items.LIME_TERRACOTTA) ||
            button.`is`(Items.GREEN_TERRACOTTA) || button.`is`(Items.RED_TERRACOTTA) ||
            button.`is`(Items.YELLOW_TERRACOTTA)
        if (!isButton) return@mapNotNull null
        val panes = (row * 9 + 1)..(row * 9 + 5)
        val greens = panes.filter { all.getOrNull(it)?.let(::isMelodyPointer) == true }
        val completed = doneButton || greens.size == 5
        MelodyRow(row, completed, if (completed) null else greens.singleOrNull())
    }

    private fun isMelodyPointer(stack: ItemStack): Boolean =
        stack.`is`(Items.LIME_STAINED_GLASS_PANE) || stack.`is`(Items.GREEN_STAINED_GLASS_PANE)

    /** Header first, then the indicator immediately below the detected content. */
    fun melodyMarker(all: List<ItemStack>, rows: List<MelodyRow> = melodyRows(all)): Int? {
        if (rows.isEmpty()) return null
        for (row in listOf(0, rows.maxOf { it.row } + 1)) {
            for (col in 1..5) {
                val slot = row * 9 + col
                if (slot < Kind.MELODY.slotCount && all.getOrNull(slot)?.`is`(Items.MAGENTA_STAINED_GLASS_PANE) == true) return slot
            }
        }
        return null
    }

    /** The aligned, unfinished row. Reading this never commits a click. */
    fun melodyCandidate(all: List<ItemStack>): Int? {
        val rows = melodyRows(all)
        val column = (melodyMarker(all, rows) ?: return null) % 9
        return rows.firstOrNull { !it.completed && it.movingSlot?.rem(9) == column }?.buttonSlot
    }

    fun melodyNextButton(all: List<ItemStack>, afterSlot: Int): Int? =
        melodyRows(all).firstOrNull { !it.completed && it.buttonSlot > afterSlot }?.buttonSlot

    fun melodyActiveButton(all: List<ItemStack>): Int? =
        melodyRows(all).firstOrNull { !it.completed && it.movingSlot != null }?.buttonSlot

    internal val RUBIX_SLOTS = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    // ── Presentation ─────────────────────────────────────────────────────────

    private fun orderColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        if (!stack.`is`(Items.RED_STAINED_GLASS_PANE)) return null
        val ordered = pendingNumbers(all).sortedBy { it.second }
        val index = ordered.indexOfFirst { it.first == slot }
        return when (index) {
            0 -> 0xDD00E676.toInt()
            1 -> 0xCCFFFFFF.toInt()
            2 -> 0xCCFF3D3D.toInt()
            else -> 0x66333333
        }
    }

    private fun selectColor(title: String, stack: ItemStack): Int? {
        val wanted = selectTarget(title) ?: return null
        if (stack.isEmpty || TerminalHelper.isSelected(stack)) return null
        return if (TerminalHelper.matchesColor(stack, wanted)) 0xCC55FFFF.toInt() else null
    }

    private fun startsColor(title: String, stack: ItemStack): Int? {
        val wanted = startsTarget(title) ?: return null
        if (stack.isEmpty || TerminalHelper.isSelected(stack)) return null
        val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.ROOT) ?: return null
        return if (name.startsWith(wanted)) 0xCC55FFFF.toInt() else null
    }

    private fun rubixColor(slot: Int, stack: ItemStack, all: List<ItemStack>, committedTarget: Int?): Int? {
        if (slot !in RUBIX_SLOTS) return null
        val panes = rubixPanes(all)
        if (panes.size < 9) return null
        val target = committedTarget ?: optimalRubixTarget(all) ?: return null
        val currentIdx = TerminalHelper.rubixColorIndex(stack)
        return if (currentIdx != target) 0xCC55FFFF.toInt() else null
    }

    internal fun melodyColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        val rows = melodyRows(all)
        val marker = melodyMarker(all, rows) ?: return null
        val row = rows.firstOrNull { it.row == slot / 9 }
        return when {
            slot % 9 == marker % 9 && slot / 9 in listOf(0, rows.maxOf { it.row } + 1) -> 0xAAE040FB.toInt()
            row == null -> null
            row.completed && slot == row.buttonSlot -> 0xFF00A060.toInt()
            slot == row.movingSlot -> 0xDD00E676.toInt()
            slot == row.buttonSlot -> 0xFFFFC107.toInt()
            slot % 9 in 1..5 -> if (isMelodyPointer(stack)) 0xFF00A060.toInt() else 0x993D4350.toInt()
            else -> null
        }
    }
}
