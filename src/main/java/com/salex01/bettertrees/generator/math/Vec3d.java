package com.salex01.bettertrees.generator.math;

/** Pure double-precision 3-vector. No Minecraft imports — see plan §1. */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);
    public static final Vec3d UP = new Vec3d(0, 1, 0);
    public static final Vec3d DOWN = new Vec3d(0, -1, 0);

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d sub(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d scale(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    public double dot(Vec3d o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3d cross(Vec3d o) {
        return new Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double lengthSq() {
        return dot(this);
    }

    public double length() {
        return StrictMath.sqrt(lengthSq());
    }

    public double distance(Vec3d o) {
        return sub(o).length();
    }

    /** Zero vector normalizes to zero rather than throwing — callers on a degenerate direction fall back to it. */
    public Vec3d normalize() {
        double len = length();
        return len > 1e-12 ? scale(1.0 / len) : ZERO;
    }

    /**
     * Any unit vector orthogonal to this one — used to seed an orthonormal basis (plan §5.5 step
     * 2). Picks whichever world axis is least parallel to {@code this} so the cross product never
     * degenerates near-zero.
     */
    public Vec3d orthogonal() {
        Vec3d reference = StrictMath.abs(x) < 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        return cross(reference).normalize();
    }
}
