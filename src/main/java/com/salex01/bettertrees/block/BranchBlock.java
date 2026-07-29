package com.salex01.bettertrees.block;

import com.salex01.bettertrees.generator.Diameter;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Connection-based branch/limb block (plan §3). Six {@link BlockStateProperties#DOWN}-style
 * booleans record which faces have an arm attached; shape and (eventually) rendering are derived
 * from that plus {@link #diameter}. Concrete diameters need different extra state — six per-face
 * {@code cut_*} booleans for D8/D12, {@code LEAFY} for D4 (plan §3.1/§3.2) — so that lives on the
 * two subclasses rather than here: {@code createBlockStateDefinition} runs inside {@link Block}'s
 * own constructor
 * (before this class's constructor body assigns {@link #diameter}), so it must not depend on any
 * field set after {@code super(...)}. See {@link CutBranchBlock} and {@link LeafyBranchBlock}.
 */
public abstract class BranchBlock extends Block implements SimpleWaterloggedBlock {
    protected final Diameter diameter;

    protected BranchBlock(BlockBehaviour.Properties properties, Diameter diameter) {
        super(properties);
        this.diameter = diameter;
    }

    public final Diameter diameter() {
        return diameter;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(
                BlockStateProperties.DOWN, BlockStateProperties.UP,
                BlockStateProperties.NORTH, BlockStateProperties.SOUTH,
                BlockStateProperties.EAST, BlockStateProperties.WEST,
                BlockStateProperties.WATERLOGGED);
    }

    /** No connections, not waterlogged — subclasses layer their own extra property on top. */
    protected final BlockState baseDefaultState() {
        return this.stateDefinition.any()
                .setValue(BlockStateProperties.DOWN, false)
                .setValue(BlockStateProperties.UP, false)
                .setValue(BlockStateProperties.NORTH, false)
                .setValue(BlockStateProperties.SOUTH, false)
                .setValue(BlockStateProperties.EAST, false)
                .setValue(BlockStateProperties.WEST, false)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean waterlogged = context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER;
        return this.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean[] connections = new boolean[Direction.values().length];
        for (Map.Entry<Direction, BooleanProperty> entry : PipeBlock.PROPERTY_BY_DIRECTION.entrySet()) {
            connections[entry.getKey().ordinal()] = state.getValue(entry.getValue());
        }
        return BranchShapes.get(diameter, BranchShapes.index(connections));
    }
}
