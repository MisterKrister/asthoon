package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.api.*
import com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes
import com.asthoonlite.dungeon.api.mapEnums.DoorTypes
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.overlay.MapOverlayWindow
import com.asthoonlite.render.HudMapCanvas
import com.asthoonlite.render.MapCanvas
import com.asthoonlite.render.RecordMapCanvas
import com.asthoonlite.render.mapBaseSpans
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import kotlin.math.cos
import kotlin.math.sin

object DungeonMap : HudElement {
    private const val BASE_SIZE = 100f
    private const val GRID_SIZE = 6
    private val MARKER_ATLAS = Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "textures/map/marker_atlas.png")

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "dungeon_map"),
            this
        )
    }

    fun resetRun() {
        DungeonScanner.reset()
        DungeonMapScanner.reset()
    }

    /**
     * Two destinations, one layout:
     *
     *  - external window on  -> record the frame, hand it to the overlay
     *    window, and draw nothing in game. Quiet mode does not apply here;
     *    the whole point is that the map survives while the game looks stock.
     *  - external window off -> draw straight into the HUD, subject to quiet
     *    mode like everything else in the game window.
     */
    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val external = Config.dungeonMapExternalWindow
        // Toggling the window off must take it down on the next frame, not at
        // the next dungeon entry. No-op (one volatile read) when it is shut.
        if (!external) MapOverlayWindow.hide()
        if (!external && QuietMode.suppressing()) return

        val mc = Minecraft.getInstance()
        val player = mc.player
        val earlyReturnReason = when {
            !Config.dungeonMapEnabled -> "Config.dungeonMapEnabled is false"
            !DungeonContext.inDungeon -> "DungeonContext.inDungeon is false"
            Config.dungeonMapHideInBoss && DungeonContext.inBoss -> "inBoss is true and dungeonMapHideInBoss is true"
            player == null -> "mc.player is null"
            // "Always Show" off = the map only exists while the player is
            // actually holding a map item, same as the real thing.
            !Config.dungeonMapAlwaysShow && !isHoldingMap(player) -> "not holding a map item and dungeonMapAlwaysShow is false"
            else -> null
        }

        if (earlyReturnReason != null || player == null) {
            // Nothing to show: do not leave a stale frame up in the window.
            MapOverlayWindow.hide()
            return
        }

        val scale = Config.dungeonMapScale.coerceIn(1f, 6f)
        val mapW = (BASE_SIZE * scale).toInt()
        val mapH = (BASE_SIZE * scale).toInt()
        // The external window owns its own inset; the in-game map uses the
        // configured HUD position.
        val startX = if (external) MapOverlayWindow.PAD else Config.dungeonMapX
        val startY = if (external) MapOverlayWindow.PAD else Config.dungeonMapY

        if (external) {
            val record = RecordMapCanvas()
            drawMap(record, mc, player, scale, mapW, mapH, startX, startY)
            MapOverlayWindow.publish(record.ops, mapW, mapH)
            return
        }

        drawMap(HudMapCanvas(context), mc, player, scale, mapW, mapH, startX, startY)
    }

    /** Every drawing call below goes through [canvas]; nothing knows the target. */
    private fun drawMap(
        canvas: MapCanvas,
        mc: Minecraft,
        player: net.minecraft.client.player.LocalPlayer,
        scale: Float,
        mapW: Int,
        mapH: Int,
        startX: Int,
        startY: Int
    ) {
        canvas.push()
        canvas.translate(startX.toFloat(), startY.toFloat())

        // Modern background panel with clean border
        canvas.fill(-2, -2, mapW + 2, mapH + 2, 0x99000000.toInt())
        canvas.fill(0, 0, mapW, mapH, 0xDD0D1F35.toInt())

        val cellGap = 3f * (scale / 2f).coerceAtLeast(1f)
        val cellW = (mapW - cellGap * (GRID_SIZE + 1)) / GRID_SIZE
        val cellH = (mapH - cellGap * (GRID_SIZE + 1)) / GRID_SIZE

        fun cellX(gx: Float): Float = cellGap + gx * (cellW + cellGap)
        fun cellY(gz: Float): Float = cellGap + gz * (cellH + cellGap)
        fun cellX(gx: Int): Float = cellX(gx.toFloat())
        fun cellY(gz: Int): Float = cellY(gz.toFloat())

        fun toScreenX(gx: Float): Float = cellX(gx) + cellW * 0.5f
        fun toScreenY(gz: Float): Float = cellY(gz) + cellH * 0.5f

        // Every map toggle below used to be declared in the GUI and read by
        // nobody — the renderer just drew everything unconditionally. These
        // are the flags that actually gate it now, declared up front because
        // the room pass, the door pass and the label pass all read them.
        //
        // Legit base = show only what the held map item itself can show:
        // rooms you have walked into, and the checkmark Hypixel prints when a
        // room is cleared. No unopened-grid preview, no names, no counters.
        val legit = Config.dungeonMapLegitBase
        val fullGrid = Config.dungeonMapFullGrid && !legit
        val showRoomNames = Config.dungeonMapShowNames && !legit
        val showSecrets = Config.dungeonMapShowSecrets && !legit
        val showCheckmarks = Config.dungeonMapShowCheckmarks

        // Legit base: the panel becomes the map item's own image. Hypixel bakes
        // rooms, doors and checkmarks straight into the colour array, so the
        // redrawn grid, door pass and label pass below would be painting the
        // same information a second time, less accurately. Only the player
        // markers are drawn on top of it.
        //
        // Source rect and destination rect are the exact endpoints the marker
        // transform rescales between (see MapBase.mapBaseSpans), so a head sits
        // on the same pixel of the image as the decoration it came from.
        val mapGap = DungeonMapScanner.roomGap
        val baseColors = DungeonMapScanner.mapColors
        val baseSpans =
            if (legit && baseColors != null && mapGap > 0) {
                mapBaseSpans(
                    baseColors,
                    DungeonMapScanner.mapOffsetX,
                    DungeonMapScanner.mapOffsetZ,
                    mapGap * GRID_SIZE,
                    cellGap.toInt(),
                    cellGap.toInt(),
                    mapW - cellGap.toInt()
                )
            } else {
                emptyList()
            }
        for (span in baseSpans) {
            canvas.fill(span.x0, span.y0, span.x1, span.y1, span.argb)
        }
        // Only suppress the overlay when the image actually decoded; a legit
        // toggle with no map packet yet still falls back to the drawn grid.
        val overlaySuppressed = legit && baseSpans.isNotEmpty()

        if (!overlaySuppressed) {

            // 1. Draw Rooms
            val floor = DungeonContext.floor
            val maxW = if (floor != FloorType.None) floor.roomsW else GRID_SIZE
            val maxH = if (floor != FloorType.None) floor.roomsH else GRID_SIZE

            for (gz in 0 until GRID_SIZE) {
                for (gx in 0 until GRID_SIZE) {
                    if (gx >= maxW || gz >= maxH) continue

                    val idx = gz * 6 + gx
                    val room = DungeonScanner.rooms.getOrNull(idx)

                    val x0 = cellX(gx)
                    val y0 = cellY(gz)

                    if (room == null) {
                        continue
                    }

                    // Skip phantom/bedrock rooms detected outside the active dungeon
                    if (room.type == RoomTypes.UNKNOWN && !room.explored && room.doors.isEmpty() && room.name == null) {
                        continue
                    }

                    if (!room.explored && !fullGrid) {
                        continue
                    }

                    val color = if (room.explored) {
                        colorForRoom(room.type)
                    } else {
                        if (room.type != RoomTypes.UNKNOWN) dim(colorForRoom(room.type), 0.65f)
                        else 0xDD414141.toInt()
                    }

                    canvas.fill(x0.toInt(), y0.toInt(), (x0 + cellW).toInt(), (y0 + cellH).toInt(), color)

                    // Join components of same room (both explored and unopened when full grid is on)
                    if (gx + 1 < maxW) {
                        val right = DungeonScanner.rooms.getOrNull(gz * 6 + gx + 1)
                        if (right === room && (room.explored || fullGrid)) {
                            val jx0 = x0 + cellW
                            val jy0 = y0
                            canvas.fill(jx0.toInt(), jy0.toInt(), (jx0 + cellGap + 1).toInt(), (jy0 + cellH).toInt(), color)
                        }
                    }
                    if (gz + 1 < maxH) {
                        val down = DungeonScanner.rooms.getOrNull((gz + 1) * 6 + gx)
                        if (down === room && (room.explored || fullGrid)) {
                            val jx0 = x0
                            val jy0 = y0 + cellH
                            canvas.fill(jx0.toInt(), jy0.toInt(), (jx0 + cellW).toInt(), (jy0 + cellGap + 1).toInt(), color)
                        }
                    }
                }
            }

            // 2. Draw Doors
            for (door in DungeonScanner.doors) {
                if (door == null) continue
                val r1 = door.roomComp1
                val r2 = door.roomComp2
                if (floor != FloorType.None) {
                    if (r1.x >= maxW || r1.z >= maxH || r2.x >= maxW || r2.z >= maxH) continue
                }
                val isHorizontal = r1.z == r2.z

                val r1Room = DungeonScanner.rooms.getOrNull(r1.z * 6 + r1.x)
                val r2Room = DungeonScanner.rooms.getOrNull(r2.z * 6 + r2.x)

                // Never draw doors between components of the same room (e.g. 2x2, 1x2, L-room)
                if (r1Room != null && r2Room != null && r1Room === r2Room) continue

                // When full grid is OFF: show door if AT LEAST ONE connected room is explored
                // so you can see where to go from the doors in the room you're currently in
                if (!fullGrid && (r1Room?.explored != true && r2Room?.explored != true)) continue

                // Only draw confirmed doors (opened normal doors, wither doors, blood doors, entrance doors)
                // Do NOT draw fake unconfirmed doors on solid walls
                val color = when (door.type) {
                    DoorTypes.WITHER -> 0xFF000000.toInt()
                    DoorTypes.BLOOD -> 0xFFFF2222.toInt()
                    DoorTypes.ENTRANCE -> 0xFF148500.toInt()
                    DoorTypes.NORMAL -> if (door.opened) 0xFF5C340E.toInt() else continue
                }

                if (isHorizontal) {
                    val minX = minOf(r1.x, r2.x)
                    val gz = r1.z
                    val dx0 = cellX(minX) + cellW
                    val dy0 = cellY(gz) + cellH * 0.35f
                    val dx1 = dx0 + cellGap
                    val dy1 = dy0 + cellH * 0.3f
                    canvas.fill(dx0.toInt(), dy0.toInt(), dx1.toInt(), dy1.toInt(), color)
                } else {
                    val minZ = minOf(r1.z, r2.z)
                    val gx = r1.x
                    val dx0 = cellX(gx) + cellW * 0.35f
                    val dy0 = cellY(minZ) + cellH
                    val dx1 = dx0 + cellW * 0.3f
                    val dy1 = dy0 + cellGap
                    canvas.fill(dx0.toInt(), dy0.toInt(), dx1.toInt(), dy1.toInt(), color)
                }
            }

            // 3. Draw Room Text / Checkmarks / Secrets
            val visitedRooms = HashSet<DungeonRoom>()
            for (room in DungeonScanner.rooms) {
                if (room == null || !visitedRooms.add(room)) continue
                if (!room.explored && !fullGrid) continue
                if (room.comps.isEmpty()) continue

                val avgGx = room.comps.map { it.cx / 2f }.average().toFloat()
                val avgGz = room.comps.map { it.cz / 2f }.average().toFloat()
                val cx = cellX(avgGx) + cellW * 0.5f
                val cy = cellY(avgGz) + cellH * 0.5f

                val textScale = (cellW / 36f).coerceIn(0.55f, 1.0f)
                val fontH = mc.font.lineHeight * textScale

                // A checkmark that is switched off falls through to the name
                // branch below instead of leaving the cell empty.
                val hasCheck = room.checkmark == CheckmarkTypes.GREEN ||
                    room.checkmark == CheckmarkTypes.WHITE ||
                    room.checkmark == CheckmarkTypes.FAILED
                val fairyCheckHidden = Config.dungeonMapDontRenderFairyCheckmark &&
                    room.type == RoomTypes.FAIRY
                val activeCheck =
                    if (hasCheck && showCheckmarks && !fairyCheckHidden) room.checkmark else null

                when (activeCheck) {
                    CheckmarkTypes.GREEN -> {
                        // Done: green check ✔
                        canvas.push()
                        canvas.translate(cx, cy - fontH * 0.5f)
                        canvas.scale(textScale * 1.25f, textScale * 1.25f)
                        canvas.text("✔", 0, 0, 0xFF55FF55.toInt(), centered = true)
                        canvas.pop()
                    }
                    CheckmarkTypes.WHITE -> {
                        // Cleared: secret count in white (or white checkmark if 0 secrets)
                        val secStr = when {
                            !showSecrets -> "✔"
                            room.totalSecrets > 0 -> {
                                val completed = if (room.secretsCompleted >= 0) room.secretsCompleted else 0
                                "$completed/${room.totalSecrets}"
                            }
                            else -> "✔"
                        }
                        canvas.push()
                        canvas.translate(cx, cy - fontH * 0.5f)
                        canvas.scale(textScale, textScale)
                        canvas.text(secStr, 0, 0, 0xFFFFFFFF.toInt(), centered = true)
                        canvas.pop()
                    }
                    CheckmarkTypes.FAILED -> {
                        // Failed: red cross ✖
                        canvas.push()
                        canvas.translate(cx, cy - fontH * 0.5f)
                        canvas.scale(textScale * 1.25f, textScale * 1.25f)
                        canvas.text("✖", 0, 0, 0xFFFF5555.toInt(), centered = true)
                        canvas.pop()
                    }
                    else -> {
                        // Uncleared: display proper room name in white
                        val rawName = room.name
                        val hideName = !showRoomNames ||
                            room.type == RoomTypes.ENTRANCE ||
                            (Config.dungeonMapDontRenderCommonNames && room.type == RoomTypes.NORMAL) ||
                            (Config.dungeonMapDontRenderYellowName && room.type == RoomTypes.YELLOW)
                        if (rawName != null && !hideName) {
                            val words = rawName.replace("\u200B", "- ").split(" ").filter { it.isNotBlank() }
                            val nameStartY = cy - (words.size * (fontH + 0.5f)) / 2f
                            words.forEachIndexed { lineIdx, word ->
                                val wy = nameStartY + lineIdx * (fontH + 0.5f)
                                canvas.push()
                                canvas.translate(cx, wy)
                                canvas.scale(textScale, textScale)
                                canvas.text(word, 0, 0, 0xFFFFFFFF.toInt(), centered = true)
                                canvas.pop()
                            }
                        }
                    }
                }
            }

        }
        // 4. Draw Teammate & Self Player Icons
        val selfGx = ((player.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize).toFloat().coerceIn(0f, 5f)
        val selfGz = ((player.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize).toFloat().coerceIn(0f, 5f)
        val selfPx = toScreenX(selfGx)
        val selfPz = toScreenY(selfGz)

        val showNames = Config.dungeonMapPlayerNames && (!Config.dungeonMapNamesOnlyLeap || isHoldingLeap(player))

        // Self icon
        val selfColor = DungeonContext.classColor(player.gameProfile.name)
        if (Config.dungeonMapMarkerSelf || !Config.dungeonMapPlayerHeads) {
            drawPlayerArrow(canvas, selfPx, selfPz, player.yRot.toDouble(), scale, selfColor, isSelf = true)
        } else {
            drawPlayerHead(canvas, player.skin, selfPx, selfPz, player.yRot.toDouble(), scale, selfColor, player.gameProfile.name)
        }

        val renderedNames = HashSet<String>()
        renderedNames.add(player.gameProfile.name.lowercase())

        // 1. Live world teammates (render distance)
        val worldPlayers = mc.level?.players() ?: emptyList()
        val teammates = DungeonContext.getTeammateNames()
        for (mate in worldPlayers) {
            val mateName = mate.gameProfile.name
            if (mateName.equals(player.gameProfile.name, ignoreCase = true) || mate.isSpectator) continue
            if (mate.uuid.version() == 2 || StarMobESP.categorizePlayer(mateName) != null) continue
            if (teammates.isNotEmpty() && !teammates.any { it.equals(mateName, ignoreCase = true) }) continue
            renderedNames.add(mateName.lowercase())

            val gx = ((mate.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize).toFloat()
            val gz = ((mate.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize).toFloat()
            if (gx < -0.5f || gx > 5.5f || gz < -0.5f || gz > 5.5f) continue
            val tx = toScreenX(gx)
            val tz = toScreenY(gz)
            val yawDeg = mate.yRot.toDouble()
            val skin = mate.skin
            val mateColor = DungeonContext.classColor(mateName)

            if (Config.dungeonMapPlayerHeads) {
                drawPlayerHead(canvas, skin, tx, tz, yawDeg, scale, mateColor, mateName)
            } else {
                drawPlayerArrow(canvas, tx, tz, yawDeg, scale * 0.8f, mateColor, isSelf = false)
            }

            if (showNames) {
                val shortName = mateName.take(4)
                canvas.text(shortName, tx.toInt(), (tz + 7).toInt(), 0xFFFFFFFF.toInt(), centered = true)
            }
        }

        // 2. Distant teammates from map packet
        for (icon in DungeonMapScanner.playerIcons) {
            val iconName = icon.name
            if (iconName != null && renderedNames.contains(iconName.lowercase())) continue

            val iconGx = icon.x.toFloat()
            val iconGz = icon.z.toFloat()
            if (kotlin.math.hypot(iconGx - selfGx, iconGz - selfGz) < 0.4f) continue

            val tx = toScreenX(iconGx)
            val tz = toScreenY(iconGz)
            val yawDeg = Math.toDegrees(icon.rot)
            val skin = getPlayerSkin(iconName)
            val mateColor = DungeonContext.classColor(iconName)

            if (Config.dungeonMapPlayerHeads && skin != null) {
                drawPlayerHead(canvas, skin, tx, tz, yawDeg, scale, mateColor, iconName ?: "?")
            } else {
                drawPlayerArrow(canvas, tx, tz, yawDeg, scale * 0.8f, mateColor, isSelf = false)
            }

            if (showNames && iconName != null) {
                val shortName = iconName.take(4)
                canvas.text(shortName, tx.toInt(), (tz + 7).toInt(), 0xFFFFFFFF.toInt(), centered = true)
            }
        }

        canvas.pop()
    }

    private fun getPlayerSkin(name: String?): net.minecraft.world.entity.player.PlayerSkin? {
        if (name.isNullOrBlank()) return null
        val mc = Minecraft.getInstance()
        val info = mc.connection?.onlinePlayers?.firstOrNull { it.profile.name.equals(name, true) }
        return info?.skin
    }

    private fun isHoldingLeap(player: net.minecraft.world.entity.player.Player): Boolean {
        val mainName = player.mainHandItem.hoverName.string
        val offName = player.offhandItem.hoverName.string
        return mainName.contains("Spirit Leap", true) || offName.contains("Spirit Leap", true)
    }

    private fun drawPlayerHead(
        canvas: MapCanvas,
        skin: net.minecraft.world.entity.player.PlayerSkin?,
        x: Float, z: Float,
        yawDeg: Double,
        scale: Float,
        borderColor: Int,
        label: String
    ) {
        val headSize = (9 * (scale / 1.66f) * Config.dungeonMapPlayerHeadScale).toInt().coerceIn(8, 24)
        val half = headSize / 2
        val hx = (x - half).toInt()
        val hz = (z - half).toInt()

        // Border + face: one call, so both backends draw the same frame.
        canvas.face(label, skin, hx, hz, headSize, borderColor)

        val yaw = Math.toRadians(yawDeg)
        val dx = (-sin(yaw) * (half + 2)).toInt()
        val dz = (cos(yaw) * (half + 2)).toInt()
        canvas.fill((x + dx - 1).toInt(), (z + dz - 1).toInt(), (x + dx + 1).toInt(), (z + dz + 1).toInt(), 0xFFFFFFFF.toInt())
    }

    private fun abbreviateName(name: String): String = when {
        name.length <= 6 -> name
        name.equals("Three Weirdos", true) -> "Weirdos"
        name.equals("Higher Lower", true) || name.equals("Higher Blaze", true) -> "Blaze"
        name.equals("Water Board", true) -> "Water"
        name.equals("Ice Path", true) || name.equals("Ice Fill", true) -> "Ice"
        name.equals("Creeper Beams", true) -> "Beams"
        name.equals("Teleport Maze", true) -> "Maze"
        else -> name.take(5)
    }

    private fun drawPlayerArrow(
        canvas: MapCanvas,
        x: Float, z: Float,
        yawDeg: Double,
        scale: Float,
        color: Int,
        isSelf: Boolean
    ) {
        val markerScale = scale * Config.dungeonMapMarkerScale * (if (isSelf) 0.30f else 0.40f)
        val w = (8 * markerScale).toInt().coerceIn(5, 10)
        val h = (12 * markerScale).toInt().coerceIn(7, 14)

        canvas.push()
        canvas.translate(x, z)
        canvas.rotate(Math.toRadians(yawDeg + 180.0).toFloat())

        val tint = if (isSelf) 0xFFFFFFFF.toInt() else color
        // The atlas sprite, or whatever the backend substitutes for it.
        canvas.marker(isSelf, w, h, markerScale, tint)

        canvas.pop()
    }

    private fun colorForRoom(type: RoomTypes): Int = when (type) {
        RoomTypes.ENTRANCE -> 0xFF148500.toInt()
        RoomTypes.NORMAL   -> 0xFF6B3A11.toInt()
        RoomTypes.FAIRY    -> 0xFFE000FF.toInt()
        RoomTypes.BLOOD    -> 0xFFFF2222.toInt()
        RoomTypes.PUZZLE   -> 0xFF750085.toInt()
        RoomTypes.TRAP     -> 0xFFD87F33.toInt()
        RoomTypes.YELLOW   -> 0xFFFEDF00.toInt()
        RoomTypes.RARE     -> 0xFFECEFF1.toInt()
        RoomTypes.UNKNOWN  -> 0xFF414141.toInt()
    }

    private fun dim(argb: Int, factor: Float): Int {
        val r = (((argb ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xEE shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun isHoldingMap(player: net.minecraft.world.entity.player.Player): Boolean {
        val main = player.mainHandItem
        if (main.get(DataComponents.MAP_ID) != null) return true
        val off = player.offhandItem
        return off.get(DataComponents.MAP_ID) != null
    }
}
