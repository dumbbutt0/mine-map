package com.map2mc.terrain;

import com.map2mc.place.Bounds;
import com.map2mc.terrain.PlazaDecorator.Cell;
import com.map2mc.terrain.PlazaDecorator.Deco;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-geometry tests for the plaza layout (no Minecraft runtime), mirroring FoundationGeneratorTest. */
class PlazaDecoratorTest {

    private static final Bounds REGION = new Bounds(-20, 60, -20, 20, 200, 20); // 41×41 square
    private static final int BASE_Y = 64;

    @Test
    void landscapesTheDiscWithLawnAndTrees() {
        Bounds footprint = new Bounds(-5, BASE_Y, -5, 5, 100, 5); // 11×11 in the centre
        List<Cell> plan = PlazaDecorator.plan(REGION, BASE_Y, footprint, 2);
        assertFalse(plan.isEmpty(), "plaza should produce cells");
        assertTrue(plan.stream().anyMatch(c -> c.kind() == Deco.LAWN), "has lawn");
        assertTrue(plan.stream().anyMatch(c -> c.kind() == Deco.TRUNK), "has tree trunks");
        assertTrue(plan.stream().anyMatch(c -> c.kind() == Deco.LEAF), "has tree leaves");
    }

    @Test
    void surfaceDecorationNeverEntersTheFootprint() {
        Bounds footprint = new Bounds(-5, BASE_Y, -5, 5, 100, 5);
        int fMinX = -6, fMaxX = 6, fMinZ = -6, fMaxZ = 6; // footprint grown by 1 (the code's clearance)
        for (Cell c : PlazaDecorator.plan(REGION, BASE_Y, footprint, 2)) {
            if (c.kind() == Deco.LAWN || c.kind() == Deco.PATH || c.kind() == Deco.FLOWER) {
                boolean inFootprint = c.pos().getX() >= fMinX && c.pos().getX() <= fMaxX
                        && c.pos().getZ() >= fMinZ && c.pos().getZ() <= fMaxZ;
                assertFalse(inFootprint, "surface deco must leave the structure footprint clear");
            }
        }
    }

    @Test
    void surfaceCellsLieWithinThePlazaDisc() {
        Bounds footprint = new Bounds(-2, BASE_Y, -2, 2, 100, 2);
        double cx = (REGION.minX() + REGION.maxX() + 1) / 2.0;
        double cz = (REGION.minZ() + REGION.maxZ() + 1) / 2.0;
        double radius = Math.min(REGION.sizeX(), REGION.sizeZ()) / 2.0;
        for (Cell c : PlazaDecorator.plan(REGION, BASE_Y, footprint, 1)) {
            if (c.kind() == Deco.LAWN || c.kind() == Deco.PATH) {
                double dx = (c.pos().getX() + 0.5) - cx;
                double dz = (c.pos().getZ() + 0.5) - cz;
                assertTrue(dx * dx + dz * dz <= radius * radius + 1e-6, "lawn/path stays inside the disc");
            }
        }
    }

    @Test
    void zeroRingsPlantsNoTreesButStillLawns() {
        Bounds footprint = new Bounds(-5, BASE_Y, -5, 5, 100, 5);
        List<Cell> plan = PlazaDecorator.plan(REGION, BASE_Y, footprint, 0);
        assertTrue(plan.stream().noneMatch(c -> c.kind() == Deco.TRUNK), "no trees with 0 rings");
        assertTrue(plan.stream().anyMatch(c -> c.kind() == Deco.LAWN), "lawn still present");
    }
}
