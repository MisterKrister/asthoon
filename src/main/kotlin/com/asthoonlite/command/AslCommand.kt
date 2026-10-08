package com.asthoonlite.command

import com.asthoonlite.AsthoonLite
import com.asthoonlite.gui.AsthoonLiteScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

import com.asthoonlite.utils.InputCapture
import com.asthoonlite.pathfinding.PathfindCapture

object AslCommand {

    private fun openConfigScreen() {
        val mc = Minecraft.getInstance()
        Thread {
            Thread.sleep(50)
            mc.execute {
                try {
                    mc.setScreen(AsthoonLiteScreen())
                } catch (e: Exception) {
                    AsthoonLite.LOGGER.error("[AsthoonLite] Failed to open screen", e)
                    mc.player?.sendSystemMessage(
                        Component.literal("§c[AsthoonLite] Error opening menu — check logs")
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (alias in listOf("asl", "asthoon")) {
                dispatcher.register(
                    ClientCommands.literal(alias)
                        .then(
                            ClientCommands.literal("termsim")
                                .executes { _ ->
                                    com.asthoonlite.dungeon.simulator.TerminalSimulator.requestStart()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("capture")
                                .then(
                                    ClientCommands.literal("1")
                                        .executes { _ ->
                                            InputCapture.toggle()
                                            1
                                        }
                                )
                                .then(
                                    ClientCommands.literal("2")
                                        .executes { _ ->
                                            PathfindCapture.toggle()
                                            1
                                        }
                                )
                                .executes { ctx ->
                                    ctx.source.sendFeedback(
                                        Component.literal("§a[AsthoonLite] §fCapture modes: §e/asl capture 1 §7(Input/Terminal) §f• §e/asl capture 2 §7(Pathfinding & Movement)")
                                    )
                                    InputCapture.toggle()
                                    1
                                }
                        )
                        .then(
                            ClientCommands.literal("routes")
                                .then(
                                    ClientCommands.literal("crosshairselect")
                                        .then(
                                            ClientCommands.literal("true")
                                                .executes { ctx ->
                                                    com.asthoonlite.pathfinding.RouteEditor.pickBlockMode = true
                                                    ctx.source.sendFeedback(Component.literal("§a[AsthoonLite] §fCrosshair select mode: §2ENABLED"))
                                                    1
                                                }
                                        )
                                        .then(
                                            ClientCommands.literal("false")
                                                .executes { ctx ->
                                                    com.asthoonlite.pathfinding.RouteEditor.pickBlockMode = false
                                                    ctx.source.sendFeedback(Component.literal("§a[AsthoonLite] §fCrosshair select mode: §cDISABLED"))
                                                    1
                                                }
                                        )
                                        .executes { ctx ->
                                            com.asthoonlite.pathfinding.RouteEditor.pickBlockMode = !com.asthoonlite.pathfinding.RouteEditor.pickBlockMode
                                            val st = if (com.asthoonlite.pathfinding.RouteEditor.pickBlockMode) "§2ENABLED" else "§cDISABLED"
                                            ctx.source.sendFeedback(Component.literal("§a[AsthoonLite] §fCrosshair select mode: $st"))
                                            1
                                        }
                                )
                        )
                        .executes { _ ->
                            openConfigScreen()
                            1
                        }
                )
            }
        }
    }
}
