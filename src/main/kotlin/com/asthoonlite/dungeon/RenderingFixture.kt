package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/** Local fixture data reaches the client through normal inventory, map, scoreboard and entity packets. */
object RenderingFixture {
    const val WORLD_NAME = "ASL"
    private const val OBJECTIVE = "asl_render_test"
    @Volatile private var status = "not requested"

    fun diagnosticReport(): String = "Rendering fixture: $status"

    fun install(): String {
        val mc = Minecraft.getInstance()
        val server = mc.singleplayerServer ?: return "Rendering fixture requires a local singleplayer world."
        val worldName = server.worldData.levelName
        if (worldName !in setOf(WORLD_NAME, "ASL Rendering Test")) {
            return "Rendering fixture only changes a dedicated world named ASL or ASL Rendering Test."
        }
        val playerId = mc.player?.uuid ?: return "Rendering fixture requires a local player."
        status = "queued for $worldName"
        server.execute {
            try {
                val player = server.playerList.getPlayer(playerId)
                    ?: error("local player disconnected before fixture setup")
                val level = server.overworld()
                check(player.level() === level) { "enter the Overworld before installing the fixture" }
                val source = player.createCommandSourceStack()
                    .withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput()
                fun command(text: String) = server.commands.performPrefixedCommand(source, text)

                // Repeat requests replace only fixture entities, inside this dedicated test world.
                for (cx in (-194 shr 4)..(-167 shr 4)) for (cz in (-189 shr 4)..(-168 shr 4)) {
                    level.getChunk(cx, cz) // FULL chunks, synchronously loaded on the server thread.
                }
                command("kill @e[tag=asl_render_fixture]")
                command("fill -194 64 -189 -167 72 -168 minecraft:air")
                command("fill -194 63 -189 -167 63 -168 minecraft:stone")
                command("tp @s -177 64 -183 0 20")
                command("setblock -185 64 -181 minecraft:lever[face=floor,facing=north]")
                command("setblock -183 64 -181 minecraft:oak_button[face=floor,facing=north]")
                command("setblock -181 64 -181 minecraft:stone_button[face=floor,facing=north]")
                command("setblock -179 64 -181 minecraft:wither_skeleton_skull[rotation=0]")
                command("setblock -177 63 -181 minecraft:podzol")
                command("setblock -177 64 -181 minecraft:red_mushroom")
                command("setblock -175 63 -181 minecraft:podzol")
                command("setblock -175 64 -181 minecraft:brown_mushroom")
                for ((x, block) in listOf(-173 to "wither_skeleton_wall_skull[facing=north]",
                    -171 to "lever[face=wall,facing=north]", -169 to "stone_button[face=wall,facing=north]")) {
                    command("setblock $x 64 -179 minecraft:stone")
                    command("setblock $x 64 -180 minecraft:$block")
                }
                command("setblock -180 67 -175 minecraft:stone")
                command("setblock -180 66 -175 minecraft:stone_button[face=ceiling,facing=north]")
                command("summon minecraft:cow -183 64 -174 {Tags:['asl_render_fixture'],Invulnerable:1b,NoAI:1b,Silent:1b,PersistenceRequired:1b}")
                command("summon minecraft:armor_stand -183 65.5 -174 {Tags:['asl_render_fixture'],NoGravity:1b,Invulnerable:1b,Invisible:1b,CustomName:{text:'✯ Zombie'},CustomNameVisible:1b}")

                val controls = listOf(
                    BlockPos(-185, 64, -181) to Blocks.LEVER,
                    BlockPos(-183, 64, -181) to Blocks.OAK_BUTTON,
                    BlockPos(-181, 64, -181) to Blocks.STONE_BUTTON,
                    BlockPos(-179, 64, -181) to Blocks.WITHER_SKELETON_SKULL,
                    BlockPos(-177, 64, -181) to Blocks.RED_MUSHROOM,
                    BlockPos(-175, 64, -181) to Blocks.BROWN_MUSHROOM,
                    BlockPos(-173, 64, -180) to Blocks.WITHER_SKELETON_WALL_SKULL,
                    BlockPos(-171, 64, -180) to Blocks.LEVER,
                    BlockPos(-169, 64, -180) to Blocks.STONE_BUTTON,
                    BlockPos(-180, 66, -175) to Blocks.STONE_BUTTON)
                check(controls.all { (pos, block) -> level.getBlockState(pos).block === block }) {
                    "a server block command did not create its interaction sample"
                }
                val entityBounds = AABB(-194.0, 63.0, -189.0, -166.0, 73.0, -167.0)
                check(level.getEntitiesOfClass(Cow::class.java, entityBounds)
                    .any { "asl_render_fixture" in it.entityTags() } &&
                    level.getEntitiesOfClass(ArmorStand::class.java, entityBounds)
                        .any { "asl_render_fixture" in it.entityTags() && it.customName?.string == "✯ Zombie" }) {
                    "server entity commands did not create the named highlight fixture"
                }

                val scoreboard = server.scoreboard
                val objective = scoreboard.getObjective(OBJECTIVE) ?: scoreboard.addObjective(
                    OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("ASL rendering fixture"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null)
                val score = scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("asl_floor"), objective)
                score.set(1)
                score.display(Component.literal("The Catacombs (F7)"))
                scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective)

                val map = MapItem.create(level, -185, -185, 0.toByte(), true, false)
                val id = requireNotNull(map.get(DataComponents.MAP_ID))
                val data = requireNotNull(MapItem.getSavedData(map, level))
                for ((cell, color) in listOf(
                    (0 to 0) to 30, (1 to 0) to 63, (2 to 0) to 85,
                    (0 to 1) to 66, (1 to 1) to 82, (2 to 1) to 18)) {
                    val (cx, cz) = cell
                    for (z in 5 + cz * 20 until 21 + cz * 20) {
                        for (x in 5 + cx * 20 until 21 + cx * 20) data.setColor(x, z, color.toByte())
                    }
                }
                for (z in 12..15) for (x in 32..35) data.setColor(x, z, 30.toByte())
                level.setMapData(id, data.locked()) // Vanilla terrain updates must retain these explicit test pixels.
                player.inventory.setItem(player.inventory.selectedSlot, map)
                player.inventoryMenu.broadcastChanges()
                status = "ready: real map ${id.id()}, F7 sidebar, named mob and interaction samples near -177,64,-181; feature settings unchanged"
            } catch (e: Exception) {
                status = "failed: ${e.javaClass.simpleName}: ${e.message}"
                AsthoonLite.LOGGER.error("[AsthoonLite] Local rendering fixture setup failed", e)
            }
            mc.execute { mc.player?.sendSystemMessage(Component.literal("ASL fixture $status")) }
        }
        return "Rendering fixture queued in $worldName; feature settings remain unchanged."
    }
}
