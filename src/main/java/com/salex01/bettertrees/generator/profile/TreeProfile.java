package com.salex01.bettertrees.generator.profile;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.math.Rng;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every tunable constant the growth algorithm (plan §5) reads. Plain Java with hardcoded defaults
 * for this milestone — JSON/Codec loading and archetype inheritance are Milestone 11 (plan §4).
 */
public record TreeProfile(
        float apicalDominance,
        Phyllotaxis phyllotaxis,
        BranchAngleCurve branchAngleCurve,
        CrownCurve crownCurve,
        Twist twist,
        float hardTwistThresholdDegrees,
        Map<Diameter, Float> twistWindowBlocks,
        float segmentLength,
        float segmentLengthJitter,
        BranchingRule rule,
        Map<Diameter, RunRange> runs,
        float runPressureExponent,
        Map<Diameter, float[]> forkWeights,
        float upBias,
        float outBias,
        float gravityPerDepth,
        float azimuthSpread,
        Map<Diameter, Float> costFactor,
        float initialEnergy,
        Occupancy occupancy,
        /** Plan §7 step 4 — refuse to grow (consume nothing) if more than this fraction of the blueprint's voxels would land on non-replaceable world blocks. */
        float maxObstructionRatio,
        Foliage foliage,
        /** Plan §8.2 — trunkHeight's hard cap, and the multiplier on trunkHeight's sqrt(area) term. */
        int maxHeight,
        float heightFactor,
        /** Plan §8.2 — canopyRadius's sqrt(area) multiplier: {@code canopyRadius = 2.0 + 1.9*sqrt(area)*canopyFactor}, the crown steering/clipping radius a big tree's handoff tips use in place of a single sapling's fixed {@code CrownCurve.REFERENCE_RADIUS}. */
        float canopyFactor,
        /** Plan §8.3 — tau(y) = maxDT * (y/H)^taperExponent. */
        float taperExponent,
        Cavities cavities,
        /** Plan §8.2 — lean magnitude's multiplier on the base footprint's eccentricity: {@code lean = eccentricity * leanFactor}. */
        float leanFactor,
        /** Plan §8.5 — per-layer accumulation rate for {@code leanOffset(y) = leanDir * min(y*leanRate, maxLean)}; clamped to at most 1 cell per layer when applied. */
        float leanRate,
        /** Plan §8.5 — the cap {@code leanOffset(y)} saturates at, producing the J-shaped sweep. */
        float maxLean,
        LimbSplit limbSplit) {

    /** Side-branch angle from vertical, in degrees, as a linear ramp from base height to crown top plus variance (plan §4.1 point 3). */
    public record BranchAngleCurve(float bottomDegrees, float topDegrees, float varianceDegrees) {
        public float angleAt(float relHeight, Rng rng) {
            float t = Math.clamp(relHeight, 0f, 1f);
            float base = bottomDegrees + t * (topDegrees - bottomDegrees);
            return base + (rng.nextFloat() * 2f - 1f) * varianceDegrees;
        }
    }

    public record Twist(float min, float mode, float max) {}

    public record RunRange(int min, int max) {}

    public record Occupancy(int probeCount, float probeDistance, float avoidanceStrength,
                             float otherTreeAvoidance, int collisionRetries, float fuseRadius,
                             boolean breakReplaceable) {}

    /** Plan §8.4 — per-hole openness oscillation driving the trunk-stack's closing/reopening cavities. */
    public record Cavities(float baseOpenness, float cavityDensityWeight, float noiseAmplitude,
                            float verticalNoiseScale, float openThreshold, int minRunBlocks) {}

    /**
     * Plan §8.6 — deliberate sub-trunk shedding. {@code energyFromArea} has no specified behavior
     * for {@code false} in the plan text (every shipped profile sets it {@code true}); only the
     * {@code true} path — area-proportional (pipe-model) energy split — is implemented.
     */
    public record LimbSplit(float chancePerLayer, int minParentCells, int minCells, int maxCells,
                             float driftPerLayer, boolean energyFromArea, float minHeightRatio) {}

    /**
     * Outer-canopy leaf placement (plan §9). {@code radius} is keyed by D8/D4 only — D12/D16 never
     * get real leaf blocks. {@code noiseAmplitude}/{@code noiseScale} have no reference value in
     * §15 (only {@code leaf_radius} and — elsewhere — {@code leafy_branch_chance} are specified);
     * these two are an implementation choice, same footing as {@code SkeletonGenerator}'s
     * {@code SIDE_DIAMETER_DOWNROLL_CHANCE}.
     */
    public record Foliage(Map<Diameter, Float> radius, float noiseAmplitude, float noiseScale, float leafyBranchChance) {
        /** Null for D12/D16 — never eligible for real leaf blocks. */
        public Float radius(Diameter d) {
            return radius.get(d);
        }
    }

    /** Child count for one fork, sampled from {@link #forkWeights} for the tip's diameter — falls back to a single continuation child if the diameter has no entry. */
    public int sampleForkCount(Diameter d, Rng rng) {
        float[] weights = forkWeights.get(d);
        if (weights == null || weights.length == 0) {
            return 1;
        }
        float total = 0f;
        for (float w : weights) {
            total += w;
        }
        float pick = rng.nextFloat() * total;
        float acc = 0f;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (pick <= acc) {
                return i + 1;
            }
        }
        return weights.length;
    }

    public RunRange runRange(Diameter d) {
        RunRange r = runs.get(d);
        if (r == null) {
            throw new IllegalStateException("No run range configured for " + d);
        }
        return r;
    }

    public float twistWindow(Diameter d) {
        Float w = twistWindowBlocks.get(d);
        return w == null ? 0f : w;
    }

    public float costFactor(Diameter d) {
        Float c = costFactor.get(d);
        return c == null ? 1f : c;
    }

    // --- Defaults from the plan text, packaged as two starting archetypes for Milestone 4's tests ---

    private static Map<Diameter, RunRange> defaultRuns() {
        Map<Diameter, RunRange> m = new EnumMap<>(Diameter.class);
        m.put(Diameter.D16, new RunRange(6, 24));
        m.put(Diameter.D12, new RunRange(4, 12));
        m.put(Diameter.D8, new RunRange(3, 8));
        m.put(Diameter.D4, new RunRange(2, 5));
        return m;
    }

    private static Map<Diameter, Float> defaultTwistWindow() {
        Map<Diameter, Float> m = new EnumMap<>(Diameter.class);
        m.put(Diameter.D16, 8f);
        m.put(Diameter.D12, 6f);
        m.put(Diameter.D8, 4f);
        m.put(Diameter.D4, 2f);
        return m;
    }

    private static Map<Diameter, Float> defaultCostFactor() {
        Map<Diameter, Float> m = new EnumMap<>(Diameter.class);
        for (Diameter d : Diameter.values()) {
            m.put(d, 1f);
        }
        return m;
    }

    private static Occupancy defaultOccupancy() {
        return new Occupancy(6, 3.0f, 0.8f, 1.2f, 4, 2.5f, true);
    }

    /** Matches §15's reference {@code cavities} block exactly. */
    private static Cavities defaultCavities() {
        return new Cavities(0.5f, 1.0f, 0.35f, 0.18f, 0.5f, 2);
    }

    /** Matches §15's reference {@code limb_split} block exactly. */
    private static LimbSplit defaultLimbSplit() {
        return new LimbSplit(0.06f, 9, 2, 6, 0.8f, true, 0.15f);
    }

    /** Matches §15's reference {@code leaf_radius} (8:2.2, 4:1.6) and {@code leafy_branch_chance} (0.9) exactly. */
    private static Foliage defaultFoliage() {
        Map<Diameter, Float> radius = new EnumMap<>(Diameter.class);
        radius.put(Diameter.D8, 2.2f);
        radius.put(Diameter.D4, 1.6f);
        return new Foliage(radius, 0.3f, 0.5f, 0.9f);
    }

    /** High apical dominance, whorled phyllotaxis, steep-to-shallow branch angle: a fir/spruce-like conifer. */
    public static TreeProfile fir() {
        // weights[i] is the chance of i+1 children — index 0 (pure continuation, no fork) must
        // dominate, especially at D16/D12, or "one dominant leader" (apical_dominance=0.85 below)
        // is contradicted by the tip forking on almost every segment. Bug found via a real in-game
        // screenshot in M5 review: these were previously weighted the other way around (weight[0]
        // smallest), producing a tip that forked ~90% of the time at every tier — a chaotic mass of
        // full-cube D16 logs rather than a tree with a trunk that tapers into branches.
        Map<Diameter, float[]> forkWeights = new EnumMap<>(Diameter.class);
        forkWeights.put(Diameter.D16, new float[]{0.85f, 0.13f, 0.02f});
        forkWeights.put(Diameter.D12, new float[]{0.8f, 0.17f, 0.03f});
        forkWeights.put(Diameter.D8, new float[]{0.75f, 0.22f, 0.03f});
        forkWeights.put(Diameter.D4, new float[]{0.65f, 0.35f});

        return new TreeProfile(
                0.85f,
                new Phyllotaxis.Whorled(5, 1.5f),
                new BranchAngleCurve(80f, 40f, 6f),
                CrownCurve.preset(CrownCurve.Preset.CONE),
                new Twist(0f, 12f, 40f),
                30f,
                defaultTwistWindow(),
                2.0f, 0.2f,
                BranchingRule.SMALL_TREE,
                defaultRuns(),
                1.5f,
                forkWeights,
                0.7f, 0.15f, 0.05f,
                1.0f,
                defaultCostFactor(),
                120f,
                defaultOccupancy(),
                0.15f,
                defaultFoliage(),
                40, 1.0f, 1.0f, 0.85f,
                defaultCavities(),
                0.6f, 0.15f, 6f,
                defaultLimbSplit());
    }

    /** Low apical dominance, spiral phyllotaxis, flat wide-variance branch angle: an oak-like broadleaf. */
    public static TreeProfile oak() {
        // Matches plan §15's reference profile exactly (it's written for the decurrent archetype,
        // which oak is) — weight[0] (pure continuation) is still the plurality/majority at every
        // tier, just less dominant than fir's, matching oak's lower apical_dominance below without
        // tipping into the "forks almost every segment" bug fixed alongside this (see fir()'s comment).
        Map<Diameter, float[]> forkWeights = new EnumMap<>(Diameter.class);
        forkWeights.put(Diameter.D16, new float[]{0.5f, 0.4f, 0.1f});
        forkWeights.put(Diameter.D12, new float[]{0.6f, 0.35f, 0.05f});
        forkWeights.put(Diameter.D8, new float[]{0.7f, 0.3f, 0.0f});
        forkWeights.put(Diameter.D4, new float[]{0.85f, 0.15f, 0.0f});

        return new TreeProfile(
                0.35f,
                new Phyllotaxis.Spiral(137.5f),
                new BranchAngleCurve(60f, 60f, 15f),
                CrownCurve.preset(CrownCurve.Preset.SPHERE),
                new Twist(0f, 18f, 55f),
                30f,
                defaultTwistWindow(),
                2.0f, 0.3f,
                BranchingRule.SMALL_TREE,
                defaultRuns(),
                1.5f,
                forkWeights,
                // up/out/gravity — also matches §15's reference bias block exactly. Previous values
                // (0.4/0.35/0.03) had up_bias barely exceeding out_bias — a near-1:1 ratio versus the
                // reference's ~2.2:1 preference for vertical growth. Found from a player report of
                // "gigantic quasi-horizontal D16 branches with almost no trunk": with up and out this
                // close together, and D16's own run range allowing up to 24 blocks of travel at full
                // thickness before Rule B forces a step-down, a tip that forks early can wander most
                // of that budget sideways instead of up.
                0.55f, 0.25f, 0.04f,
                1.2f,
                defaultCostFactor(),
                150f,
                defaultOccupancy(),
                0.15f,
                defaultFoliage(),
                40, 1.0f, 1.0f, 0.85f,
                defaultCavities(),
                0.6f, 0.15f, 6f,
                defaultLimbSplit());
    }
}
