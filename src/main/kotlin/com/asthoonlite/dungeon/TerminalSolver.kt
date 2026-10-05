package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import net.minecraft.ChatFormatting
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Items
import java.util.Locale
import java.util.regex.Pattern

/**
 * Clean, non-automating terminal overlay inspired by RSM's slot-oriented
 * solver and Odin/Noamm's Melody presentation.
 *
 * It only highlights the slots to solve; it never sends clicks for the user.
 */
object TerminalSolver {
    private const val NUMBER_TERM_COUNT = 10

    private enum class Type { PANES, RUBIX, ORDER, STARTS, SELECT, MELODY, NONE }

    /**
     * Whether [screenTitle] names a terminal this solver can draw.
     *
     * The title is stripped of formatting first: Hypixel's container titles
     * are not always plain text, and a gate that tests the raw string lets a
     * perfectly ordinary terminal through to nowhere — [colorFor] would strip
     * it and answer, but the gate rejects before it is ever asked.
     */
    fun isTerminalTitle(screenTitle: String): Boolean {
        val clean = ChatFormatting.stripFormatting(screenTitle)?.trim() ?: screenTitle.trim()
        return typeFor(clean) != Type.NONE
    }

    fun colorFor(screenTitle: String, slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        if (!Config.terminalSolverEnabled || slot < 0) return null
        val cleanTitle = ChatFormatting.stripFormatting(screenTitle)?.trim() ?: screenTitle.trim()
        val type = typeFor(cleanTitle)
        val size = slotCount(type)
        if (slot >= size) return null
        val terminalAll = all.take(size)
        return when (type) {
            Type.PANES -> if (stack.`is`(Items.RED_STAINED_GLASS_PANE)) 0xCC55FFFF.toInt() else null
            Type.ORDER -> orderColor(slot, stack, terminalAll)
            Type.SELECT -> selectColor(cleanTitle, stack)
            Type.STARTS -> startsColor(cleanTitle, stack)
            Type.RUBIX -> rubixColor(slot, stack, terminalAll)
            Type.MELODY -> melodyColor(slot, stack, terminalAll)
            Type.NONE -> null
        }
    }

    private fun slotCount(type: Type): Int = when (type) {
        Type.PANES -> 45
        Type.RUBIX, Type.STARTS -> 45
        Type.ORDER -> 36
        Type.SELECT, Type.MELODY -> 54
        Type.NONE -> 0
    }

    private fun typeFor(title: String): Type = when {
        title.startsWith("Correct all the panes!", true) -> Type.PANES
        title.startsWith("Change all to same color!", true) -> Type.RUBIX
        title.startsWith("Click in order!", true) -> Type.ORDER
        title.startsWith("What starts with:", true) -> Type.STARTS
        title.startsWith("Select all the", true) -> Type.SELECT
        title.startsWith("Click the button on time!", true) -> Type.MELODY
        else -> Type.NONE
    }

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
        val m = Pattern.compile("Select all the (.+?) items!?", Pattern.CASE_INSENSITIVE).matcher(title)
        if (!m.find() || stack.isEmpty || TerminalHelper.isSelected(stack)) return null
        val wanted = m.group(1)
        return if (TerminalHelper.matchesColor(stack, wanted)) 0xCCFF55FF.toInt() else null
    }

    private fun startsColor(title: String, stack: ItemStack): Int? {
        val m = Pattern.compile("What starts with: '(.+?)'\\??", Pattern.CASE_INSENSITIVE).matcher(title)
        if (!m.find() || stack.isEmpty || TerminalHelper.isSelected(stack)) return null
        val wanted = m.group(1).lowercase(Locale.ROOT)
        val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.ROOT) ?: return null
        return if (name.startsWith(wanted)) 0xCC55FF55.toInt() else null
    }

    private fun rubixColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        val allowed = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
        if (slot !in allowed) return null

        val panes = allowed.mapNotNull { s ->
            val st = all.getOrNull(s) ?: return@mapNotNull null
            val idx = TerminalHelper.rubixColorIndex(st)
            if (idx >= 0) s to idx else null
        }
        if (panes.size < 9) return null

        val costs = IntArray(5)
        for (target in 0..4) {
            for (p in panes) {
                val fwd = (target - p.second + 5) % 5
                val bwd = (p.second - target + 5) % 5
                costs[target] += minOf(fwd, bwd)
            }
        }
        val target = costs.indices.minByOrNull { costs[it] } ?: return null
        val currentIdx = TerminalHelper.rubixColorIndex(stack)
        return if (currentIdx == target) 0xAA00E676.toInt() else 0xAAFFAA00.toInt()
    }

    private fun melodyColor(slot: Int, stack: ItemStack, all: List<ItemStack>): Int? {
        val magenta = all.indexOfFirst { it.`is`(Items.MAGENTA_STAINED_GLASS_PANE) }
        val lime = all.indexOfLast { it.`is`(Items.LIME_STAINED_GLASS_PANE) }
        val clay = all.indexOfLast { it.`is`(Items.LIME_TERRACOTTA) }
        if (magenta < 0 || lime < 0) return null

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
