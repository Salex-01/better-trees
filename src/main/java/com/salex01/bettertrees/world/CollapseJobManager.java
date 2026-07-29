package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.salex01.bettertrees.Config;
import com.salex01.bettertrees.block.CutBranchBlock;
import com.salex01.bettertrees.generator.SupportSolver;
import com.salex01.bettertrees.generator.math.Vec3i;
import com.salex01.bettertrees.registry.ModTags;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Plan §12.1's {@code CollapseQueue} + §12.2's ticked {@link SupportSolver} runner + §12.3's {@code
 * DROP} execution, one instance per {@link ServerLevel} (same {@code SavedData} scoping as {@link
 * GrowJobManager}). Never runs a scan inline from the triggering event — {@link
 * CollapseTrigger}s are queued here and only processed on the next {@link #tick}.
 *
 * <p>Two persisted lists mirror {@code GrowJobManager}'s restart-safety story, but for a narrower
 * reason: nothing in the <em>scanning</em> phase has a world side effect (no block changes until a
 * fragment is confirmed floating), so losing an in-progress scan's frontier state to a restart is
 * safe to just redo from scratch — {@link #activeScans} is deliberately NOT persisted. What *is*
 * persisted is {@code pendingScans} (so a trigger that hadn't started scanning yet, or was still
 * mid-scan, is never silently dropped by a restart — it just restarts that scan from zero) and
 * {@code dropJobs} (confirmed-floating positions genuinely need to survive a restart, or a felled
 * fragment would hang in the world forever after one).
 */
public final class CollapseJobManager extends SavedData {
    public static final SavedDataType<CollapseJobManager> TYPE =
            new SavedDataType<>("bettertrees_collapse_jobs", CollapseJobManager::new, codec());

    private static Codec<CollapseJobManager> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                CollapseTrigger.CODEC.listOf().fieldOf("pending_scans").forGetter(m -> List.copyOf(m.pendingScans)),
                CollapseDropJob.CODEC.listOf().fieldOf("drop_jobs").forGetter(m -> List.copyOf(m.dropJobs))
        ).apply(instance, CollapseJobManager::new));
    }

    /** One in-flight {@link SupportSolver.Scan} plus the exact seed list it started from (needed once it finishes, to know which seeds survived and deserve a {@code CUT} face — see {@link #finishScan}) and the positions already handed off to a {@link CollapseDropJob} so a later, still-running frontier from the same trigger never gets double-queued. */
    private record ActiveScan(SupportSolver.Scan scan, List<Vec3i> seeds, Set<Vec3i> alreadyQueued) {}

    private final List<CollapseTrigger> pendingScans;
    private final List<CollapseDropJob> dropJobs;
    private final Map<CollapseTrigger, ActiveScan> activeScans = new HashMap<>();

    public CollapseJobManager() {
        this(List.of(), List.of());
    }

    private CollapseJobManager(List<CollapseTrigger> pendingScans, List<CollapseDropJob> dropJobs) {
        this.pendingScans = new ArrayList<>(pendingScans);
        this.dropJobs = new ArrayList<>(dropJobs);
    }

    public static CollapseJobManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Plan §12.1 — schedule a collapse check; never runs anything inline. Deduplicated against both the pending and the already-running queue. */
    public void schedule(CollapseTrigger trigger) {
        if (pendingScans.contains(trigger) || activeScans.containsKey(trigger)) {
            return;
        }
        pendingScans.add(trigger);
        setDirty();
    }

    /**
     * @return visits actually spent scanning this tick, so a caller ticking multiple levels can share one budget across them (mirrors {@code GrowJobManager.tick}'s return contract)
     */
    public int tick(ServerLevel level, int scanBudget, int dropBudget) {
        int scanUsed = tickScans(level, scanBudget);
        tickDrops(level, dropBudget);
        return scanUsed;
    }

    private int tickScans(ServerLevel level, int scanBudget) {
        if (scanBudget <= 0) {
            return 0;
        }
        int used = 0;
        boolean dirty = false;
        List<CollapseTrigger> stillPending = new ArrayList<>();
        for (CollapseTrigger trigger : pendingScans) {
            if (used >= scanBudget) {
                stillPending.add(trigger);
                continue;
            }
            ActiveScan active = activeScans.get(trigger);
            if (active == null) {
                List<Vec3i> seeds = computeSeeds(level, trigger);
                if (seeds.isEmpty()) {
                    dirty = true; // nothing above/around the removal — resolved trivially, dropped from the queue
                    continue;
                }
                SupportSolver.Scan scan = SupportSolver.start(new LevelShape(level), seeds,
                        pos -> isAnchored(level, pos), Config.MAX_COLLAPSE_SCAN.getAsInt());
                active = new ActiveScan(scan, seeds, new HashSet<>());
                activeScans.put(trigger, active);
            }

            int before = active.scan().totalVisits();
            active.scan().step(scanBudget - used);
            used += active.scan().totalVisits() - before;

            // Harvest newly-confirmed-floating positions THIS tick, even if the scan as a whole isn't
            // done yet — this is what makes "cutting a twig is instant" hold even when the same
            // removal also seeded a much slower frontier (e.g. proving the trunk side is grounded on
            // a giant tree): the twig's own fragment doesn't wait on its sibling.
            Set<Vec3i> newlyFloating = new HashSet<>(active.scan().floating());
            newlyFloating.removeAll(active.alreadyQueued());
            if (!newlyFloating.isEmpty()) {
                List<BlockPos> worldPositions = new ArrayList<>(newlyFloating.size());
                for (Vec3i v : newlyFloating) {
                    worldPositions.add(toBlockPos(v));
                }
                dropJobs.add(new CollapseDropJob(worldPositions));
                active.alreadyQueued().addAll(newlyFloating);
                dirty = true;
            }

            if (active.scan().isDone()) {
                finishScan(level, trigger, active);
                activeScans.remove(trigger);
                dirty = true;
            } else {
                stillPending.add(trigger);
            }
        }
        pendingScans.clear();
        pendingScans.addAll(stillPending);
        if (dirty) {
            setDirty();
        }
        return used;
    }

    /** Plan §3.1/§12.2 — every seed that survived (never went floating) gets its {@code cut_<dir>} face set, facing back toward the removal, so the stub reads as freshly sawn rather than naturally grown. */
    private void finishScan(ServerLevel level, CollapseTrigger trigger, ActiveScan active) {
        for (Vec3i seed : active.seeds()) {
            if (active.alreadyQueued().contains(seed)) {
                continue; // this seed's own fragment went floating — it's being dropped, not marked
            }
            markCut(level, toBlockPos(seed), trigger.pos());
        }
    }

    private static void markCut(ServerLevel level, BlockPos survivor, BlockPos removed) {
        BlockState state = level.getBlockState(survivor);
        if (!(state.getBlock() instanceof CutBranchBlock)) {
            return; // D4/D16 have no CUT property (plan §3)
        }
        for (Direction dir : Direction.values()) {
            if (survivor.relative(dir).equals(removed)) {
                BooleanProperty cutProperty = CutBranchBlock.CUT_BY_DIRECTION.get(dir);
                if (!state.getValue(cutProperty)) {
                    level.setBlock(survivor, state.setValue(cutProperty, true), Block.UPDATE_CLIENTS);
                }
                return;
            }
        }
    }

    private void tickDrops(ServerLevel level, int budget) {
        if (budget <= 0) {
            return;
        }
        int used = 0;
        boolean dirty = false;
        for (Iterator<CollapseDropJob> jobIt = dropJobs.iterator(); jobIt.hasNext(); ) {
            CollapseDropJob job = jobIt.next();
            for (Iterator<BlockPos> posIt = job.positions().iterator(); posIt.hasNext() && used < budget; ) {
                BlockPos pos = posIt.next();
                if (!level.isLoaded(pos)) {
                    continue; // left in the job, retried next tick — no ordering constraint to protect (see CollapseDropJob)
                }
                used++;
                dirty = true;
                // Re-validate before breaking anything — plan §12.2's explicit caution, since blocks
                // may have changed between the scan and this tick (a player could have replaced it).
                if (level.getBlockState(pos).is(ModTags.TREE_PARTS)) {
                    level.destroyBlock(pos, true);
                }
                posIt.remove();
            }
            if (job.isEmpty()) {
                jobIt.remove();
            }
            if (used >= budget) {
                break;
            }
        }
        if (dirty) {
            setDirty();
        }
    }

    private static List<Vec3i> computeSeeds(ServerLevel level, CollapseTrigger trigger) {
        List<Vec3i> seeds = new ArrayList<>();
        if (trigger.groundRemoval()) {
            BlockPos above = trigger.pos().above();
            if (level.isLoaded(above) && level.getBlockState(above).is(ModTags.TREE_PARTS)) {
                seeds.add(toVec3i(above));
            }
        } else {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = trigger.pos().relative(dir);
                if (level.isLoaded(neighbor) && level.getBlockState(neighbor).is(ModTags.TREE_PARTS)) {
                    seeds.add(toVec3i(neighbor));
                }
            }
        }
        return seeds;
    }

    /** Never force-loads a neighbouring chunk just to check for ground — an unloaded neighbour simply doesn't count as an anchor this check. */
    private static boolean isAnchored(ServerLevel level, Vec3i pos) {
        BlockPos worldPos = toBlockPos(pos);
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = worldPos.relative(dir);
            if (level.isLoaded(neighbor) && level.getBlockState(neighbor).is(ModTags.TREE_ANCHOR)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Plan §12.2's laziness requirement — never backed by a precomputed flood-fill of the whole
     * tree, just a live per-position tag check (see {@link SupportSolver}'s own javadoc for why).
     *
     * <p>An unloaded position reads as <em>part of the tree</em>, not excluded — the opposite of
     * {@link #isAnchored}'s unloaded-neighbour guard, and deliberately so: {@code isAnchored}
     * failing open (an unloaded anchor candidate just doesn't count) only risks under-detecting
     * grounding, which the fail-safe already treats safely ("unresolved" defaults to grounded
     * anyway). But {@code isPart} failing <em>closed</em> on an unloaded position would mean a
     * fragment that's genuinely still connected to ground purely through territory the server can't
     * currently see gets treated as if that connection doesn't exist — a false "floating" verdict
     * that goes on to actually destroy still-grounded wood. Reading unloaded as "part" instead just
     * costs a few extra visits exploring into territory that might not even be a tree before {@code
     * max_collapse_scan} catches it and, per its own §12.2 semantics, treats the unresolved result as
     * grounded — the same safe direction, reached a different way.
     */
    private record LevelShape(ServerLevel level) implements SupportSolver.ShapeQuery {
        @Override
        public boolean isPart(Vec3i pos) {
            BlockPos worldPos = toBlockPos(pos);
            return !level.isLoaded(worldPos) || level.getBlockState(worldPos).is(ModTags.TREE_PARTS);
        }
    }

    private static Vec3i toVec3i(BlockPos pos) {
        return new Vec3i(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos toBlockPos(Vec3i pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
