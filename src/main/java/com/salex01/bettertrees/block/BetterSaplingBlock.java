package com.salex01.bettertrees.block;

import com.salex01.bettertrees.generator.BigTreeGrower;
import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.LeafPlanner;
import com.salex01.bettertrees.generator.Placement;
import com.salex01.bettertrees.generator.PlacementResolver;
import com.salex01.bettertrees.generator.TreeBlueprint;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import com.salex01.bettertrees.generator.trunk.Cell;
import com.salex01.bettertrees.world.BlueprintPlacer;
import com.salex01.bettertrees.world.LevelOccupancyView;
import com.salex01.bettertrees.world.SaplingCluster;
import com.mojang.serialization.MapCodec;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Plan §7/§8 — a sapling growing into a small tree alone, or into a big tree as part of a cluster.
 * Extends {@link VegetationBlock} directly (not vanilla {@code SaplingBlock}, which drags in a
 * {@code TreeGrower} field and a two-stage charge-up we don't want, and not {@code BushBlock},
 * whose {@code BonemealableBlock} implementation spreads to a neighbour rather than growing) — the
 * generate/accept-or-refuse decision here still resolves in one tick, matching bone meal's existing
 * "resolve now" contract, but actual block placement is queued and spread over subsequent ticks by
 * {@code GrowJobManager} (plan §13, Milestone 9) rather than written synchronously in this call.
 */
public final class BetterSaplingBlock extends VegetationBlock implements BonemealableBlock {
    private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 12.0);

    private final String species;
    private final MapCodec<BetterSaplingBlock> codec;

    public BetterSaplingBlock(BlockBehaviour.Properties properties, String species) {
        super(properties);
        this.species = species;
        // Never actually round-tripped through a datapack — nothing in this mod constructs a
        // sapling from a codec — but VegetationBlock declares codec() abstract, so a working one
        // is needed regardless. Captures species so a reconstructed instance is still correct.
        this.codec = simpleCodec(props -> new BetterSaplingBlock(props, species));
    }

    @Override
    protected MapCodec<? extends VegetationBlock> codec() {
        return codec;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Same 1-in-7 odds and light gate as vanilla saplings (SaplingBlock#randomTick) — a sapling
        // in a cave or under a solid roof shouldn't grow. No STAGE charge-up though: growth resolves
        // in one tick once triggered.
        if (level.getMaxLocalRawBrightness(pos.above()) < 9 || random.nextInt(7) != 0) {
            return;
        }
        // Plan §8.1: only bone meal grows a cluster — random ticks are suppressed for any sapling
        // whose cluster size is 2 or more (an oversized/aborted cluster counts as "2 or more" here
        // too, since it's still visibly clustered even though it can't grow as one big tree).
        Set<BlockPos> cluster = SaplingCluster.detect(level, pos, this);
        if (cluster == null || cluster.size() >= 2) {
            return;
        }
        growSingle(level, pos, random, profileFor(species));
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        TreeProfile profile = profileFor(species);
        Set<BlockPos> cluster = SaplingCluster.detect(level, pos, this);
        if (cluster == null) {
            return; // plan §8.1 abort case (>16x16 bbox or >256 cells) — refuse, consume nothing structurally
        }
        if (cluster.size() <= 1) {
            growSingle(level, pos, random, profile);
        } else {
            growCluster(level, pos, cluster, random, profile);
        }
    }

    /**
     * Plan §7 steps 2-4: seed a {@code D16} tip at the sapling, run the pure generator, then hand
     * the result to {@link BlueprintPlacer}. A refusal (too obstructed, or a cancelled {@code
     * BetterTreeGrowEvent}) leaves the sapling exactly as it was — {@link BlueprintPlacer#place}
     * never writes or queues a block until both checks pass, so "consume nothing" falls out for
     * free rather than needing an explicit rollback.
     */
    private void growSingle(ServerLevel level, BlockPos pos, RandomSource random, TreeProfile profile) {
        LevelOccupancyView occupancy = new LevelOccupancyView(level, pos, profile.occupancy().breakReplaceable());

        Skeleton skeleton = SkeletonGenerator.generate(random.nextLong(), profile, Diameter.D16, occupancy);
        TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(skeleton);
        Map<Vec3i, Integer> leafDistances = LeafPlanner.planLeaves(skeleton, blueprint, profile);
        Map<Vec3i, Placement> placements = PlacementResolver.resolve(blueprint, leafDistances, skeleton.seed(), profile.foliage().leafyBranchChance());

        BlueprintPlacer.place(level, pos, species, skeleton, placements, profile.maxObstructionRatio(), profile.occupancy().breakReplaceable());
    }

    /**
     * Plan §8 — {@code origin} (the bonemealed sapling) is local {@code (0, 0)}; every cluster
     * member's footprint cell is relative to it, so {@code BlueprintPlacer} can place from {@code
     * origin} the same way {@link #growSingle} does. {@link BigTreeGrower} owns the pure
     * footprint-solve/multi-tip-generate/merge pipeline (plan §8.3) — this method is just the
     * world-layer boundary around it: build the footprint from real sapling positions, then hand
     * the result to {@code LeafPlanner}/{@code PlacementResolver}/{@code BlueprintPlacer} exactly
     * like the single-tree path.
     */
    private void growCluster(ServerLevel level, BlockPos origin, Set<BlockPos> cluster, RandomSource random, TreeProfile profile) {
        Set<Cell> footprint = new HashSet<>();
        for (BlockPos p : cluster) {
            footprint.add(new Cell(p.getX() - origin.getX(), p.getZ() - origin.getZ()));
        }

        long seed = random.nextLong();
        LevelOccupancyView occupancy = new LevelOccupancyView(level, origin, profile.occupancy().breakReplaceable());
        BigTreeGrower.Result result = BigTreeGrower.grow(footprint, profile, seed, occupancy);

        Map<Vec3i, Integer> leafDistances = LeafPlanner.planLeaves(result.skeleton(), result.blueprint(), profile,
                result.trunk().centroidX(), result.trunk().centroidZ(), result.trunk().canopyRadius());
        Map<Vec3i, Placement> placements = PlacementResolver.resolve(result.blueprint(), leafDistances,
                result.skeleton().seed(), profile.foliage().leafyBranchChance());

        BlueprintPlacer.place(level, origin, species, result.skeleton(), placements, profile.maxObstructionRatio(), profile.occupancy().breakReplaceable());
    }

    /** Hardcoded until Milestone 11's JSON archetype/species system (plan §4) replaces this outright. */
    private static TreeProfile profileFor(String species) {
        return "spruce".equals(species) ? TreeProfile.fir() : TreeProfile.oak();
    }
}
