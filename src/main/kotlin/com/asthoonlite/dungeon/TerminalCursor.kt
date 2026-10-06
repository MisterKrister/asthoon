package com.asthoonlite.dungeon

import com.asthoonlite.AsthoonLite
import com.asthoonlite.config.Config
import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A mouse pointer that moves like a hand, drawn over the container screen.
 *
 * The mod never moves the real cursor. Terminal clicks go down the packet path
 * (`MultiPlayerGameMode.handleContainerInput` -> `ServerboundContainerClickPacket`),
 * so nothing about the click needs a pointer at all — this object is purely the
 * picture of one. What it buys is that a solved terminal no longer *looks* like
 * it was solved by an act of God: a pointer travels to the pane, decelerates
 * into it, while clicks run independently on the terminal's clock.
 *
 * Path shape, in order of importance:
 *  - a quadratic bezier whose control point is pushed off the straight line, so
 *    the pointer bows instead of tracking a ruler;
 *  - configurable CSS cubic-bezier easing for acceleration and landing;
 *  - a decaying tremor, because a hand does not hold still on the way in;
 *  - travel time scaled by distance with gaussian spread, so a far flick takes
 *    longer than a hop to the neighbouring pane.
 *
 * The picture itself is 《CK》 Bacon boi 1.0's osu cursor — white core, yellow
 * rim, yellow glow, a trail of yellow blobs behind it while it travels — drawn
 * as a sprite over the container rather than hand-rolled from pixels, because
 * a hand-rolled arrow is exactly the thing that reads as *not a mouse*. The
 * arrow survives only as [drawArrowPointer], the fallback for a frame where
 * the texture pipeline says no.
 *
 * Everything pure in here ([progressAt], [travelDurationMs], [flightDurationMs],
 * [bezier], [arrowRuns], [trailAlpha]) is deterministic given its inputs — the
 * regression harness pins all six, so the feel of the movement cannot drift
 * silently.
 */
object TerminalCursor {

    /** Resting tip position in screen space. */
    private var x = 0f
    private var y = 0f
    private var positioned = false

    /** True once [x]/[y] have actually been drawn — i.e. once there is a tip
     *  worth warping the real cursor to on handback. Never true before the
     *  first frame, so an early handback leaves the cursor where it was
     *  rather than snapping it to an invented coordinate. */
    private var tipKnown = false

    private val motion = CursorMotion()
    private val moving: Boolean get() = motion.moving(System.currentTimeMillis())
    private var lastGlideAt = 0L

    /**
     * True while the drawn pointer is on screen this frame and therefore
     * *is* the cursor.
     *
     * Set the same frame the arrow is drawn, cleared the same frame it stops
     * being drawn (see [render] and [reset]). Derived from the draw, not from
     * Config, so it needs no clock and no config reads and cannot disagree
     * with what anyone can see.
     *
     * While this is true the window has a cursor at the drawn pointer's tip
     * and no cursor at all where GLFW thinks the mouse is. Everything vanilla
     * computes from the physical position — the hovered-slot highlight, the
     * item tooltip — therefore points at a slot nobody is hovering, which is
     * the giveaway that something else is moving the mouse. Callers use it to
     * stop drawing those.
     */
    private var drawn = false

    fun ownsCursor(): Boolean = drawn

    fun reset() {
        motion.reset(CursorMotion.Point(x, y))
        positioned = false
        drawn = false
        // Hand the real cursor back now that nothing is drawn for it. Clear
        // positioned first so shouldHideRealCursor agrees, but leave
        // tipKnown set: x/y are still the pointer's last tip, and that is
        // where the real cursor has to reappear.
        syncOsCursor(false)
        tipKnown = false
        trail.clear()
    }

    /**
     * Whether the real cursor is currently hidden for the drawn one.
     * Tracks intent, not the window: one transition in, one out.
     */
    private var osCursorHidden = false

    /**
     * Whether the real cursor should be out of the way this frame.
     *
     * Three conditions, all required: the swap is switched on, the drawn
     * pointer is switched on, and the drawn pointer is actually on screen.
     * With all three the real cursor goes and this object's arrow is the
     * only cursor anyone sees.
     *
     * Note what is *not* in here: a separate "container is open" flag. While
     * a pointer is armed it is drawn every frame (see [render]), so
     * [pointerVisible] already covers the whole container and the real cursor
     * does not come back in the middle of one. Keeping the real cursor hidden
     * after the drawn pointer has stopped drawing would be the wrong way
     * round — that is how a window ends up with no cursor at all.
     */
    internal fun shouldHideRealCursor(
        pointerVisible: Boolean,
        hideReal: Boolean,
        glideOn: Boolean
    ): Boolean = pointerVisible && hideReal && glideOn

    /**
     * GUI-scaled pointer tip -> window coordinates for `glfwSetCursorPos`.
     *
     * `MouseHandler.getScaledXPos` runs the other way as
     * `raw * guiScaledWidth / screenWidth`; this is that inversion, read off
     * the bytecode rather than assumed, so the two agree to the pixel.
     * Returns 0 for a degenerate scale instead of dividing by zero.
     */
    internal fun rawFromScaled(scaled: Float, screenWidth: Int, guiScaledWidth: Int): Double =
        if (guiScaledWidth <= 0) 0.0
        else scaled.toDouble() * screenWidth / guiScaledWidth

    /**
     * Swaps the real cursor out from under the drawn pointer, and hands it
     * back when the drawn one is finished.
     *
     * Handback warps first ([rawFromScaled]): the drawn pointer's tip is
     * where the eye last saw a cursor, so the real one has to appear *there*
     * rather than wherever the physical mouse drifted to while it was
     * hidden. That is what makes the handover invisible — without it the
     * arrow fades on pane 7 and the real cursor pops in three hundred pixels
     * away.
     *
     * [GLFW] is called on the client thread — both the tick and the render
     * pass run there, so the call never leaves the thread GLFW was
     * initialised on, and the equality guard makes it a no-op per state, so
     * calling it every frame costs nothing.
     *
     * The mode only goes to HIDDEN out of [render], and [render] only runs
     * behind a container screen, so the gameplay grab is never disturbed on
     * the way in. It only comes back to NORMAL while a screen is still up:
     * with no screen gameplay owns the grab and forcing NORMAL over it would
     * let the mouse out of the window. The position still gets warped either
     * way, so the cursor is where it belongs the next time a screen releases
     * it.
     */
    private fun syncOsCursor(pointerVisible: Boolean) {
        // Short-circuit before the argument list is evaluated — the repeated
        // `pointerVisible` is deliberate, because Kotlin only skips the Config
        // reads if this && decides it. With nothing drawn there is nothing to
        // hide behind anyway, and reaching those reads needs a Fabric loader to
        // open a config directory with, which the regression harness does not
        // have behind it when it calls reset().
        val wanted = pointerVisible && shouldHideRealCursor(
            pointerVisible,
            Config.autoTerminalCursorHideReal,
            Config.autoTerminalCursorGlide
        )
        if (wanted == osCursorHidden) return
        osCursorHidden = wanted

        val mc = Minecraft.getInstance()
        val win = mc.window
        val handle = win.handle()
        if (wanted) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN)
            return
        }
        if (tipKnown) {
            GLFW.glfwSetCursorPos(
                handle,
                rawFromScaled(x, win.screenWidth, win.guiScaledWidth),
                rawFromScaled(y, win.screenHeight, win.guiScaledHeight)
            )
        }
        if (mc.screen != null) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL)
        }
    }

    /** CSS timing function: invert x(u), then evaluate y(u). X controls must
     * be in [0,1]; Y may overshoot, as CSS permits. Endpoint times are clamped. */
    internal fun cubicBezierEase(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        require(x1.isFinite() && x1 in 0f..1f && x2.isFinite() && x2 in 0f..1f)
        require(y1.isFinite() && y2.isFinite() && t.isFinite())
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        fun curve(u: Double, a: Float, b: Float): Double {
            val v = 1.0 - u
            return 3.0 * v * v * u * a + 3.0 * v * u * u * b + u * u * u
        }
        fun slope(u: Double): Double = 3.0 * (1.0 - u) * (1.0 - u) * x1 +
            6.0 * (1.0 - u) * u * (x2 - x1) + 3.0 * u * u * (1.0 - x2)
        var u = t.toDouble()
        var low = 0.0
        var high = 1.0
        repeat(8) {
            val error = curve(u, x1, x2) - t
            if (abs(error) < 1e-8) return curve(u, y1, y2).toFloat()
            if (error < 0.0) low = u else high = u
            val derivative = slope(u)
            val next = if (abs(derivative) > 1e-8) u - error / derivative else Double.NaN
            u = if (next.isFinite() && next > low && next < high) next else (low + high) / 2.0
        }
        repeat(32) {
            val error = curve(u, x1, x2) - t
            if (abs(error) < 1e-8) return curve(u, y1, y2).toFloat()
            if (error < 0.0) low = u else high = u
            u = (low + high) / 2.0
        }
        return curve(u, y1, y2).toFloat()
    }

    internal fun progressAt(elapsedMs: Long, durationMs: Long,
        x1: Float = 0.2f, y1: Float = 0f, x2: Float = 0f, y2: Float = 1f): Float {
        if (durationMs <= 0L) return 1f
        val t = (elapsedMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
        return cubicBezierEase(t, x1, y1, x2, y2)
    }

    /**
     * Milliseconds for a pointer covering [distance] screen pixels.
     * `speedPercent` 100 is the natural setting; higher is snappier. `jitter`
     * is a unit gaussian-ish value in roughly [-1, 1] and is what keeps two
     * identical flicks from ever taking identical time.
     */
    internal fun travelDurationMs(distance: Float, speedPercent: Int, jitter: Float): Long {
        val base = 85f + distance.coerceAtLeast(0f) * 0.62f
        val speed = speedPercent.coerceIn(25, 400) / 100f
        val raw = (base / speed) * (1f + jitter.coerceIn(-1f, 1f) * 0.18f)
        return raw.coerceIn(70f, 420f).toLong()
    }

    /** Fit travel into the available click window where possible. The 70 ms
     * floor keeps motion visible at fast cadences; a click may fire during
     * that flight because AutoTerminal owns the clock independently. */
    internal fun flightDurationMs(naturalMs: Long, availableMs: Long): Long {
        val natural = naturalMs.coerceIn(MIN_FLIGHT_MS, MAX_FLIGHT_MS)
        if (availableMs <= 0L) return MIN_FLIGHT_MS
        return availableMs.coerceIn(MIN_FLIGHT_MS, natural)
    }

    /** Quadratic bezier point at [t] through control point [c]. */
    internal fun bezier(p0: Float, c: Float, p1: Float, t: Float): Float {
        val u = 1f - t
        return u * u * p0 + 2f * u * t * c + t * t * p1
    }

    /**
     * The pointer sprite as horizontal runs per row — a triangle head with the
     * tail continuing down-right along the same diagonal. Returned once and
     * cached; the outline is this shape dilated by one pixel.
     */
    internal fun arrowRuns(): List<ArrowRun> {
        val runs = ArrayList<ArrowRun>(24)
        for (row in 0 until ARROW_ROWS) {
            val left: Int
            val right: Int
            if (row <= HEAD_LAST_ROW) {
                left = 0
                right = row
            } else {
                left = row - ARROW_ROWS + 7
                right = left + 2
            }
            if (right < left) continue
            runs.add(ArrowRun(row, left, right))
        }
        return runs.filter { it.x0 in 0 until ARROW_COLS && it.x1 >= it.x0 }
    }

    data class ArrowRun(val row: Int, val x0: Int, val x1: Int)

    private const val ARROW_ROWS = 16
    private const val ARROW_COLS = 16
    private const val HEAD_LAST_ROW = 9

    /** Flight-time bounds, shared with the harness. */
    private const val MIN_FLIGHT_MS = 70L
    private const val MAX_FLIGHT_MS = 420L

    private val runs: List<ArrowRun> by lazy { arrowRuns() }

    /**
     * Arms the pointer for a terminal that is open, and keeps it armed: called
     * every tick while a terminal is on screen so the pointer is there from the
     * moment the pane appears instead of only after the first solve, and so it
     * does not fade out between clicks. Purely presentational — it never queues
     * a click.
     */
    fun show() {
        if (!positioned) {
            val w = Minecraft.getInstance().window
            seedFromMouse(w.guiScaledWidth / 2f, w.guiScaledHeight / 2f)
        }
        if (!moving) lastGlideAt = System.currentTimeMillis()
    }

    /** Retarget from the current sampled position. Clicks belong to AutoTerminal. */
    fun glideTo(targetX: Float, targetY: Float, clickNotBeforeMs: Long) {
        if (!positioned) seedFromMouse(targetX, targetY)
        val now = System.currentTimeMillis()
        val from = motion.position(now)
        val dist = hypot(targetX - from.x, targetY - from.y)
        val natural = travelDurationMs(dist, Config.autoTerminalCursorSpeed, gaussianUnit())
        val bow = Config.autoTerminalCursorArc.coerceIn(0, 100) / 100f * 0.4f * if (Random.nextBoolean()) 1f else -1f
        motion.glideTo(CursorMotion.Point(targetX, targetY), now, flightDurationMs(natural, clickNotBeforeMs - now), bow,
            CursorMotion.Ease(Config.autoTerminalEaseX1 / 100f, Config.autoTerminalEaseY1 / 100f,
                Config.autoTerminalEaseX2 / 100f, Config.autoTerminalEaseY2 / 100f),
            Config.autoTerminalCursorJitter.coerceIn(0, 100) / 100f, Random.nextFloat() * 6.283f, Random.nextFloat() * 6.283f)
        lastGlideAt = now
    }

    /**
     * First sighting of the pointer: put it on the real cursor rather than
     * inventing a position, so the hand has somewhere honest to leave from —
     * and so there is something to look at before the first glide runs.
     */
    private fun seedFromMouse(targetX: Float, targetY: Float) {
        positioned = true
        val mc = Minecraft.getInstance()
        val w = mc.window
        val sx = mc.mouseHandler.getScaledXPos(w)
        val sy = mc.mouseHandler.getScaledYPos(w)
        val sane = sx.isFinite() && sy.isFinite() &&
            sx >= -64.0 && sy >= -64.0 &&
            sx <= w.guiScaledWidth + 64.0 && sy <= w.guiScaledHeight + 64.0
        if (sane) {
            x = sx.toFloat().coerceIn(0f, w.guiScaledWidth.toFloat())
            y = sy.toFloat().coerceIn(0f, w.guiScaledHeight.toFloat())
        } else {
            // Pointer parked somewhere absurd (mouse outside the window):
            // bring it in from the edge nearest the target instead.
            x = if (targetX > 120f) targetX - 90f else targetX + 90f
            y = targetY + 60f
        }
        motion.reset(CursorMotion.Point(x, y))
    }

    private fun gaussianUnit(): Float {
        val u1 = (1.0 - Random.nextDouble()).coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val g = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(6.283185307179586 * u2)
        return g.toFloat().coerceIn(-1f, 1f)
    }

    /** How long a parked pointer lingers after its last movement. */
    internal const val LINGER_MS = 1400L

    /**
     * Whether a parked pointer with no flight running
     * is still on screen this frame.
     *
     * It lingers after the last click so the eye can see where that click
     * went, then gets out of the way. This is the branch that chooses between
     * the drawn pointer and handing the real cursor back, so it decides
     * whether the window has one cursor, two, or none: `positioned` stays set
     * after the final click, and anything that kept drawing off that flag
     * alone would park an arrow over the player's inventory for the rest of
     * the screen's life with the real cursor hidden behind it.
     */
    internal fun lingerOnScreen(now: Long, lastGlideAt: Long): Boolean = now - lastGlideAt < LINGER_MS

    /** Screen-space tip for this frame, or null when the pointer should be hidden. */
    private fun positionNow(now: Long): Pair<Float, Float>? {
        if (!positioned) return null
        if (!motion.moving(now) && !lingerOnScreen(now, lastGlideAt)) return null
        val pos = motion.position(now)
        return pos.x to pos.y
    }

    fun render(graphics: GuiGraphicsExtractor, now: Long = System.currentTimeMillis()) {
        if (!Config.autoTerminalCursorGlide) {
            syncOsCursor(false)
            drawn = false
            return
        }
        // While the pointer is armed it is drawn every frame and the real
        // cursor stays hidden behind it — the two never share the screen, so
        // no viewer sees one cursor hand over to the other, and never a frame
        // with no cursor at all.
        //
        // positionNow goes null twice, and only twice: no pointer is armed,
        // or a parked pointer has lingered past its 1.4s window with nothing
        // left to click. Both mean get out of the way. The second case matters
        // — `positioned` is still set after the final click, so anything that
        // kept drawing on `positioned` alone would park an arrow over the
        // player's inventory for the rest of the screen's life with the real
        // cursor still hidden behind it. Handing back here is correct, and
        // syncOsCursor warps on the way out, so even that lands on the same
        // pixel rather than wherever the physical mouse drifted to.
        val pos = positionNow(now)
        if (pos == null) {
            syncOsCursor(false)
            drawn = false
            return
        }
        syncOsCursor(true)
        drawn = true
        if (moving) pushTrail(pos.first, pos.second, now)
        drawPointer(graphics, pos.first, pos.second, 1f, now)
        x = pos.first
        y = pos.second
        tipKnown = true
    }

    // A pointer that parks dead still reads as a sprite, not as a mouse — and
    // the tell is always the same one: a real cursor leaves something behind
    // when it moves. osu cursors are built entirely out of that idea, so this
    // is what the picture above is made of.

    /**
     * The cursor art: 《CK》 Bacon boi 1.0's `cursor.png` — the one Vaxei
     * plays with. A white core inside a thin yellow rim, with a soft yellow
     * glow filling the rest of the square; the glow is the whole trick, it is
     * what makes the thing read as a cursor rather than a dot on a pane.
     *
     * Shipped at 64px rather than the skin's 128: GUI textures filter nearest,
     * and a 4:1 minification onto the 34px square it is drawn at would alias
     * the rim into a moiré.
     */
    private val cursorTexture =
        Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "textures/cursor/baconboi_cursor.png")

    /** Same skin's `cursortrail.png` — one solid yellow blob. */
    private val trailTexture =
        Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "textures/cursor/baconboi_trail.png")

    /**
     * Drawn square edge, GUI px, and the native edge each sheet ships at.
     *
     * osu draws this art at 128px across a 1920-wide window, 6.7% of the
     * screen. A 480-wide GUI wants 34px for the same fraction, which puts the
     * white core at about six pixels inside a 34px halo — small core, big
     * glow, exactly the proportion the skin was drawn with.
     */
    private const val POINTER_SIZE = 34
    private const val CURSOR_TEX_SIZE = 64
    private const val TRAIL_SIZE = 26
    private const val TRAIL_TEX_SIZE = 32

    /** How long a trail blob lives, and the most that may be alive at once. */
    internal const val TRAIL_MS = 150L
    private const val TRAIL_MAX = 12

    /** Ignore sub-pixel jitter, so a pointer holding still lays no trail. */
    private const val TRAIL_MIN_STEP = 1f

    private data class TrailDot(val x: Float, val y: Float, val at: Long)

    /** Oldest first. Small enough that the copy churn costs nothing. */
    private val trail = ArrayList<TrailDot>(TRAIL_MAX)

    /**
     * If the blit is ever rejected, draw the hand-built arrow for the rest of
     * the run instead of nothing. Sticky on purpose: the pipeline will not
     * start accepting the texture halfway through a terminal.
     */
    private var spriteBroken = false

    /**
     * Lays down one blob where the pointer just was.
     *
     * Only ever called while the pointer is travelling, so a parked pointer
     * leaves the frame clean. Spacing is by distance rather than by frame,
     * because the two are wildly different at 30fps and at 240fps and the
     * trail should not change shape with the frame rate.
     */
    private fun pushTrail(px: Float, py: Float, now: Long) {
        val last = trail.lastOrNull()
        if (last != null && abs(px - last.x) < TRAIL_MIN_STEP && abs(py - last.y) < TRAIL_MIN_STEP) return
        trail.add(TrailDot(px, py, now))
        while (trail.size > TRAIL_MAX) trail.removeAt(0)
    }

    /**
     * Opacity for a trail blob [ageMs] old, 0..255.
     *
     * Squared rather than a straight ramp: a linear fade leaves a long even
     * smear across the terminal, while squaring holds the blob solid for the
     * first few frames and then lets it go quickly. That is what gives a short
     * tight tail directly behind the pointer instead of a comet, and it is the
     * shape this skin's trail reads as.
     */
    internal fun trailAlpha(ageMs: Long): Int {
        if (ageMs >= TRAIL_MS) return 0
        val k = 1f - ageMs.coerceAtLeast(0L) / TRAIL_MS.toFloat()
        if (k <= 0f) return 0
        return (k * k * 170f).toInt().coerceIn(0, 255)
    }

    /**
     * Draws the surviving blobs, oldest and faintest first so the newest sits
     * on top of everything it passes over.
     */
    private fun drawTrail(graphics: GuiGraphicsExtractor, now: Long) {
        while (trail.isNotEmpty() && now - trail.first().at > TRAIL_MS) trail.removeAt(0)
        for (dot in trail) {
            val a = trailAlpha(now - dot.at)
            if (a <= 0) continue
            blitCentered(
                graphics, trailTexture, dot.x, dot.y,
                TRAIL_SIZE, TRAIL_TEX_SIZE, (a shl 24) or 0x00FFFFFF
            )
        }
    }

    /**
     * Draws [texture] as a [size]px square centred on (cx, cy).
     *
     * `blit` takes the source region in texels and the destination in GUI px,
     * so this is one call from sheet to screen — [texSize] is both because
     * each sheet is used whole. Colour is an ARGB tint applied over the art,
     * which is how the trail fades without a second texture per opacity step.
     */
    private fun blitCentered(
        graphics: GuiGraphicsExtractor,
        texture: Identifier,
        cx: Float,
        cy: Float,
        size: Int,
        texSize: Int,
        color: Int
    ) {
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            (cx - size / 2f).toInt(),
            (cy - size / 2f).toInt(),
            0f, 0f,
            size, size,
            texSize, texSize,
            texSize, texSize,
            color
        )
    }

    private fun drawPointer(
        graphics: GuiGraphicsExtractor,
        tipX: Float,
        tipY: Float,
        alpha: Float,
        now: Long
    ) {
        if (!spriteBroken) {
            try {
                drawTrail(graphics, now)
                blitCentered(
                    graphics, cursorTexture, tipX, tipY,
                    POINTER_SIZE, CURSOR_TEX_SIZE,
                    ((255f * alpha).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
                )
                return
            } catch (t: Throwable) {
                // Same contract as the map's marker: a rejected pipeline drops
                // to the hand-built shape rather than leaving the frame short
                // a cursor. Stale blobs go with it — an arrow trailing osu
                // dots would look stranger than an arrow with nothing behind.
                spriteBroken = true
                trail.clear()
            }
        }
        drawArrowPointer(graphics, tipX, tipY, alpha)
    }

    /** The pre-sprite pointer: a hand-built arrow, kept as the fallback. */
    private fun drawArrowPointer(graphics: GuiGraphicsExtractor, tipX: Float, tipY: Float, alpha: Float) {
        val fillA = (255f * alpha).toInt().coerceIn(0, 255)
        val outlineA = (230f * alpha).toInt().coerceIn(0, 255)
        val fill = (fillA shl 24) or 0x00FFFFFF
        val outline = (outlineA shl 24) or 0x00101014
        val shadow = ((fillA / 3) shl 24) or 0x00000000

        val ox = tipX.toInt()
        val oy = tipY.toInt()

        // One-pixel shadow underneath so the pointer reads against a white
        // terminal pane as well as a dark one.
        for (run in runs) {
            val sy = oy + run.row + 1
            graphics.fill(ox + run.x0 + 1, sy + 1, ox + run.x1 + 2, sy + 2, shadow)
        }
        // Dilated outline: each run drawn one row above/below, one px wider.
        for (run in runs) {
            for (row in run.row - 1..run.row + 1) {
                if (row < 0 || row >= ARROW_ROWS) continue
                graphics.fill(ox + run.x0 - 1, oy + row, ox + run.x1 + 2, oy + row + 1, outline)
            }
        }
        for (run in runs) {
            graphics.fill(ox + run.x0, oy + run.row, ox + run.x1 + 1, oy + run.row + 1, fill)
        }
    }

    /**
     * Wired from [AsthoonLite]: one tick listener for cleanup, and one
     * screen hook per container screen to draw the pointer on top of it.
     */
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.screen !is AbstractContainerScreen<*>) {
                // Screen went away mid-flight: release the visual pointer.
                if (moving || positioned) reset()
            }
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                render(graphics)
            }
        }
    }

    /**
     * Screen-space centre of [slot], where the pointer is aimed. The container
     * origin comes from the accessor because `leftPos`/`topPos` are protected —
     * the slot coordinates alone are relative to the container, not the screen.
     */
    fun targetFor(screen: AbstractContainerScreen<*>, slotIndex: Int): Pair<Float, Float>? {
        if (TermGui.active(screen)) return TermGui.gridFor(screen)?.center(slotIndex)
        val slot = screen.menu.slots.getOrNull(slotIndex) ?: return null
        val acc = screen as? AbstractContainerScreenAccessor ?: return null
        return (acc.leftPos + slot.x + 8f) to (acc.topPos + slot.y + 8f)
    }
}
