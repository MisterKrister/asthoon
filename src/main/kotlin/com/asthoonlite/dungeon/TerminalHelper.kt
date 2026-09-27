package com.asthoonlite.dungeon

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/**
 * Shared utility for terminal solvers and automated clickers to accurately
 * identify item selection state (enchantment glint) and match item colors/names.
 */
object TerminalHelper {

    /**
     * Accurately checks if an ItemStack in a terminal container has already been clicked
     * and selected (indicated by enchantment glint / foil on Hypixel).
     */
    fun isSelected(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.hasFoil()) return true
        if (stack.isEnchanted) return true
        if (stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true) return true
        val enchants = stack.get(DataComponents.ENCHANTMENTS)
        if (enchants != null && !enchants.isEmpty) return true
        return false
    }

    /**
     * Checks if an item matches the target color in "Select all the <COLOR> items!".
     * Correctly handles 1.8 legacy dyes ("Cactus Green", "Rose Red", "Dandelion Yellow",
     * "Lapis Lazuli", "Bone Meal", etc.) as well as distinguishing "Green" from "Lime",
     * "Blue" from "Light Blue", and "Gray" from "Light Gray".
     */
    fun matchesColor(stack: ItemStack, rawColor: String): Boolean {
        if (stack.isEmpty) return false
        if (stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return false

        val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.ROOT) ?: return false
        val color = rawColor.trim().lowercase(Locale.ROOT)

        return when (color) {
            "green" -> {
                // Must be green, strictly NOT lime
                !name.contains("lime") && (
                    name.contains("green") ||
                    name.contains("cactus")
                )
            }
            "lime" -> {
                name.contains("lime")
            }
            "red" -> {
                name.contains("red") ||
                name.contains("rose") ||
                name.contains("poppy") ||
                name.contains("redstone") ||
                name.contains("apple") ||
                name.contains("brick") ||
                name.contains("nether wart")
            }
            "yellow" -> {
                name.contains("yellow") ||
                name.contains("dandelion") ||
                name.contains("sunflower") ||
                name.contains("gold") ||
                name.contains("hay")
            }
            "blue" -> {
                // Must be blue, strictly NOT light blue
                !name.contains("light blue") && (
                    name.contains("blue") ||
                    name.contains("lapis")
                )
            }
            "light blue" -> {
                name.contains("light blue")
            }
            "cyan" -> {
                name.contains("cyan")
            }
            "purple" -> {
                name.contains("purple") ||
                name.contains("chorus")
            }
            "magenta" -> {
                name.contains("magenta") ||
                name.contains("allium")
            }
            "pink" -> {
                name.contains("pink") ||
                name.contains("peony") ||
                (name.contains("tulip") && name.contains("pink"))
            }
            "white" -> {
                name.contains("white") ||
                name.contains("bone") ||
                name.contains("quartz") ||
                name.contains("sugar") ||
                name.contains("iron") ||
                name.contains("snow")
            }
            "black" -> {
                name.contains("black") ||
                name.contains("ink") ||
                name.contains("coal") ||
                name.contains("obsidian")
            }
            "brown" -> {
                name.contains("brown") ||
                name.contains("cocoa") ||
                name.contains("dirt") ||
                name.contains("podzol")
            }
            "orange" -> {
                name.contains("orange") ||
                name.contains("pumpkin") ||
                name.contains("carrot")
            }
            "gray", "grey" -> {
                // Must be gray, strictly NOT light gray / silver
                !name.contains("light gray") && !name.contains("light grey") && !name.contains("silver") && (
                    name.contains("gray") || name.contains("grey") || name.contains("flint")
                )
            }
            "light gray", "light grey", "silver" -> {
                name.contains("light gray") ||
                name.contains("light grey") ||
                name.contains("silver")
            }
            else -> {
                name.contains(color)
            }
        }
    }

    /**
     * Maps Rubix terminal pane item types to their 0..4 color sequence index:
     * 0: Orange, 1: Yellow, 2: Green / Lime, 3: Blue / Cyan / Light Blue, 4: Red
     */
    fun rubixColorIndex(stack: ItemStack): Int = when (stack.item) {
        Items.ORANGE_STAINED_GLASS_PANE -> 0
        Items.YELLOW_STAINED_GLASS_PANE -> 1
        Items.GREEN_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE -> 2
        Items.BLUE_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.CYAN_STAINED_GLASS_PANE -> 3
        Items.RED_STAINED_GLASS_PANE -> 4
        else -> -1
    }
}
