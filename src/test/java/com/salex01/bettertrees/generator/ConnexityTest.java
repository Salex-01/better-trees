package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 test 1 — the blueprint's voxel set is a single 6-connected component containing the
 * origin. Catches every levitating-branch bug at once; relies on {@link Voxelizer}'s
 * Amanatides–Woo walk actually being 6-connected by construction rather than assuming it.
 */
class ConnexityTest {
    private static final Vec3i ORIGIN = new Vec3i(0, 0, 0);

    @Test
    void blueprintIsOneComponentContainingOrigin() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);

                assertTrue(blueprint.contains(ORIGIN), "seed=" + seed + " origin missing from blueprint");

                Set<Vec3i> visited = floodFillFrom(ORIGIN, blueprint);
                assertEquals(blueprint.size(), visited.size(),
                        "seed=" + seed + " blueprint has " + blueprint.size() + " voxels but only " + visited.size()
                                + " are reachable from the origin — a disconnected (levitating) part exists");
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
