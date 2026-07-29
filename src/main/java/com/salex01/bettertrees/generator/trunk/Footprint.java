package com.salex01.bettertrees.generator.trunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Plan §8.2's footprint analysis — the subset {@link TrunkFootprintSolver} actually needs.
 * {@code roundness} and {@code limbBudget} are real §8.2 outputs too, but nothing consumes them
 * until deliberate limb splitting (§8.6) exists — deferred to that alongside the feature that
 * actually reads them, rather than carried here unused.
 */
public record Footprint(Set<Cell> cells, int minX, int maxX, int minZ, int maxZ, float density,
                         List<Set<Cell>> holes, Map<Cell, Float> distanceTransform, float maxDistance,
                         float centroidX, float centroidZ, float eccentricity, float leanDirX, float leanDirZ) {

    public int cellCount() {
        return cells.size();
    }

    public static Footprint analyze(Set<Cell> cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("footprint must have at least one cell");
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
        }
        long bboxArea = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        float density = bboxArea <= 0 ? 1f : cells.size() / (float) bboxArea;

        List<Set<Cell>> holes = findHoles(cells, minX, maxX, minZ, maxZ);

        Map<Cell, Float> dt = DistanceTransform.compute(cells);
        float maxDistance = 0f;
        for (float v : dt.values()) {
            maxDistance = Math.max(maxDistance, v);
        }

        double centroidX = 0, centroidZ = 0;
        for (Cell c : cells) {
            centroidX += c.x() + 0.5;
            centroidZ += c.z() + 0.5;
        }
        centroidX /= cells.size();
        centroidZ /= cells.size();

        // Second-moment matrix [[ixx, ixz], [ixz, izz]] about the centroid — its principal axis is
        // the footprint's major axis (plan §8.2's "second-moment matrix giving major/minor axis and
        // eccentricity"). θ = 0.5*atan2(2*ixz, ixx-izz) is the standard closed form for that axis's
        // angle, avoiding an explicit eigenvector computation (and its degenerate cases) entirely.
        double ixx = 0, izz = 0, ixz = 0;
        for (Cell c : cells) {
            double dx = (c.x() + 0.5) - centroidX;
            double dz = (c.z() + 0.5) - centroidZ;
            ixx += dx * dx;
            izz += dz * dz;
            ixz += dx * dz;
        }
        double trace = ixx + izz;
        double diff = StrictMath.sqrt(((ixx - izz) / 2) * ((ixx - izz) / 2) + ixz * ixz);
        double lambdaMajor = trace / 2 + diff;
        double lambdaMinor = trace / 2 - diff;
        float eccentricity = lambdaMajor > 1e-9 ? (float) StrictMath.sqrt(Math.max(0.0, 1.0 - lambdaMinor / lambdaMajor)) : 0f;

        double axisX, axisZ;
        // Below this, ixx/izz/ixz are noise-dominated (a symmetric or near-symmetric footprint has
        // no real second-moment asymmetry) and atan2 returns a numerically arbitrary angle rather
        // than a meaningful axis — since eccentricity already reads as ~0 for these, leanDir has no
        // real direction to report either, and must be (0,0) rather than an arbitrary unit vector
        // that a caller might apply at full lean magnitude.
        if (eccentricity < 1e-4f) {
            axisX = 0;
            axisZ = 0;
        } else {
            double axisAngle = 0.5 * StrictMath.atan2(2 * ixz, ixx - izz);
            axisX = StrictMath.cos(axisAngle);
            axisZ = StrictMath.sin(axisAngle);
            // The principal axis itself has no sign (an eigenvector and its negation are equally
            // valid) — resolve it from the footprint's own asymmetry instead of an arbitrary or
            // seeded choice: project how the centroid sits off the bbox's center onto the axis, and
            // point toward whichever side actually has the offset. A player-planted lopsided cluster
            // leans the way it's lopsided, not the way a coin flip landed.
            double bboxCenterX = (minX + maxX + 1) / 2.0;
            double bboxCenterZ = (minZ + maxZ + 1) / 2.0;
            double projection = (centroidX - bboxCenterX) * axisX + (centroidZ - bboxCenterZ) * axisZ;
            if (projection < 0) {
                axisX = -axisX;
                axisZ = -axisZ;
            }
        }

        return new Footprint(Set.copyOf(cells), minX, maxX, minZ, maxZ, density, holes, dt, maxDistance,
                (float) centroidX, (float) centroidZ, eccentricity, (float) axisX, (float) axisZ);
    }

    /**
     * Flood fill the complement from outside the (padded) bbox (plan §8.2): background cells the
     * fill never reaches are enclosed — holes. Padding by 1 guarantees the seed cell is itself
     * background and outside the footprint's own extent, so the fill always has somewhere legal to
     * start regardless of the footprint's shape.
     */
    private static List<Set<Cell>> findHoles(Set<Cell> cells, int minX, int maxX, int minZ, int maxZ) {
        int padMinX = minX - 1, padMaxX = maxX + 1, padMinZ = minZ - 1, padMaxZ = maxZ + 1;

        Set<Cell> exterior = new HashSet<>();
        Deque<Cell> queue = new ArrayDeque<>();
        Cell start = new Cell(padMinX, padMinZ);
        exterior.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Cell current = queue.poll();
            for (Cell neighbor : current.neighbors4()) {
                if (neighbor.x() < padMinX || neighbor.x() > padMaxX || neighbor.z() < padMinZ || neighbor.z() > padMaxZ) {
                    continue;
                }
                if (cells.contains(neighbor) || exterior.contains(neighbor)) {
                    continue;
                }
                exterior.add(neighbor);
                queue.add(neighbor);
            }
        }

        Set<Cell> enclosedBackground = new HashSet<>();
        for (int x = padMinX; x <= padMaxX; x++) {
            for (int z = padMinZ; z <= padMaxZ; z++) {
                Cell c = new Cell(x, z);
                if (!cells.contains(c) && !exterior.contains(c)) {
                    enclosedBackground.add(c);
                }
            }
        }
        return new ArrayList<>(CellComponents.find(enclosedBackground));
    }
}
