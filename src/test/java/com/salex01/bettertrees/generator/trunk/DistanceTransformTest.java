package com.salex01.bettertrees.generator.trunk;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistanceTransformTest {
    @Test
    void everyCellInASolidBlockIsAtLeastOne() {
        Set<Cell> block = square(0, 0, 4);
        Map<Cell, Float> dt = DistanceTransform.compute(block);
        assertEquals(block.size(), dt.size());
        for (float v : dt.values()) {
            assertTrue(v >= 1.0f, "every foreground cell touches background within one step");
        }
    }

    /** A 3x3 solid block's lone interior cell is two Euclidean steps from the nearest background cell; every ring cell is one step. */
    @Test
    void threeByThreeBlockHasExactCenterDepth() {
        Set<Cell> block = square(0, 0, 3);
        Map<Cell, Float> dt = DistanceTransform.compute(block);

        assertEquals(2.0f, dt.get(new Cell(1, 1)), 1e-6f);
        for (Cell c : block) {
            if (c.equals(new Cell(1, 1))) {
                continue;
            }
            assertEquals(1.0f, dt.get(c), 1e-6f, "ring cell " + c + " should be exactly one step from background");
        }
    }

    /** A thin 1-wide line never gets deeper than a single boundary-adjacent cell — nothing is ever "interior". */
    @Test
    void oneWideLineIsAllBoundary() {
        Set<Cell> line = new HashSet<>();
        for (int x = 0; x < 8; x++) {
            line.add(new Cell(x, 0));
        }
        Map<Cell, Float> dt = DistanceTransform.compute(line);
        for (float v : dt.values()) {
            assertEquals(1.0f, v, 1e-6f);
        }
    }

    @Test
    void emptyFootprintProducesEmptyTransform() {
        assertTrue(DistanceTransform.compute(Set.of()).isEmpty());
    }

    static Set<Cell> square(int originX, int originZ, int size) {
        Set<Cell> cells = new HashSet<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                cells.add(new Cell(originX + x, originZ + z));
            }
        }
        return cells;
    }
}
