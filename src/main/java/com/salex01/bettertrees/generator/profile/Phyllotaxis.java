package com.salex01.bettertrees.generator.profile;

/**
 * Where side branches attach around the trunk/parent limb (plan §4.1 point 2). Determines the
 * azimuthal offset of successive side children at a fork.
 */
public sealed interface Phyllotaxis {
    /** Conifers: rings of branches at regular height intervals, bare trunk in between. */
    record Whorled(int count, float spacing) implements Phyllotaxis {}

    /** Most broadleaves: successive branches advance by a fixed golden-angle-like step. */
    record Spiral(float angleDegrees) implements Phyllotaxis {}

    /** Maple/ash: branches in opposing pairs. */
    record Opposite(float angleDegrees) implements Phyllotaxis {}

    /**
     * Azimuth offset in degrees for the {@code index}-th (0-based) side child produced at one
     * fork. Deliberately local to a single fork rather than tracking a running position along the
     * whole trunk — {@code Whorled}'s height-interval spacing and cross-fork spiral continuity are
     * archetype-tuning concerns for Milestone 11, not structural ones this milestone's tests check.
     */
    default float azimuthOffsetDegrees(int index, int sideChildCount) {
        return switch (this) {
            case Whorled w -> sideChildCount <= 0 ? 0f : index * (360f / sideChildCount);
            case Spiral s -> index * s.angleDegrees();
            case Opposite o -> (index % 2 == 0) ? 0f : o.angleDegrees();
        };
    }
}
