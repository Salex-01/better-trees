package com.salex01.bettertrees.registry;

import com.salex01.bettertrees.BetterTrees;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Mod-namespaced block tag keys (plan §3.3, §12). {@code TREE_PARTS}/{@code TREE_ANCHOR} exist
 * from this milestone on so datagen can populate them, but nothing reads them yet — that's §12's
 * support solver, a later milestone.
 */
public final class ModTags {
    private ModTags() {}

    /** Every branch block of every diameter, every species. */
    public static final TagKey<Block> BRANCHES = block("branches");
    public static final TagKey<Block> BRANCH_4 = block("branch_4");
    public static final TagKey<Block> BRANCH_8 = block("branch_8");
    public static final TagKey<Block> BRANCH_12 = block("branch_12");
    /** Branches + vanilla logs + vanilla leaves — "is this block part of a tree" (§12 collapse). */
    public static final TagKey<Block> TREE_PARTS = block("tree_parts");
    /** What counts as solid ground for §12's support solver — dirt/stone family blocks. */
    public static final TagKey<Block> TREE_ANCHOR = block("tree_anchor");
    /** Grass, flowers, snow, litter, vines, our own saplings — growth consumes these freely when {@code Occupancy.breakReplaceable()} allows it (plan §6.1's {@code Owner.Replaceable}). */
    public static final TagKey<Block> TREE_REPLACEABLE = block("tree_replaceable");

    private static TagKey<Block> block(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(BetterTrees.MODID, path));
    }
}
