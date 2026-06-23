package com.map2mc.orbit;

import com.map2mc.place.Bounds;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the most recent hero build per world (its bounding box + how long it takes to lay down),
 * so {@code /map2mc orbit} can frame a slow camera around it without re-loading the schematic.
 */
public final class LastBuild {

    public record Info(Bounds bounds, int buildSeconds, String label) {
    }

    private static final Map<RegistryKey<World>, Info> MAP = new HashMap<>();

    private LastBuild() {
    }

    public static void set(ServerWorld world, Bounds bounds, int buildSeconds, String label) {
        MAP.put(world.getRegistryKey(), new Info(bounds, buildSeconds, label));
    }

    public static Info get(ServerWorld world) {
        return MAP.get(world.getRegistryKey());
    }

    public static void clear() {
        MAP.clear();
    }
}
