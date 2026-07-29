package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 test 13 — no two voxels from different branch chains share a position unless both lie
 * within {@code fuse_radius} of their nearest common ancestor, run against a mock
 * {@link OccupancyView} that also asserts no voxel lands on a SOLID position.
 *
 * <p>The root-plate clause ("no branch voxel lands inside the root plate") is deferred — it needs
 * {@code RootGenerator}, which is plan §10 and not part of this milestone's deliverable.
 */
class SelfIntersectionTest {
    private static final float EPS = 1e-3f;

    /**
     * Re-derives voxel ownership independently from the committed segment list (rather than trusting
     * the generator's own runtime bookkeeping) and checks every overlap against the same fuse-radius
     * rule the generator's hard veto enforces — so a bug in that veto's logic would still be caught
     * here, on the output, not just exercised.
     */
    @Test
    void overlappingVoxelsAreAlwaysWithinFuseRadiusOfACommonAncestor() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                float fuseRadius = profile.occupancy().fuseRadius();

                Map<Vec3i, List<Integer>> owners = new HashMap<>();
                for (Segment seg : sk.segments()) {
                    for (Vec3i v : Voxelizer.rasterize(seg.a(), seg.b())) {
                        owners.computeIfAbsent(v, k -> new ArrayList<>()).add(seg.index());
                    }
                }

                for (Map.Entry<Vec3i, List<Integer>> entry : owners.entrySet()) {
                    List<Integer> owningSegments = entry.getValue();
                    if (owningSegments.size() < 2) {
                        continue;
                    }
                    Vec3i voxel = entry.getKey();
                    Vec3d voxelCenter = new Vec3d(voxel.x() + 0.5, voxel.y() + 0.5, voxel.z() + 0.5);

                    for (int i = 0; i < owningSegments.size(); i++) {
                        for (int j = i + 1; j < owningSegments.size(); j++) {
                            int a = owningSegments.get(i);
                            int b = owningSegments.get(j);
                            int ancestor = Skeleton.nearestCommonAncestor(a, b, sk.segments());
                            assertTrue(ancestor != -1, "seed=" + seed + " segments " + a + "/" + b + " share no ancestor in the same tree");

                            Vec3d ancestorPos = sk.segment(ancestor).b();
                            double dist = voxelCenter.distance(ancestorPos);
                            assertTrue(dist <= fuseRadius + EPS,
                                    "seed=" + seed + " voxel=" + voxel + " segments=" + a + "," + b
                                            + " distance-to-ancestor=" + dist + " fuseRadius=" + fuseRadius);
                        }
                    }
                }
            }
        }
    }

    /** A mock view with a SOLID half-space above y=10 — growth must never place a voxel there. */
    @Test
    void generatedTreeNeverOccupiesASolidVoxel() {
        OccupancyView solidAboveTen = pos -> pos.y() > 10 ? OccupancyView.Owner.solid() : OccupancyView.Owner.free();

        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, solidAboveTen);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
                for (Vec3i pos : blueprint.positions()) {
                    assertTrue(pos.y() <= 10, "seed=" + seed + " voxel=" + pos + " lands in the SOLID region");
                }
            }
        }
    }
}
