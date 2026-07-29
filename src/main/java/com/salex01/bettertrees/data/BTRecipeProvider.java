package com.salex01.bettertrees.data;

import com.salex01.bettertrees.BetterTrees;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/**
 * Branch-to-planks recipes (plan §3.3), scaled by cross-sectional area so a forest of big trees
 * isn't a wood exploit next to felling real logs. Vanilla: 1 log (16px x 16px, area 256) makes 4
 * planks — a yield rate of 1 plank per 64 area. Applied to each diameter and rounded to the
 * smallest integer ratio:
 * <ul>
 *   <li>D4 (area 16): 16/64 = 0.25 planks/branch &rarr; 4 branches &rarr; 1 plank</li>
 *   <li>D8 (area 64): 64/64 = 1 plank/branch &rarr; 1 branch &rarr; 1 plank</li>
 *   <li>D12 (area 144): 144/64 = 2.25 planks/branch &rarr; 4 branches &rarr; 9 planks</li>
 * </ul>
 * Area scaling means D4 and D12 have no exact single-branch output, which playtesting showed
 * reads as "the recipe is broken" rather than "gather more branches" — a single branch in the
 * grid just does nothing, with no in-game hint that 4 are needed. Single-branch convenience
 * recipes cover that: D4 &rarr; 1 stick (a D4 twig is basically a stick already) and D12 &rarr; 2
 * planks (a discount versus the 4-for-9 bulk rate, on purpose — it's the "I only have one" option,
 * not the efficient one). D8 already has a natural 1-for-1, so it needs no separate recipe.
 */
public class BTRecipeProvider extends RecipeProvider {
    protected BTRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        BetterTrees.BRANCHES.values().forEach(set -> {
            branchToItem(set.d4().get(), set.planks(), 4, 1, "planks", "");
            branchToItem(set.d8().get(), set.planks(), 1, 1, "planks", "");
            branchToItem(set.d12().get(), set.planks(), 4, 9, "planks", "");

            branchToItem(set.d4().get(), Items.STICK, 1, 1, "sticks", "");
            branchToItem(set.d12().get(), set.planks(), 1, 2, "planks", "_single");
        });
    }

    private void branchToItem(Block branch, ItemLike result, int branchCount, int resultCount, String group, String idSuffix) {
        // save(RecipeOutput, String) parses the id with Identifier.parse, which defaults an
        // unqualified string to the "minecraft" namespace — every recipe would land under
        // data/minecraft/recipe/ instead of ours. Build the namespaced key explicitly. idSuffix
        // disambiguates recipes that would otherwise collide on the default
        // "<result>_from_<branch>" name, e.g. D12's two different plank recipes.
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE,
                Identifier.fromNamespaceAndPath(BetterTrees.MODID, getConversionRecipeName(result, branch) + idSuffix));
        this.shapeless(RecipeCategory.BUILDING_BLOCKS, result, resultCount)
                .requires(branch, branchCount)
                .group(group)
                .unlockedBy(getHasName(branch), this.has(branch))
                .save(this.output, key);
    }

    public static class Runner extends RecipeProvider.Runner {
        public Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new BTRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "Better Trees Recipes";
        }
    }
}
