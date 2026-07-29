package com.salex01.bettertrees.generator.trunk;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Noise3D;
import com.salex01.bettertrees.generator.math.Rng;
import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Plan §8.3/§8.4/§8.5/§8.6 — the trunk as a stack of eroded footprints, with cavities that close
 * and reopen, leaning per §8.5's J-shaped sweep, and deliberate sub-trunk shedding per §8.6. Each
 * "persistent thread" (the root footprint, plus every sub-trunk {@code limb_split} carves off) has
 * its own cell set, its own distance transform (recomputed on its own footprint at birth — a small
 * carved patch has a very different depth profile than the whole base), its own local height
 * budget, and — for a child — its own drift direction. All threads share one seed, one whole-tree
 * lean vector, and one combined per-layer output (a voxel at height {@code y} renders the same way
 * regardless of which thread produced it).
 */
public final class TrunkFootprintSolver {
    private TrunkFootprintSolver() {}

    public static TrunkResult solve(Set<Cell> baseFootprint, TreeProfile profile, long treeSeed) {
        Footprint footprint = Footprint.analyze(baseFootprint);
        int area = footprint.cellCount();
        int rootHeight = computeLocalHeight(area, profile);

        float centroidX = footprint.centroidX();
        float centroidZ = footprint.centroidZ();
        // Plan §8.2: canopyRadius = 2.0 + 1.9*sqrt(A)*canopy_factor.
        float canopyRadius = 2.0f + 1.9f * (float) StrictMath.sqrt(area) * profile.canopyFactor();
        // Plan §8.2's "lean = eccentricity * lean_factor along the major axis", added directly into
        // §5.5's direction-sampling bias — a separate quantity from leanOffset(y) below, which
        // shifts trunk *layers*, not skeleton tip direction.
        float leanMagnitude = footprint.eccentricity() * profile.leanFactor();
        Vec3d leanVector = new Vec3d(footprint.leanDirX() * leanMagnitude, 0, footprint.leanDirZ() * leanMagnitude);
        // Plan §8.5: "lean_rate clamped to at most 1 cell per layer".
        float effectiveLeanRate = Math.min(profile.leanRate(), 1f);
        // Plan §8.6: "drift_per_layer is clamped to at most 1 cell per layer".
        TreeProfile.LimbSplit limbSplit = profile.limbSplit();
        float effectiveDriftPerLayer = Math.min(limbSplit.driftPerLayer(), 1f);

        List<Set<Cell>> holes = footprint.holes();
        TreeProfile.Cavities cavityConf = profile.cavities();
        CavityTracker[] cavityTrackers = new CavityTracker[holes.size()];
        for (int i = 0; i < cavityTrackers.length; i++) {
            cavityTrackers[i] = new CavityTracker();
        }

        List<Layer> layers = new ArrayList<>();
        List<HandoffTip> handoffTips = new ArrayList<>();
        Map<Vec3i, Diameter> trunkVoxels = new HashMap<>();

        List<Thread> activeThreads = new ArrayList<>();
        activeThreads.add(Thread.root(baseFootprint, footprint.distanceTransform(), footprint.maxDistance(), area, rootHeight));
        int[] nextThreadId = {1};
        int maxY = Math.min(rootHeight, profile.maxHeight());

        int y = 0;
        while (y < maxY && !activeThreads.isEmpty()) {
            // Plan §8.5: leanOffset(y) = leanDir * min(y*lean_rate, max_lean). leanDir is (0,0) for
            // a footprint with negligible eccentricity (see Footprint.analyze) — that's where a
            // symmetric footprint's "no reason to lean any particular way" belongs, not a magnitude
            // scale here, so this formula stays the literal §8.5 one.
            int leanOffsetMagnitude = Math.round(Math.min(y * effectiveLeanRate, profile.maxLean()));
            int leanDx = Math.round(footprint.leanDirX() * leanOffsetMagnitude);
            int leanDz = Math.round(footprint.leanDirZ() * leanOffsetMagnitude);

            Set<Cell> combinedShiftedLayer = new HashSet<>();
            List<Cell> shiftedSingletonsThisLayer = new ArrayList<>();
            List<Float> shiftedSingletonEnergies = new ArrayList<>();
            List<Thread> spawned = new ArrayList<>();
            List<Thread> stillActive = new ArrayList<>();

            activeThreads.sort(Comparator.comparingInt(t -> t.id));
            for (Thread t : activeThreads) {
                int localY = y - t.birthY;
                if (localY < 0) {
                    continue; // not yet born — defensive; a spawned thread's birthY is always y+1 at spawn time, so this shouldn't occur
                }
                if (t.stillFootprint.isEmpty()) {
                    continue; // already fully handed off
                }
                // No separate "ran out of local height budget" branch here: the vanishing-component
                // lookahead below already treats nextLocalY >= t.localHeight as "no survivors next
                // layer," so every cell a thread is still holding gets handed off on its very last
                // real layer, before this check could ever see localY >= t.localHeight with nonempty
                // stillFootprint. Verified empirically, not just reasoned — instrumented this branch
                // and confirmed it never fires across every shape/profile/seed this suite covers.

                float tau = t.maxDistance * (float) StrictMath.pow((double) localY / t.localHeight, profile.taperExponent());
                Set<Cell> layerCells = new HashSet<>();
                for (Cell c : t.stillFootprint) {
                    Float d = t.dt.get(c);
                    if (d != null && d >= tau) {
                        layerCells.add(c);
                    }
                }
                if (t.isRoot) {
                    applyCavities(layerCells, holes, cavityTrackers, cavityConf, footprint.density(), treeSeed, y);
                }

                if (y >= limbSplit.minHeightRatio() * rootHeight) {
                    maxY = Math.max(maxY, applyLimbSplit(t, layerCells, y, treeSeed, limbSplit, profile, nextThreadId, spawned, maxY));
                }

                int dx, dz;
                if (t.isRoot) {
                    dx = leanDx;
                    dz = leanDz;
                } else {
                    int driftMagnitude = Math.round(localY * effectiveDriftPerLayer);
                    dx = leanDx + Math.round((float) t.driftDirX * driftMagnitude);
                    dz = leanDz + Math.round((float) t.driftDirZ * driftMagnitude);
                }

                int nextLocalY = localY + 1;
                float nextTau = t.maxDistance * (float) StrictMath.pow((double) nextLocalY / t.localHeight, profile.taperExponent());
                List<Cell> vanishing = handoffVanishingComponents(layerCells, t.stillFootprint, t.dt, nextTau, nextLocalY, t.localHeight);
                for (Cell unshifted : vanishing) {
                    Cell shifted = unshifted.add(new Cell(dx, dz));
                    shiftedSingletonsThisLayer.add(shifted);
                    shiftedSingletonEnergies.add(handoffEnergy(profile, t.peakArea));
                }

                combinedShiftedLayer.addAll(translate(layerCells, dx, dz));

                // Ordinary erosion: a cell whose dt just dropped under tau this layer is consumed
                // material (lathe-turned wood, not a branch tip) — drop it from stillFootprint now
                // rather than leaving it to linger forever. Left unpruned, tau's monotonic climb
                // means these cells never come back and never get individually flagged by either
                // handoff trigger above (their component didn't vanish, it just got smaller), so
                // they'd silently accumulate across every remaining layer and all get dumped at once
                // by the end-of-loop/height-budget fallback as a batch of disconnected "singletons"
                // at the wrong height — the actual fragmentation bug a 7x7 square's clean 49-25-9-1
                // taper was hitting (found via a diagnostic showing every real layer taper cleanly,
                // yet all 48 of that taper's peeled boundary cells still surfaced as tips at the last y).
                t.stillFootprint.retainAll(layerCells);

                if (!t.stillFootprint.isEmpty()) {
                    stillActive.add(t);
                }
            }

            Set<Cell> previous = layers.isEmpty() ? Set.of() : layers.get(layers.size() - 1).cells();
            Set<Cell> repaired = LayerRepair.repair(combinedShiftedLayer, previous);
            layers.add(new Layer(y, repaired));
            for (Cell c : repaired) {
                trunkVoxels.put(new Vec3i(c.x(), y, c.z()), Diameter.D16);
            }
            for (int i = 0; i < shiftedSingletonsThisLayer.size(); i++) {
                Cell shifted = shiftedSingletonsThisLayer.get(i);
                handoffTips.add(new HandoffTip(new Vec3d(shifted.x(), y, shifted.z()), shiftedSingletonEnergies.get(i)));
            }

            activeThreads = stillActive;
            activeThreads.addAll(spawned);
            y++;
        }

        // Any thread still holding cells when the loop exits hands off where it was left rather than
        // being dropped silently. In the ordinary case this never fires — the vanishing-component
        // lookahead above already empties every thread's stillFootprint on its own last real layer —
        // but a thread can still be non-empty here if the global profile.maxHeight() cap forces the
        // whole loop to stop before a late-born limb_split child's own local height budget was fully
        // spent (its birthY + localHeight can exceed maxHeight, per applyLimbSplit's own clamp).
        // Every thread still in activeThreads at this point was active on the loop's final iteration
        // (that's what "active" means here), so lastY is genuinely this thread's own last-rendered y
        // too, not a mismatched global one.
        int lastY = layers.isEmpty() ? 0 : layers.get(layers.size() - 1).y();
        for (Thread t : activeThreads) {
            if (!t.stillFootprint.isEmpty()) {
                finalizeThread(t, lastY, footprint, effectiveLeanRate, effectiveDriftPerLayer, profile, handoffTips);
            }
        }

        return new TrunkResult(layers, layers.size(), trunkVoxels, handoffTips, centroidX, centroidZ, canopyRadius, leanVector);
    }

    /**
     * A thread still holding cells when the whole solve loop ends (see the call site's comment for
     * when this is actually reachable) hands every one of them off as a D16 tip at {@code atY} — its
     * own last-rendered y — using the same lean+drift shift that layer used, so the tip lands
     * directly on a real trunk voxel rather than floating.
     */
    private static void finalizeThread(Thread t, int atY, Footprint footprint, float effectiveLeanRate,
            float effectiveDriftPerLayer, TreeProfile profile, List<HandoffTip> handoffTips) {
        int leanOffsetMagnitude = Math.round(Math.min(atY * effectiveLeanRate, profile.maxLean()));
        int dx = Math.round(footprint.leanDirX() * leanOffsetMagnitude);
        int dz = Math.round(footprint.leanDirZ() * leanOffsetMagnitude);
        if (!t.isRoot) {
            int localY = Math.max(0, atY - t.birthY);
            int driftMagnitude = Math.round(localY * effectiveDriftPerLayer);
            dx += Math.round((float) t.driftDirX * driftMagnitude);
            dz += Math.round((float) t.driftDirZ * driftMagnitude);
        }
        for (Cell c : t.stillFootprint) {
            Cell shifted = c.add(new Cell(dx, dz));
            handoffTips.add(new HandoffTip(new Vec3d(shifted.x(), atY, shifted.z()), handoffEnergy(profile, t.peakArea)));
        }
        t.stillFootprint.clear();
    }

    /** Plan §8.2's trunkHeight formula, shared by the root footprint and every {@code limb_split} patch. */
    private static int computeLocalHeight(int area, TreeProfile profile) {
        return Math.clamp(Math.round(6f + 3.4f * (float) StrictMath.sqrt(area) * profile.heightFactor()), 6, profile.maxHeight());
    }

    /**
     * Plan §8.6: for every 4-connected component of {@code thread}'s current layer that's still
     * part of its own {@code stillFootprint} and at least {@code min_parent_cells}, roll {@code
     * chance_per_layer} once. On success, carve a boundary-connected patch out of {@code thread}
     * (removed from its {@code stillFootprint} from this height up — this layer's own output still
     * includes it, since {@code layerCells} was already computed above) and spawn a new thread for
     * it, with its own recomputed DT, its own local height, and its own seeded drift direction.
     *
     * @return the highest {@code birthY + localHeight} among newly spawned threads (or the current {@code currentMaxY} if none spawned), so the caller can extend the outer loop bound to cover a late-born sub-trunk that outlives the root's own height.
     */
    private static int applyLimbSplit(Thread thread, Set<Cell> layerCells, int y, long treeSeed,
            TreeProfile.LimbSplit limbSplit, TreeProfile profile, int[] nextThreadId, List<Thread> spawned, int currentMaxY) {
        Set<Cell> stillFootprintInLayer = new HashSet<>(layerCells);
        stillFootprintInLayer.retainAll(thread.stillFootprint);

        int resultMaxY = currentMaxY;
        List<Set<Cell>> components = CellComponents.find(stillFootprintInLayer);
        components.sort(Comparator.comparingInt((Set<Cell> c) -> componentSortKey(c).x())
                .thenComparingInt(c -> componentSortKey(c).z()));

        for (Set<Cell> component : components) {
            if (component.size() < limbSplit.minParentCells()) {
                continue;
            }
            Rng rollRng = Rng.forNode(treeSeed, thread.id * 10_000 + y);
            if (rollRng.nextFloat() >= limbSplit.chancePerLayer()) {
                continue;
            }

            Set<Cell> patch = pickBoundaryPatch(component, limbSplit.minCells(), limbSplit.maxCells(), rollRng.fork(777));
            thread.stillFootprint.removeAll(patch);
            thread.peakArea = Math.max(1f, thread.peakArea - patch.size());

            int childId = nextThreadId[0]++;
            Map<Cell, Float> childDt = DistanceTransform.compute(patch);
            float childMaxDistance = 0f;
            for (float v : childDt.values()) {
                childMaxDistance = Math.max(childMaxDistance, v);
            }
            int childLocalHeight = computeLocalHeight(patch.size(), profile);
            int childBirthY = y + 1;

            Rng driftRng = Rng.forNode(treeSeed, 500_000 + childId);
            double angle = driftRng.nextDouble() * 2 * StrictMath.PI;
            Thread child = Thread.child(childId, patch, childDt, childMaxDistance, patch.size(), childLocalHeight,
                    childBirthY, StrictMath.cos(angle), StrictMath.sin(angle));
            spawned.add(child);
            resultMaxY = Math.max(resultMaxY, Math.min(childBirthY + childLocalHeight, profile.maxHeight()));
        }
        return resultMaxY;
    }

    /** Deterministic canonical ordering for components discovered by {@code HashSet}-backed flood fill, so which one rolls first never depends on hash-iteration order (plan §16 test 6). */
    private static Cell componentSortKey(Set<Cell> component) {
        return component.stream().min(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z)).orElseThrow();
    }

    /** Plan §8.6: a connected, boundary-touching patch of {@code minCells..maxCells} cells — a randomized flood fill starting from a boundary cell of {@code component}. */
    private static Set<Cell> pickBoundaryPatch(Set<Cell> component, int minCells, int maxCells, Rng rng) {
        List<Cell> boundary = new ArrayList<>();
        for (Cell c : component) {
            for (Cell n : c.neighbors4()) {
                if (!component.contains(n)) {
                    boundary.add(c);
                    break;
                }
            }
        }
        if (boundary.isEmpty()) {
            boundary = new ArrayList<>(component);
        }
        boundary.sort(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z));
        Cell seed = boundary.get(rng.nextInt(boundary.size()));

        int targetSize = Math.min(component.size(), minCells + rng.nextInt(Math.max(1, maxCells - minCells + 1)));

        Set<Cell> patch = new HashSet<>();
        Deque<Cell> queue = new ArrayDeque<>();
        patch.add(seed);
        queue.add(seed);
        while (!queue.isEmpty() && patch.size() < targetSize) {
            Cell current = queue.poll();
            for (Cell n : current.neighbors4()) {
                if (patch.size() >= targetSize) {
                    break;
                }
                if (component.contains(n) && patch.add(n)) {
                    queue.add(n);
                }
            }
        }
        return patch;
    }

    private static Set<Cell> translate(Set<Cell> cells, int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return cells;
        }
        Set<Cell> out = new HashSet<>();
        for (Cell c : cells) {
            out.add(new Cell(c.x() + dx, c.z() + dz));
        }
        return out;
    }

    /** Plan §8.4: closed cavities fill in as wood, open ones stay excluded — {@code min_run_blocks} hysteresis prevents flicker. Root thread only — a carved {@code limb_split} patch is far too small to have an interior hole of its own. */
    private static void applyCavities(Set<Cell> layerCells, List<Set<Cell>> holes, CavityTracker[] trackers,
            TreeProfile.Cavities cavities, float density, long treeSeed, int y) {
        for (int i = 0; i < holes.size(); i++) {
            float raw = cavities.baseOpenness() * (1f - density) * cavities.cavityDensityWeight()
                    + cavities.noiseAmplitude() * noise1D(treeSeed, i, y * cavities.verticalNoiseScale());
            boolean desiredOpen = raw > cavities.openThreshold();

            CavityTracker tracker = trackers[i];
            if (y == 0) {
                tracker.open = desiredOpen;
                tracker.runLength = 1;
            } else if (desiredOpen != tracker.open && tracker.runLength >= cavities.minRunBlocks()) {
                tracker.open = desiredOpen;
                tracker.runLength = 1;
            } else {
                tracker.runLength++;
            }

            if (!tracker.open) {
                layerCells.addAll(holes.get(i));
            }
        }
    }

    /**
     * Deterministic 1D noise per cavity, reusing {@link Noise3D} rather than a new 1D noise type:
     * the cavity index scaled well past 1 lattice unit apart picks out a distinct, uncorrelated
     * lattice region per cavity, so holding the other two axes fixed already gives an independent
     * smooth curve along {@code sampleY} for each cavity without any extra seed-mixing machinery.
     */
    private static float noise1D(long treeSeed, int cavityIndex, float sampleY) {
        return Noise3D.sample(treeSeed, sampleY, cavityIndex * 1000.0, 0.0);
    }

    /**
     * Plan §8.3: a footprint component that has eroded down to exactly one cell stops being a
     * footprint and hands over to {@code SkeletonGenerator}. That's the literal trigger, but it
     * misses a real case: a multi-cell component can shrink straight to nothing between one layer
     * and the next (its every cell sits under {@code tau} at {@code localY+1}) without ever passing
     * through a size-1 state — a symmetric blob eroding evenly from all sides does this constantly.
     * Left undetected, those cells silently linger in {@code stillFootprint} with no representation
     * in any future layer's component set until something else (a height-budget expiry, or the
     * end-of-loop sweep) eventually dumps the whole leftover pile as a batch of disconnected
     * "singletons" at the wrong height — the fragmentation bug this method now closes.
     *
     * <p>Operates on the unshifted layer (lean/drift hasn't been applied yet at the point this is
     * called) — removes every found cell from {@code stillFootprint} and returns them (still
     * unshifted) for the caller to place as handoff tips at their actual, shifted world position.
     *
     * @param nextTau tau(localY+1) for this thread — irrelevant if {@code nextLocalY >= localHeight}
     * @param nextLocalY the next layer's local y; treated as having no surviving cells at all once
     *     it reaches this thread's own {@code localHeight}, so a component's last real layer hands
     *     off here rather than waiting for the separate height-budget-expiry path to catch it
     */
    private static List<Cell> handoffVanishingComponents(Set<Cell> unshiftedLayer, Set<Cell> stillFootprint,
            Map<Cell, Float> dt, float nextTau, int nextLocalY, int localHeight) {
        Set<Cell> stillFootprintInLayer = new HashSet<>(unshiftedLayer);
        stillFootprintInLayer.retainAll(stillFootprint);
        List<Cell> found = new ArrayList<>();
        for (Set<Cell> component : CellComponents.find(stillFootprintInLayer)) {
            boolean vanishesNext = nextLocalY >= localHeight || component.stream().noneMatch(c -> {
                Float d = dt.get(c);
                return d != null && d >= nextTau;
            });
            if (component.size() == 1 || vanishesNext) {
                found.addAll(component);
                stillFootprint.removeAll(component);
            }
        }
        return found;
    }

    /**
     * Plan §8.3: "energy proportional to the component's peak cross-section" — but every other
     * §8.2 formula (trunkHeight, canopyRadius, limbBudget) scales with {@code sqrt(area)}, not area
     * itself, and a literal {@code initialEnergy * area} measured out at roughly 90x a single
     * sapling's energy for a 9x9 base (12 150 vs 150), producing an 80-block-wide crown — nowhere
     * near "a big tree." {@code sqrt(area)} keeps the same intent (a bigger base grows a bigger
     * crown) while matching the rest of this section's scaling law and staying in a sane size
     * range; a literal reading with a smaller hand-picked constant would work too, but this way the
     * constant is still just {@code initialEnergy}, not a new tunable nothing else justifies.
     * {@code peakArea} is a thread's own area at birth, reduced by whatever {@code limb_split} has
     * since carved away from it (plan §8.6's pipe-model energy conservation).
     */
    private static float handoffEnergy(TreeProfile profile, float peakArea) {
        return profile.initialEnergy() * (float) StrictMath.sqrt(peakArea);
    }

    private static final class CavityTracker {
        boolean open;
        int runLength;
    }

    /** One persistent footprint stack — the root (the whole base footprint) or a {@code limb_split} sub-trunk. */
    private static final class Thread {
        final int id;
        final boolean isRoot;
        final Set<Cell> stillFootprint;
        final Map<Cell, Float> dt;
        final float maxDistance;
        final int localHeight;
        final int birthY;
        final double driftDirX, driftDirZ;
        float peakArea;

        private Thread(int id, boolean isRoot, Set<Cell> stillFootprint, Map<Cell, Float> dt, float maxDistance,
                float peakArea, int localHeight, int birthY, double driftDirX, double driftDirZ) {
            this.id = id;
            this.isRoot = isRoot;
            this.stillFootprint = stillFootprint;
            this.dt = dt;
            this.maxDistance = maxDistance;
            this.peakArea = peakArea;
            this.localHeight = localHeight;
            this.birthY = birthY;
            this.driftDirX = driftDirX;
            this.driftDirZ = driftDirZ;
        }

        static Thread root(Set<Cell> baseFootprint, Map<Cell, Float> dt, float maxDistance, int area, int localHeight) {
            return new Thread(0, true, new HashSet<>(baseFootprint), dt, maxDistance, area, localHeight, 0, 0, 0);
        }

        static Thread child(int id, Set<Cell> patch, Map<Cell, Float> dt, float maxDistance, int peakArea,
                int localHeight, int birthY, double driftDirX, double driftDirZ) {
            return new Thread(id, false, new HashSet<>(patch), dt, maxDistance, peakArea, localHeight, birthY, driftDirX, driftDirZ);
        }
    }
}
