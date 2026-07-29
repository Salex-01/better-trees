package com.salex01.bettertrees.generator.skeleton;

import java.util.ArrayList;
import java.util.List;

/**
 * The generated tree as a navigable, index-stable segment tree (plan §5) — not a flat list, so
 * tests can walk parent chains (nearest-common-ancestor for the {@code fuse_radius} exception in
 * §6.2/test 13) after the fact without re-simulating growth.
 */
public final class Skeleton {
    private final long seed;
    private final List<Segment> segments;
    private final List<LeafAnchor> leafAnchors;

    public Skeleton(long seed, List<Segment> segments, List<LeafAnchor> leafAnchors) {
        this.seed = seed;
        this.segments = List.copyOf(segments);
        this.leafAnchors = List.copyOf(leafAnchors);
    }

    public long seed() {
        return seed;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** Where tips terminated (energy exhausted, or a D4 run hit its cap) — LeafPlanner's raw material. */
    public List<LeafAnchor> leafAnchors() {
        return leafAnchors;
    }

    public Segment segment(int index) {
        return segments.get(index);
    }

    public int size() {
        return segments.size();
    }

    /** The chain of segment indices from {@code index} up to (and including) the trunk base, root first. */
    public List<Integer> ancestryToRoot(int index) {
        List<Integer> chain = new ArrayList<>();
        int i = index;
        while (i != -1) {
            chain.add(i);
            i = segments.get(i).parentIndex();
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    /** Nearest common ancestor index of two segments, or -1 if their only common ancestor is "before the trunk base". */
    public int nearestCommonAncestor(int a, int b) {
        return nearestCommonAncestor(a, b, segments);
    }

    /**
     * Same as {@link #nearestCommonAncestor(int, int)}, but usable against an in-progress segment
     * list during generation (plan §6.2's {@code fuse_radius} exception), before a {@link Skeleton}
     * exists to wrap it.
     */
    public static int nearestCommonAncestor(int a, int b, List<Segment> segments) {
        List<Integer> chainA = new ArrayList<>();
        int i = a;
        while (i != -1) {
            chainA.add(i);
            i = segments.get(i).parentIndex();
        }
        java.util.Set<Integer> setA = new java.util.HashSet<>(chainA);
        int j = b;
        while (j != -1) {
            if (setA.contains(j)) {
                return j;
            }
            j = segments.get(j).parentIndex();
        }
        return -1;
    }
}
