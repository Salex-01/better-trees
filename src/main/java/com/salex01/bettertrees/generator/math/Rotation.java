package com.salex01.bettertrees.generator.math;

/** Axis-angle rotation (plan §5.5 step 4). */
public final class Rotation {
    private Rotation() {}

    /** Rodrigues' rotation formula: rotates {@code v} around unit {@code axis} by {@code theta} radians. */
    public static Vec3d rotateAroundAxis(Vec3d v, Vec3d axis, double theta) {
        double cos = StrictMath.cos(theta);
        double sin = StrictMath.sin(theta);
        Vec3d term1 = v.scale(cos);
        Vec3d term2 = axis.cross(v).scale(sin);
        Vec3d term3 = axis.scale(axis.dot(v) * (1 - cos));
        return term1.add(term2).add(term3);
    }
}
