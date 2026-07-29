package com.salex01.bettertrees.generator;

import org.jspecify.annotations.Nullable;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * What one voxel becomes, independent of world/block state (plan §2). {@code d} is null for
 * {@link Kind#LEAF}. {@code leafDistance} is only meaningful for {@link Kind#LEAF} — vanilla's
 * DISTANCE-to-log value (1-7, plan §9), unused (0) for everything else.
 */
public record Placement(@Nullable Diameter d, boolean leafy, boolean waterlogged, Kind kind, int leafDistance) {
    public enum Kind { TRUNK, BRANCH, ROOT, LEAF }
}
