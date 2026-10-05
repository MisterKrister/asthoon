package com.asthoonlite.hud

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/**
 * Shared "big text in the middle of the screen for a moment" popup, used for
 * things like the dungeon Cleared/Secret Done alerts and the pickaxe-ability
 * ready alert. Keeps the asthoonLite blue/dark palette instead of vanilla
 * title styling.
 */
object AlertHud : HudElement {

    private const val DISPLAY_TICKS = 30      // ~1.5s
    private const val FADE_TICKS = 8

    // Minimum ticks between two calls to show() with the identical text
    // before a repeat is allowed to actually replace/restart the popup.
    // This is what makes back-to-back "Cleared"/"Done!" calls (e.g. two
    // rapid triggers off the same event) feel instant instead of
    // re-queuing a fresh 1.5s display each time — the popup that's
    // already on screen just keeps running instead of restarting.
    private const val REPEAT_SUPPRESS_TICKS = 15

    private data class Popup(val text: String, val color: Int, var ticksLeft: Int)

    @Volatile private var current: Popup? = null
    private var lastShownText: String? = null
    private var ticksSinceLastShow = Int.MAX_VALUE

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "alert_hud"),
            this
        )
    }

    /** Call once per client tick to age out the popup. See MixinClientTick-less
     *  approach: this is driven from AsthoonLite's ClientTickEvents hook instead
     *  of its own mixin to avoid adding another injection point. */
    fun tick() {
        if (ticksSinceLastShow != Int.MAX_VALUE) ticksSinceLastShow++
        current?.let {
            if (it.ticksLeft <= 0) current = null else it.ticksLeft--
        }
    }

    // SimpleSoundInstance.forUI takes the Holder<SoundEvent> directly in
    // 26.1.2 (verified against NoammAddons, e.g. Style.kt's
    // `SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch)` — no
    // `.value()` unwrap) — so no conversion needed here either.
    //
    // Fires immediately — there's no artificial delay here. If this is
    // called again with the exact same text within REPEAT_SUPPRESS_TICKS
    // of the last call, it's treated as a duplicate signal (not a fresh
    // alert) and left alone rather than restarting the popup/sound, so a
    // double-fire doesn't look like a stall.
    fun playSound(sound: Holder<SoundEvent> = SoundEvents.NOTE_BLOCK_PLING, pitch: Float = 1f) {
        val mc = Minecraft.getInstance()
        if (mc.player != null) mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch))
    }

    fun show(text: String, color: Int = 0xFF1E90FF.toInt(), sound: Holder<SoundEvent> = SoundEvents.NOTE_BLOCK_PLING, pitch: Float = 1f) {
        if (text == lastShownText && ticksSinceLastShow < REPEAT_SUPPRESS_TICKS) return

        lastShownText = text
        ticksSinceLastShow = 0
        current = Popup(text, color, DISPLAY_TICKS)
        val mc = Minecraft.getInstance()
        mc.player?.let {
            mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch))
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        val popup = current ?: return
        val mc = Minecraft.getInstance()
        val font = mc.font

        val alpha = if (popup.ticksLeft < FADE_TICKS) {
            (255 * popup.ticksLeft / FADE_TICKS).coerceIn(0, 255)
        } else 255

        val w = mc.window.guiScaledWidth.toFloat()
        val h = mc.window.guiScaledHeight.toFloat()
        val cx = w / 2f
        val cy = h / 2f - (h * 0.056f)

        val pose = context.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.scale(2.5f, 2.5f)

        val textColor = (alpha shl 24) or (popup.color and 0x00FFFFFF)
        context.centeredText(font, popup.text, 0, -font.lineHeight / 2, textColor)

        pose.popMatrix()
    }
}
