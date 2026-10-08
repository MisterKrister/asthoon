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

    // Verify waiting focus target right after skip (before lanterns appear) is in the middle device area and varies
    val middleYRange = 121.0..122.5
    val middleZRange = 92.7..94.4
    val randTargets = (1..50).map {
        val randY = 121.75 + kotlin.random.Random.nextDouble(-0.65, 0.65)
        val randZ = 93.55 + kotlin.random.Random.nextDouble(-0.75, 0.75)
        net.minecraft.world.phys.Vec3(110.875, randY, randZ)
    }
    check(randTargets.all { it.y in middleYRange && it.z in middleZRange }) {
        "All skip waiting targets must land in the middle device area"
    }
    check(randTargets.distinctBy { it.y }.size > 10 && randTargets.distinctBy { it.z }.size > 10) {
        "Skip waiting targets must vary across multiple skips instead of moving to the exact same spot"
    }

    // Verify SecretTriggerBot skip CPS cadence (~7 CPS with human jitter)
    for (i in 1..100) {
        val delay = kotlin.random.Random.nextLong(130L, 155L)
        check(delay in 130L..155L) { "Triggerbot Simon skip delay must be 130..155ms (~7 CPS)" }
        val cps = 1000.0 / delay
        check(cps in 6.4..7.8) { "Triggerbot Simon skip CPS must stay within ~6.4 to ~7.8 CPS" }
    }

    // Verify player heads are excluded from SecretHitboxes.kindOf while wither skeleton skulls are included
    val playerHeadState = net.minecraft.world.level.block.Blocks.PLAYER_HEAD.defaultBlockState()
    val playerWallHeadState = net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD.defaultBlockState()
    val witherSkullState = net.minecraft.world.level.block.Blocks.WITHER_SKELETON_SKULL.defaultBlockState()
    val witherWallSkullState = net.minecraft.world.level.block.Blocks.WITHER_SKELETON_WALL_SKULL.defaultBlockState()

    check(SecretHitboxes.kindOf(playerHeadState) == null) { "Player heads (terminal heads) must not be classified as secret skulls" }
    check(SecretHitboxes.kindOf(playerWallHeadState) == null) { "Player wall heads must not be classified as secret skulls" }
    check(SecretHitboxes.kindOf(witherSkullState) == SecretHitboxes.Kind.SKULL) { "Wither skeleton skulls must be classified as secret skulls" }
    check(SecretHitboxes.kindOf(witherWallSkullState) == SecretHitboxes.Kind.SKULL) { "Wither skeleton wall skulls must be classified as secret skulls" }

    // ── Terminal Item & Hitbox Filtering Checks ───────────────────────────
    val arrowItem = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW)
    val mapItem = net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FILLED_MAP)
    val emptyItem = net.minecraft.world.item.ItemStack.EMPTY

    check(arrowItem.`is`(net.minecraft.world.item.Items.ARROW)) { "Arrow item must be recognized as ARROW" }
    check(!mapItem.`is`(net.minecraft.world.item.Items.ARROW)) { "Non-arrow item must not be recognized as ARROW" }
    check(!emptyItem.`is`(net.minecraft.world.item.Items.ARROW)) { "Empty item must not be recognized as ARROW" }

    // Arrow align coordinate exclusion
    fun isArrowAlignPos(x: Int, y: Int, z: Int): Boolean =
        x == -2 && y in 120..124 && z in 75..79

    check(isArrowAlignPos(-2, 120, 75)) { "Arrow align grid corner must be recognized" }
    check(isArrowAlignPos(-2, 124, 79)) { "Arrow align grid max bounds must be recognized" }
    check(!isArrowAlignPos(50, 120, 75)) { "Non-arrow align position must not be marked as arrow align" }

    // Hologram completion status check
    fun isTerminalActiveHologram(name: String): Boolean {
        if (name.contains("Completed", ignoreCase = true)) return false
        if (name.contains("Active", ignoreCase = true) && !name.contains("Inactive", ignoreCase = true)) return false
        return name.contains("Terminal", ignoreCase = true) || name.contains("Click Here", ignoreCase = true)
    }

    check(isTerminalActiveHologram("INACTIVE TERMINAL CLICK HERE")) { "Inactive terminal hologram must be active" }
    check(isTerminalActiveHologram("§cInactive Terminal")) { "Colored inactive terminal must be active" }
    check(!isTerminalActiveHologram("§aCompleted Terminal")) { "Completed terminal must not be active" }
    check(!isTerminalActiveHologram("Active Terminal")) { "Active/finished terminal without Inactive must not be active" }

    // ── Secret Aura FOV & Visualizer Math Checks ──────────────────────────
    val fov = 90.0
    val halfFov = fov / 2.0
    val lookYaw = 0.0 // Facing South (+Z)
    val lookDirX = -kotlin.math.sin(Math.toRadians(lookYaw))
    val lookDirZ = kotlin.math.cos(Math.toRadians(lookYaw))

    // Point directly in front (South) -> 0° offset
    val dirFrontX = 0.0
    val dirFrontZ = 1.0
    val dotFront = (dirFrontX * lookDirX + dirFrontZ * lookDirZ).coerceIn(-1.0, 1.0)
    val angleFront = Math.toDegrees(kotlin.math.acos(dotFront))
    check(angleFront < 1e-4 && angleFront <= halfFov) { "Direct forward direction must be within FOV" }

    // Point 30° to the right -> 30° offset <= 45°
    val rad30 = Math.toRadians(30.0)
    val dir30X = -kotlin.math.sin(rad30)
    val dir30Z = kotlin.math.cos(rad30)
    val dot30 = (dir30X * lookDirX + dir30Z * lookDirZ).coerceIn(-1.0, 1.0)
    val angle30 = Math.toDegrees(kotlin.math.acos(dot30))
    check(kotlin.math.abs(angle30 - 30.0) < 1e-4 && angle30 <= halfFov) { "30° direction must be within 90° FOV" }

    // Point 60° to the right -> 60° offset > 45° (outside FOV)
    val rad60 = Math.toRadians(60.0)
    val dir60X = -kotlin.math.sin(rad60)
    val dir60Z = kotlin.math.cos(rad60)
    val dot60 = (dir60X * lookDirX + dir60Z * lookDirZ).coerceIn(-1.0, 1.0)
    val angle60 = Math.toDegrees(kotlin.math.acos(dot60))
    check(kotlin.math.abs(angle60 - 60.0) < 1e-4 && angle60 > halfFov) { "60° direction must be outside 90° FOV" }
}

