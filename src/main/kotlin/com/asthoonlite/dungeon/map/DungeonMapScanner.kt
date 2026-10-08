package com.asthoonlite.dungeon.map

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.api.*
import com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes
import com.asthoonlite.dungeon.api.mapEnums.DoorTypes
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.mixin.IMapState
import com.asthoonlite.utils.MathUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import kotlin.math.PI

object DungeonMapScanner {
    private const val COLOR_SIZE = 16384
    private const val SCAN = 128
    private const val ROOM_SPACING = 4
    var roomSize = -1
    var roomGap = -1
    var mapOffsetX = -1
    var mapOffsetZ = -1
    var mapWidth = -1
    var mapHeight = -1
    private val unscannedDoors = mutableSetOf<ComponentPosition>()
    private var lastMapId: MapId? = null
    private var scanTicks = 0
    var playerIcons = mutableListOf<PlayerIcon>()
    /** Raw 128×128 packed colours of the map currently being scanned; the
     *  legit base draws its pixels from here. Null until the first map packet. */
    var mapColors: ByteArray? = null
        private set

    data class PlayerIcon(val x: Double, val z: Double, val rot: Double, val name: String?)

    fun reset() {
        roomSize = -1
        roomGap = -1
        mapOffsetX = -1
        mapOffsetZ = -1
        mapWidth = -1
        mapHeight = -1
        unscannedDoors.clear()
        for (x in 0..10) {
            for (z in (x and 1 xor 1)..10 step 2) {
                unscannedDoors.add(ComponentPosition(x, z))
            }
        }
        playerIcons.clear()
        lastMapId = null
        scanTicks = 0
        mapColors = null
    }

    private enum class MapColors(val color: Byte) {
        EMPTY(0),
        CHECK_WHITE(34),
        CHECK_GREEN(30),
        CHECK_FAIL(18),
        CHECK_UNKNOWN(119),

        ROOM_ENTRANCE(30),
        ROOM_NORMAL(63),
        ROOM_UNOPENED(85),
        ROOM_TRAP(62),
        ROOM_BOSS(74),
        ROOM_PUZZLE(66),
        ROOM_FAIRY(82),
        ROOM_BLOOD(18),

        DOOR_WITHER(119),
        DOOR_BLOOD(18);
    }

    internal fun scanMapDimensions(colors: ByteArray, floor: FloorType): Boolean {
        val f = if (floor != FloorType.None) floor else FloorType.M7
        if (colors.size < COLOR_SIZE) {
            AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] scanMapDimensions: colors.size (${colors.size}) < COLOR_SIZE ($COLOR_SIZE)")
            return false
        }

        // Use the entrance's edges; ignore small green checkmarks and interior symbols.
        for (idx in 0 until COLOR_SIZE) {
            val x = idx % SCAN
            val z = idx / SCAN
            if (colors[idx] != MapColors.ROOM_ENTRANCE.color ||
                colorAt(colors, x - 1, z) == MapColors.ROOM_ENTRANCE.color ||
                colorAt(colors, x, z - 1) == MapColors.ROOM_ENTRANCE.color) continue
            var width = 0
            var height = 0
            while (colorAt(colors, x + width, z) == MapColors.ROOM_ENTRANCE.color) width++
            while (colorAt(colors, x, z + height) == MapColors.ROOM_ENTRANCE.color) height++
            if (width !in 8..32 || width != height) {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] scanMapDimensions: candidate at ($x,$z) rejected width=$width, height=$height (not in 8..32 or non-square)")
                continue
            }
            if ((width + ROOM_SPACING) * f.roomsW - ROOM_SPACING > SCAN ||
                (width + ROOM_SPACING) * f.roomsH - ROOM_SPACING > SCAN) {
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] scanMapDimensions: candidate at ($x,$z) size ($width) exceeded SCAN for floor ${f.shortName}")
                continue
            }
            roomSize = width
            roomGap = roomSize + ROOM_SPACING
            mapOffsetX = x % roomGap
            mapOffsetZ = z % roomGap
            mapWidth = roomGap * (f.roomsW - 1) + roomSize
            mapHeight = roomGap * (f.roomsH - 1) + roomSize
            if (SCAN - mapWidth >= roomGap * 2) mapOffsetX += roomGap
            if (SCAN - mapHeight >= roomGap * 2) mapOffsetZ += roomGap
            AsthoonLite.LOGGER.info("[AsthoonLite-Debug] scanMapDimensions SUCCESS: roomSize=$roomSize, roomGap=$roomGap, offset=($mapOffsetX,$mapOffsetZ), size=($mapWidth,$mapHeight), floor=${f.shortName}")
            return true
        }
        AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] scanMapDimensions: entrance NOT found in ${colors.size} bytes (nonZero=${colors.count { it != 0.toByte() }}, floor=${f.shortName})")
        return false
    }

    fun onMapPacket(packet: ClientboundMapItemDataPacket) {
        if (!DungeonContext.inDungeon) return
        val mapId = packet.mapId()
        val invMapId = inventoryMapId()
        if (invMapId != null && mapId != invMapId) return
        lastMapId = mapId
        updateMap(mapId)
    }

    private fun inventoryMapId(): MapId? {
        val inventory = Minecraft.getInstance().player?.inventory ?: return null
        return (0 until inventory.containerSize).firstNotNullOfOrNull {
            inventory.getItem(it).get(DataComponents.MAP_ID)
        }
    }

    private fun updateMap(mapId: MapId) {
        val level = Minecraft.getInstance().level ?: run {
            AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] DungeonMapScanner.updateMap: mc.level is null")
            return
        }
        val mapState = level.getMapData(mapId) ?: run {
            AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] DungeonMapScanner.updateMap: level.getMapData(${mapId.id()}) returned null")
            return
        }
        val colors = mapState.colors
        if (colors.size < COLOR_SIZE) {
            AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] DungeonMapScanner.updateMap: colors.size (${colors.size}) < COLOR_SIZE")
            return
        }
        lastMapId = mapId
        mapColors = colors

        val floor = if (DungeonContext.floor != FloorType.None) DungeonContext.floor else FloorType.M7
        if (roomSize == -1 && !scanMapDimensions(colors, floor)) {
            AsthoonLite.LOGGER.warn("[AsthoonLite-Debug] DungeonMapScanner.updateMap: roomSize is -1 and scanMapDimensions failed")
            return
        }

        updateRooms(colors)
        // The keyed map, not `mapState.decorations` (that getter throws the keys
        // away, and the key is the only thing that ties a decoration back to a
        // named player — see updatePlayerIcons).
        val keyed = (mapState as? IMapState)?.`asthoonlite$getDecorations`() ?: return
        updatePlayerIcons(keyed)
    }

    /**
     * Decoration types that vanilla uses for actual players. Everything else on
     * the map (markers, targets, banners, and whatever Hypixel drops in for mobs
     * and waypoints) is not a player and must not be drawn as one.
     */
    internal fun isPlayerDecoration(type: net.minecraft.world.level.saveddata.maps.MapDecorationType): Boolean =
        type === MapDecorationTypes.PLAYER.value() ||
            type === MapDecorationTypes.PLAYER_OFF_MAP.value() ||
            type === MapDecorationTypes.PLAYER_OFF_LIMITS.value()

    /**
     * The decoration key when — and only when — it is a bare index ("0",
     * "+3"). Hypixel has used both index-style and opaque keys for player
     * markers over the years, so anything else (a UUID, a name, "mob-2")
     * returns null rather than guessing: a key that only *ends* in a digit
     * would otherwise bind a mob marker to whichever teammate that digit
     * happened to index. The ordered-name fallback in [updatePlayerIcons]
     * covers the opaque-key case.
     */
    internal fun indexKeyFrom(key: String): Int? {
        val body = key.removePrefix("+")
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        return body.toIntOrNull()
    }

    /**
     * Turns the raw decoration map into the icons the map draws.
     *
     * The rule is the one that keeps mobs off the map: a decoration is only
     * drawn if it is a player-type marker AND it resolves to a name — either
     * the name Hypixel attached to it, or the teammate its key index points at.
     * A marker with no player behind it is dropped instead of being handed a
     * teammate's name by list position, which is what used to put mob markers
     * on the map wearing somebody else's face.
     *
     * `Config.dungeonMapAllDecorations` opts back in to drawing every non-frame
     * decoration for people who do want the extra markers; those keep whatever
     * name the packet carried, or none at all.
     */
    private fun updatePlayerIcons(decorations: Map<String, MapDecoration>) {
        if (roomGap <= 0 || roomSize <= 0) return
        val mc = Minecraft.getInstance()
        val localPlayer = mc.player ?: return
        val localName = localPlayer.gameProfile.name

        val selfGx = (localPlayer.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize.toDouble()
        val selfGz = (localPlayer.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize.toDouble()

        val teammates = com.asthoonlite.dungeon.DungeonContext.getTeammateNames()

        data class DecCandidate(val key: String, val dec: MapDecoration, val gx: Double, val gz: Double, val rot: Double, val explicitName: String?)

        val candidates = mutableListOf<DecCandidate>()
        for ((key, dec) in decorations) {
            if (dec.type().value() == MapDecorationTypes.FRAME.value()) continue
            val pixelX = (dec.x().toDouble() + 128.0) * 0.5
            val pixelZ = (dec.y().toDouble() + 128.0) * 0.5
            val gx = (pixelX - (mapOffsetX + roomSize / 2.0)) / roomGap.toDouble()
            val gz = (pixelZ - (mapOffsetZ + roomSize / 2.0)) / roomGap.toDouble()
            val rot = Math.toRadians((dec.rot().toDouble() * 22.5 + 180.0) % 360.0)
            val explicitName = dec.name().map { it.string }.orElse(null)
            candidates.add(DecCandidate(key, dec, gx, gz, rot, explicitName))
        }

        // Identify local player marker (candidate closest to selfGx, selfGz)
        val selfCandidate = candidates.minByOrNull { kotlin.math.hypot(it.gx - selfGx, it.gz - selfGz) }
        val remaining = if (selfCandidate != null && kotlin.math.hypot(selfCandidate.gx - selfGx, selfCandidate.gz - selfGz) < 0.9) {
            candidates.filter { it !== selfCandidate }.toMutableList()
        } else {
            candidates.toMutableList()
        }

        val boundIcons = mutableListOf<PlayerIcon>()
        val assignedTeammates = mutableSetOf<String>()
        val unassignedCandidates = mutableListOf<DecCandidate>()

        // 1. Explicit name if provided
        for (cand in remaining) {
            val name = cand.explicitName
            if (name != null && teammates.any { it.equals(name, ignoreCase = true) }) {
                val realName = teammates.first { it.equals(name, ignoreCase = true) }
                boundIcons.add(PlayerIcon(cand.gx, cand.gz, cand.rot, realName))
                assignedTeammates.add(realName.lowercase())
            } else {
                unassignedCandidates.add(cand)
            }
        }

        // 2. Index key if available
        val keyIter = unassignedCandidates.iterator()
        while (keyIter.hasNext()) {
            val cand = keyIter.next()
            val idx = indexKeyFrom(cand.key)
            if (idx != null) {
                val name = teammates.getOrNull(idx)
                if (name != null && !assignedTeammates.contains(name.lowercase())) {
                    boundIcons.add(PlayerIcon(cand.gx, cand.gz, cand.rot, name))
                    assignedTeammates.add(name.lowercase())
                    keyIter.remove()
                }
            }
        }

        // 3. Match world teammates in render distance by proximity
        val worldPlayers = mc.level?.players() ?: emptyList()
        for (mate in worldPlayers) {
            val mName = mate.gameProfile.name
            if (mName.equals(localName, ignoreCase = true) || mate.isSpectator) continue
            if (assignedTeammates.contains(mName.lowercase())) continue
            if (!teammates.any { it.equals(mName, ignoreCase = true) }) continue

            val mateGx = (mate.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize.toDouble()
            val mateGz = (mate.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize.toDouble()

            val nearest = unassignedCandidates.minByOrNull { kotlin.math.hypot(it.gx - mateGx, it.gz - mateGz) }
            if (nearest != null && kotlin.math.hypot(nearest.gx - mateGx, nearest.gz - mateGz) < 0.9) {
                boundIcons.add(PlayerIcon(nearest.gx, nearest.gz, nearest.rot, mName))
                assignedTeammates.add(mName.lowercase())
                unassignedCandidates.remove(nearest)
            }
        }

        // 4. For any remaining candidates (distant), bind remaining teammates in party order
        val remainingTeammates = teammates.filter { !assignedTeammates.contains(it.lowercase()) }
        unassignedCandidates.forEachIndexed { i, cand ->
            val name = remainingTeammates.getOrNull(i)
            if (name != null || Config.dungeonMapAllDecorations) {
                boundIcons.add(PlayerIcon(cand.gx, cand.gz, cand.rot, name))
            }
        }

        playerIcons = boundIcons
    }

    internal fun colorAt(colors: ByteArray, x: Int, z: Int): Byte? =
        if (x in 0 until SCAN && z in 0 until SCAN) colors.getOrNull(x + z * SCAN) else null

    private fun updateRooms(colors: ByteArray) {
        val visited = mutableSetOf<DungeonRoom>()
        for (idx in DungeonScanner.rooms.indices) {
            val room_ = DungeonScanner.rooms[idx]
            if (room_ != null && !visited.add(room_)) continue

            val x = idx % 6
            val z = idx / 6
            if (x * roomGap >= mapWidth || z * roomGap >= mapHeight) continue
            val mrx = mapOffsetX + x * roomGap
            val mrz = mapOffsetZ + z * roomGap
            val mcx = mrx + roomSize / 2 - 1
            val mcz = mrz + roomSize / 2 - 1 + 2
            val roomCol = colorAt(colors, mrx, mrz) ?: continue
            val centerCol = colorAt(colors, mcx, mcz) ?: continue

            if (roomCol == MapColors.EMPTY.color) continue

            val room: DungeonRoom
            if (room_ == null) {
                val comp = ComponentPosition(x * 2, z * 2)
                room = DungeonRoom(mutableListOf(comp.withWorld()), 0).scan()
                DungeonScanner.addRoom(comp, room)
            } else {
                room = room_
                if (room.name == null) room.scan()
            }

            room.type = when (roomCol) {
                MapColors.ROOM_ENTRANCE.color -> RoomTypes.ENTRANCE
                MapColors.ROOM_BLOOD.color -> RoomTypes.BLOOD
                MapColors.ROOM_UNOPENED.color ->
                    if (room.type == RoomTypes.UNKNOWN) RoomTypes.UNKNOWN
                    else room.type
                MapColors.ROOM_BOSS.color -> RoomTypes.YELLOW
                MapColors.ROOM_FAIRY.color -> RoomTypes.FAIRY
                MapColors.ROOM_NORMAL.color ->
                    if (room.type == RoomTypes.RARE) RoomTypes.RARE
                    else RoomTypes.NORMAL
                MapColors.ROOM_PUZZLE.color -> RoomTypes.PUZZLE
                MapColors.ROOM_TRAP.color -> RoomTypes.TRAP
                else -> RoomTypes.UNKNOWN
            }

            val nstate = roomCol != MapColors.ROOM_UNOPENED.color
            room.explored = nstate || room.clientExplored
            if (nstate) room.clientExplored = false

            if (room.checkmark != CheckmarkTypes.GREEN) {
                room.checkmark = if (roomCol == centerCol) CheckmarkTypes.NONE
                else when (centerCol) {
                    MapColors.CHECK_WHITE.color -> CheckmarkTypes.WHITE
                    MapColors.CHECK_GREEN.color -> CheckmarkTypes.GREEN
                    MapColors.CHECK_FAIL.color -> CheckmarkTypes.FAILED
                    MapColors.CHECK_UNKNOWN.color ->
                        if (room.checkmark == CheckmarkTypes.NONE) CheckmarkTypes.NONE
                        else CheckmarkTypes.UNEXPLORED
                    else -> CheckmarkTypes.NONE
                }
            }
        }

        unscannedDoors.removeIf { comp ->
            val idx = comp.getDoorIdx()
            val mjx = mapOffsetX + (comp.x / 2) * roomGap + (comp.x and 1) * roomSize
            val mjz = mapOffsetZ + (comp.z / 2) * roomGap + (comp.z and 1) * roomSize
            val mdx = mjx + (comp.z and 1) * roomSize / 2
            val mdz = mjz + (comp.x and 1) * roomSize / 2
            if (mjx >= mapOffsetX + mapWidth || mjz >= mapOffsetZ + mapHeight) return@removeIf false
            val joinedCol = colorAt(colors, mjx, mjz) ?: return@removeIf false
            val doorCol = colorAt(colors, mdx, mdz) ?: return@removeIf false

            if (doorCol == MapColors.EMPTY.color) return@removeIf false

            if (joinedCol == doorCol) {
                val neighboring = comp.getNeighboringRooms()
                if (neighboring.size != 2) return@removeIf false
                val r1 = DungeonScanner.rooms[neighboring[0].getRoomIdx()]
                val r2 = DungeonScanner.rooms[neighboring[1].getRoomIdx()]
                if (r1?.type == RoomTypes.FAIRY || r2?.type == RoomTypes.FAIRY) return@removeIf false
                DungeonScanner.doors[idx]?.also { d ->
                    d.rooms.forEach { it.doors.remove(d) }
                }
                DungeonScanner.doors[idx] = null
                return@removeIf DungeonScanner.mergeRooms(neighboring[0], neighboring[1])
            }

            val door = DungeonScanner.doors[idx] ?: let {
                val d = DungeonDoor(comp.withWorld())
                DungeonScanner.addDoor(d)
                d
            }

            return@removeIf when (doorCol) {
                MapColors.DOOR_WITHER.color -> {
                    door.type = DoorTypes.WITHER
                    door.opened = false
                    if (
                        comp.getNeighboringRooms()
                            .mapNotNull { DungeonScanner.rooms[it.getRoomIdx()] }
                            .any { it.type == RoomTypes.FAIRY && !it.explored }
                    ) door.holyShitFairyDoorPleaseStopFlashingSobs = true
                    false
                }
                MapColors.DOOR_BLOOD.color -> {
                    door.type = DoorTypes.BLOOD
                    true
                }
                else -> {
                    door.type = DoorTypes.NORMAL
                    door.opened = true
                    true
                }
            }
        }
    }

    fun register() {
        // Map data can arrive before dungeon detection or before the map inventory slot.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (DungeonContext.inDungeon) {
                if (++scanTicks % 10 == 0) {
                    (lastMapId ?: inventoryMapId())?.let(::updateMap)
                } else {
                    val id = lastMapId ?: inventoryMapId()
                    if (id != null) {
                        val level = Minecraft.getInstance().level
                        val mapState = level?.getMapData(id)
                        val keyed = (mapState as? IMapState)?.`asthoonlite$getDecorations`()
                        if (keyed != null) {
                            updatePlayerIcons(keyed)
                        }
                    }
                }
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }
}
