package com.map2mc.build;

import java.util.List;

/**
 * One build in flight: an already-ordered list of placements plus a cursor marking how far we've
 * gotten. Pacing ({@code blocksPerTick}) lives on the job so {@code /map2mc speed} can retune the
 * active build live. The ordering was applied once at submit time — this class never re-sorts.
 */
public final class BuildJob {

    private final long id;
    private final List<Placement> ordered;
    private final String strategyName;
    private final String label;
    private final int placeFlags;

    private int blocksPerTick;
    private int cursor = 0;
    private boolean paused = false;

    public BuildJob(long id, List<Placement> ordered, String strategyName, String label,
                    int blocksPerTick, int placeFlags) {
        this.id = id;
        this.ordered = ordered;
        this.strategyName = strategyName;
        this.label = label;
        this.blocksPerTick = Math.max(1, blocksPerTick);
        this.placeFlags = placeFlags;
    }

    public long id() {
        return id;
    }

    public int total() {
        return ordered.size();
    }

    public int placed() {
        return cursor;
    }

    public boolean isDone() {
        return cursor >= ordered.size();
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public float progress() {
        return ordered.isEmpty() ? 1f : (float) cursor / ordered.size();
    }

    public String label() {
        return label;
    }

    public String strategyName() {
        return strategyName;
    }

    public int blocksPerTick() {
        return blocksPerTick;
    }

    public void setBlocksPerTick(int n) {
        this.blocksPerTick = Math.max(1, n);
    }

    public int placeFlags() {
        return placeFlags;
    }

    /** Returns up to {@code n} not-yet-placed placements and advances the cursor past them. */
    List<Placement> nextBatch(int n) {
        int end = Math.min(cursor + n, ordered.size());
        List<Placement> batch = ordered.subList(cursor, end);
        cursor = end;
        return batch;
    }
}
