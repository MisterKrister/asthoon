package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.resources.Identifier
import java.util.regex.Pattern

/**
 * F7/M7 server-tick helpers, consolidated from the timing behavior in
 * Devonian + NoammAddons.
 *
 * Storm/PY deliberately uses an elapsed Storm-phase clock: the actionable
 * target is 31.50 seconds (630 server ticks) from the Storm phase start.
 * This avoids Devonian's older 4.8s countdown being interpreted as the
 * pad timing.
 */
object DungeonTimers : HudElement {
    private val bossPattern = Pattern.compile("^\\[BOSS] (?:Maxor|Storm|Goldor|Necron):.*$")
    private var maxor = -1
    private var storm = -1
    private var goldor = -1
    private var necron = -1
    private var goldorFrenzy = -1
    private var stormPad = -1
    private var stormElapsedTicks = -1
    private var stormActive = false
    private var deathTick = -1
    private var secretTick = -1
    private var runStartMillis = 0L

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "dungeon_timers"), this
        )
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }

        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) onChat(ChatFormatting.stripFormatting(text.string) ?: "")
            true
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (maxor > 0) maxor--
            if (storm > 0) storm--
            if (goldor > 0) goldor--
            if (necron > 0) necron--
            if (goldorFrenzy > 0) goldorFrenzy--
            if (stormPad > 0) stormPad--
            if (stormActive && stormPad == 0) stormPad = 20
            if (deathTick > 0) deathTick--
            if (deathTick == 0) deathTick = 40
            if (secretTick > 0) secretTick--
            if (secretTick == 0 && !isBoss()) secretTick = 20
        }
    }

    fun onServerTime(packet: ClientboundSetTimePacket) {
        if (!Config.dungeonTickTimersEnabled || !DungeonContext.inDungeon) return
        if (runStartMillis == 0L) runStartMillis = System.currentTimeMillis()
        if (stormElapsedTicks >= 0) stormElapsedTicks++
        if (deathTick < 0 && System.currentTimeMillis() - runStartMillis < 6000L) {
            deathTick = 40 - (packet.gameTime % 40).toInt()
        }
        if (!isBoss()) secretTick = 20 - (packet.gameTime % 20).toInt()
    }

    private fun onChat(message: String) {
        if (!Config.dungeonTickTimersEnabled || !DungeonContext.inDungeon || !bossPattern.matcher(message).matches() &&
            !message.startsWith("The Core entrance is opening!")) return

        when (message) {
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> maxor = 167
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!" -> storm = 120
            "[BOSS] Storm: ENERGY HEED MY CALL!",
            "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" -> {
                if (!stormActive) {
                    stormActive = true
                    stormElapsedTicks = 0
                }
            }
            "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                stormPad = 20
                stormActive = true
            }
            "[BOSS] Storm: I should have known that I stood no chance." -> {
                goldor = 104
                stormActive = false
                stormPad = -1
                stormElapsedTicks = -1
            }
            "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                goldorFrenzy = 60
            }
            "[BOSS] Necron: I'm afraid, your journey ends now." -> necron = 60
            "The Core entrance is opening!" -> goldorFrenzy = -1
        }
    }

    private fun isBoss(): Boolean {
        val p = Minecraft.getInstance().player ?: return false
        // The F7/M7 boss arena is at high Y; this is intentionally only a
        // lightweight guard because Hypixel's exact location API is not part
        // of asthoonLite.
        return p.y > 100
    }

    private fun reset() {
        maxor = -1; storm = -1; goldor = -1; necron = -1
        goldorFrenzy = -1; stormPad = -1
        stormElapsedTicks = -1; stormActive = false
        deathTick = -1; secretTick = -1
        runStartMillis = System.currentTimeMillis()
    }

    private fun fmt(ticks: Int): String = String.format("%.2fs", ticks / 20f)
    
    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.dungeonTickTimersEnabled || !DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return

        val lines = ArrayList<String>()
        if (maxor > 0) lines += "Maxor ${fmt(maxor)}"
        if (storm > 0) lines += "Storm ${fmt(storm)}"
        if (stormElapsedTicks >= 0) {
            val elapsed = stormElapsedTicks.coerceAtLeast(0)
            val remain = 630 - elapsed
            lines += if (remain > 0) "PY ${fmt(remain)}  (step @ 31.50s)"
                     else "PY ${fmt(elapsed - 630)}  (31.50s)"
        }
        if (stormPad > 0) lines += "Pad ${fmt(stormPad)}"
        if (goldor > 0) lines += "Goldor ${fmt(goldor)}"
        if (goldorFrenzy > 0) lines += "Goldor Hit ${fmt(goldorFrenzy)}"
        if (necron > 0) lines += "Necron ${fmt(necron)}"
        if (deathTick > 0) lines += "Death ${fmt(deathTick)}"
        if (secretTick > 0) lines += "Secret ${fmt(secretTick)}"
        if (lines.isEmpty()) return

        val x = Config.dungeonTimerX
        var y = Config.dungeonTimerY
        for (line in lines.take(8)) {
            context.text(mc.font, line, x, y, 0xFFE0EEFF.toInt())
            y += mc.font.lineHeight + 2
        }
    }
}
