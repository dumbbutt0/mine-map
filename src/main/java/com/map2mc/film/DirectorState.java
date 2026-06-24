package com.map2mc.film;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * The thread-safe hand-off from the server director (writer, server tick) to the client camera mixin
 * (reader, render frame) in the same JVM. A single immutable {@link Snapshot} is swapped atomically
 * via a {@code volatile} reference each server tick; the mixin reads it (possibly many times per
 * tick) and lerps {@code prev → cur} by the render partial-tick. {@code null} == not filming.
 */
public final class DirectorState {

    /** One camera pose (world coords + look angles in degrees). */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    /** Last tick's pose, this tick's pose, and which player/world the film is for. */
    public record Snapshot(Pose prev, Pose cur, UUID filmingPlayer, RegistryKey<World> worldKey) {
    }

    private static volatile Snapshot snapshot = null;

    private DirectorState() {
    }

    /** Server thread: publish the new (prev,cur) pair for this tick. */
    public static void publish(Snapshot s) {
        snapshot = s;
    }

    /** Render thread: read the current snapshot (null if not filming). */
    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean active() {
        return snapshot != null;
    }

    public static void reset() {
        snapshot = null;
    }
}
