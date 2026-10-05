package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.api.FloorType
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.TerminalSolver.Kind
import com.mojang.serialization.Lifecycle
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderOwner
import net.minecraft.core.HolderSet
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import com.asthoonlite.dungeon.solvers.TicTacToeSolver
import com.asthoonlite.overlay.J2dMapCanvas
import com.asthoonlite.render.MapCanvas
import com.asthoonlite.render.RecordMapCanvas
import com.asthoonlite.render.mapBaseSpans
import com.asthoonlite.render.replay
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.awt.image.BufferedImage
import java.util.Optional
import java.util.stream.Stream

/** Run with ./gradlew regressionCheck (also included in build). No game or server needed. */
fun main() {
    // Registry-backed types (map decoration kinds) are not usable until the
    // built-in registries exist. Same two calls the dedicated server makes
    // before it touches a registry — no world, no server, no connection.
    net.minecraft.SharedConstants.tryDetectVersion()
    net.minecraft.server.Bootstrap.bootStrap()

    // `ItemStack` reads a component map off the item's holder, and vanilla
    // only binds those during a resource reload — something this harness
    // never does, so an ItemStack construction dies with "Components not
    // bound yet". Bind the built-in ones here, once, exactly the way the
    // reload path does: build the pending maps from a lookup over every
    // built-in registry, then apply them.
    //
    // The item table is filled when `Items` loads, so make sure that has
    // happened first — binding before registration would bind nothing.
    requireNotNull(Items.WHITE_STAINED_GLASS_PANE) { "Item table must be populated before binding" }
    val builtInLookups: List<HolderLookup.RegistryLookup<*>> = BuiltInRegistries.REGISTRY.stream()
        .map { registry -> UntaggedLookup<Any>(registry as HolderLookup.RegistryLookup<Any>) }
        .toList()
    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
        .build(HarnessLookupProvider(builtInLookups.associateBy { lookup -> lookup.key() }))
        .forEach { pending -> pending.apply() }

    val scoreboard = Scoreboard()
    val objective = scoreboard.addObjective(
        "sidebar", ObjectiveCriteria.DUMMY, Component.literal("SKYBLOCK"),
        ObjectiveCriteria.RenderType.INTEGER, false, null
    )
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("line-0"), objective)
        .display(Component.literal("§7The Catacombs (F7)"))
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("#hidden"), objective)
        .display(Component.literal("Hidden line"))
    var lines = DungeonContext.sidebarLines(scoreboard, objective)
    check(lines == listOf("The Catacombs (F7)")) { "Use displayed scoreboard text, not internal owners" }
    DungeonContext.updateFromSidebar(lines)
    check(DungeonContext.inDungeon && DungeonContext.floor == FloorType.F7)
    repeat(80) { DungeonContext.updateFromSidebar(emptyList()) }
    check(DungeonContext.inDungeon) { "Keep the dungeon active during short scoreboard refreshes" }
    DungeonContext.updateFromSidebar(emptyList())
    check(!DungeonContext.inDungeon && DungeonContext.floor == FloorType.None)

    // Legacy scoreboards split the line across a team prefix, owner, and suffix.
    val team = scoreboard.addPlayerTeam("legacy")
    team.setPlayerPrefix(Component.literal("§7The Catacombs "))
    team.setPlayerSuffix(Component.literal("§r"))
    scoreboard.addPlayerToTeam("(M3)", team)
    scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("(M3)"), objective).set(1)
    scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly("line-0"), objective)
    lines = DungeonContext.sidebarLines(scoreboard, objective)
    check(lines == listOf("The Catacombs (M3)"))
    DungeonContext.updateFromSidebar(lines)
    check(DungeonContext.floor == FloorType.M3)

    val title = "Correct all the panes!"
    check(AutoTerminal.beginTerminal(title, 1_000L))
    val delayField = AutoTerminal::class.java.getDeclaredField("currentClickDelayMs").apply { isAccessible = true }
    delayField.setLong(null, 430L)
    check(!AutoTerminal.canClick(1_429L))
    check(AutoTerminal.canClick(1_430L))
    AutoTerminal.recordClick(1_430L, 10, 180L)
    // The same terminal is reopened with a new container ID after each click.
    check(!AutoTerminal.beginTerminal(title, 1_480L))
    check(!AutoTerminal.canClick(1_609L))
    check(AutoTerminal.canClick(1_610L)) { "Subsequent clicks must use 180ms, not 430ms" }
    AutoTerminal.recordClick(1_610L, 11, 180L)
    check(!AutoTerminal.beginTerminal(title, 1_660L))
    check(AutoTerminal.canClick(1_790L))
    check(AutoTerminal.beginTerminal("Click in order!", 2_000L))
    delayField.setLong(null, 430L)
    check(!AutoTerminal.canClick(2_429L))
    check(AutoTerminal.canClick(2_430L))
    AutoTerminal.onEscape()
    check(AutoTerminal.beginTerminal("Click in order!", 3_000L)) { "Reopening after closing starts a new session" }

    // ── Terminal identification, candidates, click order ────────────────────
    run {
        // Plain, simulator-wrapped and colour-formatted titles all name the
        // same terminal. A phrase does not stop being true because something
        // wrote in front of it — that was the failure the fuzzy match fixes.
        check(TerminalSolver.kindOf("Select all the red items!") == Kind.SELECT)
        check(TerminalSolver.kindOf("P3 · Click in order!") == Kind.ORDER)
        check(TerminalSolver.kindOf("§cCorrect all the panes!") == Kind.PANES)
        check(TerminalSolver.kindOf("What starts with: 'a'?") == Kind.STARTS)
        check(TerminalSolver.kindOf("Change all to same color!") == Kind.RUBIX)
        check(TerminalSolver.kindOf("Click the button on time!") == Kind.MELODY)
        check(TerminalSolver.kindOf("Your inventory") == null)
        check(TerminalSolver.kindOf("Sort these items in order") == null) {
            "Containing 'order' is not containing 'click in order'"
        }
        check(!TerminalSolver.isTerminalTitle("Chest"))
        check(TerminalSolver.cleanTitle("§aClick in order!") == "Click in order!")

        // The gate that used to make Auto Terminal do nothing at all: with no
        // type switches chosen there is no filter, not a filter on nothing.
        check(AutoTerminal.typeAllowed(Kind.SELECT, emptySet())) { "No switches on must mean every terminal runs" }
        check(AutoTerminal.typeAllowed(Kind.MELODY, emptySet()))
        check(!AutoTerminal.typeAllowed(Kind.SELECT, setOf(Kind.MELODY)))
        check(AutoTerminal.typeAllowed(Kind.MELODY, setOf(Kind.MELODY)))

        val pane = Items.WHITE_STAINED_GLASS_PANE
        val paneGrid = { size: Int -> ArrayList<ItemStack>(size).apply { repeat(size) { add(ItemStack(pane)) } } }

        // Select: only the panes of the right colour that are not picked yet.
        val select = paneGrid(54)
        select[1] = ItemStack(Items.RED_STAINED_GLASS_PANE)
        select[2] = ItemStack(Items.BLACK_STAINED_GLASS_PANE) // never a target
        select[20] = ItemStack(Items.RED_STAINED_GLASS_PANE)
        select[44] = ItemStack(Items.RED_STAINED_GLASS_PANE)
        val selectTitle = "Select all the red items!"
        check(TerminalSolver.clickCandidates(selectTitle, select) == listOf(1, 20, 44))
        check(TerminalSolver.clickCandidates(selectTitle, select, setOf(1)) == listOf(20, 44))
        check(TerminalSolver.clickCandidates("Chest", select).isEmpty()) {
            "A non-terminal must never hand out a slot to click"
        }
        check(TerminalSolver.clickCandidates(selectTitle, select.take(10)).isEmpty()) {
            "A clipped slot list short of the terminal's own size is not readable"
        }

        // The marker and the clicker are one decision: from wherever the
        // pointer is, it goes to the nearest pane still to click.
        check(TerminalSolver.nextClickSlot(selectTitle, select, lastSlot = 1) == 20)
        check(TerminalSolver.nextClickSlot(selectTitle, select, lastSlot = 20) == 1)
        // No previous click means "come from the middle of the grid", and the
        // nearest of 1, 20 and 44 to slot 27 is 20.
        check(TerminalSolver.nextClickSlot(selectTitle, select, lastSlot = null) == 20) {
            "A fresh terminal starts from the middle of the grid, not from slot 0"
        }
        check(TerminalSolver.nextClickSlot(selectTitle, select, setOf(1), lastSlot = 1) == 20)
        check(TerminalSolver.nextClickSlot(selectTitle, select, setOf(1, 20, 44)) == null) {
            "Every pane blocked means nothing left to click, not a random pick"
        }

        // The number terminal only accepts the lowest count next.
        val order = paneGrid(36)
        order[4] = ItemStack(Items.RED_STAINED_GLASS_PANE, 1)
        order[9] = ItemStack(Items.RED_STAINED_GLASS_PANE, 1)
        order[20] = ItemStack(Items.RED_STAINED_GLASS_PANE, 2)
        check(TerminalSolver.clickCandidates("Click in order!", order) == listOf(4, 9))
        check(TerminalSolver.nextClickSlot("Click in order!", order, lastSlot = 4) == 9)

        // What starts with: the letter from the title, and nothing else.
        val starts = paneGrid(45)
        starts[5] = ItemStack(Items.APPLE)
        starts[14] = ItemStack(Items.ARROW)
        starts[27] = ItemStack(Items.BREAD)
        check(TerminalSolver.clickCandidates("What starts with: 'a'?", starts) == listOf(5, 14))

        // Correct all the panes: every red one, in slot order.
        val panes = paneGrid(45)
        panes[3] = ItemStack(Items.RED_STAINED_GLASS_PANE)
        panes[10] = ItemStack(Items.RED_STAINED_GLASS_PANE)
        check(TerminalSolver.clickCandidates("Correct all the panes!", panes) == listOf(3, 10))

        // Rubix: the target is the cheapest colour to reach, and the
        // candidates are the panes that are not there yet.
        val rubix = paneGrid(45)
        for (slot in listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)) {
            rubix[slot] = ItemStack(Items.ORANGE_STAINED_GLASS_PANE)
        }
        check(TerminalSolver.optimalRubixTarget(rubix) == 0)
        check(TerminalSolver.clickCandidates("Change all to same color!", rubix).isEmpty()) {
            "All nine panes already at the target: nothing left to click"
        }
        // Three panes already yellow flips the target to yellow (6 forward
        // clicks) rather than orange (12), and the candidates are the six
        // panes still orange.
        for (slot in listOf(12, 13, 14)) rubix[slot] = ItemStack(Items.YELLOW_STAINED_GLASS_PANE)
        check(TerminalSolver.optimalRubixTarget(rubix) == 1)
        check(TerminalSolver.clickCandidates("Change all to same color!", rubix) == listOf(21, 22, 23, 30, 31, 32))
    }

    for (floor in FloorType.entries.filter { it != FloorType.None }) {
        val colors = ByteArray(128 * 128)
        val width = floor.roomsW * 20 - 4
        val height = floor.roomsH * 20 - 4
        val offsetX = (128 - width) / 2
        val offsetZ = (128 - height) / 2
        // An earlier green checkmark must not be mistaken for the entrance.
        colors[16 + 16 * 128] = 30
        val entranceX = offsetX + (floor.roomsW - 1) * 20
        val entranceZ = offsetZ + (floor.roomsH - 1) * 20
        for (z in entranceZ until entranceZ + 16) for (x in entranceX until entranceX + 16) {
            colors[x + z * 128] = 30
        }
        colors[entranceX + 8 + (entranceZ + 8) * 128] = 34
        DungeonMapScanner.reset()
        check(DungeonMapScanner.scanMapDimensions(colors, floor)) { "Entrance not found on $floor" }
        check(DungeonMapScanner.roomSize == 16 && DungeonMapScanner.roomGap == 20)
        check(DungeonMapScanner.mapWidth == width && DungeonMapScanner.mapHeight == height)
        check(DungeonMapScanner.mapOffsetX == offsetX && DungeonMapScanner.mapOffsetZ == offsetZ) {
            "Wrong map origin on $floor"
        }
    }
    DungeonMapScanner.reset()
    check(!DungeonMapScanner.scanMapDimensions(ByteArray(128 * 128), FloorType.F7))
    check(!DungeonMapScanner.scanMapDimensions(ByteArray(32), FloorType.F7))
    val edgeColors = ByteArray(128 * 128)
    edgeColors[128] = 63
    check(DungeonMapScanner.colorAt(edgeColors, 0, 1) == 63.toByte())
    check(DungeonMapScanner.colorAt(edgeColors, 128, 0) == null) { "Map pixels must not wrap into the next row" }
    check(DungeonMapScanner.colorAt(edgeColors, -1, 1) == null)

    // 26.1's maxY is inclusive; Hypixel's legacy world ends at 255, not 256.
    val legacyWorld = LevelHeightAccessor.create(0, 256)
    check(legacyWorld.maxY == 255 && !legacyWorld.isInsideBuildHeight(256))

    val categorize = StarMobESP::class.java.getDeclaredMethod("categorize", String::class.java).apply { isAccessible = true }
    check(categorize.invoke(StarMobESP, "✯ WITHERMANCER") == StarMobESP.MobCategory.CHONK)
    check(categorize.invoke(StarMobESP, "✯ SKELETON MASTER") == StarMobESP.MobCategory.SKELETON_MASTER)
    val playerCategory = StarMobESP::class.java.getDeclaredMethod("categorizePlayer", String::class.java).apply { isAccessible = true }
    check(playerCategory.invoke(StarMobESP, "Shadow Assassin") == StarMobESP.MobCategory.SHADOW_ASSASSIN)
    check(playerCategory.invoke(StarMobESP, "PartyMember") == null)

    // TicTacToe minimax test:
    // 1. AI plays corner (0) -> player O must take center (4)
    val boardAiCorner = listOf<String?>("X", null, null, null, null, null, null, null, null)
    check(TicTacToeSolver.bestMove(boardAiCorner, "O") == 4) { "O must take center when X starts in corner" }

    // 2. AI plays center (4) -> player O must take corner (0)
    val boardAiCenter = listOf<String?>(null, null, null, null, "X", null, null, null, null)
    check(TicTacToeSolver.bestMove(boardAiCenter, "O") == 0) { "O must take corner when X starts in center" }

    // 3. AI threatens win at 0, 1 -> player O at 4 must block at 2
    val boardAiWinThreat = listOf<String?>("X", "X", null, null, "O", null, null, null, null)
    check(TicTacToeSolver.bestMove(boardAiWinThreat, "O") == 2) { "O must block X at slot 2" }

    // ── Secret hitbox expansion geometry ───────────────────────────────────
    // Guards the lerp MixinBlockStateShape feeds into Minecraft's picking ray:
    // normal (0 %) -> full block (100 %), with buttons stopping at the
    // button's own height. If these ever regress, clicking silently stops
    // working in ways that are hard to notice in a playtest.
    run {
        fun assertValid(b: DoubleArray, label: String) {
            check(b.size == 6) { "$label: expected 6 values, got ${b.size}" }
            for (v in b) check(v >= -1e-9 && v <= 1.0 + 1e-9) {
                "$label escaped the unit block: ${b.toList()}"
            }
            check(b[0] < b[3] && b[1] < b[4] && b[2] < b[5]) {
                "$label produced an empty or inverted box: ${b.toList()}"
            }
        }

        fun fullBlock() = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

        // The slider is an expansion fraction, not a raw size: 0 % is
        // "normal", 100 % is "full block", and it clamps at both ends.
        check(SecretHitboxes.expansionFraction(0) == 0.0) { "0% must be the normal endpoint" }
        check(SecretHitboxes.expansionFraction(100) == 1.0) { "100% must be the full-block endpoint" }
        check(SecretHitboxes.expansionFraction(50) == 0.5) { "50% must land mid-lerp" }
        check(SecretHitboxes.expansionFraction(-5) == 0.0) { "expansion must clamp at 0" }
        check(SecretHitboxes.expansionFraction(500) == 1.0) { "expansion must clamp at 1" }

        // Vanilla footprints as the game actually builds them. The thin axis
        // is the depth: Y for a button on the floor, X for one on a side wall,
        // Z for one on the other pair. Wall and ceiling buttons are exactly
        // the case that used to break — the rule has to find the depth, not
        // assume it is Y.
        val vanillaFloorLever = doubleArrayOf(0.3125, 0.0, 0.3125, 0.6875, 0.5, 0.6875)
        val vanillaWallButtonX = doubleArrayOf(0.0, 0.375, 0.3125, 0.125, 0.75, 0.6875)    // depth = X
        val vanillaWallButtonZ = doubleArrayOf(0.3125, 0.375, 0.875, 0.6875, 0.625, 1.0)   // depth = Z
        val vanillaFloorButton = doubleArrayOf(0.3125, 0.875, 0.375, 0.6875, 1.0, 0.625)   // depth = Y
        val vanillaSkull      = doubleArrayOf(0.125, 0.0, 0.125, 0.875, 0.875, 0.875)

        // Levers, skulls and mushrooms grow all the way to the cell walls.
        for ((kind, vanilla) in listOf(
            SecretHitboxes.Kind.LEVER to vanillaFloorLever,
            SecretHitboxes.Kind.SKULL to vanillaSkull,
            SecretHitboxes.Kind.MUSHROOM to vanillaSkull
        )) {
            val target = SecretHitboxes.targetBounds(kind, vanilla)
            check(target.contentEquals(fullBlock())) {
                "$kind must target the whole block, got ${target.toList()}"
            }
            val atMax = SecretHitboxes.lerpBounds(vanilla, target, 100)
            check(atMax.contentEquals(fullBlock())) {
                "$kind at 100% must be the whole block, got ${atMax.toList()}"
            }
            assertValid(atMax, "$kind@100%")
        }

        // Buttons: the depth axis is copied from vanilla, untouched, and the
        // other two go to the cell walls — in whichever axis depth sits.
        val buttonCases = listOf(
            Triple("wall/X", vanillaWallButtonX, intArrayOf(0)),
            Triple("wall/Z", vanillaWallButtonZ, intArrayOf(2)),
            Triple("floor", vanillaFloorButton, intArrayOf(1))
        )
        for ((label, vanilla, depthAxes) in buttonCases) {
            val depth = depthAxes[0]
            val target = SecretHitboxes.targetBounds(SecretHitboxes.Kind.BUTTON, vanilla)
            check(target[depth] == vanilla[depth] && target[depth + 3] == vanilla[depth + 3]) {
                "button($label) must keep its depth on axis $depth: ${target.toList()} vs ${vanilla.toList()}"
            }
            for (axis in intArrayOf(0, 1, 2)) {
                if (axis == depth) continue
                check(target[axis] == 0.0 && target[axis + 3] == 1.0) {
                    "button($label) must span the block on axis $axis: ${target.toList()}"
                }
            }
            assertValid(target, "button($label)@target")
        }

        // The measured 26.1.2 shapes: read them out of the real block states,
        // so the rule is pinned against the game rather than against numbers
        // someone typed in. The shape may change shape between versions; this
        // is what notices.
        run {
            val empty = net.minecraft.world.level.EmptyBlockGetter.INSTANCE
            val zero = net.minecraft.core.BlockPos.ZERO
            val button = net.minecraft.world.level.block.Blocks.STONE_BUTTON.defaultBlockState()
            for (face in net.minecraft.world.level.block.state.properties.AttachFace.values()) {
                val state = button.setValue(
                    net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE, face
                )
                val b = state.getShape(empty, zero).bounds()
                val vanilla = doubleArrayOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
                val extents = doubleArrayOf(
                    vanilla[3] - vanilla[0], vanilla[4] - vanilla[1], vanilla[5] - vanilla[2]
                )
                val depth = extents.indices.minByOrNull { extents[it] }!!
                check(extents.max() >= extents[depth] * 2.0) {
                    "button($face) no longer has a distinguishable depth axis: ${extents.toList()}"
                }
                val target = SecretHitboxes.targetBounds(SecretHitboxes.Kind.BUTTON, vanilla)
                check(target[depth] == vanilla[depth] && target[depth + 3] == vanilla[depth + 3]) {
                    "button($face) grew into its own depth: ${target.toList()} from ${vanilla.toList()}"
                }
                for (axis in intArrayOf(0, 1, 2)) {
                    if (axis == depth) continue
                    check(target[axis] == 0.0 && target[axis + 3] == 1.0) {
                        "button($face) failed to open up axis $axis: ${target.toList()}"
                    }
                }
            }
        }

        // 0 % is a pure pass-through — vanilla hands back untouched.
        for (kind in SecretHitboxes.Kind.values()) {
            val vanilla = if (kind == SecretHitboxes.Kind.BUTTON) vanillaWallButtonX else vanillaFloorLever
            val out = SecretHitboxes.lerpBounds(vanilla, SecretHitboxes.targetBounds(kind, vanilla), 0)
            check(out.contentEquals(vanilla)) { "$kind at 0% must be exactly vanilla: ${out.toList()}" }
        }

        // Every slider step is valid, monotone, and never leaves the
        // vanilla..target corridor. A box grows *outward*: mins only ever move
        // down, maxes only ever move up. Getting that backwards is exactly the
        // kind of thing that reads as "the hitbox shrank when I raised it".
        for ((label, vanilla, _) in buttonCases) {
            val target = SecretHitboxes.targetBounds(SecretHitboxes.Kind.BUTTON, vanilla)
            var prev = SecretHitboxes.lerpBounds(vanilla, target, 0)
            for (size in 1..100) {
                val cur = SecretHitboxes.lerpBounds(vanilla, target, size)
                assertValid(cur, "button($label)@${size}%")
                for (i in 0..5) {
                    if (i < 3) {
                        check(cur[i] <= prev[i] + 1e-12) {
                            "button($label)@${size}% min axis $i moved inwards: ${cur.toList()}"
                        }
                        check(cur[i] >= target[i] - 1e-9 && cur[i] <= vanilla[i] + 1e-9) {
                            "button($label)@${size}% min axis $i escaped target..vanilla: ${cur.toList()}"
                        }
                    } else {
                        check(cur[i] >= prev[i] - 1e-12) {
                            "button($label)@${size}% max axis $i moved inwards: ${cur.toList()}"
                        }
                        check(cur[i] >= vanilla[i] - 1e-9 && cur[i] <= target[i] + 1e-9) {
                            "button($label)@${size}% max axis $i escaped vanilla..target: ${cur.toList()}"
                        }
                    }
                }
                prev = cur
            }
        }

        // The whole point of the button exception: the depth never moves, at
        // any setting, in any orientation. This is the check that fails the
        // moment someone "simplifies" the axis rule back to a fixed Y.
        for ((label, vanilla, depthAxes) in buttonCases) {
            val depth = depthAxes[0]
            val target = SecretHitboxes.targetBounds(SecretHitboxes.Kind.BUTTON, vanilla)
            for (size in 0..100) {
                val b = SecretHitboxes.lerpBounds(vanilla, target, size)
                check(b[depth] == vanilla[depth] && b[depth + 3] == vanilla[depth + 3]) {
                    "button($label)@${size}% changed its depth on axis $depth: ${b.toList()}"
                }
            }
        }

        // ...and it does go full-block across the plate at the top of the range.
        for ((label, vanilla, depthAxes) in buttonCases) {
            val depth = depthAxes[0]
            val target = SecretHitboxes.targetBounds(SecretHitboxes.Kind.BUTTON, vanilla)
            val fullButton = SecretHitboxes.lerpBounds(vanilla, target, 100)
            for (axis in intArrayOf(0, 1, 2)) {
                if (axis == depth) continue
                check(fullButton[axis] == 0.0 && fullButton[axis + 3] == 1.0) {
                    "button($label)@100% must span axis $axis: ${fullButton.toList()}"
                }
            }
        }

        // Per-block size sliders multiply the master: one knob to pull
        // everything back, one to tune a family without disturbing the rest.
        check(SecretHitboxes.sizePercent(100, 100) == 100) { "full master and full family = full" }
        check(SecretHitboxes.sizePercent(50, 100) == 50) { "a halved master halves every family" }
        check(SecretHitboxes.sizePercent(100, 50) == 50) { "a halved family halves only itself" }
        check(SecretHitboxes.sizePercent(0, 80) == 0) { "a master at zero turns everything off" }
        check(SecretHitboxes.sizePercent(80, 0) == 0) { "a family at zero turns itself off" }
        check(SecretHitboxes.sizePercent(30, 40) == 12) { "the two settings compose" }
        check(SecretHitboxes.sizePercent(500, 500) == 100) { "out-of-range settings clamp instead of overflowing" }

        // Out-of-range config values clamp instead of producing a degenerate
        // box — a stale config must never be able to break picking.
        for (kind in SecretHitboxes.Kind.values()) {
            val vanilla = if (kind == SecretHitboxes.Kind.BUTTON) vanillaWallButtonX else vanillaSkull
            val target = SecretHitboxes.targetBounds(kind, vanilla)
            assertValid(SecretHitboxes.lerpBounds(vanilla, target, -10), "$kind@-10%")
            assertValid(SecretHitboxes.lerpBounds(vanilla, target, 500), "$kind@500%")
        }
    }

    // ── Dungeon map canvas / external overlay window ──────────────────────
    run {
        // The map draws once and two backends consume it: straight into the
        // HUD, or recorded and replayed by the external window. A frame that
        // does not round-trip means the two backends disagree about geometry.

        val source = RecordMapCanvas()
        source.push()
        source.translate(8f, 8f)
        source.fill(-2, -2, 102, 102, 0x99000000.toInt())
        source.fill(0, 0, 100, 100, 0xDD0D1F35.toInt())
        source.push()
        source.scale(0.6f, 0.6f)
        source.text("✔", 0, 0, 0xFF55FF55.toInt(), centered = true)
        source.text("Floor", 3, 4, 0xFFFFFFFF.toInt(), centered = false)
        source.pop()
        source.push()
        source.translate(50f, 50f)
        source.rotate(1.5707964f)
        source.marker(true, 8, 12, 1.5f, 0xFFFFFFFF.toInt())
        source.pop()
        source.face("Steve", null, 10, 10, 9, 0xFF00FF00.toInt())
        source.push()
        source.translate(90f, 90f)
        source.marker(false, 6, 9, 0.9f, 0xFFFF0000.toInt())
        source.pop()
        source.pop()

        val copy = RecordMapCanvas()
        replay(source.ops, copy)
        check(copy.ops == source.ops) {
            "recorded frame did not round-trip: ${copy.ops.size} ops vs ${source.ops.size}"
        }

        // Every frame the map emits must be push/pop balanced. The external
        // window restores the Graphics2D transform on a finally block as a
        // safety net, but an unbalanced frame is a bug in the map, not the
        // painter — and it would leave later frames rotated.
        var depth = 0
        var minDepth = 0
        val probe = object : MapCanvas {
            override fun push() { depth++ }
            override fun pop() { depth--; if (depth < minDepth) minDepth = depth }
            override fun translate(x: Float, y: Float) {}
            override fun scale(sx: Float, sy: Float) {}
            override fun rotate(rad: Float) {}
            override fun fill(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {}
            override fun text(text: String, x: Int, y: Int, argb: Int, centered: Boolean) {}
            override fun marker(isSelf: Boolean, w: Int, h: Int, markerScale: Float, tint: Int) {}
            override fun face(label: String, skin: net.minecraft.world.entity.player.PlayerSkin?, x: Int, y: Int, size: Int, borderArgb: Int) {}
        }
        replay(source.ops, probe)
        check(depth == 0) { "map frame left the transform stack $depth deep (expected 0)" }
        check(minDepth >= 0) { "map frame popped more than it pushed (depth reached $minDepth)" }

        // The Java2D backend must honour the same rectangle convention the
        // game uses: x2/y2 are exclusive, and ARGB alpha survives the trip
        // through java.awt.Color.
        val img = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            val canvas = J2dMapCanvas(g2, g2.fontMetrics)
            canvas.fill(2, 3, 6, 7, 0xFFFF0000.toInt())
            fun opaque(x: Int, y: Int) = img.getRGB(x, y) == 0xFFFF0000.toInt()
            check(opaque(2, 3)) { "java2d fill missed its top-left pixel" }
            check(opaque(5, 6)) { "java2d fill missed its bottom-right interior pixel" }
            check(!opaque(6, 6)) { "java2d fill painted past x2 — the game treats x2 as exclusive" }
            check(!opaque(5, 7)) { "java2d fill painted past y2 — the game treats y2 as exclusive" }
            check(!opaque(1, 3) && !opaque(2, 2)) { "java2d fill painted outside its rectangle" }

            // Reversed corners normalise instead of drawing nothing.
            canvas.fill(6, 7, 2, 3, 0xFF00FF00.toInt())
            check(img.getRGB(2, 3) == 0xFF00FF00.toInt() && img.getRGB(5, 6) == 0xFF00FF00.toInt()) {
                "java2d fill did not normalise reversed corners"
            }
            check(img.getRGB(6, 6) == 0 && img.getRGB(5, 7) == 0) {
                "normalised fill painted outside the requested rectangle"
            }

            // Alpha comes through, not just the colour channels.
            canvas.fill(10, 10, 12, 12, 0x8000FF00.toInt())
            val half = img.getRGB(11, 11)
            check(half ushr 24 == 0x80) { "java2d fill dropped alpha: ${(half ushr 24).toString(16)}" }

            // push/translate/fill/pop must leave the surface transform exactly
            // as it was, or the next frame starts offset.
            val before = g2.transform
            canvas.push()
            canvas.translate(5f, 5f)
            canvas.fill(0, 0, 4, 4, 0xFFFFFFFF.toInt())
            canvas.pop()
            check(g2.transform == before) { "java2d transform not restored after push/pop" }
        } finally {
            g2.dispose()
        }
    }


    // ── Map decoration binding: what is allowed to draw as a player ─────────
    run {
        // Bare index keys bind; anything opaque does not, because guessing
        // from a trailing digit would hand a mob marker a teammate's name.
        check(DungeonMapScanner.indexKeyFrom("+0") == 0) { "+0 should bind to index 0" }
        check(DungeonMapScanner.indexKeyFrom("+3") == 3) { "+3 should bind to index 3" }
        check(DungeonMapScanner.indexKeyFrom("2") == 2) { "bare index key should bind" }
        check(DungeonMapScanner.indexKeyFrom("") == null) { "empty key must not bind" }
        check(DungeonMapScanner.indexKeyFrom("+") == null) { "bare plus must not bind" }
        check(DungeonMapScanner.indexKeyFrom("m4") == null) { "key merely ending in a digit must not bind" }
        check(DungeonMapScanner.indexKeyFrom("9a3f2c1d-0000-0000-0000-000000000042") == null) {
            "a UUID key must not bind to whatever digit it happens to end on"
        }

        check(DungeonMapScanner.isPlayerDecoration(MapDecorationTypes.PLAYER.value()))
        check(DungeonMapScanner.isPlayerDecoration(MapDecorationTypes.PLAYER_OFF_MAP.value()))
        check(!DungeonMapScanner.isPlayerDecoration(MapDecorationTypes.FRAME.value())) {
            "item frames are not players"
        }
        for (type in listOf(
            MapDecorationTypes.RED_MARKER.value(),
            MapDecorationTypes.TARGET_POINT.value(),
            MapDecorationTypes.RED_X.value(),
            MapDecorationTypes.WHITE_BANNER.value()
        )) {
            check(!DungeonMapScanner.isPlayerDecoration(type)) { "non-player marker type passed the player filter" }
        }
    }

    // ── Legit map base: vanilla 128x128 pixels -> panel spans ───────────────
    run {
        // 4x4 block of packed colour at (10,10)..(13,13) with one empty pixel
        // punched out at (11,11). Destination is 4 map px -> 64 panel px (16x).
        val colors = ByteArray(128 * 128)
        for (z in 10 until 14) for (x in 10 until 14) colors[z * 128 + x] = 42.toByte()
        colors[11 * 128 + 11] = 0

        val spans = mapBaseSpans(colors, 10, 10, 4, 0, 0, 64)
        check(spans.isNotEmpty()) { "legit base decoded nothing from painted pixels" }
        check(spans.all { it.x0 >= 0 && it.y0 >= 0 && it.x1 <= 64 && it.y1 <= 64 }) {
            "legit base span escaped the destination rect"
        }
        check(spans.all { it.x1 > it.x0 && it.y1 > it.y0 }) { "legit base emitted a degenerate span" }
        check(spans.all { it.argb != 0 }) { "legit base emitted colour for an empty map pixel" }

        // The punched-out pixel maps to panel [16,32) x [16,32); nothing may
        // cover it — that is the "panel background shows through" rule.
        val hole = spans.filter { it.x0 < 32 && it.x1 > 16 && it.y0 < 32 && it.y1 > 16 }
        check(hole.isEmpty()) { "legit base painted over an empty map pixel: $hole" }

        // Rows must tile vertically with no hairline gap: collect every span
        // crossing a fixed column inside the painted block and walk it.
        val column = spans.filter { it.x0 <= 8 && it.x1 > 8 }.sortedBy { it.y0 }
        check(column.size == 4) { "expected one span per row through the column, got ${column.size}" }
        check(column.first().y0 == 0) { "legit base starts below the destination top" }
        for (i in 1 until column.size) {
            check(column[i].y0 == column[i - 1].y1) {
                "legit base left a vertical gap between y=${column[i - 1].y1} and y=${column[i].y0}"
            }
        }
        check(column.last().y1 == 64) { "legit base stops short of the destination bottom" }

        // Wrong-sized and out-of-range sources decode to nothing rather than
        // reading past the array.
        check(mapBaseSpans(ByteArray(100), 0, 0, 4, 0, 0, 64).isEmpty()) { "short colour array must be rejected" }
        check(mapBaseSpans(ByteArray(128 * 128), 0, 0, 0, 0, 0, 64).isEmpty()) { "zero-sized source must be rejected" }

        // A source rect that hangs off the right edge — offset 16 plus six
        // room-gaps runs to 136, past the 128-wide image — must be clipped to
        // the pixels that exist rather than rejected. Floors that start late
        // used to lose the whole image and fall back to the redrawn grid.
        val edge = ByteArray(128 * 128)
        for (z in 0 until 128) for (x in 120 until 128) edge[z * 128 + x] = 42
        val clipped = mapBaseSpans(edge, 16, 0, 120, 0, 0, 120)
        check(clipped.isNotEmpty()) { "clipped source rect painted nothing" }
        check(clipped.all { it.x0 >= 0 && it.y0 >= 0 && it.x1 <= 120 && it.y1 <= 120 }) {
            "clipped span escaped the destination rect"
        }
        // Clipping must not move anything: map x=120 with origin 16 and scale
        // 1 lands at panel x=104, and every column before the paint stays bare.
        check(clipped.all { it.x0 >= 104 }) { "clipped span shifted off its map pixel" }
    }

    // ── Pointer flight time: how the travel meets the terminal's clock ──────
    run {
        // No pending delay: the natural travel time, unchanged.
        check(TerminalCursor.flightDurationMs(200L, -1L) == 200L) { "no delay must not alter the flight" }
        check(TerminalCursor.flightDurationMs(200L, 0L) == 200L) { "zero delay must not alter the flight" }

        // A delay longer than the trip stretches the whole motion out, so the
        // pointer lands on the terminal's clock instead of waiting at the pane.
        check(TerminalCursor.flightDurationMs(200L, 350L) == 350L) { "flight must stretch to fill the delay" }
        check(TerminalCursor.flightDurationMs(150L, 400L) == 400L) { "flight must fill the whole window" }

        // Never stretched so far it stops reading as a hand.
        check(TerminalCursor.flightDurationMs(200L, 999_999L) == 1000L) { "stretch must be capped" }

        // A short delay never speeds the pointer up: the speed setting wins,
        // and the click falls on arrival instead.
        check(TerminalCursor.flightDurationMs(420L, 50L) == 420L) { "short delay must not outrun the setting" }
        check(TerminalCursor.flightDurationMs(300L, 100L) == 300L) { "short delay must not shorten the flight" }

        // Out-of-range natural times are pulled back inside the flight bounds.
        check(TerminalCursor.flightDurationMs(5L, 0L) == 70L) { "flight must respect its floor" }
        check(TerminalCursor.flightDurationMs(99_999L, 0L) == 420L) { "flight must respect its ceiling" }

        // And it never shrinks as the window grows.
        var previousDuration = 0L
        for (available in listOf(0L, 50L, 120L, 250L, 500L, 900L)) {
            val duration = TerminalCursor.flightDurationMs(180L, available)
            check(duration >= previousDuration) { "a longer window must never shorten the flight" }
            previousDuration = duration
        }
    }

    // ── Terminal pointer motion ─────────────────────────────────────────────
    run {
        check(TerminalCursor.progressAt(0, 100) == 0f) { "pointer must start at rest" }
        check(TerminalCursor.progressAt(100, 100) == 1f) { "pointer must land at the end of its duration" }
        check(TerminalCursor.progressAt(9_999, 100) == 1f) { "pointer must not run past the end" }
        check(TerminalCursor.progressAt(5, 0) == 1f) { "a zero duration must resolve immediately" }

        var previous = -1f
        for (elapsed in 0..100 step 2) {
            val p = TerminalCursor.progressAt(elapsed.toLong(), 100L)
            check(p >= previous) { "pointer progress went backwards at ${elapsed}ms" }
            previous = p
        }
        // Minimum-jerk easing eases in and out: the first fifth of the trip
        // must cover less than a fifth of the distance.
        check(TerminalCursor.progressAt(20, 100) < 0.2f) { "pointer leaves the start too fast to read as a hand" }

        val slow = TerminalCursor.travelDurationMs(120f, 100, 0f)
        val fast = TerminalCursor.travelDurationMs(120f, 400, 0f)
        check(slow in 70L..420L) { "natural travel time out of range: $slow" }
        check(fast in 70L..420L) { "fast travel time out of range: $fast" }
        check(fast < slow) { "raising speed must shorten the trip ($fast vs $slow)" }
        check(TerminalCursor.travelDurationMs(10f, 100, 0f) < TerminalCursor.travelDurationMs(400f, 100, 0f)) {
            "a longer trip must take longer"
        }
        check(TerminalCursor.travelDurationMs(120f, 100, 1f) > TerminalCursor.travelDurationMs(120f, 100, -1f)) {
            "jitter must be able to make identical trips differ"
        }

        check(TerminalCursor.bezier(0f, 50f, 100f, 0f) == 0f) { "bezier must start at its first control point" }
        check(TerminalCursor.bezier(0f, 50f, 100f, 1f) == 100f) { "bezier must end at its last control point" }
        check(kotlin.math.abs(TerminalCursor.bezier(0f, 50f, 100f, 0.5f) - 50f) < 0.001f) {
            "bezier midpoint must sit on the control point when endpoints are symmetric"
        }

        val runs = TerminalCursor.arrowRuns()
        check(runs.size >= 12) { "pointer sprite lost its tail: only ${runs.size} rows" }
        check(runs.all { it.row in 0..15 && it.x0 >= 0 && it.x1 < 16 && it.x0 <= it.x1 }) {
            "pointer sprite has a row outside its 16x16 grid"
        }
        check(runs.map { it.row } == runs.mapIndexed { i, _ -> i }) { "pointer sprite rows must be contiguous from the tip" }
        check(runs.first { it.row == 0 }.let { it.x0 == 0 && it.x1 == 0 }) { "pointer tip must be a single pixel" }
    }

    println("Dungeon regression checks passed: scoreboard detection, terminal timing, terminal identification, candidates and click order, map dimensions/bounds, mob categories, tictactoe solver, secret hitbox expansion geometry, map overlay canvas, map decoration binding, legit map base, terminal pointer motion and flight timing.")
}

/**
 * A built-in registry lookup that answers tag queries even though nothing has
 * loaded the tags.
 *
 * Vanilla binds tags in the same resource reload that binds item components.
 * This harness has no resources, so a component builder that asks for a tag
 * (fire resistance reads `#minecraft:is_fire`) would die on "Missing tag" and
 * the whole bind step would fall over before any check ran. The answer here is
 * an empty but *bound* tag set: present, iterable, containing nothing — which
 * is everything the component builders in this harness do with it. Element
 * lookups pass straight through to the real registry.
 */
private class UntaggedLookup<T : Any>(private val delegate: HolderLookup.RegistryLookup<T>) :
    HolderLookup.RegistryLookup<T> {

    override fun key(): ResourceKey<out Registry<out T>> = delegate.key()
    override fun registryLifecycle(): Lifecycle = delegate.registryLifecycle()
    override fun listElements(): Stream<Holder.Reference<T>> = delegate.listElements()
    override fun listTags(): Stream<HolderSet.Named<T>> = delegate.listTags()
    override fun get(key: ResourceKey<T>): Optional<Holder.Reference<T>> = delegate.get(key)

    override fun get(tag: TagKey<T>): Optional<HolderSet.Named<T>> {
        val loaded = delegate.get(tag)
        return if (loaded.isPresent) loaded else Optional.of(emptyTagSet(this, tag))
    }
}

/**
 * The provider the component builders read from: every built-in registry as
 * it really is, and a stub for anything else.
 *
 * "Anything else" is not hypothetical — `damage_type` is a dynamic registry
 * that only exists once datapacks are loaded, and the fire-resistance
 * initializer asks it for `#minecraft:is_fire` the moment it runs. The stub
 * has no elements and no tags, which is the truth about this harness: it
 * never loads a datapack, and nothing here damages anything.
 */
private class HarnessLookupProvider(
    private val builtIns: Map<ResourceKey<*>, HolderLookup.RegistryLookup<*>>
) : HolderLookup.Provider {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> lookup(
        key: ResourceKey<out Registry<out T>>
    ): Optional<out HolderLookup.RegistryLookup<T>> {
        val real = builtIns[key] as HolderLookup.RegistryLookup<T>?
        return Optional.of(real ?: StubLookup(key as ResourceKey<Registry<T>>))
    }

    override fun listRegistryKeys(): Stream<ResourceKey<out Registry<*>>> =
        builtIns.keys.filterIsInstance<ResourceKey<out Registry<*>>>().stream()
}

/**
 * A registry this harness never populates: no listable elements, tags that are
 * present but empty, and a synthetic holder for any element asked for by name.
 *
 * The component builders only ever *store* what they look up — the holder goes
 * straight into the component map without its value being read — so an
 * unbound reference is enough. `trim_material` is the one that actually asks:
 * an item declares a delayed holder component pointing at
 * `minecraft:trim_material/redstone`, and without a datapack there is nothing
 * real to hand back.
 */
private class StubLookup<T : Any>(private val registryKey: ResourceKey<Registry<T>>) :
    HolderLookup.RegistryLookup<T> {

    private val holders = HashMap<ResourceKey<T>, Holder.Reference<T>>()

    override fun key(): ResourceKey<out Registry<out T>> = registryKey
    override fun registryLifecycle(): Lifecycle = Lifecycle.stable()
    override fun listElements(): Stream<Holder.Reference<T>> = Stream.empty()
    override fun listTags(): Stream<HolderSet.Named<T>> = Stream.empty()

    override fun get(key: ResourceKey<T>): Optional<Holder.Reference<T>> =
        Optional.of(holders.getOrPut(key) { Holder.Reference.createStandAlone(this, key) })

    override fun get(tag: TagKey<T>): Optional<HolderSet.Named<T>> = Optional.of(emptyTagSet(this, tag))
}

/** `HolderSet.Named` is constructible and bindable, but only inside its own package. */
private val namedTagConstructor = HolderSet.Named::class.java
    .getDeclaredConstructor(HolderOwner::class.java, TagKey::class.java)
    .apply { isAccessible = true }

private val namedTagBind = HolderSet.Named::class.java
    .getDeclaredMethod("bind", List::class.java)
    .apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
private fun <T : Any> emptyTagSet(owner: HolderOwner<T>, tag: TagKey<T>): HolderSet.Named<T> {
    val named = namedTagConstructor.newInstance(owner, tag) as HolderSet.Named<T>
    namedTagBind.invoke(named, emptyList<Holder<T>>())
    return named
}
