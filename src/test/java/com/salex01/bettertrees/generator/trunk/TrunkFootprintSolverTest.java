package com.salex01.bettertrees.generator.trunk;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §16 tests 7 (layer invariant) and 8 (bounds), plus Milestone 7's own acceptance check —
 * "4x4, 9x9, ring and cavity clusters all grow" — exercised as the four synthetic footprint shapes
 * below rather than through the world layer (no {@code Level} exists here; that's §8.1's job).
 */
class TrunkFootprintSolverTest {
    private static final List<TreeProfile> PROFILES = List.of(TreeProfile.fir(), TreeProfile.oak());
    private static final long[] SEEDS = {1L, 2L, 12345L, 987654321L, -42L};

    private static Set<Cell> fourByFour() {
        return DistanceTransformTest.square(0, 0, 4);
    }

    private static Set<Cell> nineByNine() {
        return DistanceTransformTest.square(0, 0, 9);
    }

    /** A ring: a 9x9 square with its 5x5 center removed — the removed middle is also §8.4's cavity case. */
    private static Set<Cell> ringWithCavity() {
        Set<Cell> outer = DistanceTransformTest.square(0, 0, 9);
        outer.removeAll(DistanceTransformTest.square(2, 2, 5));
        return outer;
    }

    private static List<Set<Cell>> shapes() {
        return List.of(fourByFour(), nineByNine(), ringWithCavity());
    }

    @Test
    void everyShapeAndProfileAndSeedGrowsAtLeastOneLayer() {
        for (Set<Cell> shape : shapes()) {
            for (TreeProfile profile : PROFILES) {
                for (long seed : SEEDS) {
                    TrunkResult result = TrunkFootprintSolver.solve(shape, profile, seed);
                    assertFalse(result.layers().isEmpty(), "shape of size " + shape.size() + " produced no layers");
                    assertFalse(result.trunkVoxels().isEmpty());
                    assertFalse(result.handoffTips().isEmpty(), "every footprint must eventually hand over to the crown");
                }
            }
        }
    }

    /** Test 7 — every 4-connected component of layer(y) shares at least one cell with layer(y-1). */
    @Test
    void everyLayerComponentTouchesTheLayerBelow() {
        for (Set<Cell> shape : shapes()) {
            for (TreeProfile profile : PROFILES) {
                for (long seed : SEEDS) {
                    List<Layer> layers = TrunkFootprintSolver.solve(shape, profile, seed).layers();
                    for (int i = 1; i < layers.size(); i++) {
                        Set<Cell> previous = layers.get(i - 1).cells();
                        for (Set<Cell> component : CellComponents.find(layers.get(i).cells())) {
                            boolean touches = component.stream().anyMatch(previous::contains);
                            assertTrue(touches, "seed=" + seed + " layer y=" + layers.get(i).y()
                                    + " component " + component + " doesn't touch layer(y-1)");
                        }
                    }
                }
            }
        }
    }

    /**
     * Test 8 — every layer's cells stay inside the base footprint's own bbox plus the lean budget
     * (plan §8.5's {@code max_lean}, in either direction on either axis since {@code leanDir} can
     * point any way) plus a sub-trunk drift budget (§8.6's {@code drift_per_layer}, unbounded but
     * capped by how many layers any one thread can possibly be active for — the biggest possible
     * {@code limb_split} patch, {@code max_cells}, bounds a child's own {@code localHeight} via the
     * same §8.2 formula the root uses, so {@code (localHeight(max_cells) - 1) * drift_per_layer} is
     * a tight upper bound on any one thread's drift, not just a generous one), and height never
     * exceeds {@code max_height}.
     */
    @Test
    void everyLayerStaysInBoundsAndHeightRespectsMaxHeight() {
        for (Set<Cell> shape : shapes()) {
            int minX = shape.stream().mapToInt(Cell::x).min().orElseThrow();
            int maxX = shape.stream().mapToInt(Cell::x).max().orElseThrow();
            int minZ = shape.stream().mapToInt(Cell::z).min().orElseThrow();
            int maxZ = shape.stream().mapToInt(Cell::z).max().orElseThrow();

            for (TreeProfile profile : PROFILES) {
                int leanBudget = (int) Math.ceil(profile.maxLean());
                int maxChildLocalHeight = Math.clamp(
                        Math.round(6f + 3.4f * (float) Math.sqrt(profile.limbSplit().maxCells()) * profile.heightFactor()),
                        6, profile.maxHeight());
                int driftBudget = (int) Math.ceil((maxChildLocalHeight - 1) * Math.min(profile.limbSplit().driftPerLayer(), 1f));
                int budget = leanBudget + driftBudget;
                for (long seed : SEEDS) {
                    TrunkResult result = TrunkFootprintSolver.solve(shape, profile, seed);
                    assertTrue(result.height() <= profile.maxHeight(), "trunk height must respect max_height");
                    assertTrue(result.height() >= 6, "trunk height floor from plan §8.2's formula");

                    for (Layer layer : result.layers()) {
                        for (Cell c : layer.cells()) {
                            assertTrue(c.x() >= minX - budget && c.x() <= maxX + budget
                                            && c.z() >= minZ - budget && c.z() <= maxZ + budget,
                                    "seed=" + seed + " cell " + c + " escaped the base footprint's bbox plus its lean+drift budget of " + budget);
                        }
                    }
                }
            }
        }
    }

    /** The trunk voxel map's Y range must match the layer list one-to-one, and never exceed the reported height. */
    @Test
    void trunkVoxelsMatchLayersExactly() {
        TrunkResult result = TrunkFootprintSolver.solve(nineByNine(), TreeProfile.oak(), 7L);
        int expectedCount = result.layers().stream().mapToInt(l -> l.cells().size()).sum();
        assertEquals(expectedCount, result.trunkVoxels().size());
        for (var entry : result.trunkVoxels().entrySet()) {
            assertTrue(entry.getKey().y() < result.height());
        }
    }

    /**
     * Handoff energy is always positive and never exceeds what the whole base footprint could ever
     * carry (plan §8.3's {@code sqrt(area)} scaling — see {@link TrunkFootprintSolver}'s {@code
     * handoffEnergy} javadoc — applied to a thread's own {@code peakArea}, which starts at the
     * whole footprint's size for the root and only ever shrinks from there, whether by {@code
     * limb_split} carving cells away (§8.6's pipe-model conservation) or by being a child thread
     * born with a smaller patch to begin with).
     */
    @Test
    void handoffTipsCarryPositiveAreaProportionalEnergy() {
        Set<Cell> shape = nineByNine();
        TreeProfile profile = TreeProfile.oak();
        TrunkResult result = TrunkFootprintSolver.solve(shape, profile, 99L);
        float wholeFootprintEnergy = profile.initialEnergy() * (float) Math.sqrt(shape.size());
        assertFalse(result.handoffTips().isEmpty());
        for (var tip : result.handoffTips()) {
            assertTrue(tip.energy() > 0f, "handoff energy must be positive");
            assertTrue(tip.energy() <= wholeFootprintEnergy + 1e-3f,
                    "no thread's peak cross-section can exceed the whole base footprint's: got " + tip.energy() + " > " + wholeFootprintEnergy);
        }
    }

    /**
     * Every handoff tip must land on a real, rendered trunk voxel — never floating at a height or
     * position the trunk stack itself never produced. Regression test for the orphaned-cell
     * fragmentation bug: peeled-off boundary cells that were never pruned from a thread's {@code
     * stillFootprint} used to accumulate and get dumped as disconnected "singletons" at whatever y
     * the loop happened to end on, none of which corresponded to an actual trunk voxel there.
     */
    @Test
    void everyHandoffTipSitsOnARenderedTrunkVoxel() {
        for (Set<Cell> shape : shapes()) {
            for (TreeProfile profile : PROFILES) {
                for (long seed : SEEDS) {
                    TrunkResult result = TrunkFootprintSolver.solve(shape, profile, seed);
                    for (var tip : result.handoffTips()) {
                        Vec3i pos = new Vec3i(Math.round((float) tip.pos().x()), Math.round((float) tip.pos().y()),
                                Math.round((float) tip.pos().z()));
                        assertTrue(result.trunkVoxels().containsKey(pos),
                                "seed=" + seed + " handoff tip at " + pos + " has no corresponding trunk voxel");
                    }
                }
            }
        }
    }

    @Test
    void singleCellFootprintHandsOffImmediately() {
        Set<Cell> shape = Set.of(new Cell(0, 0));
        TrunkResult result = TrunkFootprintSolver.solve(shape, TreeProfile.fir(), 1L);
        assertEquals(1, result.handoffTips().size());
    }
}
