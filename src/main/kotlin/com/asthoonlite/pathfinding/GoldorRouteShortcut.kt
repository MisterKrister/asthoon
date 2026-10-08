package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Teleport to Goldor, then replay the last route after the server moves the player. */
object GoldorRouteShortcut {
    private var previousKeyDown = false
    private var pendingPresetId: String? = null
    private var teleportConfirmed = false
    private var settledTicks = 0
    private var waitingTicks = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun reset() {
        previousKeyDown = false
        cancelPending()
    }

    fun cancelPending() {
        pendingPresetId = null
        teleportConfirmed = false
        settledTicks = 0
        waitingTicks = 0
    }

    /** Called by the existing player-position packet callback, after vanilla applies the teleport. */
    fun onPlayerPositionPacket() {
        if (pendingPresetId != null) teleportConfirmed = true
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val key = Config.goldorRouteKey
        val down = when {
            key < 0 -> false
            key in 0..7 -> GLFW.glfwGetMouseButton(mc.window.handle(), key) == GLFW.GLFW_PRESS
            else -> InputConstants.isKeyDown(mc.window, key)
        }
        val pressed = down && !previousKeyDown
        previousKeyDown = down
        val player = mc.player ?: run { cancelPending(); return }
        if (mc.level == null || player.isDeadOrDying || mc.screen is PauseScreen) {
            cancelPending()
            return
        }

        if (pressed && mc.screen == null) {
            val lastId = Config.lastPathfindingPresetId.ifBlank { PathExecutor.activePreset?.id ?: Config.activePathfindingPresetId }
            val preset = PathPresetManager.getPresetById(lastId)
            if (preset == null || preset.points.isEmpty()) {
                player.sendSystemMessage(Component.literal("§e[AsthoonLite] Start a saved route once before using the Goldor shortcut."))
                return
            }
            PathExecutor.stop()
            Config.activePathfindingPresetId = ""
            cancelPending()
            pendingPresetId = preset.id
            player.connection.sendCommand("tpto goldor")
        }

        val id = pendingPresetId ?: return
        waitingTicks++
        if (waitingTicks > 100) {
            cancelPending()
            player.sendSystemMessage(Component.literal("§e[AsthoonLite] Goldor route restart timed out waiting for the teleport and landing."))
            return
        }
        settledTicks = settledTicksAfterTeleport(teleportConfirmed, player.onGround(),
            player.deltaMovement.horizontalDistance(), mc.screen != null, settledTicks)
        if (settledTicks < 2) return
        cancelPending()
        val preset = PathPresetManager.getPresetById(id)
        if (preset == null || preset.points.isEmpty()) {
            player.sendSystemMessage(Component.literal("§e[AsthoonLite] The last played route is no longer available."))
            return
        }
        Config.pathfindingEnabled = true
        Config.activePathfindingPresetId = preset.id
        PathExecutor.start(preset)
    }

    internal fun settledTicksAfterTeleport(confirmed: Boolean, onGround: Boolean, horizontalSpeed: Double,
                                         screenOpen: Boolean, previousTicks: Int): Int =
        if (confirmed && onGround && horizontalSpeed <= 0.06 && !screenOpen) (previousTicks + 1).coerceAtMost(2) else 0
}
