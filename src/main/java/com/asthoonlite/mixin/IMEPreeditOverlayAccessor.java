package com.asthoonlite.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the vanilla caret anchor instead of approximating EditBox scrolling. */
@Mixin(IMEPreeditOverlay.class)
public interface IMEPreeditOverlayAccessor {
    @Accessor("inputLeft")
    int getInputLeft();

    @Accessor("inputTop")
    int getInputTop();

    @Accessor("inputHeight")
    int getInputHeight();

    @Accessor("preEditTextWidth")
    int getPreEditTextWidth();

    @Accessor("font")
    Font getFont();
}
