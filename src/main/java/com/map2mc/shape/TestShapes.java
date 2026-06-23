package com.map2mc.shape;

import com.map2mc.build.Placement;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic test geometry for Phase 1 — no schematic needed. These exist purely to exercise the
 * {@link com.map2mc.build.BuildQueue} (pacing + bottom-up ordering) with deterministic, countable
 * shapes.
 */
public final class TestShapes {

    private TestShapes() {
    }

    /**
     * A hollow square tower whose footprint tapers from a wider base to a narrow top — light on
     * block count and reads clearly as "rising" in a timelapse. {@code base} is the center of the
     * bottom layer.
     */
    public static List<Placement> hollowTaperingTower(int height, BlockPos base) {
        List<Placement> ps = new ArrayList<>();
        BlockState wall = Blocks.STONE_BRICKS.getDefaultState();
        BlockState cap = Blocks.WAXED_EXPOSED_COPPER.getDefaultState();
        int baseHalf = Math.max(2, height / 8);
        for (int y = 0; y < height; y++) {
            int half = Math.max(1, baseHalf - (y * (baseHalf - 1)) / Math.max(1, height - 1));
            BlockState s = (y == height - 1) ? cap : wall;
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    boolean edge = (x == -half || x == half || z == -half || z == half);
                    if (edge) {
                        ps.add(new Placement(base.add(x, y, z), s));
                    }
                }
            }
        }
        return ps;
    }

    /**
     * A solid stepped pyramid: layer {@code y} is a square of half-width {@code (height-1-y)}, so
     * it shrinks toward the top. Deterministic block census makes it the workhorse for the
     * Phase 1 GameTest. {@code base} is the center of the bottom layer.
     */
    public static List<Placement> steppedPyramid(int height, BlockPos base) {
        List<Placement> ps = new ArrayList<>();
        BlockState body = Blocks.STONE.getDefaultState();
        BlockState top = Blocks.WAXED_EXPOSED_COPPER.getDefaultState();
        for (int y = 0; y < height; y++) {
            int half = height - 1 - y;
            BlockState s = (y == height - 1) ? top : body;
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    ps.add(new Placement(base.add(x, y, z), s));
                }
            }
        }
        return ps;
    }

    /** Exact block count of {@link #steppedPyramid} of a given height: sum of (2k+1)^2 for k=0..h-1. */
    public static int steppedPyramidBlockCount(int height) {
        int total = 0;
        for (int y = 0; y < height; y++) {
            int side = 2 * (height - 1 - y) + 1;
            total += side * side;
        }
        return total;
    }
}
