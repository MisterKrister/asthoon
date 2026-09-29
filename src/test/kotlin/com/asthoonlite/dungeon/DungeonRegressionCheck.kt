package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.api.FloorType
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.solvers.TicTacToeSolver
import net.minecraft.network.chat.Component
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/** Run with ./gradlew regressionCheck (also included in build). No game or server needed. */
fun main() {
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

    println("Dungeon regression checks passed: scoreboard detection, terminal timing, map dimensions/bounds, mob categories, tictactoe solver.")
}
