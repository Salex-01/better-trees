package com.salex01.bettertrees.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Plan §13 — fired on {@code NeoForge.EVENT_BUS} once per accepted growth (single sapling or
 * cluster), after the obstruction-ratio check passes but before any block is written or a {@link
 * com.salex01.bettertrees.world.GrowJob} is queued. Claim/protection mods cancel this the same way
 * they'd cancel vanilla's {@code SaplingGrowTreeEvent} — a cancelled event leaves the world
 * untouched, same "consumes nothing" contract as an obstruction-ratio refusal.
 */
public final class BetterTreeGrowEvent extends Event implements ICancellableEvent {
    private final ServerLevel level;
    private final BlockPos origin;
    private final String species;

    public BetterTreeGrowEvent(ServerLevel level, BlockPos origin, String species) {
        this.level = level;
        this.origin = origin;
        this.species = species;
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos origin() {
        return origin;
    }

    public String species() {
        return species;
    }
}
