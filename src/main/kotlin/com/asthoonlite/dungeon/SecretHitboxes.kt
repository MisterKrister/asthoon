package com.asthoonlite.dungeon

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Enlarged interaction shapes for dungeon secret controls (levers, buttons,
 * skull heads, mushrooms) plus the visualisation of those shapes.
 *
 * How the override actually reaches the game — the important part, because
 * this is what was broken before:
 *
 *   Minecraft.pick() -> Entity.pick() -> Level.clip(ClipContext.Block.OUTLINE)
 *       -> BlockStateBase.getShape(level, pos, ctx)      <-- MixinBlockStateShape
 *           -> Block.getShape(...) virtual -> LeverBlock.getShape / ...
 *
 * The old implementation mixed into `LeverBlock#getShape` and
 * `ButtonBlock#getShape` only. That worked for those two classes and did
 * literally nothing for skulls and mushrooms (no mixin existed), which is why
 * the skull/mushroom toggles drew a box you could not click. It also had no
 * way to reach `WallSkullBlock`, which does not extend `SkullBlock` at all —
 * it extends `AbstractSkullBlock` directly.
 *
 * Now a single injection sits on `BlockStateBase#getShape`, ahead of the
 * virtual dispatch, so every block type goes through the same gate.
 *
 * How the slider behaves: the box is a per-axis lerp from the *real* vanilla
 * footprint to a per-family target box. 0 % hands the call straight back to
 * vanilla ("normal"), 100 % lands on the target ("full block"). Nothing in
 * between ever shrinks below vanilla or overshoots the target.
 *
 * Physics safety: `BlockBehaviour#getCollisionShape()` falls back to the
 * 2-arg `BlockStateBase#getShape()`, which always passes
 * `CollisionContext.empty()`. We never override on an empty context, so the
 * enlarged shape can only ever be seen by picking/outline code, never by the
 * collision system. See MixinBlockStateShape for the full note.
 */
object SecretHitboxes {

    /** One of the four block families this module owns. */
    enum class Kind { LEVER, BUTTON, SKULL, MUSHROOM }

    /** A tracked secret control, rebuilt every client tick. */
    data class SecretBlock(val pos: BlockPos, val kind: Kind, val state: BlockState)

    private val pressedUntil = HashMap<BlockPos, Long>()
    private val previousPowered = HashMap<BlockPos, Boolean>()

    /** Render list. Rebuilt on the tick thread (20 Hz), read on the render
     *  thread (once per frame) — never scanned block-by-block per frame. */
    @Volatile
    private var tracked = emptyList<SecretBlock>()

    /** Bounded VoxelShape memo so the per-raycast shape build stops allocating.
     *  Key covers the state identity *and* the slider value — both the shape
     *  and its vanilla base depend on them. */
    private val shapeCache = HashMap<Long, VoxelShape>()
    private var shapeCacheSize = -1

    private fun shapeKey(state: BlockState, sizePercent: Int): Long =
        (state.hashCode().toLong() and 0xFFFFFFFFL) shl 10 or (sizePercent.toLong() and 0x3FF)

    // ── Hot path ────────────────────────────────────────────────────────────

    /**
     * Gate called from MixinBlockStateShape for *every* block shape lookup in
     * the world. Two boolean reads, no allocation, no map access.
     */
    @JvmStatic
    fun shapeOverrideEnabled(): Boolean =
        DungeonContext.inDungeon && Config.secretHitboxesEnabled

    /**
     * Enlarged interaction shape for this state/pos, or null to let vanilla
     * through untouched. `null` is the normal answer — only the four tracked
     * block families inside a dungeon ever return a shape.
     *
     * At 0 % it returns null on purpose: "normal" means vanilla decides, so
     * the mixin is never cancelled and there is nothing to approximate.
     */
    @JvmStatic
    fun interactionShape(state: BlockState, pos: BlockPos, level: BlockGetter): VoxelShape? {
        if (!shapeOverrideEnabled()) return null
        val kind = kindOf(state) ?: return null
        if (!isKindEnabled(kind, pos)) return null
        if (Config.secretHitboxSize <= 0) return null

        val key = shapeKey(state, Config.secretHitboxSize)
        shapeCache[key]?.let { return it }

        val vanilla = vanillaBounds(state, level, pos) ?: return null
        val bounds = lerpBounds(vanilla, targetBounds(kind, vanilla), Config.secretHitboxSize)
        val made = Shapes.box(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])

        // Defensive ceiling: the key space is small by construction, but never
        // let a pathological case turn this into an unbounded map.
        if (shapeCache.size > 256) shapeCache.clear()
        shapeCache[key] = made
        return made
    }

    /** Fast classifier. `AbstractSkullBlock` rather than `SkullBlock` because
     *  `WallSkullBlock` is a sibling, not a subclass, of `SkullBlock`. */
    @JvmStatic
    fun kindOf(state: BlockState): Kind? {
        val block = state.block
        return when {
            block is LeverBlock -> Kind.LEVER
            block is ButtonBlock -> Kind.BUTTON
            block is AbstractSkullBlock -> Kind.SKULL
            block === Blocks.RED_MUSHROOM || block === Blocks.BROWN_MUSHROOM ||
                block === Blocks.RED_MUSHROOM_BLOCK || block === Blocks.BROWN_MUSHROOM_BLOCK -> Kind.MUSHROOM
            else -> null
        }
    }

    // ── Per-type enable checks ──────────────────────────────────────────────

    @JvmStatic
    fun isValidLever(pos: BlockPos): Boolean =
        DungeonContext.inDungeon && Config.secretHitboxesEnabled && Config.leverHitboxEnabled && pos !in blackListedLevers

    @JvmStatic
    fun isButtonHitboxEnabled(): Boolean =
        DungeonContext.inDungeon && Config.secretHitboxesEnabled && Config.buttonHitboxEnabled

    @JvmStatic
    fun isSkullHitboxEnabled(): Boolean =
        DungeonContext.inDungeon && Config.secretHitboxesEnabled && Config.skullHitboxEnabled

    @JvmStatic
    fun isMushroomHitboxEnabled(): Boolean =
        DungeonContext.inDungeon && Config.secretHitboxesEnabled && Config.mushroomHitboxEnabled

    @JvmStatic
    fun isLeverHitboxEnabled(pos: BlockPos): Boolean = isValidLever(pos)

    /**
     * True when the block outline renderer should hand control to us.
     *
     * Quiet mode drops the override so the outline vanilla itself would draw
     * is what shows up — an untouched client draws a selection outline when
     * you look at a block, so showing that is the legitimate-looking result,
     * not a missing one. The click target is untouched by this: it comes from
     * [interactionShape], which does not consult quiet mode.
     */
    @JvmStatic
    fun shouldOverrideOutline(state: BlockState, pos: BlockPos): Boolean {
        if (QuietMode.suppressing()) return false
        if (!shapeOverrideEnabled()) return false
        return when (kindOf(state)) {
            Kind.LEVER -> isValidLever(pos)
            Kind.BUTTON -> isButtonHitboxEnabled()
            Kind.SKULL -> isSkullHitboxEnabled()
            Kind.MUSHROOM -> isMushroomHitboxEnabled()
            null -> false
        }
    }

    /**
     * What actually gets drawn as the block selection outline.
     *
     * - `secretHitboxHideOutline` -> nothing at all.
     * - `secretHitboxVanillaOutline` -> the *real* vanilla shape, read through
     *   the 2-arg overload which runs with CollisionContext.empty() and is
     *   therefore immune to our own override. The previous build re-implemented
     *   vanilla's button/lever geometry by hand here; it disagreed with the
     *   real 26.1.2 shape (vanilla now builds buttons from
     *   `Shapes.join(cube(14), rotated plate, ONLY_FIRST)` over the full 1..15
     *   face, not the old 5..11 inset), so the outline was drawn on the wrong
     *   footprint. Read it, don't guess it.
     * - otherwise -> the enlarged shape, so what you see is what you click.
     */
    @JvmStatic
    fun getOutlineShape(state: BlockState, pos: BlockPos, level: BlockGetter): VoxelShape {
        if (Config.secretHitboxHideOutline) return Shapes.empty()
        if (Config.secretHitboxVanillaOutline) return vanillaShape(state, level, pos)
        return interactionShape(state, pos, level) ?: vanillaShape(state, level, pos)
    }

    /**
     * The untouched vanilla shape for this state.
     *
     * The 2-arg `getShape(BlockGetter, BlockPos)` always resolves through
     * `CollisionContext.empty()`, and MixinBlockStateShape refuses to run on
     * an empty context — so this is a guaranteed clean read even while the
     * feature is switched on. It is also what terminates the recursion: an
     * override that needs the vanilla base can always ask for it safely.
     */
    @JvmStatic
    fun vanillaShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        state.getShape(level, pos)

    // ── Expansion: normal (0 %) → full block (100 %) ────────────────────────

    /**
     * 0.0 at the slider minimum, 1.0 at the maximum. Clamped on both ends so
     * a stale config value can never produce an inverted box.
     */
    @JvmStatic
    fun expansionFraction(sizePercent: Int): Double = sizePercent.coerceIn(0, 100) / 100.0

    /**
     * The block-local box the expansion grows *toward*.
     *
     * levers / skulls / mushrooms grow all the way to the cell walls: that is
     * "full block", and it is what their GUI rows already promise.
     *
     * buttons stop at the button's own height. X and Z go to the cell walls
     * ("as wide as the block") while Y keeps the vanilla range, so the box
     * ends up wide but never taller than the button. Taking Y from vanilla
     * rather than hard-coding a number is what makes this orientation-free —
     * floor, ceiling and wall buttons all keep their real plate height with no
     * per-face bookkeeping.
     */
    @JvmStatic
    fun targetBounds(kind: Kind, vanilla: DoubleArray): DoubleArray = when (kind) {
        Kind.BUTTON -> doubleArrayOf(0.0, vanilla[1], 0.0, 1.0, vanilla[4], 1.0)
        else -> doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    }

    /**
     * Per-axis interpolation from the vanilla footprint to the target box.
     *
     * 0 % returns vanilla untouched, 100 % returns the target untouched, and
     * every step in between is monotone: the result can never fall below
     * vanilla (so we never make a control harder to click than the game does)
     * and can never overshoot the target (so it never leaves the block — the
     * server validates the reported hit within ±1.0000001 of block centre).
     */
    @JvmStatic
    fun lerpBounds(vanilla: DoubleArray, target: DoubleArray, sizePercent: Int): DoubleArray {
        val t = expansionFraction(sizePercent)
        if (t <= 0.0) return vanilla.copyOf()
        if (t >= 1.0) return target.copyOf()
        return DoubleArray(6) { i -> vanilla[i] + (target[i] - vanilla[i]) * t }
    }

    /** Vanilla footprint as a block-local min/max box, or null when empty. */
    @JvmStatic
    fun vanillaBounds(state: BlockState, level: BlockGetter, pos: BlockPos): DoubleArray? {
        val shape = vanillaShape(state, level, pos)
        if (shape.isEmpty()) return null
        val b = shape.bounds()
        return doubleArrayOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
    }

    /**
     * The box drawn on screen for a tracked control: the picker's shape, or
     * vanilla's when the picker has nothing to add (0 %, feature off).
     * Display and click target are derived from one call so they cannot drift.
     */
    private fun displayBounds(state: BlockState, pos: BlockPos, level: BlockGetter): DoubleArray? {
        val shape = interactionShape(state, pos, level) ?: vanillaShape(state, level, pos)
        if (shape.isEmpty()) return null
        val b = shape.bounds()
        return doubleArrayOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
    }

    // ── Pressed-state flash ─────────────────────────────────────────────────

    @JvmStatic
    fun markPressed(pos: BlockPos) {
        if (Config.pressedHitboxEnabled) pressedUntil[pos] = System.currentTimeMillis() + Config.pressedHitboxDuration
    }

    // ── Tick / render ───────────────────────────────────────────────────────

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.END_EXTRACTION.register { render() }
    }

    private fun tick() {
        if (!DungeonContext.inDungeon) {
            if (tracked.isNotEmpty() || pressedUntil.isNotEmpty() || previousPowered.isNotEmpty()) {
                pressedUntil.clear()
                previousPowered.clear()
                tracked = emptyList()
            }
            return
        }

        // Config can change at any moment from the GUI; drop stale shapes.
        if (Config.secretHitboxSize != shapeCacheSize) {
            shapeCacheSize = Config.secretHitboxSize
            shapeCache.clear()
        }

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val radius = 8

        val floor = player.y.toInt()
        val found = ArrayList<SecretBlock>(32)
        val live = HashSet<BlockPos>()
        val now = System.currentTimeMillis()

        var x = player.x.toInt() - radius
        val xEnd = player.x.toInt() + radius
        while (x <= xEnd) {
            var y = floor - radius
            val yEnd = floor + radius
            while (y <= yEnd) {
                var z = player.z.toInt() - radius
                val zEnd = player.z.toInt() + radius
                while (z <= zEnd) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    val kind = kindOf(state)
                    if (kind != null && isKindEnabled(kind, pos)) {
                        found.add(SecretBlock(pos, kind, state))
                        live.add(pos)
                    }
                    if (kind == Kind.LEVER || kind == Kind.BUTTON) {
                        val powered = powered(state)
                        val old = previousPowered.put(pos, powered)
                        if (Config.pressedHitboxEnabled && old == false && powered) {
                            pressedUntil[pos] = now + Config.pressedHitboxDuration
                        }
                    }
                    z++
                }
                y++
            }
            x++
        }

        previousPowered.keys.retainAll(live)
        pressedUntil.entries.removeIf { it.value < now }
        tracked = found
    }

    private fun isKindEnabled(kind: Kind, pos: BlockPos): Boolean = when (kind) {
        Kind.LEVER -> isValidLever(pos)
        Kind.BUTTON -> isButtonHitboxEnabled()
        Kind.SKULL -> isSkullHitboxEnabled()
        Kind.MUSHROOM -> isMushroomHitboxEnabled()
    }

    private fun render() {
        if (!Config.secretHitboxesEnabled || !DungeonContext.inDungeon) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        // Visualised boxes come from the tick-built list — no world scan here.
        // They are the *same* value the picker uses, so what is drawn is
        // exactly what is clickable; at 0 % that falls back to vanilla.
        if (Config.moddedHitboxDisplayEnabled) {
            for (secret in tracked) {
                val b = displayBounds(secret.state, secret.pos, level) ?: continue
                val px = secret.pos.x.toDouble()
                val py = secret.pos.y.toDouble()
                val pz = secret.pos.z.toDouble()
                WorldBoxRenderer.queueOutline(
                    px + b[0], py + b[1], pz + b[2], px + b[3], py + b[4], pz + b[5],
                    0.12f, 0.70f, 1f, 1f, thickness = 0.025
                )
                WorldBoxRenderer.queueFilled(
                    px + b[0], py + b[1], pz + b[2], px + b[3], py + b[4], pz + b[5],
                    0.12f, 0.70f, 1f, 0.08f
                )
            }
        }

        if (!Config.pressedHitboxEnabled || pressedUntil.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((pos, until) in pressedUntil) {
            if (until < now) continue
            val state = level.getBlockState(pos)
            if (kindOf(state) == null) continue
            // 2-arg getShape == CollisionContext.empty() == guaranteed vanilla
            // footprint, even with the feature live.
            val shape = state.getShape(level, pos).bounds()
            WorldBoxRenderer.queueOutline(
                pos.x + shape.minX, pos.y + shape.minY, pos.z + shape.minZ,
                pos.x + shape.maxX, pos.y + shape.maxY, pos.z + shape.maxZ,
                1f, 0.82f, 0.1f, 1f
            )
        }
    }

    /**
     * Levers on the F7/M7 blood-door mechanism. Clicking these with an
     * enlarged box desyncs the door sequence, so they are excluded outright.
     */
    private val blackListedLevers = setOf(
        BlockPos(61, 136, 142), BlockPos(60, 136, 142), BlockPos(59, 136, 142),
        BlockPos(62, 135, 142), BlockPos(61, 135, 142), BlockPos(59, 135, 142),
        BlockPos(58, 135, 142), BlockPos(62, 134, 142), BlockPos(61, 134, 142),
        BlockPos(59, 134, 142), BlockPos(58, 134, 142), BlockPos(61, 133, 142),
        BlockPos(60, 133, 142), BlockPos(59, 133, 142)
    )

    private fun powered(state: BlockState): Boolean = when {
        state.hasProperty(LeverBlock.POWERED) -> state.getValue(LeverBlock.POWERED)
        state.hasProperty(ButtonBlock.POWERED) -> state.getValue(ButtonBlock.POWERED)
        else -> false
    }
}
