package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.LeafAnchor;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 test 5 — no foliage anchored to D12/D16; every leaf within vanilla's graph-distance
 * decay range of a branch.
 */
class LeafRuleTest {
    /** A trunk stub every fixture skeleton anchors off of, so the anchor has real wood to attach to (an anchor with no wood nearby has no decay support and legitimately produces nothing, per real vanilla mechanics). */
    private static Segment trunkStub() {
        return new Segment(0, -1, new Vec3d(0, 0, 0), new Vec3d(0, 2, 0), Diameter.D16, false, 0f, new Vec3d(0, 0, 0), 2f);
    }

    /** A lone D12 anchor must produce zero leaves — the D12/D16 exclusion, isolated from everything else LeafPlanner does. */
    @Test
    void d12AnchorAloneProducesNoLeaves() {
        Skeleton sk = new Skeleton(0L, List.of(trunkStub()), List.of(new LeafAnchor(new Vec3d(0, 2, 0), Diameter.D12)));
        TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
        assertTrue(LeafPlanner.planLeaves(sk, blueprint, TreeProfile.oak()).isEmpty());
    }

    @Test
    void d16AnchorAloneProducesNoLeaves() {
        Skeleton sk = new Skeleton(0L, List.of(trunkStub()), List.of(new LeafAnchor(new Vec3d(0, 2, 0), Diameter.D16)));
        TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
        assertTrue(LeafPlanner.planLeaves(sk, blueprint, TreeProfile.oak()).isEmpty());
    }

    /** The same anchor at D8 or D4 must produce at least one leaf — proves the D12/D16 case above is the exclusion doing something, not an empty planner. */
    @Test
    void d8AndD4AnchorsProduceLeaves() {
        for (Diameter d : List.of(Diameter.D8, Diameter.D4)) {
            Skeleton sk = new Skeleton(0L, List.of(trunkStub()), List.of(new LeafAnchor(new Vec3d(0, 2, 0), d)));
            TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
            assertFalse(LeafPlanner.planLeaves(sk, blueprint, TreeProfile.oak()).isEmpty(), "diameter=" + d);
        }
    }

    /**
     * Property check over generated trees: every DISTANCE value {@link LeafPlanner} assigns is
     * within vanilla's [1, 7] range, and matches an independently re-derived BFS graph distance
     * from the blueprint's own wood voxels through the same leaf set — not trusting the planner's
     * internal bookkeeping, the same principle {@code SelfIntersectionTest} uses for voxel ownership.
     */
    @Test
    void everyLeafDistanceMatchesAnIndependentGraphBfs() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
                Map<Vec3i, Integer> leaves = LeafPlanner.planLeaves(sk, blueprint, profile);

                Map<Vec3i, Integer> independent = independentBfs(blueprint, leaves.keySet());
                for (Map.Entry<Vec3i, Integer> entry : leaves.entrySet()) {
                    int distance = entry.getValue();
                    assertTrue(distance >= 1 && distance <= 7,
                            "seed=" + seed + " leaf=" + entry.getKey() + " distance=" + distance + " out of vanilla's [1,7] range");
                    assertEquals(independent.get(entry.getKey()), distance,
                            "seed=" + seed + " leaf=" + entry.getKey() + " planner distance disagrees with an independently re-derived BFS");
                }
            }
        }
    }

    /** Re-derives graph distance from scratch via a plain BFS, independent of {@code LeafPlanner.bfsDistances}'s own implementation. */
    private static Map<Vec3i, Integer> independentBfs(TreeBlueprint blueprint, Set<Vec3i> leafPositions) {
        Map<Vec3i, Integer> distances = new java.util.HashMap<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        Set<Vec3i> leafSet = new HashSet<>(leafPositions);

        for (Vec3i leaf : leafSet) {
            boolean adjacentToWood = false;
            for (Vec3i neighbor : leaf.faceNeighbors()) {
                if (blueprint.contains(neighbor)) {
                    adjacentToWood = true;
                    break;
                }
            }
            if (adjacentToWood) {
                distances.put(leaf, 1);
                queue.add(leaf);
            }
        }
        while (!queue.isEmpty()) {
            Vec3i current = queue.poll();
            int d = distances.get(current);
            if (d >= 7) {
                continue;
            }
            for (Vec3i neighbor : current.faceNeighbors()) {
                if (!leafSet.contains(neighbor) || distances.containsKey(neighbor)) {
                    continue;
                }
                distances.put(neighbor, d + 1);
                queue.add(neighbor);
            }
        }
        return distances;
    }
}
