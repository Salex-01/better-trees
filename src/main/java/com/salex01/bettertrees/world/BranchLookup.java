package com.salex01.bettertrees.world;

import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.registry.ModTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The only Minecraft-aware diameter reader (plan §2) — everywhere else that needs a state's
 * diameter should go through this rather than re-deriving it, so third-party woods that only add
 * themselves to {@code #minecraft:logs} still resolve to {@link Diameter#D16} for free.
 */
public final class BranchLookup {
    private BranchLookup() {}

    public static @Nullable Diameter of(BlockState state) {
        if (state.is(ModTags.BRANCH_4)) {
            return Diameter.D4;
        }
        if (state.is(ModTags.BRANCH_8)) {
            return Diameter.D8;
        }
        if (state.is(ModTags.BRANCH_12)) {
            return Diameter.D12;
        }
        if (state.is(BlockTags.LOGS)) {
            return Diameter.D16;
        }
        return null;
    }
}
