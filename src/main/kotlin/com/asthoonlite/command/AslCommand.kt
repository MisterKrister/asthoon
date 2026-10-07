package com.asthoonlite.command

import com.asthoonlite.AsthoonLite
import com.asthoonlite.gui.AsthoonLiteScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

import com.asthoonlite.utils.InputCapture

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
                            ClientCommands.literal("capture")
                                .executes { _ ->
                                    InputCapture.toggle()
                                    1
                                }
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
