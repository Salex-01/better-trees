package com.salex01.bettertrees.data;

import com.salex01.bettertrees.BetterTrees;
import com.salex01.bettertrees.block.BranchBlock;
import com.salex01.bettertrees.block.CutBranchBlock;
import com.salex01.bettertrees.block.LeafyBranchBlock;
import com.salex01.bettertrees.generator.Diameter;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.renderer.block.model.VariantMutator;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplate;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Blockstate + model datagen for {@link BranchBlock} (plan §3). Reuses each species' own vanilla
 * log textures (e.g. {@code birch_log}/{@code birch_log_top}) rather than shipping bark/end-grain
 * art of our own — M3's acceptance criterion is no missing texture warnings, not new art.
 *
 * <p>Three model parts per diameter:
 * <ul>
 *   <li><b>core</b> — the {@code d}-cubed centre, bark on all six faces, unconditional.</li>
 *   <li><b>arm</b> — a {@code d x d} limb from the core face to the block boundary, bark on all
 *       faces, one {@code when} per connected direction ({@link PipeBlock#PROPERTY_BY_DIRECTION}).</li>
 *   <li><b>cut arm</b> — the same box as {@code arm}, bark on its sides, but rings on the outward
 *       end face instead of bark — a sawn-off stub, not a decal on the core. Emitted per direction
 *       {@code when cut_<dir>=true} (§3.1, revised to per-face after playtesting the original
 *       whole-block boolean: it put rings on every never-grown face of a branch tip, which read as
 *       broken rather than as a chop).</li>
 * </ul>
 * D4 has neither {@code cut} property — {@link LeafyBranchBlock} carries {@code LEAFY} instead,
 * and its shell model is Milestone 3.
 */
public class BTModelProvider extends ModelProvider {
    // North is the unrotated base orientation for arm/decal models; every other face is this
    // rotated. Must agree with how BranchShapes builds its own geometry per Direction.
    private static final Map<Direction, VariantMutator> ROTATIONS = new EnumMap<>(Direction.class);
    static {
        ROTATIONS.put(Direction.NORTH, BlockModelGenerators.NOP);
        ROTATIONS.put(Direction.EAST, BlockModelGenerators.Y_ROT_90);
        ROTATIONS.put(Direction.SOUTH, BlockModelGenerators.Y_ROT_180);
        ROTATIONS.put(Direction.WEST, BlockModelGenerators.Y_ROT_270);
        ROTATIONS.put(Direction.UP, BlockModelGenerators.X_ROT_270);
        ROTATIONS.put(Direction.DOWN, BlockModelGenerators.X_ROT_90);
    }

    public BTModelProvider(PackOutput output) {
        super(output, BetterTrees.MODID);
    }

    /**
     * Vanilla's own sapling texture for the species where one exists. Mangrove has no simple
     * cross-quad sapling in vanilla (it grows from a propagule with different geometry and
     * behaviour) — its propagule texture stands in until this milestone's sapling gets real art.
     */
    private static final Map<String, Identifier> SAPLING_TEXTURE_OVERRIDE =
            Map.of("mangrove", Identifier.withDefaultNamespace("block/mangrove_propagule"));

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        BetterTrees.BRANCHES.forEach((species, branches) -> {
            registerBranch(blockModels, branches.d12().get(), Diameter.D12, branches.log(), CutBranchBlock.CUT_BY_DIRECTION);
            registerBranch(blockModels, branches.d8().get(), Diameter.D8, branches.log(), CutBranchBlock.CUT_BY_DIRECTION);
            registerBranch(blockModels, branches.d4().get(), Diameter.D4, branches.log(), null);
            registerSapling(blockModels, species, branches.sapling().get());
        });
    }

    private void registerSapling(BlockModelGenerators blockModels, String species, Block sapling) {
        Identifier texture = SAPLING_TEXTURE_OVERRIDE.getOrDefault(species, Identifier.withDefaultNamespace("block/" + species + "_sapling"));
        Identifier model = ModelTemplates.CROSS.create(sapling, TextureMapping.cross(texture), blockModels.modelOutput);
        blockModels.blockStateOutput.accept(MultiPartGenerator.multiPart(sapling).with(BlockModelGenerators.plainVariant(model)));
        blockModels.registerSimpleItemModel(sapling, model);
    }

    private void registerBranch(BlockModelGenerators blockModels, Block block, Diameter diameter, Block logSource,
            @Nullable Map<Direction, BooleanProperty> cutByDirection) {
        TextureMapping textures = TextureMapping.logColumn(logSource);

        Identifier coreModel = coreTemplate(diameter).create(block, textures, blockModels.modelOutput);
        Identifier armModel = armTemplate(diameter).create(block, textures, blockModels.modelOutput);

        MultiPartGenerator generator = MultiPartGenerator.multiPart(block)
                .with(BlockModelGenerators.plainVariant(coreModel));

        Identifier cutArmModel = cutByDirection != null ? cutArmTemplate(diameter).create(block, textures, blockModels.modelOutput) : null;

        for (Direction dir : Direction.values()) {
            BooleanProperty connected = PipeBlock.PROPERTY_BY_DIRECTION.get(dir);
            BooleanProperty cut = cutByDirection != null ? cutByDirection.get(dir) : null;

            // An arm only exists where its own connection boolean is true — cut is a modifier on
            // an existing arm (rings instead of bark at the tip), never a trigger on its own.
            var plainArmCondition = BlockModelGenerators.condition().term(connected, true);
            if (cut != null) {
                plainArmCondition = plainArmCondition.term(cut, false);
            }
            generator = generator.with(plainArmCondition, rotated(armModel, dir));

            if (cut != null) {
                generator = generator.with(
                        BlockModelGenerators.condition().term(connected, true).term(cut, true),
                        rotated(cutArmModel, dir));
            }
        }

        blockModels.blockStateOutput.accept(generator);
        blockModels.registerSimpleItemModel(block, coreModel);
    }

    private static MultiVariant rotated(Identifier model, Direction dir) {
        MultiVariant variant = BlockModelGenerators.plainVariant(model);
        VariantMutator rotation = ROTATIONS.get(dir);
        return rotation == BlockModelGenerators.NOP ? variant : variant.with(rotation).with(BlockModelGenerators.UV_LOCK);
    }

    /**
     * No {@code cullface} on any face — the core is recessed inside the block on every diameter
     * we ship (D16 is vanilla logs, never this template), so it never actually touches a
     * neighbour's face. Setting cullface here would hide these faces whenever *any* opaque block
     * sits next to ours, even though there's a visible gap around the recessed core showing it.
     */
    private static ExtendedModelTemplate coreTemplate(Diameter diameter) {
        double min = coreMin(diameter);
        double max = coreMax(diameter);
        return ExtendedModelTemplateBuilder.builder()
                .requiredTextureSlot(TextureSlot.SIDE)
                .requiredTextureSlot(TextureSlot.PARTICLE)
                .element(e -> e.from((float) min, (float) min, (float) min).to((float) max, (float) max, (float) max)
                        .textureAll(TextureSlot.SIDE))
                .build();
    }

    /**
     * Points north (-Z): from the core's north face out to the block boundary at z=0. Only the
     * outward (north) face actually reaches the block boundary — the four side faces are
     * recessed the same way the core's are — so only that one face gets a cullface.
     */
    private static ExtendedModelTemplate armTemplate(Diameter diameter) {
        double min = coreMin(diameter);
        double max = coreMax(diameter);
        return ExtendedModelTemplateBuilder.builder()
                .suffix("_arm")
                .requiredTextureSlot(TextureSlot.SIDE)
                .requiredTextureSlot(TextureSlot.PARTICLE)
                .element(e -> e.from((float) min, (float) min, 0f).to((float) max, (float) max, (float) min)
                        .textureAll(TextureSlot.SIDE)
                        .face(Direction.NORTH, f -> f.cullface(Direction.NORTH)))
                .build();
    }

    /**
     * Points north: the same box as {@link #armTemplate} — a sawn-off stub still standing out to
     * the block boundary, bark on its sides — but with rings on the outward (north) end face
     * instead of bark, capping where it was cut. Cullface follows the same rule as the arm: only
     * the outward face touches a neighbour, so only it gets one.
     */
    private static ExtendedModelTemplate cutArmTemplate(Diameter diameter) {
        double min = coreMin(diameter);
        double max = coreMax(diameter);
        return ExtendedModelTemplateBuilder.builder()
                .suffix("_cut")
                .requiredTextureSlot(TextureSlot.SIDE)
                .requiredTextureSlot(TextureSlot.END)
                .requiredTextureSlot(TextureSlot.PARTICLE)
                .element(e -> e.from((float) min, (float) min, 0f).to((float) max, (float) max, (float) min)
                        .textureAll(TextureSlot.SIDE)
                        .face(Direction.NORTH, f -> f.texture(TextureSlot.END).cullface(Direction.NORTH)))
                .build();
    }

    private static double coreMin(Diameter diameter) {
        return 8.0 - diameter.px() / 2.0;
    }

    private static double coreMax(Diameter diameter) {
        return 8.0 + diameter.px() / 2.0;
    }
}
