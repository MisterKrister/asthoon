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

    // Every manual left click walks toward the stable target by one of the
    // shortest ring directions; a right button remains explicitly backward.
    for (from in 0..4) for (target in 0..4) {
        val forward = (target - from + 5) % 5
        val backward = (from - target + 5) % 5
        val expected = if (forward <= backward) 0 else 1
        for (source in listOf("manual", "keyboard")) {
            check(TerminalPracticeClock.effectiveRubixButton(0, source, from, target) == expected)
            var color = from
            repeat(minOf(forward, backward)) {
                val button = TerminalPracticeClock.effectiveRubixButton(0, source, color, target)
                color = TerminalSolver.rubixAdvance(color, button)
            }
            check(color == target) { "Practice left clicks must take the shortest route from $from to $target" }
        }
        for (source in listOf("manual", "keyboard", "automatic", "other")) {
            check(TerminalPracticeClock.effectiveRubixButton(1, source, from, target) == 1)
            check(TerminalPracticeClock.effectiveRubixButton(2, source, from, target) == 2)
        }
        check(TerminalPracticeClock.effectiveRubixButton(0, "automatic", from, target) == 0) {
            "An automatic action already supplies its chosen direction"
        }
        check(TerminalPracticeClock.effectiveRubixButton(0, "other", from, target) == 0)
    }
    check(TerminalPracticeClock.effectiveRubixButton(0, "manual", -1, 4) == 0)
    check(TerminalPracticeClock.effectiveRubixButton(0, "manual", 0, null) == 0)
    check(TerminalPracticeClock.effectiveRubixButton(0, "manual", 0, 5) == 0)
    check(TerminalPracticeClock.initialRubixTarget(List(8) { 4 } + 0) == 4)
    check(TerminalPracticeClock.initialRubixTarget(List(9) { 0 }) == 0)
    check(TerminalPracticeClock.initialRubixTarget(listOf(0)) == null)
    check(TerminalPracticeClock.initialRubixTarget(List(8) { 4 } + -1) == null)

    // Two rapid left clicks both use predicted state while the displayed
    // pane still shows orange. After the first delayed click applies, the
    // remaining queue must still predict the same blue endpoint.
    val burst = TerminalPracticeClock()
    val target = 3
    repeat(2) { index ->
        val color = burst.rubixPredictedColor(12, 0)
        check(color == if (index == 0) 0 else 4)
        val effective = TerminalPracticeClock.effectiveRubixButton(0, "manual", color, target)
        check(effective == 1)
        check(burst.enqueue(12, effective, "manual", 100, 200, requestedButton = 0,
            rubixTarget = target, rubixPredictedBefore = color))
        val recorded = requireNotNull(burst.lastQueued)
        check(recorded.requestedButton == 0 && recorded.button == 1 && recorded.rubixTarget == 3)
        check(recorded.rubixPredictedBefore == color) { "Queue metadata retains physical input and mapping state" }
    }
    check(burst.rubixPredictedColor(12, 0) == 3 && burst.rubixPredictedColor(13, 0) == 0)
    check(burst.poll(299) == null)
    val first = requireNotNull(burst.poll(300))
    val actual = TerminalSolver.rubixAdvance(0, first.button)
    check(actual == 4 && burst.rubixPredictedColor(12, actual) == 3)
    val second = requireNotNull(burst.poll(300))
    check(TerminalSolver.rubixAdvance(actual, second.button) == 3)
    check(burst.rubixPredictedColor(12, 3) == 3) { "Applied clicks cannot stay in the prediction queue" }
    check(first.requestedButton == 0 && second.requestedButton == 0) { "Capture outcomes retain both physical left clicks" }

    val direct = TerminalPracticeClock()
    direct.enqueue(12, 2, "automatic", 100, 500)
    check(direct.rubixPredictedColor(12, 4) == 0) { "Explicit middle clicks keep Odin's forward direction" }
    direct.cancel()
    check(direct.rubixPredictedColor(12, 4) == 4)
}
