package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Plan §12.3 {@code DROP} mode — a batch of confirmed-floating positions still waiting to be broken
 * (with drops) over subsequent ticks. Unlike {@code GrowJob}, there's no meaningful placement order
 * to preserve here (every position is independently doomed, none depends on another still existing),
 * so this is just a mutable position list rather than a cursor over a fixed order — positions are
 * removed as they're actually broken, and one left unloaded this tick simply stays for the next
 * without blocking its siblings the way {@code GrowJobManager} deliberately blocks a whole job on an
 * unloaded voxel (that job cares about placement order; this one doesn't).
 */
public final class CollapseDropJob {
    public static final Codec<CollapseDropJob> CODEC =
            BlockPos.CODEC.listOf().xmap(CollapseDropJob::new, CollapseDropJob::positionsCopy);

    private final List<BlockPos> positions;

    public CollapseDropJob(List<BlockPos> positions) {
        this.positions = new ArrayList<>(positions);
    }

    public List<BlockPos> positions() {
        return positions;
    }

    private List<BlockPos> positionsCopy() {
        return List.copyOf(positions);
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }
}
