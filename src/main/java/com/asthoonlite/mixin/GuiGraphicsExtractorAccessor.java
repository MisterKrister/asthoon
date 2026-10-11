package com.asthoonlite.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Verified against the unobfuscated Minecraft 26.1.2 fields. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("preeditOverlay")
    Renderable getPreeditOverlay();

    @Accessor("guiRenderState")
    GuiRenderState getGuiRenderState();
}
