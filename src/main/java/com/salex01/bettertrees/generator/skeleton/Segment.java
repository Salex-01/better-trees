package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Vec3d;

/**
 * One emitted growth step (plan §5.1) — {@code index}/{@code parentIndex} make the skeleton a
 * navigable tree with stable node identity, not a flat list discarded after voxelizing.
 * {@code parentIndex == -1} marks the trunk base.
 *
 * @param firstOfBranch exempts this segment's twist from Rule C (plan §5.4) — a side branch
 *                       leaving its parent is a branch angle, not a twist.
 * @param twistDegrees  the angle actually sampled between this segment's direction and its
 *                       parent's — recorded so test 4 can check it against the threshold/window
 *                       without re-deriving direction math.
 * @param runRoot       where the current diameter run started (plan §5.1/§5.3).
 * @param runLengthAfter cumulative blocks at this diameter, including this segment.
 */
public record Segment(int index, int parentIndex, Vec3d a, Vec3d b, Diameter diameter,
                       boolean firstOfBranch, float twistDegrees, Vec3d runRoot, float runLengthAfter) {
    public Vec3d dir() {
        return b.sub(a).normalize();
    }

    public double length() {
        return a.distance(b);
    }
}
