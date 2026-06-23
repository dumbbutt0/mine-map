package com.map2mc.undo;

import com.map2mc.build.ChunkPlacer;
import com.map2mc.build.Placement;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small per-world stack of {@link UndoLog}s. Each completed (or cancelled) build pushes its log;
 * {@code /map2mc undo} pops the most recent and restores it. Depth is capped at {@link #MAX_DEPTH}
 * so memory stays bounded — the most recent build is always undoable, plus a few before it.
 *
 * <p>Restoration reuses the existing {@link ChunkPlacer} (force-load + {@code setBlockState} on the
 * server thread) — no separate placement path. Prior states are written back with
 * {@code NOTIFY_LISTENERS} only, matching how the build was laid down (no physics cascade).
 */
public final class UndoManager {

    /** How many builds back you can undo. The last build is always restorable. */
    public static final int MAX_DEPTH = 5;

    private static final Map<RegistryKey<World>, Deque<UndoLog>> STACKS = new HashMap<>();

    private UndoManager() {
    }

    public static void push(ServerWorld world, UndoLog log) {
        if (log == null || log.size() == 0) {
            return;
        }
        Deque<UndoLog> stack = STACKS.computeIfAbsent(world.getRegistryKey(), k -> new ArrayDeque<>());
        stack.push(log);
        while (stack.size() > MAX_DEPTH) {
            stack.removeLast(); // drop the oldest
        }
    }

    /** Restore the most recent build's prior states. Returns blocks restored, or -1 if none. */
    public static int undoLast(ServerWorld world) {
        Deque<UndoLog> stack = STACKS.get(world.getRegistryKey());
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        UndoLog log = stack.pop();
        ChunkPlacer placer = new ChunkPlacer(world);
        List<Placement> restore = log.toRestorePlacements();
        for (Placement p : restore) {
            placer.place(p, Block.NOTIFY_LISTENERS, null); // null = don't record undo-of-undo
        }
        placer.releaseAll();
        return restore.size();
    }

    public static int depth(ServerWorld world) {
        Deque<UndoLog> stack = STACKS.get(world.getRegistryKey());
        return stack == null ? 0 : stack.size();
    }

    public static void clear() {
        STACKS.clear();
    }
}
