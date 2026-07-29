package com.salex01.bettertrees.generator.skeleton;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.OccupancyView;
import com.salex01.bettertrees.generator.Voxelizer;
import com.salex01.bettertrees.generator.math.Rng;
import com.salex01.bettertrees.generator.math.Rotation;
import com.salex01.bettertrees.generator.math.Triangular;
import com.salex01.bettertrees.generator.math.Vec3d;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.generator.profile.CrownCurve;
import com.salex01.bettertrees.generator.profile.TreeProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-shot growth algorithm (plan §5): a work queue of {@link Tip}s, each pop emitting one
 * {@link Segment} and pushing 1–3 children. Occupancy steering/veto (§6.2) is threaded through via
 * {@link OccupancyView}: soft avoidance biases the preferred direction away from probed obstacles
 * before sampling, and a hard veto rejects (and retries) any candidate segment that actually
 * rasterizes through something solid.
 *
 * <p>Every random draw for one segment comes from an {@link Rng} derived from
 * {@code (seed, segmentIndex)} — never a single stream consumed across the whole tree — so a
 * segment's own randomness never depends on queue order or how many other tips were processed
 * first (plan §16 test 6, and the note ahead of §19's monotonicity requirement).
 */
public final class SkeletonGenerator {
    /** Not a plan-specified constant — an implementation choice made here, not derived from §5 text. Candidate for promotion to a {@link TreeProfile} field if per-species tuning turns out to need it. */
    private static final float SIDE_DIAMETER_DOWNROLL_CHANCE = 0.35f;

    private SkeletonGenerator() {}

    public static Skeleton generate(long seed, TreeProfile profile, Diameter startDiameter, OccupancyView occupancy) {
        // A lone sapling doesn't know its own final height up front — costFactor/initialEnergy is
        // the only budget-shaped proxy available (plan §5's original approach, unchanged since M4).
        float estimatedMaxHeight = Math.max(1f, profile.initialEnergy() / Math.max(0.01f, profile.costFactor(startDiameter)));
        Tip start = new Tip(-1, Vec3d.ZERO, Vec3d.UP, startDiameter, 0f, Vec3d.ZERO, profile.initialEnergy(), 0, 0f, false);
        return generate(seed, profile, List.of(start), estimatedMaxHeight, Vec3d.ZERO, CrownCurve.REFERENCE_RADIUS, Vec3d.ZERO, occupancy);
    }

    /**
     * Plan §8.3's handoff — a big tree's crown starts from one or more {@code TrunkFootprintSolver}
     * handoff points instead of a single ground-level tip. All of them share one {@code segments}
     * list, one {@code selfOccupancy} map and one work queue, so segment indices never collide
     * across sub-trunks and later limbs correctly veto against earlier ones (a separate {@code
     * generate()} call per tip would blind each call to what every other tip had already claimed).
     * Unlike the single-sapling overload, {@code estimatedMaxHeight} here is a real, already-known
     * quantity (the trunk stack's own height, plus the initial tips' own energy budget) rather than
     * a proxy — callers should pass the true figure, not re-derive costFactor's estimate.
     *
     * <p>{@code crownAxis}/{@code crownRadius} replace the single-sapling overload's implicit
     * "column at local (0,0), radius {@code CrownCurve.REFERENCE_RADIUS}" assumption — a big tree's
     * handoff tips are scattered across the whole footprint, not centered on the caller's local
     * origin, so {@code crown_curve}'s radial pull/clip (plan §5.5 step 1) needs its own axis
     * (the footprint's centroid) and its own scale ({@code canopyRadius}, plan §8.2) instead of
     * silently measuring from the wrong point. Found from measuring a real 9x9 cluster during this
     * milestone's review: without this, every handoff tip steered relative to world axis (0,0)
     * instead of its own cluster, producing an ~80-block-wide sprawl instead of one tree.
     *
     * <p>{@code leanVector} is plan §5.5's whole-tree lean bias (magnitude {@code eccentricity *
     * lean_factor} along the footprint's major axis, plan §8.2) — added into every tip's preferred
     * direction unconditionally, same as {@code up_bias}/{@code out_bias}/gravity. {@code Vec3d.ZERO}
     * for the single-sapling overload (a lone sapling's footprint is a single cell — eccentricity 0,
     * no lean).
     *
     * @param initialTips one Tip per starting point; sorted into a fixed {@code (y, x, z)} order
     *                     before queueing so which segment index each one's first segment gets
     *                     never depends on caller-supplied list order (plan §16 test 6).
     */
    public static Skeleton generate(long seed, TreeProfile profile, List<Tip> initialTips, float estimatedMaxHeight,
            Vec3d crownAxis, float crownRadius, Vec3d leanVector, OccupancyView occupancy) {
        List<Segment> segments = new ArrayList<>();
        List<LeafAnchor> leafAnchors = new ArrayList<>();
        Deque<Tip> queue = new ArrayDeque<>();
        // Every voxel maps to *all* segments that have claimed it, not just the first — a candidate
        // touching a voxel three-plus branches share must be fusable with each of them individually
        // (plan §16 test 13's pairwise rule), not just whichever one got there first.
        Map<Vec3i, List<Integer>> selfOccupancy = new HashMap<>();
        TreeProfile.Occupancy occ = profile.occupancy();

        List<Tip> ordered = new ArrayList<>(initialTips);
        ordered.sort(Comparator.<Tip>comparingDouble(t -> t.pos().y())
                .thenComparingDouble(t -> t.pos().x())
                .thenComparingDouble(t -> t.pos().z()));
        for (Tip t : ordered) {
            float relHeight = clamp01((float) (t.pos().y() / estimatedMaxHeight));
            queue.add(new Tip(t.parentSegmentIndex(), t.pos(), t.dir(), t.d(), t.runLength(), t.runRoot(),
                    t.energy(), t.depth(), relHeight, t.firstOfBranch()));
        }

        while (!queue.isEmpty()) {
            Tip tip = queue.poll();
            int segIndex = segments.size();
            Rng rng = Rng.forNode(seed, segIndex);

            Diameter d = tip.d();
            float runLength = tip.runLength();
            Vec3d runRoot = tip.runRoot();
            boolean steppedDown = false;

            TreeProfile.RunRange range = profile.runRange(d);
            float t = clamp01(range.max() == range.min() ? 1f : (runLength - range.min()) / (float) (range.max() - range.min()));
            float p = (float) StrictMath.pow(t, profile.runPressureExponent());
            boolean forced = runLength >= range.max();

            Diameter smaller = smallerTier(d);
            if (smaller == null) {
                // D4 cannot step down further — at maxRun the tip terminates as a leaf anchor (plan §5.3).
                if (forced) {
                    leafAnchors.add(new LeafAnchor(tip.pos(), d));
                    continue;
                }
            } else if (forced || rng.nextFloat() < p) {
                d = smaller;
                runLength = 0f;
                runRoot = tip.pos();
                steppedDown = true;
            }

            // --- §6.2 hard veto: try a candidate segment, resample on collision, give up after collision_retries. ---
            Vec3d newDir = null;
            Vec3d newPos = null;
            float segLen = 0f;
            boolean accepted = false;
            for (int retry = 0; retry <= occ.collisionRetries(); retry++) {
                Vec3d candidateDir = candidateDirection(tip, profile, d, runRoot, steppedDown, rng, retry, crownAxis, crownRadius, leanVector, occupancy);
                Rng lenRng = rng.fork(4000 + retry);
                float candidateLen = Math.max(0.1f, profile.segmentLength() + (lenRng.nextFloat() * 2f - 1f) * profile.segmentLengthJitter());
                Vec3d candidatePos = tip.pos().add(candidateDir.scale(candidateLen));

                if (!isSegmentBlocked(tip.pos(), candidatePos, tip.parentSegmentIndex(), segments, selfOccupancy, occupancy, occ)) {
                    newDir = candidateDir;
                    newPos = candidatePos;
                    segLen = candidateLen;
                    accepted = true;
                    break;
                }
            }

            if (!accepted) {
                // Every retry ran into something solid — the tip stops here rather than being dropped.
                // Simplification versus the plan text: remaining energy is not yet redistributed to
                // siblings (that's an energy-conservation concern, test 12, a later milestone) — it's
                // just lost. Doesn't affect this milestone's structural tests (1–6, 13).
                leafAnchors.add(new LeafAnchor(tip.pos(), d));
                continue;
            }

            float twistDeg = angleDegrees(tip.dir(), newDir);
            float newRunLength = runLength + segLen;

            segments.add(new Segment(segIndex, tip.parentSegmentIndex(), tip.pos(), newPos, d,
                    tip.firstOfBranch(), twistDeg, runRoot, newRunLength));
            for (Vec3i voxel : Voxelizer.rasterize(tip.pos(), newPos)) {
                selfOccupancy.computeIfAbsent(voxel, k -> new ArrayList<>()).add(segIndex);
            }

            float newEnergy = tip.energy() - segLen * profile.costFactor(d);
            float newRelHeight = clamp01((float) (newPos.y() / estimatedMaxHeight));

            if (newEnergy <= 0f) {
                leafAnchors.add(new LeafAnchor(newPos, d));
                continue;
            }

            int forkCount = profile.sampleForkCount(d, rng);
            int sideCount = forkCount - 1;

            if (sideCount <= 0) {
                queue.add(new Tip(segIndex, newPos, newDir, d, newRunLength, runRoot, newEnergy,
                        tip.depth() + 1, newRelHeight, false));
                continue;
            }

            Diameter[] sideDiameters = new Diameter[sideCount];
            float areaSum = 0f;
            for (int i = 0; i < sideCount; i++) {
                sideDiameters[i] = pickSideDiameter(d, profile, rng);
                areaSum += areaOf(sideDiameters[i]);
            }

            float continuationEnergy = newEnergy * profile.apicalDominance();
            float sideEnergyPool = newEnergy - continuationEnergy;

            queue.add(new Tip(segIndex, newPos, newDir, d, newRunLength, runRoot, continuationEnergy,
                    tip.depth() + 1, newRelHeight, false));

            for (int i = 0; i < sideCount; i++) {
                Diameter childD = sideDiameters[i];
                float share = areaSum <= 0f ? sideEnergyPool / sideCount : sideEnergyPool * (areaOf(childD) / areaSum);
                Vec3d childDir = sideBranchDirection(newDir, profile, newRelHeight, i, sideCount, rng);
                queue.add(new Tip(segIndex, newPos, childDir, childD, 0f, newPos, share,
                        tip.depth() + 1, newRelHeight, true));
            }
        }

        return new Skeleton(seed, segments, leafAnchors);
    }

    /**
     * One candidate direction for one collision-retry attempt. Continuation tips resample fully
     * (Rule C, §5.4/§5.5) each retry; a {@code firstOfBranch} tip's direction is fixed by
     * {@code branch_angle_curve}/phyllotaxis at fork time (retry 0 uses it as-is), so later retries
     * widen a small jitter around it instead of re-deriving a branch angle.
     */
    private static Vec3d candidateDirection(Tip tip, TreeProfile profile, Diameter d, Vec3d runRoot,
            boolean steppedDown, Rng rng, int retry, Vec3d crownAxis, float crownRadius, Vec3d leanVector, OccupancyView occupancy) {
        if (tip.firstOfBranch()) {
            if (retry == 0) {
                return tip.dir();
            }
            Rng jitterRng = rng.fork(2000 + retry);
            Vec3d e1 = tip.dir().orthogonal();
            Vec3d e2 = tip.dir().cross(e1).normalize();
            double angle = jitterRng.nextDouble() * 2 * StrictMath.PI;
            double jitterDeg = 10.0 * retry;
            Vec3d axis = e1.scale(StrictMath.cos(angle)).add(e2.scale(StrictMath.sin(angle))).normalize();
            return Rotation.rotateAroundAxis(tip.dir(), axis, StrictMath.toRadians(jitterDeg)).normalize();
        }
        return sampleDirectionWithTwist(tip, profile, d, runRoot, steppedDown, rng.fork(3000 + retry), crownAxis, crownRadius, leanVector, occupancy);
    }

    /**
     * Rasterizes the candidate segment and checks every voxel (plan §6.2 point 2). A hit on a
     * voxel this same tree already owns is legal only within {@code fuse_radius} of the nearest
     * common ancestor of the two branches — that's the exception that makes forking possible at
     * all, since every fork shares voxels at the junction. Where a voxel is already shared by
     * multiple prior branches, the candidate must be fusable with *every* one of them individually,
     * not just whichever claimed it first — a voxel three separate chains happen to meet at doesn't
     * make the third one exempt from checking against the first two. Any other non-FREE,
     * non-REPLACEABLE hit blocks the candidate.
     */
    private static boolean isSegmentBlocked(Vec3d a, Vec3d b, int candidateParentIndex, List<Segment> segments,
            Map<Vec3i, List<Integer>> selfOccupancy, OccupancyView occupancy, TreeProfile.Occupancy occ) {
        for (Vec3i voxel : Voxelizer.rasterize(a, b)) {
            List<Integer> owners = selfOccupancy.get(voxel);
            if (owners != null) {
                boolean allFusable = true;
                for (int ownerIdx : owners) {
                    if (!isFusable(ownerIdx, candidateParentIndex, segments, occ.fuseRadius(), voxel)) {
                        allFusable = false;
                        break;
                    }
                }
                if (allFusable) {
                    continue;
                }
                return true;
            }
            OccupancyView.Owner owner = occupancy.at(voxel);
            if (owner instanceof OccupancyView.Owner.Free || owner instanceof OccupancyView.Owner.Replaceable) {
                continue;
            }
            if (owner instanceof OccupancyView.Owner.Self self) {
                if (isFusable(self.nodeId(), candidateParentIndex, segments, occ.fuseRadius(), voxel)) {
                    continue;
                }
                return true;
            }
            return true; // OtherTree or Solid
        }
        return false;
    }

    private static boolean isFusable(int ownerSegIndex, int candidateParentIndex, List<Segment> segments, float fuseRadius, Vec3i voxel) {
        if (candidateParentIndex == -1) {
            return false;
        }
        int ancestorIdx = Skeleton.nearestCommonAncestor(candidateParentIndex, ownerSegIndex, segments);
        if (ancestorIdx == -1) {
            return false;
        }
        Vec3d ancestorPos = segments.get(ancestorIdx).b();
        Vec3d voxelCenter = new Vec3d(voxel.x() + 0.5, voxel.y() + 0.5, voxel.z() + 0.5);
        return voxelCenter.distance(ancestorPos) <= fuseRadius;
    }

    /**
     * Rules C (twist, §5.4) and the direction-sampling bias (§5.5) for a continuation tip. A twist
     * above the hard threshold is only legal if the diameter just stepped down or the tip is still
     * within its diameter's twist window of {@code runRoot}; illegal draws resample up to 4 times,
     * then clamp to the threshold rather than being dropped.
     */
    private static Vec3d sampleDirectionWithTwist(Tip tip, TreeProfile profile, Diameter d, Vec3d runRoot,
            boolean steppedDown, Rng rng, Vec3d crownAxis, float crownRadius, Vec3d leanVector, OccupancyView occupancy) {
        Vec3d dir = tip.dir();
        Vec3d e1 = dir.orthogonal();
        Vec3d e2 = dir.cross(e1).normalize();

        Vec3d pref = preferredDirection(tip, profile, dir, crownAxis, crownRadius, leanVector, occupancy);
        double prefAzimuth = StrictMath.atan2(pref.dot(e2), pref.dot(e1));

        for (int attempt = 0; attempt <= 4; attempt++) {
            Rng draw = rng.fork(attempt);
            double azimuth = prefAzimuth + draw.nextGaussian() * profile.azimuthSpread();
            float twistDeg = Triangular.sample(profile.twist().min(), profile.twist().mode(), profile.twist().max(), draw.nextFloat());

            boolean legal = twistDeg <= profile.hardTwistThresholdDegrees()
                    || steppedDown
                    || tip.pos().distance(runRoot) <= profile.twistWindow(d);

            if (legal || attempt == 4) {
                float thetaDeg = legal ? twistDeg : profile.hardTwistThresholdDegrees();
                Vec3d axis = e1.scale(StrictMath.cos(azimuth)).add(e2.scale(StrictMath.sin(azimuth))).normalize();
                return Rotation.rotateAroundAxis(dir, axis, StrictMath.toRadians(thetaDeg)).normalize();
            }
        }
        throw new IllegalStateException("unreachable: loop always returns by the final attempt");
    }

    /**
     * Plan §5.5 step 1 — bias blended into a preferred azimuth, never directly into the sampled
     * direction. {@code crownAxis}/{@code crownRadius} are the reference column and scale the
     * radial pull/clip measures against — {@code (0, *, 0)} and {@code CrownCurve.REFERENCE_RADIUS}
     * for a single sapling (its local origin already is its own trunk axis), or a big tree's
     * footprint centroid and {@code canopyRadius} for a handoff tip (plan §8.2/§8.3) — never the
     * caller's local coordinate origin unconditionally, which for a multi-tip tree usually isn't
     * near any particular tip at all.
     */
    private static Vec3d preferredDirection(Tip tip, TreeProfile profile, Vec3d dir, Vec3d crownAxis, float crownRadius, Vec3d leanVector, OccupancyView occupancy) {
        Vec3d pos = tip.pos();
        double dx = pos.x() - crownAxis.x();
        double dz = pos.z() - crownAxis.z();
        double horizDist = StrictMath.hypot(dx, dz);
        Vec3d radial = horizDist > 1e-6
                ? new Vec3d(dx / horizDist, 0, dz / horizDist)
                : Vec3d.ZERO;

        float maxRadius = crownRadius * profile.crownCurve().radiusFactorAt(tip.relHeight());
        if (horizDist > maxRadius) {
            radial = radial.scale(-1); // past the crown's radius budget — the curve pushes tips back inward.
        }

        Vec3d biased = dir
                .add(Vec3d.UP.scale(profile.upBias()))
                .add(radial.scale(profile.outBias()))
                .add(Vec3d.DOWN.scale(profile.gravityPerDepth() * tip.depth()))
                .add(avoidanceBias(pos, dir, profile.occupancy(), occupancy))
                .add(leanVector);
        Vec3d pref = biased.normalize();
        return pref.equals(Vec3d.ZERO) ? dir : pref;
    }

    /** Plan §6.2 point 1 — probe a short cone ahead and repel the preferred direction away from obstacles before sampling. */
    private static Vec3d avoidanceBias(Vec3d pos, Vec3d dir, TreeProfile.Occupancy occ, OccupancyView occupancy) {
        Vec3d repulsion = Vec3d.ZERO;
        Vec3d e1 = dir.orthogonal();
        Vec3d e2 = dir.cross(e1).normalize();
        int probes = Math.max(1, occ.probeCount());

        for (int i = 0; i < probes; i++) {
            double angle = 2 * StrictMath.PI * i / probes;
            Vec3d probeDir = dir.scale(0.85)
                    .add(e1.scale(0.15 * StrictMath.cos(angle)))
                    .add(e2.scale(0.15 * StrictMath.sin(angle)))
                    .normalize();
            Vec3d probePos = pos.add(probeDir.scale(occ.probeDistance()));
            OccupancyView.Owner owner = occupancy.at(Vec3i.floor(probePos));

            float weight;
            if (owner instanceof OccupancyView.Owner.OtherTree) {
                weight = occ.otherTreeAvoidance();
            } else if (owner instanceof OccupancyView.Owner.Solid) {
                weight = occ.avoidanceStrength();
            } else {
                weight = 0f; // Free, Replaceable, Self — self-repulsion is handled by the hard veto's fuse exception, not steered here.
            }
            if (weight > 0f) {
                repulsion = repulsion.add(pos.sub(probePos).normalize().scale(weight));
            }
        }
        return repulsion;
    }

    /** Initial direction for a freshly forked side branch (plan §5.6): branch_angle_curve from vertical, azimuth from phyllotaxis. */
    private static Vec3d sideBranchDirection(Vec3d parentDir, TreeProfile profile, float relHeight, int index, int sideCount, Rng rng) {
        float angleFromVerticalDeg = profile.branchAngleCurve().angleAt(relHeight, rng);
        float azimuthOffsetDeg = profile.phyllotaxis().azimuthOffsetDegrees(index, sideCount);
        double baseAzimuth = StrictMath.atan2(parentDir.z(), parentDir.x());
        double azimuth = baseAzimuth + StrictMath.toRadians(azimuthOffsetDeg);
        double theta = StrictMath.toRadians(angleFromVerticalDeg);

        Vec3d horizontal = new Vec3d(StrictMath.cos(azimuth), 0, StrictMath.sin(azimuth));
        return Vec3d.UP.scale(StrictMath.cos(theta)).add(horizontal.scale(StrictMath.sin(theta))).normalize();
    }

    /** Largest legal diameter at or below {@code parentD} (self is always legal under both shipped rules), with a chance to roll one tier smaller. */
    private static Diameter pickSideDiameter(Diameter parentD, TreeProfile profile, Rng rng) {
        Diameter largest = null;
        for (Diameter candidate : Diameter.values()) {
            if (candidate.tier() <= parentD.tier() && profile.rule().legal(parentD, candidate)) {
                if (largest == null || candidate.tier() > largest.tier()) {
                    largest = candidate;
                }
            }
        }
        if (largest == null) {
            throw new IllegalStateException("no legal child diameter for parent " + parentD + " — rule.legal(d, d) must always hold");
        }
        if (rng.nextFloat() < SIDE_DIAMETER_DOWNROLL_CHANCE) {
            Diameter smaller = smallerTier(largest);
            if (smaller != null && profile.rule().legal(parentD, smaller)) {
                return smaller;
            }
        }
        return largest;
    }

    private static Diameter smallerTier(Diameter d) {
        int t = d.tier();
        return t == 0 ? null : Diameter.values()[t - 1];
    }

    private static float areaOf(Diameter d) {
        float px = d.px();
        return px * px;
    }

    private static float angleDegrees(Vec3d a, Vec3d b) {
        double dot = Math.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0);
        return (float) StrictMath.toDegrees(StrictMath.acos(dot));
    }

    private static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }
}
