package com.asthoonlite.pathfinding

import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Teleport to Goldor, then replay the last route after the server moves the player. */
object GoldorRouteShortcut {
    private val pressLatch = RestartPressLatch()
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
        pressLatch.reset()
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

    /** Retain a short press/release until the next client tick consumes it. */
    @JvmStatic
    fun onKeyboardInput(window: Long, key: Int, action: Int) = recordInput(window, key, false, action)

    @JvmStatic
    fun onMouseInput(window: Long, button: Int, action: Int) = recordInput(window, button, true, action)

    private fun recordInput(window: Long, input: Int, mouse: Boolean, action: Int) {
        val mc = Minecraft.getInstance()
        if (window != mc.window.handle()) return
        val player = mc.player
        val allowed = player != null && mc.level != null && !player.isDeadOrDying &&
            mc.screen == null && mc.isWindowActive && mc.window.isFocused && !mc.window.isIconified
        pressLatch.input(Config.goldorRouteKey, input, mouse, action, allowed)
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val pressed = pressLatch.consume(Config.goldorRouteKey,
            mc.player != null && mc.level != null && mc.screen == null &&
                mc.isWindowActive && mc.window.isFocused && !mc.window.isIconified)
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

/** Input callbacks and ticks share the client thread; only the configured input is retained. */
internal class RestartPressLatch {
    private var binding = -1
    private var down = false
    private var requested = false

    private fun updateBinding(current: Int) {
        if (binding == current) return
        reset()
        binding = current
    }

    fun input(currentBinding: Int, input: Int, mouse: Boolean, action: Int, allowed: Boolean) {
        updateBinding(currentBinding)
        if (binding < 0 || input != binding || mouse != (binding in 0..7)) return
        when (action) {
            GLFW.GLFW_PRESS -> {
                if (!down && allowed) requested = true
                down = true
            }
            GLFW.GLFW_RELEASE -> down = false
        }
    }

    fun consume(currentBinding: Int, allowed: Boolean): Boolean {
        updateBinding(currentBinding)
        val pressed = requested && allowed
        requested = false
        // Focus loss may omit the release callback; accept the next fresh press.
        if (!allowed) down = false
        return pressed
    }

    fun reset() {
        binding = -1
        down = false
        requested = false
    }
}
