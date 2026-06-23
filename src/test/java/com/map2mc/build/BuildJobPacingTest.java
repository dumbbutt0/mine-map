package com.map2mc.build;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pacing/draining logic for {@link BuildJob}. Verifies the core Phase 1 gate claim at the unit
 * level: blocks-per-tick controls how many ticks a build takes (the speed), and the cursor drains
 * exactly the placement list with no loss or duplication. (Same package so {@code nextBatch} is
 * visible.)
 */
class BuildJobPacingTest {

    private static List<Placement> placements(int n) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ps.add(new Placement(new BlockPos(0, i, 0), null));
        }
        return ps;
    }

    /** Drain a job at {@code bpt} blocks/tick; assert invariants; return the tick count taken. */
    private static int drainTicks(int total, int bpt) {
        BuildJob job = new BuildJob(1, placements(total), "ASCENDING_Y", "t", bpt, 0);
        int ticks = 0, placed = 0;
        while (!job.isDone()) {
            List<Placement> batch = job.nextBatch(job.blocksPerTick());
            assertTrue(batch.size() >= 1 && batch.size() <= bpt, "batch within [1, bpt]");
            placed += batch.size();
            ticks++;
            assertTrue(ticks <= total + 1, "must terminate");
        }
        assertEquals(total, placed, "every block placed exactly once");
        assertEquals(total, job.placed(), "cursor reached total");
        return ticks;
    }

    @Test
    void drainsInCeilTotalOverBptTicks() {
        assertEquals((int) Math.ceil(100 / 8.0), drainTicks(100, 8));   // 13
        assertEquals(4, drainTicks(10, 3));                              // 3,3,3,1
        assertEquals(1, drainTicks(5, 50));                             // bpt > total → one tick
    }

    @Test
    void higherBlocksPerTickFinishesInFewerTicks() {
        int slow = drainTicks(100, 5);   // 20
        int fast = drainTicks(100, 25);  // 4
        assertEquals(20, slow);
        assertEquals(4, fast);
        assertTrue(fast < slow, "more blocks/tick must finish sooner — this IS the speed control");
    }

    @Test
    void pauseAndResumeToggleFlag() {
        BuildJob job = new BuildJob(1, placements(10), "ASCENDING_Y", "t", 4, 0);
        assertFalse(job.isPaused());
        job.pause();
        assertTrue(job.isPaused());
        job.resume();
        assertFalse(job.isPaused());
    }

    @Test
    void blocksPerTickFloorsAtOne() {
        BuildJob job = new BuildJob(1, placements(3), "ASCENDING_Y", "t", 0, 0);
        assertEquals(1, job.blocksPerTick(), "0 b/t must be coerced to 1 so the queue never stalls");
    }
}
