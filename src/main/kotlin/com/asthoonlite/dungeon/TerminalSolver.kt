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
    private const val NUMBER_TERM_COUNT = 10

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
        return stack.count.toString()
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
        val size = type.slotCount
        if (items.size < size) return emptyList()
        val all = items.take(size)

        return when (type) {
            Kind.PANES -> all.indices.filter { i ->
                i !in blocked && all[i].`is`(Items.RED_STAINED_GLASS_PANE)
            }

            // The number terminal is a chain: the pane whose count is still
            // the lowest is the only one the server will accept next.
            Kind.ORDER -> {
                val reds = all.mapIndexedNotNull { i, s ->
                    if (s.`is`(Items.RED_STAINED_GLASS_PANE)) i to s.count else null
                }
                if (reds.isEmpty()) return emptyList()
                val next = reds.minOf { it.second }
                reds.filter { it.second == next && it.first !in blocked }.map { it.first }
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
            Kind.MELODY -> listOfNotNull(melodyCandidate(all))
        }
    }

    /**
     * The single slot a marker should ring: the nearest candidate to
     * [lastSlot], so the picture and the pointer agree about where the next
     * click is going. The slot it is already on is skipped while anything
     * else is available — a marker sitting on the pane that was just clicked
     * says nothing about where to go next.
     */
    fun nextClickSlot(
        screenTitle: String,
        items: List<ItemStack>,
        blocked: Set<Int> = emptySet(),
        rubixTarget: Int? = null,
        lastSlot: Int? = null
    ): Int? {
        val kind = kindOf(screenTitle) ?: return null
        val candidates = clickCandidates(screenTitle, items, blocked, rubixTarget)
        if (candidates.isEmpty()) return null
        val elsewhere = lastSlot?.let { s -> candidates.filter { it != s } }.orEmpty()
        val pool = if (elsewhere.isNotEmpty()) elsewhere else candidates
        return TerminalClickOrder.pickNearest(pool, lastSlot, kind.slotCount)
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
            val stack = all.getOrNull(slot) ?: return@mapNotNull null
            val idx = TerminalHelper.rubixColorIndex(stack)
            if (idx >= 0) slot to idx else null
        }

    /**
     * The colour that costs the fewest clicks to reach, summed over all nine
     * panes. Pure: same pane state, same answer, which is what lets the marker
     * show the target before the clicker has committed to one.
     */
    fun optimalRubixTarget(all: List<ItemStack>): Int? {
        val panes = rubixPanes(all)
        if (panes.size < 9) return null
        val costs = IntArray(5)
        for (target in 0..4) {
            for (p in panes) {
                // Forward only: a left click cycles a pane +1, so the cost of
                // reaching a colour from where a pane stands is the forward
                // distance, never the short way back.
                costs[target] += (target - p.second + 5) % 5
            }
        }
        return costs.indices.minByOrNull { costs[it] }
    }

    /**
     * Melody's next button: the row whose lime pane is aligned with the
     * magenta marker and is not already complete. Returns the slot to click,
     * or null when nothing is lined up right now.
     *
     * The marker lives in the first five rows — that is the terminal's
     * content window; the sixth is filler and is never read. The row's button
     * being lime terracotta is the *pressable* state, not a finished one, so
     * it is not part of the completion test: every button sits in that colour
     * while its row is live, and treating it as done skipped every row and
     * left melody clicking nothing at all.
     */
    fun melodyCandidate(all: List<ItemStack>): Int? {
        val last = minOf(all.lastIndex, CONTENT_LAST)
        if (last < 0) return null

        val magentaSlot = (0..last).firstOrNull { all[it].`is`(Items.MAGENTA_STAINED_GLASS_PANE) } ?: return null
        val targetCol = (magentaSlot % 9) - 1
        if (targetCol !in 0..4) return null

        for (r in 0..2) {
            val buttonSlot = (r + 1) * 9 + 7
            val buttonStack = all.getOrNull(buttonSlot) ?: continue
            val rowPaneSlots = ((r + 1) * 9 + 1)..((r + 1) * 9 + 5)
            val rowPanes = rowPaneSlots.mapNotNull { all.getOrNull(it) }

            // Finished when the row has filled in or the button has been
            // struck out with a completed marker. Lime terracotta is absent
            // from this list on purpose — see above.
            val isRowCompleted = buttonStack.`is`(Items.LIME_STAINED_GLASS_PANE) ||
                buttonStack.`is`(Items.LIME_CONCRETE) ||
                buttonStack.`is`(Items.EMERALD_BLOCK) ||
                rowPanes.size == 5 && rowPanes.all {
                    it.`is`(Items.LIME_STAINED_GLASS_PANE) || it.`is`(Items.GREEN_STAINED_GLASS_PANE)
                }
            if (isRowCompleted) continue

            val limePaneIndex = rowPaneSlots.firstOrNull { slot ->
                val stack = all[slot]
                stack.`is`(Items.LIME_STAINED_GLASS_PANE) || stack.`is`(Items.GREEN_STAINED_GLASS_PANE)
            } ?: continue

            val movingCol = (limePaneIndex % 9) - 1
            if (movingCol == targetCol) return buttonSlot
        }
        return null
    }

    /** Last slot of the terminal's content window: rows 0..4 of a 6-row container. */
    private const val CONTENT_LAST = 44

    private val RUBIX_SLOTS = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    // ── Presentation ─────────────────────────────────────────────────────────

    private fun orderColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        if (!stack.`is`(Items.RED_STAINED_GLASS_PANE)) return null
        val ordered = all.mapIndexedNotNull { i, s ->
            if (s.`is`(Items.RED_STAINED_GLASS_PANE)) i to s.count else null
        }.sortedBy { it.second }.take(NUMBER_TERM_COUNT)
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
        return if (TerminalHelper.matchesColor(stack, wanted)) 0xCCFF55FF.toInt() else null
    }

    private fun startsColor(title: String, stack: ItemStack): Int? {
        val wanted = startsTarget(title) ?: return null
        if (stack.isEmpty || TerminalHelper.isSelected(stack)) return null
        val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.ROOT) ?: return null
        return if (name.startsWith(wanted)) 0xCC55FF55.toInt() else null
    }

    private fun rubixColor(slot: Int, stack: ItemStack, all: List<ItemStack>, committedTarget: Int?): Int? {
        if (slot !in RUBIX_SLOTS) return null
        val panes = rubixPanes(all)
        if (panes.size < 9) return null
        val target = committedTarget ?: optimalRubixTarget(all) ?: return null
        val currentIdx = TerminalHelper.rubixColorIndex(stack)
        return if (currentIdx == target) 0xAA00E676.toInt() else 0xAAFFAA00.toInt()
    }

    private fun melodyColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        // Same content window the candidate reads — rows 0..4 only, so the
        // fillers in the last row cannot be mistaken for the marker.
        val magenta = (0..minOf(all.lastIndex, CONTENT_LAST))
            .firstOrNull { all[it].`is`(Items.MAGENTA_STAINED_GLASS_PANE) } ?: return null
        val lime = all.indexOfLast { it.`is`(Items.LIME_STAINED_GLASS_PANE) }
        val clay = all.indexOfLast { it.`is`(Items.LIME_TERRACOTTA) }
        if (lime < 0) return null

        val row = lime / 9
        val magentaCol = magenta % 9
        val slotRow = slot / 9
        val slotCol = slot % 9

        return when {
            slot == clay -> 0xFFFFC107.toInt()
            slotRow == row && slotCol in 1..5 -> if (slot == lime) 0xDD00E676.toInt() else 0x99FFFFFF.toInt()
            (slotCol == magentaCol && slotRow in 0..5) -> 0xAAE040FB.toInt()
            else -> null
        }
    }
}
