package com.asthoonlite.dungeon

internal fun simonDeviceRegressionChecks() {
    val run = SimonDeviceLifecycle()
    check(!run.completeFromMessage("Player completed a device! (1/7)", "Player", true))
    run.start(123L)
    check(run.observeBoard(true) && run.round == 1)
    check(!run.observeBoard(true) && run.round == 1)
    run.observeBoard(false)
    check(run.active && !run.completed) { "button removal between rounds must not mean device complete" }
    check(run.observeBoard(true) && run.round == 2)
    check(!run.completeFromMessage("Teammate completed a device! (1/7)", "Player", true))
    check(!run.completeFromMessage("Player completed a device! (1/7)", "Player", false))
    check(!run.completeFromMessage("Party > Player: completed a device! (1/7)", "Player", true))
    check(run.active && !run.completed) { "unrelated chat and other devices must not stop Simon" }
    check(run.completeFromMessage("Player completed a device! (1/7)", "Player", true))
    check(!run.active && run.completed && run.startedNs == 123L)
    check(!run.completeFromMessage("Player completed a device! (1/7)", "Player", true)) { "completion must be emitted once" }
    run.observeBoard(false)
    run.observeRun(999L)
    run.observeBoard(true)
    check(run.completed && !run.active) { "a completed device must remain latched through later board updates" }
    check(!run.startFromInput(999L, false) && run.completed && !run.active) {
        "attacking the start button must not restart a completed device"
    }
    check(run.startFromInput(1000L, true))
    check(!run.completed && run.active && run.round == 0 && run.startedNs == 1000L) { "an explicit start rearms a new run" }
    check(run.completeFromMessage("[MVP+] Player completed a device! (2/7)", "Player", true))
    run.reset()
    check(!run.active && !run.completed && run.startedNs == null && run.round == 0) { "join/disconnect must clear run state" }
}
