package com.salex01.bettertrees.world;

import com.salex01.bettertrees.BetterTrees;
import com.salex01.bettertrees.event.BetterTreeGrowEvent;
import com.salex01.bettertrees.generator.OccupancyView;
import com.salex01.bettertrees.generator.Placement;
import com.salex01.bettertrees.generator.PlacementResolver;
import com.salex01.bettertrees.generator.VoxelOwnership;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerLevel;

/**
 * Turns a resolved {@code Map<Vec3i, Placement>} (plan §2, §13) into a queued {@link GrowJob} that
 * {@link GrowJobManager} places over subsequent ticks. The only place outside {@link
 * LevelOccupancyView}/{@link GrowJobManager} that reads {@code Level} state for growth —
 * everything upstream of this is pure {@code generator/}.
 *
 * <p>Milestone 9: placement is no longer synchronous — this class now only does the
 * refuse-or-accept decision (obstruction ratio, then a cancellable {@link BetterTreeGrowEvent})
 * and, once accepted, computes the final placement order and enqueues it. Not a single block is
 * written here; {@code GrowJobManager} owns every {@code setBlock} call for growth from this
 * milestone on. {@code place} still returns {@code true}/{@code false} for "accepted" vs.
 * "refused, consumes nothing" — callers that used to mean "placed" by that return value now get
 * "queued to place," which is what "a 16x16 tree grows with no TPS spike" (§17) actually asks for.
 */
public final class BlueprintPlacer {
    private static final boolean[] NO_CONNECTIONS = new boolean[6];

    private BlueprintPlacer() {}

    /**
     * @return {@code true} if the tree was accepted and queued, {@code false} if refused (consumes
     *         nothing) because more than {@code maxObstructionRatio} of the blueprint would land on
     *         non-replaceable blocks, or because {@link BetterTreeGrowEvent} was cancelled.
     */
    public static boolean place(ServerLevel level, BlockPos origin, String species, Skeleton skeleton, Map<Vec3i, Placement> placements,
            float maxObstructionRatio, boolean breakReplaceable) {
        BetterTrees.BranchSet set = BetterTrees.BRANCHES.get(species);
        if (set == null) {
            throw new IllegalArgumentException("no BranchSet registered for species " + species);
        }
        GrowJobManager manager = GrowJobManager.get(level);
        // The sapling block itself isn't overwritten until this job's first voxel actually places,
        // several ticks from now rather than within this same call — it stays present, still
        // random-tickable and still bonemealable, in the meantime. Without this guard a second
        // randomTick roll or a second bonemeal application on the same still-a-sapling block would
        // queue a duplicate job at the same origin.
        if (manager.hasJobAt(origin)) {
            return false;
        }
        // Same classification the generator itself used to decide passability (Free/Replaceable
        // pass, OtherTree/Solid block) — one definition of "obstructed", shared with generation
        // rather than re-derived here, so the two can't silently drift apart.
        LevelOccupancyView view = new LevelOccupancyView(level, origin, breakReplaceable);
        // Which segment(s) own each voxel — connection booleans need this to tell "this neighbour
        // is my parent/child" from "this neighbour just happens to be touching" (see PlacementResolver).
        Map<Vec3i, List<Integer>> ownership = VoxelOwnership.of(skeleton);
        List<Segment> segments = skeleton.segments();

        int obstructed = 0;
        for (Vec3i local : placements.keySet()) {
            if (isObstructed(view, local)) {
                obstructed++;
            }
        }
        if (placements.isEmpty() || (float) obstructed / placements.size() > maxObstructionRatio) {
            return false;
        }

        List<Map.Entry<Vec3i, Placement>> ordered = new ArrayList<>(placements.entrySet());
        // Wood before leaves: leaves reading their neighbours for decay support (M6) only make
        // sense once the wood they'd attach to already exists in the world.
        ordered.sort(Comparator
                .<Map.Entry<Vec3i, Placement>>comparingInt(e -> e.getValue().kind() == Placement.Kind.LEAF ? 1 : 0)
                .thenComparingInt(e -> e.getKey().y()));

        List<QueuedVoxel> queued = new ArrayList<>(ordered.size());
        for (Map.Entry<Vec3i, Placement> entry : ordered) {
            Vec3i local = entry.getKey();
            Placement placement = entry.getValue();
            // Wood is queued unconditionally once the tree as a whole is accepted: the blueprint is
            // one 6-connected component by construction (test 1), and skipping an obstructed voxel
            // in the middle of a chain would disconnect everything above it — the exact
            // floating-branch failure this milestone's acceptance check names. Only LEAF voxels
            // (never part of that connexity guarantee) are skipped individually, so growth doesn't
            // overwrite a player's build with a stray leaf.
            if (placement.kind() == Placement.Kind.LEAF && isObstructed(view, local)) {
                continue;
            }
            boolean[] connections = placement.kind() == Placement.Kind.LEAF
                    ? NO_CONNECTIONS
                    : PlacementResolver.woodConnections(local, placements, ownership, segments);
            queued.add(QueuedVoxel.of(local, placement, connections));
        }

        BetterTreeGrowEvent event = NeoForge.EVENT_BUS.post(new BetterTreeGrowEvent(level, origin, species));
        if (event.isCanceled()) {
            return false;
        }

        manager.enqueue(GrowJob.start(origin, species, queued));
        return true;
    }

    private static boolean isObstructed(LevelOccupancyView view, Vec3i local) {
        OccupancyView.Owner owner = view.at(local);
        return owner instanceof OccupancyView.Owner.Solid || owner instanceof OccupancyView.Owner.OtherTree;
    }
}
