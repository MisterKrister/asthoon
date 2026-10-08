package com.asthoonlite.pathfinding

import com.asthoonlite.AsthoonLite
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Interactive Route Node Editor:
 * - Allows players to create, edit, remove, and reorder movement nodes.
 * - Supports manual coordinate creation and instant node placement at player coordinates.
 * - Provides an in-world "Pick with Crosshair" mode: highlights targeted blocks in real-time
 *   and appends nodes upon left-click.
 * - Restricts movement options for M7 (P1-P5) routes (strictly no AOTV or Etherwarp).
 */
object RouteEditor {

    var activePreset: PathPreset? = null

    var pickBlockMode: Boolean = false
        set(value) {
            field = value
            if (!value) targetedBlock = null
        }

    var targetedBlock: BlockPos? = null
        private set

    var pickLookNodeMode: Boolean = false
        set(value) {
            field = value
            if (!value) targetedLookPos = null
        }

    var targetedLookPos: Vec3? = null
        private set

    var defaultNodeType: RouteNodeType = RouteNodeType.WALK

    // Currently selected node for coordinate/type editing in the GUI (-1 = none)
    var editingNodeIndex: Int = -1

    // Drag-and-drop reordering state
    var draggedNodeIndex: Int = -1
    var dragHoverIndex: Int = -1

    private var wasLeftMouseDown = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register { mc, screen, _, _ ->
            if ((pickBlockMode || pickLookNodeMode) && screen is net.minecraft.client.gui.screens.PauseScreen) {
                pickBlockMode = false
                pickLookNodeMode = false
                mc.setScreen(null)
                mc.player?.sendSystemMessage(
                    Component.literal("§e[AsthoonLite] §fCrosshair select mode §cDISABLED§f.")
                )
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            finishEditing()
        }
    }

    fun finishEditing() {
        activePreset = null
        editingNodeIndex = -1
        pickBlockMode = false
        pickLookNodeMode = false
        targetedBlock = null
        targetedLookPos = null
        draggedNodeIndex = -1
        dragHoverIndex = -1
        PathPresetManager.savePresets()
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        // 1. Raycast block when pick-block mode is active
        if (pickBlockMode) {
            val hit = mc.hitResult as? BlockHitResult
            if (hit != null && hit.type == HitResult.Type.BLOCK) {
                targetedBlock = hit.blockPos
            } else {
                targetedBlock = null
            }

            // In-game click detection (when no menu/screen is open)
            if (mc.screen == null) {
                val isLeftDown = mc.mouseHandler.isLeftPressed
                if (isLeftDown && !wasLeftMouseDown) {
                    val pos = targetedBlock
                    val preset = activePreset
                    if (pos != null && preset != null) {
                        // Place node centered on top of targeted block
                        val node = PathPoint(
                            x = pos.x + 0.5,
                            y = pos.y + 1.0,
                            z = pos.z + 0.5,
                            yaw = player.yRot,
                            pitch = player.xRot,
                            action = defaultNodeType.name
                        )
                        preset.points.add(node)
                        PathPresetManager.savePresets()

                        player.sendSystemMessage(
                            Component.literal("§a[AsthoonLite] §fAdded node §e#${preset.points.size} §7[§d${defaultNodeType.displayName}§7] at §b(${pos.x}, ${pos.y + 1}, ${pos.z})")
                        )
                        level.playLocalSound(
                            player.x, player.y, player.z,
                            SoundEvents.EXPERIENCE_ORB_PICKUP,
                            SoundSource.PLAYERS,
                            0.8f, 1.4f, false
                        )
                    }
                }
                wasLeftMouseDown = isLeftDown
            } else {
                wasLeftMouseDown = false
            }
        } else if (pickLookNodeMode) {
            val eyePos = player.eyePosition
            val lookVec = player.lookAngle
            val hit = mc.hitResult
            val targetPos: Vec3 = if (hit != null && hit.type == HitResult.Type.BLOCK) {
                hit.location
            } else {
                // In the air: 5.5 blocks ahead along line of sight
                eyePos.add(lookVec.scale(5.5))
            }
            targetedLookPos = targetPos

            if (mc.screen == null) {
                val isLeftDown = mc.mouseHandler.isLeftPressed
                if (isLeftDown && !wasLeftMouseDown) {
                    val preset = activePreset
                    val editIdx = editingNodeIndex
                    if (preset != null && editIdx in preset.points.indices) {
                        val node = preset.points[editIdx]
                        node.hasLookNode = true
                        node.lookX = Math.round(targetPos.x * 100.0) / 100.0
                        node.lookY = Math.round(targetPos.y * 100.0) / 100.0
                        node.lookZ = Math.round(targetPos.z * 100.0) / 100.0
                        PathPresetManager.savePresets()

                        player.sendSystemMessage(
                            Component.literal("§a[AsthoonLite] §fSet Look Target for node §e#${editIdx + 1} §fat §b(${node.lookX}, ${node.lookY}, ${node.lookZ})")
                        )
                        level.playLocalSound(
                            player.x, player.y, player.z,
                            SoundEvents.EXPERIENCE_ORB_PICKUP,
                            SoundSource.PLAYERS,
                            0.8f, 1.4f, false
                        )
                        pickLookNodeMode = false
                    }
                }
                wasLeftMouseDown = isLeftDown
            } else {
                wasLeftMouseDown = false
            }
        } else {
            targetedBlock = null
            targetedLookPos = null
            wasLeftMouseDown = false
        }
    }

    fun addNode(point: PathPoint) {
        val preset = activePreset ?: return
        preset.points.add(point)
        PathPresetManager.savePresets()
    }

    fun addNodeAtPlayer() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val preset = activePreset ?: return

        val point = PathPoint(
            x = (player.x * 100.0).toLong() / 100.0,
            y = (player.y * 100.0).toLong() / 100.0,
            z = (player.z * 100.0).toLong() / 100.0,
            yaw = ((player.yRot * 10.0).toLong() / 10.0).toFloat(),
            pitch = ((player.xRot * 10.0).toLong() / 10.0).toFloat(),
            action = defaultNodeType.name
        )
        preset.points.add(point)
        PathPresetManager.savePresets()
        player.sendSystemMessage(
            Component.literal("§a[AsthoonLite] §fCreated node §e#${preset.points.size} §7[§d${defaultNodeType.displayName}§7] at player coordinates §b(${point.x}, ${point.y}, ${point.z})")
        )
    }

    fun updateNode(index: Int, point: PathPoint): Boolean {
        val preset = activePreset ?: return false
        if (index !in preset.points.indices) return false
        preset.points[index] = point
        PathPresetManager.savePresets()
        return true
    }

    fun removeNode(index: Int): Boolean {
        val preset = activePreset ?: return false
        if (index !in preset.points.indices) return false
        preset.points.removeAt(index)
        if (editingNodeIndex == index) editingNodeIndex = -1
        PathPresetManager.savePresets()
        return true
    }

    fun clearNodes(): Boolean {
        val preset = activePreset ?: return false
        preset.points.clear()
        editingNodeIndex = -1
        PathPresetManager.savePresets()
        return true
    }

    fun moveNode(fromIndex: Int, toIndex: Int): Boolean {
        val preset = activePreset ?: return false
        val success = preset.moveNode(fromIndex, toIndex)
        if (success) {
            PathPresetManager.savePresets()
        }
        return success
    }

    fun swapNodes(idx1: Int, idx2: Int): Boolean {
        val preset = activePreset ?: return false
        val success = preset.swapNodes(idx1, idx2)
        if (success) {
            PathPresetManager.savePresets()
        }
        return success
    }
}
