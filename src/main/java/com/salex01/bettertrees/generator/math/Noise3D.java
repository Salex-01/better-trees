package com.salex01.bettertrees.generator.math;

/**
 * Deterministic 3D value noise (plan §9 — leaf-blob perturbation). Value noise, not
 * gradient/Perlin noise: a pseudo-random value at each integer lattice point, smoothstep-faded
 * and trilinearly interpolated between them. Nothing in §9 asks for gradient continuity, and
 * value noise is simpler to keep pure and seed-deterministic.
 */
public final class Noise3D {
    private Noise3D() {}

    /** Roughly in [-1, 1] at continuous point {@code (x, y, z)}, keyed by {@code seed}. */
    public static float sample(long seed, double x, double y, double z) {
        int x0 = (int) StrictMath.floor(x);
        int y0 = (int) StrictMath.floor(y);
        int z0 = (int) StrictMath.floor(z);
        double fx = fade(x - x0);
        double fy = fade(y - y0);
        double fz = fade(z - z0);

        double c000 = latticeSigned(seed, x0, y0, z0);
        double c100 = latticeSigned(seed, x0 + 1, y0, z0);
        double c010 = latticeSigned(seed, x0, y0 + 1, z0);
        double c110 = latticeSigned(seed, x0 + 1, y0 + 1, z0);
        double c001 = latticeSigned(seed, x0, y0, z0 + 1);
        double c101 = latticeSigned(seed, x0 + 1, y0, z0 + 1);
        double c011 = latticeSigned(seed, x0, y0 + 1, z0 + 1);
        double c111 = latticeSigned(seed, x0 + 1, y0 + 1, z0 + 1);

        double x00 = lerp(c000, c100, fx);
        double x10 = lerp(c010, c110, fx);
        double x01 = lerp(c001, c101, fx);
        double x11 = lerp(c011, c111, fx);

        double y0v = lerp(x00, x10, fy);
        double y1v = lerp(x01, x11, fy);

        return (float) lerp(y0v, y1v, fz);
    }

    /** Deterministic pseudo-random value in [0, 1) for one integer point — also usable standalone for a per-voxel weighted coin flip (e.g. {@code leafy_branch_chance}). */
    public static float hash01(long seed, int x, int y, int z) {
        return (float) latticeUnit(seed, x, y, z);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double latticeSigned(long seed, int x, int y, int z) {
        return latticeUnit(seed, x, y, z) * 2.0 - 1.0;
    }

    /** [0, 1) — top 53 bits of the mixed hash, same convention {@link java.util.Random#nextDouble()} uses. */
    private static double latticeUnit(long seed, int x, int y, int z) {
        long h = mix(seed, x);
        h = mix(h, y);
        h = mix(h, z);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Same splitmix64-style avalanche as {@link Rng}'s node mixing — kept local rather than shared since both are small, stable, and serve different value shapes (a stream seed vs. a single lattice value). */
    private static long mix(long seed, int index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
