package com.map2mc.build.ordering;

import com.map2mc.build.Placement;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the ordering strategies. Placements carry a {@code null} BlockState on
 * purpose — ordering only ever reads {@code pos()}, so no registry/bootstrap is needed.
 */
class OrderingStrategyTest {

    /** A scrambled little cloud of positions spanning several Y layers. */
    private static List<Placement> sample() {
        List<Placement> ps = new ArrayList<>();
        int[][] coords = {
                {0, 3, 0}, {2, 0, -2}, {-1, 1, 1}, {0, 0, 0}, {3, 2, 3},
                {-3, 0, 3}, {1, 3, -1}, {0, 2, 0}, {-2, 1, -2}, {2, 0, 2},
        };
        for (int[] c : coords) {
            ps.add(new Placement(new BlockPos(c[0], c[1], c[2]), null));
        }
        return ps;
    }

    @Test
    void ascendingYProducesNonDecreasingY() {
        List<Placement> in = sample();
        List<Placement> out = new AscendingYStrategy().order(in, OrderingContext.fromPlacements(in));

        assertEquals(in.size(), out.size(), "ordering must not drop or add placements");
        int prevY = Integer.MIN_VALUE;
        for (Placement p : out) {
            assertTrue(p.pos().getY() >= prevY,
                    "Y went backwards at " + p.pos() + " (prev " + prevY + ")");
            prevY = p.pos().getY();
        }
    }

    @Test
    void ascendingYDoesNotMutateInput() {
        List<Placement> in = sample();
        Placement first = in.get(0);
        new AscendingYStrategy().order(in, OrderingContext.fromPlacements(in));
        assertEquals(first, in.get(0), "input list must be left untouched");
    }

    @Test
    void radialOutProducesNonDecreasingDistanceFromCenter() {
        List<Placement> in = sample();
        OrderingContext ctx = OrderingContext.fromPlacements(in);
        List<Placement> out = new RadialOutStrategy().order(in, ctx);

        int cx = ctx.center().getX();
        int cz = ctx.center().getZ();
        long prevD2 = Long.MIN_VALUE;
        for (Placement p : out) {
            long dx = p.pos().getX() - cx;
            long dz = p.pos().getZ() - cz;
            long d2 = dx * dx + dz * dz;
            assertTrue(d2 >= prevD2,
                    "radial distance decreased at " + p.pos() + " (" + d2 + " < " + prevD2 + ")");
            prevD2 = d2;
        }
    }

    @Test
    void supportsFirstMatchesAscendingYOrderForNow() {
        List<Placement> in = sample();
        OrderingContext ctx = OrderingContext.fromPlacements(in);
        List<Placement> asc = new AscendingYStrategy().order(in, ctx);
        List<Placement> sup = new SupportsFirstStrategy().order(in, ctx);
        assertEquals(asc, sup, "SUPPORTS_FIRST MVP must currently alias ASCENDING_Y");
        assertEquals("SUPPORTS_FIRST", new SupportsFirstStrategy().name());
    }
}
