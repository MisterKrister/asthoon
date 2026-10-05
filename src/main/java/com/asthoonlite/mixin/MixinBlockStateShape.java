package com.asthoonlite.mixin;

import com.asthoonlite.dungeon.SecretHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single choke point for every block interaction/outline shape override.
 *
 * Why this exists instead of per-block mixins on LeverBlock/ButtonBlock:
 *
 *   BlockBehaviour$BlockStateBase.getShape(BlockGetter, BlockPos, CollisionContext)
 *       -> Block.getShape(state, level, pos, ctx)      // virtual dispatch
 *           -> LeverBlock.getShape / ButtonBlock.getShape / SkullBlock.getShape / ...
 *
 * Injecting on the *base* method only fires when the base body runs, so any
 * block that overrides getShape (lever, button, skull, wall skull, mushroom)
 * would bypass it. Injecting on BlockStateBase.getShape fires before the
 * virtual call, so one injection covers every block type, and it is exactly
 * the method that net.minecraft.world.level.ClipContext$Block.OUTLINE resolves
 * to (verified against this jar's BootstrapMethods table) — which is what
 * Minecraft.pick() / Entity.pick() uses for the block you are looking at.
 *
 * Physics is deliberately never touched:
 *   ClipContext.Block.COLLIDER resolves to getCollisionShape(), and
 *   BlockBehaviour.getCollisionShape() falls back to the *2-arg* getShape(),
 *   which always runs with CollisionContext.empty(). We refuse to override on
 *   an empty context, so the enlarged interaction shape can never leak into
 *   collision. Only the picking / outline path (EntityCollisionContext) is
 *   affected.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class MixinBlockStateShape {

    @Inject(
            method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void asthoonlite$secretInteractionShape(BlockGetter level, BlockPos pos, CollisionContext context,
                                                    CallbackInfoReturnable<VoxelShape> cir) {
        // Hot gate first: no allocation, two boolean reads, and it is false
        // everywhere outside a dungeon run or with the feature switched off.
        if (!SecretHitboxes.shapeOverrideEnabled()) return;
        if (pos == null || context == null) return;

        // Never on an empty context — that is the physics/placement path.
        if (context == CollisionContext.empty()) return;
        if (context == CollisionContext.emptyWithFluidCollisions()) return;

        Object self = this;
        if (!(self instanceof BlockState state)) return;

        VoxelShape shape = SecretHitboxes.interactionShape(state, pos, level);
        if (shape != null) cir.setReturnValue(shape);
    }
}
