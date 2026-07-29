package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// NO MINECRAFT IMPORTS — see plan §1.
/**
 * Plan §12.2 — lockstep multi-source BFS that decides which of a cut tree's remaining parts are
 * still grounded and which are floating (and therefore have to drop, plan §12.3). One frontier per
 * seed (the face-neighbours of a removed position that are themselves tree parts); every frontier
 * expands exactly one position per round, round-robin, so the total work to resolve any one
 * frontier is bounded by <em>that frontier's own component size</em>, never by the size of the
 * whole tree — snapping a twig off a giant only ever costs a handful of visits, because the twig's
 * tiny frontier exhausts long before a trunk-sized frontier could get anywhere. Frontiers that meet
 * merge (union-find); a frontier that visits an anchored position is grounded and drops out of the
 * race (its queue is dropped, no further exploration needed); a frontier whose queue empties
 * without ever grounding is a floating component.
 *
 * <p>{@link ShapeQuery} is deliberately a lazy per-position membership test, not a precomputed
 * {@code Set<Vec3i>} — that laziness is <em>the entire point</em> of the milestone: the world layer
 * must never flood-fill an entire standing tree just to hand this solver a finished shape, since
 * that alone would already cost O(size of the whole tree) and defeat the "twig is instant" property
 * this class exists to deliver. Tests wrap a concrete {@code TreeBlueprint}/{@code Set<Vec3i>} in a
 * trivial lambda; the world layer wraps a live tag check instead (see {@code world/CollapseJobManager}).
 */
public final class SupportSolver {
    private SupportSolver() {}

    /** Lazy "is this position still part of the tree" test — see class javadoc for why this can't be a Set. */
    @FunctionalInterface
    public interface ShapeQuery {
        boolean isPart(Vec3i pos);
    }

    /**
     * @param floating every position whose frontier conclusively exhausted without ever reaching an
     *                 anchor. If {@code aborted}, any frontier still unresolved at the moment the
     *                 fail-safe tripped is left out entirely (plan §12.2's "treat everything as
     *                 grounded") — but a fragment that had <em>already</em> resolved floating earlier
     *                 in the same scan is still included, never retracted; see {@link Scan#floating()}.
     * @param aborted  {@code true} if the fail-safe (plan's {@code max_collapse_scan}) tripped
     * @param visited  total positions popped and processed across every frontier — the same count {@code max_collapse_scan} caps
     */
    public record Result(Set<Vec3i> floating, boolean aborted, int visited) {}

    /** Runs a {@link Scan} to completion in a single call — for tests, and for any live scan small enough to finish inside one tick's budget. */
    public static Result solve(ShapeQuery shape, List<Vec3i> seeds, Predicate<Vec3i> anchored, int maxVisits) {
        Scan scan = start(shape, seeds, anchored, maxVisits);
        scan.step(Integer.MAX_VALUE);
        return new Result(scan.floating(), scan.aborted(), scan.totalVisits());
    }

    public static Scan start(ShapeQuery shape, List<Vec3i> seeds, Predicate<Vec3i> anchored, int maxVisits) {
        return new Scan(shape, seeds, anchored, maxVisits);
    }

    /**
     * Resumable solver state — plan §12.2's "budget {@code collapse_scan_per_tick}... resuming
     * across ticks with saved frontier state." Deliberately in-memory only, not itself persisted
     * across a server restart: nothing this class does has a world side effect (no block is broken
     * until §12.3's execution phase runs against the finished {@link #floating()} result), so a
     * restart mid-scan is safe to just re-{@link #start} from scratch on the next tick — cheap
     * relative to the correctness/complexity cost of serializing live union-find + queue state.
     */
    public static final class Scan {
        private final ShapeQuery shape;
        private final Predicate<Vec3i> anchored;
        private final int maxVisits;

        private final int[] parent;
        private final Map<Integer, Deque<Vec3i>> queueByRoot = new HashMap<>();
        private final Set<Integer> grounded = new HashSet<>();
        /**
         * Roots whose queue emptied without ever grounding — final and permanent the moment a root
         * is added (a merge can only ever happen while both sides still have queued work, so a truly
         * exhausted root can never later gain a connection to ground or to another frontier; see the
         * class javadoc's "twig dies before trunk finishes" argument). This is what makes {@link
         * #floating()} safe to call incrementally, mid-scan: a fragment that resolves floating early
         * shows up immediately and permanently, without waiting for a slower sibling frontier from the
         * same cut (e.g. the trunk-side seed of the same removal) to finish proving it's grounded —
         * the mechanism plan §12.2 means by "cutting a twig is instant" even off a giant tree.
         */
        private final Set<Integer> exhaustedRoots = new HashSet<>();
        private final Map<Vec3i, Integer> owner = new HashMap<>();

        private int totalVisits;
        private boolean aborted;

        private Scan(ShapeQuery shape, List<Vec3i> seeds, Predicate<Vec3i> anchored, int maxVisits) {
            this.shape = shape;
            this.anchored = anchored;
            this.maxVisits = maxVisits;

            List<Vec3i> distinctSeeds = new ArrayList<>(new LinkedHashSet<>(seeds));
            this.parent = new int[distinctSeeds.size()];
            for (int i = 0; i < distinctSeeds.size(); i++) {
                parent[i] = i;
                Deque<Vec3i> q = new ArrayDeque<>();
                q.add(distinctSeeds.get(i));
                queueByRoot.put(i, q);
                owner.putIfAbsent(distinctSeeds.get(i), i);
            }
        }

        /**
         * Advances up to {@code budget} total visits, round-robin across every still-active
         * frontier. Returns {@code true} once the whole scan is finished (every frontier grounded
         * or exhausted, or the fail-safe aborted it) — callers that get {@code false} back should
         * call {@link #step} again on a later tick with a fresh budget.
         */
        public boolean step(int budget) {
            if (aborted) {
                return true;
            }
            int used = 0;
            while (used < budget) {
                List<Integer> activeRoots = currentActiveRoots();
                if (activeRoots.isEmpty()) {
                    return true;
                }
                for (int root : activeRoots) {
                    if (used >= budget) {
                        break;
                    }
                    int cur = find(root);
                    if (grounded.contains(cur)) {
                        continue; // grounded by an earlier merge this same round
                    }
                    Deque<Vec3i> queue = queueByRoot.get(cur);
                    if (queue == null || queue.isEmpty()) {
                        continue; // exhausted (floating) already, or absorbed into another root this round
                    }

                    Vec3i pos = queue.poll();
                    used++;
                    totalVisits++;
                    if (totalVisits > maxVisits) {
                        aborted = true;
                        return true;
                    }

                    if (anchored.test(pos)) {
                        grounded.add(cur);
                        queueByRoot.get(cur).clear(); // drop out of the race — no further exploration needed
                        continue;
                    }

                    for (Vec3i neighbor : pos.faceNeighbors()) {
                        if (!shape.isPart(neighbor)) {
                            continue;
                        }
                        Integer existingOwner = owner.get(neighbor);
                        if (existingOwner == null) {
                            owner.put(neighbor, cur);
                            queueByRoot.get(find(cur)).add(neighbor);
                        } else {
                            int otherRoot = find(existingOwner);
                            int myRoot = find(cur);
                            if (otherRoot != myRoot) {
                                union(myRoot, otherRoot);
                            }
                        }
                    }

                    int finalRoot = find(cur);
                    if (!grounded.contains(finalRoot) && queueByRoot.get(finalRoot).isEmpty()) {
                        exhaustedRoots.add(finalRoot);
                    }
                }
            }
            return isDone();
        }

        public boolean isDone() {
            return aborted || currentActiveRoots().isEmpty();
        }

        public boolean aborted() {
            return aborted;
        }

        public int totalVisits() {
            return totalVisits;
        }

        /**
         * Every position whose frontier has <em>conclusively</em> resolved as floating so far — safe
         * to call at any point, not just once {@link #isDone()}. Positions belonging to a still-active
         * (undetermined) frontier are simply absent until that frontier itself resolves; nothing here
         * is ever later retracted. A caller ticking this across several ticks (the world layer) can
         * therefore act on a fragment the moment it appears, rather than waiting for every frontier
         * from the same cut to finish — including one that later hits the {@code max_collapse_scan}
         * fail-safe and aborts, which only leaves its own still-undetermined territory untouched
         * ("treated as grounded"), never undoes an earlier, already-confirmed floating fragment.
         */
        public Set<Vec3i> floating() {
            Set<Vec3i> result = new HashSet<>();
            for (Map.Entry<Vec3i, Integer> entry : owner.entrySet()) {
                if (exhaustedRoots.contains(find(entry.getValue()))) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        private List<Integer> currentActiveRoots() {
            Set<Integer> roots = new LinkedHashSet<>();
            for (int i = 0; i < parent.length; i++) {
                int root = find(i);
                if (grounded.contains(root)) {
                    continue;
                }
                Deque<Vec3i> queue = queueByRoot.get(root);
                if (queue != null && !queue.isEmpty()) {
                    roots.add(root);
                }
            }
            return new ArrayList<>(roots);
        }

        private int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        private void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA == rootB) {
                return;
            }
            int survivor = Math.min(rootA, rootB);
            int absorbed = Math.max(rootA, rootB);
            parent[absorbed] = survivor;

            Deque<Vec3i> survivorQueue = queueByRoot.remove(survivor);
            Deque<Vec3i> absorbedQueue = queueByRoot.remove(absorbed);
            boolean isGrounded = grounded.remove(survivor) | grounded.remove(absorbed);
            if (isGrounded) {
                grounded.add(survivor);
                queueByRoot.put(survivor, new ArrayDeque<>()); // grounded — drop out of the race entirely
            } else {
                survivorQueue.addAll(absorbedQueue);
                queueByRoot.put(survivor, survivorQueue);
            }
        }
    }
}
