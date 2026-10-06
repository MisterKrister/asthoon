package com.asthoonlite.mining

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.hud.AlertHud
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.util.regex.Pattern

/**
 * Ported/generalized from Scatha-Pro's ChatParser#parseTunnelVisionMessages
 * + CoreManager cooldown fields + TunnelVisionEffectProgressBar. Scatha's
 * version is hardcoded to the Tunnel Vision ability specifically; this
 * generalizes the same chat-parsing pattern to work for *any* pickaxe
 * ability (Mining Speed Boost, Mole difficulty pickaxe abilities, etc.),
 * reading the ability name/cooldown straight out of the held pickaxe's
 * lore instead of a hardcoded item check.
 *
 * Hypixel messages this relies on (confirmed against Scatha-Pro's source):
 *  - "You used your <Ability> Pickaxe Ability!"
 *  - "Your pickaxe ability is on cooldown for <N>s."
 * Both are generic across abilities, so no per-ability hardcoding needed.
 */
object PickaxeAbilityTimer : HudElement {

    private val usedPattern = Pattern.compile("You used your (.+) Pickaxe Ability!", Pattern.CASE_INSENSITIVE)
    private val cooldownPattern = Pattern.compile(
        "Your pickaxe ability is on cooldown for ([\\d.]+)s\\.?", Pattern.CASE_INSENSITIVE
    )

    private var abilityName: String? = null
    private var cooldownEndMs: Long = -1
    private var cooldownTotalMs: Long = -1
    private var alerted = false

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "pickaxe_ability_timer"),
            this
        )

        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (!overlay) onChat(ChatFormatting.stripFormatting(text.string) ?: "")
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (cooldownEndMs < 0) return@register
            val now = System.currentTimeMillis()
            if (now >= cooldownEndMs && !alerted) {
                alerted = true
                if (Config.pickaxeAbilityTimerEnabled) {
                    AlertHud.show("${abilityName ?: "Ability"} Ready!", 0xFF00C853.toInt(), SoundEvents.NOTE_BLOCK_PLING)
                }
            }
        }
    }

    private fun onChat(text: String) {
        if (!Config.pickaxeAbilityTimerEnabled) return

        val used = usedPattern.matcher(text)
        if (used.matches()) {
            val name = used.group(1)
            val cooldownSec = getAbilityCooldownSeconds(name)
            if (cooldownSec != null) {
                abilityName = name
                cooldownTotalMs = (cooldownSec * 1000).toLong()
                cooldownEndMs = System.currentTimeMillis() + cooldownTotalMs
                alerted = false
            }
            return
        }

        val cd = cooldownPattern.matcher(text)
        if (cd.matches()) {
            val seconds = cd.group(1).toDoubleOrNull() ?: return
            cooldownTotalMs = (seconds * 1000).toLong()
            cooldownEndMs = System.currentTimeMillis() + cooldownTotalMs
            alerted = false
        }
    }

    /**
     * Reads "Ability: <Name>" ... "Cooldown: Ns" out of the held item's
     * lore — same technique as Scatha-Pro's SkyBlockItemUtil, generalized
     * to match whatever ability name Hypixel just told us was used instead
     * of a hardcoded "Tunnel Vision" string.
     */
    private fun getAbilityCooldownSeconds(usedAbilityName: String): Double? {
        val stack: ItemStack = Minecraft.getInstance().player?.mainHandItem ?: return null
        val lore: ItemLore = stack.get(DataComponents.LORE) ?: return null

        var expectCooldownSoon = false
        for (line in lore.lines()) {
            val content = line.string
            if (content.contains("Ability: $usedAbilityName", ignoreCase = true)) {
                expectCooldownSoon = true
                continue
            }
            if (expectCooldownSoon && content.trimStart().startsWith("Cooldown:")) {
                val numeric = content.substringAfter("Cooldown:").trim().removeSuffix("s").trim()
                return numeric.toDoubleOrNull()
            }
        }
        return null
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (QuietMode.suppressing()) return
        if (!Config.pickaxeAbilityTimerEnabled) return
        if (cooldownEndMs < 0) return

        val now = System.currentTimeMillis()
        val remainingMs = (cooldownEndMs - now).coerceAtLeast(0)
        val progress = if (cooldownTotalMs > 0) {
            1f - (remainingMs.toFloat() / cooldownTotalMs.toFloat()).coerceIn(0f, 1f)
        } else 1f

        val mc = Minecraft.getInstance()
        val font = mc.font
        val scale = Config.pickaxeTimerScale.coerceIn(0.5f, 3f)

        val label = if (remainingMs <= 0) "${abilityName ?: "Ability"} Ready!"
                    else "${abilityName ?: "Ability"} — ${"%.1f".format(remainingMs / 1000f)}s"

        val barW = 100
        val barH = 6
        val panelW = maxOf(barW, font.width(label)) + 12
        val panelH = font.lineHeight + barH + 12

        val pose = context.pose()
        pose.pushMatrix()
        pose.translate(Config.pickaxeTimerX.toFloat(), Config.pickaxeTimerY.toFloat())
        pose.scale(scale, scale)

        context.fill(0, 0, panelW, panelH, 0xCC0D1F35.toInt())
        context.fill(0, 0, panelW, 1, 0x661E90FF.toInt())
        context.text(font, label, 6, 5, 0xFFE0EEFF.toInt())

        val barY = font.lineHeight + 8
        context.fill(6, barY, 6 + barW, barY + barH, 0xFF081220.toInt())
        val fillColor = if (remainingMs <= 0) 0xFF00C853.toInt() else 0xFF1E90FF.toInt()
        context.fill(6, barY, 6 + (barW * progress).toInt(), barY + barH, fillColor)

        pose.popMatrix()
    }

    fun panelSize(): Pair<Int, Int> {
        val font = Minecraft.getInstance().font
        val label = "${abilityName ?: "Ability"} — 10.0s"
        val w = maxOf(100, font.width(label)) + 12
        val h = font.lineHeight + 6 + 12
        return w to h
    }
}
