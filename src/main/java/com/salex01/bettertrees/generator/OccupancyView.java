package com.salex01.bettertrees.generator;

import com.salex01.bettertrees.generator.math.Vec3i;

/**
 * Read-only occupancy query passed into growth (plan §6.1) — the world layer implements it, the
 * generator stays pure. {@code Vec3i} here is ours (see {@link Vec3i}'s own javadoc), never
 * Minecraft's.
 */
public interface OccupancyView {
    Owner at(Vec3i pos);

    /** Always {@link Owner#free()} everywhere — the trivial view for tests that don't exercise occupancy. */
    OccupancyView ALWAYS_FREE = pos -> Owner.free();

    sealed interface Owner {
        record Free() implements Owner {}
        /** {@code nodeId} is a skeleton-local node index — not {@code life/}'s NodeId, a separate later concept (plan §19). */
        record Self(int nodeId) implements Owner {}
        record OtherTree() implements Owner {}
        /** Grass, flowers, snow, litter, vines — {@code #bettertrees:tree_replaceable} — growth consumes these freely. */
        record Replaceable() implements Owner {}
        /** Everything else, including player builds. */
        record Solid() implements Owner {}

        Free FREE = new Free();
        OtherTree OTHER_TREE = new OtherTree();
        Replaceable REPLACEABLE = new Replaceable();
        Solid SOLID = new Solid();

        static Owner free() { return FREE; }
        static Owner self(int nodeId) { return new Self(nodeId); }
        static Owner otherTree() { return OTHER_TREE; }
        static Owner replaceable() { return REPLACEABLE; }
        static Owner solid() { return SOLID; }
    }
}
