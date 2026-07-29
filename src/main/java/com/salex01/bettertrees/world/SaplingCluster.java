package com.salex01.bettertrees.world;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Plan §8.1 — face-adjacent flood fill over same-species saplings at one sapling's Y. Not in
 * {@code generator/}: it reads {@code Level}/{@code BlockState} directly, same boundary as {@link
 * LevelOccupancyView}.
 */
public final class SaplingCluster {
    private static final int MAX_CELLS = 256;
    private static final int MAX_BBOX = 16;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private SaplingCluster() {}

    /** @return every sapling position in {@code start}'s cluster (including {@code start} itself), or {@code null} if the cluster exceeds a 16x16 bbox or 256 cells (plan §8.1's abort case). */
    public static Set<BlockPos> detect(Level level, BlockPos start, Block saplingBlock) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);

        int minX = start.getX(), maxX = start.getX(), minZ = start.getZ(), maxZ = start.getZ();

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : HORIZONTAL) {
                BlockPos next = current.relative(dir);
                if (visited.contains(next) || !level.getBlockState(next).is(saplingBlock)) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
                minX = Math.min(minX, next.getX());
                maxX = Math.max(maxX, next.getX());
                minZ = Math.min(minZ, next.getZ());
                maxZ = Math.max(maxZ, next.getZ());
                if (visited.size() > MAX_CELLS || maxX - minX + 1 > MAX_BBOX || maxZ - minZ + 1 > MAX_BBOX) {
                    return null;
                }
            }
        }
        return visited;
    }
}
