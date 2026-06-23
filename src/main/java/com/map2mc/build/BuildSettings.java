package com.map2mc.build;

import net.minecraft.block.Block;

/**
 * Per-submission knobs for a build.
 *
 * @param blocksPerTick how many blocks the queue lays down each server tick (× 20 TPS = blocks/sec)
 * @param placeFlags    {@code World#setBlockState} flags (see {@link net.minecraft.block.Block})
 * @param groundSnap    whether a schematic should be snapped to the surface (used by Phase 2)
 */
public record BuildSettings(int blocksPerTick, int placeFlags, boolean groundSnap) {

    public BuildSettings {
        if (blocksPerTick < 1) {
            blocksPerTick = 1; // never stall the queue
        }
    }

    /**
     * The cinematic default. {@code NOTIFY_LISTENERS} (0x2) makes clients re-render each placed
     * block but does NOT trigger neighbor/physics updates — so falling blocks (sand, gravel) and
     * other reactive blocks don't collapse the structure mid-timelapse.
     */
    public static BuildSettings cinematicDefault() {
        return new BuildSettings(8, Block.NOTIFY_LISTENERS, true);
    }
}
