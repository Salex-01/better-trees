package com.salex01.bettertrees.generator.trunk;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerRepairTest {
    @Test
    void baseLayerWithNothingBelowIsReturnedUnchanged() {
        Set<Cell> layer = Set.of(new Cell(0, 0), new Cell(1, 0));
        assertEquals(layer, LayerRepair.repair(layer, Set.of()));
    }

    @Test
    void alreadyTouchingComponentIsUntouched() {
        Set<Cell> previous = Set.of(new Cell(0, 0));
        Set<Cell> layer = Set.of(new Cell(0, 0), new Cell(1, 0));
        assertEquals(layer, LayerRepair.repair(layer, previous));
    }

    /** A small island a few cells away from the previous layer gets bridged: the path cells appear, and the result now touches. */
    @Test
    void nearbyDriftedComponentGetsBridged() {
        Set<Cell> previous = Set.of(new Cell(0, 0));
        Set<Cell> layer = Set.of(new Cell(3, 0));

        Set<Cell> repaired = LayerRepair.repair(layer, previous);

        assertTrue(repaired.containsAll(layer), "the original drifted cells must survive repair");
        assertTrue(repaired.contains(new Cell(0, 0)), "bridge must touch the previous layer");
        List<Set<Cell>> components = CellComponents.find(repaired);
        assertEquals(1, components.size(), "bridging must produce one connected mass");
        for (Set<Cell> component : components) {
            boolean touches = component.stream().anyMatch(previous::contains);
            assertTrue(touches, "every component must touch layer(y-1) after repair");
        }
    }

    /** A component too far away (beyond the repair cap) is deleted rather than bridged with an absurd path; a component that already shares a cell with the previous layer is left alone. */
    @Test
    void farAwayComponentIsDeletedNotBridged() {
        Set<Cell> previous = Set.of(new Cell(0, 0));
        Set<Cell> keep = Set.of(new Cell(0, 0));
        Set<Cell> layer = new java.util.HashSet<>(keep);
        layer.add(new Cell(50, 50));

        Set<Cell> repaired = LayerRepair.repair(layer, previous);

        assertEquals(keep, repaired, "the unreachable far component must be dropped, the already-touching one kept as is");
    }

    @Test
    void everyComponentTouchesPreviousLayerAfterRepairAcrossSyntheticCases() {
        Set<Cell> previous = Set.of(new Cell(5, 5), new Cell(5, 6));
        Set<Cell> layer = Set.of(new Cell(5, 5), new Cell(8, 8), new Cell(8, 9), new Cell(2, 2));

        Set<Cell> repaired = LayerRepair.repair(layer, previous);
        for (Set<Cell> component : CellComponents.find(repaired)) {
            assertFalse(component.isEmpty());
            boolean touches = component.stream().anyMatch(previous::contains);
            assertTrue(touches, "component " + component + " must touch the previous layer after repair");
        }
    }
}
