package com.salex01.bettertrees.world;

import com.salex01.bettertrees.generator.OccupancyView;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world-layer implementation of {@link OccupancyView} (plan §6.1) — the only place growth
 * ever reads real block state. {@code origin} is the world position the generator's local
 * {@code (0,0,0)} maps to (the sapling's own position for Feature 1, plan §7).
 */
public final class LevelOccupancyView implements OccupancyView {
    private final Level level;
    private final BlockPos origin;
    private final boolean breakReplaceable;

    /** {@code breakReplaceable} mirrors {@code TreeProfile.Occupancy.breakReplaceable()} — when false, tag-matched vegetation reads as {@link Owner#solid()} instead of {@link Owner#replaceable()}, so growth steers around it like any other obstruction. */
    public LevelOccupancyView(Level level, BlockPos origin, boolean breakReplaceable) {
        this.level = level;
        this.origin = origin;
        this.breakReplaceable = breakReplaceable;
    }

    @Override
    public Owner at(Vec3i pos) {
        BlockPos worldPos = origin.offset(pos.x(), pos.y(), pos.z());
        if (!level.isLoaded(worldPos)) {
            // Growth never touches unloaded chunks — read as SOLID so it steers away rather than
            // risk generating a chunk off in the dark. Known trade-off: a sapling grown right at the
            // edge of loaded/simulated chunks (e.g. render distance 2, or right after world join)
            // can read the unloaded side as an artificial wall and come out visibly lopsided. Safer
            // than the alternative (reading — and thereby triggering generation of — the real
            // terrain), and out of reach for a normal player standing next to their own sapling;
            // revisit only if this turns out to matter in practice.
            return Owner.solid();
        }
        BlockState state = level.getBlockState(worldPos);
        if (BranchLookup.of(state) != null) {
            // No per-tree ownership tracking yet (that's TreeCoreBlockEntity, plan §17) — every
            // branch or log already in the world reads as someone else's tree. Harmless for the
            // currently-growing tree itself: nothing of "ours" exists in the world yet when this
            // runs, since BlueprintPlacer only writes blocks after generation finishes.
            return Owner.otherTree();
        }
        if (state.isAir()) {
            return Owner.free();
        }
        if (state.is(ModTags.TREE_REPLACEABLE)) {
            return breakReplaceable ? Owner.replaceable() : Owner.solid();
        }
        return Owner.solid();
    }
}
