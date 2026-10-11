package com.asthoonlite.dungeon

import com.asthoonlite.dungeon.api.FloorType
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.overlay.MapOverlayWindow
import org.lwjgl.glfw.GLFW

internal fun mapRenderingRegressionChecks() {
    val headlessProperty = System.getProperty("java.awt.headless")
    check(MapOverlayWindow.desktopBootstrapAllowed("Windows 11", null))
    check(MapOverlayWindow.desktopBootstrapAllowed("Mac OS X", null))
    check(MapOverlayWindow.desktopBootstrapAllowed("Linux", ":0", GLFW.GLFW_PLATFORM_X11))
    check(!MapOverlayWindow.desktopBootstrapAllowed("Linux", ":0"))
    check(!MapOverlayWindow.desktopBootstrapAllowed("Linux", ":0", GLFW.GLFW_PLATFORM_WAYLAND))
    check(!MapOverlayWindow.desktopBootstrapAllowed("Linux", null, GLFW.GLFW_PLATFORM_X11))
    check(!MapOverlayWindow.desktopBootstrapAllowed("Linux", " ", GLFW.GLFW_PLATFORM_X11))
    check(!MapOverlayWindow.desktopBootstrapAllowed("Windows 11", null, explicitlyHeadless = true))
    check(!MapOverlayWindow.desktopBootstrapAllowed("unknown", ":0"))
    check(System.getProperty("java.awt.headless") == headlessProperty) {
        "Desktop policy checks must leave the harness's headless setting untouched"
    }

    fun gate(quiet: Boolean = false, enabled: Boolean = true, dungeon: Boolean = true,
             player: Boolean = true, hideBoss: Boolean = false, boss: Boolean = false,
             always: Boolean = true, held: Boolean = false, hidden: Boolean = false,
             editing: Boolean = false) = DungeonMap.visibilityGate(
        quiet, enabled, dungeon, player, hideBoss, boss, always, held, hidden, editing)

    check(gate() == DungeonMap.VisibilityGate.VISIBLE)
    check(gate(quiet = true) == DungeonMap.VisibilityGate.QUIET) {
        "A working editor preview must not imply that quiet mode permits gameplay drawing"
    }
    check(gate(enabled = false) == DungeonMap.VisibilityGate.DISABLED)
    check(gate(dungeon = false) == DungeonMap.VisibilityGate.OUTSIDE_DUNGEON)
    check(gate(player = false) == DungeonMap.VisibilityGate.NO_PLAYER)
    check(gate(hideBoss = true, boss = true) == DungeonMap.VisibilityGate.BOSS)
    check(gate(boss = true) == DungeonMap.VisibilityGate.VISIBLE)
    check(gate(always = false) == DungeonMap.VisibilityGate.MAP_NOT_HELD)
    check(gate(always = false, held = true) == DungeonMap.VisibilityGate.VISIBLE)
    check(gate(hidden = true) == DungeonMap.VisibilityGate.HIDDEN_HUD)
    check(gate(editing = true) == DungeonMap.VisibilityGate.EDITOR) {
        "Gameplay extraction must not paint a second map over the editor's shared preview"
    }
    check(gate(quiet = true, hidden = true, editing = true) == DungeonMap.VisibilityGate.QUIET)

    for (available in listOf(false, true)) {
        check(DungeonMap.renderRoute(false, available) == DungeonMap.RenderRoute(false, false)) {
            "Turning Full Map Overlay off must leave the explored HUD layer alone"
        }
    }
    check(DungeonMap.renderRoute(true, true) == DungeonMap.RenderRoute(false, true)) {
        "Full Map Overlay must reach the desktop layer without an unexposed legacy config toggle"
    }
    check(DungeonMap.renderRoute(true, false) == DungeonMap.RenderRoute(true, false)) {
        "Unsupported desktop placement or failed AWT initialization must keep advanced content on the HUD"
    }

    DungeonMapScanner.reset()
    check(!DungeonMapScanner.ensureMapDimensions(ByteArray(128 * 128), FloorType.F7))
    check(DungeonMapScanner.roomSize == -1 && DungeonMapScanner.mapOffsetX == -1) {
        "An incomplete first packet must not lock in guessed map geometry"
    }
    val twelvePixelRooms = ByteArray(128 * 128)
    for (z in 18 until 30) for (x in 18 until 30) twelvePixelRooms[x + z * 128] = 30
    check(DungeonMapScanner.ensureMapDimensions(twelvePixelRooms, FloorType.F7)) {
        "Entrance pixels received after an incomplete packet must still initialize the map"
    }
    check(DungeonMapScanner.roomSize == 12 && DungeonMapScanner.roomGap == 16 &&
        DungeonMapScanner.mapOffsetX == 18 && DungeonMapScanner.mapWidth == 92) {
        "Recovered dimensions must come from the real pixel buffer, not the former 16-pixel fallback"
    }

    DungeonMapScanner.reset()
    val earlyFloorMap = ByteArray(128 * 128)
    for (z in 11 until 27) for (x in 22 until 38) earlyFloorMap[x + z * 128] = 30
    check(DungeonMapScanner.ensureMapDimensions(earlyFloorMap, FloorType.None))
    check(DungeonMapScanner.ensureMapDimensions(earlyFloorMap, FloorType.F1))
    check(DungeonMapScanner.mapOffsetX == 22 && DungeonMapScanner.mapOffsetZ == 11 &&
        DungeonMapScanner.mapWidth == 76 && DungeonMapScanner.mapHeight == 96) {
        "A floor detected after the first map packet must recalibrate the grid"
    }
    DungeonMapScanner.reset()
}
