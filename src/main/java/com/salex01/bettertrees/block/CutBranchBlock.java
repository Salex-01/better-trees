package com.salex01.bettertrees.block;

import com.google.common.collect.ImmutableMap;
import com.salex01.bettertrees.generator.Diameter;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * D8/D12 branch. A direction's arm can be capped with rings instead of bark at its outward end —
 * one {@code cut_*} boolean per direction, {@link #CUT_BY_DIRECTION} (plan §3.1, revised after
 * playtesting the original whole-block boolean: it rings-textured every never-grown face on a
 * branch tip, which read as broken rather than as a chop).
 *
 * <p>{@code cut_<dir>} only means anything when {@code <dir>} itself is {@code true} — an arm has
 * to exist before its end can be capped. There is no state where a direction shows geometry
 * without its own connection boolean being set; the datagen provider requires both. Shape is
 * therefore still purely a function of the six connection booleans (see
 * {@link BranchBlock#getShape}) — {@code cut_*} only changes which model gets applied to an arm
 * that's there regardless, never whether it's there.
 *
 * <p>Still not set automatically anywhere — that's the support solver's job (§12). TODO(later):
 * a shears interaction that severs one specific connection (sets {@code <dir>=false}) while
 * marking that same face {@code cut_<dir>=true} for one step, so the stub reads as freshly sawn
 * before it heals over.
 */
public final class CutBranchBlock extends BranchBlock {
    public static final BooleanProperty CUT_DOWN = BooleanProperty.create("cut_down");
    public static final BooleanProperty CUT_UP = BooleanProperty.create("cut_up");
    public static final BooleanProperty CUT_NORTH = BooleanProperty.create("cut_north");
    public static final BooleanProperty CUT_SOUTH = BooleanProperty.create("cut_south");
    public static final BooleanProperty CUT_EAST = BooleanProperty.create("cut_east");
    public static final BooleanProperty CUT_WEST = BooleanProperty.create("cut_west");

    public static final Map<Direction, BooleanProperty> CUT_BY_DIRECTION = ImmutableMap.<Direction, BooleanProperty>builder()
            .put(Direction.DOWN, CUT_DOWN)
            .put(Direction.UP, CUT_UP)
            .put(Direction.NORTH, CUT_NORTH)
            .put(Direction.SOUTH, CUT_SOUTH)
            .put(Direction.EAST, CUT_EAST)
            .put(Direction.WEST, CUT_WEST)
            .build();

    public CutBranchBlock(BlockBehaviour.Properties properties, Diameter diameter) {
        super(properties, diameter);
        BlockState state = baseDefaultState();
        for (BooleanProperty cut : CUT_BY_DIRECTION.values()) {
            state = state.setValue(cut, false);
        }
        this.registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CUT_DOWN, CUT_UP, CUT_NORTH, CUT_SOUTH, CUT_EAST, CUT_WEST);
    }
}
