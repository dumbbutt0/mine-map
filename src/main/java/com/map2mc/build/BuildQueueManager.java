package com.map2mc.build;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns one {@link BuildQueue} per world and drives them from the server tick.
 *
 * <p>Registered from the mod initializer against {@code ServerTickEvents.END_SERVER_TICK} (END so
 * placements settle before the next client sync) and {@code ServerLifecycleEvents.SERVER_STOPPING}
 * (to release forced chunks and drop stale world references between sessions).
 */
public final class BuildQueueManager {

    private static final Map<RegistryKey<World>, BuildQueue> QUEUES = new HashMap<>();

    private BuildQueueManager() {
    }

    /** Get (lazily creating) the queue for a world. */
    public static BuildQueue get(ServerWorld world) {
        return QUEUES.computeIfAbsent(world.getRegistryKey(), k -> new BuildQueue(world));
    }

    public static void onEndServerTick(MinecraftServer server) {
        if (QUEUES.isEmpty()) {
            return;
        }
        for (BuildQueue q : QUEUES.values()) {
            q.tick();
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        for (BuildQueue q : QUEUES.values()) {
            q.cancelActive(); // release any force-loaded chunks
        }
        QUEUES.clear();
    }
}
