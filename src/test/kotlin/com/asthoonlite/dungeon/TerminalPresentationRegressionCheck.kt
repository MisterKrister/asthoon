package com.asthoonlite.dungeon

import org.lwjgl.glfw.GLFW

/** Cursor policy checks need no window, native GLFW runtime or game render loop. */
internal fun terminalPresentationRegressionChecks() {
    val transitions = TerminalCursor.CursorModeTransitions()
    repeat(120) { transitions.observed(GLFW.GLFW_CURSOR_HIDDEN) }
    check(transitions.hideRequests == 0L && transitions.hideSuccesses == 0L) { "An observed mode alone is not our hide transition" }
    transitions.requested(GLFW.GLFW_CURSOR_HIDDEN, "software-pointer-queued")
    transitions.observed(GLFW.GLFW_CURSOR_HIDDEN)
    repeat(120) { transitions.observed(GLFW.GLFW_CURSOR_HIDDEN) }
    check(transitions.hideRequests == 1L && transitions.hideSuccesses == 1L) { "One successful native request must count once, independent of frame rate" }
    check(transitions.lastHideObservedMode == GLFW.GLFW_CURSOR_HIDDEN && transitions.lastHideReason == "software-pointer-queued")
    transitions.requested(GLFW.GLFW_CURSOR_NORMAL, "screen-removed")
    transitions.observed(GLFW.GLFW_CURSOR_NORMAL)
    check(transitions.restoreRequests == 1L && transitions.restoreSuccesses == 1L)
    transitions.requested(GLFW.GLFW_CURSOR_HIDDEN, "software-pointer-queued")
    transitions.observed(GLFW.GLFW_CURSOR_NORMAL)
    check(transitions.hideRequests == 2L && transitions.hideSuccesses == 1L) { "A rejected hide readback is a request, not a success" }
    check(transitions.lastHideObservedMode == GLFW.GLFW_CURSOR_NORMAL)
    transitions.requested(GLFW.GLFW_CURSOR_HIDDEN, "software-pointer-queued")
    transitions.failed("native-hide-error")
    transitions.observed(GLFW.GLFW_CURSOR_HIDDEN)
    check(transitions.hideRequests == 3L && transitions.hideSuccesses == 1L && transitions.lastHideObservedMode == null)
    check(transitions.lastHideReason == "native-hide-error")
    transitions.requested(GLFW.GLFW_CURSOR_DISABLED, "gameplay-grab")
    transitions.observed(GLFW.GLFW_CURSOR_HIDDEN)
    transitions.requested(GLFW.GLFW_CURSOR_DISABLED, "gameplay-grab")
    transitions.observed(GLFW.GLFW_CURSOR_DISABLED)
    check(transitions.restoreRequests == 3L && transitions.restoreSuccesses == 2L) { "Only a matching restore readback counts as success" }

    // Exercise the same preparation path as TerminalCursor.glideTo, including
    // native idle seeding before scheduling. Testing CursorMotion on its own
    // cannot catch an outer aim that resets its flight on every frame.
    for (native in listOf(false, true)) {
        val hand = CursorMotion()
        val initial = CursorMotion.Point(10f, 20f)
        val target = CursorMotion.Point(250f, 100f)
        hand.reset(initial)
        var physical = CursorMotion.Point(40f, 60f)
        var nativeReads = 0
        var trips = 0
        var lastFrom: CursorMotion.Point? = null
        val nativeIdlePosition: (() -> CursorMotion.Point)? = if (native) {
            { nativeReads++; physical }
        } else null
        fun aim(next: CursorMotion.Point, now: Long): Boolean {
            val from = TerminalCursor.prepareGlide(hand, next, now, nativeIdlePosition) ?: return false
            lastFrom = from
            trips++
            return hand.glideTo(next, now, 400L, 0.1f, CursorMotion.Ease(), 0.35f, 1f, 2f,
                overshootPx = 5f, settleMs = 100L, dwellMs = 50L)
        }

        AutoTerminal.beginTerminal("repeated aim native=$native", 1_000L)
        AutoTerminal.recordClick(1_000L, 15, 550L)
        var firstSentAt: Long? = null
        for (now in 1_000L..2_000L step 10L) {
            aim(target, now)
            if (now == 1_000L) {
                check(lastFrom == if (native) physical else initial)
                physical = CursorMotion.Point(220f, 130f)
            }
            if (firstSentAt == null && AutoTerminal.canClick(now) &&
                !AutoTerminal.pointerBlocksClick(true, hand.moving(now))) {
                firstSentAt = now
                AutoTerminal.recordClick(now, 16, 550L)
            }
        }
        check(trips == 1 && nativeReads == if (native) 1 else 0) {
            "Per-frame aim must not reseed an existing native/software flight or reroll its timing"
        }
        check(firstSentAt == 1_550L) { "Repeated aim must let the pointer land and release the 550 ms click clock" }
        check(hand.position(2_000L) == target && !hand.moving(2_000L)) {
            "Repeated observations after landing must leave the pointer idle"
        }

        val second = CursorMotion.Point(70f, 200f)
        check(aim(second, 2_010L))
        check(lastFrom == if (native) physical else target) { "A new idle native trip starts at the actual pointer" }
        val inFlight = hand.position(2_110L)
        val readsBeforeRetarget = nativeReads
        physical = CursorMotion.Point(300f, 300f)
        val third = CursorMotion.Point(140f, 50f)
        check(aim(third, 2_110L))
        check(lastFrom == inFlight && hand.position(2_110L) == inFlight) {
            "A changed target during flight must keep its sampled position without jumping to native input"
        }
        check(nativeReads == readsBeforeRetarget)
        hand.reset(initial)
        check(aim(third, 3_000L)) { "Resetting a terminal clears its remembered target" }
        AutoTerminal.reset()
    }

    fun mode(
        style: Int = 0, nativeWarp: Boolean = true, moving: Boolean = true,
        glide: Boolean = true, terminal: Boolean = true, focused: Boolean = true,
        iconified: Boolean = false, grabbed: Boolean = false, dimensions: Boolean = true,
        completedPractice: Boolean = false
    ) = TerminalCursor.presentation(glide, terminal, focused, iconified, grabbed,
        dimensions, style, nativeWarp, moving, completedPractice)

    check(TerminalCursor.supportsNativeWarp(GLFW.GLFW_PLATFORM_WIN32))
    check(TerminalCursor.supportsNativeWarp(GLFW.GLFW_PLATFORM_COCOA))
    check(TerminalCursor.supportsNativeWarp(GLFW.GLFW_PLATFORM_X11)) { "XWayland follows the X11 cursor path" }
    check(!TerminalCursor.supportsNativeWarp(GLFW.GLFW_PLATFORM_WAYLAND)) { "Native Wayland cannot warp a normal GUI pointer" }
    check(!TerminalCursor.supportsNativeWarp(0) && !TerminalCursor.supportsNativeWarp(GLFW.GLFW_PLATFORM_NULL))
    check(mode() == TerminalCursor.Presentation.OSU)
    check(mode(moving = false, nativeWarp = false) == TerminalCursor.Presentation.OSU) { "The osu pointer stays visible between terminal clicks" }
    check(mode(style = 1) == TerminalCursor.Presentation.NATIVE) { "Default style keeps the system pointer on supported backends" }
    check(mode(style = 1, nativeWarp = false) == TerminalCursor.Presentation.ARROW) { "An unsupported native warp needs a software arrow during motion" }
    check(mode(style = 1, nativeWarp = false, moving = false) == TerminalCursor.Presentation.NATIVE) { "Default style hands the user's pointer back between glides" }
    for (style in 0..1) {
        check(mode(style = style, glide = false) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, terminal = false) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, focused = false) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, iconified = true) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, grabbed = true) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, dimensions = false) == TerminalCursor.Presentation.INACTIVE)
        check(mode(style = style, completedPractice = true) == TerminalCursor.Presentation.INACTIVE) {
            "A completed practice board hands the pointer back until its screen is replaced"
        }
    }

    // Simulate repeated extraction frames with the shared presentation and
    // restoration policies. Active/manual screens acquire once; completion
    // restores once and remains inactive throughout the restart delay.
    val completionTransitions = TerminalCursor.CursorModeTransitions()
    var nativeMode = GLFW.GLFW_CURSOR_NORMAL
    var owned = false
    fun extractionFrame(completed: Boolean) {
        val presenting = mode(completedPractice = completed) != TerminalCursor.Presentation.INACTIVE
        if (presenting && TerminalCursor.shouldHideRealCursor(true, true, true)) {
            if (nativeMode != GLFW.GLFW_CURSOR_HIDDEN) {
                completionTransitions.requested(GLFW.GLFW_CURSOR_HIDDEN, "software-pointer-queued")
                nativeMode = GLFW.GLFW_CURSOR_HIDDEN
                completionTransitions.observed(nativeMode)
                owned = true
            }
        } else {
            TerminalCursor.restoredMode(owned, nativeMode, false)?.let { restored ->
                completionTransitions.requested(restored, "practice-completed")
                nativeMode = restored
                completionTransitions.observed(nativeMode)
            }
            owned = false
        }
    }
    repeat(120) { extractionFrame(false) }
    check(completionTransitions.hideSuccesses == 1L && completionTransitions.restoreSuccesses == 0L)
    repeat(120) { extractionFrame(true) }
    check(completionTransitions.hideRequests == 1L && completionTransitions.restoreRequests == 1L &&
        completionTransitions.restoreSuccesses == 1L && nativeMode == GLFW.GLFW_CURSOR_NORMAL && !owned) {
        "Completed-board frames must not alternate restore/hide requests"
    }
    repeat(120) { extractionFrame(false) }
    check(completionTransitions.hideSuccesses == 2L && completionTransitions.restoreSuccesses == 1L) {
        "A new board may acquire the software cursor once again"
    }
    check(!TerminalCursor.shouldHideRealCursor(false, true, true)) { "An absent or rejected software draw cannot hide the native pointer" }
    check(TerminalCursor.restoredMode(true, GLFW.GLFW_CURSOR_HIDDEN, false) == GLFW.GLFW_CURSOR_NORMAL)
    check(TerminalCursor.restoredMode(true, GLFW.GLFW_CURSOR_HIDDEN, true) == GLFW.GLFW_CURSOR_DISABLED)
    check(TerminalCursor.restoredMode(true, GLFW.GLFW_CURSOR_DISABLED, false) == null) { "Restoration must not cancel Minecraft's camera grab" }
    check(TerminalCursor.restoredMode(true, GLFW.GLFW_CURSOR_CAPTURED, false) == null) { "Another owner's confined cursor must stay confined" }
    check(TerminalCursor.restoredMode(false, GLFW.GLFW_CURSOR_HIDDEN, false) == null) { "Leave another module's hidden cursor alone" }
    check(TerminalCursor.restoredMode(true, GLFW.GLFW_CURSOR_NORMAL, false) == null)

    for (osu in listOf(false, true)) {
        check(TerminalCursor.pointerIntersectsViewport(120f, 80f, 240, 160, osu))
        check(!TerminalCursor.pointerIntersectsViewport(Float.NaN, 80f, 240, 160, osu))
        check(!TerminalCursor.pointerIntersectsViewport(120f, Float.POSITIVE_INFINITY, 240, 160, osu))
        check(!TerminalCursor.pointerIntersectsViewport(120f, 80f, 0, 160, osu))
        check(!TerminalCursor.pointerIntersectsViewport(-40f, 80f, 240, 160, osu))
        check(!TerminalCursor.pointerIntersectsViewport(120f, 200f, 240, 160, osu))
    }
    check(TerminalCursor.pointerIntersectsViewport(-16f, 80f, 240, 160, true)) { "A partially visible osu halo still intersects the screen" }
    check(!TerminalCursor.pointerIntersectsViewport(-17f, 80f, 240, 160, true))
    check(!TerminalCursor.pointerIntersectsViewport(-16f, 80f, 240, 160, false)) { "An arrow fallback cannot use the wider osu halo bounds" }
    check(TerminalCursor.pointerIntersectsViewport(-11f, -16f, 240, 160, false))
    check(!TerminalCursor.pointerIntersectsViewport(-12f, -16f, 240, 160, false)) { "A sparse arrow bounding box alone does not prove a visible span" }
    check(!TerminalCursor.pointerIntersectsViewport(-13f, 80f, 240, 160, false))
    check(!TerminalCursor.pointerIntersectsViewport(240f, 80f, 240, 160, false))

    val kind = TerminalSolver.Kind.PANES
    val required = TermGui.requiredSlots(kind)
    check(TermGui.gateReason(true, false, kind, required, required) == "ready")
    check(TermGui.gateReason(false, false, kind, required, required) == "custom-gui-disabled")
    check(TermGui.gateReason(true, true, kind, required, required) == "quiet-mode")
    check(TermGui.gateReason(true, false, null, required, required) == "unrecognized-title")
    check(TermGui.gateReason(true, false, kind, required - 1, required) == "menu-incomplete")

    for (choice in listOf(TerminalSolver.Kind.SELECT, TerminalSolver.Kind.STARTS)) {
        for (simulation in listOf(false, true)) for (solver in listOf(false, true)) {
            check(TermGui.drawItems(choice, simulation, solver, false)) { "$choice must show the actual choices in live and practice menus" }
            check(!TermGui.drawItems(choice, simulation, solver, true)) { "Empty $choice tiles have no icon" }
        }
    }
    for (other in TerminalSolver.Kind.entries.filter { it != TerminalSolver.Kind.SELECT && it != TerminalSolver.Kind.STARTS }) {
        check(TermGui.drawItems(other, true, false, false)) { "Manual practice still shows $other items" }
        check(!TermGui.drawItems(other, true, true, false)) { "$other solver presentation must remain unchanged" }
        check(!TermGui.drawItems(other, false, false, false) && !TermGui.drawItems(other, false, true, false)) { "Live $other presentation must remain unchanged" }
        check(!TermGui.drawItems(other, true, false, true))
    }
    check(TermGui.tileBackground(null) == 0xFF292F3B.toInt()) { "Showing choice items must preserve the neutral gray tile background" }
    check(TermGui.tileBackground(0xCC55FFFF.toInt()) == 0xCC55FFFF.toInt()) { "Solver hints retain their existing colour" }
}
