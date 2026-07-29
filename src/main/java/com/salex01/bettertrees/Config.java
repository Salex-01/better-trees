package com.salex01.bettertrees;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    /** Plan §13 — {@code GrowJobManager}'s per-tick placement budget, shared across every pending job in a level. */
    public static final ModConfigSpec.IntValue BLOCKS_PER_TICK = BUILDER
            .comment("How many tree voxels GrowJobManager places per server tick (shared across all growing trees in a level).")
            .defineInRange("blocksPerTick", 96, 1, 20000);

    /** Plan §12.2 — {@code SupportSolver}'s per-tick visit budget while checking whether a cut fragment is still grounded. */
    public static final ModConfigSpec.IntValue COLLAPSE_SCAN_PER_TICK = BUILDER
            .comment("How many tree-part positions the collapse SupportSolver visits per server tick (shared across every pending scan in a level).")
            .defineInRange("collapseScanPerTick", 2048, 1, 200000);

    /** Plan §12.2's fail-safe — abort a scan and treat the still-undetermined remainder as grounded rather than ever lag the server on a pathological shape. */
    public static final ModConfigSpec.IntValue MAX_COLLAPSE_SCAN = BUILDER
            .comment("Total visits one collapse scan may spend before it aborts and treats whatever's still undetermined as grounded.")
            .defineInRange("maxCollapseScan", 8192, 1, 1000000);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemName));
    }
}
