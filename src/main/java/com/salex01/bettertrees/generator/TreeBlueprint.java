package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every voxel a skeleton occupies, reduced from overlapping segments by max-diameter-wins (plan
 * §6). Minimal for this milestone — just position-to-diameter, enough for the connexity and
 * self-intersection tests; full {@link Placement} resolution (connection booleans, leafy,
 * waterlogged) is world-layer work for when {@code world/BlueprintPlacer} actually consumes this.
 */
public final class TreeBlueprint {
    private final Map<Vec3i, Diameter> voxels;

    private TreeBlueprint(Map<Vec3i, Diameter> voxels) {
        this.voxels = Map.copyOf(voxels);
    }

    public static TreeBlueprint fromSkeleton(Skeleton skeleton) {
        return merge(Map.of(), skeleton);
    }

    /**
     * Plan §8.3 — a big tree's blueprint is the trunk stack's own voxels (already {@code D16}, no
     * segment behind them) plus every crown segment grown from its handoff tips, same max-diameter-
     * wins reduction as {@link #fromSkeleton}. {@code baseVoxels} wins ties against an
     * equal-diameter segment voxel — a handoff tip's own first segment necessarily reoccupies the
     * trunk cell it started from, and that voxel is trunk, not crown, either way.
     */
    public static TreeBlueprint merge(Map<Vec3i, Diameter> baseVoxels, Skeleton skeleton) {
        Map<Vec3i, Diameter> voxels = new HashMap<>(baseVoxels);
        for (Segment seg : skeleton.segments()) {
            for (Vec3i v : Voxelizer.rasterize(seg.a(), seg.b())) {
                voxels.merge(v, seg.diameter(), (existing, incoming) -> existing.tier() >= incoming.tier() ? existing : incoming);
            }
        }
        return new TreeBlueprint(voxels);
    }

    public Set<Vec3i> positions() {
        return voxels.keySet();
    }

    public Diameter diameterAt(Vec3i pos) {
        return voxels.get(pos);
    }

    public boolean contains(Vec3i pos) {
        return voxels.containsKey(pos);
    }

    public int size() {
        return voxels.size();
    }
}
