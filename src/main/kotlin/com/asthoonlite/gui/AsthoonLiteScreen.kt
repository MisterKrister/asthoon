package com.asthoonlite.gui

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.config.TerminalMode
import com.asthoonlite.pathfinding.RouteCategory
import com.asthoonlite.pathfinding.RouteSubcategory
import com.asthoonlite.pathfinding.PathPreset
import com.asthoonlite.pathfinding.PathPresetManager
import com.asthoonlite.pathfinding.RouteEditor
import com.asthoonlite.pathfinding.RouteNodeType
import com.asthoonlite.pathfinding.PathExecutor
import com.asthoonlite.pathfinding.PathPoint
import com.asthoonlite.pet.PetHudEditorScreen
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Modern, clean, user-friendly settings GUI for AsthoonLite.
 * Features dark card styling, emerald toggle switches, dynamic sliders,
 * dedicated Map & Terminals workspaces with Devonian & RSM preset loading,
 * dedicated Pathfinding workspace with route creation, dropdowns, import/export,
 * smooth scrolling, and instant click responsiveness across entire rows.
 */
class AsthoonLiteScreen : Screen(Component.literal("AsthoonLite")) {

    private enum class Tab(val label: String) {
        QOL("QOL"),
        DUNGEON("Dungeon"),
        MAP("Map"),
        TERMINALS("Terminals"),
        PATHFINDING("Pathfinding"),
        HITBOXES("Hitboxes"),
        MINING("Mining"),
        FISHING("Fishing"),
        NUCLEUS("Nucleus"),
        FUNNY("Funny")
    }

    private enum class DungeonSection(val label: String) {
        GENERAL("General"),
        PUZZLES("Puzzles"),
        F7M7("F7 / M7"),
        SECRETS("Secrets")
    }

    private enum class PathfindingSection(val label: String) {
        USER_PRESETS("User Presets"),
        CREATE_PRESET("Create Preset"),
        ROUTE_EDITOR("Route Editor"),
        EXPORT_PRESET("Export Preset"),
        IMPORT_PRESET("Import Preset")
    }

    private var activeTab = Tab.QOL
    private var activeDungeonSection = DungeonSection.GENERAL
    private var activePathfindingSection = PathfindingSection.USER_PRESETS

    companion object {
        private const val PANEL_W = 540
        private const val HEADER_H = 38
        private const val TAB_BAR_H = 26
        private const val DUNGEON_BAR_H = 26
        private const val ROW_H = 30
        private const val ROW_GAP = 4
        private const val FOOTER_H = 34

        // Color palette
        private const val COL_BACKDROP        = 0xB3050914.toInt() // 70% dark vignette
        private const val COL_PANEL_BG        = 0xFF0C1322.toInt() // Deep dark blue-slate
        private const val COL_PANEL_BORDER    = 0xFF1E293B.toInt() // Subtle slate border
        private const val COL_HEADER_BG       = 0xFF080D18.toInt() // Slate 950 header
        private const val COL_HEADER_BORDER   = 0xFF1E293B.toInt()
        private const val COL_ACCENT          = 0xFF38BDF8.toInt() // Bright sky cyan
        private const val COL_ACCENT_DIM      = 0xFF0284C7.toInt()
        private const val COL_TEXT_TITLE      = 0xFFF1F5F9.toInt() // Crisp white
        private const val COL_TEXT_SUB        = 0xFF8294AA.toInt() // Soft slate blue
        private const val COL_TEXT_MUTED      = 0xFF475569.toInt()

        private const val COL_CARD_BG         = 0xFF121C2D.toInt() // Dark card
        private const val COL_CARD_SUB_BG     = 0xFF0E1726.toInt() // Indented subcard
        private const val COL_CARD_HOVER      = 0xFF1A283E.toInt() // Hovered card
        private const val COL_CARD_BORDER     = 0xFF1E2D42.toInt()
        private const val COL_CARD_BORDER_HOV = 0xFF2A4263.toInt()

        private const val COL_STATUS_ON       = 0xFF10B981.toInt() // Emerald green
        private const val COL_STATUS_OFF      = 0xFF2A374A.toInt() // Muted dark slate
        private const val COL_TOGGLE_ON       = 0xFF10B981.toInt()
        private const val COL_TOGGLE_OFF      = 0xFF1E293B.toInt()
        private const val COL_TOGGLE_KNOB_ON  = 0xFFFFFFFF.toInt()
        private const val COL_TOGGLE_KNOB_OFF = 0xFF64748B.toInt()
    }

    private sealed interface ContentItem {
        val height: Int
    }

    private data class SectionHeader(val title: String) : ContentItem {
        override val height: Int = 22
    }

    private data class ToggleRow(
        val label: String,
        val subtitle: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    ) : ContentItem {
        override val height: Int = ROW_H
    }

    /**
     * A non-interactive line of explanation, drawn under the section it is
     * about. A setting is only easy to use when the consequence of flipping it
     * is written next to the switch instead of guessed at.
     */
    private data class NoteRow(val text: String) : ContentItem {
        override val height: Int = 15
    }

    private data class WidgetRow(val widget: AbstractWidget) : ContentItem {
        override val height: Int = widget.height
    }

    private data class MultiWidgetRow(val widgets: List<AbstractWidget>, override val height: Int = 24) : ContentItem

    private data class SearchableEntry(
        val category: String,
        val cleanLabel: String,
        val subtitle: String,
        val searchKey: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    )

    private var cachedSearchEntries: List<SearchableEntry>? = null

    private var currentItems: List<ContentItem> = emptyList()

    private lateinit var tabButtons: List<ModernButton>
    private var dungeonSectionButtons: MutableList<ModernButton> = mutableListOf()
    private var pathfindingSectionButtons: MutableList<ModernButton> = mutableListOf()
    private var extraWidgets: MutableList<Pair<AbstractWidget, Int>> = mutableListOf()
    private lateinit var btnClose: ModernButton
    private lateinit var btnDone: ModernButton
    private lateinit var btnAutoClickerKey: ModernButton
    private var listeningForAutoClickerKey = false
    private lateinit var btnInventoryAutoClickerKey: ModernButton
    private var listeningForInventoryAutoClickerKey = false
    private lateinit var btnQuietModeKey: ModernButton
    private var listeningForQuietModeKey = false
    private lateinit var searchBox: EditBox
    private var searchQuery = ""
    private var scrollOffset = 0

    // Pathfinding preset creator state
    private var createCategory: RouteCategory = RouteCategory.MINING
    private var createSubcategory: String = "Macro"
    private var createDropdownOpen: Boolean = false
    private var createPresetName: String = ""
    private var exportFeedbackMsg: String = ""
    private var importFeedbackMsg: String = ""

    // Route Editor state
    private var addNodeInputIndex: String = ""
    private var addNodeInputX: String = ""
    private var addNodeInputY: String = ""
    private var addNodeInputZ: String = ""
    private var addNodeSelectedType: RouteNodeType = RouteNodeType.WALK

    private var editNodeInputIndex: String = ""
    private var editNodeInputX: String = ""
    private var editNodeInputY: String = ""
    private var editNodeInputZ: String = ""
    private var editNodeSelectedType: RouteNodeType = RouteNodeType.WALK
    private var editTypeDropdownOpen: Boolean = false
    private var editLookDropdownOpen: Boolean = false
    private var editLookInputX: String = ""
    private var editLookInputY: String = ""
    private var editLookInputZ: String = ""
    private var editTimeoutSeconds: String = ""

    private var mouseDownNodeIdx: Int = -1
    private var mouseClickStartX: Double = 0.0
    private var mouseClickStartY: Double = 0.0
    private var isDraggingNode: Boolean = false
    private var draggingNodeFromIdx: Int = -1
    private var draggingNodeHoverIdx: Int = -1
    private val nodeItemBounds = mutableListOf<Triple<Int, Int, Int>>() // (index, topY, bottomY)

    // Scrollbar drag state
    private var isDraggingScrollbar = false
    private var scrollDragStartOffset = 0
    private var scrollDragStartY = 0.0

    private fun panelH() = (height - 24).coerceIn(360, 560)
    private fun px() = (width - PANEL_W) / 2
    private fun py() = (height - panelH()) / 2

    private fun contentTop(): Int {
        val base = py() + HEADER_H + TAB_BAR_H
        val hasSubBar = searchQuery.isEmpty() && (activeTab == Tab.DUNGEON || activeTab == Tab.PATHFINDING)
        return if (hasSubBar) base + DUNGEON_BAR_H + 8 else base + 8
    }

    private fun contentBottom(): Int = py() + panelH() - FOOTER_H

    override fun init() {
        cachedSearchEntries = null
        val px = px()
        val py = py()

        // If a route is currently opened in the route editor, stay on Route Editor until the user hits Done
        if (RouteEditor.activePreset != null && activeTab == Tab.QOL) {
            activeTab = Tab.PATHFINDING
            activePathfindingSection = PathfindingSection.ROUTE_EDITOR
        }

        // ── Header Search Box ────────────────────────────────────────────────
        val searchX = px + 145
        val searchW = PANEL_W - 145 - 34
        searchBox = EditBox(font, searchX, py + 9, searchW, 20, Component.literal("Search..."))
        searchBox.setHint(Component.literal("Search features..."))
        searchBox.setResponder { query ->
            if (query.isBlank()) {
                if (searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    scrollOffset = 0
                    rebuildTab(activeTab)
                }
            } else {
                scrollOffset = 0
                rebuildSearch(query)
            }
        }
        searchBox.setBordered(true)
        searchBox.setTextColor(COL_TEXT_TITLE)
        addRenderableWidget(searchBox)

        // ── Header Close button ───────────────────────────────────────────────
        btnClose = ModernButton(px + PANEL_W - 28, py + 9, 20, 20, Component.literal("✕")) { onClose() }
        addRenderableWidget(btnClose)

        // ── Tab bar (proportional tab sizing for crisp layout) ───────────────
        val availableW = PANEL_W - 16
        val labels = Tab.entries.map { it.label }
        val textWidths = labels.map { font.width(it) }
        val totalTextW = textWidths.sum()
        val extraPerTab = (availableW - totalTextW) / Tab.entries.size
        var curTabX = px + 8
        tabButtons = Tab.entries.mapIndexed { i, tab ->
            val w = textWidths[i] + extraPerTab
            val btn = ModernButton(curTabX, py + HEADER_H, w, TAB_BAR_H, Component.literal(tab.label)) {
                if (::searchBox.isInitialized && searchBox.value.isNotEmpty()) {
                    searchBox.value = ""
                }
                if (activeTab != tab || searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    scrollOffset = 0
                    rebuildTab(tab)
                }
            }
            curTabX += w
            btn
        }
        tabButtons.forEach { addRenderableWidget(it) }

        // ── Footer Done button ───────────────────────────────────────────────
        btnDone = ModernButton(px + (PANEL_W - 100) / 2, py + panelH() - 26, 100, 20, Component.literal("Done")) {
            if (activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR && RouteEditor.activePreset != null) {
                RouteEditor.finishEditing()
            }
            onClose()
        }
        addRenderableWidget(btnDone)

        rebuildTab(activeTab)
    }

    private fun rebuildTab(tab: Tab) {
        activeTab = tab
        searchQuery = ""

        // Remove old extra widgets & sub-bar buttons
        extraWidgets.forEach { removeWidget(it.first) }
        extraWidgets.clear()
        dungeonSectionButtons.forEach { removeWidget(it) }
        dungeonSectionButtons.clear()
        pathfindingSectionButtons.forEach { removeWidget(it) }
        pathfindingSectionButtons.clear()
        listeningForAutoClickerKey = false
        listeningForInventoryAutoClickerKey = false
        listeningForQuietModeKey = false

        val px = px()
        val py = py()

        // ── Dungeon Section Buttons ──────────────────────────────────────────
        if (tab == Tab.DUNGEON) {
            val sectionY = py + HEADER_H + TAB_BAR_H + 4
            val sectionW = (PANEL_W - 24) / DungeonSection.entries.size
            DungeonSection.entries.forEachIndexed { i, section ->
                val btn = ModernButton(
                    px + 12 + i * sectionW,
                    sectionY,
                    sectionW - 4,
                    DUNGEON_BAR_H - 4,
                    Component.literal(section.label)
                ) {
                    if (activeDungeonSection != section) {
                        activeDungeonSection = section
                        scrollOffset = 0
                        rebuildTab(Tab.DUNGEON)
                    }
                }
                addRenderableWidget(btn)
                dungeonSectionButtons.add(btn)
            }
        }

        // ── Pathfinding Section Buttons ──────────────────────────────────────
        if (tab == Tab.PATHFINDING) {
            val sectionY = py + HEADER_H + TAB_BAR_H + 4
            val sectionW = (PANEL_W - 24) / PathfindingSection.entries.size
            PathfindingSection.entries.forEachIndexed { i, section ->
                val btn = ModernButton(
                    px + 12 + i * sectionW,
                    sectionY,
                    sectionW - 4,
                    DUNGEON_BAR_H - 4,
                    Component.literal(section.label)
                ) {
                    if (activePathfindingSection != section) {
                        activePathfindingSection = section
                        scrollOffset = 0
                        rebuildTab(Tab.PATHFINDING)
                    }
                }
                addRenderableWidget(btn)
                pathfindingSectionButtons.add(btn)
            }
        }

        currentItems = applyCollapse(buildItemsForTab(tab, px))

        var relY = 0
        for (item in currentItems) {
            if (item is WidgetRow) {
                extraWidgets.add(Pair(item.widget, relY))
                addWidget(item.widget)
            } else if (item is MultiWidgetRow) {
                for (w in item.widgets) {
                    extraWidgets.add(Pair(w, relY))
                    addWidget(w)
                }
            }
            relY += item.height + ROW_GAP
        }

        // Folding a section shortens the list, so the viewport can now sit
        // past the end of it. Clamping here rather than on the next scroll
        // keeps the first frame after a fold on screen instead of blank.
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        updateWidgetPositions()
    }

    /**
     * Drops the rows of every section the player has folded away, keeping the
     * headers themselves so the folded state stays visible and clickable.
     *
     * Nothing is deleted — the settings are all still there, one click on the
     * header from being back. That is the whole point: a long tab should be
     * *short by default*, not short by removal.
     */
    private fun applyCollapse(items: List<ContentItem>): List<ContentItem> {
        if (items.none { it is SectionHeader && Config.isSectionCollapsed(it.title) }) return items
        val out = ArrayList<ContentItem>(items.size)
        var hidden = false
        for (item in items) {
            if (item is SectionHeader) {
                hidden = Config.isSectionCollapsed(item.title)
                out.add(item)
            } else if (!hidden) {
                out.add(item)
            }
        }
        return out
    }

    private fun updateWidgetPositions() {
        val top = contentTop()
        val bottom = contentBottom()
        for ((widget, relY) in extraWidgets) {
            val targetY = top - scrollOffset + relY
            widget.y = targetY
            widget.visible = targetY + widget.height >= top && targetY <= bottom
        }
    }

    private fun maxScroll(): Int {
        var totalHeight = 0
        for (item in currentItems) {
            totalHeight += item.height + ROW_GAP
        }
        val viewport = (contentBottom() - contentTop()).coerceAtLeast(60)
        return (totalHeight - viewport).coerceAtLeast(0)
    }

    private fun getSearchableEntries(px: Int): List<SearchableEntry> {
        cachedSearchEntries?.let { return it }
        val list = mutableListOf<SearchableEntry>()

        fun collect(category: String, items: List<ContentItem>) {
            for (item in items) {
                if (item is ToggleRow) {
                    val raw = item.label
                    val clean = if (raw.startsWith("    ↳ ")) raw.substring(6)
                        else if (raw.startsWith("  ↳ ")) raw.substring(4)
                        else if (raw.startsWith("↳ ")) raw.substring(2)
                        else raw
                    val key = (clean + " " + item.subtitle).lowercase()
                    list.add(SearchableEntry(category, clean, item.subtitle, key, item.get, item.set))
                }
            }
        }

        val prevTab = activeTab
        val prevSec = activeDungeonSection
        for (tab in Tab.entries) {
            if (tab == Tab.DUNGEON) {
                for (section in DungeonSection.entries) {
                    activeDungeonSection = section
                    collect("Dungeon - ${section.label}", buildItemsForTab(Tab.DUNGEON, px))
                }
            } else {
                collect(tab.label, buildItemsForTab(tab, px))
            }
        }
        activeTab = prevTab
        activeDungeonSection = prevSec
        cachedSearchEntries = list
        return list
    }

    private fun rebuildSearch(query: String) {
        searchQuery = query

        extraWidgets.forEach { removeWidget(it.first) }
        extraWidgets.clear()
        dungeonSectionButtons.forEach { removeWidget(it) }
        dungeonSectionButtons.clear()
        pathfindingSectionButtons.forEach { removeWidget(it) }
        pathfindingSectionButtons.clear()
        listeningForAutoClickerKey = false
        listeningForInventoryAutoClickerKey = false
        listeningForQuietModeKey = false

        val px = px()
        val all = getSearchableEntries(px)
        val q = query.lowercase().trim()
        val matched = all.filter { it.searchKey.contains(q) }

        val items = mutableListOf<ContentItem>()
        if (matched.isEmpty()) {
            items.add(SectionHeader("No results for \"$query\""))
        } else {
            val grouped = matched.groupBy { it.category }
            for ((category, list) in grouped) {
                items.add(SectionHeader(category))
                for (entry in list) {
                    items.add(ToggleRow(entry.cleanLabel, entry.subtitle, entry.get, entry.set))
                }
            }
        }

        currentItems = items
        updateWidgetPositions()
    }

    private fun buildItemsForTab(tab: Tab, px: Int): List<ContentItem> {
        val fullX = px + 16
        val fullW = PANEL_W - 32
        val subX = px + 28
        val subW = PANEL_W - 44

        return when (tab) {
            Tab.QOL -> listOf(
                SectionHeader("HUD & Interface"),
                ToggleRow("Etherwarp Highlight", "Highlights etherwarp target block",
                    { Config.etherwarpEnabled }, { Config.etherwarpEnabled = it }),
                ToggleRow("Pet HUD Display", "Shows active pet on screen",
                    { Config.petDisplayEnabled }, { Config.petDisplayEnabled = it }),
                WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("Edit Pet HUD Position & Scale")) {
                    minecraft.setScreen(PetHudEditorScreen())
                }),
                ToggleRow("Pet Menu Highlight", "Glows active pet in Pets GUI",
                    { Config.petMenuHighlightEnabled }, { Config.petMenuHighlightEnabled = it }),
                SectionHeader("Session"),
                ToggleRow("Quiet Mode", "Draws nothing in-game so a window capture looks vanilla",
                    { Config.quietModeEnabled }, { QuietMode.setEnabled(it) }),
                WidgetRow(run {
                    btnQuietModeKey = ModernButton(subX, 0, subW, 24, Component.literal(quietModeKeyLabel())) {
                        listeningForQuietModeKey = true
                        btnQuietModeKey.message = Component.literal("Press a key (ESC = NONE)")
                    }
                    btnQuietModeKey
                }),
                SectionHeader("Configuration"),
                WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Reset All Settings to Clean Defaults")) {
                    Config.resetToCleanDefaults()
                    rebuildTab(Tab.QOL)
                }),
            )
            Tab.MAP -> listOf(
                SectionHeader("Presets"),
                WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Load Map Preset")) {
                    Config.applyDevonianMapPreset()
                    rebuildTab(Tab.MAP)
                }),
                SectionHeader("Map Display"),
                ToggleRow("Dungeon Map", "On-screen dungeon map HUD",
                    { Config.dungeonMapEnabled }, { Config.dungeonMapEnabled = it }),
                WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("Map Style: " + if (Config.dungeonMapStyle == 1) "Noamm" else "Devonian")) {
                    Config.dungeonMapStyle = if (Config.dungeonMapStyle == 1) 0 else 1
                    init()
                }),
                ToggleRow("Edit Map Position", "Drag the map in game; saves on drop",
                    { Config.dungeonMapEditMode }, {
                        Config.dungeonMapEditMode = it
                        if (it) minecraft.setScreen(com.asthoonlite.dungeon.DungeonMapEditorScreen())
                    }),
                ToggleRow("  ↳ Always Show", "Show map without holding map item",
                    { Config.dungeonMapAlwaysShow }, { Config.dungeonMapAlwaysShow = it }),
                ToggleRow("  ↳ Full Map Overlay", "Cover the HUD map with an external map showing unopened rooms",
                    { Config.dungeonMapFullGrid }, { Config.dungeonMapFullGrid = it }),
                ToggleRow("  ↳ Legit Base", "Draw only what the held map item shows: explored rooms, cleared-room checkmarks, no names or counters",
                    { Config.dungeonMapLegitBase }, { Config.dungeonMapLegitBase = it }),
                ToggleRow("  ↳ Hide Map in Boss", "Automatically hide map during boss fights",
                    { Config.dungeonMapHideInBoss }, { Config.dungeonMapHideInBoss = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 1, 6, Config.dungeonMapScale.toInt(), "Map Scale: ", "x") {
                    Config.dungeonMapScale = it.toFloat()
                    Config.save()
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 1000, Config.dungeonMapX, "Map Position X: ", " px") {
                    Config.dungeonMapX = it
                    Config.save()
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 1000, Config.dungeonMapY, "Map Position Y: ", " px") {
                    Config.dungeonMapY = it
                    Config.save()
                }),
                SectionHeader("Player Tracking"),
                ToggleRow("  ↳ Player Heads", "Render teammate heads on map",
                    { Config.dungeonMapPlayerHeads }, { Config.dungeonMapPlayerHeads = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 5, 30, (Config.dungeonMapPlayerHeadScale * 10).toInt(), "Player Head Scale: ", "0.1x") {
                    Config.dungeonMapPlayerHeadScale = it / 10.0f
                }),
                ToggleRow("    ↳ Direction for Self", "Show a direction marker on your head",
                    { Config.dungeonMapMarkerSelf }, { Config.dungeonMapMarkerSelf = it }),
                WidgetRow(IntSlider(subX + 12, 0, subW - 12, 24, 5, 30, (Config.dungeonMapMarkerScale * 10).toInt(), "Marker Arrow Scale: ", "0.1x") {
                    Config.dungeonMapMarkerScale = it / 10.0f
                }),
                ToggleRow("  ↳ Player Names", "Show player name tags on map",
                    { Config.dungeonMapPlayerNames }, { Config.dungeonMapPlayerNames = it }),
                ToggleRow("    ↳ Only When Holding Leap", "Only show names while holding Spirit Leap",
                    { Config.dungeonMapNamesOnlyLeap }, { Config.dungeonMapNamesOnlyLeap = it }),
                ToggleRow("  ↳ All Map Markers", "Draw every decoration from the map packet, including mob and waypoint markers (off = teammate markers only)",
                    { Config.dungeonMapAllDecorations }, { Config.dungeonMapAllDecorations = it }),
                SectionHeader("Room Labels & Secrets"),
                ToggleRow("  ↳ Show Room Names", "Display room titles on the map",
                    { Config.dungeonMapShowNames }, { Config.dungeonMapShowNames = it }),
                ToggleRow("    ↳ Hide Common Room Names", "Only show special, puzzle, and trap room names",
                    { Config.dungeonMapDontRenderCommonNames }, { Config.dungeonMapDontRenderCommonNames = it }),
                ToggleRow("    ↳ Hide Yellow Room Name", "Don't render name on yellow room",
                    { Config.dungeonMapDontRenderYellowName }, { Config.dungeonMapDontRenderYellowName = it }),
                ToggleRow("  ↳ Show Secret Counts", "Display remaining/total secrets on rooms",
                    { Config.dungeonMapShowSecrets }, { Config.dungeonMapShowSecrets = it }),
                ToggleRow("  ↳ Show Checkmarks", "Display room checkmarks",
                    { Config.dungeonMapShowCheckmarks }, { Config.dungeonMapShowCheckmarks = it }),
                ToggleRow("    ↳ Hide Fairy Checkmark", "Don't render checkmark in fairy room",
                    { Config.dungeonMapDontRenderFairyCheckmark }, { Config.dungeonMapDontRenderFairyCheckmark = it }),
            )
            Tab.TERMINALS -> listOf(
                // Grouped by what the row is *for*: what runs, when it clicks,
                // how it moves, what it looks like. Two controls that had to
                // agree with a third became one — Delay Spread is the whole
                // Min/Max window centred on Click Delay — the duplicated First
                // Click Delay row is gone, and Melody moved out of the timing
                // list into a section of its own, which is where its rows were
                // reading from all along.
                SectionHeader("Solver & Automation"),
                WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Start Terminal Simulator")) {
                    com.asthoonlite.dungeon.simulator.TerminalSimulator.start()
                }),
                NoteRow("Join a world to practice. Records every terminal; next random board 1 second after completion. Esc stops."),
                ToggleRow("Use Auto Terminal in Simulator", "Uses your enabled Auto Terminal settings instead of manual practice",
                    { Config.terminalSimulatorAutoEnabled }, { Config.terminalSimulatorAutoEnabled = it }),
                ToggleRow("No Melody in Simulator", "Excludes Melody from simulator rounds",
                    { Config.terminalSimulatorNoMelody }, { Config.terminalSimulatorNoMelody = it }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 500, Config.terminalSimulatorPingMs, "Simulator Ping: ", " ms") {
                    Config.terminalSimulatorPingMs = it
                }),
                ToggleRow("Record Terminal Inputs", "Records precise inputs and slot state from real terminals to logs/asthoonlite/terminals; simulator recording is always on",
                    { Config.terminalInputLoggingEnabled }, { Config.terminalInputLoggingEnabled = it }),
                WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Load AutoTerm Preset")) {
                    Config.applyRsmAutoPreset()
                    rebuildTab(Tab.TERMINALS)
                }),
                ToggleRow("Auto Terminal", "Automatically clicks the correct terminal buttons",
                    { Config.autoTerminalEnabled }, { Config.autoTerminalEnabled = it }),
                ToggleRow("  ↳ Run Anywhere (P3 Sim)", "Also runs outside a real dungeon — the terminal title is all the identification needed, so the p3 simulator and practice worlds work",
                    { Config.autoTerminalAnywhere }, { Config.autoTerminalAnywhere = it }),
                WidgetRow(terminalModeButton(fullX, fullW)),
                NoteRow(TerminalMode.description(Config.autoTerminalMode)),
                ToggleRow("Terminal Solver", "Displays custom terminal GUI and highlights correct clicks",
                    { Config.terminalSolverEnabled }, { Config.terminalSolverEnabled = it }),
                ToggleRow("Terminal Progress", "Shows the terminal's name and how far through it the clicker is, centred near the top while a terminal is open",
                    { Config.autoTerminalHudProgress }, { Config.autoTerminalHudProgress = it }),
                SectionHeader("Terminal Interaction & Aura"),
                ToggleRow("Terminal Highlight", "Highlights the clickable interaction hitbox in front of terminals",
                    { Config.terminalHighlightEnabled }, { Config.terminalHighlightEnabled = it }),
                ToggleRow("Terminal Triggerbot", "Automatically clicks terminal when looking at its interaction hitbox within range",
                    { Config.terminalTriggerBotEnabled }, { Config.terminalTriggerBotEnabled = it }),
                WidgetRow(FloatSlider(subX, 0, subW, 24, 1.0f, 30.0f, Config.terminalTriggerBotCooldown.toFloat(), "Terminal Triggerbot Cooldown: ", "s") {
                    Config.terminalTriggerBotCooldown = it.toDouble()
                }),
                ToggleRow("Terminal Aura", "Automatically opens terminals within reach and FOV",
                    { Config.terminalAuraEnabled }, { Config.terminalAuraEnabled = it }),
                WidgetRow(FloatSlider(subX, 0, subW, 24, 2.0f, 6.0f, Config.terminalAuraRange.toFloat(), "Terminal Aura Range: ", " blocks") {
                    Config.terminalAuraRange = it.toDouble()
                }),
                SectionHeader(if (Config.autoTerminalMode == TerminalMode.LEGIT) "Click Timing (Locked in Legit Mode)" else "Click Timing"),
                ToggleRow("Random Delay", "Humanized random delays between clicks",
                    { Config.autoTerminalRandomDelay }, { Config.autoTerminalRandomDelay = it }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 1000, Config.autoTerminalClickDelayMs, "Click Delay: ", " ms") {
                    Config.autoTerminalClickDelayMs = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 500, Config.autoTerminalDelaySpreadMs, "Delay Spread: ±", " ms") {
                    Config.autoTerminalDelaySpreadMs = it
                }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 1000, Config.autoTerminalFirstClickDelayMs, "First Click Delay: ", " ms") {
                    Config.autoTerminalFirstClickDelayMs = it
                }),
                NoteRow("Click Delay is the centre of every beat and Delay Spread is how far either side of it one beat may stray. One number instead of a Min and a Max that had to be kept in step with it, and the beat cannot end up outside a range that ignores what you set."),
                SectionHeader("Melody"),
                ToggleRow("Melody Skip", "Skips subsequent Melody rows on correct timing",
                    { Config.autoTerminalMelodySkip }, { Config.autoTerminalMelodySkip = it }),
                ToggleRow("  ↳ Don't Skip First Row", "Waits for first row before skipping",
                    { Config.autoTerminalDontSkipFirst }, { Config.autoTerminalDontSkipFirst = it }),
                ToggleRow("Announce Melody in Chat", "Sends party chat message when opening Melody",
                    { Config.autoTerminalAnnounceMelody }, { Config.autoTerminalAnnounceMelody = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 1000, Config.autoTerminalMelodyFirstClickDelayMs, "Melody First Click Delay: ", " ms") {
                    Config.autoTerminalMelodyFirstClickDelayMs = it
                }),
                SectionHeader("Pointer"),
                ToggleRow("Glide Pointer", "Draws a pointer that travels between panes while clicks follow the terminal timing",
                    { Config.autoTerminalCursorGlide }, { Config.autoTerminalCursorGlide = it }),
                WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("Cursor Style: " + if (Config.autoTerminalCursorStyle == 1) "Default (User Cursor)" else "Osu")) {
                    Config.autoTerminalCursorStyle = if (Config.autoTerminalCursorStyle == 0) 1 else 0
                    init()
                }),
                ToggleRow("  ↳ Hide Real Cursor", "Steps the real cursor aside while the drawn pointer is on screen",
                    { Config.autoTerminalCursorHideReal }, { Config.autoTerminalCursorHideReal = it }),
                ToggleRow("  ↳ Glide On Melody", "Glides to the next row immediately after a click, without waiting for the row update",
                    { Config.autoTerminalCursorMelody }, { Config.autoTerminalCursorMelody = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 25, 400, Config.autoTerminalCursorSpeed, "Pointer Speed: ", "%") {
                    Config.autoTerminalCursorSpeed = it
                }),
                NoteRow("100% = natural hand speed. Higher is snappier. The trip is fitted into the beat, so Pointer Speed changes how it looks, never how fast the terminal runs — that is Click Delay."),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalHumanize, "Humanize: ", "%") {
                    Config.autoTerminalHumanize = it
                }),
                NoteRow("Humanize scales how much everything below varies: timing, arc, tremor, curve, the pause before moving, and carrying past a pane before settling. 0 is a machine — identical hops every time."),
                SectionHeader("Pointer Fine Tuning"),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalCursorArc, "Pointer Arc: ", "%") {
                    Config.autoTerminalCursorArc = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalCursorJitter, "Pointer Tremor: ", "%") {
                    Config.autoTerminalCursorJitter = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalEaseX1, "Acceleration X1: ", "%") {
                    Config.autoTerminalEaseX1 = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalEaseY1, "Acceleration Y1: ", "%") {
                    Config.autoTerminalEaseY1 = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalEaseX2, "Landing X2: ", "%") {
                    Config.autoTerminalEaseX2 = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalEaseY2, "Landing Y2: ", "%") {
                    Config.autoTerminalEaseY2 = it
                }),
                SectionHeader("Custom Terminal GUI"),
                ToggleRow("Custom Terminal GUI", "Renders clean custom centered grid overlay for terminals",
                    { Config.termGuiEnabled }, { Config.termGuiEnabled = it }),
                WidgetRow(FloatSlider(subX, 0, subW, 24, 1.0f, 3.0f, Config.termGuiSize, "Term Size: ", "x") {
                    Config.termGuiSize = it
                }),
                WidgetRow(FloatSlider(subX, 0, subW, 24, 1.0f, 3.0f, Config.termGuiMelodySize, "Melody Size: ", "x") {
                    Config.termGuiMelodySize = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 8, Config.termGuiGap, "Tile Gap: ", " px") {
                    Config.termGuiGap = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 15, Config.termGuiRoundness, "Roundness: ", " px") {
                    Config.termGuiRoundness = it
                }),
                NoteRow("Melody carries its own size — five rows of seven does not fit at the term size."),
                ToggleRow("Click Flash", "Marks the pane a click was for, fading out over the same instant the click lands",
                    { Config.termGuiClickFlash }, { Config.termGuiClickFlash = it }),
                SectionHeader("Terminal Types — all on by default"),
                ToggleRow("Automate Colours", "Solves 'Select all the X items'",
                    { Config.autoTermColors }, { Config.autoTermColors = it }),
                ToggleRow("Automate Melody", "Solves 'Click the button on time!'",
                    { Config.autoTermMelody }, { Config.autoTermMelody = it }),
                ToggleRow("Automate Numbers", "Solves 'Click in order!'",
                    { Config.autoTermNumbers }, { Config.autoTermNumbers = it }),
                ToggleRow("Automate Red-Green", "Solves 'Correct all the panes!'",
                    { Config.autoTermRedGreen }, { Config.autoTermRedGreen = it }),
                ToggleRow("Automate Rubix", "Solves 'Change all to same color!'",
                    { Config.autoTermRubix }, { Config.autoTermRubix = it }),
                ToggleRow("Automate Starts With", "Solves 'What starts with: X'",
                    { Config.autoTermStartsWith }, { Config.autoTermStartsWith = it }),
            )
            Tab.PATHFINDING -> buildPathfindingItems(px)
            Tab.HITBOXES -> listOf(
                SectionHeader("Secret Hitbox Toggles"),
                ToggleRow("Secret Hitboxes", "Enlarged clickboxes for dungeon secrets",
                    { Config.secretHitboxesEnabled }, { Config.secretHitboxesEnabled = it }),
                ToggleRow("  ↳ Run Anywhere", "Also applies outside Catacombs (housing, practice worlds, singleplayer)",
                    { Config.secretHitboxAnywhere }, { Config.secretHitboxAnywhere = it }),
                ToggleRow("  ↳ Lever Hitbox", "Hitbox matching the 3D lever target area",
                    { Config.leverHitboxEnabled }, { Config.leverHitboxEnabled = it }),
                ToggleRow("  ↳ Button Hitbox", "Enlarged button target area",
                    { Config.buttonHitboxEnabled }, { Config.buttonHitboxEnabled = it }),
                ToggleRow("  ↳ Skull Hitbox", "Full block Wither Essence skull hitbox",
                    { Config.skullHitboxEnabled }, { Config.skullHitboxEnabled = it }),
                ToggleRow("  ↳ Mushroom Hitbox", "Full block Mushroom hitbox",
                    { Config.mushroomHitboxEnabled }, { Config.mushroomHitboxEnabled = it }),
                SectionHeader("Hitbox Sizes"),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.secretLeverHitboxSize, "Lever Size: ", "%") {
                    Config.secretLeverHitboxSize = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.secretButtonHitboxSize, "Button Size: ", "%") {
                    Config.secretButtonHitboxSize = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.secretSkullHitboxSize, "Skull Size: ", "%") {
                    Config.secretSkullHitboxSize = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.secretMushroomHitboxSize, "Mushroom Size: ", "%") {
                    Config.secretMushroomHitboxSize = it
                }),
                NoteRow("Buttons keep their real depth — only length and width grow."),
                SectionHeader("Hitbox Visuals & Outline"),
                ToggleRow("Show 3D Hitbox Boxes", "Renders custom 3D boxes in-game",
                    { Config.moddedHitboxDisplayEnabled }, { Config.moddedHitboxDisplayEnabled = it }),
                ToggleRow("  ↳ Through Walls", "Renders 3D boxes through walls so recessed secrets stay visible",
                    { Config.secretHitboxThroughWalls }, { Config.secretHitboxThroughWalls = it }),
                ToggleRow("Legit Selection Outline", "Shows vanilla outline when looking at blocks",
                    { Config.secretHitboxVanillaOutline }, { Config.secretHitboxVanillaOutline = it }),
                ToggleRow("Hide Selection Outline", "Completely hide the in-game black selection outline",
                    { Config.secretHitboxHideOutline }, { Config.secretHitboxHideOutline = it }),
                SectionHeader("Interaction Feedback"),
                ToggleRow("Pressed Hitbox", "Briefly shows true control shape when activated",
                    { Config.pressedHitboxEnabled }, { Config.pressedHitboxEnabled = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 100, 2000, Config.pressedHitboxDuration, "Pressed Hitbox Duration: ", " ms") {
                    Config.pressedHitboxDuration = it
                }),
            )
            Tab.DUNGEON -> when (activeDungeonSection) {
                DungeonSection.GENERAL -> listOf(
                    SectionHeader("Shortcuts"),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Open Map Settings →")) {
                        scrollOffset = 0
                        rebuildTab(Tab.MAP)
                    }),
                    SectionHeader("Room Clear Alerts"),
                    ToggleRow("Room Cleared Alert", "Chime and banner alert when room is cleared",
                        { Config.roomClearAlertEnabled }, { Config.roomClearAlertEnabled = it }),
                    ToggleRow("  ↳ No Blood Room Alert", "Don't send room clear alert in Blood Room",
                        { Config.roomClearNoBlood }, { Config.roomClearNoBlood = it }),
                    ToggleRow("  ↳ Only Show For Blood Rush", "Only alert if room has wither or blood door",
                        { Config.roomClearOnlyKey }, { Config.roomClearOnlyKey = it }),
                    SectionHeader("Starred Mob ESP"),
                    ToggleRow("Starred Mob ESP", "Highlights all starred mobs and minibosses",
                        { Config.starMobEspEnabled }, { Config.starMobEspEnabled = it }),
                    WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("Star Mob Mode: " + if (Config.starMobRenderMode == 1) "Box" else "Fill")) {
                        Config.starMobRenderMode = if (Config.starMobRenderMode == 1) 0 else 1
                        init()
                    }),
                    ToggleRow("  ↳ Through Walls", "Show starred mob boxes through blocks",
                        { Config.starMobEspThroughWalls }, { Config.starMobEspThroughWalls = it }),
                    ToggleRow("  ↳ Color By Mob Type", "Color-code starred mob categories",
                        { Config.starMobEspByType }, { Config.starMobEspByType = it }),
                    ToggleRow("  ↳ Show Full Shadow", "Show full hitbox of invisible Shadow Assassins",
                        { Config.starMobShowFullShadow }, { Config.starMobShowFullShadow = it }),
                    ToggleRow("  ↳ Bat ESP", "Highlight secret and dungeon bats",
                        { Config.starMobEspBats }, { Config.starMobEspBats = it }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 1, 10, Config.starMobLineWidth.toInt(), "Star Mob Line Width: ", " px") {
                        Config.starMobLineWidth = it.toDouble()
                    }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, (Config.starMobFillAlpha * 100).toInt(), "Star Mob Fill Alpha: ", "%") {
                        Config.starMobFillAlpha = it / 100.0
                    }),
                )
                DungeonSection.PUZZLES -> listOf(
                    SectionHeader("Bulk Controls"),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Turn On All Puzzle Solvers")) {
                        Config.enableAllPuzzleSolvers()
                        rebuildTab(Tab.DUNGEON)
                    }),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Turn Off All Puzzle Solvers")) {
                        Config.disableAllPuzzleSolvers()
                        rebuildTab(Tab.DUNGEON)
                    }),
                    SectionHeader("Puzzle Solvers"),
                    ToggleRow("Quiz Solver", "Shows the correct trivia answer in chat",
                        { Config.quizSolverEnabled }, { Config.quizSolverEnabled = it }),
                    ToggleRow("Three Weirdos", "Highlights the NPC with the true statement",
                        { Config.weirdosSolverEnabled }, { Config.weirdosSolverEnabled = it }),
                    ToggleRow("Higher / Lower", "Detects Higher vs Lower Blaze and highlights order",
                        { Config.higherLowerSolverEnabled }, { Config.higherLowerSolverEnabled = it }),
                    ToggleRow("Tic Tac Toe", "Minimax solver, prevents wrong button clicks",
                        { Config.ticTacToeSolverEnabled }, { Config.ticTacToeSolverEnabled = it }),
                    ToggleRow("Ice Fill", "Calculates optimal 3-cluster ice path",
                        { Config.iceFillSolverEnabled }, { Config.iceFillSolverEnabled = it }),
                    ToggleRow("Ice Path", "Tracks Silverfish and renders sliding ice path",
                        { Config.icePathSolverEnabled }, { Config.icePathSolverEnabled = it }),
                    ToggleRow("Water Board", "Solves water board levers with countdown timer",
                        { Config.waterBoardSolverEnabled }, { Config.waterBoardSolverEnabled = it }),
                    ToggleRow("Boulder", "Highlights box pushing solution steps",
                        { Config.boulderSolverEnabled }, { Config.boulderSolverEnabled = it }),
                    ToggleRow("Creeper Beams", "Pairs sea lanterns with matching colored beams",
                        { Config.creeperBeamSolverEnabled }, { Config.creeperBeamSolverEnabled = it }),
                    ToggleRow("Teleport Maze", "Tracks pads and highlights correct teleport paths",
                        { Config.teleportMazeSolverEnabled }, { Config.teleportMazeSolverEnabled = it }),
                    ToggleRow("Livid Solver", "Finds the correct Livid based on ceiling wool",
                        { Config.lividSolverEnabled }, { Config.lividSolverEnabled = it }),
                )
                DungeonSection.F7M7 -> listOf(
                    SectionHeader("Presets & Shortcuts"),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Load Watcher Preset")) {
                        Config.applyDevonianWatcherPreset()
                        rebuildTab(Tab.DUNGEON)
                    }),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Open Terminal Settings →")) {
                        scrollOffset = 0
                        rebuildTab(Tab.TERMINALS)
                    }),
                    SectionHeader("Blood & Watcher Helper"),
                    ToggleRow("Blood / Watcher Solver", "Watcher and blood mob drop timers",
                        { Config.bloodRoomSolverEnabled }, { Config.bloodRoomSolverEnabled = it }),
                    ToggleRow("  ↳ Show Countdown Timer", "Displays seconds until mob drops/spawns",
                        { Config.campHelperShowTimer }, { Config.campHelperShowTimer = it }),
                    ToggleRow("  ↳ Play Sound Alert", "Chime when mob is about to drop",
                        { Config.campHelperPlaySound }, { Config.campHelperPlaySound = it }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 5, 30, (Config.campHelperSoundThreshold * 10).toInt(), "Watcher Sound Alert: ", "0.1s") {
                        Config.campHelperSoundThreshold = it / 10.0
                    }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 1, 5, Config.campHelperLineWidth.toInt(), "Watcher Box Line Width: ", " px") {
                        Config.campHelperLineWidth = it.toDouble()
                    }),
                    SectionHeader("Boss Phases & Timers"),
                    ToggleRow("F7 / M7 Tick Timers", "Run split timer, phase tracker and alerts",
                        { Config.dungeonTickTimersEnabled }, { Config.dungeonTickTimersEnabled = it }),
                    ToggleRow("M7 Dragon Phase", "Dragon boxes, spawn timers, priority and health",
                        { Config.dragonPhaseEnabled }, { Config.dragonPhaseEnabled = it }),
                    ToggleRow("  ↳ Power Priority", "Use higher-power dragon priority order",
                        { Config.dragonPowerPriority }, { Config.dragonPowerPriority = it }),
                    SectionHeader("Devices & Terminals"),
                    ToggleRow("Auto I4 / Sharpshooter", "Automatically solves the F7 fourth device",
                        { Config.autoI4Enabled }, { Config.autoI4Enabled = it }),
                    ToggleRow("Auto Simon Says", "Automatically solves Simon Says, looking at each button with human camera movement and timing",
                        { Config.autoSimonSaysEnabled }, { Config.autoSimonSaysEnabled = it }),
                    ToggleRow("  ↳ Fast Mode", "Increases rotation speed and click cadence for Simon Says",
                        { Config.autoSimonSaysFast }, { Config.autoSimonSaysFast = it }),
                    ToggleRow("Block Wrong Device Clicks", "Prevents clicking incorrect Simon Says buttons or completed/wrong arrows",
                        { Config.blockWrongDeviceClicks }, { Config.blockWrongDeviceClicks = it }),
                    ToggleRow("Mask Display", "Bonzo, Spirit, and Phoenix mask cooldown HUD",
                        { Config.maskDisplayEnabled }, { Config.maskDisplayEnabled = it }),
                )
                DungeonSection.SECRETS -> listOf(
                    SectionHeader("Shortcuts"),
                    WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Open Hitbox Settings →")) {
                        scrollOffset = 0
                        rebuildTab(Tab.HITBOXES)
                    }),
                    SectionHeader("Notifications & Audio"),
                    ToggleRow("Room Clear Alert", "Alert when a room is cleared",
                        { Config.roomClearAlertEnabled }, { Config.roomClearAlertEnabled = it }),
                    ToggleRow("Secrets Done Alert", "Alert when all secrets in room are collected",
                        { Config.roomSecretAlertEnabled }, { Config.roomSecretAlertEnabled = it }),
                    ToggleRow("Secret Sound", "Plays sound effect when secret is clicked/collected",
                        { Config.secretSoundEnabled }, { Config.secretSoundEnabled = it }),
                    ToggleRow("Item Secret Pickup Sound", "Light chime when floor secret is collected",
                        { Config.secretItemPickupSoundEnabled }, { Config.secretItemPickupSoundEnabled = it }),
                    SectionHeader("Secret Interaction & Aura"),
                    ToggleRow("Secret Triggerbot", "Auto-click levers, buttons, skulls, and secrets when looking at them",
                        { Config.secretTriggerBotEnabled }, { Config.secretTriggerBotEnabled = it }),
                    WidgetRow(FloatSlider(subX, 0, subW, 24, 1.0f, 30.0f, Config.secretTriggerBotCooldown.toFloat(), "Secret Triggerbot Cooldown: ", "s") {
                        Config.secretTriggerBotCooldown = it.toDouble()
                    }),
                    ToggleRow("  ↳ Block Wrong Simon Clicks", "Prevents clicking or triggerbot from failing Simon Says on wrong buttons",
                        { Config.blockWrongDeviceClicks }, { Config.blockWrongDeviceClicks = it }),
                    ToggleRow("Auto-Close Secret Chest", "Instantly closes secret chest GUI on open",
                        { Config.autoCloseSecretChest }, { Config.autoCloseSecretChest = it }),
                    ToggleRow("Secret Aura", "Auto-interact with secrets in configured range and FOV",
                        { Config.secretAuraEnabled }, { Config.secretAuraEnabled = it }),
                    ToggleRow("  ↳ Through Walls", "Allows secret aura to interact with secrets through walls and obstacles",
                        { Config.secretAuraThroughWalls }, { Config.secretAuraThroughWalls = it }),
                    ToggleRow("  ↳ FOV / Range Visualizer", "Renders circle and FOV cone displaying the configured pickup radius",
                        { Config.secretAuraVisualizer }, { Config.secretAuraVisualizer = it }),
                    ToggleRow("  ↳ Break Block Secrets", "Allow aura to break mushroom blocks",
                        { Config.secretAuraBreakBlocks }, { Config.secretAuraBreakBlocks = it }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 1, 20, Config.secretAuraRange, "Secret Aura Range: ", " blocks") {
                        Config.secretAuraRange = it
                    }),
                    WidgetRow(IntSlider(subX, 0, subW, 24, 5, 180, Config.secretAuraFov, "Secret Aura FOV: ", "°") {
                        Config.secretAuraFov = it
                    }),
                    ToggleRow("Relic Aura", "Highlight matching M7 relic and destination cauldron",
                        { Config.relicAuraEnabled }, { Config.relicAuraEnabled = it }),
                )
            }
            Tab.MINING -> listOf(
                SectionHeader("Mining Utilities"),
                ToggleRow("Pickaxe Ability Timer", "Cooldown bar and alert for pickaxe abilities",
                    { Config.pickaxeAbilityTimerEnabled }, { Config.pickaxeAbilityTimerEnabled = it }),
            )
            Tab.FISHING -> listOf(
                SectionHeader("Fishing Utilities"),
                ToggleRow("Fish Bite Alert", "Alerts when the bobber dips",
                    { Config.fishBiteAlertEnabled }, { Config.fishBiteAlertEnabled = it }),
            )
            Tab.NUCLEUS -> listOf(
                SectionHeader("Crystal Nucleus"),
                ToggleRow("Auto Nucleus Warp", "Warps to the Crystal Nucleus when found",
                    { Config.autoNucleusWarpEnabled }, { Config.autoNucleusWarpEnabled = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 50, (Config.autoNucleusWarpMinSec * 10).toInt(), "Min Delay: ", "0.1s") {
                    Config.autoNucleusWarpMinSec = it / 10.0
                    Config.save()
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 50, (Config.autoNucleusWarpMaxSec * 10).toInt(), "Max Delay: ", "0.1s") {
                    Config.autoNucleusWarpMaxSec = it / 10.0
                    Config.save()
                }),
            )
            Tab.FUNNY -> listOf(
                SectionHeader("Right-Click Autoclicker"),
                ToggleRow("Autoclicker", "Right-click autoclicker at configured CPS",
                    { Config.autoClickerEnabled }, { Config.autoClickerEnabled = it }),
                WidgetRow(run {
                    btnAutoClickerKey = ModernButton(subX, 0, subW, 24, Component.literal(autoClickerKeyLabel())) {
                        listeningForAutoClickerKey = true
                        btnAutoClickerKey.message = Component.literal("Press a key (ESC = NONE)")
                    }
                    btnAutoClickerKey
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 1, 500, Config.autoClickerCps, "Autoclicker CPS: ", " CPS") {
                    Config.autoClickerCps = it
                }),

                SectionHeader("Weapon Left-Click Macro"),
                ToggleRow("Weapon Left-Click", "Simulates ~7 CPS left clicks when holding left click with Terminator, Claymore, Hyperion, or Slayer weapons",
                    { Config.weaponAutoClickerEnabled }, { Config.weaponAutoClickerEnabled = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 4, 15, Config.weaponAutoClickerCps, "Weapon CPS: ", " CPS") {
                    Config.weaponAutoClickerCps = it
                }),

                SectionHeader("Inventory Stash Macro"),
                ToggleRow("Inventory Left-Click", "Simulates lower CPS humanized clicks when holding left click over inventory or stash items",
                    { Config.inventoryAutoClickerEnabled }, { Config.inventoryAutoClickerEnabled = it }),
                WidgetRow(run {
                    btnInventoryAutoClickerKey = ModernButton(subX, 0, subW, 24, Component.literal(inventoryAutoClickerKeyLabel())) {
                        listeningForInventoryAutoClickerKey = true
                        btnInventoryAutoClickerKey.message = Component.literal("Press a key (ESC = NONE)")
                    }
                    btnInventoryAutoClickerKey
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 2, 12, Config.inventoryAutoClickerCps, "Inventory CPS: ", " CPS") {
                    Config.inventoryAutoClickerCps = it
                }),
            )
        }
    }

    private fun buildPathfindingItems(px: Int): List<ContentItem> {
        val fullX = px + 16
        val fullW = PANEL_W - 32
        val subX = px + 28
        val subW = PANEL_W - 44

        val items = mutableListOf<ContentItem>()

        when (activePathfindingSection) {
            PathfindingSection.USER_PRESETS -> {
                items.add(SectionHeader("Pathfinding System"))
                items.add(ToggleRow("Pathfinding System", "Enable global pathfinding routing and execution",
                    { Config.pathfindingEnabled }, { Config.pathfindingEnabled = it }))
                items.add(ToggleRow("  ↳ Route Visualizer", "Renders 3D waypoints and path lines in world",
                    { Config.pathfindingDebugRender }, { Config.pathfindingDebugRender = it }))

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("+ Create New Route Preset"), 0xFF10B981.toInt()) {
                    activePathfindingSection = PathfindingSection.CREATE_PRESET
                    createDropdownOpen = false
                    rebuildTab(Tab.PATHFINDING)
                }))

                val presets = PathPresetManager.getPresets()
                items.add(SectionHeader("Saved User Presets (${presets.size})"))
                if (presets.isEmpty()) {
                    items.add(NoteRow("0 Presets found. Click '+ Create New Route Preset' above to create one."))
                } else {
                    for (preset in presets) {
                        val cat = preset.routeCategory()
                        val isActive = Config.activePathfindingPresetId == preset.id

                        items.add(SectionHeader("${cat.displayName} ✦ ${preset.name}"))
                        items.add(NoteRow("Category: ${preset.category}  •  Subcategory: ${preset.subcategory}  •  Nodes: ${preset.points.size}"))

                        val btnW = (subW - 12) / 4
                        val selectBtnText = if (isActive) "Active" else "Inactive"
                        val selectBtnAccent = if (isActive) 0xFF10B981.toInt() else 0xFF475569.toInt()
                        val btnSelect = ModernButton(subX, 0, btnW, 22, Component.literal(selectBtnText), selectBtnAccent) {
                            if (isActive) {
                                Config.activePathfindingPresetId = ""
                                PathExecutor.stop()
                                minecraft.player?.sendSystemMessage(
                                    Component.literal("§e[AsthoonLite] §fRoute §6\"${preset.name}\" §cDEACTIVATED§f.")
                                )
                            } else {
                                Config.activePathfindingPresetId = preset.id
                                Config.pathfindingEnabled = true
                                Config.pathfindingDebugRender = true
                                minecraft.player?.sendSystemMessage(
                                    Component.literal("§a[AsthoonLite] §fRoute §e\"${preset.name}\" §2ACTIVATED§f. Starting navigation to node #1...")
                                )
                            }
                            rebuildTab(Tab.PATHFINDING)
                        }
                        val btnEdit = ModernButton(subX + btnW + 4, 0, btnW, 22, Component.literal("Edit Route"), 0xFFEAB308.toInt()) {
                            RouteEditor.activePreset = preset
                            RouteEditor.editingNodeIndex = -1
                            activePathfindingSection = PathfindingSection.ROUTE_EDITOR
                            rebuildTab(Tab.PATHFINDING)
                        }
                        val btnExport = ModernButton(subX + (btnW + 4) * 2, 0, btnW, 22, Component.literal("Export JSON"), 0xFF38BDF8.toInt()) {
                            val json = PathPresetManager.exportToJson(preset)
                            PathPresetManager.copyToClipboard(json)
                            minecraft.player?.sendSystemMessage(Component.literal("§a[AsthoonLite] §fCopied preset §e\"${preset.name}\" §fto clipboard!"))
                            exportFeedbackMsg = "Copied \"${preset.name}\" to clipboard!"
                        }
                        val btnDelete = ModernButton(subX + (btnW + 4) * 3, 0, btnW, 22, Component.literal("Delete"), 0xFFEF4444.toInt()) {
                            PathPresetManager.deletePreset(preset.id)
                            if (Config.activePathfindingPresetId == preset.id) {
                                Config.activePathfindingPresetId = ""
                                PathExecutor.stop()
                            }
                            if (RouteEditor.activePreset?.id == preset.id) {
                                RouteEditor.finishEditing()
                            }
                            rebuildTab(Tab.PATHFINDING)
                        }
                        items.add(MultiWidgetRow(listOf(btnSelect, btnEdit, btnExport, btnDelete), 22))
                    }
                }
            }

            PathfindingSection.CREATE_PRESET -> {
                items.add(SectionHeader("1. Select Route Category"))

                val catW = (subW - 12) / 4
                val catButtons = RouteCategory.entries.mapIndexed { idx, cat ->
                    val isSelected = createCategory == cat
                    val label = if (isSelected) "● ${cat.displayName}" else cat.displayName
                    ModernButton(subX + idx * (catW + 4), 0, catW, 24, Component.literal(label), cat.color) {
                        createCategory = cat
                        createSubcategory = cat.getSubcategories().firstOrNull()?.name ?: ""
                        createDropdownOpen = false
                        createPresetName = "${cat.displayName} ${createSubcategory} Route".trim()
                        rebuildTab(Tab.PATHFINDING)
                    }
                }
                items.add(MultiWidgetRow(catButtons, 24))

                items.add(SectionHeader("2. Select Subcategory (${createCategory.displayName})"))
                val subs = createCategory.getSubcategories()
                if (subs.isEmpty()) {
                    items.add(NoteRow("Dungeons category currently has no subcategories (blank)."))
                } else {
                    val currentSubColor = subs.firstOrNull { it.name.equals(createSubcategory, ignoreCase = true) }?.color ?: createCategory.color
                    val dropdownLabel = if (createDropdownOpen) "▲ Subcategory: $createSubcategory (Click to close)" else "▼ Subcategory: $createSubcategory (Click to choose)"
                    items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal(dropdownLabel), currentSubColor) {
                        createDropdownOpen = !createDropdownOpen
                        rebuildTab(Tab.PATHFINDING)
                    }))

                    if (createDropdownOpen) {
                        if (createCategory == RouteCategory.M7) {
                            val pW = (subW - 16) / 5
                            val pButtons = subs.mapIndexed { idx, sub ->
                                val isSel = createSubcategory.equals(sub.name, ignoreCase = true)
                                val text = if (isSel) "● ${sub.name}" else sub.name
                                ModernButton(subX + idx * (pW + 4), 0, pW, 22, Component.literal(text), sub.color) {
                                    createSubcategory = sub.name
                                    createDropdownOpen = false
                                    createPresetName = "M7 ${sub.name} Route"
                                    rebuildTab(Tab.PATHFINDING)
                                }
                            }
                            items.add(MultiWidgetRow(pButtons, 22))
                        } else {
                            for (sub in subs) {
                                val isSel = createSubcategory.equals(sub.name, ignoreCase = true)
                                val text = if (isSel) "● ${sub.name} (Selected)" else "✦ ${sub.name}"
                                items.add(WidgetRow(ModernButton(subX + 12, 0, subW - 12, 22, Component.literal(text), sub.color) {
                                    createSubcategory = sub.name
                                    createDropdownOpen = false
                                    createPresetName = "${createCategory.displayName} ${sub.name} Route"
                                    rebuildTab(Tab.PATHFINDING)
                                }))
                            }
                        }
                    }
                }

                items.add(SectionHeader("3. Preset Information"))
                val nameBox = EditBox(font, subX, 0, subW, 20, Component.literal("Preset Name"))
                if (createPresetName.isBlank()) {
                    createPresetName = "${createCategory.displayName} ${createSubcategory} Route".trim()
                }
                nameBox.value = createPresetName
                nameBox.setResponder { createPresetName = it }
                items.add(WidgetRow(nameBox))
                items.add(NoteRow("Category: §e${createCategory.displayName} §7• Subcategory: §6${if (createSubcategory.isBlank()) "None" else createSubcategory}"))

                items.add(SectionHeader("4. Save & Open Route Editor"))
                items.add(WidgetRow(ModernButton(subX, 0, subW, 26, Component.literal("✦ Save & Configure Route Nodes"), 0xFF10B981.toInt()) {
                    val finalName = createPresetName.ifBlank { "${createCategory.displayName} Route" }
                    val newPreset = PathPreset(
                        name = finalName,
                        category = createCategory.displayName,
                        subcategory = createSubcategory,
                        description = "Custom route for ${createCategory.displayName} ($createSubcategory)"
                    )
                    PathPresetManager.addPreset(newPreset)
                    Config.activePathfindingPresetId = newPreset.id
                    RouteEditor.activePreset = newPreset
                    minecraft.player?.sendSystemMessage(Component.literal("§a[AsthoonLite] §fCreated preset §e\"$finalName\"§f! Opening Route Node Editor."))
                    activePathfindingSection = PathfindingSection.ROUTE_EDITOR
                    rebuildTab(Tab.PATHFINDING)
                }))
            }

            PathfindingSection.ROUTE_EDITOR -> {
                val preset = RouteEditor.activePreset
                if (preset == null) {
                    items.add(SectionHeader("Route Node Editor - Select Route"))
                    items.add(NoteRow("Select a route preset below to open in the node editor, or create a new route."))
                    val presets = PathPresetManager.getPresets()
                    if (presets.isEmpty()) {
                        items.add(NoteRow("0 Presets available. Click '+ Create New Preset' to begin."))
                        items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("+ Create New Route Preset"), 0xFF10B981.toInt()) {
                            activePathfindingSection = PathfindingSection.CREATE_PRESET
                            rebuildTab(Tab.PATHFINDING)
                        }))
                    } else {
                        for (p in presets) {
                            val cat = p.routeCategory()
                            items.add(SectionHeader("${cat.displayName} ✦ ${p.name}"))
                            items.add(NoteRow("Category: ${p.category}  •  Subcategory: ${p.subcategory}  •  Nodes: ${p.points.size}"))
                            items.add(WidgetRow(ModernButton(subX, 0, subW, 22, Component.literal("✎ Open in Route Editor"), p.subcategoryColor()) {
                                RouteEditor.activePreset = p
                                RouteEditor.editingNodeIndex = -1
                                rebuildTab(Tab.PATHFINDING)
                            }))
                        }
                    }
                } else {
                    val cat = preset.routeCategory()
                    val allowedTypes = RouteNodeType.allowedForCategory(cat)
                    if (addNodeSelectedType !in allowedTypes) {
                        addNodeSelectedType = allowedTypes.first()
                    }
                    if (editNodeSelectedType !in allowedTypes) {
                        editNodeSelectedType = allowedTypes.first()
                    }

                    items.add(SectionHeader("Route: ${preset.name}"))
                    items.add(NoteRow("Category: ${preset.category}  •  Subcategory: ${preset.subcategory}  •  Nodes: ${preset.points.size}"))

                    val btnDoneRoute = ModernButton(subX, 0, subW, 24, Component.literal("✔ Done Editing (Close Route Editor)"), 0xFF10B981.toInt()) {
                        RouteEditor.finishEditing()
                        rebuildTab(Tab.PATHFINDING)
                    }
                    items.add(WidgetRow(btnDoneRoute))

                    // Controls: Run Route / Stop, and In-World Node View Toggle
                    val btnHalfW = (subW - 4) / 2
                    val isRunning = PathExecutor.isActive && PathExecutor.activePreset?.id == preset.id
                    val runBtnText = if (isRunning) "■ Stop Path Execution" else "▶ Run Route in World"
                    val runBtnCol = if (isRunning) 0xFFEF4444.toInt() else 0xFF10B981.toInt()
                    val btnRun = ModernButton(subX, 0, btnHalfW, 24, Component.literal(runBtnText), runBtnCol) {
                        if (isRunning) {
                            PathExecutor.stop()
                            Config.activePathfindingPresetId = ""
                            rebuildTab(Tab.PATHFINDING)
                        } else {
                            Config.activePathfindingPresetId = preset.id
                            Config.pathfindingEnabled = true
                            PathExecutor.start(preset)
                            onClose()
                        }
                    }

                    val nodeViewText = if (RouteEditor.nodeViewMode) "👁 Node View: ON" else "👁 Node View: OFF"
                    val nodeViewCol = if (RouteEditor.nodeViewMode) 0xFF06B6D4.toInt() else 0xFF64748B.toInt()
                    val btnNodeView = ModernButton(subX + btnHalfW + 4, 0, btnHalfW, 24, Component.literal(nodeViewText), nodeViewCol) {
                        RouteEditor.nodeViewMode = !RouteEditor.nodeViewMode
                        rebuildTab(Tab.PATHFINDING)
                    }
                    items.add(MultiWidgetRow(listOf(btnRun, btnNodeView), 24))

                    // ── Add Waypoint Node ────────────────────────────────────
                    items.add(SectionHeader("Add Waypoint Node"))
                    if (addNodeInputIndex.isBlank()) {
                        addNodeInputIndex = (preset.points.size + 1).toString()
                    }

                    val btnAddPos = ModernButton(subX, 0, btnHalfW, 22, Component.literal("+ Add at Player Pos (#$addNodeInputIndex)"), 0xFF38BDF8.toInt()) {
                        val player = minecraft.player
                        if (player != null) {
                            val node = PathPoint(
                                x = Math.round(player.x * 100.0) / 100.0,
                                y = Math.round(player.y * 100.0) / 100.0,
                                z = Math.round(player.z * 100.0) / 100.0,
                                yaw = player.yRot,
                                pitch = player.xRot,
                                action = addNodeSelectedType.name
                            )
                            val targetNum = addNodeInputIndex.toIntOrNull() ?: (preset.points.size + 1)
                            val insertIdx = (targetNum - 1).coerceIn(0, preset.points.size)
                            preset.points.add(insertIdx, node)
                            PathPresetManager.savePresets()
                            addNodeInputIndex = (preset.points.size + 1).toString()
                            rebuildTab(Tab.PATHFINDING)
                        }
                    }

                    val pickText = if (RouteEditor.pickBlockMode) "⌖ Crosshair Pick: ON" else "⌖ Add with Crosshair"
                    val pickCol = if (RouteEditor.pickBlockMode) 0xFF06B6D4.toInt() else 0xFF8B5CF6.toInt()
                    val btnAddCrosshair = ModernButton(subX + btnHalfW + 4, 0, btnHalfW, 22, Component.literal(pickText), pickCol) {
                        RouteEditor.pickBlockMode = !RouteEditor.pickBlockMode
                        if (RouteEditor.pickBlockMode) {
                            minecraft.player?.sendSystemMessage(
                                Component.literal("§a[AsthoonLite] §fCrosshair select mode §2ENABLED§f for route §e\"${preset.name}\"§f. Left-click blocks in the world to add waypoints. Press §bESC§f or type §b/asl routes crosshairselect false§f to exit.")
                            )
                            onClose()
                        } else {
                            minecraft.player?.sendSystemMessage(
                                Component.literal("§e[AsthoonLite] §fCrosshair select mode §cDISABLED§f.")
                            )
                            rebuildTab(Tab.PATHFINDING)
                        }
                    }
                    items.add(MultiWidgetRow(listOf(btnAddPos, btnAddCrosshair), 22))

                    // 5 columns: Node #, X, Y, Z, Type
                    val colW = (subW - 16) / 5
                    val editAddNum = EditBox(font, subX, 0, colW, 20, Component.literal("Node #"))
                    editAddNum.setHint(Component.literal("# in list"))
                    editAddNum.value = addNodeInputIndex
                    editAddNum.setResponder { addNodeInputIndex = it }

                    val editAddX = EditBox(font, subX + colW + 4, 0, colW, 20, Component.literal("X"))
                    editAddX.setHint(Component.literal("X coord"))
                    editAddX.value = addNodeInputX
                    editAddX.setResponder { addNodeInputX = it }

                    val editAddY = EditBox(font, subX + (colW + 4) * 2, 0, colW, 20, Component.literal("Y"))
                    editAddY.setHint(Component.literal("Y coord"))
                    editAddY.value = addNodeInputY
                    editAddY.setResponder { addNodeInputY = it }

                    val editAddZ = EditBox(font, subX + (colW + 4) * 3, 0, colW, 20, Component.literal("Z"))
                    editAddZ.setHint(Component.literal("Z coord"))
                    editAddZ.value = addNodeInputZ
                    editAddZ.setResponder { addNodeInputZ = it }

                    val btnAddType = ModernButton(subX + (colW + 4) * 4, 0, colW, 20, Component.literal(addNodeSelectedType.displayName), addNodeSelectedType.badgeColor) {
                        val nextIdx = (allowedTypes.indexOf(addNodeSelectedType) + 1) % allowedTypes.size
                        addNodeSelectedType = allowedTypes[nextIdx]
                        rebuildTab(Tab.PATHFINDING)
                    }
                    items.add(MultiWidgetRow(listOf(editAddNum, editAddX, editAddY, editAddZ, btnAddType), 20))

                    val btnAddCoords = ModernButton(subX, 0, subW, 22, Component.literal("+ Add Node from Coords into List"), 0xFF10B981.toInt()) {
                        val xVal = addNodeInputX.toDoubleOrNull()
                        val yVal = addNodeInputY.toDoubleOrNull()
                        val zVal = addNodeInputZ.toDoubleOrNull()
                        if (xVal != null && yVal != null && zVal != null) {
                            val node = PathPoint(
                                x = xVal,
                                y = yVal,
                                z = zVal,
                                action = addNodeSelectedType.name
                            )
                            val targetNum = addNodeInputIndex.toIntOrNull() ?: (preset.points.size + 1)
                            val insertIdx = (targetNum - 1).coerceIn(0, preset.points.size)
                            preset.points.add(insertIdx, node)
                            PathPresetManager.savePresets()
                            addNodeInputX = ""
                            addNodeInputY = ""
                            addNodeInputZ = ""
                            addNodeInputIndex = (preset.points.size + 1).toString()
                            rebuildTab(Tab.PATHFINDING)
                        }
                    }
                    items.add(WidgetRow(btnAddCoords))

                    // ── Edit Waypoint Node Section ───────────────────────────
                    if (RouteEditor.editingNodeIndex in preset.points.indices) {
                        val editIdx = RouteEditor.editingNodeIndex
                        val targetNode = preset.points[editIdx]

                        items.add(SectionHeader("Edit Waypoint Node #${editIdx + 1}"))
                        items.add(NoteRow("Select action type, position, look target, or wait duration."))

                        // 1. Position & Coordinates
                        val editColW = (subW - 12) / 4
                        val editNumBox = EditBox(font, subX, 0, editColW, 20, Component.literal("Node #"))
                        editNumBox.setHint(Component.literal("# in list"))
                        if (editNodeInputIndex.isBlank()) editNodeInputIndex = (editIdx + 1).toString()
                        editNumBox.value = editNodeInputIndex
                        editNumBox.setResponder { editNodeInputIndex = it }

                        val editXBox = EditBox(font, subX + editColW + 4, 0, editColW, 20, Component.literal("X"))
                        editXBox.setHint(Component.literal("X coord"))
                        if (editNodeInputX.isBlank()) editNodeInputX = targetNode.x.toString()
                        editXBox.value = editNodeInputX
                        editXBox.setResponder { editNodeInputX = it }

                        val editYBox = EditBox(font, subX + (editColW + 4) * 2, 0, editColW, 20, Component.literal("Y"))
                        editYBox.setHint(Component.literal("Y coord"))
                        if (editNodeInputY.isBlank()) editNodeInputY = targetNode.y.toString()
                        editYBox.value = editNodeInputY
                        editYBox.setResponder { editNodeInputY = it }

                        val editZBox = EditBox(font, subX + (editColW + 4) * 3, 0, editColW, 20, Component.literal("Z"))
                        editZBox.setHint(Component.literal("Z coord"))
                        if (editNodeInputZ.isBlank()) editNodeInputZ = targetNode.z.toString()
                        editZBox.value = editNodeInputZ
                        editZBox.setResponder { editNodeInputZ = it }

                        items.add(MultiWidgetRow(listOf(editNumBox, editXBox, editYBox, editZBox), 20))

                        // 2. Action Type Dropdown
                        val typeDropIcon = if (editTypeDropdownOpen) "▲" else "▼"
                        val btnTypeDropdown = ModernButton(
                            subX, 0, subW, 22,
                            Component.literal("Action Type: ${editNodeSelectedType.displayName} $typeDropIcon"),
                            editNodeSelectedType.badgeColor
                        ) {
                            editTypeDropdownOpen = !editTypeDropdownOpen
                            rebuildTab(Tab.PATHFINDING)
                        }
                        items.add(WidgetRow(btnTypeDropdown))

                        if (editTypeDropdownOpen) {
                            val typePairs = allowedTypes.chunked(2)
                            for (pair in typePairs) {
                                if (pair.size == 2) {
                                    val b1 = ModernButton(subX, 0, (subW - 4) / 2, 20, Component.literal(pair[0].displayName), pair[0].badgeColor) {
                                        editNodeSelectedType = pair[0]
                                        targetNode.action = pair[0].name
                                        PathPresetManager.savePresets()
                                        editTypeDropdownOpen = false
                                        rebuildTab(Tab.PATHFINDING)
                                    }
                                    val b2 = ModernButton(subX + (subW - 4) / 2 + 4, 0, (subW - 4) / 2, 20, Component.literal(pair[1].displayName), pair[1].badgeColor) {
                                        editNodeSelectedType = pair[1]
                                        targetNode.action = pair[1].name
                                        PathPresetManager.savePresets()
                                        editTypeDropdownOpen = false
                                        rebuildTab(Tab.PATHFINDING)
                                    }
                                    items.add(MultiWidgetRow(listOf(b1, b2), 20))
                                } else {
                                    val b1 = ModernButton(subX, 0, subW, 20, Component.literal(pair[0].displayName), pair[0].badgeColor) {
                                        editNodeSelectedType = pair[0]
                                        targetNode.action = pair[0].name
                                        PathPresetManager.savePresets()
                                        editTypeDropdownOpen = false
                                        rebuildTab(Tab.PATHFINDING)
                                    }
                                    items.add(WidgetRow(b1))
                                }
                            }
                        }

                        // 3. Timeout Configuration (if type is TIMEOUT)
                        if (editNodeSelectedType == RouteNodeType.TIMEOUT) {
                            items.add(NoteRow("Timeout Duration (seconds to wait standing at node):"))
                            val timeoutBox = EditBox(font, subX, 0, subW, 20, Component.literal("Timeout (s)"))
                            timeoutBox.setHint(Component.literal("Timeout duration in seconds (e.g. 1.5, 3.0)"))
                            if (editTimeoutSeconds.isBlank()) editTimeoutSeconds = targetNode.timeoutSeconds.toString()
                            timeoutBox.value = editTimeoutSeconds
                            timeoutBox.setResponder {
                                editTimeoutSeconds = it
                                it.toDoubleOrNull()?.let { v ->
                                    targetNode.timeoutSeconds = v
                                    PathPresetManager.savePresets()
                                }
                            }
                            items.add(WidgetRow(timeoutBox))
                        }

                        // 4. Look Node Dropdown
                        val lookSummary = if (targetNode.hasLookNode) {
                            "Active (${String.format("%.1f", targetNode.lookX)}, ${String.format("%.1f", targetNode.lookY)}, ${String.format("%.1f", targetNode.lookZ)})"
                        } else {
                            "None"
                        }
                        val lookDropIcon = if (editLookDropdownOpen) "▲" else "▼"
                        val btnLookDropdown = ModernButton(
                            subX, 0, subW, 22,
                            Component.literal("⌖ Look Node: $lookSummary $lookDropIcon"),
                            if (targetNode.hasLookNode) 0xFF38BDF8.toInt() else 0xFF64748B.toInt()
                        ) {
                            editLookDropdownOpen = !editLookDropdownOpen
                            rebuildTab(Tab.PATHFINDING)
                        }
                        items.add(WidgetRow(btnLookDropdown))

                        if (editLookDropdownOpen) {
                            items.add(NoteRow("Aims camera at 3D target (blocks or mid-air) when approaching/at node."))
                            val btnPickLook = ModernButton(subX, 0, (subW - 4) * 2 / 3, 22, Component.literal("⌖ Pick Target in World/Air"), 0xFF38BDF8.toInt()) {
                                RouteEditor.pickLookNodeMode = true
                                minecraft.setScreen(null)
                                minecraft.player?.sendSystemMessage(
                                    Component.literal("§e[AsthoonLite] §fLook node selection §2ACTIVE§f! §bAim at block or mid-air and LEFT-CLICK §fto set target.")
                                )
                            }
                            val btnClearLook = ModernButton(subX + (subW - 4) * 2 / 3 + 4, 0, (subW - 4) / 3, 22, Component.literal("✕ Clear"), 0xFFEF4444.toInt()) {
                                targetNode.hasLookNode = false
                                editLookInputX = ""
                                editLookInputY = ""
                                editLookInputZ = ""
                                PathPresetManager.savePresets()
                                rebuildTab(Tab.PATHFINDING)
                            }
                            items.add(MultiWidgetRow(listOf(btnPickLook, btnClearLook), 22))

                            val lookColW = (subW - 8) / 3
                            val lookXBox = EditBox(font, subX, 0, lookColW, 20, Component.literal("Look X"))
                            lookXBox.setHint(Component.literal("Look X"))
                            if (editLookInputX.isBlank()) editLookInputX = if (targetNode.hasLookNode) targetNode.lookX.toString() else targetNode.x.toString()
                            lookXBox.value = editLookInputX
                            lookXBox.setResponder {
                                editLookInputX = it
                                it.toDoubleOrNull()?.let { v ->
                                    targetNode.lookX = v
                                    targetNode.hasLookNode = true
                                    PathPresetManager.savePresets()
                                }
                            }

                            val lookYBox = EditBox(font, subX + lookColW + 4, 0, lookColW, 20, Component.literal("Look Y"))
                            lookYBox.setHint(Component.literal("Look Y"))
                            if (editLookInputY.isBlank()) editLookInputY = if (targetNode.hasLookNode) targetNode.lookY.toString() else (targetNode.y + 1.2).toString()
                            lookYBox.value = editLookInputY
                            lookYBox.setResponder {
                                editLookInputY = it
                                it.toDoubleOrNull()?.let { v ->
                                    targetNode.lookY = v
                                    targetNode.hasLookNode = true
                                    PathPresetManager.savePresets()
                                }
                            }

                            val lookZBox = EditBox(font, subX + (lookColW + 4) * 2, 0, lookColW, 20, Component.literal("Look Z"))
                            lookZBox.setHint(Component.literal("Look Z"))
                            if (editLookInputZ.isBlank()) editLookInputZ = if (targetNode.hasLookNode) targetNode.lookZ.toString() else targetNode.z.toString()
                            lookZBox.value = editLookInputZ
                            lookZBox.setResponder {
                                editLookInputZ = it
                                it.toDoubleOrNull()?.let { v ->
                                    targetNode.lookZ = v
                                    targetNode.hasLookNode = true
                                    PathPresetManager.savePresets()
                                }
                            }

                            items.add(MultiWidgetRow(listOf(lookXBox, lookYBox, lookZBox), 20))
                        }

                        // 5. Apply Changes & Deselect
                        val halfBtnW = (subW - 4) / 2
                        val btnApplyEdit = ModernButton(subX, 0, halfBtnW, 24, Component.literal("✦ Apply Changes to Node #${editIdx + 1}"), 0xFF10B981.toInt()) {
                            val newX = editNodeInputX.toDoubleOrNull() ?: targetNode.x
                            val newY = editNodeInputY.toDoubleOrNull() ?: targetNode.y
                            val newZ = editNodeInputZ.toDoubleOrNull() ?: targetNode.z
                            targetNode.x = newX
                            targetNode.y = newY
                            targetNode.z = newZ
                            targetNode.action = editNodeSelectedType.name
                            if (editNodeSelectedType == RouteNodeType.TIMEOUT) {
                                targetNode.timeoutSeconds = editTimeoutSeconds.toDoubleOrNull() ?: targetNode.timeoutSeconds
                            }
                            if (editLookInputX.isNotBlank() && editLookInputY.isNotBlank() && editLookInputZ.isNotBlank()) {
                                val lx = editLookInputX.toDoubleOrNull()
                                val ly = editLookInputY.toDoubleOrNull()
                                val lz = editLookInputZ.toDoubleOrNull()
                                if (lx != null && ly != null && lz != null) {
                                    targetNode.lookX = lx
                                    targetNode.lookY = ly
                                    targetNode.lookZ = lz
                                    targetNode.hasLookNode = true
                                }
                            }

                            val targetNum = editNodeInputIndex.toIntOrNull() ?: (editIdx + 1)
                            val targetPos = (targetNum - 1).coerceIn(0, preset.points.size - 1)
                            if (targetPos != editIdx) {
                                preset.moveNode(editIdx, targetPos)
                                RouteEditor.editingNodeIndex = targetPos
                            }
                            PathPresetManager.savePresets()
                            minecraft.player?.sendSystemMessage(
                                Component.literal("§a[AsthoonLite] §fUpdated node §e#${targetPos + 1}§f!")
                            )
                            rebuildTab(Tab.PATHFINDING)
                        }

                        val btnCancelEdit = ModernButton(subX + halfBtnW + 4, 0, halfBtnW, 24, Component.literal("✕ Deselect Node"), 0xFF64748B.toInt()) {
                            RouteEditor.editingNodeIndex = -1
                            editNodeInputIndex = ""
                            editNodeInputX = ""
                            editNodeInputY = ""
                            editNodeInputZ = ""
                            editTypeDropdownOpen = false
                            editLookDropdownOpen = false
                            rebuildTab(Tab.PATHFINDING)
                        }
                        items.add(MultiWidgetRow(listOf(btnApplyEdit, btnCancelEdit), 24))
                    }

                    // Numbered Route Nodes
                    items.add(SectionHeader("Route Nodes (${preset.points.size}) - Hold LMB to Drag & Reorder"))
                    if (preset.points.isEmpty()) {
                        items.add(NoteRow("Route is empty. Click '+ Add Node at Current Player Position' or use Crosshair Pick."))
                    } else {
                        val infoW = subW - 104
                        val btnMiniW = 24

                        for (idx in preset.points.indices) {
                            val node = preset.points[idx]
                            val nType = node.nodeType()
                            val isExecCurrent = PathExecutor.isActive && PathExecutor.currentNodeIndex == idx
                            val isEditingThis = RouteEditor.editingNodeIndex == idx
                            val prefix = if (isExecCurrent) "▶ #${idx + 1}" else "#${idx + 1}"
                            val tag = if (isEditingThis) " [EDITING]" else ""
                            val lookTag = if (node.hasLookNode) " [LOOK]" else ""
                            val timeoutTag = if (nType == RouteNodeType.TIMEOUT) " [${node.timeoutSeconds}s]" else ""
                            val label = "$prefix [${nType.displayName}]$timeoutTag$lookTag (${String.format("%.1f", node.x)}, ${String.format("%.1f", node.y)}, ${String.format("%.1f", node.z)})$tag"

                            val btnColor = if (isEditingThis) 0xFFF59E0B.toInt() else nType.badgeColor
                            val btnInfo = ModernButton(subX, 0, infoW, 20, Component.literal(label), btnColor) {
                                RouteEditor.editingNodeIndex = idx
                                editNodeInputIndex = (idx + 1).toString()
                                editNodeInputX = node.x.toString()
                                editNodeInputY = node.y.toString()
                                editNodeInputZ = node.z.toString()
                                editNodeSelectedType = nType
                                editLookInputX = if (node.hasLookNode) node.lookX.toString() else ""
                                editLookInputY = if (node.hasLookNode) node.lookY.toString() else ""
                                editLookInputZ = if (node.hasLookNode) node.lookZ.toString() else ""
                                editTimeoutSeconds = if (node.timeoutSeconds > 0.0) node.timeoutSeconds.toString() else "1.0"
                                editTypeDropdownOpen = false
                                editLookDropdownOpen = false
                                rebuildTab(Tab.PATHFINDING)
                            }

                            val btnUp = ModernButton(subX + infoW + 2, 0, btnMiniW, 20, Component.literal("▲"), 0xFF38BDF8.toInt()) {
                                if (preset.moveNode(idx, idx - 1)) {
                                    if (RouteEditor.editingNodeIndex == idx) RouteEditor.editingNodeIndex = idx - 1
                                    PathPresetManager.savePresets()
                                    rebuildTab(Tab.PATHFINDING)
                                }
                            }

                            val btnDown = ModernButton(subX + infoW + 2 + btnMiniW + 2, 0, btnMiniW, 20, Component.literal("▼"), 0xFF38BDF8.toInt()) {
                                if (preset.moveNode(idx, idx + 1)) {
                                    if (RouteEditor.editingNodeIndex == idx) RouteEditor.editingNodeIndex = idx + 1
                                    PathPresetManager.savePresets()
                                    rebuildTab(Tab.PATHFINDING)
                                }
                            }

                            val btnCycle = ModernButton(subX + infoW + 2 + (btnMiniW + 2) * 2, 0, btnMiniW, 20, Component.literal("⇄"), nType.badgeColor) {
                                val nextTypeIdx = (allowedTypes.indexOf(nType) + 1) % allowedTypes.size
                                node.action = allowedTypes[nextTypeIdx].name
                                if (RouteEditor.editingNodeIndex == idx) {
                                    editNodeSelectedType = allowedTypes[nextTypeIdx]
                                }
                                PathPresetManager.savePresets()
                                rebuildTab(Tab.PATHFINDING)
                            }

                            val btnDelete = ModernButton(subX + infoW + 2 + (btnMiniW + 2) * 3, 0, btnMiniW, 20, Component.literal("✕"), 0xFFEF4444.toInt()) {
                                preset.points.removeAt(idx)
                                if (RouteEditor.editingNodeIndex == idx) {
                                    RouteEditor.editingNodeIndex = -1
                                } else if (RouteEditor.editingNodeIndex > idx) {
                                    RouteEditor.editingNodeIndex--
                                }
                                PathPresetManager.savePresets()
                                rebuildTab(Tab.PATHFINDING)
                            }

                            items.add(MultiWidgetRow(listOf(btnInfo, btnUp, btnDown, btnCycle, btnDelete), 20))
                        }
                    }

                    items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("✔ Done Editing Route"), 0xFF10B981.toInt()) {
                        RouteEditor.finishEditing()
                        rebuildTab(Tab.PATHFINDING)
                    }))
                }
            }

            PathfindingSection.EXPORT_PRESET -> {
                items.add(SectionHeader("Export Presets"))
                items.add(NoteRow("Export your presets to system clipboard or to disk."))

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("✦ Copy All Presets to Clipboard"), 0xFF38BDF8.toInt()) {
                    val json = PathPresetManager.exportToJson()
                    PathPresetManager.copyToClipboard(json)
                    exportFeedbackMsg = "Successfully copied all presets to clipboard!"
                    rebuildTab(Tab.PATHFINDING)
                }))

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("✦ Save Presets to File (presets.json)"), 0xFF10B981.toInt()) {
                    PathPresetManager.savePresets()
                    exportFeedbackMsg = "Successfully saved presets to .minecraft/asthoonlite/pathfinding/presets.json!"
                    rebuildTab(Tab.PATHFINDING)
                }))

                if (exportFeedbackMsg.isNotBlank()) {
                    items.add(NoteRow("§a✔ $exportFeedbackMsg"))
                }

                items.add(SectionHeader("Presets JSON Preview"))
                val preview = PathPresetManager.exportToJson().lines().take(8).joinToString(" ")
                items.add(NoteRow(preview.take(80) + "..."))
            }

            PathfindingSection.IMPORT_PRESET -> {
                items.add(SectionHeader("Import Presets"))
                items.add(NoteRow("Import presets from clipboard or reload presets.json."))

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("✦ Import from Clipboard"), 0xFF38BDF8.toInt()) {
                    val text = PathPresetManager.readFromClipboard()
                    val count = PathPresetManager.importFromJson(text)
                    importFeedbackMsg = if (count > 0) "Successfully imported $count preset(s)!" else "No valid preset JSON found in clipboard."
                    rebuildTab(Tab.PATHFINDING)
                }))

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("✦ Reload from presets.json File"), 0xFFF59E0B.toInt()) {
                    PathPresetManager.loadPresets()
                    importFeedbackMsg = "Reloaded presets from presets.json disk file!"
                    rebuildTab(Tab.PATHFINDING)
                }))

                if (importFeedbackMsg.isNotBlank()) {
                    items.add(NoteRow("§a✔ $importFeedbackMsg"))
                }

                items.add(WidgetRow(ModernButton(subX, 0, subW, 24, Component.literal("View User Presets"), 0xFF10B981.toInt()) {
                    activePathfindingSection = PathfindingSection.USER_PRESETS
                    rebuildTab(Tab.PATHFINDING)
                }))
            }
        }

        return items
    }

    /**
     * The Click Order row: one button that cycles None → Random → Human →
     * Skizo → None. The label is rewritten on press rather than the row being
     * rebuilt, so the button always reads the mode that is actually in force —
     * a row that showed the old mode after a click would be a setting that
     * lies about itself.
     */
    private fun terminalModeButton(x: Int, w: Int): ModernButton {
        var button: ModernButton? = null
        button = ModernButton(x, 0, w, 24, Component.literal(terminalModeLabel())) {
            Config.autoTerminalMode = (Config.autoTerminalMode + 1) % TerminalMode.MODE_COUNT
            button?.setMessage(Component.literal(terminalModeLabel()))
            rebuildTab(Tab.TERMINALS)
        }
        return button
    }

    private fun terminalModeLabel(): String =
        "Terminal Mode: ${TerminalMode.modeName(Config.autoTerminalMode)}"

    private fun autoClickerKeyLabel(): String {
        if (listeningForAutoClickerKey) return "Press a key (ESC = NONE)"
        val key = Config.autoClickerKey
        if (key == InputConstants.UNKNOWN.value || key == GLFW.GLFW_KEY_UNKNOWN || key < 0) return "Autoclicker Keybind: NONE"
        if (key in 0..7) return "Autoclicker Keybind: MOUSE $key"
        return "Autoclicker Keybind: ${InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string.uppercase()}"
    }

    private fun inventoryAutoClickerKeyLabel(): String {
        if (listeningForInventoryAutoClickerKey) return "Press a key (ESC = NONE)"
        val key = Config.inventoryAutoClickerKey
        if (key == InputConstants.UNKNOWN.value || key == GLFW.GLFW_KEY_UNKNOWN || key < 0) return "Stash Macro Keybind: NONE"
        if (key in 0..7) return "Stash Macro Keybind: MOUSE $key"
        return "Stash Macro Keybind: ${InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string.uppercase()}"
    }

    private fun quietModeKeyLabel(): String {
        if (listeningForQuietModeKey) return "Press a key (ESC = NONE)"
        val key = Config.quietModeKey
        if (key == InputConstants.UNKNOWN.value || key == GLFW.GLFW_KEY_UNKNOWN || key < 0) return "Quiet Mode Keybind: NONE"
        if (key in 0..7) return "Quiet Mode Keybind: MOUSE $key"
        return "Quiet Mode Keybind: ${InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string.uppercase()}"
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (listeningForQuietModeKey) {
            val keyCode = event.key()
            Config.quietModeKey = if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.UNKNOWN.value
            } else {
                keyCode
            }
            listeningForQuietModeKey = false
            if (::btnQuietModeKey.isInitialized) {
                btnQuietModeKey.message = Component.literal(quietModeKeyLabel())
            }
            return true
        }
        if (listeningForAutoClickerKey) {
            val keyCode = event.key()
            Config.autoClickerKey = if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.UNKNOWN.value
            } else {
                keyCode
            }
            listeningForAutoClickerKey = false
            if (::btnAutoClickerKey.isInitialized) {
                btnAutoClickerKey.message = Component.literal(autoClickerKeyLabel())
            }
            return true
        }
        if (listeningForInventoryAutoClickerKey) {
            val keyCode = event.key()
            Config.inventoryAutoClickerKey = if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.UNKNOWN.value
            } else {
                keyCode
            }
            listeningForInventoryAutoClickerKey = false
            if (::btnInventoryAutoClickerKey.isInitialized) {
                btnInventoryAutoClickerKey.message = Component.literal(inventoryAutoClickerKeyLabel())
            }
            return true
        }
        if (::searchBox.isInitialized && searchBox.isFocused) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                if (searchBox.value.isNotEmpty()) {
                    searchBox.value = ""
                    return true
                }
                searchBox.isFocused = false
            }
        }
        return super.keyPressed(event)
    }

    private fun getNodeIndexAtY(mouseY: Double): Int {
        for (entry in nodeItemBounds) {
            if (mouseY >= entry.second && mouseY <= entry.third) {
                return entry.first
            }
        }
        if (nodeItemBounds.isNotEmpty()) {
            if (mouseY < nodeItemBounds.first().second) return nodeItemBounds.first().first
            if (mouseY > nodeItemBounds.last().third) return nodeItemBounds.last().first
        }
        return -1
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (listeningForQuietModeKey && event.button() != 0) {
            Config.quietModeKey = event.button()
            listeningForQuietModeKey = false
            if (::btnQuietModeKey.isInitialized) {
                btnQuietModeKey.message = Component.literal(quietModeKeyLabel())
            }
            return true
        }
        if (listeningForAutoClickerKey && event.button() != 0) {
            Config.autoClickerKey = event.button()
            listeningForAutoClickerKey = false
            if (::btnAutoClickerKey.isInitialized) {
                btnAutoClickerKey.message = Component.literal(autoClickerKeyLabel())
            }
            return true
        }
        if (listeningForInventoryAutoClickerKey && event.button() != 0) {
            Config.inventoryAutoClickerKey = event.button()
            listeningForInventoryAutoClickerKey = false
            if (::btnInventoryAutoClickerKey.isInitialized) {
                btnInventoryAutoClickerKey.message = Component.literal(inventoryAutoClickerKeyLabel())
            }
            return true
        }

        // Track potential node drag start in Route Editor
        if (event.button() == 0 && activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR) {
            val idx = getNodeIndexAtY(event.y())
            if (idx >= 0) {
                mouseDownNodeIdx = idx
                mouseClickStartX = event.x()
                mouseClickStartY = event.y()
                isDraggingNode = false
            }
        }

        // First allow child widgets (buttons, sliders) to handle clicks
        if (super.mouseClicked(event, doubleClick)) return true

        // Check for scrollbar click & drag initiation
        val maxScroll = maxScroll()
        if (maxScroll > 0) {
            val px = px()
            val trackX = px + PANEL_W - 8
            val top = contentTop()
            val bottom = contentBottom()
            val mx = event.x().toInt()
            val my = event.y().toInt()
            if (mx in (trackX - 6)..(trackX + 8) && my in top..bottom) {
                isDraggingScrollbar = true
                scrollDragStartY = event.y()
                scrollDragStartOffset = scrollOffset
                return true
            }
        }

        // Check if user clicked anywhere on a feature card row
        if (event.button() == 0) {
            val mx = event.x().toInt()
            val my = event.y().toInt()
            val top = contentTop()
            val bottom = contentBottom()

            if (my in top..bottom) {
                val px = px()
                var curRelY = 0
                for (item in currentItems) {
                    val itemY = top - scrollOffset + curRelY
                    val itemH = item.height
                    // A header folds its section. Only outside search: a search
                    // result list has no sections to fold, only group labels.
                    if (item is SectionHeader && searchQuery.isEmpty()) {
                        val hx = px + 16
                        val hw = PANEL_W - 32
                        if (mx in hx..(hx + hw) && my in itemY..(itemY + itemH)) {
                            if (::searchBox.isInitialized && searchBox.isFocused) {
                                searchBox.isFocused = false
                            }
                            Config.toggleSectionCollapsed(item.title)
                            AbstractWidget.playButtonClickSound(minecraft.soundManager)
                            rebuildTab(activeTab)
                            return true
                        }
                    }
                    if (item is ToggleRow) {
                        val isSub2 = item.label.startsWith("    ↳ ")
                        val isSub1 = !isSub2 && item.label.startsWith("  ↳ ")
                        val indent = if (isSub2) 24 else (if (isSub1) 12 else 0)
                        val cardX = px + 16 + indent
                        val cardW = PANEL_W - 32 - indent

                        if (mx in cardX..(cardX + cardW) && my in itemY..(itemY + itemH)) {
                            if (::searchBox.isInitialized && searchBox.isFocused) {
                                searchBox.isFocused = false
                            }
                            item.set(!item.get())
                            AbstractWidget.playButtonClickSound(minecraft.soundManager)
                            return true
                        }
                    }
                    curRelY += itemH + ROW_GAP
                }
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val px = px()
        val py = py()
        val pH = panelH()
        if (mouseX >= px && mouseX <= px + PANEL_W && mouseY >= py && mouseY <= py + pH) {
            scrollOffset = (scrollOffset - (scrollY * 28).toInt()).coerceIn(0, maxScroll())
            updateWidgetPositions()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (isDraggingScrollbar && maxScroll() > 0) {
            val top = contentTop()
            val bottom = contentBottom()
            val trackH = (bottom - top).coerceAtLeast(1)
            val deltaY = event.y() - scrollDragStartY
            scrollOffset = (scrollDragStartOffset + (deltaY / trackH * maxScroll()).toInt()).coerceIn(0, maxScroll())
            updateWidgetPositions()
            return true
        }

        // LMB drag-to-reorder node handling in Route Editor
        if (mouseDownNodeIdx >= 0 && activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR) {
            val dy = Math.abs(event.y() - mouseClickStartY)
            if (dy > 4.0 || isDraggingNode) {
                isDraggingNode = true
                draggingNodeFromIdx = mouseDownNodeIdx
                draggingNodeHoverIdx = getNodeIndexAtY(event.y())
                return true
            }
        }

        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (isDraggingNode && draggingNodeFromIdx >= 0) {
            val targetIdx = draggingNodeHoverIdx
            val preset = RouteEditor.activePreset
            if (preset != null && targetIdx >= 0 && targetIdx != draggingNodeFromIdx) {
                preset.moveNode(draggingNodeFromIdx, targetIdx)
                PathPresetManager.savePresets()
                rebuildTab(Tab.PATHFINDING)
            }
            isDraggingNode = false
            draggingNodeFromIdx = -1
            draggingNodeHoverIdx = -1
            mouseDownNodeIdx = -1
        }
        mouseDownNodeIdx = -1
        isDraggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val px = px()
        val py = py()
        val pH = panelH()

        // ── Backdrop & Window Frame ──────────────────────────────────────────
        context.fill(0, 0, width, height, COL_BACKDROP)

        // Drop shadow / glow
        context.fill(px - 3, py - 3, px + PANEL_W + 3, py + pH + 3, 0x33000000)
        // Main window background
        context.fill(px, py, px + PANEL_W, py + pH, COL_PANEL_BG)
        // Window 1px border
        context.fill(px, py, px + PANEL_W, py + 1, COL_PANEL_BORDER)
        context.fill(px, py + pH - 1, px + PANEL_W, py + pH, COL_PANEL_BORDER)
        context.fill(px, py, px + 1, py + pH, COL_PANEL_BORDER)
        context.fill(px + PANEL_W - 1, py, px + PANEL_W, py + pH, COL_PANEL_BORDER)

        // ── Header Bar ───────────────────────────────────────────────────────
        context.fill(px + 1, py + 1, px + PANEL_W - 1, py + HEADER_H, COL_HEADER_BG)
        context.fill(px, py + HEADER_H - 1, px + PANEL_W, py + HEADER_H, COL_HEADER_BORDER)

        // Header Title
        context.text(font, "✦", px + 14, py + 14, COL_ACCENT)
        context.text(font, "Asthoon", px + 26, py + 14, COL_ACCENT)
        context.text(font, "Lite", px + 26 + font.width("Asthoon"), py + 14, COL_TEXT_TITLE)

        // Version Badge
        val ver = "v1.2"
        val badgeW = font.width(ver) + 10
        val badgeX = px + 30 + font.width("AsthoonLite")
        context.fill(badgeX, py + 12, badgeX + badgeW, py + 26, 0x330284C7)
        context.fill(badgeX, py + 12, badgeX + badgeW, py + 13, 0x660284C7)
        context.fill(badgeX, py + 25, badgeX + badgeW, py + 26, 0x660284C7)
        context.fill(badgeX, py + 12, badgeX + 1, py + 26, 0x660284C7)
        context.fill(badgeX + badgeW - 1, py + 12, badgeX + badgeW, py + 26, 0x660284C7)
        context.text(font, ver, badgeX + 5, py + 15, 0xFF7DD3FC.toInt())

        // ── Tab Bar Active Underline ─────────────────────────────────────────
        if (searchQuery.isEmpty()) {
            val activeIdx = Tab.entries.indexOf(activeTab)
            val activeBtn = tabButtons.getOrNull(activeIdx)
            if (activeBtn != null) {
                context.fill(activeBtn.x, py + HEADER_H + TAB_BAR_H - 2, activeBtn.x + activeBtn.width, py + HEADER_H + TAB_BAR_H, COL_ACCENT)
            }
        }

        // ── Dungeon Subcategory Indicator ────────────────────────────────────
        if (searchQuery.isEmpty() && activeTab == Tab.DUNGEON) {
            val secW = (PANEL_W - 24) / DungeonSection.entries.size
            val activeSecIdx = DungeonSection.entries.indexOf(activeDungeonSection)
            if (activeSecIdx >= 0) {
                val secX = px + 12 + activeSecIdx * secW
                val secY = py + HEADER_H + TAB_BAR_H + 4
                context.fill(secX, secY + DUNGEON_BAR_H - 6, secX + secW - 4, secY + DUNGEON_BAR_H - 4, COL_ACCENT)
            }
        }

        // ── Pathfinding Subcategory Indicator ────────────────────────────────
        if (searchQuery.isEmpty() && activeTab == Tab.PATHFINDING) {
            val secW = (PANEL_W - 24) / PathfindingSection.entries.size
            val activeSecIdx = PathfindingSection.entries.indexOf(activePathfindingSection)
            if (activeSecIdx >= 0) {
                val secX = px + 12 + activeSecIdx * secW
                val secY = py + HEADER_H + TAB_BAR_H + 4
                context.fill(secX, secY + DUNGEON_BAR_H - 6, secX + secW - 4, secY + DUNGEON_BAR_H - 4, COL_ACCENT)
            }
        }

        // ── Feature Rows & Headers (Clipped Viewport) ─────────────────────────
        val top = contentTop()
        val bottom = contentBottom()
        context.enableScissor(px + 8, top, px + PANEL_W - 8, bottom)

        if (activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR) {
            nodeItemBounds.clear()
        }

        var curRelY = 0
        for (item in currentItems) {
            val itemY = top - scrollOffset + curRelY
            val itemH = item.height
            if (itemY + itemH >= top && itemY <= bottom) {
                when (item) {
                    is SectionHeader -> {
                        val hx = px + 16
                        val hw = PANEL_W - 32
                        val hovered = searchQuery.isEmpty() &&
                            mouseX in hx..(hx + hw) && mouseY in itemY..(itemY + itemH)
                        drawSectionHeader(context, hx, itemY, hw, itemH, item.title, hovered)
                    }
                    is ToggleRow -> {
                        val isSub2 = item.label.startsWith("    ↳ ")
                        val isSub1 = !isSub2 && item.label.startsWith("  ↳ ")
                        val indent = if (isSub2) 24 else (if (isSub1) 12 else 0)
                        val cardX = px + 16 + indent
                        val cardW = PANEL_W - 32 - indent
                        val hovered = mouseX in cardX..(cardX + cardW) && mouseY in itemY..(itemY + itemH)
                        drawFeatureRow(context, cardX, itemY, cardW, itemH, item.label, item.subtitle, item.get(), hovered)
                    }
                    is WidgetRow -> {
                        item.widget.extractRenderState(context, mouseX, mouseY, delta)
                    }
                    is MultiWidgetRow -> {
                        for (w in item.widgets) {
                            w.extractRenderState(context, mouseX, mouseY, delta)
                        }
                        if (activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR) {
                            val firstWidget = item.widgets.firstOrNull() as? ModernButton
                            if (firstWidget != null) {
                                val str = firstWidget.message.string
                                if (str.startsWith("#") || str.startsWith("▶ #")) {
                                    val hashIdx = str.indexOf('#')
                                    val spaceIdx = str.indexOf(' ', hashIdx)
                                    val numStr = if (spaceIdx > hashIdx) str.substring(hashIdx + 1, spaceIdx) else ""
                                    val nodeNum = numStr.toIntOrNull()
                                    if (nodeNum != null) {
                                        nodeItemBounds.add(Triple(nodeNum - 1, itemY, itemY + itemH))
                                    }
                                }
                            }
                        }
                    }
                    is NoteRow -> {
                        context.text(font, item.text, px + 20, itemY + 3, COL_TEXT_SUB)
                    }
                }
            }
            curRelY += itemH + ROW_GAP
        }

        context.disableScissor()

        // ── Route Node Drag-and-Drop Indicator & Tooltip ─────────────────────
        if (isDraggingNode && draggingNodeFromIdx >= 0 && activeTab == Tab.PATHFINDING && activePathfindingSection == PathfindingSection.ROUTE_EDITOR) {
            val hoverEntry = nodeItemBounds.firstOrNull { it.first == draggingNodeHoverIdx }
            if (hoverEntry != null) {
                val cardX = px + 28
                val cardW = PANEL_W - 44
                context.fill(cardX - 2, hoverEntry.second - 2, cardX + cardW + 2, hoverEntry.third + 2, 0x5538BDF8)
                context.fill(cardX - 2, hoverEntry.second - 2, cardX + cardW + 2, hoverEntry.second, 0xFF38BDF8.toInt())
            }
            val tip = "Moving Node #${draggingNodeFromIdx + 1} ➔ #${draggingNodeHoverIdx + 1}"
            val tipW = font.width(tip)
            val tipX = mouseX + 12
            val tipY = mouseY - 14
            context.fill(tipX - 4, tipY - 3, tipX + tipW + 4, tipY + 11, 0xEE0C1322.toInt())
            context.fill(tipX - 4, tipY - 3, tipX + tipW + 4, tipY - 2, 0xFF38BDF8.toInt())
            context.text(font, tip, tipX, tipY, 0xFFFFFFFF.toInt())
        }

        // ── Scrollbar ────────────────────────────────────────────────────────
        val maxScroll = maxScroll()
        if (maxScroll > 0) {
            val trackX = px + PANEL_W - 8
            val trackTop = top
            val trackBottom = bottom
            val trackH = (trackBottom - trackTop).coerceAtLeast(1)
            val thumbH = (trackH.toDouble() * trackH.toDouble() / (trackH + maxScroll)).toInt().coerceIn(20, trackH)
            val thumbY = trackTop + ((trackH - thumbH) * (scrollOffset.toDouble() / maxScroll.toDouble())).toInt()

            context.fill(trackX, trackTop, trackX + 2, trackBottom, 0x22FFFFFF)
            val thumbCol = if (mouseX in (trackX - 2)..(trackX + 4) && mouseY in thumbY..(thumbY + thumbH)) COL_ACCENT else 0x8838BDF8.toInt()
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, thumbCol)
        }

        // ── Footer hint ──────────────────────────────────────────────────────
        val hint = "Click a header to fold • Click a row to toggle"
        val hintW = font.width(hint)
        context.text(font, hint, px + (PANEL_W - hintW) / 2, py + pH - 10, COL_TEXT_MUTED)

        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    private fun drawSectionHeader(
        ctx: GuiGraphicsExtractor,
        x: Int, y: Int, w: Int, h: Int,
        title: String,
        hovered: Boolean = false
    ) {
        // Search results are grouped, not sectioned — a caret there would
        // promise a fold that does nothing.
        val foldable = searchQuery.isEmpty()
        val collapsed = foldable && Config.isSectionCollapsed(title)
        if (hovered) ctx.fill(x - 6, y, x + w, y + h, COL_CARD_HOVER)

        val caret = if (!foldable) "" else if (collapsed) "▸  " else "▾  "
        val titleText = "✦  $caret${title.uppercase()}"
        val textW = font.width(titleText)
        val textY = y + (h - 8) / 2

        // Render accent colored section title
        ctx.text(font, titleText, x, textY, COL_ACCENT)

        // The fold affordance only speaks up on hover: a label on every header
        // would add exactly the clutter the folding is meant to remove.
        if (foldable && hovered) {
            val foldNote = if (collapsed) "click to open" else "click to fold"
            ctx.text(font, foldNote, x + w - font.width(foldNote), textY, COL_TEXT_MUTED)
        }

        // Subtle divider line extending from end of text to right edge
        val lineX = x + textW + 8
        val lineY = y + h / 2
        val lineEnd = if (foldable && hovered) x + w - 64 else x + w
        if (lineX < lineEnd) {
            ctx.fill(lineX, lineY, lineEnd, lineY + 1, 0xFF1E293B.toInt())
        }
    }

    private fun drawFeatureRow(
        ctx: GuiGraphicsExtractor,
        x: Int, y: Int, w: Int, h: Int,
        label: String, subtitle: String,
        enabled: Boolean,
        hovered: Boolean
    ) {
        val isSub2 = label.startsWith("    ↳ ")
        val isSub1 = !isSub2 && label.startsWith("  ↳ ")
        val isSub = isSub1 || isSub2
        val cleanLabel = if (isSub2) label.substring(6) else if (isSub1) label.substring(4) else label

        val bgCol = if (hovered) COL_CARD_HOVER else (if (isSub) COL_CARD_SUB_BG else COL_CARD_BG)
        val borderCol = if (hovered) COL_CARD_BORDER_HOV else COL_CARD_BORDER

        // Card background
        ctx.fill(x, y, x + w, y + h, bgCol)
        // 1px border
        ctx.fill(x, y, x + w, y + 1, borderCol)
        ctx.fill(x, y + h - 1, x + w, y + h, borderCol)
        ctx.fill(x, y, x + 1, y + h, borderCol)
        ctx.fill(x + w - 1, y, x + w, y + h, borderCol)

        // Left status indicator bar
        val statusCol = if (enabled) COL_STATUS_ON else COL_STATUS_OFF
        ctx.fill(x + 4, y + 6, x + 7, y + h - 6, statusCol)

        // Hierarchy connector
        var textX = x + 14
        if (isSub) {
            ctx.text(font, "↳", textX, y + 5, COL_ACCENT)
            textX += 12
        }

        // Label & subtitle
        ctx.text(font, cleanLabel, textX, y + 5, COL_TEXT_TITLE)
        ctx.text(font, subtitle, textX, y + 17, COL_TEXT_SUB)

        // Modern toggle switch pill
        val toggleW = 32
        val toggleH = 16
        val toggleX = x + w - toggleW - 8
        val toggleY = y + (h - toggleH) / 2

        val trackCol = if (enabled) COL_TOGGLE_ON else COL_TOGGLE_OFF
        ctx.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, trackCol)
        if (!enabled) {
            ctx.fill(toggleX, toggleY, toggleX + toggleW, toggleY + 1, 0xFF334155.toInt())
            ctx.fill(toggleX, toggleY + toggleH - 1, toggleX + toggleW, toggleY + toggleH, 0xFF334155.toInt())
            ctx.fill(toggleX, toggleY, toggleX + 1, toggleY + toggleH, 0xFF334155.toInt())
            ctx.fill(toggleX + toggleW - 1, toggleY, toggleX + toggleW, toggleY + toggleH, 0xFF334155.toInt())
        }

        // Toggle knob
        val knobCol = if (enabled) COL_TOGGLE_KNOB_ON else COL_TOGGLE_KNOB_OFF
        val knobX = if (enabled) toggleX + toggleW - 14 else toggleX + 2
        ctx.fill(knobX, toggleY + 2, knobX + 12, toggleY + toggleH - 2, knobCol)
    }

    override fun isPauseScreen() = false

    override fun onClose() {
        Config.save()
        minecraft.setScreen(null)
    }
}
