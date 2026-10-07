package com.asthoonlite.dungeon.map

import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.api.*
import com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes
import com.asthoonlite.dungeon.api.mapEnums.DoorTypes
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState

object DungeonScanner {
    data class RoomData(
        val name: String,
        val type: String,
        val secrets: Int,
        val cores: List<Int>,
        val trappedChests: Int = 0,
        val roomID: Int = 0,
        val clear: String? = null,
        val crypts: Int = 0,
        val clearScore: Int? = null,
        val secretScore: Int? = null,
        val shape: String = "1x1"
    )

    val roomsData: List<RoomData> by lazy {
        runCatching {
            DungeonScanner::class.java.getResourceAsStream("/assets/asthoonlite/dungeons/rooms.json")
                ?.bufferedReader()
                .use { it?.readText() }?.let { json ->
                    Gson().fromJson(json, Array<RoomData>::class.java).toList()
                }
        }.getOrNull() ?: emptyList()
    }

    val roomsByCore: Map<Int, RoomData> by lazy {
        buildMap {
            for (room in roomsData) {
                for (core in room.cores) {
                    put(core, room)
                }
            }
        }
    }

    var lastIdx: Int? = null
    var currentRoom: DungeonRoom? = null
    var rooms = MutableList<DungeonRoom?>(36) { null }
    var doors = MutableList<DungeonDoor?>(60) { null }
    var availablePos = findAvailablePos()
    private var worldChangeCooldown = 20
    private var foundEntrance = 20
    private var wasInEntrance = false

    private val secretRegex = "\\b(\\d+)/(\\d+) Secrets".toRegex()

    private fun findAvailablePos(): MutableList<WorldComponentPosition> {
        val pos = mutableListOf<WorldComponentPosition>()
        for (z in 0..10) {
            for (x in 0..10) {
                if (x % 2 != 0 && z % 2 != 0) continue
                pos.add(ComponentPosition(x, z).withWorld())
            }
        }
        return pos
    }

    fun getHighestY(x: Int, z: Int): Int {
        val level = Minecraft.getInstance().level ?: return -1
        var height = 0
        val mutable = BlockPos.MutableBlockPos(x, 0, z)
        for (idx in minOf(256, level.maxY) downTo maxOf(0, level.minY)) {
            mutable.set(x, idx, z)
            if (!level.isLoaded(mutable)) return -1
            val blockState = level.getBlockState(mutable)
            val block = blockState.block
            if (blockState.isAir || block == Blocks.GOLD_BLOCK) continue
            height = idx
            break
        }
        return height
    }

    private fun getLegacyId(blockState: BlockState): Int? {
        val block = blockState.block
        val registry = BuiltInRegistries.BLOCK.getKey(block)
        var registryName = "${registry.namespace}:${registry.path}"
        val fluidState = blockState.fluidState
        if (!fluidState.isEmpty) {
            if (fluidState.`is`(FluidTags.WATER))
                return if (fluidState.isSource) 9 else 8
            if (fluidState.`is`(FluidTags.LAVA))
                return if (fluidState.isSource) 11 else 10
        }
        if (block is SlabBlock) {
            registryName += "[type=${blockState.getValue(SlabBlock.TYPE).name.lowercase()}]"
        }
        return LegacyRegistry.BLOCKS[registryName]
    }

    fun hashCeil(x: Int, z: Int): Int {
        val level = Minecraft.getInstance().level ?: return 0
        val sb = StringBuilder(128)
        val mutable = BlockPos.MutableBlockPos(x, 0, z)
        for (idx in 140 downTo 12) {
            mutable.set(x, idx, z)
            if (!level.isLoaded(mutable)) continue
            val blockState = level.getBlockState(mutable)
            val block = blockState.block
            val blockId = getLegacyId(blockState) ?: continue
            if (block == Blocks.IRON_BARS || block == Blocks.CHEST) {
                sb.append('0')
                continue
            }
            sb.append(blockId)
        }
        return sb.toString().hashCode()
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (overlay && DungeonContext.inDungeon) {
                val message = ChatFormatting.stripFormatting(text.string) ?: ""
                val match = secretRegex.find(message)
                if (match != null) {
                    val found = match.groupValues.getOrNull(1)?.toIntOrNull()
                    val total = match.groupValues.getOrNull(2)?.toIntOrNull()
                    val room = currentRoom
                    if (found != null && total != null && room != null) {
                        if (found <= room.totalSecrets && total == room.totalSecrets) {
                            room.secretsCompleted = found
                        }
                    }
                }
            }
            true
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun checkDoorState() {
        for (door in doors) {
            if (door == null || door.opened) continue
            door.check()
        }
    }

    private fun checkRoomState() {
        for (room in rooms) {
            if (room == null) continue
            if (room.name == null) room.scan()
            if (room.rotation == -1) room.findRotation()
        }
    }

    fun tick() {
        if (!DungeonContext.inDungeon) return
        if (--worldChangeCooldown >= 0) return
        val player = Minecraft.getInstance().player ?: return
        val level = Minecraft.getInstance().level ?: return
        if (!level.isLoaded(player.blockPosition())) return

        val comp = WorldPosition(player.x.toInt(), player.z.toInt()).toComponent()
        if (!comp.isInBounds()) return
        val jdx = comp.getRoomIdx()
        if (jdx !in 0..35) return

        scan()

        val newRoom = rooms[jdx]
        if (!wasInEntrance) {
            if (newRoom?.type == RoomTypes.ENTRANCE) wasInEntrance = true
            else if (foundEntrance > 0) return
        }

        checkRoomState()
        checkDoorState()

        currentRoom = rooms[jdx]
        if (currentRoom?.clientExplored == false && currentRoom?.explored == false) {
            currentRoom?.clientExplored = true
            currentRoom?.explored = true
        }
        if (currentRoom?.checkmark == CheckmarkTypes.UNEXPLORED) {
            currentRoom?.checkmark = CheckmarkTypes.NONE
        }

        lastIdx = jdx
    }

    fun mergeRooms(comp1: ComponentPosition, comp2: ComponentPosition): Boolean {
        val i1 = comp1.getRoomIdx()
        val i2 = comp2.getRoomIdx()
        val r1 = rooms[comp1.getRoomIdx()]
        val r2 = rooms[comp2.getRoomIdx()]

        if (r1 != null && r2 != null) {
            if (i1 < i2) mergeRooms(r1, r2)
            else mergeRooms(r2, r1)
            return true
        }
        if (r1 == null && r2 == null) return false
        val r: DungeonRoom
        val c: ComponentPosition
        if (r1 == null) {
            r = r2!!
            c = comp1
        } else {
            r = r1
            c = comp2
        }

        r.addComponent(c)
        addRoom(c, r)
        return true
    }

    fun mergeRooms(room1: DungeonRoom, room2: DungeonRoom) {
        if (room1 === room2) return
        if (room1.type == RoomTypes.ENTRANCE || room2.type == RoomTypes.ENTRANCE) return
        if (room1.type == RoomTypes.FAIRY || room2.type == RoomTypes.FAIRY) return

        for (comp in room2.comps) {
            val c = comp.toComponent()
            room1.addComponent(c, false)
            addRoom(c, room1, true)
        }

        room1.update()
        if (room2.explored) room1.explored = true
        room2.doors.forEach { it.rooms.remove(room2) }
    }

    fun addDoor(door: DungeonDoor) {
        val comp = door.comp.toComponent()
        val idx = comp.getDoorIdx()
        if (idx !in 0..59) return

        doors[idx] = door
        comp.getNeighboringRooms().forEach {
            rooms.getOrNull(it.getRoomIdx())?.also { r ->
                r.doors.add(door)
                door.rooms.add(r)
            }
        }
    }

    fun addRoom(comp: ComponentPosition, room: DungeonRoom, force: Boolean = false) {
        val idx = comp.getRoomIdx()
        if (idx !in 0..35) return
        if (!force) {
            rooms[idx]?.also {
                if (room.name == null) mergeRooms(it, room)
                else mergeRooms(room, it)
                return
            }
        }
        rooms[idx] = room

        comp.getNeighboringDoors().forEach {
            doors.getOrNull(it.getDoorIdx())?.also { d ->
                d.rooms.add(room)
                room.doors.add(d)
            }
        }
    }

    fun reset() {
        worldChangeCooldown = 5
        foundEntrance = 5
        wasInEntrance = false
        rooms.fill(null)
        doors.fill(null)
        lastIdx = null
        currentRoom = null
        availablePos = findAvailablePos().asReversed()
    }

    fun scan(): Boolean {
        foundEntrance--
        if (availablePos.isEmpty()) return false
        val level = Minecraft.getInstance().level ?: return false

        val startLen = availablePos.size
        availablePos.removeIf { pos ->
            val (wx, wz, cx, cz) = pos
            val comp = pos.toComponent()

            val floor = DungeonContext.floor
            if (floor != FloorType.None) {
                if (cx / 2 >= floor.roomsW || cz / 2 >= floor.roomsH) {
                    return@removeIf true
                }
            }

            if (!level.isLoaded(BlockPos(wx, 67, wz))) return@removeIf false

            val roofHeight = getHighestY(wx, wz)
            if (roofHeight < 0) return@removeIf true

            if (comp.isValidDoor()) {
                if (roofHeight != 0 && roofHeight < 85) {
                    val door = DungeonDoor(pos)
                    if (cz % 2 == 1) door.rotation = 0
                    addDoor(door)
                }
                return@removeIf true
            }
            if (roofHeight <= 0) return@removeIf true

            var room = DungeonRoom(mutableListOf(pos), roofHeight).scan()
            addRoom(comp, room)
            if (room.type == RoomTypes.ENTRANCE) {
                room.explored = true
                room.checkmark = CheckmarkTypes.NONE
                foundEntrance = 0
            } else if (foundEntrance > 0) return@removeIf false

            comp.getNeighbors().forEach { (posRoom, posDoor) ->
                val worldPos = posDoor.withWorld()
                val nx0 = worldPos.wx
                val nz0 = worldPos.wz

                val heightBlockPos = BlockPos(nx0, roofHeight, nz0)
                if (!level.isLoaded(heightBlockPos)) return@forEach
                val heightBlock = level.getBlockState(heightBlockPos)
                val aboveHeightBlock = level.getBlockState(BlockPos(nx0, roofHeight + 1, nz0))
                val heightBlockId = BuiltInRegistries.BLOCK.indexOf(heightBlock.block)
                val aboveHeightId = BuiltInRegistries.BLOCK.indexOf(aboveHeightBlock.block)

                if (room.type == RoomTypes.ENTRANCE && heightBlockId != 0) {
                    val block1Pos = BlockPos(nx0, 76, nz0)
                    if (!level.isLoaded(block1Pos)) return@forEach
                    val block1 = level.getBlockState(block1Pos)
                    if (block1.isAir) return@forEach
                    val doorIdx = posDoor.getDoorIdx()
                    if (doorIdx in 0..60) {
                        val door = DungeonDoor(worldPos)
                        door.type = DoorTypes.ENTRANCE
                        addDoor(door)
                    }
                    return@forEach
                }

                if (heightBlockId == 0 || aboveHeightId != 0) return@forEach

                val ndx = posRoom.getRoomIdx()
                if (ndx !in 0..35) return@forEach

                val nroom = rooms[ndx]
                if (nroom == null) {
                    room.addComponent(posRoom)
                    addRoom(posRoom, room)
                    return@forEach
                }

                if (nroom.type == RoomTypes.ENTRANCE || nroom == room) return@forEach

                mergeRooms(nroom, room)
                room = nroom
            }
            return@removeIf true
        }

        return availablePos.size != startLen
    }
}
