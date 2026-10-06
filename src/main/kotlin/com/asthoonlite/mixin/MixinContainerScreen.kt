package com.asthoonlite.mixin

import com.asthoonlite.dungeon.TermGui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Suppresses the vanilla chest panel while the custom terminal grid is up.
 *
 * `ContainerScreen.extractBackground` is what draws the chest texture —
 * `Screen.extractBackground` only draws the blurred world behind it, so
 * cancelling there would leave the panel standing and the grid floating on
 * top of it. Cancelling the override stops the panel and the blur together,
 * which is what leaves the screen showing the grid, the close button and
 * nothing else.
 *
 * Confirmed against 26.1.2 with `javap`: `ContainerScreen` declares
 * `public void extractBackground(GuiGraphicsExtractor, int, int, float)` and
 * calls `super` before drawing `CONTAINER_BACKGROUND`.
 */
@Mixin(ContainerScreen::class)
abstract class MixinContainerScreen {

    @Inject(
        method = ["extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIF)V"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun asthoonlite_suppressContainerBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
        ci: CallbackInfo
    ) {
        if (TermGui.isActive(this)) ci.cancel()
    }
}
