package com.map2mc.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * A single block to place: an absolute world position and the state to set there.
 * The atomic unit the {@link BuildQueue} consumes.
 */
public record Placement(BlockPos pos, BlockState state) {
}
