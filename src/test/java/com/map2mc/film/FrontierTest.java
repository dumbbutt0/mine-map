package com.map2mc.film;

import com.map2mc.build.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the frontier window math. Placements carry a null BlockState (only positions
 * matter); no Minecraft bootstrap needed.
 */
class FrontierTest {

    @Test
    void windowStartClampsAndSizes() {
        assertEquals(0, Frontier.windowStart(0, 256), "nothing placed");
        assertEquals(0, Frontier.windowStart(5, 256), "window larger than cursor → from 0");
        assertEquals(744, Frontier.windowStart(1000, 256));
        assertEquals(999, Frontier.windowStart(1000, 1), "window of 1 → just the last block");
        assertEquals(999, Frontier.windowStart(1000, 0), "window coerced to ≥1");
    }

    @Test
    void centroidIsAverageOfBlockCentres() {
        List<Placement> window = List.of(
                new Placement(new BlockPos(0, 0, 0), null),
                new Placement(new BlockPos(2, 4, 6), null));
        Frontier f = Frontier.ofWindow(window, Vec3d.ZERO);
        // centres are (0.5,0.5,0.5) and (2.5,4.5,6.5) → mean (1.5, 2.5, 3.5)
        assertEquals(1.5, f.centroid().x, 1e-9);
        assertEquals(2.5, f.centroid().y, 1e-9);
        assertEquals(3.5, f.centroid().z, 1e-9);
        assertEquals(2, f.count());
    }

    @Test
    void emptyWindowFallsBackWithCountZero() {
        Frontier f = Frontier.ofWindow(List.of(), new Vec3d(7, 8, 9));
        assertEquals(0, f.count(), "0 → caller holds the progress-0 pose (camera waits)");
        assertEquals(new Vec3d(7, 8, 9), f.centroid());
    }

    @Test
    void bboxCoversTheWindow() {
        List<Placement> window = List.of(
                new Placement(new BlockPos(-3, 10, 4), null),
                new Placement(new BlockPos(5, 12, -1), null));
        Frontier f = Frontier.ofWindow(window, Vec3d.ZERO);
        assertEquals(-3, f.bbox().minX());
        assertEquals(5, f.bbox().maxX());
        assertEquals(10, f.bbox().minY());
        assertEquals(12, f.bbox().maxY());
    }
}
