package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.trunk.Cell;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composed big-tree pipeline (§8.3: {@code TrunkFootprintSolver} -> multi-tip {@code
 * SkeletonGenerator} -> {@code TreeBlueprint.merge}) — exercised nowhere else. Every other new
 * class this milestone is tested in isolation, but composition is exactly where a coordinate-frame
 * or origin-convention bug would live, and it's what plan test 1 ("catches every levitating-branch
 * bug at once") is actually for.
 */
class BigTreeGrowerTest {
    private static final Vec3i ORIGIN = new Vec3i(0, 0, 0);
    private static final List<TreeProfile> PROFILES = List.of(TreeProfile.fir(), TreeProfile.oak());
    // seed=1 (with ringWithCavity) and seed=6 (with fourByFour) are both confirmed to trigger a
    // limb_split for every profile here — determinism (test 6) needs to cover that code path too,
    // not just the common no-split case, since it adds its own RNG draws and HashSet-derived
    // orderings (component/boundary sorting) that a purely lucky no-split run would never exercise.
    private static final long[] SEEDS = {1L, 2L, 6L, 12345L};

    private static Set<Cell> fourByFour() {
        Set<Cell> cells = new HashSet<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    /** A 9x9 ring with its 5x5 center removed — the shape where cavity fill/reopen actually toggles and DT is uniformly shallow, so it's the one most likely to expose a merge-time connexity bug the per-layer {@code LayerRepair} invariant wouldn't catch on its own. */
    private static Set<Cell> ringWithCavity() {
        Set<Cell> outer = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                outer.add(new Cell(x, z));
            }
        }
        for (int x = 2; x < 7; x++) {
            for (int z = 2; z < 7; z++) {
                outer.remove(new Cell(x, z));
            }
        }
        return outer;
    }

    private static List<Set<Cell>> shapes() {
        return List.of(fourByFour(), ringWithCavity());
    }

    /** Test 1's invariant, over the merged big-tree blueprint instead of a single sapling's — the origin cell is always the bonemealed sapling, and {@code layer(0)} always contains it since {@code tau(0) = 0}. */
    @Test
    void mergedBlueprintIsOneConnectedComponentContainingOrigin() {
        for (Set<Cell> shape : shapes()) {
            for (TreeProfile profile : PROFILES) {
                for (long seed : SEEDS) {
                    BigTreeGrower.Result result = BigTreeGrower.grow(shape, profile, seed, OccupancyView.ALWAYS_FREE);
                    TreeBlueprint blueprint = result.blueprint();

                    assertTrue(blueprint.contains(ORIGIN), "seed=" + seed + " origin (the bonemealed sapling) must be part of the trunk stack's base layer");

                    Set<Vec3i> visited = floodFillFrom(ORIGIN, blueprint);
                    assertEquals(blueprint.size(), visited.size(),
                            "seed=" + seed + " " + blueprint.size() + " voxels but only " + visited.size() + " reachable from origin");
                }
            }
        }
    }

    /** Test 6 for the multi-tip path — same seed and profile must reproduce a byte-identical merged blueprint. */
    @Test
    void sameSeedGivesIdenticalMergedBlueprint() {
        for (Set<Cell> shape : shapes()) {
            for (TreeProfile profile : PROFILES) {
                for (long seed : SEEDS) {
                    BigTreeGrower.Result a = BigTreeGrower.grow(shape, profile, seed, OccupancyView.ALWAYS_FREE);
                    BigTreeGrower.Result b = BigTreeGrower.grow(shape, profile, seed, OccupancyView.ALWAYS_FREE);
                    assertEquals(a.blueprint().positions(), b.blueprint().positions(), "seed=" + seed + " voxel set must match");
                    for (Vec3i pos : a.blueprint().positions()) {
                        assertEquals(a.blueprint().diameterAt(pos), b.blueprint().diameterAt(pos), "seed=" + seed + " pos=" + pos);
                    }
                }
            }
        }
    }

    /** Every trunk-stack voxel must survive into the merged blueprint (as at least D16 — a crown segment sharing that cell can only thicken it, per max-diameter-wins). */
    @Test
    void everyTrunkVoxelSurvivesTheMerge() {
        for (Set<Cell> shape : shapes()) {
            BigTreeGrower.Result result = BigTreeGrower.grow(shape, TreeProfile.oak(), 7L, OccupancyView.ALWAYS_FREE);
            for (Vec3i trunkVoxel : result.trunk().trunkVoxels().keySet()) {
                assertTrue(result.blueprint().contains(trunkVoxel), "trunk voxel " + trunkVoxel + " missing from merged blueprint");
            }
        }
    }

    private static Set<Vec3i> floodFillFrom(Vec3i start, TreeBlueprint blueprint) {
        Set<Vec3i> visited = new HashSet<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Vec3i current = queue.poll();
            for (Vec3i neighbor : current.faceNeighbors()) {
                if (blueprint.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }
}
