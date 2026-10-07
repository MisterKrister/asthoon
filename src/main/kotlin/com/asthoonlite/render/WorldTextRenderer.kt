package com.asthoonlite.render

import com.asthoonlite.QuietMode
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font

object WorldTextRenderer {
    data class TextEntry(
        val text: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val scale: Float = 1.0f,
        val color: Int = 0xFFFFFF,
        val throughWalls: Boolean = true
    )

    private val queue = ArrayList<TextEntry>()

    fun register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context ->
            render(context)
        }
    }

    fun queueText(
        text: String,
        x: Double,
        y: Double,
        z: Double,
        scale: Float = 1.0f,
        color: Int = 0xFFFFFF,
        throughWalls: Boolean = true
    ) {
        if (QuietMode.suppressing()) return
        queue.add(TextEntry(text, x, y, z, scale, color, throughWalls))
    }

    private fun render(context: LevelRenderContext) {
        // Quiet mode can flip mid-frame; drop anything already queued rather
        // than drawing a label the capture would have seen last frame.
        if (QuietMode.suppressing()) {
            queue.clear()
            return
        }
        if (queue.isEmpty()) return
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera
        val camPos = camera.position()
        val stack = context.poseStack()
        val bufferSource = context.bufferSource()
        val font = mc.font

        for (i in queue.indices) {
            val entry = queue[i]
            val rx = entry.x - camPos.x
            val ry = entry.y - camPos.y
            val rz = entry.z - camPos.z

            val offset = -font.width(entry.text) * 0.5f
            val sc = entry.scale

            stack.pushPose()
            stack.translate(rx, ry, rz)
            stack.mulPose(camera.rotation())
            stack.scale(sc * 0.025f, -sc * 0.025f, sc * 0.025f)

            val mode = if (entry.throughWalls) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL
            val matrix = stack.last().pose()
            font.drawInBatch(
                entry.text,
                offset,
                0f,
                entry.color,
                false,
                matrix,
                bufferSource,
                mode,
                0,
                0xF000F0
            )

            stack.popPose()
        }
        queue.clear()
        bufferSource.endBatch()
    }
}
