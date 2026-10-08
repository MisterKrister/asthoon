package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.api.DungeonDoor
import com.asthoonlite.dungeon.api.DungeonRoom
import com.asthoonlite.dungeon.api.FloorType
import com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes
import com.asthoonlite.dungeon.api.mapEnums.DoorTypes
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.render.MapCanvas
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Geometry adapted from Devonian DungeonMapBaseRenderer (GPL-3.0) and
 * Noamm MapRenderer/UniqueRoom (CC0). Source revisions and licenses: PORTING_NOTES.md. */
internal object DungeonMapStyles {
    fun style(value: Int): Int = if (value == 1) 1 else 0

    data class Layout(val x: Float, val y: Float, val step: Float, val room: Float, val door: Float) {
        fun cellX(column: Float) = x + column * step
        fun cellY(row: Float) = y + row * step
        fun centerX(column: Float) = cellX(column) + room / 2
        fun centerY(row: Float) = cellY(row) + room / 2
    }

    fun layout(style: Int, size: Int, floor: FloorType, mapRoomSize: Int = 16,
               mapOffsetX: Int = 5, mapOffsetZ: Int = 5): Layout {
        val f = floor.takeUnless { it == FloorType.None } ?: FloorType.M7
        if (style(style) == 1) {
            // Noamm MapUtils calibration: room size 16/18, four-pixel connectors, 128-pixel canvas.
            val room = if (mapRoomSize == 18) 18 else 16
            val (x, y) = when (f.floorNum) {
                0 -> 22 to 22
                1 -> 22 to 11
                2, 3 -> 11 to 11
                else -> mapOffsetX.coerceAtLeast(0) to mapOffsetZ.coerceAtLeast(0)
            }
            val scale = size / 128f
            return Layout(x * scale, y * scale, (room + 4) * scale, room * scale, 6 * scale)
        }
        // Devonian's component-to-image transform, room width .8, door width .36, padding .12.
        val step = size / (f.maxDim + 0.24f)
        return Layout(((f.maxDim - f.roomsW) / 2f + 0.22f) * step,
            ((f.maxDim - f.roomsH) / 2f + 0.22f) * step, step, step * 0.8f, step * 0.36f)
    }

    data class Labels(val names: Boolean, val secrets: Boolean, val checks: Boolean,
                      val hideCommon: Boolean, val hideYellow: Boolean, val hideFairyCheck: Boolean)

    fun draw(canvas: MapCanvas, style: Int, layout: Layout, rooms: List<DungeonRoom?>,
             doors: List<DungeonDoor?>, advanced: Boolean, labels: Labels) {
        val selected = style(style)
        val unique = rooms.filterNotNull().distinct()
        if (selected == 0) drawDevonian(canvas, layout, unique, doors, advanced)
        else drawNoamm(canvas, layout, rooms, doors, advanced)
        for (room in unique) {
            if (!visible(room, advanced)) continue
            drawRoomInfo(canvas, selected, layout, room, labels, advanced)
        }
    }

    private fun visible(room: DungeonRoom, advanced: Boolean) =
        (advanced || room.explored) && (room.name != null || room.doors.isNotEmpty() || room.explored)

    private fun rect(c: MapCanvas, x: Float, y: Float, w: Float, h: Float, color: Int, overlap: Boolean = false) {
        c.fill(x.toInt(), y.toInt(), x.toInt() + ceil(w).toInt() + if (overlap) 1 else 0,
            y.toInt() + ceil(h).toInt() + if (overlap) 1 else 0, color)
    }

    private fun drawDevonian(c: MapCanvas, l: Layout, rooms: List<DungeonRoom>,
                             doors: List<DungeonDoor?>, advanced: Boolean) {
        // Devonian draws doors before room bodies so adjoining corners cannot leave seams.
        for (door in doors.filterNotNull()) {
            if (!advanced && door.rooms.none { it.explored }) continue
            if (door.type == DoorTypes.NORMAL && !door.opened) continue
            val a = door.roomComp1
            val b = door.roomComp2
            connector(c, l, a.x, a.z, a.x == b.x, true, doorColor(door, 0), overlap = true)
        }
        for (room in rooms) {
            if (!visible(room, advanced)) continue
            val cells = room.comps.map { it.cx / 2 to it.cz / 2 }
            if (cells.isEmpty()) continue
            val color = roomColor(0, room.type, room.explored)
            val minX = cells.minOf { it.first }
            val minZ = cells.minOf { it.second }
            val width = cells.maxOf { it.first } - minX + 1
            val height = cells.maxOf { it.second } - minZ + 1
            if (cells.size == width * height) {
                // 1xN and 2x2 shapes are single vector rectangles in Devonian.
                rect(c, l.cellX(minX.toFloat()), l.cellY(minZ.toFloat()),
                    l.room + (width - 1) * l.step, l.room + (height - 1) * l.step, color, true)
            } else {
                // L shapes: component rectangles plus full-width joins, without filling the missing corner.
                for ((x, z) in cells) {
                    rect(c, l.cellX(x.toFloat()), l.cellY(z.toFloat()), l.room, l.room, color, true)
                    if ((x + 1 to z) in cells) connector(c, l, x, z, false, false, color, true)
                    if ((x to z + 1) in cells) connector(c, l, x, z, true, false, color, true)
                }
            }
        }
    }

    private fun drawNoamm(c: MapCanvas, l: Layout, rooms: List<DungeonRoom?>,
                          doors: List<DungeonDoor?>, advanced: Boolean) {
        // Noamm's 11x11 room/connector/intersection pass over the existing room graph.
        fun roomAt(x: Int, z: Int) = rooms.getOrNull(z * 6 + x).takeIf { x in 0..5 && z in 0..5 }
        for (y in 0..10) for (x in 0..10) {
            val room = roomAt(x shr 1, y shr 1) ?: continue
            if (!visible(room, advanced)) continue
            val color = roomColor(1, room.type, room.explored)
            val cx = x shr 1
            val cz = y shr 1
            when {
                x and 1 == 0 && y and 1 == 0 ->
                    rect(c, l.cellX(cx.toFloat()), l.cellY(cz.toFloat()), l.room, l.room, color)
                x and 1 == 1 && y and 1 == 1 -> {
                    if (roomAt(cx + 1, cz) === room && roomAt(cx, cz + 1) === room && roomAt(cx + 1, cz + 1) === room)
                        rect(c, l.cellX(cx.toFloat()), l.cellY(cz.toFloat()), l.step, l.step, color)
                }
                else -> {
                    val vertical = y and 1 == 1
                    if (roomAt(cx + if (vertical) 0 else 1, cz + if (vertical) 1 else 0) === room)
                        connector(c, l, cx, cz, vertical, false, color)
                }
            }
        }
        for (door in doors.filterNotNull()) {
            if (!advanced && door.rooms.none { it.explored }) continue
            if (door.type == DoorTypes.NORMAL && !door.opened) continue
            val a = door.roomComp1
            connector(c, l, a.x, a.z, a.x == door.roomComp2.x, true, doorColor(door, 1))
        }
    }

    private fun connector(c: MapCanvas, l: Layout, x: Int, z: Int, vertical: Boolean,
                          door: Boolean, color: Int, overlap: Boolean = false) {
        // Noamm drawRoomConnector / Devonian drawDoor and drawRoomJoined share this rectangle.
        val across = if (door) l.door else l.room
        val inset = (l.room - across) / 2
        val gap = l.step - l.room
        val px = l.cellX(x.toFloat()) + if (vertical) inset else l.room
        val py = l.cellY(z.toFloat()) + if (vertical) l.room else inset
        rect(c, px, py, if (vertical) across else gap, if (vertical) gap else across, color, overlap)
    }

    // Devonian Center alignment and Noamm UniqueRoom center prefer the occupied elbow of an L.
    fun roomCenter(cells: List<Pair<Int, Int>>): Pair<Float, Float> {
        if (cells.size == 3) {
            val elbow = cells.firstOrNull { a -> cells.count { b -> abs(a.first - b.first) + abs(a.second - b.second) == 1 } == 2 }
            if (elbow != null) return elbow.first.toFloat() to elbow.second.toFloat()
        }
        return cells.map { it.first }.average().toFloat() to cells.map { it.second }.average().toFloat()
    }

    private fun drawRoomInfo(c: MapCanvas, style: Int, l: Layout, room: DungeonRoom, labels: Labels, advanced: Boolean) {
        val cells = room.comps.map { it.cx / 2 to it.cz / 2 }
        if (cells.isEmpty()) return
        val center = roomCenter(cells)
        val cx = l.centerX(center.first)
        val cy = l.centerY(center.second)
        val textColor = when (room.checkmark) {
            CheckmarkTypes.GREEN -> 0xFF55FF55.toInt()
            CheckmarkTypes.FAILED -> 0xFFFF5555.toInt()
            CheckmarkTypes.WHITE -> -1
            else -> 0xFFAAAAAA.toInt()
        }
        val lines = ArrayList<String>()
        val showName = labels.names && !(labels.checks && room.checkmark == CheckmarkTypes.GREEN) &&
            room.type != RoomTypes.ENTRANCE && room.type != RoomTypes.FAIRY &&
            !(labels.hideCommon && room.type == RoomTypes.NORMAL) && !(labels.hideYellow && room.type == RoomTypes.YELLOW)
        if (showName) room.name?.replace("\u200B", "- ")?.split(" ")?.filter { it.isNotBlank() }?.let(lines::addAll)
        if (labels.secrets && room.totalSecrets > 0 && !(labels.checks && room.checkmark == CheckmarkTypes.GREEN)) {
            val found = if (room.checkmark == CheckmarkTypes.GREEN) max(room.totalSecrets, room.secretsCompleted).toString()
                else room.secretsCompleted.takeIf { it >= 0 }?.toString() ?: "?"
            lines.add("$found/${room.totalSecrets}")
        }
        if (lines.isNotEmpty()) {
            val maxWidth = lines.maxOf { it.length } * 6f
            val textScale = minOf(l.room * 0.8f / maxWidth, l.room * 0.9f / (lines.size * 9f), l.room / 20f)
            c.push()
            c.translate(cx, cy - lines.size * 9 * textScale / 2)
            c.scale(textScale, textScale)
            lines.forEachIndexed { index, line -> c.text(line, 0, index * 9, textColor, true) }
            c.pop()
        } else if (labels.checks && !(labels.hideFairyCheck && room.type == RoomTypes.FAIRY)) {
            if (advanced && !room.explored && room.name != null) return
            val art = when (room.checkmark) {
                CheckmarkTypes.WHITE -> if (style == 1) "white_check" else "whiteCheck"
                CheckmarkTypes.GREEN -> if (style == 1) "green_check" else "greenCheck"
                CheckmarkTypes.FAILED -> if (style == 1) "cross" else "failedRoom"
                CheckmarkTypes.UNEXPLORED -> if (style == 1) "question" else "questionMark"
                else -> return
            }
            val size = (l.room * 0.55f).toInt().coerceAtLeast(1)
            val textureSize = if (style == 1) 100 else if (art == "questionMark") 62 else 10
            val directory = if (style == 1) "noamm" else "devonian"
            c.image("textures/map/$directory/$art.png", (cx - size / 2).toInt(), (cy - size / 2).toInt(), size, textureSize)
        }
    }

    private fun doorColor(door: DungeonDoor, style: Int): Int = when (door.type) {
        DoorTypes.ENTRANCE -> roomColor(style, RoomTypes.ENTRANCE, true)
        DoorTypes.BLOOD -> roomColor(style, RoomTypes.BLOOD, true)
        DoorTypes.WITHER -> if (style == 1 && door.opened) roomColor(style, RoomTypes.NORMAL, true) else 0xFF101010.toInt()
        DoorTypes.NORMAL -> {
            val room = if (style == 1) {
                val colored = door.rooms.filter { it.type != RoomTypes.NORMAL }
                if (colored.size == 2) colored.firstOrNull { it.type != RoomTypes.FAIRY } else colored.firstOrNull()
            } else door.rooms.minByOrNull { it.type.prio }
            roomColor(style, room?.type ?: RoomTypes.NORMAL, true)
        }
    }

    fun roomColor(style: Int, type: RoomTypes, explored: Boolean): Int {
        val color = if (style(style) == 1) when (type) {
            RoomTypes.ENTRANCE -> 0x00FF00
            RoomTypes.NORMAL -> 0x794600
            RoomTypes.FAIRY -> 0xE39BE2
            RoomTypes.BLOOD -> 0xB20000
            RoomTypes.PUZZLE -> 0x7B007B
            RoomTypes.TRAP -> 0xFF8200
            RoomTypes.YELLOW -> 0xFFC800
            RoomTypes.RARE -> 0xB2B2B2
            RoomTypes.UNKNOWN -> 0x414141
        } else when (type) {
            RoomTypes.ENTRANCE -> 0x148500
            RoomTypes.NORMAL -> 0x6B3A11
            RoomTypes.FAIRY -> 0xE000FF
            RoomTypes.BLOOD -> 0xFF0000
            RoomTypes.PUZZLE -> 0x750085
            RoomTypes.TRAP -> 0xD87F33
            RoomTypes.YELLOW -> 0xFEDF00
            RoomTypes.RARE -> 0xECEFF1
            RoomTypes.UNKNOWN -> 0x414141
        }
        if (explored) return 0xFF000000.toInt() or color
        val factor = if (style(style) == 1) 0.49 else 0.7
        return 0xFF000000.toInt() or (((color shr 16 and 255) * factor).toInt() shl 16) or
            (((color shr 8 and 255) * factor).toInt() shl 8) or ((color and 255) * factor).toInt()
    }
}
