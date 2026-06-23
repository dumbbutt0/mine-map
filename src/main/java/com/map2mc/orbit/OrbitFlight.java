package com.map2mc.orbit;

import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The mod-driven recording aid: flies the command player's camera along the orbit path in-game
 * (the alternative to typing keyframes into ReplayMod). The player is switched to spectator for the
 * duration (so the camera clips through nothing and doesn't fall) and restored afterward.
 *
 * <p>Tick-driven on END_SERVER_TICK; samples the same {@link Orbit#poseAt} the exported keyframes
 * use, so what you film matches the table exactly. One flight per player.
 */
public final class OrbitFlight {

    private static final class Flight {
        final UUID player;
        final ServerWorld world;
        final double cx, cz, aimY, radius, camYStart, camYEnd;
        final int totalTicks;
        final GameMode prevMode;
        int elapsed = 0;

        Flight(UUID player, ServerWorld world, Orbit.Params p, int totalTicks, GameMode prevMode) {
            this.player = player;
            this.world = world;
            this.cx = p.cx();
            this.cz = p.cz();
            this.aimY = p.aimY();
            this.radius = p.radius();
            this.camYStart = p.camYStart();
            this.camYEnd = p.camYEnd();
            this.totalTicks = Math.max(1, totalTicks);
            this.prevMode = prevMode;
        }
    }

    private static final Map<UUID, Flight> ACTIVE = new HashMap<>();

    private OrbitFlight() {
    }

    public static void start(ServerPlayerEntity player, ServerWorld world,
                             Orbit.Params params, int renderSeconds) {
        UUID id = player.getUuid();
        Flight existing = ACTIVE.remove(id);
        // Preserve the ORIGINAL gamemode across a restart so we always restore correctly.
        GameMode prev = existing != null ? existing.prevMode : player.interactionManager.getGameMode();
        player.changeGameMode(GameMode.SPECTATOR);
        Flight f = new Flight(id, world, params, renderSeconds * 20, prev);
        ACTIVE.put(id, f);
        apply(player, f, 0.0); // snap to the start of the orbit immediately
    }

    public static boolean cancel(UUID id, MinecraftServer server) {
        Flight f = ACTIVE.remove(id);
        if (f == null) {
            return false;
        }
        ServerPlayerEntity p = server == null ? null : server.getPlayerManager().getPlayer(id);
        if (p != null) {
            p.changeGameMode(f.prevMode);
        }
        return true;
    }

    public static void onEndServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Flight> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(f.player);
            if (p == null) {
                it.remove(); // player logged off mid-flight
                continue;
            }
            f.elapsed++;
            double frac = (double) f.elapsed / f.totalTicks;
            if (frac >= 1.0) {
                apply(p, f, 1.0);
                p.changeGameMode(f.prevMode); // restore when the orbit completes
                it.remove();
            } else {
                apply(p, f, frac);
            }
        }
    }

    private static void apply(ServerPlayerEntity p, Flight f, double frac) {
        OrbitKeyframe k = Orbit.poseAt(f.cx, f.cz, f.aimY, f.radius, f.camYStart, f.camYEnd, frac, 0, 0);
        // empty flag set => absolute teleport of position AND rotation
        p.teleport(f.world, k.x(), k.y(), k.z(),
                EnumSet.noneOf(PositionFlag.class), k.yaw(), k.pitch(), false);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }
}
