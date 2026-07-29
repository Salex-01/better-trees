package com.salex01.bettertrees.generator.trunk;

import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 8's own acceptance check — "a 7-wide trunk visibly sheds a 2-wide sub-trunk that goes
 * its own way" — as a structural proxy: no {@code Level} exists here to look at, so instead verify
 * the mechanism actually fires (more than one handoff-energy value appears, meaning at least one
 * thread's {@code peakArea} was reduced by a carve-off) across enough seeds that a 0.06-per-layer
 * chance over ~30 layers is overwhelmingly likely to trigger at least once.
 */
class LimbSplitTest {
    private static Set<Cell> sevenBySeven() {
        Set<Cell> cells = new HashSet<>();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                cells.add(new Cell(x, z));
            }
        }
        return cells;
    }

    @Test
    void aWideEnoughBaseEventuallyShedsASubTrunk() {
        TreeProfile profile = TreeProfile.oak();
        boolean sawMoreThanOneEnergyLevel = false;
        for (long seed = 0; seed < 40 && !sawMoreThanOneEnergyLevel; seed++) {
            TrunkResult result = TrunkFootprintSolver.solve(sevenBySeven(), profile, seed);
            float first = result.handoffTips().get(0).energy();
            for (var tip : result.handoffTips()) {
                if (Math.abs(tip.energy() - first) > 1e-3f) {
                    sawMoreThanOneEnergyLevel = true;
                    break;
                }
            }
        }
        assertTrue(sawMoreThanOneEnergyLevel, "a 7x7 base over 40 seeds never produced a split — limb_split isn't firing");
    }

    /** A footprint too small to ever reach min_parent_cells never splits, regardless of seed. */
    @Test
    void tooSmallABaseNeverSplits() {
        TreeProfile profile = TreeProfile.oak();
        Set<Cell> tiny = DistanceTransformTest.square(0, 0, 2);
        for (long seed = 0; seed < 20; seed++) {
            TrunkResult result = TrunkFootprintSolver.solve(tiny, profile, seed);
            float first = result.handoffTips().get(0).energy();
            for (var tip : result.handoffTips()) {
                assertTrue(Math.abs(tip.energy() - first) < 1e-3f,
                        "seed=" + seed + " a 2x2 base (4 cells, below min_parent_cells=9) should never have a component large enough to split");
            }
        }
    }
}
