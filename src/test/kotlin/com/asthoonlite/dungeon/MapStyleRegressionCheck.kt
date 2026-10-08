package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.api.FloorType
import com.asthoonlite.dungeon.api.mapEnums.RoomTypes
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.overlay.J2dMapCanvas
import com.asthoonlite.overlay.MapOverlayWindow
import com.asthoonlite.render.RecordMapCanvas
import com.asthoonlite.render.replay
import com.google.gson.Gson
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.image.BufferedImage

internal fun mapStyleRegressionChecks() {
    val legacy = Gson().fromJson("""{"dungeonMapX":71,"dungeonMapFullGrid":false,"dungeonMapExternalWindow":true,"starMobColor":123456}""", Config.Data::class.java)
    check(legacy.dungeonMapX == 71 && !legacy.dungeonMapFullGrid && legacy.dungeonMapExternalWindow && legacy.starMobColor == 123456)
    check(legacy.dungeonMapStyle == 0 && !legacy.dungeonMapEditMode && legacy.starMobRenderMode == 0)
    check(DungeonMapStyles.style(1) == 1 && DungeonMapStyles.style(-1) == 0 && DungeonMapStyles.style(99) == 0)
    val dev = DungeonMapStyles.layout(0, 128, FloorType.F7)
    val noamm = DungeonMapStyles.layout(1, 128, FloorType.F7)
    check(noamm == DungeonMapStyles.Layout(5f, 5f, 20f, 16f, 6f)) { "Noamm's native room/connector calibration changed" }
    check(dev != noamm && dev.room / dev.step in 0.799f..0.801f) { "Style picker must change actual geometry" }
    check(DungeonMapStyles.roomColor(0, RoomTypes.FAIRY, true) == 0xFFE000FF.toInt())
    check(DungeonMapStyles.roomColor(1, RoomTypes.FAIRY, true) == 0xFFE39BE2.toInt())
    for (floor in FloorType.entries.filterNot { it == FloorType.None }) for (style in 0..1) {
        val l = DungeonMapStyles.layout(style, 200, floor)
        check(l.x >= 0 && l.y >= 0 && l.cellX((floor.roomsW - 1).toFloat()) + l.room <= 200.1f &&
            l.cellY((floor.roomsH - 1).toFloat()) + l.room <= 200.1f) { "$style/$floor map escaped its rectangle" }
    }
    check(DungeonMapStyles.roomCenter(listOf(0 to 0, 1 to 0, 0 to 1)) == (0f to 0f)) { "L-room text must stay in its elbow" }
    check(DungeonMapStyles.roomCenter(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) == (0.5f to 0.5f))

    check(DungeonMap.clampRect(-8, 999, 166, 480, 270) == DungeonMap.Rect(0, 104, 166))
    check(DungeonMap.clampRect(400, 42, 600, 480, 270) == DungeonMap.Rect(210, 0, 270))
    val rect = DungeonMap.Rect(12, 42, 166)
    check(rect.contains(12.0, 42.0) && rect.contains(177.99, 207.99))
    check(!rect.contains(178.0, 208.0) && !rect.contains(11.99, 42.0))
    val desktop = MapOverlayWindow.desktopRect(100, 200, 1920, 1080, 480, 270, rect.x, rect.y, rect.size)
    check(desktop.x == 148.0 && desktop.y == 368.0 && desktop.width == 664.0 && desktop.height == 664.0)
    check(MapOverlayWindow.awtBounds(desktop, 0, 0, 2.0, 2.0) == MapOverlayWindow.Bounds(74, 184, 332, 332))
    val second = desktop.copy(x = -1820.0, y = 100.0, monitorX = -1920)
    check(MapOverlayWindow.awtBounds(second, -960, 0, 2.0, 2.0).x == -910) { "Monitor origin must be retained across DPI conversion" }
    check(MapOverlayWindow.awtBounds(desktop.copy(physical = false), 0, 0, 2.0, 2.0).width == 664) { "Logical GLFW coordinates must not be scaled twice" }

    for (clazz in DungeonContext.PlayerClass.entries.filterNot { it == DungeonContext.PlayerClass.UNKNOWN }) {
        val row = DungeonContext.parsePartyRow("§b[100] §6[MVP++] §fTest_Mage §a(${clazz.displayName} L)")
        check(row?.name == "Test_Mage" && row.role == clazz && !row.dead) { "Displayed party name and class must agree: $clazz" }
    }
    check(DungeonContext.parsePartyRow("[100] Self (DEAD)")?.dead == true)
    check(DungeonContext.parsePartyRow("Mage joined the dungeon") == null)
    check(DungeonContext.parsePartyRow("[100] Other (Banana L)") == null)
    check(DungeonContext.PlayerClass.MAGE.color == 0xFF00AAAA.toInt())
    check(DungeonContext.PlayerClass.TANK.color == 0xFF00AA00.toInt())
    check(DungeonMapScanner.indexKeyFrom("icon-0") == 0 && DungeonMapScanner.indexKeyFrom("icon-12") == 12)
    check(DungeonMapScanner.indexKeyFrom("mob-2") == null)
    check(TerminalCursor.holdNormalCursor(1, true, true)) { "Solver resets during a Normal terminal must keep the OS cursor hidden" }
    check(!TerminalCursor.holdNormalCursor(1, false, true) && !TerminalCursor.holdNormalCursor(1, true, false))
    check(!TerminalCursor.holdNormalCursor(0, true, true)) { "Osu handover policy must remain unchanged" }
    for (kind in TerminalSolver.Kind.entries) {
        val required = TermGui.requiredSlots(kind)
        check(TermGui.renderGate(true, false, kind, kind.slotCount + 36, required)) { "Live chest terminal must pass the render gate" }
        check(TermGui.renderGate(true, false, kind, kind.slotCount, required)) { "Practice terminal must use the same render gate" }
        check(!TermGui.renderGate(false, false, kind, 90, required) && !TermGui.renderGate(true, true, kind, 90, required))
        check(!TermGui.renderGate(true, false, kind, required - 1, required)) { "Truncated menus must still be rejected" }
    }
    check(!TermGui.renderGate(true, false, null, 90, 0)) { "Unrecognized titles must not become custom terminals" }

    val hitbox = AABB(10.2, 64.1, 20.3, 11.8, 66.9, 21.7)
    check(StarMobESP.renderBounds(1, hitbox, Vec3(11.0, 64.0, 21.0), 0.8) == hitbox) { "Box mode must preserve the entity's entire AABB" }
    check(StarMobESP.renderBounds(0, hitbox, Vec3(11.0, 64.0, 21.0), 0.8).maxY == 64.8) { "Fill geometry must remain backward compatible" }

    // Every ported sprite must round-trip through recording and actually paint in Java2D.
    val recording = RecordMapCanvas()
    for ((directory, names, native) in listOf(
        Triple("noamm", listOf("green_check", "white_check", "cross", "question", "marker"), 100),
        Triple("devonian", listOf("greenCheck", "whiteCheck", "failedRoom", "questionMark"), 10))) {
        for (name in names) {
            val copy = RecordMapCanvas()
            recording.ops.clear()
            recording.image("textures/map/$directory/$name.png", 0, 0, 24,
                if (name == "questionMark") 62 else if (name == "marker") 7 else native,
                if (name == "marker") 0xFF00AAAA.toInt() else -1)
            replay(recording.ops, copy)
            check(copy.ops == recording.ops)
            val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { replay(copy.ops, J2dMapCanvas(graphics, graphics.fontMetrics)) }
            finally { graphics.dispose() }
            check((0 until 24).any { x -> (0 until 24).any { y -> image.getRGB(x, y) ushr 24 != 0 } }) { "Missing or empty map sprite $directory/$name" }
        }
    }
}
