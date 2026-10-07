package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.asthoonlite.dungeon.simulator.TerminalSimulation
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Items
import kotlin.random.Random

/** Seeded practice boards and click rules, run after registry bootstrap. */
internal fun terminalSimulationChecks() {
    fun signature(sim: TerminalSimulation) = sim.items.map {
        listOf(BuiltInRegistries.ITEM.getKey(it.item).toString(), it.count.toString(),
            it.hoverName.string, it.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE).toString())
    }
    fun board(kind: Kind, seed: Long = 14L) = TerminalSimulation(kind, seed, 1_000L)
    val ring = listOf(Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
        Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS_PANE)

    for (kind in Kind.entries) for (seed in 0L..7L) {
        val sim = board(kind, seed)
        val same = board(kind, seed)
        check(sim.title == same.title && signature(sim) == signature(same)) { "$kind seed $seed must reproduce the board" }
        check(sim.items.size == kind.slotCount && TerminalSolver.kindOf(sim.title) == kind)
        check(sim.goalClicks > 0 && !sim.completed) { "A generated $kind board must need input" }
        val before = signature(sim)
        check(!sim.applyClick(-1, 0, 1_000L).accepted)
        check(!sim.applyClick(kind.slotCount, 0, 1_000L).accepted)
        check(sim.applyClick(0, 0, 1_000L).reason == "background")
        check(!sim.applyClick(sim.correctSlots.first(), 0, 999L).accepted)
        check(!sim.applyClick(sim.correctSlots.first(), 3, 1_000L).accepted)
        check(signature(sim) == before && sim.revision == 0L) { "Rejected static input cannot alter the board" }

        // Snapshots must not grant the screen a way to mutate engine state.
        sim.items[sim.correctSlots.first()].count = 64
        check(signature(sim) == before)

        when (kind) {
            Kind.ORDER -> {
                val order = sim.correctSlots.sortedBy { sim.items[it].count }
                check(order.map { sim.items[it].count } == (1..10).toList())
                check(sim.applyClick(order.last(), 0, 1_000L).reason == "out_of_order")
                for ((index, slot) in order.withIndex()) {
                    val result = sim.applyClick(slot, 0, 1_000L + index)
                    check(result.accepted && result.completed == (index == 9))
                    check(sim.items[slot].`is`(Items.LIME_STAINED_GLASS_PANE))
                    if (index != 9) check(sim.applyClick(slot, 0, 1_000L + index).reason == "out_of_order")
                }
            }
            Kind.PANES -> {
                check(sim.correctSlots.all { it / 9 in 1..3 && it % 9 in 2..6 })
                val first = sim.correctSlots.first()
                check(sim.applyClick(first, 0, 1_000L).accepted)
                if (!sim.completed) {
                    check(sim.items[first].`is`(Items.LIME_STAINED_GLASS_PANE))
                    check(sim.applyClick(first, 1, 1_000L).accepted)
                    check(sim.items[first].`is`(Items.RED_STAINED_GLASS_PANE)) { "Panes clicks toggle both directions" }
                }
                for (slot in sim.items.indices.filter { sim.items[it].`is`(Items.RED_STAINED_GLASS_PANE) }) {
                    check(sim.applyClick(slot, 0, 1_000L).accepted)
                }
            }
            Kind.SELECT, Kind.STARTS -> {
                val content = sim.items.indices.filter { !sim.items[it].`is`(Items.BLACK_STAINED_GLASS_PANE) }
                val wrong = content.firstOrNull { it !in sim.correctSlots }
                wrong?.let {
                    check(sim.applyClick(it, 0, 1_000L).reason == if (kind == Kind.SELECT) "wrong_color" else "wrong_letter")
                }
                check(sim.correctSlots.isNotEmpty())
                check(TerminalSolver.clickCandidates(sim.title, sim.items).toSet() == sim.correctSlots) {
                    "$kind independent practice targets must agree with the displayed names/colors"
                }
                for ((index, slot) in sim.correctSlots.withIndex()) {
                    val result = sim.applyClick(slot, 0, 1_000L + index)
                    check(result.accepted && sim.items[slot].get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true)
                    if (!result.completed) check(sim.applyClick(slot, 1, 1_000L + index).reason == "already_selected")
                }
            }
            Kind.RUBIX -> {
                check(sim.correctSlots == setOf(12, 13, 14, 21, 22, 23, 30, 31, 32))
                val first = sim.correctSlots.first()
                val original = ring.indexOf(sim.items[first].item)
                val advanced = sim.applyClick(first, 0, 1_000L)
                check(advanced.accepted && sim.items[first].item == ring[(original + 1) % 5])
                if (!sim.completed) {
                    check(sim.applyClick(first, 1, 1_000L).accepted && sim.items[first].item == ring[original])
                    // Any uniform color is a valid goal. Use orange and a
                    // simple left-click route rather than the solver's plan.
                    for (slot in sim.correctSlots) {
                        val clicks = (5 - ring.indexOf(sim.items[slot].item)) % 5
                        repeat(clicks) { if (!sim.completed) check(sim.applyClick(slot, 0, 1_000L).accepted) }
                    }
                }
                check(sim.items.filterIndexed { index, _ -> index in sim.correctSlots }.map { it.item }.distinct().size == 1)
            }
            Kind.MELODY -> {
                check(sim.correctSlots == setOf(16, 25, 34) && sim.goalClicks == 3)
                check(TerminalSolver.melodyRows(sim.items).map { it.row } == listOf(1, 2, 3))
                check(sim.applyClick(25, 0, 1_000L).reason == "inactive_row")
                if (sim.melodyColumn != sim.melodyTargetColumn) check(sim.applyClick(16, 0, 1_000L).reason == "off_beat")
                var now = 1_000L
                for (slot in listOf(16, 25, 34)) {
                    var attempts = 0
                    while (sim.melodyColumn != sim.melodyTargetColumn) {
                        now += 50L
                        sim.tick(now)
                        check(++attempts <= 80) { "Bouncing melody must reach every target within a cycle" }
                    }
                    val result = sim.applyClick(slot, 0, now)
                    check(result.accepted && result.completed == (slot == 34))
                    check(sim.items[slot].`is`(Items.LIME_STAINED_GLASS_PANE))
                    check(TerminalSolver.melodyRows(sim.items).first { it.buttonSlot == slot }.completed)
                    if (!sim.completed) check(sim.applyClick(slot, 0, now).reason == "inactive_row")
                }
                check(sim.items[43].`is`(Items.BLACK_STAINED_GLASS_PANE)) { "Indicator is never a fourth content row" }
            }
        }
        check(sim.completed && sim.completedAtMs != null) { "The generated $kind board must be solvable" }
        val completeBoard = signature(sim)
        check(sim.applyClick(sim.correctSlots.first(), 0, 50_000L).reason == "completed")
        check(sim.tick(100_000L).isEmpty() && signature(sim) == completeBoard) { "A completed board is frozen" }
    }

    val melody = board(Kind.MELODY)
    check(melody.melodyColumn == 1 && melody.tick(1_049L).isEmpty())
    check(melody.tick(1_050L).isNotEmpty() && melody.melodyColumn == 2) { "Odin melody moves on the first container tick" }
    check(melody.tick(1_549L).isEmpty())
    check(melody.tick(1_550L).isNotEmpty() && melody.melodyColumn == 3)
    val slowFrames = board(Kind.MELODY)
    val fastFrames = board(Kind.MELODY)
    for (time in 1_000L..31_000L step 50L) fastFrames.tick(time)
    slowFrames.tick(31_000L)
    check(signature(slowFrames) == signature(fastFrames)) { "Skipped frames cannot change the melody clock" }
    check(slowFrames.tick(30_000L).isEmpty()) { "An earlier timestamp cannot rewind melody" }

    val changedSeeds = (0L..20L).map { signature(board(Kind.ORDER, it)) }.distinct().size
    check(changedSeeds > 10) { "Random rounds must offer different boards" }
    check(Kind.entries.toSet() == (0L..100L).map { TerminalSimulation.random(it, 1_000L).kind }.toSet()) {
        "Random practice selection must include every terminal kind"
    }
    check(runCatching { TerminalSimulation(Kind.MELODY, 1L, 1_000L, melodyStepMs = 0L) }.isFailure)

    // Language fallback stays pure: these synthetic catalogs do not modify
    // Minecraft's global language or registry state during the harness.
    val initials = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W")
    val english = initials.map { "${it.lowercase()}_item" to "$it Item" }
    for (seed in 0L..20L) {
        val choice = TerminalSimulation.startsCatalogChoice(english, Random(seed))
        check(choice.letter == initials.random(Random(seed))) { "English initials preserve the seeded Odin selection" }
        check(choice.matches.size == 1 && choice.other.size == 10)
    }
    val partial = listOf("apple" to "Pomme", "bone" to "Bone", "carrot" to "Karotte")
    for (seed in 0L..10L) {
        val choice = TerminalSimulation.startsCatalogChoice(partial, Random(seed))
        check(choice.letter == "B" && choice.matches == listOf(1)) { "Unsupported initials cannot create an empty matching pool" }
    }
    val localized = listOf("apple" to "リンゴ", "bone" to "骨", "carrot" to "ニンジン", "empty_name" to "")
    for (seed in 0L..20L) {
        val choice = TerminalSimulation.startsCatalogChoice(localized, Random(seed))
        check(choice.letter.isNotEmpty() && choice.matches.isNotEmpty()) { "A localized catalog always has a selectable target" }
        check(choice.matches.all { localized[it].second.startsWith(choice.letter, true) })
        check(choice.other.all { !localized[it].second.startsWith(choice.letter, true) })
        check((choice.matches + choice.other).toSet() == localized.indices.toSet())
    }
    val unicode = TerminalSimulation.startsCatalogChoice(listOf("apple" to "🍎 Apple"), Random(1L))
    check(unicode.letter == "🍎" && unicode.matches == listOf(0)) { "A displayed initial is one complete Unicode code point" }
}
