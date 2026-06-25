package com.map2mc.terrain;

import com.map2mc.place.Bounds;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure foundation geometry: bridge below the base, clear terrain+vegetation above, nothing on flat bare ground. */
class FoundationGeneratorTest {

    private static final Bounds REGION = new Bounds(0, 0, 0, 2, 100, 2); // 3×3 (footprint + margin)

    @Test
    void bridgesBelowAndClearsVegetationAbove() {
        int[] solid = new int[9];
        int[] veg = new int[9];
        Arrays.fill(solid, 64);
        Arrays.fill(veg, 64);
        solid[0] = 60;
        veg[0] = 60;            // column (0,0): solid 4 below the base 64 → 4 bridge-fill
        solid[8] = 64;
        veg[8] = 67;            // column (2,2): a TREE (ground at base, leaves to 67) → 3 clear ABOVE the base

        List<FoundationGenerator.Cell> cells = FoundationGenerator.plan(REGION, 64, solid, veg, 24);
        assertEquals(4, cells.stream().filter(FoundationGenerator.Cell::fill).count(), "bridge fill [60..63]");
        assertEquals(3, cells.stream().filter(c -> !c.fill()).count(), "clear vegetation [64..66]");
        // the clear is the tree column, above the base, up to (not incl.) the vegetation top
        assertTrue(cells.stream().filter(c -> !c.fill()).allMatch(
                c -> c.pos().getX() == 2 && c.pos().getZ() == 2 && c.pos().getY() >= 64 && c.pos().getY() < 67));
    }

    @Test
    void flatBareGroundAtTheBaseNeedsNothing() {
        int[] flat = new int[9];
        Arrays.fill(flat, 64);
        assertTrue(FoundationGenerator.plan(REGION, 64, flat, flat, 24).isEmpty(), "flat bare ground → nothing");
    }

    @Test
    void bridgeFillIsCappedAtMaxBridge() {
        Bounds one = new Bounds(0, 0, 0, 0, 100, 0);
        List<FoundationGenerator.Cell> cells = FoundationGenerator.plan(one, 64, new int[]{0}, new int[]{0}, 10);
        assertEquals(10, cells.size(), "fill capped to maxBridge, not down to y=0");
        assertTrue(cells.stream().allMatch(c -> c.pos().getY() >= 54 && c.fill()));
    }
}
