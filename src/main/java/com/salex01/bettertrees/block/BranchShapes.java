package com.salex01.bettertrees.block;

import com.salex01.bettertrees.generator.Diameter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Precomputed collision/selection shapes for {@link BranchBlock}: a {@code d}-cubed core at the
 * block centre plus a {@code d x d} arm per connected face. One {@code VoxelShape[64]} per
 * diameter, indexed by a bitmask over {@link Direction#values()} order — see {@link #index}.
 */
final class BranchShapes {
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final VoxelShape[] D4 = build(Diameter.D4);
    private static final VoxelShape[] D8 = build(Diameter.D8);
    private static final VoxelShape[] D12 = build(Diameter.D12);

    private BranchShapes() {}

    static VoxelShape get(Diameter diameter, int connectionIndex) {
        return switch (diameter) {
            case D4 -> D4[connectionIndex];
            case D8 -> D8[connectionIndex];
            case D12 -> D12[connectionIndex];
            case D16 -> throw new IllegalArgumentException("D16 has no BranchBlock — it is a vanilla log");
        };
    }

    /** Bit {@code i} is set when the connection in {@link Direction#values()}{@code [i]} is present. */
    static int index(boolean[] connections) {
        int index = 0;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (connections[i]) {
                index |= 1 << i;
            }
        }
        return index;
    }

    private static VoxelShape[] build(Diameter diameter) {
        double coreMin = 8.0 - diameter.px() / 2.0;
        double coreMax = 8.0 + diameter.px() / 2.0;
        VoxelShape core = Block.box(coreMin, coreMin, coreMin, coreMax, coreMax, coreMax);

        VoxelShape[] arms = new VoxelShape[DIRECTIONS.length];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            arms[i] = armBox(DIRECTIONS[i], coreMin, coreMax);
        }

        VoxelShape[] shapes = new VoxelShape[1 << DIRECTIONS.length];
        for (int mask = 0; mask < shapes.length; mask++) {
            VoxelShape shape = core;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    shape = Shapes.or(shape, arms[i]);
                }
            }
            shapes[mask] = shape.optimize();
        }
        return shapes;
    }

    private static VoxelShape armBox(Direction dir, double coreMin, double coreMax) {
        return Block.box(
                axisMin(dir.getStepX(), coreMin, coreMax), axisMin(dir.getStepY(), coreMin, coreMax), axisMin(dir.getStepZ(), coreMin, coreMax),
                axisMax(dir.getStepX(), coreMin, coreMax), axisMax(dir.getStepY(), coreMin, coreMax), axisMax(dir.getStepZ(), coreMin, coreMax));
    }

    // step == 0 -> cross-section axis, clamped to the core. step != 0 -> the extension axis,
    // running from the core's face on that side out to the block boundary.
    private static double axisMin(int step, double coreMin, double coreMax) {
        if (step > 0) return coreMax;
        if (step < 0) return 0.0;
        return coreMin;
    }

    private static double axisMax(int step, double coreMin, double coreMax) {
        if (step > 0) return 16.0;
        if (step < 0) return coreMin;
        return coreMax;
    }
}
