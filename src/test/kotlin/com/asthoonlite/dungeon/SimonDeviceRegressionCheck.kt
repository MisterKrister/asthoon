package com.asthoonlite.dungeon

import com.asthoonlite.pathfinding.RouteNodeType

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

    // ── Triggerbot Same-Target Cooldown Regression Checks ─────────────────
    fun triggerbotCooldownMs(seconds: Double): Long =
        (seconds * 1000.0).toLong().coerceAtLeast(200L)

    check(triggerbotCooldownMs(10.0) == 10000L) { "Default 10s triggerbot cooldown must be 10000ms" }
    check(triggerbotCooldownMs(1.0) == 1000L) { "1.0s triggerbot cooldown must be 1000ms" }
    check(triggerbotCooldownMs(30.0) == 30000L) { "30.0s triggerbot cooldown must be 30000ms" }
    check(triggerbotCooldownMs(0.05) == 200L) { "Triggerbot cooldown must be clamped to at least 200ms" }

    // Simulate same-target cooldown tracking
    val targetPos = net.minecraft.core.BlockPos(5, 10, 15)
    val trackedClicks = mutableMapOf<net.minecraft.core.BlockPos, Long>()
    val cooldown = triggerbotCooldownMs(10.0)
    val clickTime = 50000L
    trackedClicks[targetPos] = clickTime

    fun canInteract(pos: net.minecraft.core.BlockPos, now: Long): Boolean {
        val last = trackedClicks[pos] ?: 0L
        return (now - last) >= cooldown
    }

    // Within cooldown: blocked
    check(!canInteract(targetPos, clickTime + 500L)) { "Same target must be blocked immediately after click" }
    check(!canInteract(targetPos, clickTime + 5000L)) { "Same target must be blocked midway through 10s cooldown" }
    check(!canInteract(targetPos, clickTime + 9999L)) { "Same target must be blocked just before cooldown expires" }

    // At and after cooldown: allowed to interact again
    check(canInteract(targetPos, clickTime + 10000L)) { "Same target must be allowed exactly when 10s cooldown expires" }
    check(canInteract(targetPos, clickTime + 15000L)) { "Same target must be allowed after 10s cooldown has passed" }

    // Different target: allowed immediately
    val otherPos = net.minecraft.core.BlockPos(6, 10, 15)
    check(canInteract(otherPos, clickTime + 500L)) { "Different target must not be blocked by another target's cooldown" }

    // Re-triggering updates timestamp and resets cooldown
    trackedClicks[targetPos] = clickTime + 10000L
    check(!canInteract(targetPos, clickTime + 10500L)) { "Re-clicking target must re-arm the 10s cooldown" }
    check(canInteract(targetPos, clickTime + 20000L)) { "Target must be re-interactable after second cooldown expires" }

    // ── Pathfinding & M7 Node Editor Regression Checks ───────────────────
    // 1. Fresh presets list starts with 0 presets
    val testPresets = mutableListOf<com.asthoonlite.pathfinding.PathPreset>()
    check(testPresets.isEmpty()) { "Fresh config must have 0 presets by default" }

    // 2. M7 Category taxonomy (P1 through P5)
    val m7Subs = com.asthoonlite.pathfinding.RouteCategory.M7.getSubcategories().map { it.name }
    check(m7Subs == listOf("P1", "P2", "P3", "P4", "P5")) { "M7 category must have exactly P1 through P5 subcategories" }

    // 3. M7 mobility constraints: strictly no AOTV or Etherwarp
    val m7Allowed = com.asthoonlite.pathfinding.RouteNodeType.allowedForCategory(com.asthoonlite.pathfinding.RouteCategory.M7)
    check(m7Allowed == listOf(
        com.asthoonlite.pathfinding.RouteNodeType.WALK,
        com.asthoonlite.pathfinding.RouteNodeType.BONZO_STAFF,
        com.asthoonlite.pathfinding.RouteNodeType.JUMP,
        com.asthoonlite.pathfinding.RouteNodeType.TERMINAL,
        com.asthoonlite.pathfinding.RouteNodeType.SIMON_SAYS,
        com.asthoonlite.pathfinding.RouteNodeType.ARROWS_ALIGN,
        com.asthoonlite.pathfinding.RouteNodeType.TIMEOUT,
        com.asthoonlite.pathfinding.RouteNodeType.INTERACT
    )) { "M7 routes must allow WALK, BONZO_STAFF, JUMP, TERMINAL, SIMON_SAYS, ARROWS_ALIGN, TIMEOUT, and INTERACT (no AOTV / Etherwarp)" }

    // 4. Node swapping and drag-and-drop reordering
    val preset = com.asthoonlite.pathfinding.PathPreset(
        name = "Test M7 Route",
        category = "M7",
        subcategory = "P3"
    )
    val nodeA = com.asthoonlite.pathfinding.PathPoint(10.0, 60.0, 20.0, action = "WALK")
    val nodeB = com.asthoonlite.pathfinding.PathPoint(15.0, 60.0, 25.0, action = "BONZO_STAFF")
    val nodeC = com.asthoonlite.pathfinding.PathPoint(20.0, 60.0, 30.0, action = "INTERACT")
    preset.points.addAll(listOf(nodeA, nodeB, nodeC))
    check(preset.points.size == 3)

    // Test swapNodes(0, 2)
    preset.swapNodes(0, 2)
    check(preset.points[0] == nodeC && preset.points[1] == nodeB && preset.points[2] == nodeA) {
        "swapNodes must exchange nodes at indices"
    }

    // Test moveNode(2, 0)
    preset.moveNode(2, 0)
    check(preset.points[0] == nodeA && preset.points[1] == nodeC && preset.points[2] == nodeB) {
        "moveNode must insert dragged node at destination index"
    }

    // 5. Speed-aware Bonzo Staff pause threshold
    fun bonzoRequiresPause(speedAttribute: Double): Boolean {
        val skyblockSpeed = speedAttribute * 1000.0
        return skyblockSpeed > 400.0
    }

    check(bonzoRequiresPause(0.5500)) { "550 speed (0.55) must pause forward key before Bonzo Staff firing" }
    check(bonzoRequiresPause(0.7150)) { "715 speed (0.715) must pause forward key before Bonzo Staff firing" }
    check(!bonzoRequiresPause(0.4000)) { "400 speed (0.40) must traverse without pausing forward key" }
    check(!bonzoRequiresPause(0.3500)) { "350 speed (0.35) must traverse without pausing forward key" }
    check(!bonzoRequiresPause(0.1000)) { "100 base speed (0.10) must traverse without pausing forward key" }

    // 6. 1-based index insertion into route
    fun insertNodeAtNumber(preset: com.asthoonlite.pathfinding.PathPreset, number: Int, point: com.asthoonlite.pathfinding.PathPoint) {
        val idx = (number - 1).coerceIn(0, preset.points.size)
        preset.points.add(idx, point)
    }

    val insertPreset = com.asthoonlite.pathfinding.PathPreset(name = "Insertion Test", category = "M7", subcategory = "P1")
    val p1 = com.asthoonlite.pathfinding.PathPoint(1.0, 1.0, 1.0)
    val p2 = com.asthoonlite.pathfinding.PathPoint(2.0, 2.0, 2.0)
    val p3 = com.asthoonlite.pathfinding.PathPoint(3.0, 3.0, 3.0)
    insertNodeAtNumber(insertPreset, 1, p1)
    insertNodeAtNumber(insertPreset, 2, p3) // currently p1, p3
    insertNodeAtNumber(insertPreset, 2, p2) // insert at position 2 -> p1, p2, p3
    check(insertPreset.points == listOf(p1, p2, p3)) { "Inserting at number 2 must place node between #1 and #3" }

    // 7. Node editing and repositioning
    val nodeToEdit = insertPreset.points[1] // p2
    nodeToEdit.x = 2.5
    nodeToEdit.action = "BONZO_STAFF"
    check(insertPreset.points[1].x == 2.5 && insertPreset.points[1].action == "BONZO_STAFF") {
        "Directly editing node fields must mutate the node in the route"
    }

    // 8. Continuous sprint arrival distance scaling
    fun arrivalDistance(speedAttribute: Double): Double {
        val skyblockSpeed = speedAttribute * 1000.0
        return if (skyblockSpeed > 400.0) 2.2 else 1.2
    }
    check(arrivalDistance(0.55) == 2.2) { "High speed (550) must use 2.2 block arrival threshold for non-stop sprinting" }
    check(arrivalDistance(0.10) == 1.2) { "Normal speed (100) must use 1.2 block arrival threshold" }

    // 9. RouteEditor finishEditing lifecycle check
    com.asthoonlite.pathfinding.RouteEditor.activePreset = insertPreset
    com.asthoonlite.pathfinding.RouteEditor.editingNodeIndex = 1
    com.asthoonlite.pathfinding.RouteEditor.pickBlockMode = true
    com.asthoonlite.pathfinding.RouteEditor.finishEditing()
    check(com.asthoonlite.pathfinding.RouteEditor.activePreset == null) { "finishEditing must clear activePreset to null" }
    check(com.asthoonlite.pathfinding.RouteEditor.editingNodeIndex == -1) { "finishEditing must reset editingNodeIndex to -1" }
    check(!com.asthoonlite.pathfinding.RouteEditor.pickBlockMode) { "finishEditing must disable pickBlockMode" }

    // 10. Preset Active/Inactive toggle check
    var activeId = ""
    fun togglePreset(presetId: String) {
        activeId = if (activeId == presetId) "" else presetId
    }
    togglePreset("test-preset-1")
    check(activeId == "test-preset-1") { "Toggling inactive preset must activate it" }
    togglePreset("test-preset-1")
    check(activeId.isEmpty()) { "Toggling active preset must deactivate it" }

    // 11. Expanded RouteNodeType checks for M7
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.BONZO_STAFF)) { "M7 must allow BONZO_STAFF" }
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.JUMP)) { "M7 must allow JUMP" }
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.TERMINAL)) { "M7 must allow TERMINAL" }
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.SIMON_SAYS)) { "M7 must allow SIMON_SAYS" }
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.ARROWS_ALIGN)) { "M7 must allow ARROWS_ALIGN" }
    check(m7Allowed.contains(com.asthoonlite.pathfinding.RouteNodeType.TIMEOUT)) { "M7 must allow TIMEOUT" }

    // 12. PathPoint Look Node and Timeout JSON backward-compatibility check
    val oldPointJson = """{"x":10.5,"y":64.0,"z":-20.5,"action":"WALK"}"""
    val deserializedPoint = com.google.gson.Gson().fromJson(oldPointJson, com.asthoonlite.pathfinding.PathPoint::class.java)
    check(!deserializedPoint.hasLookNode) { "Deserializing old PathPoint without look node must default hasLookNode to false" }
    check(deserializedPoint.lookX == 0.0 && deserializedPoint.lookY == 0.0 && deserializedPoint.lookZ == 0.0) { "Look coordinates must default to 0.0" }
    val effectiveTimeout = if (deserializedPoint.timeoutSeconds > 0.0) deserializedPoint.timeoutSeconds else 1.0
    check(effectiveTimeout == 1.0) { "Effective timeout seconds must fallback to 1.0" }

    // 13. PathPoint with Look Node serialization round-trip
    val lookPoint = com.asthoonlite.pathfinding.PathPoint(
        x = 5.0, y = 70.0, z = 15.0,
        hasLookNode = true,
        lookX = 5.5, lookY = 73.2, lookZ = 18.0,
        timeoutSeconds = 2.5
    )
    val roundTripJson = com.google.gson.Gson().toJson(lookPoint)
    val restoredLookPoint = com.google.gson.Gson().fromJson(roundTripJson, com.asthoonlite.pathfinding.PathPoint::class.java)
    check(restoredLookPoint.hasLookNode) { "Serialized look point must retain hasLookNode == true" }
    check(restoredLookPoint.lookX == 5.5 && restoredLookPoint.lookY == 73.2 && restoredLookPoint.lookZ == 18.0) {
        "Look coordinates must round-trip cleanly"
    }
    check(restoredLookPoint.timeoutSeconds == 2.5) { "Timeout seconds must round-trip cleanly" }

    // 14. Bonzo Staff pitch and server delay constants
    val bonzoMinPitch = 25.0f
    val bonzoMaxPitch = 65.0f
    val bonzoPostFireTicks = 6
    check(bonzoMinPitch in 20.0f..35.0f && bonzoMaxPitch in 55.0f..75.0f) {
        "Bonzo launch pitch must hit floor ahead/behind player at 25°-65° without stalling into feet at 83°"
    }
    check(bonzoPostFireTicks >= 5) {
        "Bonzo post-fire delay must be at least 5-6 ticks to absorb floor explosion propulsion"
    }

    // 15. RouteEditor Node View mode check
    check(com.asthoonlite.pathfinding.RouteEditor.nodeViewMode) { "RouteEditor.nodeViewMode must default to true" }

    // 16. Directional Bonzo launch yaw calculation check (diagonally left)
    fun computeLaunchYaw(playerX: Double, playerZ: Double, targetX: Double, targetZ: Double): Float {
        val dx = targetX - playerX
        val dz = targetZ - playerZ
        return (-Math.toDegrees(kotlin.math.atan2(dx, dz))).toFloat()
    }
    // Target is diagonally left (e.g. player at (0, 0), target at (-10, 10))
    val diagYaw = computeLaunchYaw(0.0, 0.0, -10.0, 10.0)
    check(diagYaw == 45.0f) { "Bonzo launch yaw must point directly along vector to destination (45° for diagonally left)" }

    // 17. Bonzo Staff projectile telemetry and knockback impulse detection check
    fun isBonzoKnockbackImpulse(vy: Double, dvy: Double, bpsH: Double, dvH: Double): Boolean {
        return (vy > 0.22 || dvy > 0.30 || (bpsH > 14.0 && dvH > 4.0))
    }
    // High-speed floor blast impulse: vertical launch spike
    check(isBonzoKnockbackImpulse(vy = 0.42, dvy = 0.45, bpsH = 22.0, dvH = 6.0)) {
        "High-speed Bonzo explosion knockback must be recognized"
    }
    // Subtle floor explosion with forward acceleration
    check(isBonzoKnockbackImpulse(vy = 0.15, dvy = 0.10, bpsH = 26.5, dvH = 5.2)) {
        "Bonzo horizontal acceleration boost must be recognized"
    }
    // Ordinary walking at steady speed
    check(!isBonzoKnockbackImpulse(vy = -0.07, dvy = 0.0, bpsH = 11.0, dvH = 0.1)) {
        "Steady walking must not trigger knockback detection"
    }

    // 18. Smart auto-jump gap and ledge detection
    fun shouldAutoJump(isLedge: Boolean, onGround: Boolean, distH: Double, isCollision: Boolean): Boolean {
        return isCollision || (onGround && isLedge && distH > 1.2)
    }
    check(shouldAutoJump(isLedge = true, onGround = true, distH = 8.6, isCollision = false)) {
        "Approaching a gap or ledge on a walk node must trigger auto-jump"
    }
    check(!shouldAutoJump(isLedge = false, onGround = true, distH = 8.6, isCollision = false)) {
        "Continuous flat ground must not trigger auto-jump"
    }

    // 19. Waypoint arrival transition exclusions and jump pulse
    fun shouldAdvanceViaGenericArrival(nodeType: RouteNodeType, distH: Double, threshold: Double): Boolean {
        return nodeType != RouteNodeType.BONZO_STAFF && nodeType != RouteNodeType.JUMP && distH < threshold
    }
    check(!shouldAdvanceViaGenericArrival(RouteNodeType.BONZO_STAFF, 1.8, 2.2)) {
        "BONZO_STAFF node must never be skipped by generic arrival check"
    }
    check(!shouldAdvanceViaGenericArrival(RouteNodeType.JUMP, 1.8, 2.2)) {
        "JUMP node must never be skipped by generic arrival check"
    }
    check(shouldAdvanceViaGenericArrival(RouteNodeType.WALK, 1.8, 2.2)) {
        "WALK node must advance via generic arrival check"
    }
}



