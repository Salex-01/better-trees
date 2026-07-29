package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import java.util.ArrayList;
import java.util.List;

/**
 * Amanatides–Woo segment rasterization (plan §6): walks from {@code a} to {@code b}, advancing
 * exactly one axis-aligned cell at a time along whichever axis has the smallest {@code tMax}.
 * That's a 6-connected (face-adjacent) voxel path by construction, never a diagonal-only hop —
 * this is the mechanism behind "no levitating parts" (test 1 asserts it rather than trusting it).
 *
 * <p>Ties between axes always resolve X, then Y, then Z — a fixed, deterministic tie-break (plan
 * §16 test 6).
 */
public final class Voxelizer {
    private static final int MAX_STEPS = 100_000;

    private Voxelizer() {}

    public static List<Vec3i> rasterize(Vec3d a, Vec3d b) {
        List<Vec3i> path = new ArrayList<>();

        int x = (int) StrictMath.floor(a.x());
        int y = (int) StrictMath.floor(a.y());
        int z = (int) StrictMath.floor(a.z());
        int endX = (int) StrictMath.floor(b.x());
        int endY = (int) StrictMath.floor(b.y());
        int endZ = (int) StrictMath.floor(b.z());

        path.add(new Vec3i(x, y, z));
        if (x == endX && y == endY && z == endZ) {
            return path;
        }

        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();

        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        double tDeltaX = stepX != 0 ? StrictMath.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? StrictMath.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? StrictMath.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = tMaxFor(a.x(), x, stepX, dx);
        double tMaxY = tMaxFor(a.y(), y, stepY, dy);
        double tMaxZ = tMaxFor(a.z(), z, stepZ, dz);

        int steps = 0;
        while (x != endX || y != endY || z != endZ) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            path.add(new Vec3i(x, y, z));

            if (++steps > MAX_STEPS) {
                throw new IllegalStateException("Voxelizer exceeded " + MAX_STEPS + " steps rasterizing " + a + " -> " + b);
            }
        }
        return path;
    }

    private static double tMaxFor(double start, int cell, int step, double d) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? cell + 1 : cell;
        return (boundary - start) / d;
    }
}
