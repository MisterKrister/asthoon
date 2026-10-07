package com.asthoonlite.dungeon

/** Server acknowledgement ends a device; local inputs and round resets do not. */
internal class SimonDeviceLifecycle {
    var active = false
        private set
    var completed = false
        private set
    var startedNs: Long? = null
        private set
    var round = 0
        private set
    private var inputBoardReady = false

    fun start(nowNs: Long) {
        active = true
        completed = false
        startedNs = nowNs
        round = 0
        inputBoardReady = false
    }

    fun observeRun(nowNs: Long) {
        if (!active && !completed) start(nowNs)
    }

    fun startFromInput(nowNs: Long, activatesButton: Boolean): Boolean {
        if (!activatesButton) return false
        start(nowNs)
        return true
    }

    /** Board removal happens between rounds too, so it can only cancel a pending aim. */
    fun observeBoard(ready: Boolean): Boolean {
        val newRound = active && !completed && ready && !inputBoardReady
        inputBoardReady = ready
        if (newRound) round++
        return newRound
    }

    fun completeFromMessage(message: String, localPlayer: String, nearDevice: Boolean): Boolean {
        if (!active || completed || !nearDevice || localPlayer.isEmpty()) return false
        val name = COMPLETION.matchEntire(message)?.groupValues?.get(1) ?: return false
        if (name != localPlayer) return false
        active = false
        completed = true
        return true
    }

    fun reset() {
        active = false
        completed = false
        startedNs = null
        round = 0
        inputBoardReady = false
    }

    companion object {
        // Odin uses this server line for device acknowledgement. An optional
        // rank prefix allows decorated practice-server versions of the line.
        private val COMPLETION = Regex("^(?:\\[[^\\]]+]\\s*)?([A-Za-z0-9_]{1,16}) completed a device! \\(\\d+/\\d+\\)$")
    }
}
