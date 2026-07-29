package com.salex01.bettertrees.generator.math;

/**
 * Pure integer 3-vector for voxel-grid positions — deliberately our own type, not Minecraft's
 * {@code Vec3i}/{@code BlockPos}. {@code generator/} has zero Minecraft imports (plan §1); the
 * world layer adapts between the two at the boundary.
 */
public record Vec3i(int x, int y, int z) {
    private static final Vec3i[] FACE_DIRECTIONS = {
            new Vec3i(0, 1, 0), new Vec3i(0, -1, 0),
            new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
    };

    public Vec3i add(Vec3i o) {
        return new Vec3i(x + o.x, y + o.y, z + o.z);
    }

    public Vec3i offset(int dx, int dy, int dz) {
        return new Vec3i(x + dx, y + dy, z + dz);
    }

    public static Vec3i floor(Vec3d v) {
        return new Vec3i((int) StrictMath.floor(v.x()), (int) StrictMath.floor(v.y()), (int) StrictMath.floor(v.z()));
    }

    /** The 6 face-adjacent neighbors, in a fixed order — used for 6-connectivity checks (plan §6). */
    public Vec3i[] faceNeighbors() {
        Vec3i[] out = new Vec3i[6];
        for (int i = 0; i < 6; i++) {
            out[i] = add(FACE_DIRECTIONS[i]);
        }
        return out;
    }
}
