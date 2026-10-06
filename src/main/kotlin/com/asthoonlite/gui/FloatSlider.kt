package com.asthoonlite.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Float-range slider, dressed exactly as [IntSlider] — see [drawSliderCard]
 * for why that drawing is shared rather than copied.
 *
 * The step is quantised so the value badge does not flicker through
 * floating-point noise while the thumb is dragged: the stored value snaps to
 * a representable increment before it reaches [onChange], which is also what
 * keeps it round-tripping through the config file without growing a tail of
 * decimal places.
 */
class FloatSlider(
    x: Int, y: Int, w: Int, h: Int,
    private val min: Float,
    private val max: Float,
    initial: Float,
    private val labelPrefix: String,
    private val labelSuffix: String = "",
    private val decimals: Int = 1,
    private val onChange: (Float) -> Unit
) : AbstractSliderButton(
    x, y, w, h,
    Component.literal(labelPrefix + format(initial, decimals) + labelSuffix),
    ((initial - min).toDouble() / (max - min).toDouble()).coerceIn(0.0, 1.0)
) {

    var floatValue: Float = initial
        private set

    override fun updateMessage() {
        message = Component.literal(labelPrefix + format(floatValue, decimals) + labelSuffix)
    }

    override fun applyValue() {
        // `value` is the slider's own Double; everything downstream of here
        // is Float so the badge, the snap and the saved config agree.
        val raw = (min + value.toFloat() * (max - min)).coerceIn(min, max)
        val snapped = snap(raw, min, max, 200)
        floatValue = snapped
        onChange(snapped)
    }

    override fun extractWidgetRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!visible) return
        drawSliderCard(
            context, x, y, width, height,
            hovered = isHoveredOrFocused,
            label = labelPrefix.trimEnd(':', ' '),
            valueText = format(floatValue, decimals) + labelSuffix,
            fraction = value
        )
    }

    companion object {
        /**
         * Fixed-place formatting under [Locale.ROOT] — the badge and the
         * saved config have to show the same digits, and a locale that
         * writes "2,5" would make the slider look broken on a comma.
         */
        private fun format(v: Float, decimals: Int): String =
            if (decimals <= 0) v.toInt().toString()
            else String.format(Locale.ROOT, "%.${decimals}f", v)

        /**
         * Snaps a dragged value onto [steps] points across the range so the
         * badge and the saved config show the same number the thumb implies
         * rather than the raw interpolation of wherever it was dropped.
         */
        private fun snap(v: Float, min: Float, max: Float, steps: Int): Float {
            if (max <= min) return min
            val t = ((v - min) / (max - min) * steps).toInt().coerceIn(0, steps)
            return min + (max - min) * (t.toFloat() / steps)
        }
    }
}
