package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * One tree's pending, ticked block placement (plan §13) — everything {@link GrowJobManager} needs
 * to resume it across a chunk unload or a server restart. {@code voxels} is already in its final
 * placement order (wood before leaves, then ascending Y — see {@code BlueprintPlacer}), computed
 * once when the job was created and never reordered; {@code cursor} is the index of the next voxel
 * still to place.
 */
public final class GrowJob {
    public static final Codec<GrowJob> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("origin").forGetter(GrowJob::origin),
            Codec.STRING.fieldOf("species").forGetter(GrowJob::species),
            QueuedVoxel.CODEC.listOf().fieldOf("voxels").forGetter(GrowJob::voxels),
            Codec.INT.fieldOf("cursor").forGetter(GrowJob::cursor)
    ).apply(instance, GrowJob::new));

    private final BlockPos origin;
    private final String species;
    private final List<QueuedVoxel> voxels;
    private int cursor;

    public GrowJob(BlockPos origin, String species, List<QueuedVoxel> voxels, int cursor) {
        this.origin = origin;
        this.species = species;
        this.voxels = voxels;
        this.cursor = cursor;
    }

    public static GrowJob start(BlockPos origin, String species, List<QueuedVoxel> voxels) {
        return new GrowJob(origin, species, voxels, 0);
    }

    public BlockPos origin() {
        return origin;
    }

    public String species() {
        return species;
    }

    public List<QueuedVoxel> voxels() {
        return voxels;
    }

    public int cursor() {
        return cursor;
    }

    public boolean isDone() {
        return cursor >= voxels.size();
    }

    public QueuedVoxel next() {
        return voxels.get(cursor);
    }

    public void advance() {
        cursor++;
    }
}
