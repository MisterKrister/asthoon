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
            // Fresh config has 0 presets by default
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

    fun updatePreset(preset: PathPreset) {
        synchronized(presets) {
            val idx = presets.indexOfFirst { it.id == preset.id }
            if (idx >= 0) {
                presets[idx] = preset
            } else {
                presets.add(preset)
            }
        }
        savePresets()
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
