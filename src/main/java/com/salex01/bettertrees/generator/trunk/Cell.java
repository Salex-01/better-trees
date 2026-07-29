package com.salex01.bettertrees.generator.trunk;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * A horizontal grid cell in the trunk footprint (plan §8.2) — deliberately 2D and distinct from
 * {@link com.salex01.bettertrees.generator.math.Vec3i}: the footprint solver works one horizontal
 * cross-section at a time, and folding a redundant {@code y} into every coordinate here would
 * blur that.
 */
public record Cell(int x, int z) {
    private static final Cell[] FACE_DIRECTIONS = {
            new Cell(1, 0), new Cell(-1, 0), new Cell(0, 1), new Cell(0, -1),
    };

    public Cell add(Cell o) {
        return new Cell(x + o.x, z + o.z);
    }

    /** The 4 face-adjacent neighbors, in a fixed order — used for 4-connectivity checks (plan §16 test 7). */
    public Cell[] neighbors4() {
        Cell[] out = new Cell[4];
        for (int i = 0; i < 4; i++) {
            out[i] = add(FACE_DIRECTIONS[i]);
        }
        return out;
    }

    public int manhattanDistance(Cell o) {
        return Math.abs(x - o.x) + Math.abs(z - o.z);
    }
}
