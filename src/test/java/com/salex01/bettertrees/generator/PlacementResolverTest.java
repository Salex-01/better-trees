package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code world/BlueprintPlacer} places every non-LEAF {@link Placement} unconditionally once a
 * tree is accepted (M5 review fix — per-voxel obstruction skipping used to apply to wood too,
 * which could disconnect a chain mid-tree and produce exactly the floating-branch failure the
 * milestone's acceptance check names). That's only safe because {@link PlacementResolver} never
 * drops or duplicates a wood voxel relative to the {@link TreeBlueprint} it was built from — these
 * tests lock that invariant down independently of the placer, which needs a {@code Level} and so
 * can't be exercised here.
 */
class PlacementResolverTest {
    private static final Vec3i ORIGIN = new Vec3i(0, 0, 0);

    @Test
    void everyBlueprintVoxelBecomesExactlyOneMatchingWoodPlacement() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
                Map<Vec3i, Integer> leaves = LeafPlanner.planLeaves(sk, blueprint, profile);
                Map<Vec3i, Placement> placements = PlacementResolver.resolve(blueprint, leaves, sk.seed(), profile.foliage().leafyBranchChance());

                int woodCount = 0;
                for (Map.Entry<Vec3i, Placement> entry : placements.entrySet()) {
                    if (entry.getValue().kind() == Placement.Kind.LEAF) {
                        continue;
                    }
                    woodCount++;
                    Diameter expected = blueprint.diameterAt(entry.getKey());
                    assertEquals(expected, entry.getValue().d(),
                            "seed=" + seed + " pos=" + entry.getKey() + " diameter mismatch");
                    Placement.Kind expectedKind = expected == Diameter.D16 ? Placement.Kind.TRUNK : Placement.Kind.BRANCH;
                    assertEquals(expectedKind, entry.getValue().kind(), "seed=" + seed + " pos=" + entry.getKey());
                    // leafy is a per-voxel roll (leafy_branch_chance) for D4, never true otherwise —
                    // not a hard equality against diameter==D4 the way it was before that roll existed.
                    if (expected != Diameter.D4) {
                        assertFalse(entry.getValue().leafy(), "seed=" + seed + " pos=" + entry.getKey() + " only D4 can be leafy");
                    }
                }
                assertEquals(blueprint.size(), woodCount, "seed=" + seed + " wood placement count vs blueprint size");
            }
        }
    }

    /**
     * The exact invariant {@code BlueprintPlacer} relies on: the non-LEAF subset of the placement
     * map is still one 6-connected component containing the origin, same as {@code
     * ConnexityTest} already guarantees for the raw blueprint — placing every one of these voxels
     * unconditionally can never introduce a floating part. Purely a spatial/occupancy fact — not
     * affected by which pairs the connection booleans (below) actually render as joined.
     */
    @Test
    void woodPlacementsStayOneConnectedComponent() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
                Map<Vec3i, Integer> leaves = LeafPlanner.planLeaves(sk, blueprint, profile);
                Map<Vec3i, Placement> placements = PlacementResolver.resolve(blueprint, leaves, sk.seed(), profile.foliage().leafyBranchChance());

                Set<Vec3i> wood = new HashSet<>();
                for (Map.Entry<Vec3i, Placement> entry : placements.entrySet()) {
                    if (entry.getValue().kind() != Placement.Kind.LEAF) {
                        wood.add(entry.getKey());
                    }
                }
                assertTrue(wood.contains(ORIGIN), "seed=" + seed + " origin missing from wood placements");

                Set<Vec3i> visited = floodFillFrom(ORIGIN, wood);
                assertEquals(wood.size(), visited.size(),
                        "seed=" + seed + " " + wood.size() + " wood placements but only " + visited.size()
                                + " reachable from origin — placing all of them unconditionally would still float a part");
            }
        }
    }

    /**
     * Bug found from a player report: connections used to render on bare spatial adjacency (any
     * two touching wood voxels), which visually welds together branches that are merely close in
     * space but structurally unrelated. Property check over generated trees: every connection
     * {@code woodConnections} reports true is independently verified against a same-segment or
     * direct-parent/child relationship, re-derived fresh via {@link VoxelOwnership} rather than
     * trusting {@code PlacementResolver}'s own internals.
     */
    @Test
    void everyReportedConnectionIsStructurallyRelated() {
        for (long seed : GeneratorTestSupport.seeds()) {
            for (TreeProfile profile : GeneratorTestSupport.PROFILES) {
                Skeleton sk = SkeletonGenerator.generate(seed, profile, Diameter.D16, OccupancyView.ALWAYS_FREE);
                TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
                Map<Vec3i, Integer> leaves = LeafPlanner.planLeaves(sk, blueprint, profile);
                Map<Vec3i, Placement> placements = PlacementResolver.resolve(blueprint, leaves, sk.seed(), profile.foliage().leafyBranchChance());
                Map<Vec3i, List<Integer>> ownership = VoxelOwnership.of(sk);
                List<Segment> segments = sk.segments();

                for (Map.Entry<Vec3i, Placement> entry : placements.entrySet()) {
                    if (entry.getValue().kind() == Placement.Kind.LEAF) {
                        continue;
                    }
                    Vec3i pos = entry.getKey();
                    boolean[] connections = PlacementResolver.woodConnections(pos, placements, ownership, segments);
                    List<Integer> ownersHere = ownership.getOrDefault(pos, List.of());

                    for (Vec3i neighbor : pos.faceNeighbors()) {
                        boolean reported = connectionTo(connections, pos, neighbor);
                        if (!reported) {
                            continue;
                        }
                        Placement neighborPlacement = placements.get(neighbor);
                        assertTrue(neighborPlacement != null && neighborPlacement.kind() != Placement.Kind.LEAF,
                                "seed=" + seed + " pos=" + pos + " neighbor=" + neighbor + " reported connected but isn't wood");

                        List<Integer> ownersThere = ownership.getOrDefault(neighbor, List.of());
                        boolean actuallyRelated = ownersHere.stream().anyMatch(a -> ownersThere.stream().anyMatch(b ->
                                a.equals(b) || segments.get(a).parentIndex() == b || segments.get(b).parentIndex() == a));
                        assertTrue(actuallyRelated, "seed=" + seed + " pos=" + pos + " neighbor=" + neighbor
                                + " reported connected but no owning segment pair is the same segment or a direct parent/child");
                    }
                }
            }
        }
    }

    /**
     * Direct regression fixture for the reported bug: two single-segment, unrelated vertical
     * chains one block apart in X — their voxels are face-adjacent (in Z... actually X) by
     * construction, but neither is the other's parent or child. They must not render as connected.
     * A genuine parent/child pair (segment 1 continuing from segment 0's endpoint) must.
     */
    @Test
    void unrelatedAdjacentBranchesDoNotConnectButParentChildDoes() {
        Segment trunk = new Segment(0, -1, new Vec3d(0, 0, 0), new Vec3d(0, 1, 0), Diameter.D4, false, 0f, new Vec3d(0, 0, 0), 1f);
        Segment child = new Segment(1, 0, new Vec3d(0, 1, 0), new Vec3d(0, 2, 0), Diameter.D4, false, 0f, new Vec3d(0, 0, 0), 2f);
        // Unrelated: its own separate trunk (parentIndex=-1, not 0 or 1), one block over in X so it
        // ends up face-adjacent to the first chain without sharing any ancestry.
        Segment stranger = new Segment(2, -1, new Vec3d(1, 0, 0), new Vec3d(1, 1, 0), Diameter.D4, false, 0f, new Vec3d(1, 0, 0), 1f);

        Skeleton sk = new Skeleton(0L, List.of(trunk, child, stranger), List.of());
        TreeBlueprint blueprint = TreeBlueprint.fromSkeleton(sk);
        Map<Vec3i, Placement> placements = PlacementResolver.resolve(blueprint, Map.of(), sk.seed(), 0.9f);
        Map<Vec3i, List<Integer>> ownership = VoxelOwnership.of(sk);
        List<Segment> segments = sk.segments();

        Vec3i trunkVoxel = new Vec3i(0, 0, 0);
        Vec3i childVoxel = new Vec3i(0, 1, 0);
        Vec3i strangerVoxel = new Vec3i(1, 0, 0);

        boolean[] trunkConnections = PlacementResolver.woodConnections(trunkVoxel, placements, ownership, segments);
        assertTrue(connectionTo(trunkConnections, trunkVoxel, childVoxel), "trunk must connect to its actual child, straight up");
        assertFalse(connectionTo(trunkConnections, trunkVoxel, strangerVoxel), "trunk must NOT connect to an unrelated adjacent branch");
    }

    /** Maps a face-neighbour offset to {@link PlacementResolver}'s FACE_OFFSETS index order (down, up, north, south, west, east). */
    private static boolean connectionTo(boolean[] connections, Vec3i pos, Vec3i neighbor) {
        int dx = neighbor.x() - pos.x();
        int dy = neighbor.y() - pos.y();
        int dz = neighbor.z() - pos.z();
        if (dy == -1) return connections[0];
        if (dy == 1) return connections[1];
        if (dz == -1) return connections[2];
        if (dz == 1) return connections[3];
        if (dx == -1) return connections[4];
        if (dx == 1) return connections[5];
        throw new IllegalArgumentException("not a face neighbor: " + pos + " -> " + neighbor);
    }

    private static Set<Vec3i> floodFillFrom(Vec3i start, Set<Vec3i> wood) {
        Set<Vec3i> visited = new HashSet<>();
        Deque<Vec3i> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Vec3i current = queue.poll();
            for (Vec3i neighbor : current.faceNeighbors()) {
                if (wood.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }
}
