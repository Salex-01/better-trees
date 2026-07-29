package com.salex01.bettertrees.generator.trunk;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Two-pass 3-4 chamfer distance transform (plan §8.2) — for every cell in {@code footprint}, its
 * approximate distance to the nearest cell <em>outside</em> the footprint (background, including
 * holes: a hole is background too, so cells ringing a hole taper just like cells ringing the outer
 * boundary — plan §8.4's "sparse/holed footprints taper into airy trunks" falls out of this for
 * free rather than needing separate handling).
 *
 * <p>Classic two-pass chamfer (Borgefors 1986): axis-aligned neighbors cost 3, diagonal neighbors
 * cost 4, then the raw integer cost is divided by 3 so a straight run of boundary-adjacent cells
 * reads as distance ~1.0, matching what a true Euclidean distance transform would give to within
 * a few percent — plenty for a taper curve, per §8.2's own "a two-pass 3-4 chamfer is plenty".
 */
public final class DistanceTransform {
    private static final float ORTHOGONAL_COST = 3f;
    private static final float DIAGONAL_COST = 4f;
    private static final float UNIT = 3f;

    private DistanceTransform() {}

    /** @return every footprint cell mapped to its chamfer distance from the nearest background cell (never contains a non-footprint cell). */
    public static Map<Cell, Float> compute(Set<Cell> footprint) {
        if (footprint.isEmpty()) {
            return Map.of();
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : footprint) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
        }
        int w = maxX - minX + 1;
        int h = maxZ - minZ + 1;
        float inf = Float.MAX_VALUE / 4f;
        boolean[][] fg = new boolean[w][h];
        float[][] dist = new float[w][h];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                boolean foreground = footprint.contains(new Cell(x + minX, z + minZ));
                fg[x][z] = foreground;
                dist[x][z] = foreground ? inf : 0f;
            }
        }

        // Forward pass: x ascending, z ascending — every neighbor read below was already finalized
        // this pass (same x, lower z; or a fully-processed lower x row, any z).
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                if (!fg[x][z]) {
                    continue;
                }
                float best = dist[x][z];
                best = Math.min(best, valueAt(dist, w, h, x - 1, z) + ORTHOGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x, z - 1) + ORTHOGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x - 1, z - 1) + DIAGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x - 1, z + 1) + DIAGONAL_COST);
                dist[x][z] = best;
            }
        }
        // Backward pass: x descending, z descending — mirror image of the forward mask.
        for (int x = w - 1; x >= 0; x--) {
            for (int z = h - 1; z >= 0; z--) {
                if (!fg[x][z]) {
                    continue;
                }
                float best = dist[x][z];
                best = Math.min(best, valueAt(dist, w, h, x + 1, z) + ORTHOGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x, z + 1) + ORTHOGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x + 1, z + 1) + DIAGONAL_COST);
                best = Math.min(best, valueAt(dist, w, h, x + 1, z - 1) + DIAGONAL_COST);
                dist[x][z] = best;
            }
        }

        Map<Cell, Float> result = new HashMap<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                if (fg[x][z]) {
                    result.put(new Cell(x + minX, z + minZ), dist[x][z] / UNIT);
                }
            }
        }
        return result;
    }

    private static float valueAt(float[][] dist, int w, int h, int x, int z) {
        if (x < 0 || x >= w || z < 0 || z >= h) {
            return 0f; // just outside the footprint's own bbox — necessarily background, distance 0
        }
        return dist[x][z];
    }
}
