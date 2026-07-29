package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Vec3d;

/**
 * Growth-queue work item (plan §5.1). {@code parentSegmentIndex} is bookkeeping beyond the plan's
 * illustrative record — it's what lets the next emitted {@link Segment} record its own parent.
 */
public record Tip(int parentSegmentIndex, Vec3d pos, Vec3d dir, Diameter d,
                   float runLength, Vec3d runRoot, float energy, int depth, float relHeight,
                   boolean firstOfBranch) {}
