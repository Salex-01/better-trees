package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.skeleton.Segment;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Which segment(s) claim each voxel a {@link Skeleton} rasterizes to. Same "every claimant, not
 * just the first" bookkeeping {@code SkeletonGenerator}'s hard veto keeps during generation (the
 * multi-owner fix from plan §16 test 13), rebuilt independently here from the committed segment
 * list — matching {@code SelfIntersectionTest}'s own approach of not trusting generation's runtime
 * state for something that gets checked again after the fact.
 */
public final class VoxelOwnership {
    private VoxelOwnership() {}

    public static Map<Vec3i, List<Integer>> of(Skeleton skeleton) {
        Map<Vec3i, List<Integer>> owners = new HashMap<>();
        for (Segment seg : skeleton.segments()) {
            for (Vec3i voxel : Voxelizer.rasterize(seg.a(), seg.b())) {
                owners.computeIfAbsent(voxel, k -> new ArrayList<>()).add(seg.index());
            }
        }
        return owners;
    }
}
