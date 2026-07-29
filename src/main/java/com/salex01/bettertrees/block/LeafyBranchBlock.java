package com.salex01.bettertrees.block;

import com.salex01.bettertrees.generator.Diameter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * D4 branch: carries {@link #LEAFY} instead of {@code CUT} (plan §3.2) — a leafy twig never
 * shows end-grain, it just has an inner foliage shell when true. {@code LEAFY} and
 * {@code WATERLOGGED} are mutually exclusive: submerging a leafy twig strips it (drops 0-1
 * leaves, same as breaking real leaves) and shears do the same on right-click. The shell model
 * itself (cutout render type, foliage tint) is a stretch goal — Milestone 3's acceptance only
 * requires the state flip and the drop, not the mesh.
 */
public final class LeafyBranchBlock extends BranchBlock {
    public static final BooleanProperty LEAFY = BooleanProperty.create("leafy");

    /** Vanilla leaves block for this species, used only for the item drop when stripped. */
    private final Block leaves;

    public LeafyBranchBlock(BlockBehaviour.Properties properties, Diameter diameter, Block leaves) {
        super(properties, diameter);
        this.leaves = leaves;
        this.registerDefaultState(baseDefaultState().setValue(LEAFY, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LEAFY);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!state.getValue(LEAFY) || !stack.canPerformAction(ItemAbilities.SHEARS_HARVEST)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        if (level instanceof ServerLevel serverLevel) {
            // Deterministic, unlike the waterlog strip below — shears guarantee a drop the same
            // way they do on vanilla leaves, and with no leafy_shell mesh yet, the dropped item
            // is the only visible evidence the interaction did anything.
            strip(serverLevel, pos, state, 1);
            level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
            stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
            level.gameEvent(player, GameEvent.SHEAR, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Submerging a leafy twig strips it, same as real leaves not surviving underwater. Delegates
     * to {@link BranchBlock}'s inherited {@code SimpleWaterloggedBlock} default first so the
     * WATERLOGGED flip and fluid tick scheduling stay identical to every other branch block.
     */
    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (!super.placeLiquid(level, pos, state, fluidState)) {
            return false;
        }
        if (state.getValue(LEAFY) && level instanceof ServerLevel serverLevel) {
            strip(serverLevel, pos, serverLevel.getBlockState(pos), serverLevel.random.nextBoolean() ? 1 : 0);
        }
        return true;
    }

    private void strip(ServerLevel level, BlockPos pos, BlockState state, int leafCount) {
        level.setBlock(pos, state.setValue(LEAFY, false), 3);
        if (leafCount > 0) {
            Block.popResource(level, pos, new ItemStack(leaves.asItem(), leafCount));
        }
    }
}
