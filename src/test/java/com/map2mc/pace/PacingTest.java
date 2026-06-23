package com.map2mc.pace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure-logic tests for duration-driven pacing + the manual override (no Minecraft needed). */
class PacingTest {

    @AfterEach
    void resetPolicy() {
        PaceSetting.reset();
    }

    @Test
    void ceilDivisionForKnownCounts() {
        // The in-scope Eiffel (34,840 non-air) over 120s: ceil(34840 / 2400) = 15 b/t.
        assertEquals(15, Pacing.blocksPerTick(34840, 120));
        // The fixture (62) over 120s rounds up to the floor of 1 b/t.
        assertEquals(1, Pacing.blocksPerTick(62, 120));
        // Exact division and just-over both behave (ceil).
        assertEquals(1, Pacing.blocksPerTick(2400, 120));
        assertEquals(2, Pacing.blocksPerTick(2401, 120));
        // Tighter window raises the rate.
        assertEquals(4, Pacing.blocksPerTick(62, 1));
    }

    @Test
    void instantLaysEverythingAtOnce() {
        assertEquals(500, Pacing.blocksPerTick(500, 0));
    }

    @Test
    void estimateRoughlyMatchesTarget() {
        // 34,840 at 15 b/t ≈ 116s — within tolerance of the 120s target.
        int eta = Pacing.estimateSeconds(34840, 15);
        assertEquals(117, eta); // ceil(ceil(34840/15)/20) = ceil(2323/20) = 117
    }

    @Test
    void durationPolicyDerivesFromCount() {
        PaceSetting.setDuration(120);
        assertEquals(15, PaceSetting.resolveBpt(34840));
        assertEquals(1, PaceSetting.resolveBpt(62));
    }

    @Test
    void manualOverrideBypassesDuration() {
        PaceSetting.setManual(7);
        // ignores the block count entirely — explicit b/t wins.
        assertEquals(7, PaceSetting.resolveBpt(34840));
        assertEquals(7, PaceSetting.resolveBpt(1));
    }

    @Test
    void instantPolicyResolvesToCount() {
        PaceSetting.setInstant();
        assertEquals(34840, PaceSetting.resolveBpt(34840));
    }

    @Test
    void defaultIsDurationDriven() {
        PaceSetting.reset();
        assertEquals(PaceSetting.Mode.DURATION, PaceSetting.mode());
        // default duration is 120s (Map2mcConfig.defaultDurationSeconds)
        assertEquals(15, PaceSetting.resolveBpt(34840));
    }
}
