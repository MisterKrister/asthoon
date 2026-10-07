package com.asthoonlite.dungeon

import com.asthoonlite.config.Config
import com.asthoonlite.dungeon.simulator.TerminalPracticeClock

fun terminalPracticeRegressionChecks() {
    val defaults = Config.Data()
    check(!defaults.terminalSimulatorAutoEnabled && !defaults.terminalInputLoggingEnabled)
    check(defaults.terminalSimulatorPingMs == 0)
    val clock = TerminalPracticeClock()
    check(clock.enqueue(10, 0, "manual", 100, 200))
    check(clock.enqueue(11, 2, "automatic", 100, 200))
    check(clock.poll(299) == null)
    check(clock.poll(300)?.let { it.slot == 10 && it.button == 0 && it.source == "manual" } == true)
    check(clock.poll(300)?.slot == 11) { "Inputs sharing a timestamp must retain their order" }
    clock.enqueue(12, 0, "manual", 300, 200)
    check(clock.complete(301).map { it.slot } == listOf(12))
    clock.complete(400)
    check(clock.poll(600) == null && !clock.enqueue(13, 0, "manual", 600, 0))
    check(!clock.restartDue(1300) && clock.restartDue(1301)) { "Restart must be one second after completion" }
    clock.cancel()
    check(!clock.restartDue(5000) && clock.poll(5000) == null)
    val abandoned = TerminalPracticeClock()
    abandoned.enqueue(1, 0, "manual", 0, 500)
    check(abandoned.cancel().map { it.id } == listOf(1L))
    check(abandoned.poll(1000) == null) { "Closing a screen must discard its delayed inputs" }
}
