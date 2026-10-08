package com.asthoonlite.config

import com.asthoonlite.AsthoonLite
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * All runtime toggles are read LIVE off `data` through the properties below
 * (`Config.xEnabled` always reflects `data.xEnabled` — there is no separate
 * cached/mirrored boolean anywhere else in the mod anymore). This used to
 * be the source of the "have to toggle it off and back on before it works"
 * bug: a couple of modules (etherwarp, the hitbox fix) kept their own
 * mirrored `var enabled` field that only got synced from `data` inside
 * `load()` — and `load()` returned early on a fresh install (no config file
 * yet) *before* that sync line ran, so the mirror stayed at its class
 * default until the first time you touched the toggle in the GUI. Now every
 * feature module reads `Config.xEnabled` directly at the point of use, so
 * there's nothing to fall out of sync in the first place.
 */
object Config {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val configDir: File by lazy {
        try {
            FabricLoader.getInstance().configDir.resolve(AsthoonLite.MOD_ID).toFile()
        } catch (_: Throwable) {
            File("config", AsthoonLite.MOD_ID)
        }
    }

    private val configFile: File by lazy {
        File(configDir, "asthoonLite.json")
    }

    data class Data(
        // ── QOL ───────────────────────────────────────────────────────────
        // Everything below defaults to OFF: a fresh install of the mod does
        // nothing until you turn features on yourself via /asl.
        var etherwarpEnabled        : Boolean = false,
        var petDisplayEnabled       : Boolean = false,
        var petMenuHighlightEnabled : Boolean = false,
        var petDisplayX             : Int     = 4,
        var petDisplayY             : Int     = 4,
        var petDisplayScale         : Float   = 1.0f,

        // ── Dungeon ───────────────────────────────────────────────────────
        var roomClearAlertEnabled   : Boolean = false,
        var roomClearNoBlood        : Boolean = false,
        var roomClearOnlyKey        : Boolean = false,
        var roomSecretAlertEnabled  : Boolean = false,
        var secretItemPickupSoundEnabled : Boolean = false,
        var quizSolverEnabled       : Boolean = false,
        var weirdosSolverEnabled    : Boolean = false,
        var higherLowerSolverEnabled: Boolean = false,
        var ticTacToeSolverEnabled  : Boolean = false,
        var iceFillSolverEnabled    : Boolean = false,
        var icePathSolverEnabled    : Boolean = false,
        var waterBoardSolverEnabled : Boolean = false,
        var boulderSolverEnabled    : Boolean = false,
        var creeperBeamSolverEnabled: Boolean = false,
        var teleportMazeSolverEnabled: Boolean = false,
        var lividSolverEnabled      : Boolean = false,
        var starMobEspEnabled       : Boolean = false,
        var starMobEspThroughWalls : Boolean = false,
        // false = one flat color for every starred/miniboss mob (starMobColor).
        // true  = color-coded by mob category, like devonian's BoxStarMob.
        var starMobEspByType        : Boolean = false,
        var starMobLineWidth        : Double  = 3.0,
        var starMobFillAlpha        : Double  = 0.25,
        var starMobShowFullShadow   : Boolean = true,
        var starMobColor            : Int     = 0xFF00FFFF.toInt(), // cyan
        var starMobChonkColor       : Int     = 0xFFFF0080.toInt(), // withermancer/lord/commander/super archer
        var starMobFelColor         : Int     = 0xFF00FF80.toInt(),
        var starMobMinibossColor    : Int     = 0xFFEB01A5.toInt(), // lost adventurer/diamond guy/king midas
        var starMobShadowAssassinColor : Int  = 0xFFFF0000.toInt(),
        var starMobSmColor          : Int     = 0xFFFF8000.toInt(), // skeleton master

        var dungeonMapEnabled       : Boolean = false,
        var dungeonMapAlwaysShow    : Boolean = true,
        var dungeonMapFullGrid      : Boolean = true,
        var dungeonMapX             : Int     = 12,
        var dungeonMapY             : Int     = 42,
        var dungeonMapScale         : Float   = 1.66f,
        var dungeonMapShowNames     : Boolean = true,
        var dungeonMapShowSecrets   : Boolean = true,
        var dungeonMapShowCheckmarks: Boolean = true,
        var dungeonMapDontRenderCommonNames : Boolean = false,
        var dungeonMapDontRenderYellowName : Boolean = false,
        var dungeonMapDontRenderFairyCheckmark : Boolean = false,
        var dungeonMapHideInBoss    : Boolean = false,
        var dungeonMapPlayerHeads   : Boolean = true,
        var dungeonMapMarkerSelf    : Boolean = true,
        var dungeonMapPlayerNames   : Boolean = true,
        var dungeonMapNamesOnlyLeap : Boolean = false,
        var dungeonMapPlayerHeadScale : Float = 1.0f,
        var dungeonMapMarkerScale   : Float   = 1.0f,
        // Off (default) = only markers that resolve to a real teammate are
        // drawn, so Hypixel's mob/waypoint markers never show up as heads.
        // On = dump every non-frame decoration from the map packet.
        var dungeonMapAllDecorations : Boolean = false,

        // ── Dungeon: F7/M7 ──────────────────────────────────────────────
        var dungeonTickTimersEnabled : Boolean = false,
        var dungeonTimerX            : Int     = 4,
        var dungeonTimerY            : Int     = 170,
        var bloodRoomSolverEnabled   : Boolean = false,
        var campHelperShowTimer      : Boolean = false,
        var campHelperPlaySound      : Boolean = false,
        var campHelperSoundThreshold : Double  = 1.3,
        var campHelperLineWidth      : Double  = 3.0,
        var dragonPhaseEnabled       : Boolean = false,
        var dragonPowerPriority      : Boolean = false,
        var dragonHudX               : Int     = 4,
        var dragonHudY               : Int     = 170,
        var terminalSolverEnabled    : Boolean = false,
        var autoTerminalEnabled     : Boolean = false,
        // Random Delay defaults on with a window that is wider downward than
        // upward around the mean: NoammAddons ships the same shape (a gaussian
        // between a min and a max, random on out of the box), and a fixed
        // interval is what a metronome looks like, not a hand.
        var autoTerminalRandomDelay : Boolean = true,
        var autoTerminalFirstClickDelayMs : Int = 430,
        var autoTerminalMelodyFirstClickDelayMs : Int = 0,
        var autoTerminalClickDelayMs : Int = 180,
        var autoTerminalBreakThresholdMs : Int = 500,
        var autoTerminalMinRandomDelayMs : Int = 150,
        var autoTerminalMaxRandomDelayMs : Int = 240,
        var autoTerminalMelodySkip  : Boolean = false,
        var autoTerminalNoBreak     : Boolean = false,
        var autoTerminalDontSkipFirst : Boolean = false,
        var autoTerminalAnnounceMelody : Boolean = false,
        var autoTerminalMelodyMessage : String = "melody",
        // Per-terminal-type switches default ON: "Auto Terminal" on its own has
        // to solve something. A config where they are all off is treated as
        // "no filter chosen" rather than "nothing may run" — see
        // AutoTerminal.isTypeEnabled. They stay real toggles, so switching a
        // type off still stops it.
        var autoTermColors          : Boolean = true,
        var autoTermMelody          : Boolean = true,
        var autoTermNumbers         : Boolean = true,
        var autoTermRedGreen        : Boolean = true,
        var autoTermRubix           : Boolean = true,
        var autoTermStartsWith      : Boolean = true,
        var autoI4Enabled           : Boolean = false,
        var autoSimonSaysEnabled    : Boolean = false,
        var autoSimonSaysStart      : Boolean = false,
        var blockWrongDeviceClicks : Boolean = false,
        var instantSimonSaysEnabled : Boolean = false,
        var secretHitboxesEnabled   : Boolean = false,
        var leverHitboxEnabled      : Boolean = false,
        var buttonHitboxEnabled     : Boolean = false,
        var skullHitboxEnabled      : Boolean = false,
        var mushroomHitboxEnabled   : Boolean = false,
        var secretHitboxVanillaOutline : Boolean = true,
        var secretHitboxHideOutline : Boolean = false,
        var secretHitboxSize        : Int     = 100,
        var moddedHitboxDisplayEnabled : Boolean = false,
        var pressedHitboxEnabled    : Boolean = false,
        var pressedHitboxDuration   : Int     = 500,
        var relicAuraEnabled        : Boolean = false,
        var secretAuraEnabled       : Boolean = false,
        var secretAuraRange         : Int     = 6,
        var secretAuraFov            : Int     = 90,
        var secretAuraBreakBlocks    : Boolean = false,
        var autoCloseSecretChest    : Boolean = false,
        var secretSoundEnabled      : Boolean = false,
        var maskDisplayEnabled       : Boolean = false,
        var maskHudX                 : Int     = 4,
        var maskHudY                 : Int     = 240,

        // ── Mining ────────────────────────────────────────────────────────
        var pickaxeAbilityTimerEnabled : Boolean = false,
        var pickaxeTimerX            : Int   = 4,
        var pickaxeTimerY            : Int   = 40,
        var pickaxeTimerScale        : Float = 1.0f,

        // ── Fishing ───────────────────────────────────────────────────────
        var fishBiteAlertEnabled    : Boolean = false,

        // ── Nucleus ───────────────────────────────────────────────────────
        var autoNucleusWarpEnabled  : Boolean = false,
        var autoNucleusWarpMinSec   : Double  = 0.3,
        var autoNucleusWarpMaxSec   : Double  = 0.7,

        // ── Funny ─────────────────────────────────────────────────────────
        var autoClickerEnabled      : Boolean = false,
        var autoClickerCps          : Int     = 10,
        var autoClickerKey           : Int     = -1,
        var weaponAutoClickerEnabled : Boolean = false,
        var weaponAutoClickerCps     : Int     = 7,
        var inventoryAutoClickerEnabled : Boolean = false,
        var inventoryAutoClickerCps     : Int     = 5,
        var inventoryAutoClickerKey     : Int     = -1,

        // ── Session ─────────────────────────────────────────────────────────
        var quietModeEnabled        : Boolean = false,
        var quietModeKey            : Int     = -1,

        // ── Window separation ───────────────────────────────────────────────
        var dungeonMapExternalWindow: Boolean = false,
        var dungeonMapLegitBase    : Boolean = true,
        var dungeonMapWindowX      : Int     = -1,
        var dungeonMapWindowY      : Int     = -1,
        var espExternalOverlay     : Boolean = false,
        var autoTerminalCursorGlide: Boolean = true,
        var starMobOutlineOnly     : Boolean = true,

        // ── AutoTerminal pointer (visual only; clicks still go by packet) ─
        // Appended at the end of Data so existing config files keep loading.
        var autoTerminalCursorSpeed : Int = 100,  // % of natural travel speed
        var autoTerminalCursorArc   : Int = 25,   // % of distance bowed off-straight
        var autoTerminalCursorJitter: Int = 35,   // % tremor while travelling
        // Melody ships three content rows now, so the trip between two of them
        // fits the click beat easily enough for the drawn pointer to keep up
        // with the packets — which is the whole point of having one on screen.
        var autoTerminalCursorMelody: Boolean = true,
        // Two pointers on one screen is one pointer too many: the real cursor
        // steps aside for the drawn one while it is up.
        var autoTerminalCursorHideReal: Boolean = true,
        // Terminals outside a real dungeon run (p3 simulator, practice worlds):
        // the screen title alone is what identifies a terminal, so the dungeon
        // check is only ever a safety net.
        var autoTerminalAnywhere: Boolean = true,

        // ── Per-block hitbox size (each a multiplier on secretHitboxSize) ──
        // Separated so a lever can be left forgiving while a button stays
        // close to stock, which is what one shared slider could never do.
        // Appended at the end of Data: existing config files keep loading.
        var secretLeverHitboxSize   : Int = 100,
        var secretButtonHitboxSize  : Int = 100,
        var secretSkullHitboxSize   : Int = 100,
        var secretMushroomHitboxSize: Int = 100,

        // ── Settings screen ─────────────────────────────────────────────────
        // Section headers the player has folded away. Keyed by title rather
        // than index, so renaming or reordering a section only ever costs one
        // row coming back open — it can never fold the wrong one.
        var collapsedSections: MutableSet<String> = mutableSetOf(),

        // ── Custom terminal GUI ─────────────────────────────────────────────
        // A grid centred in screen space instead of highlights painted onto
        // Hypixel's chest slots — see [com.asthoonlite.dungeon.TermGui].
        // On by default: this is the look the clicker and the drawn pointer
        // are built to sit on top of, so a fresh config gets all three.
        // Appended at the end of Data: existing config files keep loading.
        var termGuiEnabled   : Boolean = true,
        var termGuiSize      : Float   = 2.0f,
        var termGuiMelodySize: Float   = 1.5f,
        var termGuiGap       : Int     = 2,
        var termGuiRoundness : Int     = 2,

        // CSS timing curve for the pointer, percent mapping to 0..1. Melody and
        // the pane terminals share it; the shape is the whole of the accel.
        var autoTerminalEaseX1: Int = 20,
        var autoTerminalEaseY1: Int = 0,
        var autoTerminalEaseX2: Int = 0,
        var autoTerminalEaseY2: Int = 100,

        // ── How imperfect a hand is ────────────────────────────────────────
        // Appended at the end of Data, same as the pointer block above, so
        // existing config files keep loading. The individual sliders above
        // set how *much* of a trait exists; this sets how much of it varies
        // and is allowed to be wrong — timing spread, arc variation, tremor,
        // easing jitter, the pre-move hesitation, and the overshoot that
        // carries past the pane before settling. 0 is a machine: every hop
        // over the same distance is the same hop.
        var autoTerminalHumanize: Int = 50,

        // ── What the clicker looks like on screen ──────────────────────────
        // Appended at the end of Data, same as the blocks above, so existing
        // config files keep loading.
        //
        // [autoTerminalHudProgress] draws the terminal's name and how far
        // through it the clicker is — the readout NoammAddons shows beside its
        // auto clicker instead of the solution. [termGuiClickFlash] marks the
        // pane that was just clicked on the custom grid, so a click leaves a
        // mark instead of a cursor that lands somewhere and says nothing about
        // which pane it was for.
        var autoTerminalHudProgress: Boolean = true,
        var termGuiClickFlash      : Boolean = true,

        // Which candidate the clicker takes when several are ready at once —
        // NoammAddons' Click Order dropdown, in his numbering so the settings
        // row can print the same words: 0 None (slot order), 1 Random,
        // 2 Human (nearest to where the pointer already is), 3 Skizo (furthest).
        var autoTerminalClickOrder : Int = 2,

        // 0 = Osu cursor, 1 = Normal (User arrow) cursor
        var autoTerminalCursorStyle: Int = 0,

        // 0 = Normal, 1 = Human, 2 = Legit
        var autoTerminalMode       : Int = 1,

        // Local practice and terminal input recording. Existing config keys stay intact.
        var terminalSimulatorAutoEnabled: Boolean = false,
        var terminalSimulatorPingMs: Int = 0,
        var terminalInputLoggingEnabled: Boolean = false,
        var terminalSimulatorNoMelody: Boolean = false,

        // ── Hitbox environment & visibility ─────────────────────────────────
        var secretHitboxAnywhere: Boolean = true,
        var secretHitboxThroughWalls: Boolean = true,

        // ── Simon Says Fast Mode ─────────────────────────────────────────────
        var autoSimonSaysFast: Boolean = false,

        // ── ESP Toggles ──────────────────────────────────────────────────────
        var starMobEspBats: Boolean = false,

        // ── Secret Triggerbot ────────────────────────────────────────────────
        var secretTriggerBotEnabled: Boolean = false,
    )

    var data = Data()
        private set

    var etherwarpEnabled: Boolean
        get() = data.etherwarpEnabled
        set(v) { data.etherwarpEnabled = v; save() }

    // ── Pet display ───────────────────────────────────────────────────────

    var petDisplayEnabled: Boolean
        get() = data.petDisplayEnabled
        set(v) { data.petDisplayEnabled = v; save() }

    var petMenuHighlightEnabled: Boolean
        get() = data.petMenuHighlightEnabled
        set(v) { data.petMenuHighlightEnabled = v; save() }

    var petDisplayX: Int
        get() = data.petDisplayX
        set(v) { data.petDisplayX = v }

    var petDisplayY: Int
        get() = data.petDisplayY
        set(v) { data.petDisplayY = v }

    var petDisplayScale: Float
        get() = data.petDisplayScale
        set(v) { data.petDisplayScale = v }

    // ── Dungeon ───────────────────────────────────────────────────────────

    var roomClearAlertEnabled: Boolean
        get() = data.roomClearAlertEnabled
        set(v) { data.roomClearAlertEnabled = v; save() }

    var roomClearNoBlood: Boolean
        get() = data.roomClearNoBlood
        set(v) { data.roomClearNoBlood = v; save() }

    var roomClearOnlyKey: Boolean
        get() = data.roomClearOnlyKey
        set(v) { data.roomClearOnlyKey = v; save() }

    var roomSecretAlertEnabled: Boolean
        get() = data.roomSecretAlertEnabled
        set(v) { data.roomSecretAlertEnabled = v; save() }

    var secretItemPickupSoundEnabled: Boolean
        get() = data.secretItemPickupSoundEnabled
        set(v) { data.secretItemPickupSoundEnabled = v; save() }

    var quizSolverEnabled: Boolean
        get() = data.quizSolverEnabled
        set(v) { data.quizSolverEnabled = v; save() }

    var weirdosSolverEnabled: Boolean
        get() = data.weirdosSolverEnabled
        set(v) { data.weirdosSolverEnabled = v; save() }

    var higherLowerSolverEnabled: Boolean
        get() = data.higherLowerSolverEnabled
        set(v) { data.higherLowerSolverEnabled = v; save() }

    var ticTacToeSolverEnabled: Boolean
        get() = data.ticTacToeSolverEnabled
        set(v) { data.ticTacToeSolverEnabled = v; save() }

    var iceFillSolverEnabled: Boolean
        get() = data.iceFillSolverEnabled
        set(v) { data.iceFillSolverEnabled = v; save() }

    var icePathSolverEnabled: Boolean
        get() = data.icePathSolverEnabled
        set(v) { data.icePathSolverEnabled = v; save() }

    var waterBoardSolverEnabled: Boolean
        get() = data.waterBoardSolverEnabled
        set(v) { data.waterBoardSolverEnabled = v; save() }

    var boulderSolverEnabled: Boolean
        get() = data.boulderSolverEnabled
        set(v) { data.boulderSolverEnabled = v; save() }

    var creeperBeamSolverEnabled: Boolean
        get() = data.creeperBeamSolverEnabled
        set(v) { data.creeperBeamSolverEnabled = v; save() }

    var teleportMazeSolverEnabled: Boolean
        get() = data.teleportMazeSolverEnabled
        set(v) { data.teleportMazeSolverEnabled = v; save() }

    var lividSolverEnabled: Boolean
        get() = data.lividSolverEnabled
        set(v) { data.lividSolverEnabled = v; save() }

    var starMobEspEnabled: Boolean
        get() = data.starMobEspEnabled
        set(v) { data.starMobEspEnabled = v; save() }

    var starMobEspThroughWalls: Boolean
        get() = data.starMobEspThroughWalls
        set(v) { data.starMobEspThroughWalls = v; save() }

    var starMobEspBats: Boolean
        get() = data.starMobEspBats
        set(v) { data.starMobEspBats = v; save() }

    /** Off = flat starMobColor for everything. On = per-category colors. */
    var starMobEspByType: Boolean
        get() = data.starMobEspByType
        set(v) { data.starMobEspByType = v; save() }

    var starMobLineWidth: Double
        get() = data.starMobLineWidth
        set(v) { data.starMobLineWidth = v.coerceIn(0.5, 10.0); save() }

    var starMobFillAlpha: Double
        get() = data.starMobFillAlpha
        set(v) { data.starMobFillAlpha = v.coerceIn(0.0, 1.0); save() }

    var starMobShowFullShadow: Boolean
        get() = data.starMobShowFullShadow
        set(v) { data.starMobShowFullShadow = v; save() }

    var starMobColor: Int
        get() = data.starMobColor
        set(v) { data.starMobColor = v; save() }

    var starMobChonkColor: Int
        get() = data.starMobChonkColor
        set(v) { data.starMobChonkColor = v; save() }

    var starMobFelColor: Int
        get() = data.starMobFelColor
        set(v) { data.starMobFelColor = v; save() }

    var starMobMinibossColor: Int
        get() = data.starMobMinibossColor
        set(v) { data.starMobMinibossColor = v; save() }

    var starMobShadowAssassinColor: Int
        get() = data.starMobShadowAssassinColor
        set(v) { data.starMobShadowAssassinColor = v; save() }

    var starMobSmColor: Int
        get() = data.starMobSmColor
        set(v) { data.starMobSmColor = v; save() }

    var dungeonMapEnabled: Boolean
        get() = data.dungeonMapEnabled
        set(v) { data.dungeonMapEnabled = v; save() }

    /** Keep the map on screen even when it's not the held item. */
    var dungeonMapAlwaysShow: Boolean
        get() = data.dungeonMapAlwaysShow
        set(v) { data.dungeonMapAlwaysShow = v; save() }

    /** Off = normal map. On = full/cheater room layout, including unopened rooms. */
    var dungeonMapFullGrid: Boolean
        get() = data.dungeonMapFullGrid
        set(v) { data.dungeonMapFullGrid = v; save() }

    var dungeonMapX: Int
        get() = data.dungeonMapX
        set(v) { data.dungeonMapX = v }

    var dungeonMapY: Int
        get() = data.dungeonMapY
        set(v) { data.dungeonMapY = v }

    var dungeonMapScale: Float
        get() = data.dungeonMapScale
        set(v) { data.dungeonMapScale = v }

    var dungeonMapShowNames: Boolean
        get() = data.dungeonMapShowNames
        set(v) { data.dungeonMapShowNames = v; save() }

    var dungeonMapShowSecrets: Boolean
        get() = data.dungeonMapShowSecrets
        set(v) { data.dungeonMapShowSecrets = v; save() }

    var dungeonMapShowCheckmarks: Boolean
        get() = data.dungeonMapShowCheckmarks
        set(v) { data.dungeonMapShowCheckmarks = v; save() }

    var dungeonMapDontRenderCommonNames: Boolean
        get() = data.dungeonMapDontRenderCommonNames
        set(v) { data.dungeonMapDontRenderCommonNames = v; save() }

    var dungeonMapDontRenderYellowName: Boolean
        get() = data.dungeonMapDontRenderYellowName
        set(v) { data.dungeonMapDontRenderYellowName = v; save() }

    var dungeonMapDontRenderFairyCheckmark: Boolean
        get() = data.dungeonMapDontRenderFairyCheckmark
        set(v) { data.dungeonMapDontRenderFairyCheckmark = v; save() }

    var dungeonMapHideInBoss: Boolean
        get() = data.dungeonMapHideInBoss
        set(v) { data.dungeonMapHideInBoss = v; save() }

    var dungeonMapPlayerHeads: Boolean
        get() = data.dungeonMapPlayerHeads
        set(v) { data.dungeonMapPlayerHeads = v; save() }

    var dungeonMapMarkerSelf: Boolean
        get() = data.dungeonMapMarkerSelf
        set(v) { data.dungeonMapMarkerSelf = v; save() }

    var dungeonMapPlayerNames: Boolean
        get() = data.dungeonMapPlayerNames
        set(v) { data.dungeonMapPlayerNames = v; save() }

    var dungeonMapNamesOnlyLeap: Boolean
        get() = data.dungeonMapNamesOnlyLeap
        set(v) { data.dungeonMapNamesOnlyLeap = v; save() }

    var dungeonMapPlayerHeadScale: Float
        get() = data.dungeonMapPlayerHeadScale
        set(v) { data.dungeonMapPlayerHeadScale = v.coerceIn(0.5f, 3.0f); save() }

    var dungeonMapMarkerScale: Float
        get() = data.dungeonMapMarkerScale
        set(v) { data.dungeonMapMarkerScale = v.coerceIn(0.5f, 3.0f); save() }

    /** Draw every decoration the map packet carries, not just teammate markers. */
    var dungeonMapAllDecorations: Boolean
        get() = data.dungeonMapAllDecorations
        set(v) { data.dungeonMapAllDecorations = v; save() }

    var dungeonTickTimersEnabled: Boolean
        get() = data.dungeonTickTimersEnabled
        set(v) { data.dungeonTickTimersEnabled = v; save() }

    var dungeonTimerX: Int
        get() = data.dungeonTimerX
        set(v) { data.dungeonTimerX = v }

    var dungeonTimerY: Int
        get() = data.dungeonTimerY
        set(v) { data.dungeonTimerY = v }

    var bloodRoomSolverEnabled: Boolean
        get() = data.bloodRoomSolverEnabled
        set(v) { data.bloodRoomSolverEnabled = v; save() }

    var campHelperShowTimer: Boolean
        get() = data.campHelperShowTimer
        set(v) { data.campHelperShowTimer = v; save() }

    var campHelperPlaySound: Boolean
        get() = data.campHelperPlaySound
        set(v) { data.campHelperPlaySound = v; save() }

    var campHelperSoundThreshold: Double
        get() = data.campHelperSoundThreshold
        set(v) { data.campHelperSoundThreshold = v; save() }

    var campHelperLineWidth: Double
        get() = data.campHelperLineWidth
        set(v) { data.campHelperLineWidth = v; save() }

    var dragonPhaseEnabled: Boolean
        get() = data.dragonPhaseEnabled
        set(v) { data.dragonPhaseEnabled = v; save() }

    var dragonPowerPriority: Boolean
        get() = data.dragonPowerPriority
        set(v) { data.dragonPowerPriority = v; save() }

    var dragonHudX: Int
        get() = data.dragonHudX
        set(v) { data.dragonHudX = v }

    var dragonHudY: Int
        get() = data.dragonHudY
        set(v) { data.dragonHudY = v }

    var terminalSolverEnabled: Boolean
        get() = data.terminalSolverEnabled
        set(v) {
            data.terminalSolverEnabled = v
            data.termGuiEnabled = v
            save()
        }

    // ── Custom terminal GUI ─────────────────────────────────────────────────

    var termGuiEnabled: Boolean
        get() = data.terminalSolverEnabled
        set(v) {
            data.termGuiEnabled = v
            data.terminalSolverEnabled = v
            save()
        }

    /** Tile scale. Melody carries its own — five rows of seven does not fit at the term size. */
    var termGuiSize: Float
        get() = data.termGuiSize
        set(v) { data.termGuiSize = v; save() }

    var termGuiMelodySize: Float
        get() = data.termGuiMelodySize
        set(v) { data.termGuiMelodySize = v; save() }

    /** Gap between tiles, before scaling. */
    var termGuiGap: Int
        get() = data.termGuiGap
        set(v) { data.termGuiGap = v; save() }

    var termGuiRoundness: Int
        get() = data.termGuiRoundness
        set(v) { data.termGuiRoundness = v; save() }

    var autoTerminalHudProgress: Boolean
        get() = data.autoTerminalHudProgress
        set(v) { data.autoTerminalHudProgress = v; save() }

    var termGuiClickFlash: Boolean
        get() = data.termGuiClickFlash
        set(v) { data.termGuiClickFlash = v; save() }

    var autoTerminalClickOrder: Int
        get() = data.autoTerminalClickOrder
        set(v) { data.autoTerminalClickOrder = v.coerceIn(0, 3); save() }

    var autoTerminalEnabled: Boolean
        get() = data.autoTerminalEnabled
        set(v) { data.autoTerminalEnabled = v; save() }

    var terminalSimulatorAutoEnabled: Boolean
        get() = data.terminalSimulatorAutoEnabled
        set(v) { data.terminalSimulatorAutoEnabled = v; save() }

    var terminalSimulatorPingMs: Int
        get() = data.terminalSimulatorPingMs.coerceIn(0, 500)
        set(v) { data.terminalSimulatorPingMs = v.coerceIn(0, 500); save() }

    var terminalInputLoggingEnabled: Boolean
        get() = data.terminalInputLoggingEnabled
        set(v) { data.terminalInputLoggingEnabled = v; save() }

    var terminalSimulatorNoMelody: Boolean
        get() = data.terminalSimulatorNoMelody
        set(v) { data.terminalSimulatorNoMelody = v; save() }

    var autoTerminalRandomDelay: Boolean
        get() = data.autoTerminalRandomDelay
        set(v) { data.autoTerminalRandomDelay = v; save() }

    var autoTerminalFirstClickDelayMs: Int
        get() = data.autoTerminalFirstClickDelayMs
        set(v) { data.autoTerminalFirstClickDelayMs = v.coerceIn(0, 1000); save() }

    var autoTerminalMelodyFirstClickDelayMs: Int
        get() = data.autoTerminalMelodyFirstClickDelayMs
        set(v) { data.autoTerminalMelodyFirstClickDelayMs = v.coerceIn(0, 1000); save() }

    var autoTerminalClickDelayMs: Int
        get() = data.autoTerminalClickDelayMs
        set(v) {
            // The window travels with the mean rather than being left behind
            // by it: Drag Click Delay and the spread you chose stays the spread
            // you have, instead of silently becoming lopsided.
            val keep = autoTerminalDelaySpreadMs
            data.autoTerminalClickDelayMs = v.coerceIn(0, 1000)
            val (low, high) = spreadWindow(data.autoTerminalClickDelayMs, keep)
            data.autoTerminalMinRandomDelayMs = low
            data.autoTerminalMaxRandomDelayMs = high
            save()
        }

    // Unwired: `AutoTerminal` reads neither of these and the Terminal tab
    // rows that used to expose them are gone. They stay because the file is
    // on disk — dropping the field would drop the user's value with it, and
    // there is nothing to gain from that. Re-wire before re-exposing.
    var autoTerminalBreakThresholdMs: Int
        get() = data.autoTerminalBreakThresholdMs
        set(v) { data.autoTerminalBreakThresholdMs = v.coerceIn(0, 2000); save() }

    var autoTerminalMinRandomDelayMs: Int
        get() = data.autoTerminalMinRandomDelayMs
        set(v) { data.autoTerminalMinRandomDelayMs = v.coerceIn(0, 1000); save() }

    var autoTerminalMaxRandomDelayMs: Int
        get() = data.autoTerminalMaxRandomDelayMs
        set(v) { data.autoTerminalMaxRandomDelayMs = v.coerceIn(0, 1000); save() }

    /**
     * The jitter window as one number: how far either side of the Click Delay
     * the beat may stray.
     *
     * It is read back as the half-width of the stored Min/Max pair and written
     * as that pair centred on the Click Delay, so the two sliders that used to
     * have to agree with a third become one control that cannot disagree with
     * anything. The pair stays on disk under its old names because that is
     * where users' settings already live.
     */
    var autoTerminalDelaySpreadMs: Int
        get() = ((data.autoTerminalMaxRandomDelayMs - data.autoTerminalMinRandomDelayMs) / 2).coerceIn(0, 500)
        set(v) {
            val (low, high) = spreadWindow(data.autoTerminalClickDelayMs, v)
            data.autoTerminalMinRandomDelayMs = low
            data.autoTerminalMaxRandomDelayMs = high
            save()
        }

    var autoTerminalMelodySkip: Boolean
        get() = data.autoTerminalMelodySkip
        set(v) { data.autoTerminalMelodySkip = v; save() }

    /** See [autoTerminalBreakThresholdMs] — unwired, kept for the on-disk value. */
    var autoTerminalNoBreak: Boolean
        get() = data.autoTerminalNoBreak
        set(v) { data.autoTerminalNoBreak = v; save() }

    var autoTerminalDontSkipFirst: Boolean
        get() = data.autoTerminalDontSkipFirst
        set(v) { data.autoTerminalDontSkipFirst = v; save() }

    var autoTerminalAnnounceMelody: Boolean
        get() = data.autoTerminalAnnounceMelody
        set(v) { data.autoTerminalAnnounceMelody = v; save() }

    var autoTerminalMelodyMessage: String
        get() = data.autoTerminalMelodyMessage
        set(v) { data.autoTerminalMelodyMessage = v; save() }

    var autoTermColors: Boolean
        get() = data.autoTermColors
        set(v) { data.autoTermColors = v; save() }

    var autoTermMelody: Boolean
        get() = data.autoTermMelody
        set(v) { data.autoTermMelody = v; save() }

    var autoTermNumbers: Boolean
        get() = data.autoTermNumbers
        set(v) { data.autoTermNumbers = v; save() }

    var autoTermRedGreen: Boolean
        get() = data.autoTermRedGreen
        set(v) { data.autoTermRedGreen = v; save() }

    var autoTermRubix: Boolean
        get() = data.autoTermRubix
        set(v) { data.autoTermRubix = v; save() }

    var autoTermStartsWith: Boolean
        get() = data.autoTermStartsWith
        set(v) { data.autoTermStartsWith = v; save() }

    fun applyRsmAutoPreset() {
        data.autoTerminalEnabled = true
        data.terminalSolverEnabled = true
        // The look the clicker is built to sit on: the grid instead of
        // Hypixel's chest slots, the drawn pointer doing the pointing, and the
        // pointer's real-cursor twin stepping aside while it is up.
        data.termGuiEnabled = true
        data.autoTerminalCursorGlide = true
        data.autoTerminalCursorHideReal = true
        data.autoTerminalCursorMelody = true
        data.autoTerminalHudProgress = true
        data.termGuiClickFlash = true
        data.autoTerminalClickOrder = 2
        data.autoTerminalRandomDelay = true
        data.autoTerminalFirstClickDelayMs = 430
        data.autoTerminalMelodyFirstClickDelayMs = 0
        data.autoTerminalClickDelayMs = 135
        data.autoTerminalBreakThresholdMs = 500
        data.autoTerminalMinRandomDelayMs = 120
        data.autoTerminalMaxRandomDelayMs = 150
        data.autoTerminalMelodySkip = true
        data.autoTerminalNoBreak = false
        data.autoTerminalDontSkipFirst = true
        data.autoTerminalAnnounceMelody = true
        data.autoTerminalMelodyMessage = "melody"
        data.autoTermColors = true
        data.autoTermMelody = true
        data.autoTermNumbers = true
        data.autoTermRedGreen = true
        data.autoTermRubix = true
        data.autoTermStartsWith = true
        save()
    }

    fun applyDevonianMapPreset() {
        data.dungeonMapEnabled = true
        data.dungeonMapAlwaysShow = true
        data.dungeonMapFullGrid = true
        data.dungeonMapShowNames = true
        data.dungeonMapShowSecrets = true
        data.dungeonMapShowCheckmarks = true
        data.dungeonMapDontRenderCommonNames = true
        data.dungeonMapDontRenderYellowName = true
        data.dungeonMapDontRenderFairyCheckmark = true
        data.dungeonMapHideInBoss = true
        data.dungeonMapPlayerHeads = true
        data.dungeonMapMarkerSelf = true
        data.dungeonMapPlayerNames = true
        data.dungeonMapNamesOnlyLeap = false
        data.dungeonMapScale = 1.66f
        data.dungeonMapX = 12
        data.dungeonMapY = 42
        save()
    }

    fun applyDevonianWatcherPreset() {
        data.bloodRoomSolverEnabled = true
        data.campHelperShowTimer = true
        data.campHelperPlaySound = false
        data.campHelperSoundThreshold = 1.3
        data.campHelperLineWidth = 3.0
        save()
    }

    fun enableAllPuzzleSolvers() {
        data.quizSolverEnabled = true
        data.weirdosSolverEnabled = true
        data.higherLowerSolverEnabled = true
        data.ticTacToeSolverEnabled = true
        data.iceFillSolverEnabled = true
        data.icePathSolverEnabled = true
        data.waterBoardSolverEnabled = true
        data.boulderSolverEnabled = true
        data.creeperBeamSolverEnabled = true
        data.teleportMazeSolverEnabled = true
        data.lividSolverEnabled = true
        save()
    }

    fun disableAllPuzzleSolvers() {
        data.quizSolverEnabled = false
        data.weirdosSolverEnabled = false
        data.higherLowerSolverEnabled = false
        data.ticTacToeSolverEnabled = false
        data.iceFillSolverEnabled = false
        data.icePathSolverEnabled = false
        data.waterBoardSolverEnabled = false
        data.boulderSolverEnabled = false
        data.creeperBeamSolverEnabled = false
        data.teleportMazeSolverEnabled = false
        data.lividSolverEnabled = false
        save()
    }

    fun resetToCleanDefaults() {
        data = Data()
        save()
    }

    var autoI4Enabled: Boolean
        get() = data.autoI4Enabled
        set(v) { data.autoI4Enabled = v; save() }

    var autoSimonSaysEnabled: Boolean
        get() = data.autoSimonSaysEnabled
        set(v) { data.autoSimonSaysEnabled = v; save() }

    var autoSimonSaysFast: Boolean
        get() = data.autoSimonSaysFast
        set(v) { data.autoSimonSaysFast = v; save() }

    var autoSimonSaysStart: Boolean
        get() = data.autoSimonSaysStart
        set(v) { data.autoSimonSaysStart = v; save() }

    var blockWrongDeviceClicks: Boolean
        get() = data.blockWrongDeviceClicks
        set(v) { data.blockWrongDeviceClicks = v; save() }

    var instantSimonSaysEnabled: Boolean
        get() = data.instantSimonSaysEnabled
        set(v) { data.instantSimonSaysEnabled = v; save() }

    var secretHitboxesEnabled: Boolean
        get() = data.secretHitboxesEnabled
        set(v) { data.secretHitboxesEnabled = v; save() }

    var leverHitboxEnabled: Boolean
        get() = data.leverHitboxEnabled
        set(v) { data.leverHitboxEnabled = v; save() }

    var buttonHitboxEnabled: Boolean
        get() = data.buttonHitboxEnabled
        set(v) { data.buttonHitboxEnabled = v; save() }

    var skullHitboxEnabled: Boolean
        get() = data.skullHitboxEnabled
        set(v) { data.skullHitboxEnabled = v; save() }

    var mushroomHitboxEnabled: Boolean
        get() = data.mushroomHitboxEnabled
        set(v) { data.mushroomHitboxEnabled = v; save() }

    var secretHitboxVanillaOutline: Boolean
        get() = data.secretHitboxVanillaOutline
        set(v) { data.secretHitboxVanillaOutline = v; save() }

    var secretHitboxHideOutline: Boolean
        get() = data.secretHitboxHideOutline
        set(v) { data.secretHitboxHideOutline = v; save() }

    var secretSoundEnabled: Boolean
        get() = data.secretSoundEnabled
        set(v) { data.secretSoundEnabled = v; save() }

    var secretHitboxSize: Int
        get() = data.secretHitboxSize
        set(v) { data.secretHitboxSize = v.coerceIn(0, 100); save() }

    var secretLeverHitboxSize: Int
        get() = data.secretLeverHitboxSize
        set(v) { data.secretLeverHitboxSize = v.coerceIn(0, 100); save() }

    var secretButtonHitboxSize: Int
        get() = data.secretButtonHitboxSize
        set(v) { data.secretButtonHitboxSize = v.coerceIn(0, 100); save() }

    var secretSkullHitboxSize: Int
        get() = data.secretSkullHitboxSize
        set(v) { data.secretSkullHitboxSize = v.coerceIn(0, 100); save() }

    var secretMushroomHitboxSize: Int
        get() = data.secretMushroomHitboxSize
        set(v) { data.secretMushroomHitboxSize = v.coerceIn(0, 100); save() }

    fun isSectionCollapsed(title: String): Boolean = data.collapsedSections.contains(title)

    /** Fold or unfold a settings section and remember which. */
    fun toggleSectionCollapsed(title: String) {
        if (!data.collapsedSections.remove(title)) data.collapsedSections.add(title)
        save()
    }

    var moddedHitboxDisplayEnabled: Boolean
        get() = data.moddedHitboxDisplayEnabled
        set(v) { data.moddedHitboxDisplayEnabled = v; save() }

    var pressedHitboxEnabled: Boolean
        get() = data.pressedHitboxEnabled
        set(v) { data.pressedHitboxEnabled = v; save() }

    var pressedHitboxDuration: Int
        get() = data.pressedHitboxDuration
        set(v) { data.pressedHitboxDuration = v.coerceIn(100, 2000); save() }

    var relicAuraEnabled: Boolean
        get() = data.relicAuraEnabled
        set(v) { data.relicAuraEnabled = v; save() }

    var secretAuraEnabled: Boolean
        get() = data.secretAuraEnabled
        set(v) { data.secretAuraEnabled = v; save() }

    var secretTriggerBotEnabled: Boolean
        get() = data.secretTriggerBotEnabled
        set(v) { data.secretTriggerBotEnabled = v; save() }

    var secretAuraRange: Int
        get() = data.secretAuraRange
        set(v) { data.secretAuraRange = v.coerceIn(1, 20); save() }

    var secretAuraFov: Int
        get() = data.secretAuraFov
        set(v) { data.secretAuraFov = v.coerceIn(5, 180); save() }

    var secretAuraBreakBlocks: Boolean
        get() = data.secretAuraBreakBlocks
        set(v) { data.secretAuraBreakBlocks = v; save() }

    var autoCloseSecretChest: Boolean
        get() = data.autoCloseSecretChest
        set(v) { data.autoCloseSecretChest = v; save() }

    var maskDisplayEnabled: Boolean
        get() = data.maskDisplayEnabled
        set(v) { data.maskDisplayEnabled = v; save() }

    var maskHudX: Int
        get() = data.maskHudX
        set(v) { data.maskHudX = v }

    var maskHudY: Int
        get() = data.maskHudY
        set(v) { data.maskHudY = v }

    // ── Mining ────────────────────────────────────────────────────────────

    var pickaxeAbilityTimerEnabled: Boolean
        get() = data.pickaxeAbilityTimerEnabled
        set(v) { data.pickaxeAbilityTimerEnabled = v; save() }

    var pickaxeTimerX: Int
        get() = data.pickaxeTimerX
        set(v) { data.pickaxeTimerX = v }

    var pickaxeTimerY: Int
        get() = data.pickaxeTimerY
        set(v) { data.pickaxeTimerY = v }

    var pickaxeTimerScale: Float
        get() = data.pickaxeTimerScale
        set(v) { data.pickaxeTimerScale = v }

    // ── Fishing ───────────────────────────────────────────────────────────

    var fishBiteAlertEnabled: Boolean
        get() = data.fishBiteAlertEnabled
        set(v) { data.fishBiteAlertEnabled = v; save() }

    // ── Nucleus ───────────────────────────────────────────────────────────

    var autoNucleusWarpEnabled: Boolean
        get() = data.autoNucleusWarpEnabled
        set(v) { data.autoNucleusWarpEnabled = v; save() }

    var autoNucleusWarpMinSec: Double
        get() = data.autoNucleusWarpMinSec
        set(v) { data.autoNucleusWarpMinSec = v; save() }

    var autoNucleusWarpMaxSec: Double
        get() = data.autoNucleusWarpMaxSec
        set(v) { data.autoNucleusWarpMaxSec = v; save() }

    // ── Funny ─────────────────────────────────────────────────────────────

    var autoClickerEnabled: Boolean
        get() = data.autoClickerEnabled
        set(v) { data.autoClickerEnabled = v; save() }

    // Up to 500 clicks/sec. Above 20 (1 per client tick) this fires more
    // than once per tick to actually reach the requested rate — see
    // AutoClicker.tick()'s fractional accumulator.
    var autoClickerCps: Int
        get() = data.autoClickerCps
        set(v) { data.autoClickerCps = v.coerceIn(1, 500); save() }

    var autoClickerKey: Int
        get() = data.autoClickerKey
        set(v) { data.autoClickerKey = v; save() }

    var weaponAutoClickerEnabled: Boolean
        get() = data.weaponAutoClickerEnabled
        set(v) { data.weaponAutoClickerEnabled = v; save() }

    var weaponAutoClickerCps: Int
        get() = data.weaponAutoClickerCps
        set(v) { data.weaponAutoClickerCps = v.coerceIn(4, 15); save() }

    var inventoryAutoClickerEnabled: Boolean
        get() = data.inventoryAutoClickerEnabled
        set(v) { data.inventoryAutoClickerEnabled = v; save() }

    var inventoryAutoClickerCps: Int
        get() = data.inventoryAutoClickerCps
        set(v) { data.inventoryAutoClickerCps = v.coerceIn(2, 15); save() }

    var inventoryAutoClickerKey: Int
        get() = data.inventoryAutoClickerKey
        set(v) { data.inventoryAutoClickerKey = v; save() }

    var quietModeEnabled: Boolean
        get() = data.quietModeEnabled
        set(v) { data.quietModeEnabled = v; save() }

    var quietModeKey: Int
        get() = data.quietModeKey
        set(v) { data.quietModeKey = v; save() }

    var dungeonMapExternalWindow: Boolean
        get() = data.dungeonMapExternalWindow
        set(v) { data.dungeonMapExternalWindow = v; save() }

    var dungeonMapLegitBase: Boolean
        get() = data.dungeonMapLegitBase
        set(v) { data.dungeonMapLegitBase = v; save() }

    // -1 = pick the default top-left spot; the map window drag writes these.
    var dungeonMapWindowX: Int
        get() = data.dungeonMapWindowX
        set(v) { data.dungeonMapWindowX = v; save() }

    var dungeonMapWindowY: Int
        get() = data.dungeonMapWindowY
        set(v) { data.dungeonMapWindowY = v; save() }

    var espExternalOverlay: Boolean
        get() = data.espExternalOverlay
        set(v) { data.espExternalOverlay = v; save() }

    var autoTerminalCursorGlide: Boolean
        get() = data.autoTerminalCursorGlide
        set(v) { data.autoTerminalCursorGlide = v; save() }

    /** Pointer travel speed, 100 = natural. Higher reaches the pane sooner. */
    var autoTerminalCursorSpeed: Int
        get() = data.autoTerminalCursorSpeed
        set(v) { data.autoTerminalCursorSpeed = v.coerceIn(25, 400); save() }

    /** How far the path bows off a straight line, as a percent of the distance. */
    var autoTerminalCursorArc: Int
        get() = data.autoTerminalCursorArc
        set(v) { data.autoTerminalCursorArc = v.coerceIn(0, 100); save() }

    /** Tremor amplitude while travelling, as a percent. */
    var autoTerminalCursorJitter: Int
        get() = data.autoTerminalCursorJitter
        set(v) { data.autoTerminalCursorJitter = v.coerceIn(0, 100); save() }

    /** Glide and premove on Melody without delaying its click clock. */
    var autoTerminalCursorMelody: Boolean
        get() = data.autoTerminalCursorMelody
        set(v) { data.autoTerminalCursorMelody = v; save() }

    /** Hide the real cursor while the drawn pointer is on screen. Two
     *  pointers over one pane reads as a bug, and the drawn one is the one
     *  that is about to click. */
    var autoTerminalCursorHideReal: Boolean
        get() = data.autoTerminalCursorHideReal
        set(v) { data.autoTerminalCursorHideReal = v; save() }

    /** 0 = Osu cursor, 1 = Normal (User arrow) cursor */
    var autoTerminalCursorStyle: Int
        get() = data.autoTerminalCursorStyle
        set(v) { data.autoTerminalCursorStyle = v.coerceIn(0, 1); save() }

    /** Run the terminal clicker outside a real dungeon run — the p3 simulator
     *  and practice worlds never set `DungeonContext.inDungeon`, and a terminal
     *  title is all the identification any of them need. */
    var autoTerminalAnywhere: Boolean
        get() = data.autoTerminalAnywhere
        set(v) { data.autoTerminalAnywhere = v; save() }

    /** How imperfect the pointer's hand is, 0-100. Scales the *variation* of
     *  every trait above rather than their size: at 0 the arc, the tremor and
     *  the timing are still configured, they are just identical every time. */
    var autoTerminalHumanize: Int
        get() = data.autoTerminalHumanize.coerceIn(0, 100)
        set(v) { data.autoTerminalHumanize = v.coerceIn(0, 100); save() }

    // The grid's own fields kept their original `termGui*` names: Config.Data
    // is Gson-serialised to disk, so renaming one silently resets the setting
    // for anyone who already has it. The curve below was new in the same
    // change and landed under these names.

    var autoTerminalEaseX1: Int
        get() = data.autoTerminalEaseX1.coerceIn(0, 100)
        set(v) { data.autoTerminalEaseX1 = v.coerceIn(0, 100); save() }
    var autoTerminalEaseY1: Int
        get() = data.autoTerminalEaseY1.coerceIn(0, 100)
        set(v) { data.autoTerminalEaseY1 = v.coerceIn(0, 100); save() }
    var autoTerminalEaseX2: Int
        get() = data.autoTerminalEaseX2.coerceIn(0, 100)
        set(v) { data.autoTerminalEaseX2 = v.coerceIn(0, 100); save() }
    var autoTerminalEaseY2: Int
        get() = data.autoTerminalEaseY2.coerceIn(0, 100)
        set(v) { data.autoTerminalEaseY2 = v.coerceIn(0, 100); save() }

    var starMobOutlineOnly: Boolean
        get() = data.starMobOutlineOnly
        set(v) { data.starMobOutlineOnly = v; save() }

    var autoTerminalMode: Int
        get() = data.autoTerminalMode
        set(v) { data.autoTerminalMode = v; save() }

    var secretHitboxAnywhere: Boolean
        get() = data.secretHitboxAnywhere
        set(v) { data.secretHitboxAnywhere = v; save() }

    var secretHitboxThroughWalls: Boolean
        get() = data.secretHitboxThroughWalls
        set(v) { data.secretHitboxThroughWalls = v; save() }

    fun load() {
        if (!configDir.exists()) configDir.mkdirs()

        if (configFile.exists()) {
            runCatching {
                val text = configFile.readText().takeUnless(String::isBlank)
                if (text != null) data = gson.fromJson(text, Data::class.java) ?: Data()
            }.onFailure {
                AsthoonLite.LOGGER.warn("[AsthoonLite] Failed to load config, using defaults", it)
                data = Data()
            }
        }

        // Always persist current state (creates the file with defaults on
        // first launch). No other module needs to be told about this —
        // everything reads Config.xEnabled live.
        save()
    }

    fun save() {
        runCatching {
            if (!configDir.exists()) configDir.mkdirs()
            configFile.writeText(gson.toJson(data))
        }.onFailure {
            AsthoonLite.LOGGER.warn("[AsthoonLite] Failed to save config", it)
        }
    }
}

/**
 * The Min/Max pair one Delay Spread value writes: [spread] wide either side of
 * [mean], clamped to what the fields can hold.
 *
 * Top-level rather than a member of [Config] on purpose: initialising that
 * object asks FabricLoader for a config directory, which the offline
 * regression harness does not have. This is the whole policy behind the one
 * Delay Spread slider, so the harness has to be able to reach it — and it
 * never touches the settings on disk itself.
 */
internal fun spreadWindow(mean: Int, spread: Int): Pair<Int, Int> {
    val s = spread.coerceIn(0, 500)
    val centre = mean.coerceAtLeast(0)
    return (centre - s).coerceAtLeast(0) to (centre + s).coerceAtMost(1000)
}

object TerminalMode {
    const val NORMAL = 0
    const val HUMAN = 1
    const val LEGIT = 2
    const val MODE_COUNT = 3

    fun modeName(mode: Int): String = when (mode) {
        NORMAL -> "Normal"
        HUMAN -> "Human"
        LEGIT -> "Legit"
        else -> "Normal"
    }

    fun description(mode: Int): String = when (mode) {
        NORMAL -> "Simulated hand based on Noamm/RSM human models. Follows your Click Delay, Spread, and Pointer tuning."
        HUMAN -> "Tuned from your personal recordings. Adapts to Custom GUI on/off and scales with your slider settings."
        LEGIT -> "Strictly mimics your recorded human timings and flight curves with fixed authentic parameters. Sliders are locked."
        else -> ""
    }
}
