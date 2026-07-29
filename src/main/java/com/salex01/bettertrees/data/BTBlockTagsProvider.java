package com.salex01.bettertrees.data;

import com.salex01.bettertrees.BetterTrees;
import com.salex01.bettertrees.registry.ModTags;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagAppender;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

/**
 * Block tags for every branch (plan §3.3). Branches go straight into
 * {@code #minecraft:logs_that_burn} rather than {@code #minecraft:logs} directly — {@code logs}
 * itself is defined as {@code logs_that_burn} plus the two nether stem tags (verified in the
 * vanilla data pack), so membership in the former already implies the latter. That's what lets
 * vanilla's own {@code LeavesBlock#getDistanceAt} (which tests {@code BlockTags.LOGS}) treat our
 * branches as valid leaf support for free — no {@code BetterLeavesBlock} needed until §12.
 */
public class BTBlockTagsProvider extends BlockTagsProvider {
    public BTBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, BetterTrees.MODID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        TagAppender<Block, Block> axeMineable = tag(BlockTags.MINEABLE_WITH_AXE);
        TagAppender<Block, Block> burns = tag(BlockTags.LOGS_THAT_BURN);
        TagAppender<Block, Block> branches = tag(ModTags.BRANCHES);
        TagAppender<Block, Block> branch4 = tag(ModTags.BRANCH_4);
        TagAppender<Block, Block> branch8 = tag(ModTags.BRANCH_8);
        TagAppender<Block, Block> branch12 = tag(ModTags.BRANCH_12);
        TagAppender<Block, Block> saplings = tag(BlockTags.SAPLINGS);

        BetterTrees.BRANCHES.values().forEach(set -> {
            Block d12 = set.d12().get();
            Block d8 = set.d8().get();
            Block d4 = set.d4().get();

            axeMineable.add(d12, d8, d4);
            burns.add(d12, d8, d4);
            branches.add(d12, d8, d4);
            branch12.add(d12);
            branch8.add(d8);
            branch4.add(d4);
            saplings.add(set.sapling().get());
        });

        tag(ModTags.TREE_PARTS).addTag(ModTags.BRANCHES).addTag(BlockTags.LOGS).addTag(BlockTags.LEAVES);

        // First pass at "the ground" for §12's support solver — widened/tuned when that solver
        // actually walks it. Not consumed by anything yet.
        tag(ModTags.TREE_ANCHOR)
                .addTag(BlockTags.DIRT)
                .addTag(BlockTags.BASE_STONE_OVERWORLD)
                .add(Blocks.SAND)
                .add(Blocks.RED_SAND)
                .add(Blocks.GRAVEL)
                .add(Blocks.FARMLAND)
                .add(Blocks.DIRT_PATH);

        // Vanilla's own worldgen-replaceable tag already covers grass, flowers, snow layers,
        // vines and the rest of §6.1's "growth consumes these freely" list — no need to hand-curate.
        TagAppender<Block, Block> replaceable = tag(ModTags.TREE_REPLACEABLE).addTag(BlockTags.REPLACEABLE);
        // Our own saplings join the tag too, so a tree's trunk base can consume the sapling it grew
        // from exactly like any other replaceable block, with no special-casing in the placer.
        BetterTrees.BRANCHES.values().forEach(set -> replaceable.add(set.sapling().get()));
    }
}
