package com.map2mc.film;

import com.map2mc.schem.SchematicData;

/**
 * Offline analysis of a schematic's per-layer block counts (the "area profile"), used to choose the
 * aerial→orbit handoff. A build transitions from a wide BASE (high per-layer count) to a narrow
 * VERTICAL body (low per-layer count); that DROP is where the camera should stop looking down and
 * start orbiting. Pure and deterministic — computed once from the schematic, independent of the
 * within-layer placement scan that fooled the motion-based detector. (The early slice of the V2-2 analyzer.)
 */
public final class AreaProfile {

    private AreaProfile() {
    }

    /** Non-air block count per Y layer (index 0 = the bottom layer). */
    public static int[] areaPerLayer(SchematicData d) {
        int[] area = new int[d.height()];
        for (int y = 0; y < d.height(); y++) {
            int c = 0;
            for (int z = 0; z < d.length(); z++) {
                for (int x = 0; x < d.width(); x++) {
                    if (!"minecraft:air".equals(d.palette()[d.paletteIndexAt(x, y, z)])) {
                        c++;
                    }
                }
            }
            area[y] = c;
        }
        return area;
    }

    /**
     * The build PROGRESS [0,1] at which to hand off from the overhead to the rising orbit: the
     * cumulative block fraction below the first layer that NARROWS to under {@code transitionFrac} of
     * the widest layer (after a wide base). Returns {@code -1} when there is NO clear base→vertical
     * transition (a uniform tower or slab), so the caller falls back to the height threshold.
     */
    public static double handoffProgress(SchematicData d, double transitionFrac) {
        return handoffProgress(areaPerLayer(d), transitionFrac);
    }

    /** As {@link #handoffProgress(SchematicData, double)} but from a precomputed profile (unit-tested). */
    public static double handoffProgress(int[] area, double transitionFrac) {
        long total = 0;
        int maxArea = 0;
        for (int a : area) {
            total += a;
            maxArea = Math.max(maxArea, a);
        }
        if (total <= 0 || maxArea <= 0) {
            return -1.0; // empty → defer to the height fallback
        }
        double baseThreshold = transitionFrac * maxArea;
        boolean sawBase = false;
        int handoffY = -1;
        for (int y = 0; y < area.length; y++) {
            if (area[y] >= baseThreshold) {
                sawBase = true;
            } else if (sawBase) {
                handoffY = y; // first layer that narrows below the base, after a wide base
                break;
            }
        }
        if (handoffY < 0) {
            return -1.0; // never narrowed (uniform tower/slab) → defer to the height fallback
        }
        long below = 0;
        for (int y = 0; y < handoffY; y++) {
            below += area[y];
        }
        return Math.max(0.0, Math.min(1.0, below / (double) total));
    }
}
