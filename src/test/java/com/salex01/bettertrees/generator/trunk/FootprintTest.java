package com.salex01.bettertrees.generator.trunk;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintTest {
    @Test
    void solidSquareHasNoHolesAndFullDensity() {
        Footprint fp = Footprint.analyze(DistanceTransformTest.square(0, 0, 5));
        assertEquals(25, fp.cellCount());
        assertEquals(1.0f, fp.density(), 1e-6f);
        assertTrue(fp.holes().isEmpty());
    }

    /** A 5x5 ring (outer square minus its 3x3 interior) has exactly one hole: the missing 3x3 middle. */
    @Test
    void ringShapeHasOneHoleMatchingTheGap() {
        Set<Cell> outer = DistanceTransformTest.square(0, 0, 5);
        Set<Cell> inner = DistanceTransformTest.square(1, 1, 3);
        outer.removeAll(inner);

        Footprint fp = Footprint.analyze(outer);
        assertEquals(25 - 9, fp.cellCount());
        assertEquals(1, fp.holes().size());
        assertEquals(inner, fp.holes().get(0));
        assertTrue(fp.density() < 1.0f, "bbox still 5x5 but not every cell in it is footprint");
    }

    /** Two separate 3x3 gaps inside a big enough ring produce two independent hole components. */
    @Test
    void twoSeparateGapsProduceTwoHoles() {
        Set<Cell> base = DistanceTransformTest.square(0, 0, 9);
        Set<Cell> gapA = DistanceTransformTest.square(1, 1, 2);
        Set<Cell> gapB = DistanceTransformTest.square(6, 6, 2);
        base.removeAll(gapA);
        base.removeAll(gapB);

        Footprint fp = Footprint.analyze(base);
        assertEquals(2, fp.holes().size());
    }

    @Test
    void distanceTransformOnlyCoversFootprintCells() {
        Set<Cell> cells = DistanceTransformTest.square(0, 0, 4);
        Footprint fp = Footprint.analyze(cells);
        assertEquals(cells, fp.distanceTransform().keySet());
        assertTrue(fp.maxDistance() > 0f);
    }
}
