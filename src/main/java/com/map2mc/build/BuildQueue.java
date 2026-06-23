package com.map2mc.build;

import com.map2mc.Map2mc;
import com.map2mc.build.ordering.OrderingContext;
import com.map2mc.build.ordering.OrderingStrategy;
import com.map2mc.undo.UndoManager;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Per-world placement engine — THE SPINE. Holds a FIFO of {@link BuildJob}s; only the head job
 * ticks (one visible build at a time, which is correct for a timelapse). Each server tick it lays
 * down up to {@code blocksPerTick} blocks of the active job, so even a 12k-block landmark is
 * amortized across seconds and never freezes the client.
 */
public final class BuildQueue {

    private final ServerWorld world;
    private final ChunkPlacer placer;
    private final Deque<BuildJob> jobs = new ArrayDeque<>();
    private long nextId = 1;

    public BuildQueue(ServerWorld world) {
        this.world = world;
        this.placer = new ChunkPlacer(world);
    }

    /**
     * Order the placements with {@code strategy} (once, here — not per tick) and enqueue them.
     * Returns the new job's id.
     */
    public long submit(List<Placement> placements, OrderingStrategy strategy, OrderingContext ctx,
                       BuildSettings settings, String label) {
        List<Placement> ordered = strategy.order(placements, ctx);
        long id = nextId++;
        jobs.addLast(new BuildJob(id, ordered, strategy.name(), label,
                settings.blocksPerTick(), settings.placeFlags()));
        return id;
    }

    /** Called once per server tick for this world. Lays the active job's next batch. */
    public void tick() {
        BuildJob job = jobs.peekFirst();
        if (job == null || job.isPaused()) {
            return;
        }
        for (Placement p : job.nextBatch(job.blocksPerTick())) {
            placer.place(p, job.placeFlags(), job.undoLog()); // capture prior states for /map2mc undo
        }
        if (job.isDone()) {
            jobs.pollFirst();
            placer.releaseAll();
            UndoManager.push(world, job.undoLog()); // make the finished build undoable
            Map2mc.LOGGER.info("[map2mc] build complete: {} ({} blocks)", job.label(), job.total());
        }
    }

    public BuildJob active() {
        return jobs.peekFirst();
    }

    public boolean pauseActive() {
        BuildJob j = jobs.peekFirst();
        if (j == null) return false;
        j.pause();
        return true;
    }

    public boolean resumeActive() {
        BuildJob j = jobs.peekFirst();
        if (j == null) return false;
        j.resume();
        return true;
    }

    public boolean cancelActive() {
        BuildJob j = jobs.pollFirst();
        if (j == null) return false;
        placer.releaseAll();
        UndoManager.push(world, j.undoLog()); // a partially-placed build is still undoable
        return true;
    }

    public boolean setActiveBlocksPerTick(int n) {
        BuildJob j = jobs.peekFirst();
        if (j == null) return false;
        j.setBlocksPerTick(n);
        return true;
    }

    public String statusLine() {
        BuildJob j = jobs.peekFirst();
        if (j == null) {
            return "idle (no active build)";
        }
        int remaining = j.total() - j.placed();
        int etaSec = (int) Math.ceil(remaining / (double) (j.blocksPerTick() * 20));
        return String.format("[%s] %s: %d/%d (%.1f%%) — %s, %d b/t, ~%ds left%s",
                j.strategyName(), j.label(), j.placed(), j.total(), j.progress() * 100,
                j.isPaused() ? "PAUSED" : "running", j.blocksPerTick(), etaSec,
                jobs.size() > 1 ? " (+" + (jobs.size() - 1) + " queued)" : "");
    }
}
