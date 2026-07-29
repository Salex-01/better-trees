package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import com.salex01.bettertrees.generator.skeleton.Skeleton;
import com.salex01.bettertrees.generator.skeleton.SkeletonGenerator;
import com.salex01.bettertrees.generator.skeleton.Tip;
import com.salex01.bettertrees.generator.trunk.Cell;
import com.salex01.bettertrees.generator.trunk.HandoffTip;
import com.salex01.bettertrees.generator.trunk.TrunkFootprintSolver;
import com.salex01.bettertrees.generator.trunk.TrunkResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Plan §8's full pure pipeline — {@code TrunkFootprintSolver} to a stack of layers and handoff
 * points, then {@code SkeletonGenerator}'s multi-tip overload to grow every handoff point's crown
 * into one shared skeleton, then merged into one {@code TreeBlueprint}. One place for this
 * composition so the world layer ({@code BetterSaplingBlock}) and pure tests (which need
 * {@code Skeleton}/{@code TrunkResult} to check connexity, determinism, and crown-shape agreement
 * with {@code LeafPlanner}) never duplicate the tip-construction and energy/height-budget logic.
 */
public final class BigTreeGrower {
    private BigTreeGrower() {}

    public record Result(TrunkResult trunk, Skeleton skeleton, TreeBlueprint blueprint) {}

    public static Result grow(Set<Cell> footprint, TreeProfile profile, long seed, OccupancyView occupancy) {
        TrunkResult trunk = TrunkFootprintSolver.solve(footprint, profile, seed);

        List<Tip> tips = new ArrayList<>();
        float maxTipEnergy = 0f;
        for (HandoffTip handoff : trunk.handoffTips()) {
            tips.add(new Tip(-1, handoff.pos(), Vec3d.UP, Diameter.D16, 0f, handoff.pos(), handoff.energy(), 0, 0f, false));
            maxTipEnergy = Math.max(maxTipEnergy, handoff.energy());
        }
        // Trunk height is already known (that's the whole point of computing it first) — only the
        // crown above it still needs a budget-shaped proxy, same costFactor/energy estimate the
        // single-sapling path uses for its own unknown final height.
        float crownBudget = maxTipEnergy / Math.max(0.01f, profile.costFactor(Diameter.D16));
        float estimatedMaxHeight = Math.max(1f, trunk.height() + crownBudget);
        Vec3d crownAxis = new Vec3d(trunk.centroidX(), 0, trunk.centroidZ());

        Skeleton skeleton = SkeletonGenerator.generate(seed, profile, tips, estimatedMaxHeight, crownAxis, trunk.canopyRadius(), trunk.leanVector(), occupancy);
        TreeBlueprint blueprint = TreeBlueprint.merge(trunk.trunkVoxels(), skeleton);
        return new Result(trunk, skeleton, blueprint);
    }
}
