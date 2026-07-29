package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Noise3D;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.skeleton.Segment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Merges a {@link TreeBlueprint}'s wood voxels with {@link LeafPlanner}'s leaf voxels into one
 * {@code Map<Vec3i, Placement>} — everything {@code world/BlueprintPlacer} needs to resolve
 * concrete blockstates, except the six connection booleans (those depend on which neighbouring
 * voxels are wood, which the placer reads straight off this same map rather than duplicating here).
 */
public final class PlacementResolver {
    private PlacementResolver() {}

    /**
     * @param leafDistances every leaf voxel LeafPlanner accepted, mapped to its DISTANCE (1-7, plan §9)
     * @param treeSeed used only to roll {@code leafy_branch_chance} per D4 voxel — deterministic per
     *                  {@code (treeSeed, position)}, not per segment: segments are only ~2 blocks
     *                  (1-3 voxels), so voxel-level granularity reads close enough to segment-level
     *                  without needing to thread a leafy flag through {@link TreeBlueprint}'s
     *                  max-diameter-wins merge, which currently tracks diameter only.
     */
    public static Map<Vec3i, Placement> resolve(TreeBlueprint blueprint, Map<Vec3i, Integer> leafDistances,
            long treeSeed, float leafyBranchChance) {
        Map<Vec3i, Placement> placements = new HashMap<>();
        for (Vec3i pos : blueprint.positions()) {
            Diameter d = blueprint.diameterAt(pos);
            // §3.2/§9's prose describes every naturally grown D4 as pre-foliated, but §15's
            // reference profile gives "leafy_branch_chance": 0.9 — a value strictly less than 1
            // only makes sense as a per-voxel roll, so that's what governs here; the prose reads as
            // the common case, not a hard guarantee.
            boolean leafy = d == Diameter.D4 && Noise3D.hash01(treeSeed, pos.x(), pos.y(), pos.z()) < leafyBranchChance;
            Placement.Kind kind = d == Diameter.D16 ? Placement.Kind.TRUNK : Placement.Kind.BRANCH;
            placements.put(pos, new Placement(d, leafy, false, kind, 0));
        }
        for (Map.Entry<Vec3i, Integer> entry : leafDistances.entrySet()) {
            // Wood always wins voxel ownership — LeafPlanner already skips blueprint.contains(pos),
            // but stay defensive rather than assuming callers never hand this a stale leaf map.
            placements.putIfAbsent(entry.getKey(), new Placement(null, false, false, Placement.Kind.LEAF, entry.getValue()));
        }
        return placements;
    }

    // Direction.values() ordinal order in Minecraft is DOWN, UP, NORTH, SOUTH, WEST, EAST — this
    // array must stay in that exact order so world/BlueprintPlacer can zip it against Direction.values()
    // without needing a Minecraft import here.
    private static final Vec3i[] FACE_OFFSETS = {
            new Vec3i(0, -1, 0), new Vec3i(0, 1, 0),
            new Vec3i(0, 0, -1), new Vec3i(0, 0, 1),
            new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0),
    };

    /**
     * Face connectivity for a wood voxel, indexed in {@link #FACE_OFFSETS}'s order (down, up,
     * north, south, west, east). A neighbour only counts as connected if it's wood (never LEAF)
     * <em>and</em> the two voxels are actually structurally related — the same segment, or a direct
     * parent/child pair (via {@code ownership}, plan §16 test 13's per-voxel claimant list rebuilt
     * from the committed segments by {@link VoxelOwnership}).
     *
     * <p>Bug found from a player report: this used to connect on bare spatial adjacency — any two
     * wood voxels touching, regardless of which segments owned them. The hard veto during
     * generation only rejects a candidate segment that <em>overlaps</em> a voxel another segment
     * already claims (and even then only outside {@code fuse_radius} of a common ancestor); it
     * never checks whether a candidate's voxels end up merely face-adjacent to an unrelated
     * branch's. Two branches from different parts of the tree can legitimately end up touching by
     * spatial coincidence, and bare-adjacency connectivity rendered that as a fused joint — visually
     * welding unrelated limbs together. Deliberately stricter than generation's own {@code
     * fuse_radius} exception: rendering only asks "parent, child, or the same segment", not "close
     * to a shared ancestor" — a joint's shared voxel already carries both the parent's and every
     * child's ownership, so parent/child alone covers every legitimate fork without needing that
     * distance check here too.
     *
     * <p>Plan §8.3 exception: a big tree's trunk-stack voxels aren't produced by any {@code
     * Segment} at all (they come straight from {@code TrunkFootprintSolver}'s layers), so {@code
     * ownership} has no entry for them — {@code structurallyRelated} treats a wood neighbour with
     * no owning segment as related unconditionally, since the trunk stack is one shape by
     * construction (every layer is either a monotone subset of the one below it, or explicitly
     * repaired into touching it — plan §8.3/§8.7) and a handoff tip's first segment is genuinely
     * that trunk's own continuation, not a coincidence of nearby geometry the way an unrelated
     * branch touching another one is.
     */
    public static boolean[] woodConnections(Vec3i pos, Map<Vec3i, Placement> placements,
            Map<Vec3i, List<Integer>> ownership, List<Segment> segments) {
        boolean[] connections = new boolean[6];
        List<Integer> ownersHere = ownership.getOrDefault(pos, List.of());
        for (int i = 0; i < 6; i++) {
            Vec3i neighborPos = pos.add(FACE_OFFSETS[i]);
            Placement neighbor = placements.get(neighborPos);
            if (neighbor == null || neighbor.kind() == Placement.Kind.LEAF) {
                continue;
            }
            List<Integer> ownersThere = ownership.getOrDefault(neighborPos, List.of());
            connections[i] = structurallyRelated(ownersHere, ownersThere, segments);
        }
        return connections;
    }

    private static boolean structurallyRelated(List<Integer> ownersA, List<Integer> ownersB, List<Segment> segments) {
        if (ownersA.isEmpty() || ownersB.isEmpty()) {
            return true; // trunk-stack voxel on at least one side — see this method's javadoc
        }
        for (int a : ownersA) {
            for (int b : ownersB) {
                if (a == b || segments.get(a).parentIndex() == b || segments.get(b).parentIndex() == a) {
                    return true;
                }
            }
        }
        return false;
    }
}
