package com.salex01.bettertrees.generator.math;

/** Triangular-distribution sampling (plan §5.4 — twist angle). */
public final class Triangular {
    private Triangular() {}

    /**
     * Exact inverse-CDF sample from a triangular distribution with min {@code a}, mode {@code c},
     * max {@code b}, given a uniform {@code u} in [0, 1).
     */
    public static float sample(float a, float c, float b, float u) {
        float f = (c - a) / (b - a);
        return u < f
                ? a + (float) StrictMath.sqrt(u * (b - a) * (c - a))
                : b - (float) StrictMath.sqrt((1 - u) * (b - a) * (b - c));
    }
}
