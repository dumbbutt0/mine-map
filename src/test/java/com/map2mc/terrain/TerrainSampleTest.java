package com.map2mc.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure terrain stats: min/median/max/slope + the water/void flag. No Minecraft bootstrap. */
class TerrainSampleTest {

    @Test
    void statsOverAGentleSlope() {
        TerrainSample s = TerrainSample.of(new int[]{60, 62, 64, 64, 66, 68}, new boolean[6]);
        assertEquals(60, s.minY());
        assertEquals(68, s.maxY());
        assertEquals(8, s.slope());
        assertTrue(s.medianY() >= 62 && s.medianY() <= 66, "median in the middle, got " + s.medianY());
        assertFalse(s.hasWaterOrVoid());
    }

    @Test
    void flatGroundIsZeroSlope() {
        TerrainSample s = TerrainSample.of(new int[]{64, 64, 64, 64}, new boolean[4]);
        assertEquals(0, s.slope());
        assertEquals(64, s.medianY());
    }

    @Test
    void waterOrVoidFlagSet() {
        TerrainSample s = TerrainSample.of(new int[]{64, 63, 65}, new boolean[]{false, true, false});
        assertTrue(s.hasWaterOrVoid());
    }

    @Test
    void maxBridgeDepthIsBaseMinusLowestColumn() {
        TerrainSample s = TerrainSample.of(new int[]{50, 60, 64}, new boolean[3]);
        assertEquals(14, s.maxBridgeDepth(64)); // 64 - 50
        assertEquals(0, s.maxBridgeDepth(40), "no fill when the base is below all ground");
    }
}
