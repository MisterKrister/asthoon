package com.asthoonlite.gui

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
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
 * smooth scrolling, and instant click responsiveness across entire rows.
 */
class AsthoonLiteScreen : Screen(Component.literal("AsthoonLite")) {

    private enum class Tab(val label: String) {
        QOL("QOL"),
        DUNGEON("Dungeon"),
        MAP("Map"),
        TERMINALS("Terminals"),
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

    private var activeTab = Tab.QOL
    private var activeDungeonSection = DungeonSection.GENERAL

    companion object {
        private const val PANEL_W = 500
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

    private var currentItems: List<ContentItem> = emptyList()

    private lateinit var tabButtons: List<ModernButton>
    private var dungeonSectionButtons: MutableList<ModernButton> = mutableListOf()
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

    private fun panelH() = (height - 24).coerceIn(360, 560)
    private fun px() = (width - PANEL_W) / 2
    private fun py() = (height - panelH()) / 2

    private fun contentTop(): Int {
        val base = py() + HEADER_H + TAB_BAR_H
        return if (searchQuery.isEmpty() && activeTab == Tab.DUNGEON) base + DUNGEON_BAR_H + 8 else base + 8
    }

    private fun contentBottom(): Int = py() + panelH() - FOOTER_H

    override fun init() {
        val px = px()
        val py = py()

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

        // ── Tab bar ──────────────────────────────────────────────────────────
        val tabW = (PANEL_W - 16) / Tab.entries.size
        tabButtons = Tab.entries.mapIndexed { i, tab ->
            ModernButton(px + 8 + i * tabW, py + HEADER_H, tabW, TAB_BAR_H, Component.literal(tab.label)) {
                if (::searchBox.isInitialized && searchBox.value.isNotEmpty()) {
                    searchBox.value = ""
                }
                if (activeTab != tab || searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    scrollOffset = 0
                    rebuildTab(tab)
                }
            }
        }
        tabButtons.forEach { addRenderableWidget(it) }

        // ── Footer Done button ───────────────────────────────────────────────
        btnDone = ModernButton(px + (PANEL_W - 100) / 2, py + panelH() - 26, 100, 20, Component.literal("Done")) { onClose() }
        addRenderableWidget(btnDone)

        rebuildTab(activeTab)
    }

    private fun rebuildTab(tab: Tab) {
        activeTab = tab
        searchQuery = ""

        // Remove old extra widgets & dungeon section buttons
        extraWidgets.forEach { removeWidget(it.first) }
        extraWidgets.clear()
        dungeonSectionButtons.forEach { removeWidget(it) }
        dungeonSectionButtons.clear()
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

        currentItems = applyCollapse(buildItemsForTab(tab, px))

        var relY = 0
        for (item in currentItems) {
            if (item is WidgetRow) {
                extraWidgets.add(Pair(item.widget, relY))
                addWidget(item.widget)
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

    private fun getAllSearchableItems(px: Int): List<Pair<String, ToggleRow>> {
        val list = mutableListOf<Pair<String, ToggleRow>>()

        fun collect(category: String, items: List<ContentItem>) {
            for (item in items) {
                if (item is ToggleRow) {
                    list.add(Pair(category, item))
                }
            }
        }

        val prevTab = activeTab
        val prevSec = activeDungeonSection
        for (tab in Tab.entries) {
            if (tab == Tab.DUNGEON) {
                for (section in DungeonSection.entries) {
                    activeDungeonSection = section
                    val items = buildItemsForTab(Tab.DUNGEON, px)
                    collect("Dungeon - ${section.label}", items)
                }
            } else {
                collect(tab.label, buildItemsForTab(tab, px))
            }
        }
        activeTab = prevTab
        activeDungeonSection = prevSec
        return list
    }

    private fun rebuildSearch(query: String) {
        searchQuery = query

        extraWidgets.forEach { removeWidget(it.first) }
        extraWidgets.clear()
        dungeonSectionButtons.forEach { removeWidget(it) }
        dungeonSectionButtons.clear()
        listeningForAutoClickerKey = false
        listeningForInventoryAutoClickerKey = false
        listeningForQuietModeKey = false

        val px = px()
        val all = getAllSearchableItems(px)
        val q = query.lowercase().trim()
        val matched = all.filter { (_, row) ->
            val cleanLabel = row.label.replace(Regex("^\\s*↳\\s*"), "")
            cleanLabel.lowercase().contains(q) || row.subtitle.lowercase().contains(q)
        }

        val items = mutableListOf<ContentItem>()
        if (matched.isEmpty()) {
            items.add(SectionHeader("No results for \"$query\""))
        } else {
            val grouped = matched.groupBy { it.first }
            for ((category, list) in grouped) {
                items.add(SectionHeader(category))
                for ((_, row) in list) {
                    val cleanLabel = row.label.replace(Regex("^\\s*↳\\s*"), "")
                    items.add(ToggleRow(cleanLabel, row.subtitle, row.get, row.set))
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
                ToggleRow("  ↳ Always Show", "Show map without holding map item",
                    { Config.dungeonMapAlwaysShow }, { Config.dungeonMapAlwaysShow = it }),
                ToggleRow("  ↳ Full Map / Unopened", "Show unopened rooms from map packet",
                    { Config.dungeonMapFullGrid }, { Config.dungeonMapFullGrid = it }),
                ToggleRow("  ↳ Legit Base", "Draw only what the held map item shows: explored rooms, cleared-room checkmarks, no names or counters",
                    { Config.dungeonMapLegitBase }, { Config.dungeonMapLegitBase = it }),
                ToggleRow("  ↳ Hide Map in Boss", "Automatically hide map during boss fights",
                    { Config.dungeonMapHideInBoss }, { Config.dungeonMapHideInBoss = it }),
                ToggleRow("  ↳ External Overlay Window",
                    "Draw the map in its own always-on-top window, outside the game window, so a window capture never sees it",
                    { Config.dungeonMapExternalWindow }, { Config.dungeonMapExternalWindow = it }),
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
                ToggleRow("    ↳ Arrow for Self", "Render directional arrow instead of head for self",
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
                // Six headers became four. The preset button had a section of
                // its own and the melody rows had another, and both already
                // had a section they belonged under — the button is
                // automation, the melody delays are delays, the melody
                // toggles are behaviour. Two notes folded into the row they
                // were describing, and the two rows wired to settings nothing
                // reads went away with them.
                SectionHeader("Solver & Automation"),
                WidgetRow(ModernButton(fullX, 0, fullW, 24, Component.literal("Load AutoTerm Preset")) {
                    Config.applyRsmAutoPreset()
                    rebuildTab(Tab.TERMINALS)
                }),
                ToggleRow("Auto Terminal", "Automatically clicks the correct terminal buttons",
                    { Config.autoTerminalEnabled }, { Config.autoTerminalEnabled = it }),
                ToggleRow("  ↳ Run Anywhere (P3 Sim)", "Also runs outside a real dungeon — the terminal title is all the identification needed, so the p3 simulator and practice worlds work",
                    { Config.autoTerminalAnywhere }, { Config.autoTerminalAnywhere = it }),
                ToggleRow("Terminal Solver", "Highlights correct terminal clicks",
                    { Config.terminalSolverEnabled }, { Config.terminalSolverEnabled = it }),
                ToggleRow("Melody Skip", "Skips subsequent Melody rows on correct timing",
                    { Config.autoTerminalMelodySkip }, { Config.autoTerminalMelodySkip = it }),
                ToggleRow("  ↳ Don't Skip First Row", "Waits for first row before skipping",
                    { Config.autoTerminalDontSkipFirst }, { Config.autoTerminalDontSkipFirst = it }),
                ToggleRow("Announce Melody in Chat", "Sends party chat message when opening Melody",
                    { Config.autoTerminalAnnounceMelody }, { Config.autoTerminalAnnounceMelody = it }),
                SectionHeader("Click Timing & Delays"),
                ToggleRow("Random Delay", "Humanized random delays between clicks",
                    { Config.autoTerminalRandomDelay }, { Config.autoTerminalRandomDelay = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 500, Config.autoTerminalMinRandomDelayMs, "Min Random Delay: ", " ms") {
                    Config.autoTerminalMinRandomDelayMs = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 500, Config.autoTerminalMaxRandomDelayMs, "Max Random Delay: ", " ms") {
                    Config.autoTerminalMaxRandomDelayMs = it
                }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 600, Config.autoTerminalFirstClickDelayMs, "First Click Delay: ", " ms") {
                    Config.autoTerminalFirstClickDelayMs = it
                }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 500, Config.autoTerminalClickDelayMs, "Click Delay: ", " ms") {
                    Config.autoTerminalClickDelayMs = it
                }),
                WidgetRow(IntSlider(fullX, 0, fullW, 24, 0, 500, Config.autoTerminalMelodyFirstClickDelayMs, "Melody First Click Delay: ", " ms") {
                    Config.autoTerminalMelodyFirstClickDelayMs = it
                }),
                SectionHeader("Pointer"),
                ToggleRow("Glide Pointer", "Draws a pointer that travels between panes while clicks follow the terminal timing",
                    { Config.autoTerminalCursorGlide }, { Config.autoTerminalCursorGlide = it }),
                ToggleRow("  ↳ Hide Real Cursor", "Steps the real cursor aside while the drawn pointer is on screen",
                    { Config.autoTerminalCursorHideReal }, { Config.autoTerminalCursorHideReal = it }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 25, 400, Config.autoTerminalCursorSpeed, "Pointer Speed: ", "%") {
                    Config.autoTerminalCursorSpeed = it
                }),
                NoteRow("100% = natural hand speed. Higher is snappier."),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalCursorArc, "Pointer Arc: ", "%") {
                    Config.autoTerminalCursorArc = it
                }),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.autoTerminalCursorJitter, "Pointer Tremor: ", "%") {
                    Config.autoTerminalCursorJitter = it
                }),
                NoteRow("Clicks continue while the pointer moves. Acceleration uses a tunable cubic Bezier curve."),
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
                ToggleRow("  ↳ Glide On Melody", "Glides to the next row immediately after a click, without waiting for the row update",
                    { Config.autoTerminalCursorMelody }, { Config.autoTerminalCursorMelody = it }),
                SectionHeader("Custom Terminal GUI"),
                ToggleRow("Custom Terminal GUI", "Draws the terminal as its own grid centred on screen — sized, gapped and rounded here rather than inherited from the chest panel. Clicks are routed through the grid.",
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
            Tab.HITBOXES -> listOf(
                SectionHeader("Secret Hitbox Toggles"),
                ToggleRow("Secret Hitboxes", "Enlarged clickboxes for dungeon secrets",
                    { Config.secretHitboxesEnabled }, { Config.secretHitboxesEnabled = it }),
                ToggleRow("  ↳ Lever Hitbox", "Hitbox matching the 3D lever target area",
                    { Config.leverHitboxEnabled }, { Config.leverHitboxEnabled = it }),
                ToggleRow("  ↳ Button Hitbox", "Enlarged button target area",
                    { Config.buttonHitboxEnabled }, { Config.buttonHitboxEnabled = it }),
                ToggleRow("  ↳ Skull Hitbox", "Full block Wither Essence skull hitbox",
                    { Config.skullHitboxEnabled }, { Config.skullHitboxEnabled = it }),
                ToggleRow("  ↳ Mushroom Hitbox", "Full block Mushroom hitbox",
                    { Config.mushroomHitboxEnabled }, { Config.mushroomHitboxEnabled = it }),
                SectionHeader("Hitbox Sizes"),
                WidgetRow(IntSlider(subX, 0, subW, 24, 0, 100, Config.secretHitboxSize, "Expansion (all): ", "%") {
                    Config.secretHitboxSize = it
                }),
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
                NoteRow("Each size multiplies Expansion (all). 100% = follow it."),
                NoteRow("Buttons keep their real depth — only length and width grow."),
                SectionHeader("Hitbox Visuals & Outline"),
                ToggleRow("Show 3D Hitbox Boxes", "Renders custom 3D boxes in-game",
                    { Config.moddedHitboxDisplayEnabled }, { Config.moddedHitboxDisplayEnabled = it }),
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
                    ToggleRow("  ↳ Through Walls", "Show starred mob boxes through blocks",
                        { Config.starMobEspThroughWalls }, { Config.starMobEspThroughWalls = it }),
                    ToggleRow("  ↳ Color By Mob Type", "Color-code starred mob categories",
                        { Config.starMobEspByType }, { Config.starMobEspByType = it }),
                    ToggleRow("  ↳ Show Full Shadow", "Show full hitbox of invisible Shadow Assassins",
                        { Config.starMobShowFullShadow }, { Config.starMobShowFullShadow = it }),
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
                    ToggleRow("Auto Simon Says", "Automatically clicks the Simon Says sequence",
                        { Config.autoSimonSaysEnabled }, { Config.autoSimonSaysEnabled = it }),
                    ToggleRow("  ↳ Auto Start SS", "Automatically starts Simon Says",
                        { Config.autoSimonSaysStart }, { Config.autoSimonSaysStart = it }),
                    ToggleRow("  ↳ Block Wrong Device Clicks", "Blocks wrong Simon Says and extra Arrow Align clicks",
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
                    ToggleRow("Auto-Close Secret Chest", "Instantly closes secret chest GUI on open",
                        { Config.autoCloseSecretChest }, { Config.autoCloseSecretChest = it }),
                    ToggleRow("Secret Aura", "Auto-interact with secrets in configured range and FOV",
                        { Config.secretAuraEnabled }, { Config.secretAuraEnabled = it }),
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

                SectionHeader("Simon Says"),
                ToggleRow("I1 / Instant SS", "Instant start-button click for Simon Says",
                    { Config.instantSimonSaysEnabled }, { Config.instantSimonSaysEnabled = it }),
            )
        }
    }

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

        // First allow child widgets (buttons, sliders) to handle clicks
        if (super.mouseClicked(event, doubleClick)) return true

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
        val top = contentTop()
        val bottom = contentBottom()
        if (mouseX >= px && mouseX <= px + PANEL_W && mouseY >= top && mouseY <= bottom) {
            scrollOffset = (scrollOffset - (scrollY * 28).toInt()).coerceIn(0, maxScroll())
            updateWidgetPositions()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
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
            val tabW = (PANEL_W - 16) / Tab.entries.size
            val activeIdx = Tab.entries.indexOf(activeTab)
            if (activeIdx >= 0) {
                val barX = px + 8 + activeIdx * tabW
                context.fill(barX, py + HEADER_H + TAB_BAR_H - 2, barX + tabW, py + HEADER_H + TAB_BAR_H, COL_ACCENT)
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

        // ── Feature Rows & Headers (Clipped Viewport) ─────────────────────────
        val top = contentTop()
        val bottom = contentBottom()
        context.enableScissor(px + 8, top, px + PANEL_W - 8, bottom)

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
                    is NoteRow -> {
                        context.text(font, item.text, px + 20, itemY + 3, COL_TEXT_SUB)
                    }
                }
            }
            curRelY += itemH + ROW_GAP
        }

        context.disableScissor()

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
