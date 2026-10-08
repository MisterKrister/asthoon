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

    // ── Simon Skip Sequence & Focus Target Regression ────────────────────
    val b1 = net.minecraft.core.BlockPos(110, 120, 92)
    val b2 = net.minecraft.core.BlockPos(110, 121, 93)
    val b3 = net.minecraft.core.BlockPos(110, 122, 94)
    val b4 = net.minecraft.core.BlockPos(110, 123, 95)
    val b5 = net.minecraft.core.BlockPos(110, 120, 94)

    // Simulate skip: first button breaks off
    var broken: net.minecraft.core.BlockPos? = null
    var startBtn: net.minecraft.core.BlockPos? = null
    val seq = mutableListOf<net.minecraft.core.BlockPos>()
    var skip = true

    fun observeLantern(pos: net.minecraft.core.BlockPos) {
        if (skip && broken == null) {
            broken = pos
        } else if (pos != broken && !seq.contains(pos)) {
            if (startBtn == null) {
                startBtn = pos
            }
            if (seq.size < 5) seq.add(pos)
        }
    }

    observeLantern(b1) // round 1 (broken)
    check(broken == b1 && seq.isEmpty() && startBtn == null) { "first lantern during skip must be marked broken" }

    observeLantern(b2) // round 2 starting button
    check(broken == b1 && startBtn == b2 && seq == listOf(b2)) { "second lantern must be sequence start" }

    observeLantern(b3) // round 2 second button
    check(seq == listOf(b2, b3)) { "sequence must retain buttons in order" }

    observeLantern(b1) // re-observing broken button must be ignored
    check(seq == listOf(b2, b3) && seq.size == 2) { "broken button must not be added to sequence" }

    // Verify waiting focus target is starting button, never broken button
    val waitingTarget = startBtn
    check(waitingTarget == b2 && waitingTarget != broken) { "waiting focus target must be sequence start button" }

    // Verify sequence progression across rounds
    var clickIdx = 0
    // Round 2 clicks
    check(seq.getOrNull(clickIdx) == b2)
    clickIdx++
    check(seq.getOrNull(clickIdx) == b3)
    clickIdx++
    check(clickIdx >= seq.size) { "round 2 complete when clickIndex reaches sequence size" }

    // Round 3 lanterns appear
    observeLantern(b4)
    check(seq == listOf(b2, b3, b4))

    // Round reset on new board
    clickIdx = 0
    check(seq.getOrNull(clickIdx) == b2) { "round 3 must start at sequence starting button" }
    clickIdx++
    check(seq.getOrNull(clickIdx) == b3)
    clickIdx++
    check(seq.getOrNull(clickIdx) == b4)
    clickIdx++
    check(clickIdx >= seq.size)
}
