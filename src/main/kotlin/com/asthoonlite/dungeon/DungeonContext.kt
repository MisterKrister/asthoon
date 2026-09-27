package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.dungeon.api.FloorType
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.phys.Vec3
import kotlin.math.round

/** Keeps dungeon-only features from running on islands/normal SkyBlock areas. */
object DungeonContext {
    @Volatile var inDungeon: Boolean = false
        private set
    @Volatile var inBoss: Boolean = false
        private set
    var floor: FloorType = FloorType.None
        private set

    enum class PlayerClass(val displayName: String, val color: Int) {
        ARCHER("Archer", 0xFFFF3333.toInt()),
        MAGE("Mage", 0xFF3388FF.toInt()),
        TANK("Tank", 0xFF33CC33.toInt()),
        BERSERK("Berserk", 0xFFFFAA00.toInt()),
        HEALER("Healer", 0xFFAA00AA.toInt()),
        UNKNOWN("Unknown", 0xFFFFFFFF.toInt());

        companion object {
            fun from(name: String): PlayerClass = when (name.uppercase()) {
                "ARCHER" -> ARCHER
                "MAGE" -> MAGE
                "TANK" -> TANK
                "BERSERK" -> BERSERK
                "HEALER" -> HEALER
                else -> UNKNOWN
            }
        }
    }

    val playerClasses = java.util.concurrent.ConcurrentHashMap<String, PlayerClass>()

    fun classColor(playerName: String?): Int {
        if (playerName == null) return PlayerClass.UNKNOWN.color
        val direct = playerClasses[playerName]
        if (direct != null) return direct.color
        val match = playerClasses.entries.firstOrNull { it.key.equals(playerName, ignoreCase = true) }
        return match?.value?.color ?: PlayerClass.UNKNOWN.color
    }

    private var scoreboardMissingTicks = 0
    private var lastLoggedLines: List<String> = emptyList()
    private val floorPattern = Regex("The Catacombs \\(([FM][1-7]|E)\\)", RegexOption.IGNORE_CASE)
    private val tabClassPattern = Regex("""(?:\[\d+\]\s+)?(?:\[[^\]]+\]\s+)*([A-Za-z0-9_]{1,16})\s+(?:.*?\s+)?\((\w+)(?:\s+[0-9IVXLCDM]+)?\)""")

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) onChat(ChatFormatting.stripFormatting(text.string) ?: "")
            true
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset("ClientPlayConnectionEvents.JOIN") }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset("ClientPlayConnectionEvents.DISCONNECT") }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun onChat(message: String) {
        when {
            message == "[NPC] Mort: Here, I found this map when I first entered the dungeon." -> {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Mort map chat message detected.")
                activate("Chat Mort")
            }
            message.startsWith("[BOSS] Maxor:") ||
                message.startsWith("[BOSS] Storm:") ||
                message.startsWith("[BOSS] Goldor:") ||
                message.startsWith("[BOSS] Necron:") ||
                message.startsWith("[BOSS] Bonzo:") ||
                message.startsWith("[BOSS] Scarf:") ||
                message.startsWith("[BOSS] The Professor:") ||
                message.startsWith("[BOSS] Thorn:") ||
                message.startsWith("[BOSS] Livid:") ||
                message.startsWith("[BOSS] Sadan:") -> {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Boss chat detected: '$message'")
                activate("Chat Boss")
                if (!inBoss) {
                    inBoss = true
                    AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: inBoss transitioned to TRUE.")
                }
            }
            message.startsWith("[BOSS] The Watcher:") ||
                message == "The BLOOD DOOR has been opened!" -> {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Blood/Watcher chat detected: '$message'")
                activate("Chat Watcher/Blood")
            }
            message.contains("Sending to server", ignoreCase = true) ||
                message.contains("Dungeon Hub", ignoreCase = true) ||
                message.contains("You were spawned in Limbo", ignoreCase = true) -> {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Exit chat detected: '$message'")
                reset("Chat Exit: $message")
            }
        }
    }

    fun onSetPlayerTeam(packet: ClientboundSetPlayerTeamPacket) {
        val params = packet.parameters.orElse(null) ?: return
        val prefix = params.playerPrefix.string
        val suffix = params.playerSuffix.string
        val raw = prefix + suffix
        val text = ChatFormatting.stripFormatting(raw) ?: raw
        if (text.contains("The Catacombs (", ignoreCase = true) && !text.contains("Queue", ignoreCase = true)) {
            AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: TeamPacket matched Catacombs: '$text'")
            floorPattern.find(text)?.let {
                val detected = FloorType.from(it.groupValues[1])
                if (floor != detected) {
                    floor = detected
                    AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Detected floor from TeamPacket: $floor")
                }
            }
            activate("TeamPacket: $text")
        }
    }

    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket) {
        val actions = packet.actions()
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) ||
            actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            for (entry in packet.entries()) {
                val display = entry.displayName?.string ?: continue
                val text = ChatFormatting.stripFormatting(display) ?: display
                if (text.startsWith("Area: ", ignoreCase = true) || text.startsWith("Dungeon: ", ignoreCase = true)) {
                    AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: TabList area entry: '$text'")
                    if (text.contains("Catacombs", ignoreCase = true)) {
                        activate("TabList: $text")
                    } else if (inDungeon && !text.contains("Catacombs", ignoreCase = true)) {
                        AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Left Catacombs according to TabList: '$text'")
                        reset("TabList Left Catacombs: $text")
                    }
                }
            }
        }
    }

    private fun activate(reason: String) {
        if (!inDungeon) {
            AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext.activate(): inDungeon transitioned from FALSE to TRUE! Reason: $reason")
        }
        inDungeon = true
        scoreboardMissingTicks = 0
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || mc.player == null) {
            if (inDungeon) reset("Minecraft level or player is null")
            return
        }

        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        val lines = sidebarLines(scoreboard, objective)
        if (lines != lastLoggedLines) {
            lastLoggedLines = lines
            if (lines.isNotEmpty()) {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Sidebar updated (${lines.size} lines, obj='${objective?.name}'): [${lines.joinToString(" | ")}]")
            }
        }
        updateFromSidebar(lines)

        // Parse player classes from tab list
        val onlinePlayers = mc.connection?.onlinePlayers ?: emptyList()
        for (info in onlinePlayers) {
            val displayName = info.tabListDisplayName?.string
            val text = (if (displayName != null) ChatFormatting.stripFormatting(displayName) else null) ?: ""
            if (text.isNotBlank()) {
                parseTabPlayerClass(info.profile.name, text)
            }
        }
    }

    private fun parseTabPlayerClass(playerName: String, text: String) {
        val upper = text.uppercase()
        val detected = when {
            upper.contains("(ARCHER") || upper.contains(" ARCHER") || upper.contains("ARCHER)") -> PlayerClass.ARCHER
            upper.contains("(MAGE") || upper.contains(" MAGE") || upper.contains("MAGE)") -> PlayerClass.MAGE
            upper.contains("(TANK") || upper.contains(" TANK") || upper.contains("TANK)") -> PlayerClass.TANK
            upper.contains("(BERSERK") || upper.contains(" BERSERK") || upper.contains("BERSERK)") -> PlayerClass.BERSERK
            upper.contains("(HEALER") || upper.contains(" HEALER") || upper.contains("HEALER)") -> PlayerClass.HEALER
            else -> {
                val match = tabClassPattern.find(text)
                val role = match?.groupValues?.getOrNull(2)
                if (role == null || role.equals("DEAD", ignoreCase = true)) null else PlayerClass.from(role)
            }
        }
        if (detected != null && detected != PlayerClass.UNKNOWN) {
            playerClasses[playerName] = detected
            val match = tabClassPattern.find(text)
            if (match != null) {
                playerClasses[match.groupValues[1]] = detected
            }
        }
    }

    internal fun sidebarLines(scoreboard: Scoreboard, objective: Objective?): List<String> =
        objective?.let { scoreboard.listPlayerScores(it) }.orEmpty().filterNot { it.isHidden }.map { score ->
            val name = score.owner
            val line = PlayerTeam.formatNameForTeam(
                scoreboard.getPlayersTeam(name), score.ownerName()
            ).string
            ChatFormatting.stripFormatting(line) ?: line
        }

    internal fun updateFromSidebar(lines: List<String>) {
        val hasDungeonScoreboard = lines.any { line ->
            line.contains("The Catacombs", ignoreCase = true) ||
                line.contains("Completed Rooms:", ignoreCase = true) ||
                line.contains("Secrets Found:", ignoreCase = true) ||
                line.contains("Time Elapsed:", ignoreCase = true) ||
                line.contains("Cleared:", ignoreCase = true) ||
                line.contains("Dungeon:", ignoreCase = true)
        }

        if (hasDungeonScoreboard) {
            lines.firstNotNullOfOrNull { floorPattern.find(it) }
                ?.let {
                    val detected = FloorType.from(it.groupValues[1])
                    if (floor != detected) {
                        floor = detected
                        AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext: Detected floor from Sidebar: $floor")
                    }
                }
            activate("Sidebar lines")
        } else if (inDungeon) {
            scoreboardMissingTicks++
            if (scoreboardMissingTicks > 80) {
                reset("Scoreboard missing for > 80 ticks")
            }
        }
    }

    fun roomKey(pos: Vec3): Pair<Int, Int> =
        round((pos.x + 185.0) / 32.0).toInt() to round((pos.z + 185.0) / 32.0).toInt()

    fun currentRoomKey(): Pair<Int, Int>? =
        Minecraft.getInstance().player?.let { roomKey(it.position()) }

    fun sameRoom(a: Vec3, b: Vec3): Boolean = roomKey(a) == roomKey(b)

    fun reset(reason: String = "manual") {
        if (inDungeon || inBoss || floor != FloorType.None) {
            AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonContext.reset() called! Reason: $reason, wasInDungeon=$inDungeon, wasInBoss=$inBoss, wasFloor=$floor")
        }
        inDungeon = false
        inBoss = false
        floor = FloorType.None
        scoreboardMissingTicks = 0
        lastLoggedLines = emptyList()
        playerClasses.clear()
    }
}
