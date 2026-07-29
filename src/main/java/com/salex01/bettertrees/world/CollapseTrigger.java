package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * Plan §12.1 — a queued "something was cut here, go check what's still grounded" entry. Deliberately
 * tiny and re-derivable: {@link CollapseJobManager} recomputes the actual seed positions fresh from
 * live world state when it starts the scan, rather than baking them in here, so this record only
 * needs to remember *where* the removal happened and *which* of §12.1's two trigger shapes it was —
 * both cheap to persist, and safe to re-derive even if some time passed between scheduling and
 * actually running the scan.
 *
 * @param pos           the position that was removed
 * @param groundRemoval {@code true} if {@code pos} was a {@code #bettertrees:tree_anchor} block (the
 *                      seed is then just {@code pos.above()}, if that's still a tree part); {@code
 *                      false} if {@code pos} was itself a {@code #bettertrees:tree_parts} block (the
 *                      seeds are then every remaining face-neighbour that's a tree part)
 */
public record CollapseTrigger(BlockPos pos, boolean groundRemoval) {
    public static final Codec<CollapseTrigger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(CollapseTrigger::pos),
            Codec.BOOL.fieldOf("ground_removal").forGetter(CollapseTrigger::groundRemoval)
    ).apply(instance, CollapseTrigger::new));
}
