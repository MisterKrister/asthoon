package com.asthoonlite.dungeon

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.mixin.AbstractContainerScreenAccessor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.glfw.GLFW
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * A mouse pointer that moves like a hand, drawn over the container screen.
 *
 * The mod never moves the real cursor. Terminal clicks go down the packet path
 * (`MultiPlayerGameMode.handleContainerInput` -> `ServerboundContainerClickPacket`),
 * so nothing about the click needs a pointer at all — this object is purely the
 * picture of one. What it buys is that a solved terminal no longer *looks* like
 * it was solved by an act of God: a pointer travels to the pane, decelerates
 * into it, and the click lands when the pointer arrives.
 *
 * Path shape, in order of importance:
 *  - a quadratic bezier whose control point is pushed off the straight line, so
 *    the pointer bows instead of tracking a ruler;
 *  - minimum-jerk easing (the 6t^5-15t^4+10t^3 curve), which is what a real arm
 *    does — accelerate, then settle onto the target rather than stopping dead;
 *  - a decaying tremor, because a hand does not hold still on the way in;
 *  - travel time scaled by distance with gaussian spread, so a far flick takes
 *    longer than a hop to the neighbouring pane.
 *
 * Everything pure in here ([progressAt], [travelDurationMs], [bezier],
 * [arrowRuns]) is deterministic given its inputs — the regression harness pins
 * all four, so the feel of the movement cannot drift silently.
 */
object TerminalCursor {

    /** Resting tip position in screen space. */
    private var x = 0f
    private var y = 0f
    private var positioned = false

    private var fromX = 0f
    private var fromY = 0f
    private var ctrlX = 0f
    private var ctrlY = 0f
    private var toX = 0f
    private var toY = 0f

    private var startedAt = 0L
    private var durationMs = 1L
    private var moving = false
    private var pendingClick: (() -> Unit)? = null

    /** When the terminal will accept the parked click; 0 = not parking. */
    private var notBeforeAt = 0L

    private var lastGlideAt = 0L
    private var phaseX = Random.nextFloat() * 6.283f
    private var phaseY = Random.nextFloat() * 6.283f

    /** True while the pointer is travelling *or* parked on a pane waiting to
     *  click; callers use it to hold off picking the next pane until this one
     *  has been "clicked". */
    fun busy(): Boolean = moving || pendingClick != null

    fun reset() {
        moving = false
        pendingClick = null
        notBeforeAt = 0L
        positioned = false
        applyOsCursor(false)
    }

    /**
     * Whether the real cursor is currently hidden for the drawn one.
     * Tracks intent, not the window: one transition in, one out.
     */
    private var osCursorHidden = false

    /**
     * Swaps the real cursor out from under the drawn pointer while it is on
     * screen, and puts it back the moment it is not.
     *
     * Two pointers over one pane reads as a bug, and the drawn one is the one
     * that is about to click. [GLFW] is called on the client thread — both
     * the tick and the render pass run there, so the call never leaves the
     * thread GLFW was initialised on. The guard makes it a no-op per state,
     * so calling it every frame while the pointer is up costs nothing.
     */
    private fun applyOsCursor(pointerVisible: Boolean) {
        val wanted = pointerVisible && Config.autoTerminalCursorHideReal
        if (wanted == osCursorHidden) return
        osCursorHidden = wanted
        val handle = Minecraft.getInstance().window.handle()
        GLFW.glfwSetInputMode(
            handle,
            GLFW.GLFW_CURSOR,
            if (wanted) GLFW.GLFW_CURSOR_HIDDEN else GLFW.GLFW_CURSOR_NORMAL
        )
    }

    /**
     * Normalised progress along the path, before the bezier is evaluated.
     * Minimum-jerk easing: zero velocity at both ends, which is what stops the
     * pointer arriving at full speed and teleporting to a stop.
     */
    internal fun progressAt(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 1f
        val t = (elapsedMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
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

    /**
     * Flight time once the terminal's own clock is in play.
     *
     * [naturalMs] is the hand's travel time (distance and the speed setting);
     * [availableMs] is how long until the terminal accepts a click. The pointer
     * never moves faster than the speed setting says it should, and it never
     * dawdles past the moment it is trying to meet — so a long click delay
     * stretches the whole motion out to land exactly on it, while a short one
     * leaves the natural time alone and lets the click fall on arrival.
     * Either way the click is never sent early: the arrival path in [tick]
     * holds at the pane until [notBeforeAt] passes.
     */
    internal fun flightDurationMs(naturalMs: Long, availableMs: Long): Long {
        val natural = naturalMs.coerceIn(MIN_FLIGHT_MS, MAX_FLIGHT_MS)
        if (availableMs <= 0L) return natural
        return maxOf(natural, availableMs.coerceAtMost(MAX_SYNC_MS))
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

    /** Longest the pointer will stretch to meet a click delay. Past this the
     *  natural travel time wins — a crawl across the pane stops reading as a
     *  hand and starts reading as a slideshow. */
    private const val MAX_SYNC_MS = 1000L

    private val runs: List<ArrowRun> by lazy { arrowRuns() }

    /**
     * Arms the pointer for a terminal that is open, and keeps it armed: called
     * every tick while a terminal is on screen so the pointer is there from the
     * moment the pane appears instead of only after the first solve, and so it
     * does not fade out between clicks. Purely presentational — it never queues
     * a click.
     */
    fun show() {
        if (!positioned && !moving && pendingClick == null) {
            val w = Minecraft.getInstance().window
            seedFromMouse(w.guiScaledWidth / 2f, w.guiScaledHeight / 2f)
        }
        if (!moving) lastGlideAt = System.currentTimeMillis()
    }

    /**
     * Starts a glide to ([targetX], [targetY]). [onArrive] fires exactly once,
     * either on the frame the pointer lands or — if it beat the terminal's own
     * delay — on the tick the delay expires with the pointer parked on the
     * pane. That is the moment the click packet goes out, so the picture, the
     * motion and the auto-terminal cadence are all in step.
     *
     * Does nothing and returns false if a flight or a parked click is already
     * outstanding, so callers can fall back to clicking immediately.
     */
    fun glideTo(targetX: Float, targetY: Float, clickNotBeforeMs: Long, onArrive: () -> Unit): Boolean {
        if (moving || pendingClick != null) return false

        if (!positioned) seedFromMouse(targetX, targetY)

        fromX = x
        fromY = y
        toX = targetX
        toY = targetY

        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx, dy)

        // Bow the path: push the control point along the normal of the
        // straight line by a fraction of the distance, sign chosen at random
        // so consecutive moves do not all curve the same way.
        if (dist < 0.5f) {
            ctrlX = fromX
            ctrlY = fromY
        } else {
            val arc = Config.autoTerminalCursorArc.coerceIn(0, 100) / 100f
            val bow = dist * arc * 0.4f * if (Random.nextBoolean()) 1f else -1f
            ctrlX = (fromX + toX) / 2f + (-dy / dist) * bow
            ctrlY = (fromY + toY) / 2f + (dx / dist) * bow
        }

        val now = System.currentTimeMillis()
        val natural = travelDurationMs(dist, Config.autoTerminalCursorSpeed, gaussianUnit())
        durationMs = flightDurationMs(natural, clickNotBeforeMs - now)
        startedAt = now
        notBeforeAt = clickNotBeforeMs
        phaseX = Random.nextFloat() * 6.283f
        phaseY = Random.nextFloat() * 6.283f
        moving = true
        pendingClick = onArrive
        lastGlideAt = startedAt
        return true
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
    }

    /**
     * Lands the pointer and fires the queued click. Two stages: the flight has
     * to finish first, then — if the terminal's own delay is still running —
     * the pointer sits on the pane until it is allowed to click. Nothing ever
     * goes out early, and [busy] holds the solver off the whole time.
     */
    fun tick(now: Long = System.currentTimeMillis()) {
        if (!moving) {
            val parked = pendingClick ?: return
            if (now < notBeforeAt) return
            pendingClick = null
            notBeforeAt = 0L
            lastGlideAt = now
            parked()
            return
        }
        if (now - startedAt < durationMs) return
        x = toX
        y = toY
        moving = false
        lastGlideAt = now
        if (now < notBeforeAt) return
        // Detach before invoking: the click starts the next glide, and that
        // glide must not be handed this one's callback.
        val click = pendingClick
        pendingClick = null
        notBeforeAt = 0L
        click?.invoke()
    }

    private fun gaussianUnit(): Float {
        val u1 = (1.0 - Random.nextDouble()).coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        val g = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(6.283185307179586 * u2)
        return g.toFloat().coerceIn(-1f, 1f)
    }

    /** Screen-space tip for this frame, or null when the pointer should be hidden. */
    private fun positionNow(now: Long): Pair<Float, Float>? {
        if (!positioned) return null
        if (!moving) {
            // Parked on a pane about to click: hold it there however long the
            // terminal's clock takes. Otherwise linger after the last click,
            // then get out of the way.
            if (pendingClick != null) return x to y
            return if (now - lastGlideAt < 1400L) x to y else null
        }
        val t = progressAt(now - startedAt, durationMs)
        var px = bezier(fromX, ctrlX, toX, t)
        var py = bezier(fromY, ctrlY, toY, t)

        val tremor = Config.autoTerminalCursorJitter.coerceIn(0, 100) / 100f
        if (tremor > 0f && t < 1f) {
            val settle = (1f - t)
            px += sin(t * 9f + phaseX) * 1.4f * tremor * settle
            py += cos(t * 7f + phaseY) * 1.4f * tremor * settle
        }
        return px to py
    }

    fun render(graphics: GuiGraphicsExtractor, now: Long = System.currentTimeMillis()) {
        if (!Config.autoTerminalCursorGlide || QuietMode.suppressing()) {
            applyOsCursor(false)
            return
        }
        val pos = positionNow(now)
        if (pos == null) {
            applyOsCursor(false)
            return
        }
        // Fade the resting pointer out instead of popping it off the screen.
        // A parked pointer waiting to click stays solid: it is about to act.
        val alpha = if (moving || pendingClick != null) 1f
        else ((1400L - (now - lastGlideAt)) / 400f).coerceIn(0f, 1f)
        // The real cursor goes with this one, exactly — a pointer faded to
        // nothing must not leave the window without a visible cursor.
        applyOsCursor(alpha > 0f)
        if (alpha <= 0f) return

        drawPointer(graphics, pos.first, pos.second, alpha)
    }

    private fun drawPointer(graphics: GuiGraphicsExtractor, tipX: Float, tipY: Float, alpha: Float) {
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
     * Wired from [AsthoonLite]: one tick listener to land flights, and one
     * screen hook per container screen to draw the pointer on top of it.
     */
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.screen !is AbstractContainerScreen<*>) {
                // Screen went away mid-flight: drop the queued click with it.
                if (moving || positioned) reset()
            } else {
                tick()
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
        val slot = screen.menu.slots.getOrNull(slotIndex) ?: return null
        val acc = screen as? AbstractContainerScreenAccessor ?: return null
        return (acc.leftPos + slot.x + 8f) to (acc.topPos + slot.y + 8f)
    }
}
