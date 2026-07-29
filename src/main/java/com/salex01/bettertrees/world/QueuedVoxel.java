package com.salex01.bettertrees.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.salex01.bettertrees.generator.Diameter;
import com.salex01.bettertrees.generator.Placement;
import com.salex01.bettertrees.generator.math.Vec3i;
import org.jspecify.annotations.Nullable;

/**
 * One placed-or-not-yet-placed voxel of a {@link GrowJob} — everything {@link GrowJobManager}
 * needs to resolve a concrete block state on the tick it actually places this voxel, without
 * keeping the {@code Skeleton}/ownership map that produced it around between ticks (or across a
 * server restart). Baked out of a {@code Placement} plus its precomputed connection booleans (plan
 * §2's {@code PlacementResolver.woodConnections}) once, at enqueue time — connections never change
 * after that, since they only depend on which neighbouring voxels are wood, and the whole blueprint
 * is already fixed by the time a job exists. {@code waterlogged} is deliberately NOT baked in here:
 * it's a live {@code Level} fluid-state read, done fresh at actual placement time in {@link
 * GrowJobManager}, same as the original synchronous {@code BlueprintPlacer} did.
 */
public record QueuedVoxel(Vec3i local, Placement.Kind kind, @Nullable Diameter diameter, int leafDistance,
                           boolean leafy, int connectionBits) {
    // Fails soft (a DataResult error the SavedData load can report/skip) rather than throwing —
    // an IllegalArgumentException out of Enum.valueOf would hard-crash loading the whole level's
    // pending-job list over one corrupt or forward-version save entry.
    private static <E extends Enum<E>> DataResult<E> safeValueOf(E[] values, String name) {
        for (E e : values) {
            if (e.name().equals(name)) {
                return DataResult.success(e);
            }
        }
        return DataResult.error(() -> "Unknown enum constant \"" + name + "\" for " + values.getClass().componentType().getSimpleName());
    }

    private static final Codec<Placement.Kind> KIND_CODEC = Codec.STRING.flatXmap(
            name -> safeValueOf(Placement.Kind.values(), name), kind -> DataResult.success(kind.name()));
    private static final Codec<@Nullable Diameter> OPTIONAL_DIAMETER_CODEC = Codec.STRING.flatXmap(
            name -> name.isEmpty() ? DataResult.success(null) : safeValueOf(Diameter.values(), name),
            d -> DataResult.success(d == null ? "" : d.name()));

    public static final Codec<QueuedVoxel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(v -> v.local.x()),
            Codec.INT.fieldOf("y").forGetter(v -> v.local.y()),
            Codec.INT.fieldOf("z").forGetter(v -> v.local.z()),
            KIND_CODEC.fieldOf("kind").forGetter(QueuedVoxel::kind),
            OPTIONAL_DIAMETER_CODEC.fieldOf("diameter").forGetter(QueuedVoxel::diameter),
            Codec.INT.fieldOf("leaf_distance").forGetter(QueuedVoxel::leafDistance),
            Codec.BOOL.fieldOf("leafy").forGetter(QueuedVoxel::leafy),
            Codec.INT.fieldOf("connections").forGetter(QueuedVoxel::connectionBits)
    ).apply(instance, (x, y, z, kind, diameter, leafDistance, leafy, connections) ->
            new QueuedVoxel(new Vec3i(x, y, z), kind, diameter, leafDistance, leafy, connections)));

    public static QueuedVoxel of(Vec3i local, Placement placement, boolean[] connections) {
        int bits = 0;
        for (int i = 0; i < connections.length; i++) {
            if (connections[i]) {
                bits |= 1 << i;
            }
        }
        return new QueuedVoxel(local, placement.kind(), placement.d(), placement.leafDistance(), placement.leafy(), bits);
    }

    /** Indexed the same as {@code Direction.values()}'s ordinal order — see {@code PlacementResolver.FACE_OFFSETS}. */
    public boolean connection(int direction) {
        return (connectionBits & (1 << direction)) != 0;
    }
}
