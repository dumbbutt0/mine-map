package com.map2mc.terrain;

import java.util.Arrays;

/**
 * Pure summary of the live ground under a build's footprint — min / median / max surface Y, the slope
 * (max−min), and whether any column is water or void (no solid ground) — plus how MANY columns are,
 * so a probe can report the magnitude, not just the flag. No Minecraft access, so it is
 * unit-tested on synthetic height grids; {@link TerrainSampler} builds the grid from the world.
 *
 * <p>Each surface Y is "the Y a build sits ON" (the first non-solid block above the ground, i.e.
 * vanilla {@code getTopY}). The site decision picks {@code medianY} as the build base so the structure
 * sits at LOCAL GRADE rather than floating at y=0.
 */
public record TerrainSample(int minY, int medianY, int maxY, int slope, boolean hasWaterOrVoid,
                            int waterOrVoidColumns) {

    /**
     * @param surfaceY    per-column surface Y (any iteration order — only order-independent stats used)
     * @param waterOrVoid parallel flags: true where a column is over water or has no solid ground
     */
    public static TerrainSample of(int[] surfaceY, boolean[] waterOrVoid) {
        if (surfaceY.length == 0) {
            return new TerrainSample(0, 0, 0, 0, false, 0);
        }
        int[] sorted = surfaceY.clone();
        Arrays.sort(sorted);
        int min = sorted[0];
        int max = sorted[sorted.length - 1];
        int median = sorted[sorted.length / 2];
        int wet = 0;
        for (boolean b : waterOrVoid) {
            if (b) {
                wet++;
            }
        }
        return new TerrainSample(min, median, max, max - min, wet > 0, wet);
    }

    /** Deepest fill needed to bridge the lowest column up to {@code baseY}. */
    public int maxBridgeDepth(int baseY) {
        return Math.max(0, baseY - minY);
    }
}
