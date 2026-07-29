package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.GeneratorTestSupport;
import com.salex01.bettertrees.generator.OccupancyView;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 tests 2 (parent rule), 3 (run rule), 4 (twist rule) — skeleton-only, no voxels, run
 * against an always-FREE {@link OccupancyView} so occupancy plumbing (a later milestone step)
 * can't mask a bug in the growth rules themselves.
 */
class GrowthRulesTest {
    private static final float EPS = 1e-3f;

    /** Test 2 — every single-parent segment's diameter is legal under its profile's rule from its parent's diameter. */
    @Test
    void parentDiameterIsAlwaysLegal() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                for (Segment seg : sk.segments()) {
                    if (seg.parentIndex() == -1) {
                        continue;
                    }
                    Segment parent = sk.segment(seg.parentIndex());
                    assertTrue(profile.rule().legal(parent.diameter(), seg.diameter()),
                            "seed=" + seed + " parent=" + parent.diameter() + " child=" + seg.diameter());
                }
            }
        }
    }

    /**
     * Test 3 — no chain holds a diameter longer than {@code maxRun[d]}. Rule B's own check (plan
     * §5.3) fires using the run length going INTO a segment, so a single segment may carry a run
     * past the cap on the step where it's crossed — the invariant is that the run length before
     * *that* segment was still under the cap, i.e. no two consecutive segments both overshoot.
     */
    @Test
    void noChainExceedsMaxRun() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                for (Segment seg : sk.segments()) {
                    int maxRun = profile.runRange(seg.diameter()).max();
                    float priorRunLength = seg.runLengthAfter() - (float) seg.length();
                    assertTrue(priorRunLength < maxRun + EPS,
                            "seed=" + seed + " diameter=" + seg.diameter() + " priorRunLength=" + priorRunLength + " maxRun=" + maxRun);
                }
            }
        }
    }

    /**
     * Test 4 — for every twist above the hard threshold, either the diameter stepped down or the
     * tip was still within its diameter's twist window of {@code runRoot}. {@code firstOfBranch}
     * segments are exempt (plan §5.4 — a branch angle, not a twist).
     */
    @Test
    void twistAboveThresholdIsAlwaysExempt() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                for (Segment seg : sk.segments()) {
                    if (seg.firstOfBranch()) {
                        continue;
                    }
                    if (seg.twistDegrees() <= profile.hardTwistThresholdDegrees() + EPS) {
                        continue;
                    }
                    boolean steppedDown = seg.parentIndex() == -1
                            || seg.diameter() != sk.segment(seg.parentIndex()).diameter();
                    boolean withinWindow = seg.a().distance(seg.runRoot()) <= profile.twistWindow(seg.diameter()) + EPS;
                    assertTrue(steppedDown || withinWindow,
                            "seed=" + seed + " twist=" + seg.twistDegrees() + " steppedDown=" + steppedDown
                                    + " dist=" + seg.a().distance(seg.runRoot()) + " window=" + profile.twistWindow(seg.diameter()));
                }
            }
        }
    }
}
