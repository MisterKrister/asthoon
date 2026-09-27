package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.api.*
import com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes
import com.asthoonlite.dungeon.api.mapEnums.DoorTypes
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.map.DungeonScanner
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
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

    private var renderTicks = 0
    private var lastRenderStateReason = ""

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val earlyReturnReason = when {
            !Config.dungeonMapEnabled -> "Config.dungeonMapEnabled is false"
            !DungeonContext.inDungeon -> "DungeonContext.inDungeon is false"
            Config.dungeonMapHideInBoss && DungeonContext.inBoss -> "inBoss is true and dungeonMapHideInBoss is true"
            player == null -> "mc.player is null"
            else -> null
        }

        if (earlyReturnReason != null || player == null) {
            if (earlyReturnReason != null && earlyReturnReason != lastRenderStateReason) {
                lastRenderStateReason = earlyReturnReason
                AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonMap not rendering: $earlyReturnReason")
            }
            return
        }

        renderTicks++
        if (renderTicks % 60 == 0 || lastRenderStateReason.isNotEmpty()) {
            lastRenderStateReason = ""
            val nonNullRooms = DungeonScanner.rooms.filterNotNull().size
            val nonNullDoors = DungeonScanner.doors.filterNotNull().size
            AsthoonLite.LOGGER.info("[AsthoonLite-Debug] DungeonMap rendering: rooms=$nonNullRooms, doors=$nonNullDoors, icons=${DungeonMapScanner.playerIcons.size}, scale=${Config.dungeonMapScale}, pos=(${Config.dungeonMapX},${Config.dungeonMapY})")
        }

        val scale = Config.dungeonMapScale.coerceIn(1f, 6f)
        val mapW = (BASE_SIZE * scale).toInt()
        val mapH = (BASE_SIZE * scale).toInt()
        val startX = Config.dungeonMapX
        val startY = Config.dungeonMapY

        context.pose().pushMatrix()
        context.pose().translate(startX.toFloat(), startY.toFloat())

        // Modern background panel with clean border
        context.fill(-2, -2, mapW + 2, mapH + 2, 0x99000000.toInt())
        context.fill(0, 0, mapW, mapH, 0xDD0D1F35.toInt())

        val cellGap = 3f * (scale / 2f).coerceAtLeast(1f)
        val cellW = (mapW - cellGap * (GRID_SIZE + 1)) / GRID_SIZE
        val cellH = (mapH - cellGap * (GRID_SIZE + 1)) / GRID_SIZE

        fun cellX(gx: Float): Float = cellGap + gx * (cellW + cellGap)
        fun cellY(gz: Float): Float = cellGap + gz * (cellH + cellGap)
        fun cellX(gx: Int): Float = cellX(gx.toFloat())
        fun cellY(gz: Int): Float = cellY(gz.toFloat())

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

                if (!room.explored && !Config.dungeonMapFullGrid) {
                    continue
                }

                val color = if (room.explored) {
                    colorForRoom(room.type)
                } else {
                    if (room.type != RoomTypes.UNKNOWN) dim(colorForRoom(room.type), 0.65f)
                    else 0xDD414141.toInt()
                }

                context.fill(x0.toInt(), y0.toInt(), (x0 + cellW).toInt(), (y0 + cellH).toInt(), color)

                // Join components of same room (both explored and unopened when full grid is on)
                if (gx + 1 < maxW) {
                    val right = DungeonScanner.rooms.getOrNull(gz * 6 + gx + 1)
                    if (right === room && (room.explored || Config.dungeonMapFullGrid)) {
                        val jx0 = x0 + cellW
                        val jy0 = y0
                        context.fill(jx0.toInt(), jy0.toInt(), (jx0 + cellGap + 1).toInt(), (jy0 + cellH).toInt(), color)
                    }
                }
                if (gz + 1 < maxH) {
                    val down = DungeonScanner.rooms.getOrNull((gz + 1) * 6 + gx)
                    if (down === room && (room.explored || Config.dungeonMapFullGrid)) {
                        val jx0 = x0
                        val jy0 = y0 + cellH
                        context.fill(jx0.toInt(), jy0.toInt(), (jx0 + cellW).toInt(), (jy0 + cellGap + 1).toInt(), color)
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
            if (!Config.dungeonMapFullGrid && (r1Room?.explored != true && r2Room?.explored != true)) continue

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
                context.fill(dx0.toInt(), dy0.toInt(), dx1.toInt(), dy1.toInt(), color)
            } else {
                val minZ = minOf(r1.z, r2.z)
                val gx = r1.x
                val dx0 = cellX(gx) + cellW * 0.35f
                val dy0 = cellY(minZ) + cellH
                val dx1 = dx0 + cellW * 0.3f
                val dy1 = dy0 + cellGap
                context.fill(dx0.toInt(), dy0.toInt(), dx1.toInt(), dy1.toInt(), color)
            }
        }

        // 3. Draw Room Text / Checkmarks / Secrets
        val visitedRooms = HashSet<DungeonRoom>()
        for (room in DungeonScanner.rooms) {
            if (room == null || !visitedRooms.add(room)) continue
            if (!room.explored && !Config.dungeonMapFullGrid) continue
            if (room.comps.isEmpty()) continue

            val avgGx = room.comps.map { it.cx / 2f }.average().toFloat()
            val avgGz = room.comps.map { it.cz / 2f }.average().toFloat()
            val cx = cellX(avgGx) + cellW * 0.5f
            val cy = cellY(avgGz) + cellH * 0.5f

            val textScale = (cellW / 36f).coerceIn(0.55f, 1.0f)
            val font = mc.font
            val fontH = font.lineHeight * textScale

            when (room.checkmark) {
                CheckmarkTypes.GREEN -> {
                    // Done: green check ✔
                    context.pose().pushMatrix()
                    context.pose().translate(cx, cy - fontH * 0.5f)
                    context.pose().scale(textScale * 1.25f, textScale * 1.25f)
                    context.centeredText(font, "✔", 0, 0, 0xFF55FF55.toInt())
                    context.pose().popMatrix()
                }
                CheckmarkTypes.WHITE -> {
                    // Cleared: secret count in white (or white checkmark if 0 secrets)
                    val secStr = if (room.totalSecrets > 0) {
                        val completed = if (room.secretsCompleted >= 0) room.secretsCompleted else 0
                        "$completed/${room.totalSecrets}"
                    } else {
                        "✔"
                    }
                    context.pose().pushMatrix()
                    context.pose().translate(cx, cy - fontH * 0.5f)
                    context.pose().scale(textScale, textScale)
                    context.centeredText(font, secStr, 0, 0, 0xFFFFFFFF.toInt())
                    context.pose().popMatrix()
                }
                CheckmarkTypes.FAILED -> {
                    // Failed: red cross ✖
                    context.pose().pushMatrix()
                    context.pose().translate(cx, cy - fontH * 0.5f)
                    context.pose().scale(textScale * 1.25f, textScale * 1.25f)
                    context.centeredText(font, "✖", 0, 0, 0xFFFF5555.toInt())
                    context.pose().popMatrix()
                }
                else -> {
                    // Uncleared: display proper room name in white
                    val rawName = room.name
                    if (rawName != null && room.type != RoomTypes.ENTRANCE) {
                        val words = rawName.replace("\u200B", "- ").split(" ").filter { it.isNotBlank() }
                        val startY = cy - (words.size * (fontH + 0.5f)) / 2f
                        words.forEachIndexed { lineIdx, word ->
                            val wy = startY + lineIdx * (fontH + 0.5f)
                            context.pose().pushMatrix()
                            context.pose().translate(cx, wy)
                            context.pose().scale(textScale, textScale)
                            context.centeredText(font, word, 0, 0, 0xFFFFFFFF.toInt())
                            context.pose().popMatrix()
                        }
                    }
                }
            }
        }

        // 4. Draw Teammate & Self Player Icons
        val selfGx = ((player.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize).toFloat().coerceIn(0f, 5f)
        val selfGz = ((player.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize).toFloat().coerceIn(0f, 5f)
        val selfPx = cellX(0) + selfGx * (cellW + cellGap) + cellW * 0.5f
        val selfPz = cellY(0) + selfGz * (cellH + cellGap) + cellH * 0.5f

        val showNames = Config.dungeonMapPlayerNames && (!Config.dungeonMapNamesOnlyLeap || isHoldingLeap(player))

        // Self icon
        val selfColor = DungeonContext.classColor(player.gameProfile.name)
        if (Config.dungeonMapMarkerSelf || !Config.dungeonMapPlayerHeads) {
            drawPlayerArrow(context, selfPx, selfPz, player.yRot.toDouble(), scale, selfColor, isSelf = true)
        } else {
            val selfSkin = player.skin
            drawPlayerHead(context, selfSkin, selfPx, selfPz, player.yRot.toDouble(), scale, selfColor)
        }

        val renderedNames = HashSet<String>()
        renderedNames.add(player.gameProfile.name.lowercase())

        // 1. Live world teammates (render distance)
        val worldPlayers = mc.level?.players() ?: emptyList()
        for (mate in worldPlayers) {
            val mateName = mate.gameProfile.name
            if (mateName.equals(player.gameProfile.name, ignoreCase = true) || mate.isSpectator) continue
            renderedNames.add(mateName.lowercase())

            val gx = ((mate.x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize).toFloat()
            val gz = ((mate.z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize).toFloat()
            if (gx < -0.5f || gx > 5.5f || gz < -0.5f || gz > 5.5f) continue
            val tx = cellX(0) + gx * (cellW + cellGap) + cellW * 0.5f
            val tz = cellY(0) + gz * (cellH + cellGap) + cellH * 0.5f
            val yawDeg = mate.yRot.toDouble()
            val skin = mate.skin
            val mateColor = DungeonContext.classColor(mateName)

            if (Config.dungeonMapPlayerHeads) {
                drawPlayerHead(context, skin, tx, tz, yawDeg, scale, mateColor)
            } else {
                drawPlayerArrow(context, tx, tz, yawDeg, scale * 0.8f, mateColor, isSelf = false)
            }

            if (showNames) {
                val shortName = mateName.take(4)
                context.centeredText(mc.font, shortName, tx.toInt(), (tz + 7).toInt(), 0xFFFFFFFF.toInt())
            }
        }

        // 2. Distant teammates from map packet
        for (icon in DungeonMapScanner.playerIcons) {
            val iconName = icon.name
            if (iconName != null && renderedNames.contains(iconName.lowercase())) continue

            val iconGx = icon.x.toFloat() / 2f
            val iconGz = icon.z.toFloat() / 2f
            if (kotlin.math.hypot(iconGx - selfGx, iconGz - selfGz) < 0.4f) continue

            val tx = cellX(0) + iconGx * (cellW + cellGap) + cellW * 0.5f
            val tz = cellY(0) + iconGz * (cellH + cellGap) + cellH * 0.5f
            val yawDeg = Math.toDegrees(icon.rot)
            val skin = getPlayerSkin(iconName)
            val mateColor = DungeonContext.classColor(iconName)

            if (Config.dungeonMapPlayerHeads && skin != null) {
                drawPlayerHead(context, skin, tx, tz, yawDeg, scale, mateColor)
            } else {
                drawPlayerArrow(context, tx, tz, yawDeg, scale * 0.8f, mateColor, isSelf = false)
            }

            if (showNames && iconName != null) {
                val shortName = iconName.take(4)
                context.centeredText(mc.font, shortName, tx.toInt(), (tz + 7).toInt(), 0xFFFFFFFF.toInt())
            }
        }

        context.pose().popMatrix()
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
        context: GuiGraphicsExtractor,
        skin: net.minecraft.world.entity.player.PlayerSkin,
        x: Float, z: Float,
        yawDeg: Double,
        scale: Float,
        borderColor: Int
    ) {
        val headSize = (9 * (scale / 1.66f) * Config.dungeonMapPlayerHeadScale).toInt().coerceIn(8, 24)
        val half = headSize / 2
        val hx = (x - half).toInt()
        val hz = (z - half).toInt()

        context.fill(hx - 2, hz - 2, hx + headSize + 2, hz + headSize + 2, borderColor)
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(context, skin, hx, hz, headSize)

        val yaw = Math.toRadians(yawDeg)
        val dx = (-sin(yaw) * (half + 2)).toInt()
        val dz = (cos(yaw) * (half + 2)).toInt()
        context.fill((x + dx - 1).toInt(), (z + dz - 1).toInt(), (x + dx + 1).toInt(), (z + dz + 1).toInt(), 0xFFFFFFFF.toInt())
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
        context: GuiGraphicsExtractor,
        x: Float, z: Float,
        yawDeg: Double,
        scale: Float,
        color: Int,
        isSelf: Boolean
    ) {
        val markerScale = scale * Config.dungeonMapMarkerScale * (if (isSelf) 0.30f else 0.40f)
        val w = (8 * markerScale).toInt().coerceIn(5, 10)
        val h = (12 * markerScale).toInt().coerceIn(7, 14)
        val halfW = w / 2
        val halfH = h / 2

        context.pose().pushMatrix()
        context.pose().translate(x, z)
        context.pose().rotate(Math.toRadians(yawDeg + 180.0).toFloat())

        val u = if (isSelf) 0f else 20f
        val tint = if (isSelf) 0xFFFFFFFF.toInt() else color

        var drewTexture = false
        try {
            context.blit(
                RenderPipelines.GUI_TEXTURED,
                MARKER_ATLAS,
                -halfW, -halfH,
                u, 0f,
                w, h,
                20, 28,
                40, 56,
                tint
            )
            drewTexture = true
        } catch (_: Throwable) {
            drewTexture = false
        }

        if (!drewTexture) {
            val arrow = (markerScale * 4.0).coerceIn(2.0, 5.0)
            val arrowColor = if (isSelf) 0xFF00FF00.toInt() else color
            for (row in (-arrow).toInt()..arrow.toInt()) {
                val half = ((row + arrow) * 0.45).toInt() + 1
                context.fill(-half, row, half + 1, row + 1, 0xFF000000.toInt())
            }
            for (row in (-arrow * 0.85).toInt()..(arrow * 0.85).toInt()) {
                val half = ((row + arrow) * 0.38).toInt()
                context.fill(-half, row, half + 1, row + 1, arrowColor)
            }
        }
        context.pose().popMatrix()
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
