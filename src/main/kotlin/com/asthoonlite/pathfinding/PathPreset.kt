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
 * Coordinate, orientation, and action point within a recorded path.
 */
data class PathPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val action: String = "MOVE",
    val speedBps: Double = 0.0,
    val delayMs: Long = 0L
)

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
}
