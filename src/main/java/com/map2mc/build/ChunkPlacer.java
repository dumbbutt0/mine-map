package com.map2mc.build;

import com.map2mc.undo.UndoLog;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Performs the actual block placement on the server thread, and keeps target chunks loaded.
 *
 * <p>The first time a placement lands in a given chunk, that chunk is force-loaded so builds far
 * from any player don't silently drop blocks into unloaded chunks. All forced chunks are released
 * when the owning job finishes or is cancelled. For builds near a player (the common case) the
 * chunk is already loaded, so the forceload is just a cheap marker.
 */
public final class ChunkPlacer {

    private final ServerWorld world;
    private final Set<Long> forced = new HashSet<>();

    public ChunkPlacer(ServerWorld world) {
        this.world = world;
    }

    /**
     * Place one block, forcing its chunk on first touch. Must be called on the server thread.
     *
     * @param undo if non-null, the block's PRIOR state is recorded here before it is overwritten, so
     *             the build can be reverted later (Phase 2.5). Pass null for placements that should
     *             not be undoable (e.g. an undo restoring prior states — no undo-of-undo).
     */
    public void place(Placement p, int flags, UndoLog undo) {
        if (undo != null) {
            undo.record(p.pos().asLong(), world.getBlockState(p.pos()));
        }
        ChunkPos cp = new ChunkPos(p.pos());
        if (forced.add(cp.toLong())) {
            world.setChunkForced(cp.x, cp.z, true);
        }
        world.setBlockState(p.pos(), p.state(), flags);
    }

    /** Release every chunk this placer force-loaded. */
    public void releaseAll() {
        for (long key : forced) {
            ChunkPos cp = new ChunkPos(key);
            world.setChunkForced(cp.x, cp.z, false);
        }
        forced.clear();
    }
}
