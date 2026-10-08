package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Starred-mob ESP implementing Devonian's BoxStarMob styling, categorization,
 * and hitbox calculations:
 * - Packet-level and tick-level detection:
 *   - ClientboundSetEntityDataPacket: instant custom name tag checking for '✯'
 *   - ClientboundAddEntityPacket & ClientboundPlayerInfoUpdatePacket: miniboss tracking
 * - Armor stand mapping: nametag -> entity id - 1 (-3 for Withermancer)
 * - Fake player detection: Shadow Assassin, Lost Adventurer, Diamond Guy, King Midas
 * - Fels detection: Enderman named Dinnerbone or stand named Fels
 * - Bats detection
 * - Customizable line width, fill alpha, and colors
 */
object StarMobESP {

    enum class MobCategory {
        SHADOW_ASSASSIN,
        MINIBOSS,
        CHONK,
        SKELETON_MASTER,
        FEL,
        REGULAR
    }

    private val starMobs = LinkedHashMap<Int, MobCategory>()
    private val extraBats = HashSet<Int>()
    private val playerMobMap = ConcurrentHashMap<UUID, MobCategory>()
    private var lastStandId: Int = 0
    private var fallbackScanTicks: Int = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { queueBoxes() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetRun("ClientPlayConnectionEvents.JOIN") }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetRun("ClientPlayConnectionEvents.DISCONNECT") }
    }

    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return
        for (entry in packet.entries()) {
            val name = entry.profile?.name ?: continue
            val cat = categorizePlayer(name) ?: continue
            playerMobMap[entry.profileId] = cat
        }
    }

    fun onAddEntity(packet: ClientboundAddEntityPacket) {
        if (packet.type == EntityType.ARMOR_STAND) {
            lastStandId = packet.id
        }
    }

    fun onEntityData(packet: ClientboundSetEntityDataPacket) {
        val items = packet.packedItems
        val nameItem = items.firstOrNull { it.id == 2 } ?: return
        val opt = nameItem.value as? Optional<*> ?: return
        val comp = opt.orElse(null) as? Component ?: return
        val raw = comp.string
        val name = ChatFormatting.stripFormatting(raw) ?: raw
        if (!name.contains("✯")) return

        val normalized = name.uppercase()
        val offset = if (normalized.contains("WITHERMANCER")) 3 else 1
        val targetId = packet.id - offset
        val cat = categorize(normalized)
        val mc = Minecraft.getInstance()
        val level = mc.level
        val direct = level?.getEntity(targetId)
        if (direct != null && !isCandidateMob(direct, mc.player)) {
            return
        }
        if (!starMobs.containsKey(targetId)) {
            starMobs[targetId] = cat
        }
        extraBats.remove(targetId)
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (!Config.starMobEspEnabled || !DungeonContext.inDungeon || level == null) {
            if (starMobs.isNotEmpty()) {
                starMobs.clear()
                extraBats.clear()
            }
            return
        }

        val localPlayer = mc.player ?: return

        // Throttle world entity search to every 5 ticks (packets handle instant adds)
        if (++fallbackScanTicks % 5 == 0) {
            // Scan loaded armor stands continuously as a robust fallback
            for (stand in level.getEntitiesOfClass(ArmorStand::class.java, localPlayer.boundingBox.inflate(96.0))) {
                val raw = stand.customName?.string ?: continue
                val name = ChatFormatting.stripFormatting(raw) ?: continue
                if (!name.contains("✯")) continue
                val normalized = name.uppercase()
                val offset = if (normalized.contains("WITHERMANCER")) 3 else 1
                val direct = level.getEntity(stand.id - offset)
                val mob = if (isCandidateMob(direct, localPlayer)) direct else {
                    val bounds = stand.boundingBox.move(0.0, -1.0, 0.0).inflate(1.5, 2.5, 1.5)
                    level.getEntities(stand, bounds) { entity ->
                        isCandidateMob(entity, localPlayer)
                    }.minByOrNull { it.distanceToSqr(stand) }
                }
                if (mob != null && !starMobs.containsKey(mob.id)) {
                    val cat = categorize(normalized)
                    starMobs[mob.id] = cat
                }
                if (mob != null) extraBats.remove(mob.id)
            }

            // Bats (Starred / secret bats)
            if (Config.starMobEspBats) {
                for (bat in level.getEntitiesOfClass(net.minecraft.world.entity.ambient.Bat::class.java, localPlayer.boundingBox.inflate(64.0))) {
                    if (!bat.isInvisible && !bat.isPassenger && bat.health > 0f && !starMobs.containsKey(bat.id)) {
                        if (!isRoomCleared(bat.position())) {
                            starMobs[bat.id] = MobCategory.REGULAR
                            extraBats.add(bat.id)
                        }
                    }
                }
            }
        }

        // Purge dead, invalid, bat-disabled, or cleared-room entities every tick
        val iterator = starMobs.iterator()
        while (iterator.hasNext()) {
            val (id, _) = iterator.next()
            val entity = level.getEntity(id)
            if (entity == null || entity.isRemoved || (entity is LivingEntity && (entity.isDeadOrDying || entity.health <= 0f))) {
                iterator.remove()
                continue
            }
            if (entity !is LivingEntity || entity is ArmorStand) {
                iterator.remove()
                continue
            }
            if (entity is net.minecraft.world.entity.ambient.Bat && !Config.starMobEspBats) {
                iterator.remove()
                continue
            }
            if (isRoomCleared(entity.position())) {
                iterator.remove()
                continue
            }
        }
    }

    private fun isCandidateMob(entity: Entity?, local: Player?): Boolean = when {
        entity == null || entity is ArmorStand || entity.isRemoved -> false
        entity is net.minecraft.world.entity.ExperienceOrb -> false
        entity is net.minecraft.world.entity.projectile.arrow.AbstractArrow -> false
        entity is Player -> entity != local && entity.health > 0f &&
            (entity.uuid.version() == 2 || categorizePlayer(entity.gameProfile.name) != null)
        entity is LivingEntity -> entity.health > 0f && !entity.isDeadOrDying
        else -> false
    }

    private fun categorize(name: String): MobCategory = when {
        name.contains("SHADOW ASSASSIN") -> MobCategory.SHADOW_ASSASSIN
        name.contains("FEL") -> MobCategory.FEL
        name.contains("SKELETON MASTER") -> MobCategory.SKELETON_MASTER
        name.contains("WITHERMANCER") || name.contains("LORD") ||
            name.contains("ZOMBIE COMMANDER") || name.contains("SUPER ARCHER") -> MobCategory.CHONK
        name.contains("ADVENTURER") || name.contains("ANGRY ARCHAEOLOGIST") || name.contains("KING MIDAS") -> MobCategory.MINIBOSS
        else -> MobCategory.REGULAR
    }

    fun categorizePlayer(name: String): MobCategory? = when (name) {
        "Shadow Assassin" -> MobCategory.SHADOW_ASSASSIN
        "Lost Adventurer", "Diamond Guy", "King Midas", "Bonzo", "Scarf", "The Professor", "Livid" -> MobCategory.MINIBOSS
        else -> null
    }

    fun colorFor(category: MobCategory): Int = when {
        !Config.starMobEspByType -> Config.starMobColor
        category == MobCategory.SHADOW_ASSASSIN -> Config.starMobShadowAssassinColor
        category == MobCategory.MINIBOSS -> Config.starMobMinibossColor
        category == MobCategory.CHONK -> Config.starMobChonkColor
        category == MobCategory.SKELETON_MASTER -> Config.starMobSmColor
        category == MobCategory.FEL -> Config.starMobFelColor
        else -> Config.starMobColor
    }

    private fun getHeight(entity: Entity, category: MobCategory): Double = when (category) {
        MobCategory.CHONK -> if (entity is LivingEntity && entity.name.string.contains("Withermancer", ignoreCase = true)) 3.0 else 2.0
        MobCategory.FEL -> if (entity.isInvisible) 0.8 else 3.0
        MobCategory.SHADOW_ASSASSIN -> if (!Config.starMobShowFullShadow && entity.isInvisible) 0.8 else 2.0
        else -> 2.0
    }

    private fun isRoomCleared(pos: net.minecraft.world.phys.Vec3): Boolean {
        val comp = com.asthoonlite.dungeon.api.WorldPosition(pos.x.toInt(), pos.z.toInt()).toComponent()
        val mobRoom = if (comp.isInBounds()) com.asthoonlite.dungeon.map.DungeonScanner.rooms.getOrNull(comp.getRoomIdx()) else null
        if (mobRoom != null && (mobRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.WHITE ||
                mobRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.GREEN ||
                mobRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.FAILED)) {
            return true
        }
        val currentRoom = com.asthoonlite.dungeon.map.DungeonScanner.currentRoom
        if (currentRoom != null && (currentRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.WHITE ||
                currentRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.GREEN ||
                currentRoom.checkmark == com.asthoonlite.dungeon.api.mapEnums.CheckmarkTypes.FAILED)) {
            val player = Minecraft.getInstance().player
            if (player != null && isInSameRoom(pos, player.position())) {
                return true
            }
        }
        return false
    }

    fun shouldForceGlow(entity: Entity): Boolean {
        if (!Config.starMobEspEnabled || !DungeonContext.inDungeon || !starMobs.containsKey(entity.id)) return false
        val player = Minecraft.getInstance().player ?: return false
        if (!Config.starMobEspThroughWalls && !player.hasLineOfSight(entity)) return false
        if (isRoomCleared(entity.position())) return false
        return isInSameRoom(entity.position(), player.position())
    }

    fun glowColorFor(entity: Entity): Int {
        if (!shouldForceGlow(entity)) return Int.MIN_VALUE
        return colorFor(starMobs[entity.id] ?: MobCategory.REGULAR)
    }

    private fun isInSameRoom(mobPos: net.minecraft.world.phys.Vec3, playerPos: net.minecraft.world.phys.Vec3): Boolean {
        val playerComp = com.asthoonlite.dungeon.api.WorldPosition(playerPos.x.toInt(), playerPos.z.toInt()).toComponent()
        val mobComp = com.asthoonlite.dungeon.api.WorldPosition(mobPos.x.toInt(), mobPos.z.toInt()).toComponent()

        val playerRoom = com.asthoonlite.dungeon.map.DungeonScanner.currentRoom
            ?: (if (playerComp.isInBounds()) com.asthoonlite.dungeon.map.DungeonScanner.rooms.getOrNull(playerComp.getRoomIdx()) else null)

        val mobRoom = if (mobComp.isInBounds()) com.asthoonlite.dungeon.map.DungeonScanner.rooms.getOrNull(mobComp.getRoomIdx()) else null

        if (playerRoom != null && mobRoom != null) {
            return playerRoom === mobRoom
        }
        if (playerRoom != null) {
            return playerRoom.comps.any { it.cx / 2 == mobComp.x / 2 && it.cz / 2 == mobComp.z / 2 }
        }
        if (mobRoom != null) {
            return mobRoom.comps.any { it.cx / 2 == playerComp.x / 2 && it.cz / 2 == playerComp.z / 2 }
        }
        return playerComp.x / 2 == mobComp.x / 2 && playerComp.z / 2 == mobComp.z / 2
    }

    private fun queueBoxes() {
        if (!Config.starMobEspEnabled || !DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        val phase = Config.starMobEspThroughWalls

        for ((id, category) in starMobs) {
            if (Config.starMobRenderMode == 1 && id in extraBats) continue
            val entity = level.getEntity(id) ?: continue
            if (entity.isRemoved || (entity is LivingEntity && (entity.isDeadOrDying || entity.health <= 0f))) continue
            val pos = entity.position()
            if (isRoomCleared(pos)) continue
            // Restrict ESP to only mobs in the current room as the player
            if (!isInSameRoom(pos, player.position())) continue
            // If through-walls is off, skip mobs blocked by geometry
            if (!phase && !player.hasLineOfSight(entity)) continue

            val height = getHeight(entity, category)
            val color = colorFor(category)
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val bounds = renderBounds(Config.starMobRenderMode, entity.boundingBox, pos, height)
            val minX = bounds.minX
            val maxX = bounds.maxX
            val minY = bounds.minY
            val maxY = bounds.maxY
            val minZ = bounds.minZ
            val maxZ = bounds.maxZ

            val fillA = Config.starMobFillAlpha.toFloat()
            val thickness = (Config.starMobLineWidth * 0.007).coerceIn(0.01, 0.08)

            if (Config.starMobRenderMode != 1 && fillA > 0f) {
                WorldBoxRenderer.queueFilled(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, fillA, throughWalls = phase)
            }
            WorldBoxRenderer.queueOutline(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, 0.95f, thickness = thickness, throughWalls = phase)
        }
    }

    internal fun renderBounds(mode: Int, hitbox: AABB, pos: Vec3, height: Double): AABB =
        if (mode == 1) hitbox else AABB(pos.x - 0.4, pos.y, pos.z - 0.4, pos.x + 0.4, pos.y + height, pos.z + 0.4)

    fun resetRun(reason: String = "manual") {
        starMobs.clear()
        extraBats.clear()
        playerMobMap.clear()
        lastStandId = 0
        fallbackScanTicks = 0
    }
}
