package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.List;

/**
 * Shared fixtures for property-style generator tests (plan §16). Two structurally different
 * profiles (fir: high apical dominance, whorled; oak: low dominance, spiral) deliberately, per
 * review — a single profile hides parameter-coupling bugs a second one surfaces.
 *
 * <p>Seed count starts small for fast iteration; raise once everything is green and wall time is
 * checked (plan's target is 10 000 seeds per profile).
 */
public final class GeneratorTestSupport {
    private GeneratorTestSupport() {}

    public static final List<TreeProfile> PROFILES = List.of(TreeProfile.fir(), TreeProfile.oak());

    public static final int SEED_COUNT = 2000;

    public static long[] seeds() {
        long[] seeds = new long[SEED_COUNT];
        for (int i = 0; i < SEED_COUNT; i++) {
            seeds[i] = i * 104729L + 17;
        }
        return seeds;
    }
}
