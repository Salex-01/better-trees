package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import com.salex01.bettertrees.BetterTrees;
import com.salex01.bettertrees.block.LeafyBranchBlock;
import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.Placement;
import com.salex01.bettertrees.generator.math.Vec3i;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Plan §13 — spreads a big tree's block placement across ticks instead of one `BlueprintPlacer`
 * call issuing thousands of {@code setBlock}s in a single tick. One instance per {@link
 * ServerLevel} (vanilla {@link SavedData} is already level-scoped via {@code
 * ServerLevel#getDataStorage()}), holding every tree currently growing in that dimension.
 *
 * <p>This class is also the only place that still turns a {@link QueuedVoxel} into a real {@link
 * BlockState} — {@code BlueprintPlacer} now only computes the ordered voxel list and enqueues a
 * {@link GrowJob}; the state-resolution logic that used to live there (leaf DISTANCE, D16 axis/bark,
 * branch connections, waterlog, {@code LEAFY}) moved here wholesale rather than being duplicated,
 * since after M9 this is the only code path that actually writes a block for growth.
 */
public final class GrowJobManager extends SavedData {
    private static final int PLACEMENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    public static final SavedDataType<GrowJobManager> TYPE =
            new SavedDataType<>("bettertrees_grow_jobs", GrowJobManager::new, codec());

    private static Codec<GrowJobManager> codec() {
        return GrowJob.CODEC.listOf().xmap(GrowJobManager::new, m -> List.copyOf(m.jobs));
    }

    private final List<GrowJob> jobs;

    public GrowJobManager() {
        this.jobs = new ArrayList<>();
    }

    private GrowJobManager(List<GrowJob> jobs) {
        this.jobs = new ArrayList<>(jobs);
    }

    public static GrowJobManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void enqueue(GrowJob job) {
        jobs.add(job);
        setDirty();
    }

    /**
     * Whether a job already exists at this exact origin — {@code BlueprintPlacer} refuses a second
     * {@code place()} call for a sapling/cluster that already has one queued, rather than double-
     * placing it. Without this, the window between "job enqueued" and "the trunk's base voxel
     * actually overwrites the sapling block" (now several ticks, not the same call) leaves the
     * sapling present, random-tickable and bonemealable, so a second `randomTick` roll or a second
     * bonemeal application would otherwise queue a duplicate job at the same origin.
     */
    public boolean hasJobAt(BlockPos origin) {
        for (GrowJob job : jobs) {
            if (job.origin().equals(origin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forward-looking hook for §12's collapse trigger (Milestone 10, not written yet): a position
     * still inside an in-progress job's own voxel set shouldn't schedule a support check, since the
     * wood around it may not all exist yet. Landed now, unused until M10, per the plan's explicit
     * instruction to suppress collapse "while a grow job is running."
     */
    public boolean isGrowingAt(BlockPos pos) {
        for (GrowJob job : jobs) {
            Vec3i local = new Vec3i(pos.getX() - job.origin().getX(), pos.getY() - job.origin().getY(),
                    pos.getZ() - job.origin().getZ());
            for (int i = job.cursor(); i < job.voxels().size(); i++) {
                if (job.voxels().get(i).local().equals(local)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Places up to {@code budget} voxels total across every pending job in this level, in queue
     * order. Checked per voxel, not once per job: a 16x16 cluster's crown can span several chunks,
     * so checking only the job's origin chunk would still force-load whichever chunk the next voxel
     * happens to land in — precisely the TPS spike this milestone exists to avoid. A voxel whose own
     * chunk isn't loaded stops that job for this tick (its remaining voxels are still in ascending-Y
     * order, so later ones are no likelier to be loaded) without advancing the cursor, so it's
     * retried next tick; the job survives a chunk unload or a server restart either way, since the
     * whole job list is this {@code SavedData}'s persisted state.
     *
     * @return voxels actually placed this tick, so a caller ticking multiple levels can share one budget across them
     */
    public int tick(ServerLevel level, int budget) {
        if (jobs.isEmpty() || budget <= 0) {
            return 0;
        }
        int placed = 0;
        List<GrowJob> stillPending = new ArrayList<>();
        for (GrowJob job : jobs) {
            if (placed >= budget) {
                stillPending.add(job);
                continue;
            }
            BetterTrees.BranchSet set = BetterTrees.BRANCHES.get(job.species());
            while (placed < budget && !job.isDone()) {
                QueuedVoxel voxel = job.next();
                BlockPos worldPos = job.origin().offset(voxel.local().x(), voxel.local().y(), voxel.local().z());
                if (!level.isLoaded(worldPos)) {
                    break;
                }
                level.setBlock(worldPos, resolveState(level, worldPos, voxel, set), PLACEMENT_FLAGS);
                job.advance();
                placed++;
            }
            if (!job.isDone()) {
                stillPending.add(job);
            }
        }
        jobs.clear();
        jobs.addAll(stillPending);
        if (placed > 0) {
            setDirty();
        }
        return placed;
    }

    private static BlockState resolveState(ServerLevel level, BlockPos worldPos, QueuedVoxel voxel, BetterTrees.BranchSet set) {
        if (voxel.kind() == Placement.Kind.LEAF) {
            // Real DISTANCE from LeafPlanner's BFS (plan §9), not a forced PERSISTENT=true — a
            // placed leaf decays exactly like a vanilla-grown one once something removes its
            // support, and PERSISTENT stays at its normal default (false).
            BlockState state = set.leaves().defaultBlockState();
            if (state.hasProperty(BlockStateProperties.DISTANCE)) {
                state = state.setValue(BlockStateProperties.DISTANCE, Math.clamp(voxel.leafDistance(), 1, 7));
            }
            return state;
        }

        if (voxel.diameter() == Diameter.D16) {
            // The vanilla "wood" block (e.g. oak_wood), not "log" — bark on all six faces
            // regardless of AXIS, matching BlueprintPlacer's original M5 reasoning.
            BlockState state = set.wood().defaultBlockState();
            if (state.hasProperty(BlockStateProperties.AXIS)) {
                state = state.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
            }
            return applyWaterlogged(level, worldPos, state);
        }

        Block branchBlock = switch (voxel.diameter()) {
            case D12 -> set.d12().get();
            case D8 -> set.d8().get();
            case D4 -> set.d4().get();
            case D16 -> throw new IllegalStateException("unreachable: D16 handled above");
        };
        BlockState state = branchBlock.defaultBlockState();
        Direction[] directions = Direction.values(); // ordinal order matches PlacementResolver.woodConnections's FACE_OFFSETS
        for (int i = 0; i < directions.length; i++) {
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(directions[i]), voxel.connection(i));
        }

        boolean waterlogged = level.getFluidState(worldPos).getType() == Fluids.WATER;
        state = state.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        if (voxel.diameter() == Diameter.D4) {
            // LEAFY and WATERLOGGED are mutually exclusive (plan §3.2).
            state = state.setValue(LeafyBranchBlock.LEAFY, voxel.leafy() && !waterlogged);
        }
        return state;
    }

    private static BlockState applyWaterlogged(ServerLevel level, BlockPos worldPos, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return state;
        }
        boolean waterlogged = level.getFluidState(worldPos).getType() == Fluids.WATER;
        return state.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }
}
