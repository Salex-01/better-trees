package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 test 9 — "remove any single voxel, run {@link SupportSolver} over the blueprint, and the
 * set it marks floating must equal the set with no ground path. Property-test over seeds."
 *
 * <p>"Ground" has no literal representation in a pure {@link TreeBlueprint} (no anchor/dirt block
 * exists in {@code generator/}'s coordinate space at all) — the natural stand-in is {@code y == 0},
 * the layer every blueprint's trunk base actually occupies by construction (test 1's own origin-
 * containment invariant, and generation only ever grows upward from there — {@code "roots": {"mode":
 * "none"}} in every shipped profile per plan §15, so no voxel exists below it). This is deliberately
 * WOOD-only, matching what {@link TreeBlueprint} itself models — leaves are a separate, non-structural
 * map ({@code LeafPlanner}'s output) not exercised by this test; the live {@code
 * #bettertrees:tree_parts} tag does include leaves too, but that's a world-layer-only nuance.
 */
class SupportSolverTest {
    /** Independent seed sample, deliberately smaller than {@link GeneratorTestSupport#seeds()} — "remove every voxel of every blueprint" is quadratic in blueprint size, so this test bounds cost with a random voxel sample per tree instead (see {@link #removedVoxelSample}). */
    private static final int SEED_COUNT = 12;
    private static final int VOXELS_PER_TREE = 40;
    private static final int MAX_VISITS = 1_000_000; // effectively unbounded for these tree sizes — the fail-safe itself is covered separately below

    @Test
    void floatingSetExactlyMatchesNoGroundPathAfterRemovingAnySingleVoxel() {
        for (long seed : seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);

                for (Vec3i removed : removedVoxelSample(blueprint, seed)) {
                    Set<Vec3i> remaining = new HashSet<>(blueprint.positions());
                    remaining.remove(removed);

                    Set<Vec3i> expectedFloating = noGroundPathSet(remaining);

                    List<Vec3i> seedPositions = new ArrayList<>();
                    for (Vec3i n : removed.faceNeighbors()) {
                        if (remaining.contains(n)) {
                            seedPositions.add(n);
                        }
                    }

                    SupportSolver.Result result = SupportSolver.solve(
                            remaining::contains, seedPositions, pos -> pos.y() == 0, MAX_VISITS);

                    assertFalse(result.aborted(), "seed=" + seed + " removed=" + removed + " unexpectedly hit the fail-safe");
                    assertEquals(expectedFloating, result.floating(),
                            "seed=" + seed + " removed=" + removed + " floating set diverges from ground-path reachability");
                }
            }
        }
    }

    /**
     * A hand-built shape where the grounded side is far larger than the floating twig, verifying
     * plan §12.2's central performance claim directly rather than just its end result: the twig's
     * fragment shows up in {@link SupportSolver.Scan#floating()} using only a handful of visits, well
     * before the whole scan finishes (long before the grounded side could possibly have proven itself
     * connected to y=0), which is the mechanism behind "cutting a twig is instant" even off a giant.
     */
    @Test
    void smallFloatingFragmentResolvesLongBeforeTheGroundedSideFinishes() {
        // A 200-tall vertical column resting on y=0 (grounded, huge), plus a 3-voxel twig hanging off
        // a point far from the ground that was cut loose from everything else.
        Set<Vec3i> shape = new HashSet<>();
        for (int y = 0; y < 200; y++) {
            shape.add(new Vec3i(0, y, 0));
        }
        Vec3i twigBase = new Vec3i(5, 50, 5);
        Vec3i twigMid = new Vec3i(5, 51, 5);
        Vec3i twigTip = new Vec3i(5, 52, 5);
        shape.add(twigBase);
        shape.add(twigMid);
        shape.add(twigTip);
        // twigBase is deliberately NOT connected to the column — it was already severed from the
        // trunk by an earlier cut this test doesn't model; only the trunk's own seed and the twig's
        // own seed are passed in, exactly like a real removal only ever seeds its own two sides.

        List<Vec3i> seeds = List.of(new Vec3i(0, 100, 0), twigBase);
        SupportSolver.Scan scan = SupportSolver.start(shape::contains, seeds, pos -> pos.y() == 0, 100_000);

        // Budget generous enough for the 3-voxel twig to fully exhaust, but nowhere near enough for
        // the 200-tall column's frontier to reach y=0 from y=100 in one round-robin pass together
        // with a second frontier (that walk alone needs on the order of 100 visits reaching down,
        // hundreds of the shared budget away from finishing given round-robin alternation is far
        // slower than that alone would suggest with two frontiers active).
        boolean done = scan.step(10);

        assertTrue(scan.floating().contains(twigMid), "twig fragment should already be confirmed floating");
        assertTrue(scan.floating().contains(twigBase));
        assertTrue(scan.floating().contains(twigTip));
        assertEquals(3, scan.floating().size(), "only the twig's 3 voxels should be confirmed floating this early");
        assertFalse(done, "the column-side frontier should still be unresolved after only 10 visits");
    }

    /** Plan §12.2's fail-safe: exceeding {@code max_collapse_scan} aborts rather than ever lag the server. */
    @Test
    void abortsAfterMaxVisitsOnAPathologicallyLargeFloatingShape() {
        Set<Vec3i> shape = new HashSet<>();
        for (int x = 0; x < 200; x++) {
            shape.add(new Vec3i(x, 5, 0)); // floating: nothing in this shape ever touches y == 0
        }
        SupportSolver.Result result = SupportSolver.solve(shape::contains, List.of(new Vec3i(0, 5, 0)), pos -> pos.y() == 0, 32);

        assertTrue(result.aborted());
        assertTrue(result.visited() > 32, "abort should trip once visits exceed the cap, not stop exactly at it");
    }

    private static long[] seeds() {
        long[] seeds = new long[SEED_COUNT];
        for (int i = 0; i < SEED_COUNT; i++) {
            seeds[i] = i * 104729L + 17;
        }
        return seeds;
    }

    private static List<Vec3i> removedVoxelSample(TreeBlueprint blueprint, long seed) {
        List<Vec3i> all = new ArrayList<>(blueprint.positions());
        // Never remove the origin itself — every blueprint contains it (test 1), and removing the
        // one guaranteed y==0 voxel of a single-column base can legitimately float the *entire* rest
        // of the tree, which is a valid but uninteresting case this sampler doesn't need to chase;
        // plenty of other y==0 voxels (when present) and every non-base voxel are still fair game.
        Random random = new Random(seed);
        java.util.Collections.shuffle(all, random);
        return all.subList(0, Math.min(VOXELS_PER_TREE, all.size()));
    }

    /** Independent ground truth: flood fill from every remaining {@code y == 0} voxel; anything unreached has no ground path. */
    private static Set<Vec3i> noGroundPathSet(Set<Vec3i> remaining) {
        Set<Vec3i> reached = new HashSet<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        for (Vec3i pos : remaining) {
            if (pos.y() == 0) {
                reached.add(pos);
                queue.add(pos);
            }
        }
        while (!queue.isEmpty()) {
            Vec3i current = queue.poll();
            for (Vec3i neighbor : current.faceNeighbors()) {
                if (remaining.contains(neighbor) && reached.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        Set<Vec3i> noGroundPath = new HashSet<>(remaining);
        noGroundPath.removeAll(reached);
        return noGroundPath;
    }
}
