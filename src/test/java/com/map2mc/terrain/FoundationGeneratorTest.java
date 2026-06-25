package com.map2mc.terrain;

import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure foundation geometry (plan): bridge below the base, clear above, nothing on flat ground. */
class FoundationGeneratorTest {

    private static final Bounds FOOT = new Bounds(0, 0, 0, 2, 100, 2); // 3×3 footprint

    @Test
    void bridgesBelowAndClearsAbove() {
        int[] surf = new int[9];
        Arrays.fill(surf, 64);
        surf[0] = 60; // column (x=0,z=0): 4 below the base 64
        surf[8] = 67; // column (x=2,z=2): 3 above the base
        List<FoundationGenerator.Cell> cells = FoundationGenerator.plan(FOOT, 64, surf, 24);

        long fill = cells.stream().filter(FoundationGenerator.Cell::fill).count();
        long clear = cells.stream().filter(c -> !c.fill()).count();
        assertEquals(4, fill, "bridge-down fill [60..63] for the low column");
        assertEquals(3, clear, "clear-above [64..66] for the high column");
        // fill is the low column at the right Ys
        assertTrue(cells.stream().filter(FoundationGenerator.Cell::fill).allMatch(
                c -> c.pos().getX() == 0 && c.pos().getZ() == 0 && c.pos().getY() >= 60 && c.pos().getY() < 64));
        // clear is the high column above the base
        assertTrue(cells.stream().filter(c -> !c.fill()).allMatch(
                c -> c.pos().getX() == 2 && c.pos().getZ() == 2 && c.pos().getY() >= 64 && c.pos().getY() < 67));
        // never outside the footprint
        assertTrue(cells.stream().allMatch(
                c -> c.pos().getX() >= 0 && c.pos().getX() <= 2 && c.pos().getZ() >= 0 && c.pos().getZ() <= 2));
    }

    @Test
    void flatGroundAtTheBaseNeedsNothing() {
        int[] surf = new int[9];
        Arrays.fill(surf, 64);
        assertTrue(FoundationGenerator.plan(FOOT, 64, surf, 24).isEmpty(), "flat ground → no foundation");
    }

    @Test
    void bridgeFillIsCappedAtMaxBridge() {
        Bounds oneByOne = new Bounds(0, 0, 0, 0, 100, 0);
        List<FoundationGenerator.Cell> cells = FoundationGenerator.plan(oneByOne, 64, new int[]{0}, 10);
        assertEquals(10, cells.size(), "fill capped to maxBridge, not all the way to y=0");
        assertTrue(cells.stream().allMatch(c -> c.pos().getY() >= 54 && c.fill()));
    }
}
