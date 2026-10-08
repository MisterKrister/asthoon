package com.asthoonlite.pathfinding

import com.asthoonlite.AsthoonLite
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Manages loading, persistence, and import/export of user pathfinding presets.
 */
object PathPresetManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val presets = mutableListOf<PathPreset>()

    private val presetsDir: File by lazy {
        val dir = File(Minecraft.getInstance().gameDirectory, "asthoonlite/pathfinding")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val presetsFile: File by lazy {
        File(presetsDir, "presets.json")
    }

    fun init() {
        loadPresets()
    }

    fun getPresets(): List<PathPreset> = synchronized(presets) {
        presets.toList()
    }

    fun addPreset(preset: PathPreset) {
        synchronized(presets) {
            presets.removeAll { it.id == preset.id }
            presets.add(0, preset) // Insert at top
        }
        savePresets()
    }

    fun deletePreset(id: String): Boolean {
        val removed = synchronized(presets) {
            presets.removeIf { it.id == id }
        }
        if (removed) savePresets()
        return removed
    }

    fun getPresetById(id: String): PathPreset? = synchronized(presets) {
        presets.firstOrNull { it.id == id }
    }

    fun loadPresets() {
        synchronized(presets) {
            presets.clear()
            if (presetsFile.exists()) {
                try {
                    FileReader(presetsFile).use { reader ->
                        val listType = object : TypeToken<List<PathPreset>>() {}.type
                        val loaded: List<PathPreset>? = gson.fromJson(reader, listType)
                        if (loaded != null) {
                            presets.addAll(loaded)
                        }
                    }
                } catch (e: Exception) {
                    AsthoonLite.LOGGER.error("[AsthoonLite] Failed to load presets.json", e)
                }
            }

            if (presets.isEmpty()) {
                seedDefaultPresets()
                savePresets()
            }
        }
    }

    fun savePresets() {
        synchronized(presets) {
            try {
                if (!presetsDir.exists()) presetsDir.mkdirs()
                FileWriter(presetsFile).use { writer ->
                    gson.toJson(presets, writer)
                }
            } catch (e: Exception) {
                AsthoonLite.LOGGER.error("[AsthoonLite] Failed to save presets.json", e)
            }
        }
    }

    private fun seedDefaultPresets() {
        presets.add(
            PathPreset(
                name = "Dwarven Mithril Macro Route",
                category = "Mining",
                subcategory = "Macro",
                description = "Optimized vein pathing for Dwarven Mines mithril nodes",
                points = mutableListOf(
                    PathPoint(0.0, 70.0, 0.0, 0f, 0f, "MOVE", 5.0),
                    PathPoint(4.0, 70.0, 3.0, 45f, 10f, "MOVE", 5.0),
                    PathPoint(8.0, 71.0, 8.0, 60f, -5f, "AOTE", 12.0)
                )
            )
        )
        presets.add(
            PathPreset(
                name = "Crystal Hollows Powder Route",
                category = "Mining",
                subcategory = "Powder",
                description = "Chest opening and powder spiral route",
                points = mutableListOf(
                    PathPoint(250.0, 100.0, 320.0, 90f, 0f, "MOVE", 6.0),
                    PathPoint(265.0, 100.0, 335.0, 135f, 15f, "ETHERWARP", 0.0)
                )
            )
        )
        presets.add(
            PathPreset(
                name = "Zealot Bruiser Farm",
                category = "Combat",
                subcategory = "Mob",
                description = "Dragons Nest perimeter sweep targeting Bruisers",
                points = mutableListOf(
                    PathPoint(-650.0, 10.0, -280.0, -90f, 0f, "MOVE", 7.0),
                    PathPoint(-620.0, 10.0, -280.0, -45f, 5f, "MOVE", 7.0)
                )
            )
        )
        presets.add(
            PathPreset(
                name = "M7 P3 Terminal Rush Route",
                category = "M7",
                subcategory = "P3",
                description = "Goldor section 2 to 3 gate sprint with etherwarp skips",
                points = mutableListOf(
                    PathPoint(100.0, 120.0, 90.0, 0f, 0f, "MOVE", 8.0),
                    PathPoint(105.0, 120.0, 95.0, 90f, 0f, "ETHERWARP", 0.0),
                    PathPoint(108.0, 121.0, 100.0, 180f, -20f, "BONZO", 14.0)
                )
            )
        )
        presets.add(
            PathPreset(
                name = "M7 P5 Dragon Platform Setup",
                category = "M7",
                subcategory = "P5",
                description = "Wither King arena spawn to green dragon pillar",
                points = mutableListOf(
                    PathPoint(50.0, 20.0, 50.0, 45f, 0f, "MOVE", 6.5),
                    PathPoint(75.0, 25.0, 75.0, 45f, -30f, "SPRING_BOOTS", 10.0)
                )
            )
        )
    }

    fun exportToJson(singlePreset: PathPreset? = null): String {
        return if (singlePreset != null) {
            gson.toJson(singlePreset)
        } else {
            synchronized(presets) { gson.toJson(presets) }
        }
    }

    /**
     * Parses JSON string representing either a single PathPreset or a List<PathPreset>.
     * Returns count of imported presets.
     */
    fun importFromJson(jsonStr: String): Int {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return 0
        var count = 0
        try {
            if (trimmed.startsWith("[")) {
                val listType = object : TypeToken<List<PathPreset>>() {}.type
                val list: List<PathPreset> = gson.fromJson(trimmed, listType)
                for (preset in list) {
                    addPreset(preset)
                    count++
                }
            } else {
                val preset: PathPreset = gson.fromJson(trimmed, PathPreset::class.java)
                if (preset.name.isNotBlank()) {
                    addPreset(preset)
                    count++
                }
            }
        } catch (e: Exception) {
            AsthoonLite.LOGGER.error("[AsthoonLite] Failed to parse preset JSON", e)
        }
        return count
    }

    fun copyToClipboard(text: String) {
        try {
            Minecraft.getInstance().keyboardHandler.clipboard = text
        } catch (e: Exception) {
            AsthoonLite.LOGGER.error("[AsthoonLite] Failed to copy to clipboard", e)
        }
    }

    fun readFromClipboard(): String {
        return try {
            Minecraft.getInstance().keyboardHandler.clipboard
        } catch (e: Exception) {
            ""
        }
    }
}
