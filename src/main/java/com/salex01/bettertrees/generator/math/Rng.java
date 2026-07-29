package com.salex01.bettertrees.generator.math;

import java.util.Random;

/**
 * Deterministic per-node random stream (plan §16 test 6, and the determinism note ahead of §19's
 * monotonicity requirement). Each node gets its own {@link Rng} derived from
 * {@code (treeSeed, nodeIndex)} via a splitmix64-style mix, rather than every node drawing from
 * one shared stream — so a node's own sequence of draws never depends on traversal order, how
 * many siblings were processed first, or anything else outside {@code (treeSeed, nodeIndex)}
 * itself. {@link java.util.Random}'s own algorithm is specified by the JDK and stable across
 * platforms, so it's safe to wrap directly once seeded this way.
 */
public final class Rng {
    private final Random random;

    private Rng(long seed) {
        this.random = new Random(seed);
    }

    public static Rng forNode(long treeSeed, int nodeIndex) {
        return new Rng(mix(treeSeed, nodeIndex));
    }

    /** Derives an independent child stream for a sub-purpose of one node (e.g. retry N of a resample). */
    public Rng fork(int salt) {
        return new Rng(mix(random.nextLong(), salt));
    }

    private static long mix(long seed, int index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public float nextFloat() {
        return random.nextFloat();
    }

    public double nextGaussian() {
        return random.nextGaussian();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
