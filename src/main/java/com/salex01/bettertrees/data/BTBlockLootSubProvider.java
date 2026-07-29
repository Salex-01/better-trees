package com.salex01.bettertrees.data;

import com.salex01.bettertrees.BetterTrees;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Every branch block drops itself (plan §3.3 acceptance: self-drops for all woods/diameters). */
public class BTBlockLootSubProvider extends BlockLootSubProvider {
    public BTBlockLootSubProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        for (Block block : getKnownBlocks()) {
            dropSelf(block);
        }
    }

    /**
     * Restricted to our own blocks — the base class's default iterates every registered block in
     * the game and demands a loot table be present in {@link #generate()}'s output for each one
     * that already declares a loot table resource key, which would blow up on every vanilla block
     * we didn't touch.
     */
    @Override
    protected Iterable<Block> getKnownBlocks() {
        return BetterTrees.BLOCKS.getEntries().stream()
                .<Block>map(DeferredHolder::get)
                .toList();
    }
}
