package com.salex01.bettertrees.generator.trunk;

import com.salex01.bettertrees.generator.math.Vec3d;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Where the trunk stack stops being a footprint and hands over to §5's organic
 * {@code SkeletonGenerator} (plan §8.3) — a footprint component that eroded down to a single cell.
 * {@code energy} is proportional to the component's peak cross-section (plan §8.3), which for
 * Milestone 7 (no §8.6 deliberate limb splitting yet) is always the whole base footprint's cell
 * count, since erosion alone only ever shrinks a component.
 */
public record HandoffTip(Vec3d pos, float energy) {}
