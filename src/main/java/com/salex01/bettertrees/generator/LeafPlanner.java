package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Noise3D;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.CrownCurve;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.LeafAnchor;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Real leaf-block placement around the outer canopy (plan §9): a noise-perturbed blob per
 * eligible (D8/D4) anchor, clipped by {@code crown_curve}, reduced to exactly the voxels vanilla
 * leaf decay would actually support.
 */
public final class LeafPlanner {
    /** Vanilla leaf decay dies at DISTANCE 7 — matches {@code BlockStateProperties.DISTANCE}'s own [1,7] range. */
    private static final int MAX_DISTANCE = 7;

    private LeafPlanner() {}

    /** @return every placed leaf voxel mapped to its vanilla-equivalent DISTANCE (1-7) — never contains a voxel the BFS from wood couldn't reach within {@link #MAX_DISTANCE}. */
    public static Map<Vec3i, Integer> planLeaves(Skeleton skeleton, TreeBlueprint blueprint, TreeProfile profile) {
        return planLeaves(skeleton, blueprint, profile, 0.0, 0.0, CrownCurve.REFERENCE_RADIUS);
    }

    /**
     * Plan §8.3 overload — a big tree's crown_curve clip (plan §9) measures against the whole
     * cluster's own centroid and {@code canopyRadius} (plan §8.2), not the single-sapling default
     * of local {@code (0, 0)} and {@code CrownCurve.REFERENCE_RADIUS} — same reasoning as {@code
     * SkeletonGenerator}'s multi-tip {@code generate()} overload, which this must agree with or a
     * big tree's leaves and its wood would be clipped to two different crown shapes.
     */
    public static Map<Vec3i, Integer> planLeaves(Skeleton skeleton, TreeBlueprint blueprint, TreeProfile profile,
            double crownAxisX, double crownAxisZ, float crownRadius) {
        Set<Vec3i> candidates = candidates(skeleton, blueprint, profile, crownAxisX, crownAxisZ, crownRadius);
        return bfsDistances(blueprint, candidates);
    }

    private static Set<Vec3i> candidates(Skeleton skeleton, TreeBlueprint blueprint, TreeProfile profile,
            double crownAxisX, double crownAxisZ, float crownRadius) {
        long seed = skeleton.seed();
        TreeProfile.Foliage foliage = profile.foliage();

        // An empty blueprint (no wood at all) is a real possibility in isolated unit tests — never
        // in an actual generated tree, which always has at least the origin trunk voxel — but stay
        // defensive rather than let minY/maxY sit at their unset sentinel extremes.
        int minY = 0, maxY = 0;
        boolean any = false;
        for (Vec3i pos : blueprint.positions()) {
            minY = any ? Math.min(minY, pos.y()) : pos.y();
            maxY = any ? Math.max(maxY, pos.y()) : pos.y();
            any = true;
        }
        float treeHeight = Math.max(1f, maxY - minY);

        Set<Vec3i> candidates = new HashSet<>();
        for (LeafAnchor anchor : skeleton.leafAnchors()) {
            Float baseRadius = foliage.radius(anchor.d());
            if (baseRadius == null) {
                continue; // never D12/D16 (plan §9)
            }

            // tipProximity is always 1.0 here — every LeafAnchor is created at a terminal tip in
            // this one-shot generator (energy exhaustion, D4 run cap, or a fully-vetoed retry
            // loop), so "how close to the tip" is trivially "at it". The factor becomes a real
            // variable once §19's living-tree state machine can have anchors mid-branch.
            float radius = baseRadius * (0.6f + 0.4f * 1.0f);

            Vec3i center = Vec3i.floor(anchor.pos());
            int r = (int) StrictMath.ceil(radius * (1f + foliage.noiseAmplitude())) + 1;

            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double dist = StrictMath.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                        Vec3i pos = center.offset(dx, dy, dz);

                        double noise = Noise3D.sample(seed, pos.x() * foliage.noiseScale(),
                                pos.y() * foliage.noiseScale(), pos.z() * foliage.noiseScale());
                        double perturbedRadius = radius * (1.0 + foliage.noiseAmplitude() * noise);
                        if (dist > perturbedRadius) {
                            continue;
                        }

                        if (blueprint.contains(pos)) {
                            continue; // skip wood
                        }

                        // crown_curve clip, per plan §9 — measured against the tree's actual height
                        // (this blueprint's own Y span), not the generator's energy-budget proxy for
                        // "max height" (that proxy is right for steering growth mid-generation, when
                        // the final height isn't known yet, but wrong here: the finished blueprint's
                        // real height is already in hand).
                        float relHeight = clamp01((pos.y() - minY) / treeHeight);
                        float maxCrownRadius = crownRadius * profile.crownCurve().radiusFactorAt(relHeight);
                        double horiz = StrictMath.hypot(pos.x() - crownAxisX, pos.z() - crownAxisZ);
                        if (horiz > maxCrownRadius) {
                            continue;
                        }

                        candidates.add(pos);
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Vanilla leaf decay is graph distance, not Euclidean: distance 1 for a candidate face-adjacent
     * to wood (branches count — they're in {@code #minecraft:logs}, plan §3.3), distance N+1 for a
     * candidate face-adjacent to a distance-N leaf, dropped if unreachable within {@link #MAX_DISTANCE}.
     * A plain BFS over the candidate set finds the true shortest distance from any wood voxel to
     * every reachable candidate in one pass, jointly across every anchor's blob at once.
     */
    private static Map<Vec3i, Integer> bfsDistances(TreeBlueprint blueprint, Set<Vec3i> candidates) {
        Map<Vec3i, Integer> distances = new HashMap<>();
        Deque<Vec3i> queue = new ArrayDeque<>();

        for (Vec3i candidate : candidates) {
            for (Vec3i neighbor : candidate.faceNeighbors()) {
                if (blueprint.contains(neighbor)) {
                    distances.put(candidate, 1);
                    queue.add(candidate);
                    break;
                }
            }
        }

        while (!queue.isEmpty()) {
            Vec3i current = queue.poll();
            int d = distances.get(current);
            if (d >= MAX_DISTANCE) {
                continue;
            }
            for (Vec3i neighbor : current.faceNeighbors()) {
                if (!candidates.contains(neighbor) || distances.containsKey(neighbor)) {
                    continue;
                }
                distances.put(neighbor, d + 1);
                queue.add(neighbor);
            }
        }
        return distances;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
