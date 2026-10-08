package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.api.cornerStart
import com.asthoonlite.dungeon.api.halfRoomSize
import com.asthoonlite.dungeon.api.roomDoorCombinedSize
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.overlay.MapOverlayWindow
import com.asthoonlite.render.HudMapCanvas
import com.asthoonlite.render.MapCanvas
import com.asthoonlite.render.RecordMapCanvas
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin

object DungeonMap : HudElement {
    data class Rect(val x: Int, val y: Int, val size: Int) {
        fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + size && py < y + size
    }

    internal fun clampRect(x: Int, y: Int, size: Int, width: Int, height: Int): Rect {
        val fitted = size.coerceIn(1, minOf(width, height).coerceAtLeast(1))
        return Rect(x.coerceIn(0, (width - fitted).coerceAtLeast(0)),
            y.coerceIn(0, (height - fitted).coerceAtLeast(0)), fitted)
    }

    fun screenRect(width: Int, height: Int) = clampRect(Config.dungeonMapX, Config.dungeonMapY,
        (100 * Config.dungeonMapScale.coerceIn(1f, 6f)).toInt(), width, height)

    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "dungeon_map"), this)
    }

    fun resetRun() {
        DungeonScanner.reset()
        DungeonMapScanner.reset()
        MapOverlayWindow.hide()
    }

    override fun extractRenderState(context: net.minecraft.client.gui.GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (QuietMode.suppressing() || !Config.dungeonMapEnabled || !DungeonContext.inDungeon || player == null ||
            Config.dungeonMapHideInBoss && DungeonContext.inBoss ||
            !Config.dungeonMapAlwaysShow && !isHoldingMap(player)) {
            MapOverlayWindow.hide()
            return
        }
        val rect = screenRect(mc.window.guiScaledWidth, mc.window.guiScaledHeight)
        drawMap(HudMapCanvas(context), mc, rect, advanced = false)
        if (Config.dungeonMapFullGrid && !Config.dungeonMapEditMode) {
            val record = RecordMapCanvas()
            drawMap(record, mc, Rect(0, 0, rect.size), advanced = true)
            MapOverlayWindow.publish(record.ops.toList(), rect.size,
                MapOverlayWindow.placement(mc.window, rect.x, rect.y, rect.size))
        } else MapOverlayWindow.hide()
    }

    /** The editor uses the same canvas and geometry, even outside a dungeon. */
    fun preview(canvas: MapCanvas, width: Int, height: Int) {
        MapOverlayWindow.hide()
        drawMap(canvas, Minecraft.getInstance(), screenRect(width, height), advanced = false)
    }

    private fun drawMap(canvas: MapCanvas, mc: Minecraft, rect: Rect, advanced: Boolean) {
        val style = DungeonMapStyles.style(Config.dungeonMapStyle)
        val layout = DungeonMapStyles.layout(style, rect.size, DungeonContext.floor,
            DungeonMapScanner.roomSize, DungeonMapScanner.mapOffsetX.takeIf { it >= 0 } ?: 5,
            DungeonMapScanner.mapOffsetZ.takeIf { it >= 0 } ?: 5)
        canvas.push()
        canvas.translate(rect.x.toFloat(), rect.y.toFloat())
        // Border stays inside the rectangle, so the external frame covers the entire HUD frame.
        canvas.fill(0, 0, rect.size, rect.size, 0xFF090B10.toInt())
        canvas.fill(1, 1, rect.size - 1, rect.size - 1, 0xFF0D1F35.toInt())
        val labels = advanced || !Config.dungeonMapLegitBase
        DungeonMapStyles.draw(canvas, style, layout, DungeonScanner.rooms.toList(), DungeonScanner.doors.toList(), advanced,
            DungeonMapStyles.Labels(Config.dungeonMapShowNames && labels, Config.dungeonMapShowSecrets && labels,
                Config.dungeonMapShowCheckmarks, Config.dungeonMapDontRenderCommonNames,
                Config.dungeonMapDontRenderYellowName, Config.dungeonMapDontRenderFairyCheckmark))
        mc.player?.let { drawPlayers(canvas, mc, it, layout, rect.size, style) }
        if (Config.dungeonMapEditMode) {
            val color = 0xFF55FFFF.toInt()
            canvas.fill(0, 0, rect.size, 2, color)
            canvas.fill(0, rect.size - 2, rect.size, rect.size, color)
            canvas.fill(0, 0, 2, rect.size, color)
            canvas.fill(rect.size - 2, 0, rect.size, rect.size, color)
            canvas.fill(3, 3, 11, 5, color)
            canvas.fill(3, 7, 11, 9, color)
        }
        canvas.pop()
    }

    private fun drawPlayers(c: MapCanvas, mc: Minecraft, self: LocalPlayer, l: DungeonMapStyles.Layout, size: Int, style: Int) {
        val scale = size / 100f
        val showNames = Config.dungeonMapPlayerNames && (!Config.dungeonMapNamesOnlyLeap || isHoldingLeap(self))
        fun player(name: String, gx: Float, gz: Float, yaw: Double, skin: PlayerSkin?, own: Boolean) {
            if (gx !in -0.5f..5.5f || gz !in -0.5f..5.5f) return
            val color = DungeonContext.classColor(name)
            val headSize = ((if (style == 1) 12 * size / 128f else 9 * scale / 1.66f) * Config.dungeonMapPlayerHeadScale)
                .toInt().coerceIn(6, 24)
            val inset = if (Config.dungeonMapPlayerHeads) headSize / 2 + 3f else 8f
            val x = l.centerX(gx).coerceIn(inset.coerceAtMost(size / 2f), (size - inset).coerceAtLeast(size / 2f))
            val y = l.centerY(gz).coerceIn(inset.coerceAtMost(size / 2f), (size - inset).coerceAtLeast(size / 2f))
            c.push()
            c.translate(x, y)
            c.rotate(Math.toRadians(yaw + 180).toFloat())
            if (Config.dungeonMapPlayerHeads) {
                // Noamm renderPlayer: rotate face and class border together, including self.
                c.face(name, skin, -headSize / 2, -headSize / 2, headSize, color)
            } else {
                val markerScale = scale * Config.dungeonMapMarkerScale * if (own) 0.30f else 0.40f
                if (style == 0) c.marker(own, (8 * markerScale).toInt().coerceIn(5, 10),
                    (12 * markerScale).toInt().coerceIn(7, 14), markerScale, color)
                else {
                    val edge = (12 * size / 128f * Config.dungeonMapMarkerScale).toInt().coerceAtLeast(5)
                    c.image("textures/map/noamm/marker.png", -edge / 2, -edge / 2, edge, 7, color)
                }
            }
            c.pop()
            if (own && Config.dungeonMapMarkerSelf && Config.dungeonMapPlayerHeads) {
                c.push()
                c.translate(x, y)
                c.rotate(Math.toRadians(yaw + 180).toFloat())
                c.fill(-1, -headSize / 2 - 4, 2, -headSize / 2 - 2, color)
                c.pop()
            }
            if (showNames) c.text(name.take(4), x.toInt().coerceIn(12, (size - 12).coerceAtLeast(12)),
                (y + headSize / 2 + 3).toInt().coerceAtMost(size - 9), color, true)
        }
        fun gx(x: Double) = ((x - cornerStart.x - halfRoomSize) / roomDoorCombinedSize).toFloat()
        fun gz(z: Double) = ((z - cornerStart.z - halfRoomSize) / roomDoorCombinedSize).toFloat()
        val names = HashSet<String>()
        val selfName = self.gameProfile.name
        player(selfName, gx(self.x), gz(self.z), self.yRot.toDouble(), self.skin, true)
        names.add(selfName.lowercase())
        val party = DungeonContext.getTeammateNames()
        for (mate in mc.level?.players().orEmpty()) {
            val name = mate.gameProfile.name
            if (!party.any { it.equals(name, true) } || mate.isSpectator || mate.uuid.version() == 2) continue
            DungeonContext.rememberSkin(name, mate.skin)
            player(name, gx(mate.x), gz(mate.z), mate.yRot.toDouble(), mate.skin, false)
            names.add(name.lowercase())
        }
        for (icon in DungeonMapScanner.playerIcons) {
            val name = icon.name ?: if (Config.dungeonMapAllDecorations) "?" else continue
            if (!names.add(name.lowercase())) continue
            val skin = mc.connection?.getPlayerInfo(name)?.skin ?: DungeonContext.playerSkin(name)
            player(name, icon.x.toFloat(), icon.z.toFloat(), Math.toDegrees(icon.rot), skin, false)
        }
    }

    private fun isHoldingLeap(player: LocalPlayer) = player.mainHandItem.hoverName.string.contains("Spirit Leap", true) ||
        player.offhandItem.hoverName.string.contains("Spirit Leap", true)

    private fun isHoldingMap(player: LocalPlayer) = player.mainHandItem.get(DataComponents.MAP_ID) != null ||
        player.offhandItem.get(DataComponents.MAP_ID) != null
}
