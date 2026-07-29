package com.salex01.bettertrees.generator.trunk;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §8.2/§8.5 — {@code Footprint}'s eccentricity and signed {@code leanDir}. The sign is
 * resolved from the footprint's own asymmetry (centroid offset from the bbox center), not a
 * seeded coin flip, so a mirror-image cluster leans the mirrored way.
 */
class FootprintLeanTest {
    /**
     * A perfect square has no eccentricity, and — since a symmetric footprint's principal-axis
     * angle is numerically arbitrary (atan2 of two near-zero, noise-dominated second moments) rather
     * than genuinely meaningful — {@code leanDir} must be exactly (0,0), not an arbitrary unit
     * vector a caller could apply at full lean magnitude. Regression test for a real bug: a 7x7
     * square fragmented into dozens of spurious one-cell handoffs because leanDir was nonzero here
     * and got applied at near-full strength every layer.
     */
    @Test
    void symmetricSquareHasNoEccentricity() {
        Footprint fp = Footprint.analyze(DistanceTransformTest.square(0, 0, 6));
        assertEquals(0f, fp.eccentricity(), 1e-4f);
        assertEquals(0f, fp.leanDirX(), 1e-4f);
        assertEquals(0f, fp.leanDirZ(), 1e-4f);
    }

    /** An elongated rectangle is eccentric, and its lean direction is (near enough) along the long axis. */
    @Test
    void elongatedRectangleIsEccentricAlongItsLongAxis() {
        Set<Cell> cells = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(new Cell(x, z));
            }
        }
        Footprint fp = Footprint.analyze(cells);
        assertTrue(fp.eccentricity() > 0.9f, "a 20x3 rectangle should read as strongly eccentric");
        assertTrue(Math.abs(fp.leanDirX()) > Math.abs(fp.leanDirZ()), "lean should point mostly along the long (x) axis");
    }

    /** Mirror-imaging a lopsided footprint across x flips the sign of leanDirX — lean tracks the shape's own asymmetry, not an arbitrary/seeded choice. */
    @Test
    void mirroredFootprintsLeanOppositeWays() {
        // An L-shape: heavier on the +x side.
        Set<Cell> shape = new HashSet<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 2; z++) {
                shape.add(new Cell(x, z));
            }
        }
        for (int x = 4; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                shape.add(new Cell(x, z));
            }
        }

        Set<Cell> mirrored = new HashSet<>();
        for (Cell c : shape) {
            mirrored.add(new Cell(5 - c.x(), c.z()));
        }

        Footprint a = Footprint.analyze(shape);
        Footprint b = Footprint.analyze(mirrored);

        assertTrue(a.eccentricity() > 0.1f, "the fixture should actually be asymmetric enough to have a meaningful lean");
        // The mirrored shape's centroid sits on the opposite side of its own bbox center, so its
        // resolved leanDir should point the opposite way in x.
        assertTrue(Math.signum(a.leanDirX()) != Math.signum(b.leanDirX()) || Math.abs(a.leanDirX()) < 1e-3f,
                "mirrored footprints should lean opposite ways in x");
    }

    /** TrunkFootprintSolver's leanVector (the §5.5 skeleton bias) is zero exactly when eccentricity is zero, and scales up with it otherwise. */
    @Test
    void leanVectorMagnitudeTracksEccentricity() {
        var symmetric = TrunkFootprintSolver.solve(DistanceTransformTest.square(0, 0, 6),
                com.salex01.bettertrees.generator.profile.TreeProfile.oak(), 1L);
        assertEquals(0.0, symmetric.leanVector().length(), 1e-3);

        Set<Cell> elongated = new HashSet<>();
        for (int x = 0; x < 12; x++) {
            elongated.add(new Cell(x, 0));
            elongated.add(new Cell(x, 1));
        }
        var lopsided = TrunkFootprintSolver.solve(elongated, com.salex01.bettertrees.generator.profile.TreeProfile.oak(), 1L);
        assertTrue(lopsided.leanVector().length() > 0.1, "an elongated footprint should produce a nonzero lean bias");
    }
}
