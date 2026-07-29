package com.salex01.bettertrees;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.mojang.logging.LogUtils;
import com.salex01.bettertrees.block.BetterSaplingBlock;
import com.salex01.bettertrees.block.CutBranchBlock;
import com.salex01.bettertrees.block.LeafyBranchBlock;
import com.salex01.bettertrees.data.BTBlockLootSubProvider;
import com.salex01.bettertrees.data.BTBlockTagsProvider;
import com.salex01.bettertrees.data.BTModelProvider;
import com.salex01.bettertrees.data.BTRecipeProvider;
import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.registry.ModTags;
import com.salex01.bettertrees.world.CollapseJobManager;
import com.salex01.bettertrees.world.CollapseTrigger;
import com.salex01.bettertrees.world.GrowJobManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.Set;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BetterTrees.MODID)
public class BetterTrees {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "bettertrees";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "bettertrees" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "bettertrees" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "bettertrees" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * A vanilla wood family branches ride on: log texture source, the full-bark "wood" block the
     * D16 trunk tier places (plan §2/§13 — bark on all six faces regardless of the trunk's actual
     * 3D direction, since D16 has no connection-based geometry to orient rings correctly the way
     * D4/D8/D12 branches do), the plank this species' branches craft down into, and the leaves a
     * leafy D4 drops when stripped. Deliberately not §4's future {@code SpeciesRegistry} (which
     * will carry profile ids, growth params, etc.) — this is registration-site plumbing only, and
     * §4 replaces it outright rather than extending it. Nine overworld species; crimson/warped
     * nether stems are structurally and thematically different (no leaves, no branch habit) and
     * are excluded from branch generation entirely.
     */
    private record WoodSpecies(String name, Block log, Block wood, Block planks, Block leaves) {}

    private static final List<WoodSpecies> WOOD_SPECIES = List.of(
            new WoodSpecies("oak", Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.OAK_PLANKS, Blocks.OAK_LEAVES),
            new WoodSpecies("spruce", Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LEAVES),
            new WoodSpecies("birch", Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD, Blocks.BIRCH_PLANKS, Blocks.BIRCH_LEAVES),
            new WoodSpecies("jungle", Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LEAVES),
            new WoodSpecies("acacia", Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.ACACIA_PLANKS, Blocks.ACACIA_LEAVES),
            new WoodSpecies("dark_oak", Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LEAVES),
            new WoodSpecies("mangrove", Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_LEAVES),
            new WoodSpecies("cherry", Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.CHERRY_PLANKS, Blocks.CHERRY_LEAVES),
            new WoodSpecies("pale_oak", Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD, Blocks.PALE_OAK_PLANKS, Blocks.PALE_OAK_LEAVES));

    /**
     * The three branch blocks + items for one species, plus the vanilla log datagen reads
     * textures from and the vanilla planks the volume-scaled recipes (§3.3) convert down into.
     */
    public record BranchSet(
            Block log, Block wood, Block planks, Block leaves, DeferredBlock<CutBranchBlock> d12, DeferredBlock<CutBranchBlock> d8, DeferredBlock<LeafyBranchBlock> d4,
            DeferredItem<BlockItem> d12Item, DeferredItem<BlockItem> d8Item, DeferredItem<BlockItem> d4Item,
            DeferredBlock<BetterSaplingBlock> sapling, DeferredItem<BlockItem> saplingItem) {}

    /** Keyed by {@link WoodSpecies#name()}, insertion order preserved (oak first, matches WOOD_SPECIES). */
    public static final Map<String, BranchSet> BRANCHES = new LinkedHashMap<>();

    static {
        for (WoodSpecies species : WOOD_SPECIES) {
            // ofFullCopy(species.log()) drags along that log's own per-state mapColor function
            // (bark colour on the sides, a different colour on the end-grain, keyed on its AXIS
            // property) — evaluating that against a state of ours crashes since we have no AXIS
            // property. Pin it back to the species' single plain plank-family colour afterward.
            DeferredBlock<CutBranchBlock> d12 = BLOCKS.registerBlock(species.name() + "_branch_12",
                    props -> new CutBranchBlock(props, Diameter.D12),
                    () -> BlockBehaviour.Properties.ofFullCopy(species.log()).mapColor(species.log().defaultMapColor()));
            DeferredBlock<CutBranchBlock> d8 = BLOCKS.registerBlock(species.name() + "_branch_8",
                    props -> new CutBranchBlock(props, Diameter.D8),
                    () -> BlockBehaviour.Properties.ofFullCopy(species.log()).mapColor(species.log().defaultMapColor()));
            DeferredBlock<LeafyBranchBlock> d4 = BLOCKS.registerBlock(species.name() + "_branch_4",
                    props -> new LeafyBranchBlock(props, Diameter.D4, species.leaves()),
                    () -> BlockBehaviour.Properties.ofFullCopy(species.log()).mapColor(species.log().defaultMapColor()));

            // Walk-through, instant-break, random-tickable — matches vanilla saplings; growth
            // itself (plan §7) lives in BetterSaplingBlock rather than a vanilla TreeGrower.
            DeferredBlock<BetterSaplingBlock> sapling = BLOCKS.registerBlock(species.name() + "_sapling",
                    props -> new BetterSaplingBlock(props, species.name()),
                    () -> BlockBehaviour.Properties.of()
                            .noCollision()
                            .randomTicks()
                            .instabreak()
                            .sound(SoundType.GRASS)
                            .pushReaction(PushReaction.DESTROY));

            BRANCHES.put(species.name(), new BranchSet(species.log(), species.wood(), species.planks(), species.leaves(), d12, d8, d4,
                    ITEMS.registerSimpleBlockItem(species.name() + "_branch_12", d12),
                    ITEMS.registerSimpleBlockItem(species.name() + "_branch_8", d8),
                    ITEMS.registerSimpleBlockItem(species.name() + "_branch_4", d4),
                    sapling,
                    ITEMS.registerSimpleBlockItem(species.name() + "_sapling", sapling)));
        }
    }

    // Creates the mod's creative tab, placed after the combat tab — grows with §4's SpeciesRegistry.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BETTER_TREES_TAB = CREATIVE_MODE_TABS.register("bettertrees_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bettertrees"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> BRANCHES.get("oak").d12Item().get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                for (BranchSet set : BRANCHES.values()) {
                    output.accept(set.d12Item().get());
                    output.accept(set.d8Item().get());
                    output.accept(set.d4Item().get());
                    output.accept(set.saplingItem().get());
                }
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BetterTrees(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BetterTrees) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the client-side datagen provider (blockstates/models) for gradlew runData
        modEventBus.addListener(this::gatherData);
        // Register the server-side datagen providers (tags/loot/recipes) for gradlew runServerData
        modEventBus.addListener(this::gatherServerData);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    private void gatherData(GatherDataEvent.Client event) {
        event.addProvider(new BTModelProvider(event.getGenerator().getPackOutput()));
    }

    private void gatherServerData(GatherDataEvent.Server event) {
        var output = event.getGenerator().getPackOutput();
        var lookupProvider = event.getLookupProvider();

        event.addProvider(new BTBlockTagsProvider(output, lookupProvider));
        event.addProvider(new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(BTBlockLootSubProvider::new, LootContextParamSets.BLOCK)), lookupProvider));
        event.addProvider(new BTRecipeProvider.Runner(output, lookupProvider));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    /** Plan §13 — GrowJobManager's per-tick placement, one level at a time, budget shared across all of them. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        int budget = Config.BLOCKS_PER_TICK.getAsInt();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (budget <= 0) {
                break;
            }
            budget -= GrowJobManager.get(level).tick(level, budget);
        }

        // Plan §12.2/§12.3 — collapse scanning and DROP execution get their own budgets, ticked
        // independently of growth: cutting trees and growing trees are unrelated events that just
        // happen to share the same per-tick-budget shape, not the same underlying queue.
        int scanBudget = Config.COLLAPSE_SCAN_PER_TICK.getAsInt();
        int dropBudget = Config.BLOCKS_PER_TICK.getAsInt();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            CollapseJobManager.get(level).tick(level, scanBudget, dropBudget);
        }
    }

    /**
     * Plan §12.1 — schedules a collapse check on any removal of a {@code #bettertrees:tree_parts}
     * block, or a {@code #bettertrees:tree_anchor} block that had a tree part resting directly on
     * top of it. Never runs the scan itself here — that would violate §12.1's explicit "never run
     * inline"; this only ever enqueues a {@link CollapseTrigger} for {@link CollapseJobManager} to
     * process on a later tick. {@link BlockEvent.BreakEvent} only fires for a player's own break
     * (not explosions, pistons, fire, etc.) — a deliberate narrowing to match this milestone's
     * acceptance check ("cutting"), the same kind of documented scope decision prior milestones have
     * made rather than chasing every literal reading of "any removal" at once.
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        boolean isTreePart = event.getState().is(ModTags.TREE_PARTS);
        boolean isGroundedAnchor = !isTreePart && event.getState().is(ModTags.TREE_ANCHOR)
                && level.getBlockState(pos.above()).is(ModTags.TREE_PARTS);
        if (!isTreePart && !isGroundedAnchor) {
            return;
        }

        // Plan §13's forward-looking hook, landed in M9 specifically for this: a position still
        // inside an in-progress grow job's own voxel set isn't a real cut — the wood around it may
        // not all exist yet, so a support check right now would be unreliable.
        if (GrowJobManager.get(level).isGrowingAt(pos)) {
            return;
        }

        CollapseJobManager.get(level).schedule(new CollapseTrigger(pos, isGroundedAnchor));
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = BetterTrees.MODID, value = Dist.CLIENT)
    static class ClientModEvents {
        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
