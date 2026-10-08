package com.asthoonlite.dungeon

import com.asthoonlite.QuietMode
import com.asthoonlite.config.Config
import com.asthoonlite.render.WorldBoxRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.WitherSkullBlock
import net.minecraft.world.level.block.WitherWallSkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
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
    private var shapeCacheSignature = -1L

    private fun shapeKey(state: BlockState, sizePercent: Int): Long =
        (state.hashCode().toLong() and 0xFFFFFFFFL) shl 10 or (sizePercent.toLong() and 0x3FF)

    /**
     * Every size setting, packed. The memo is keyed on the *resulting* size,
     * so stale entries can only ever be wasteful, never wrong — but a slider
     * that has been moved should not leave the old boxes sitting in memory
     * either, and five separate fields cannot be watched with one integer.
     */
    private fun sizeSignature(): Long {
        var s = Config.secretLeverHitboxSize.toLong() and 0x7FL
        s = (s shl 7) or (Config.secretButtonHitboxSize.toLong() and 0x7FL)
        s = (s shl 7) or (Config.secretSkullHitboxSize.toLong() and 0x7FL)
        s = (s shl 7) or (Config.secretMushroomHitboxSize.toLong() and 0x7FL)
        return s
    }

    // ── Hot path ────────────────────────────────────────────────────────────

    /**
     * Gate called from MixinBlockStateShape for *every* block shape lookup in
     * the world. Two boolean reads, no allocation, no map access.
     */
    @JvmStatic
    fun shapeOverrideEnabled(): Boolean =
        (DungeonContext.inDungeon || Config.secretHitboxAnywhere) && Config.secretHitboxesEnabled

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
        val sizePercent = sizePercentFor(kind)
        if (sizePercent <= 0) return null

        val key = shapeKey(state, sizePercent)
        shapeCache[key]?.let { return it }

        val bounds = if (kind == Kind.LEVER) {
            getLeverRelativeBounds(state, sizePercent)
        } else {
            val vanilla = vanillaBounds(state, level, pos) ?: return null
            lerpBounds(vanilla, targetBounds(kind, vanilla), sizePercent)
        }
        val made = Shapes.box(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])

        // Defensive ceiling: the key space is small by construction, but never
        // let a pathological case turn this into an unbounded map.
        if (shapeCache.size > 256) shapeCache.clear()
        shapeCache[key] = made
        return made
    }

    /** Fast classifier. Only Wither Skeleton skulls (Wither Essence) count as secret skulls. */
    @JvmStatic
    fun kindOf(state: BlockState): Kind? {
        val block = state.block
        return when {
            block is LeverBlock -> Kind.LEVER
            block is ButtonBlock -> Kind.BUTTON
            block is WitherSkullBlock || block is WitherWallSkullBlock ||
                block === Blocks.WITHER_SKELETON_SKULL || block === Blocks.WITHER_SKELETON_WALL_SKULL -> Kind.SKULL
            block === Blocks.RED_MUSHROOM || block === Blocks.BROWN_MUSHROOM ||
                block === Blocks.RED_MUSHROOM_BLOCK || block === Blocks.BROWN_MUSHROOM_BLOCK -> Kind.MUSHROOM
            else -> null
        }
    }

    // ── Per-type enable checks ──────────────────────────────────────────────

    @JvmStatic
    fun isValidLever(pos: BlockPos): Boolean =
        (DungeonContext.inDungeon || Config.secretHitboxAnywhere) && Config.secretHitboxesEnabled && Config.leverHitboxEnabled && pos !in blackListedLevers

    @JvmStatic
    fun isButtonHitboxEnabled(): Boolean =
        (DungeonContext.inDungeon || Config.secretHitboxAnywhere) && Config.secretHitboxesEnabled && Config.buttonHitboxEnabled

    @JvmStatic
    fun isSkullHitboxEnabled(): Boolean =
        (DungeonContext.inDungeon || Config.secretHitboxAnywhere) && !DungeonContext.inBoss && Config.secretHitboxesEnabled && Config.skullHitboxEnabled

    @JvmStatic
    fun isMushroomHitboxEnabled(): Boolean =
        (DungeonContext.inDungeon || Config.secretHitboxAnywhere) && !DungeonContext.inBoss && Config.secretHitboxesEnabled && Config.mushroomHitboxEnabled

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
     * buttons are the interesting one. A button sticks out of a face by 2 px
     * and presents a plate 6–8 px across, so one axis is always about a
     * quarter as thick as the others — and *which* axis it is depends on where
     * the button is mounted. Floor and ceiling buttons carry their depth in Y,
     * wall buttons carry it in X or Z. Measured from 26.1.2:
     *
     *   floor   ext (0.375, 0.125, 0.250)   depth = Y
     *   wall    ext (0.375, 0.250, 0.125)   depth = Z
     *   ceiling ext (0.375, 0.125, 0.250)   depth = Y
     *
     * So the rule is not "Y stays" — that only held for buttons on the floor,
     * and it made wall buttons protrude a whole block. The rule is: the
     * thinnest axis is the depth, it stays exactly vanilla, the other two go
     * to the cell walls. Length and width grow; how far it sticks out does not.
     */
    @JvmStatic
    fun targetBounds(kind: Kind, vanilla: DoubleArray): DoubleArray = when (kind) {
        Kind.BUTTON -> buttonTargetBounds(vanilla)
        else -> doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    }

    private fun buttonTargetBounds(vanilla: DoubleArray): DoubleArray {
        val ex = vanilla[3] - vanilla[0]
        val ey = vanilla[4] - vanilla[1]
        val ez = vanilla[5] - vanilla[2]
        val extents = doubleArrayOf(ex, ey, ez)

        // Smallest extent is the depth — unless nothing is meaningfully
        // smaller than anything else, in which case the shape is not a plate
        // on a face and there is no depth to preserve. Keep Y then: it is the
        // orientation buttons are most often found in and the old behaviour.
        val depth = extents.indices.minByOrNull { extents[it] } ?: 1
        if (extents.max() >= extents[depth] * 2.0) {
            return when (depth) {
                0 -> doubleArrayOf(vanilla[0], 0.0, 0.0, vanilla[3], 1.0, 1.0)
                2 -> doubleArrayOf(0.0, 0.0, vanilla[2], 1.0, 1.0, vanilla[5])
                else -> doubleArrayOf(0.0, vanilla[1], 0.0, 1.0, vanilla[4], 1.0)
            }
        }
        return doubleArrayOf(0.0, vanilla[1], 0.0, 1.0, vanilla[4], 1.0)
    }

    /**
     * Relative bounding box for a lever scaled to [sizePercent] (1..100%).
     * Scales outward from the attached face (floor, ceiling, or wall) centered on the face,
     * matching Noamm's getLeverShape math.
     */
    @JvmStatic
    fun getLeverRelativeBounds(state: BlockState, sizePercent: Int): DoubleArray {
        val size = (sizePercent.coerceIn(1, 100) / 100.0).coerceIn(0.1, 1.0)
        val half = size / 2.0
        if (!state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE) ||
            !state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACING)) {
            val pad = (1.0 - size) / 2.0
            return doubleArrayOf(pad, pad, pad, 1.0 - pad, 1.0 - pad, 1.0 - pad)
        }
        val face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
        val dir = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)

        return when (face) {
            AttachFace.FLOOR -> doubleArrayOf(
                0.5 - half, 0.0, 0.5 - half,
                0.5 + half, size, 0.5 + half
            )
            AttachFace.CEILING -> doubleArrayOf(
                0.5 - half, 1.0 - size, 0.5 - half,
                0.5 + half, 1.0, 0.5 + half
            )
            else -> when (dir) {
                Direction.EAST -> doubleArrayOf(0.0, 0.5 - half, 0.5 - half, size, 0.5 + half, 0.5 + half)
                Direction.WEST -> doubleArrayOf(1.0 - size, 0.5 - half, 0.5 - half, 1.0, 0.5 + half, 0.5 + half)
                Direction.SOUTH -> doubleArrayOf(0.5 - half, 0.5 - half, 0.0, 0.5 + half, 0.5 + half, size)
                Direction.NORTH -> doubleArrayOf(0.5 - half, 0.5 - half, 1.0 - size, 0.5 + half, 0.5 + half, 1.0)
                else -> doubleArrayOf(0.5 - half, 0.0, 0.5 - half, 0.5 + half, size, 0.5 + half)
            }
        }
    }

    /**
     * The slider actually applied to [kind]: the master expansion multiplied
     * by this block family's own setting.
     *
     * Two numbers rather than one so a lever can stay forgiving while a
     * button sits close to stock — which is what a single shared slider could
     * never express. 100 % on the per-kind slider means "follow the master",
     * so a config that never touches them behaves exactly as it did before
     * they existed.
     */
    @JvmStatic
    fun sizePercentFor(kind: Kind): Int {
        val perKind = when (kind) {
            Kind.LEVER -> Config.secretLeverHitboxSize
            Kind.BUTTON -> Config.secretButtonHitboxSize
            Kind.SKULL -> Config.secretSkullHitboxSize
            Kind.MUSHROOM -> Config.secretMushroomHitboxSize
        }
        return perKind.coerceIn(0, 100)
    }

    /** The combination itself, kept pure so it can be pinned by a test. */
    @JvmStatic
    fun sizePercent(master: Int, perKind: Int): Int =
        (master.coerceIn(0, 100) * perKind.coerceIn(0, 100)) / 100

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
        val kind = kindOf(state)
        if (kind == Kind.LEVER && (isKindEnabled(Kind.LEVER, pos) || Config.moddedHitboxDisplayEnabled)) {
            val pct = sizePercentFor(Kind.LEVER)
            if (pct > 0) return getLeverRelativeBounds(state, pct)
        }
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
        if ((!DungeonContext.inDungeon && !Config.secretHitboxAnywhere) ||
            (!Config.secretHitboxesEnabled && !Config.moddedHitboxDisplayEnabled && !Config.pressedHitboxEnabled)) {
            if (tracked.isNotEmpty() || pressedUntil.isNotEmpty() || previousPowered.isNotEmpty()) {
                pressedUntil.clear()
                previousPowered.clear()
                tracked = emptyList()
            }
            return
        }

        // Config can change at any moment from the GUI; drop stale shapes.
        val signature = sizeSignature()
        if (signature != shapeCacheSignature) {
            shapeCacheSignature = signature
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
        val mpos = BlockPos.MutableBlockPos()

        var x = player.x.toInt() - radius
        val xEnd = player.x.toInt() + radius
        while (x <= xEnd) {
            var y = floor - radius
            val yEnd = floor + radius
            while (y <= yEnd) {
                var z = player.z.toInt() - radius
                val zEnd = player.z.toInt() + radius
                while (z <= zEnd) {
                    mpos.set(x, y, z)
                    val state = level.getBlockState(mpos)
                    val kind = kindOf(state)
                    if (kind != null) {
                        val pos = mpos.immutable()
                        val shouldTrack = isKindEnabled(kind, pos) || (Config.moddedHitboxDisplayEnabled && (kind != Kind.LEVER || pos !in blackListedLevers) && (!DungeonContext.inBoss || (kind != Kind.SKULL && kind != Kind.MUSHROOM)))
                        if (shouldTrack) {
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
        if (!DungeonContext.inDungeon && !Config.secretHitboxAnywhere) return
        if (!Config.secretHitboxesEnabled && !Config.moddedHitboxDisplayEnabled && !Config.pressedHitboxEnabled) return
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
                    0.12f, 0.70f, 1f, 1f, thickness = 0.025,
                    throughWalls = Config.secretHitboxThroughWalls
                )
                WorldBoxRenderer.queueFilled(
                    px + b[0], py + b[1], pz + b[2], px + b[3], py + b[4], pz + b[5],
                    0.12f, 0.70f, 1f, 0.08f,
                    throughWalls = Config.secretHitboxThroughWalls
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
                1f, 0.82f, 0.1f, 1f,
                throughWalls = Config.secretHitboxThroughWalls
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
