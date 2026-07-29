package com.salex01.bettertrees.generator.profile;

import java.util.List;

/**
 * Max horizontal branch reach versus relative height {@code t} in [0, 1] (plan §4.1 point 4) —
 * piecewise-linear between control points, each {@code [t, radiusFactor]}. Does most of a
 * species's visual work on its own.
 */
public record CrownCurve(List<ControlPoint> points) {
    /**
     * Reference crown reach at {@code radiusFactor=1}, in blocks — not itself a profile field (no
     * test depends on crown-shape precision). Shared between {@code SkeletonGenerator} (growth
     * steering) and {@code LeafPlanner} (canopy clipping, plan §9) so both agree on what a given
     * {@code radiusFactor} actually means in-world.
     */
    public static final float REFERENCE_RADIUS = 6.0f;

    public record ControlPoint(float t, float radiusFactor) {}

    public CrownCurve {
        if (points.size() < 2) {
            throw new IllegalArgumentException("CrownCurve needs at least 2 control points");
        }
    }

    public float radiusFactorAt(float relHeight) {
        float t = Math.clamp(relHeight, 0f, 1f);
        for (int i = 0; i < points.size() - 1; i++) {
            ControlPoint a = points.get(i);
            ControlPoint b = points.get(i + 1);
            if (t <= b.t()) {
                float span = b.t() - a.t();
                float frac = span <= 1e-6f ? 0f : (t - a.t()) / span;
                return a.radiusFactor() + frac * (b.radiusFactor() - a.radiusFactor());
            }
        }
        return points.get(points.size() - 1).radiusFactor();
    }

    private static CrownCurve of(float... tRadiusPairs) {
        List<ControlPoint> pts = new java.util.ArrayList<>();
        for (int i = 0; i < tRadiusPairs.length; i += 2) {
            pts.add(new ControlPoint(tRadiusPairs[i], tRadiusPairs[i + 1]));
        }
        return new CrownCurve(pts);
    }

    public static CrownCurve preset(Preset preset) {
        return switch (preset) {
            case CONE -> of(0f, 1.0f, 1f, 0.05f);
            case SPHERE -> of(0f, 0.2f, 0.5f, 1.0f, 1f, 0.2f);
            case VASE -> of(0f, 0.15f, 0.4f, 0.3f, 0.8f, 1.0f, 1f, 0.7f);
            case UMBRELLA -> of(0f, 0.1f, 0.7f, 0.15f, 0.85f, 1.0f, 1f, 0.9f);
            case COLUMNAR -> of(0f, 0.35f, 1f, 0.3f);
        };
    }

    public enum Preset { CONE, SPHERE, VASE, UMBRELLA, COLUMNAR }
}
