package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.GeneratorTestSupport;
import com.salex01.bettertrees.generator.LeafPlanner;
import com.salex01.bettertrees.generator.OccupancyView;
import com.salex01.bettertrees.generator.TreeBlueprint;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plan §16 test 6 — same seed plus profile gives a byte-identical skeleton. Load-bearing for §14.2. */
class DeterminismTest {
    @Test
    void sameSeedAndProfileGiveIdenticalSkeleton() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton a = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                Skeleton b = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);

                assertEquals(a.segments().size(), b.segments().size(), "seed=" + seed);
                for (int i = 0; i < a.segments().size(); i++) {
                    assertEquals(a.segment(i), b.segment(i), "seed=" + seed + " segment=" + i);
                }
                assertEquals(a.leafAnchors(), b.leafAnchors(), "seed=" + seed);
            }
        }
    }

    /**
     * Test 6 as originally written only compared segments/anchors — {@code LeafPlanner.planLeaves}
     * (Milestone 6) adds its own noise sampling on top, which is a second, independent place
     * nondeterminism could sneak in (e.g. keying noise off a mutable iteration order instead of
     * {@code skeleton.seed()} + position). §14.2's "each piece regenerates the full skeleton
     * deterministically" is only actually true end-to-end if the leaf plan is included too.
     */
    @Test
    void sameSeedAndProfileGiveIdenticalLeafPlan() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton skA = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprintA = TreeBlueprint.fromSkeleton(skA);
                Map<Vec3i, Integer> leavesA = LeafPlanner.planLeaves(skA, blueprintA, profile);

                Skeleton skB = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprintB = TreeBlueprint.fromSkeleton(skB);
                Map<Vec3i, Integer> leavesB = LeafPlanner.planLeaves(skB, blueprintB, profile);

                assertEquals(leavesA, leavesB, "seed=" + seed);
            }
        }
    }
}
