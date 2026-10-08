package com.asthoonlite.pathfinding

import java.util.UUID

/**
 * Route category classification for pathfinding presets.
 * Each category features distinct thematic color-coding.
 */
enum class RouteCategory(
    val displayName: String,
    val color: Int,
    val glowColor: Int
) {
    MINING("Mining", 0xFFEAB308.toInt(), 0x55EAB308),
    COMBAT("Combat", 0xFFEF4444.toInt(), 0x55EF4444),
    DUNGEONS("Dungeons", 0xFF8B5CF6.toInt(), 0x558B5CF6),
    M7("M7", 0xFFEC4899.toInt(), 0x55EC4899);

    fun getSubcategories(): List<RouteSubcategory> = when (this) {
        MINING -> listOf(
            RouteSubcategory("Macro", 0xFFFDE047.toInt()),
            RouteSubcategory("Powder", 0xFFCA8A04.toInt()),
            RouteSubcategory("Return Route", 0xFFA16207.toInt())
        )
        COMBAT -> listOf(
            RouteSubcategory("Mob", 0xFFF87171.toInt()),
            RouteSubcategory("Return Route", 0xFFB91C1C.toInt())
        )
        DUNGEONS -> emptyList()
        M7 -> listOf(
            RouteSubcategory("P1", 0xFFF472B6.toInt()),
            RouteSubcategory("P2", 0xFFEC4899.toInt()),
            RouteSubcategory("P3", 0xFFDB2777.toInt()),
            RouteSubcategory("P4", 0xFFBE185D.toInt()),
            RouteSubcategory("P5", 0xFF9D174D.toInt())
        )
    }

    companion object {
        fun fromString(name: String): RouteCategory =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) } ?: MINING
    }
}

/**
 * Subcategory with a shade derived from the parent category.
 */
data class RouteSubcategory(
    val name: String,
    val color: Int
)

/**
 * Node type classification for movement and actions in pathfinding.
 * For M7 (P1-P5), mobility is restricted: no AOTV or Etherwarp, only WALK, BONZO_STAFF, INTERACT, JUMP.
 */
enum class RouteNodeType(
    val displayName: String,
    val badgeColor: Int,
    val description: String
) {
    WALK("Walk", 0xFF10B981.toInt(), "Traverse on foot towards waypoint"),
    BONZO_STAFF("Bonzo Staff", 0xFFEC4899.toInt(), "Fires Bonzo's Staff explosive recoil to launch forward"),
    JUMP("Jump", 0xFFF59E0B.toInt(), "Jumps while moving towards waypoint"),
    CROUCH("Crouch", 0xFF6366F1.toInt(), "Crouches/sneaks while traversing or holding waypoint"),
    TERMINAL("Terminal", 0xFF06B6D4.toInt(), "Aims camera at terminal and opens with triggerbot"),
    SIMON_SAYS("Simon Says", 0xFF8B5CF6.toInt(), "Stands at waypoint until Simon Says device completes"),
    ARROWS_ALIGN("Arrows Align", 0xFF3B82F6.toInt(), "Stands at waypoint until Arrows Align completes"),
    TIMEOUT("Timeout", 0xFFF97316.toInt(), "Waits configured seconds standing still"),
    INTERACT("Interact", 0xFF38BDF8.toInt(), "Clicks or triggers lever, button, or device");

    companion object {
        fun fromString(name: String): RouteNodeType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) } ?: WALK

        fun allowedForCategory(category: RouteCategory): List<RouteNodeType> = when (category) {
            RouteCategory.M7 -> listOf(WALK, BONZO_STAFF, JUMP, CROUCH, TERMINAL, SIMON_SAYS, ARROWS_ALIGN, TIMEOUT, INTERACT)
            else -> entries
        }
    }
}

/**
 * Coordinate, orientation, and action point within a recorded path or route.
 */
data class PathPoint(
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var action: String = "WALK",
    var speedBps: Double = 0.0,
    var delayMs: Long = 0L,
    var note: String = "",
    var hasLookNode: Boolean = false,
    var lookX: Double = 0.0,
    var lookY: Double = 0.0,
    var lookZ: Double = 0.0,
    var timeoutSeconds: Double = 1.0
) {
    fun nodeType(): RouteNodeType = RouteNodeType.fromString(action)
}

/**
 * User-created pathfinding route preset.
 */
data class PathPreset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var category: String,
    var subcategory: String,
    val createdAt: Long = System.currentTimeMillis(),
    var description: String = "",
    val points: MutableList<PathPoint> = mutableListOf()
) {
    fun routeCategory(): RouteCategory = RouteCategory.fromString(category)

    fun subcategoryColor(): Int {
        val cat = routeCategory()
        return cat.getSubcategories().firstOrNull { it.name.equals(subcategory, ignoreCase = true) }?.color ?: cat.color
    }

    fun swapNodes(i: Int, j: Int): Boolean {
        if (i !in points.indices || j !in points.indices || i == j) return false
        val temp = points[i]
        points[i] = points[j]
        points[j] = temp
        return true
    }

    fun moveNode(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in points.indices || toIndex !in points.indices || fromIndex == toIndex) return false
        val item = points.removeAt(fromIndex)
        points.add(toIndex, item)
        return true
    }
}
