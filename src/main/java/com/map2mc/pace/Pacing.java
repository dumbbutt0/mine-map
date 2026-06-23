package com.map2mc.pace;

/**
 * Duration-driven pacing math (pure, unit-tested). The user thinks in target <i>duration</i>, not
 * block rate, so we derive blocks-per-tick from a loaded build's non-air block count:
 *
 * <pre>blocks_per_tick = ceil(total_blocks / (duration_seconds * 20))   // 20 ticks/sec</pre>
 *
 * e.g. the in-scope Eiffel (34,840 non-air) at 120s → ceil(34840 / 2400) = 15 b/t → ~116s.
 */
public final class Pacing {

    public static final int TICKS_PER_SECOND = 20;

    private Pacing() {
    }

    /** Blocks per tick to finish {@code totalNonAir} placements in about {@code durationSeconds}. */
    public static int blocksPerTick(int totalNonAir, int durationSeconds) {
        if (totalNonAir <= 0) {
            return 1;
        }
        if (durationSeconds <= 0) {
            return totalNonAir; // instant: lay everything in one tick
        }
        long ticks = (long) durationSeconds * TICKS_PER_SECOND;
        long bpt = (totalNonAir + ticks - 1) / ticks; // ceil division
        return (int) Math.max(1, bpt);
    }

    /** Estimated seconds a build of {@code totalNonAir} takes at {@code bpt} (for feedback). */
    public static int estimateSeconds(int totalNonAir, int bpt) {
        if (bpt <= 0) {
            return 0;
        }
        long ticks = ((long) totalNonAir + bpt - 1) / bpt;
        return (int) Math.ceil(ticks / (double) TICKS_PER_SECOND);
    }
}
