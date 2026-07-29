package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Vec3d;

/**
 * Where a tip terminated (energy exhausted, or a D4 run hit its cap) and the diameter it was
 * growing at when it did — {@link com.salex01.bettertrees.generator.LeafPlanner}'s raw material.
 * Carrying the diameter here (rather than just a position) is what lets the planner enforce
 * "never D12/D16" (plan §9) without re-deriving it from the skeleton.
 */
public record LeafAnchor(Vec3d pos, Diameter d) {}
