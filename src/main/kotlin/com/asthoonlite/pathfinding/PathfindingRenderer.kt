package com.asthoonlite.pathfinding

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import com.asthoonlite.render.WorldTextRenderer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import kotlin.math.sin

/**
 * 3D World-Space Renderer for Pathfinding Routes:
 * - Previews active route nodes with color-coded bounding boxes according to [RouteNodeType].
 * - Renders connecting tracer lines between consecutive waypoints via [WorldBoxRenderer.queueLine].
 * - Displays in-world floating labels with node index (#1, #2...) and action type via [WorldTextRenderer].
 * - Highlights the targeted block in real-time when the route editor's "Pick with Crosshair" mode is active.
 * - Highlights the currently selected/edited node with a pulse effect.
 */
object PathfindingRenderer {

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register { _ ->
            renderBoxesAndTracers()
        }
    }

    private fun renderBoxesAndTracers() {
        if (QuietMode.suppressing()) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        // 1. Highlight targeted block when "Pick with Crosshair" mode is active
        if (RouteEditor.pickBlockMode) {
            val targetedPos = RouteEditor.targetedBlock
            if (targetedPos != null) {
                val bx = targetedPos.x.toDouble()
                val by = targetedPos.y.toDouble()
                val bz = targetedPos.z.toDouble()

                // Glowing cyan/gold selection box
                val pulse = (sin(System.currentTimeMillis() / 150.0) * 0.15 + 0.85).toFloat()
                WorldBoxRenderer.queueFilled(
                    bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                    0.22f * pulse, 0.74f * pulse, 0.97f * pulse, 0.28f,
                    throughWalls = false
                )
                WorldBoxRenderer.queueOutline(
                    bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                    0.38f * pulse, 0.85f * pulse, 1.0f * pulse, 0.95f,
                    thickness = 0.04,
                    throughWalls = false
                )
            }
        }

        // 1b. Highlight targeted position (air or block) when "Pick Look Node" mode is active
        if (RouteEditor.pickLookNodeMode) {
            val target = RouteEditor.targetedLookPos
            if (target != null) {
                val pulse = (sin(System.currentTimeMillis() / 150.0) * 0.15 + 0.85).toFloat()
                val half = 0.20
                WorldBoxRenderer.queueFilled(
                    target.x - half, target.y - half, target.z - half,
                    target.x + half, target.y + half, target.z + half,
                    0.85f * pulse, 0.25f * pulse, 0.95f * pulse, 0.40f,
                    throughWalls = true
                )
                WorldBoxRenderer.queueOutline(
                    target.x - half, target.y - half, target.z - half,
                    target.x + half, target.y + half, target.z + half,
                    0.95f * pulse, 0.45f * pulse, 1.0f * pulse, 0.95f,
                    thickness = 0.04,
                    throughWalls = true
                )
                WorldTextRenderer.queueText(
                    "⌖ CLICK TO SET LOOK NODE (AIR/BLOCK)",
                    target.x, target.y + 0.45, target.z,
                    scale = 0.85f,
                    color = 0xFFD946EF.toInt(),
                    throughWalls = true
                )
            }
        }

        // 2. Render active preset nodes and tracers
        val preset = RouteEditor.activePreset
            ?: if (Config.pathfindingEnabled || Config.pathfindingDebugRender) {
                PathPresetManager.getPresetById(Config.activePathfindingPresetId)
            } else null
        if (preset == null) return

        if (!Config.pathfindingDebugRender && !PathExecutor.isActive && !RouteEditor.pickBlockMode && !RouteEditor.pickLookNodeMode && !RouteEditor.nodeViewMode) {
            return
        }

        val points = preset.points
        if (points.isEmpty()) return

        val cat = preset.routeCategory()
        val lineR = ((cat.color shr 16) and 0xFF) / 255.0f
        val lineG = ((cat.color shr 8) and 0xFF) / 255.0f
        val lineB = (cat.color and 0xFF) / 255.0f

        val now = System.currentTimeMillis()

        for (i in points.indices) {
            val pt = points[i]
            val nodeType = pt.nodeType()
            val badgeCol = nodeType.badgeColor

            var r = ((badgeCol shr 16) and 0xFF) / 255.0f
            var g = ((badgeCol shr 8) and 0xFF) / 255.0f
            var b = (badgeCol and 0xFF) / 255.0f
            var a = 0.35f

            val isEditing = (i == RouteEditor.editingNodeIndex)
            val isCurrentPathExec = (PathExecutor.isActive && PathExecutor.currentNodeIndex == i)

            if (isEditing || isCurrentPathExec) {
                val pulse = (sin(now / 120.0) * 0.25 + 0.75).toFloat()
                r = minOf(1.0f, r * pulse + 0.2f)
                g = minOf(1.0f, g * pulse + 0.2f)
                b = minOf(1.0f, b * pulse + 0.2f)
                a = 0.65f
            }

            if (nodeType == RouteNodeType.BREAK) {
                // Full block outline: 1.0 x 1.0 x 1.0 enclosing the target block to break
                val bx = kotlin.math.floor(pt.x)
                val by = kotlin.math.floor(pt.y)
                val bz = kotlin.math.floor(pt.z)
                WorldBoxRenderer.queueFilled(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, r, g, b, a * 0.45f, throughWalls = true)
                WorldBoxRenderer.queueOutline(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, r, g, b, 0.95f, thickness = 0.04, throughWalls = true)
            } else {
                // Waypoint cube: 0.5 x 0.5 x 0.5 centered at (pt.x, pt.y, pt.z)
                val half = 0.25
                val x1 = pt.x - half
                val y1 = pt.y
                val z1 = pt.z - half
                val x2 = pt.x + half
                val y2 = pt.y + 0.5
                val z2 = pt.z + half

                WorldBoxRenderer.queueFilled(x1, y1, z1, x2, y2, z2, r, g, b, a, throughWalls = true)
                WorldBoxRenderer.queueOutline(x1, y1, z1, x2, y2, z2, r, g, b, 0.90f, thickness = 0.03, throughWalls = true)
            }

            // In-world label: #1 [WALK] or #1 [BREAK] or #1 [TIMEOUT 2.0s]
            val labelY = if (nodeType == RouteNodeType.BREAK) kotlin.math.floor(pt.y) + 1.15 else pt.y + 0.70
            val label = if (nodeType == RouteNodeType.TIMEOUT) {
                "#${i + 1} [${nodeType.displayName} ${pt.timeoutSeconds}s]"
            } else {
                "#${i + 1} [${nodeType.displayName}]"
            }
            WorldTextRenderer.queueText(
                label,
                pt.x, labelY, pt.z,
                scale = 0.85f,
                color = badgeCol,
                throughWalls = true
            )

            // Look Node Marker & Tracer Line
            if (pt.hasLookNode) {
                val lHalf = 0.16
                WorldBoxRenderer.queueFilled(
                    pt.lookX - lHalf, pt.lookY - lHalf, pt.lookZ - lHalf,
                    pt.lookX + lHalf, pt.lookY + lHalf, pt.lookZ + lHalf,
                    0.20f, 0.80f, 0.95f, 0.45f,
                    throughWalls = true
                )
                WorldBoxRenderer.queueOutline(
                    pt.lookX - lHalf, pt.lookY - lHalf, pt.lookZ - lHalf,
                    pt.lookX + lHalf, pt.lookY + lHalf, pt.lookZ + lHalf,
                    0.35f, 0.90f, 1.0f, 0.90f,
                    thickness = 0.03,
                    throughWalls = true
                )
                WorldTextRenderer.queueText(
                    "#${i + 1} [LOOK]",
                    pt.lookX, pt.lookY + 0.35, pt.lookZ,
                    scale = 0.70f,
                    color = 0xFF38BDF8.toInt(),
                    throughWalls = true
                )
                WorldBoxRenderer.queueLine(
                    pt.x, pt.y + 0.25, pt.z,
                    pt.lookX, pt.lookY, pt.lookZ,
                    0.25f, 0.75f, 0.95f, 0.70f,
                    thickness = 0.03,
                    throughWalls = true
                )
            }

            // Tracer line to the next node
            if (i < points.size - 1) {
                val nextPt = points[i + 1]
                val fromY = if (nodeType == RouteNodeType.BREAK) kotlin.math.floor(pt.y) + 0.5 else pt.y + 0.25
                val toY = if (nextPt.nodeType() == RouteNodeType.BREAK) kotlin.math.floor(nextPt.y) + 0.5 else nextPt.y + 0.25
                WorldBoxRenderer.queueLine(
                    pt.x, fromY, pt.z,
                    nextPt.x, toY, nextPt.z,
                    lineR, lineG, lineB, 0.85f,
                    thickness = 0.045,
                    throughWalls = true
                )
            }
        }

        // Active path execution tracer from player to current target
        if (PathExecutor.isActive && PathExecutor.currentNodeIndex in points.indices) {
            val player = mc.player
            if (player != null) {
                val targetPt = points[PathExecutor.currentNodeIndex]
                WorldBoxRenderer.queueLine(
                    player.x, player.y + 0.1, player.z,
                    targetPt.x, targetPt.y + 0.25, targetPt.z,
                    0.20f, 0.95f, 0.40f, 0.95f,
                    thickness = 0.06,
                    throughWalls = true
                )
            }
        }
    }
}
