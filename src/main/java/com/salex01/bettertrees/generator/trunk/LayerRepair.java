package com.salex01.bettertrees.generator.trunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Plan §8.7 — runs on every layer after cavities (lean and limb drift are Milestone 8, but a
 * cavity closing/reopening can already disconnect a component from the layer below it, so this
 * pass is needed starting this milestone). "Touches {@code layer(y-1)} by a face" means shares at
 * least one {@code (x, z)} cell with it — that's the only way two horizontal cross-sections one
 * block apart can share a face at all (two cells at the same column are vertically face-adjacent;
 * anything merely horizontally near a lower-layer cell is edge- or corner-adjacent at best, exactly
 * what "connex by faces, not by edges and corners" forbids).
 */
public final class LayerRepair {
    /**
     * Not exactly spec'd by §8.7's "too far to repair" — a footprint cluster is capped at 16x16
     * (plan §8.1), so no bridge should ever need to span further than the cluster's own widest
     * possible extent.
     */
    private static final int MAX_BRIDGE_DISTANCE = 16;

    private LayerRepair() {}

    /**
     * @param layerCells this layer's cells before repair (post-erosion, post-cavity-overlay)
     * @param previousLayerCells {@code layer(y-1)}'s cells, or empty for the base layer (y=0, which has nothing below to touch and is returned unchanged)
     */
    public static Set<Cell> repair(Set<Cell> layerCells, Set<Cell> previousLayerCells) {
        if (previousLayerCells.isEmpty()) {
            return layerCells;
        }
        Set<Cell> result = new HashSet<>(layerCells);
        for (Set<Cell> component : CellComponents.find(layerCells)) {
            if (touches(component, previousLayerCells)) {
                continue;
            }
            Bridge bridge = nearestBridge(component, previousLayerCells);
            if (bridge == null || bridge.distance > MAX_BRIDGE_DISTANCE) {
                // Drifted too far to repair — delete it. Energy conservation (refunding it to the
                // parent) is test 12's concern, a later milestone; dropped here same as an
                // exhausted-retry tip is dropped during skeleton growth.
                result.removeAll(component);
                continue;
            }
            result.addAll(bridge.path);
        }

        for (Set<Cell> component : CellComponents.find(result)) {
            if (!touches(component, previousLayerCells)) {
                throw new IllegalStateException("LayerRepair post-condition violated: component " + component
                        + " still doesn't touch the layer below after repair");
            }
        }
        return result;
    }

    private static boolean touches(Set<Cell> component, Set<Cell> previousLayerCells) {
        for (Cell c : component) {
            if (previousLayerCells.contains(c)) {
                return true;
            }
        }
        return false;
    }

    private record Bridge(List<Cell> path, int distance) {}

    /** Nearest (component cell, previous-layer cell) pair by Manhattan distance, and the orthogonal path between them. */
    private static Bridge nearestBridge(Set<Cell> component, Set<Cell> previousLayerCells) {
        Cell bestFrom = null;
        Cell bestTo = null;
        int best = Integer.MAX_VALUE;
        for (Cell from : previousLayerCells) {
            for (Cell to : component) {
                int d = from.manhattanDistance(to);
                if (d < best) {
                    best = d;
                    bestFrom = from;
                    bestTo = to;
                }
            }
        }
        if (bestFrom == null) {
            return null;
        }
        return new Bridge(orthogonalPath(bestFrom, bestTo), best);
    }

    /** An L-shaped path of face-adjacent cells from {@code from} to {@code to} inclusive — axis steps only, so every consecutive pair stays 4-connected. */
    private static List<Cell> orthogonalPath(Cell from, Cell to) {
        List<Cell> path = new ArrayList<>();
        int x = from.x();
        int z = from.z();
        path.add(new Cell(x, z));
        while (x != to.x()) {
            x += Integer.signum(to.x() - x);
            path.add(new Cell(x, z));
        }
        while (z != to.z()) {
            z += Integer.signum(to.z() - z);
            path.add(new Cell(x, z));
        }
        return path;
    }
}
