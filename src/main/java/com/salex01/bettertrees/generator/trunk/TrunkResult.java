package com.salex01.bettertrees.generator.trunk;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import java.util.List;
import java.util.Map;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * {@link TrunkFootprintSolver}'s full output: the layer-by-layer cross-sections (for tests 7/8),
 * the trunk-stack's D16 voxels ready to merge into a {@code TreeBlueprint}, the handoff points
 * where {@code SkeletonGenerator} takes over to grow the organic crown, the crown's own reference
 * axis/radius (plan §8.2's centroid and {@code canopyRadius}) so that crown steering measures
 * against the whole cluster's shape rather than any single handoff tip's own column, and {@code
 * leanVector} — plan §5.5's whole-tree lean bias (magnitude {@code eccentricity * leanFactor}
 * along the footprint's own major axis), added into every handoff tip's direction sampling the
 * same way {@code crownAxis} is.
 */
public record TrunkResult(List<Layer> layers, int height, Map<Vec3i, Diameter> trunkVoxels,
                           List<HandoffTip> handoffTips, float centroidX, float centroidZ, float canopyRadius,
                           Vec3d leanVector) {}
