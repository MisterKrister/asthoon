package com.asthoonlite

import com.asthoonlite.command.AslCommand
import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.DungeonMap
import com.asthoonlite.dungeon.DungeonContext
import com.asthoonlite.dungeon.DungeonTimers
import com.asthoonlite.dungeon.DungeonServerTick
import com.asthoonlite.dungeon.AutoTerminal
import com.asthoonlite.dungeon.F7Devices
import com.asthoonlite.dungeon.ArrowAlignSolver
import com.asthoonlite.dungeon.SecretHitboxes
import com.asthoonlite.dungeon.SecretAura
import com.asthoonlite.dungeon.RelicAura
import com.asthoonlite.dungeon.BloodRoomSolver
import com.asthoonlite.dungeon.DragonPhase
import com.asthoonlite.dungeon.MaskDisplay
import com.asthoonlite.dungeon.HigherLowerSolver
import com.asthoonlite.dungeon.QuizSolver
import com.asthoonlite.dungeon.RoomAlerts
import com.asthoonlite.dungeon.StarMobESP
import com.asthoonlite.dungeon.WeirdosSolver
import com.asthoonlite.dungeon.map.DungeonMapScanner
import com.asthoonlite.dungeon.map.DungeonScanner
import com.asthoonlite.fishing.FishBiteAlert
import com.asthoonlite.funny.AutoClicker
import com.asthoonlite.funny.InventoryAutoClicker
import com.asthoonlite.funny.WeaponAutoClicker
import com.asthoonlite.hud.AlertHud
import com.asthoonlite.mining.PickaxeAbilityTimer
import com.asthoonlite.nucleus.AutoNucleusWarp
import com.asthoonlite.pet.PetHudOverlay
import com.asthoonlite.pet.PetTracker
import com.asthoonlite.render.EtherwarpOverlay
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import org.slf4j.LoggerFactory

object AsthoonLite : ClientModInitializer {

    const val MOD_ID = "asthoonlite"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        LOGGER.info("[AsthoonLite] Loaded")

        Config.load()
        AslCommand.register()
        DungeonContext.register()

        // Session gate. Registered first so every draw path below can ask it
        // a single question per frame — see QuietMode's class doc.
        QuietMode.register()

        // Shared world-space box renderer (etherwarp highlight, star mob
        // ESP, higher/lower blaze highlight all queue into this one
        // renderer/one draw call instead of each running their own GPU
        // pipeline). Must register before anything that queues boxes.
        WorldBoxRenderer.register()
        com.asthoonlite.render.WorldTextRenderer.register()

        EtherwarpOverlay.register()
        AlertHud.register()

        // ── Pet features (QOL) ───────────────────────────────────────────────
        PetTracker.register()
        PetHudOverlay.register()
        // Pet-menu slot highlight is now handled by MixinHandledScreen's
        // extractSlot injection (see mixin/MixinHandledScreen.kt) instead of
        // a ScreenEvents.afterRender registration.

        // ── Dungeon ───────────────────────────────────────────────────────────
        RoomAlerts.register()
        QuizSolver.register()
        WeirdosSolver.register()
        StarMobESP.register()
        HigherLowerSolver.register()
        DungeonScanner.register()
        DungeonMapScanner.register()
        DungeonMap.register()
        DungeonTimers.register()
        BloodRoomSolver.register()
        DragonPhase.register()
        MaskDisplay.register()
        com.asthoonlite.dungeon.TerminalSensing.register()
        com.asthoonlite.dungeon.TerminalCapture.register()
        com.asthoonlite.dungeon.simulator.TerminalSimulator.register()
        AutoTerminal.register()
        // Draws the pointer that "carries" the auto-terminal clicks. Clicks
        // themselves are packets; this is the picture of the hand.
        com.asthoonlite.dungeon.TerminalCursor.register()
        // Prints one line per terminal-shaped screen naming the gate that
        // kept the custom grid closed, so a screen that draws nothing says why.
        com.asthoonlite.dungeon.TermGui.register()
        // Name and progress of the terminal the clicker is on — the visual
        // that describes the run instead of the solution.
        com.asthoonlite.dungeon.TerminalProgressHud.register()
        F7Devices.register()
        ArrowAlignSolver.register()
        SecretHitboxes.register()
        SecretAura.register()
        com.asthoonlite.dungeon.SecretTriggerBot.register()
        com.asthoonlite.dungeon.TerminalInteraction.register()
        RelicAura.register()
        com.asthoonlite.dungeon.solvers.TicTacToeSolver.register()
        com.asthoonlite.dungeon.solvers.IceFillSolver.register()
        com.asthoonlite.dungeon.solvers.IcePathSolver.register()
        com.asthoonlite.dungeon.solvers.WaterBoardSolver.register()
        com.asthoonlite.dungeon.solvers.BoulderSolver.register()
        com.asthoonlite.dungeon.solvers.CreeperBeamSolver.register()
        com.asthoonlite.dungeon.solvers.TeleportMazeSolver.register()
        com.asthoonlite.dungeon.solvers.LividSolver.register()

        // ── Mining ────────────────────────────────────────────────────────────
        PickaxeAbilityTimer.register()

        // ── Fishing ───────────────────────────────────────────────────────────
        FishBiteAlert.register()

        // ── Nucleus ───────────────────────────────────────────────────────────
        AutoNucleusWarp.register()

        // ── Funny ─────────────────────────────────────────────────────────────
        AutoClicker.register()
        WeaponAutoClicker.register()
        InventoryAutoClicker.register()

        com.asthoonlite.utils.InputCapture.register()
        com.asthoonlite.pathfinding.PathPresetManager.init()
        com.asthoonlite.pathfinding.PathfindCapture.register()
        com.asthoonlite.pathfinding.RouteEditor.register()
        com.asthoonlite.pathfinding.PathfindingRenderer.register()
        com.asthoonlite.pathfinding.PathExecutor.register()

        ClientTickEvents.END_CLIENT_TICK.register {
            DungeonServerTick.tick()
            AlertHud.tick()
        }
    }
}
