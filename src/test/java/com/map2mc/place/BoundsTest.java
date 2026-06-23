package com.map2mc.place;

import com.map2mc.build.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link Bounds} (BlockState is never read, so placements carry null). */
class BoundsTest {

    private static Placement at(int x, int y, int z) {
        return new Placement(new BlockPos(x, y, z), null);
    }

    @Test
    void tightBoundsAndSizes() {
        Bounds b = Bounds.fromPlacements(List.of(at(0, 0, 0), at(2, 4, 6), at(1, 1, 1)));
        assertEquals(0, b.minX());
        assertEquals(2, b.maxX());
        assertEquals(4, b.maxY());
        assertEquals(6, b.maxZ());
        assertEquals(3, b.sizeX());
        assertEquals(5, b.sizeY());
        assertEquals(7, b.sizeZ());
    }

    @Test
    void centerBaseAndGeometricCenter() {
        Bounds b = Bounds.fromPlacements(List.of(at(0, 0, 0), at(2, 4, 6)));
        assertEquals(new BlockPos(1, 0, 3), b.centerBase());
        Vec3d c = b.center();
        assertEquals(1.5, c.x, 1e-9);
        assertEquals(2.5, c.y, 1e-9);
        assertEquals(3.5, c.z, 1e-9);
        assertEquals(3.5, b.horizontalRadius(), 1e-9); // max(3,7)/2
    }

    @Test
    void edgeSamplesStayWithinTheEnclosingBox() {
        Bounds b = Bounds.fromPlacements(List.of(at(0, 0, 0), at(2, 4, 6)));
        List<Vec3d> pts = b.edgeSamples(1.0);
        assertFalse(pts.isEmpty());
        for (Vec3d p : pts) {
            assertTrue(p.x >= b.minX() && p.x <= b.maxX() + 1, "x in box");
            assertTrue(p.y >= b.minY() && p.y <= b.maxY() + 1, "y in box");
            assertTrue(p.z >= b.minZ() && p.z <= b.maxZ() + 1, "z in box");
        }
    }

    @Test
    void emptyIsDegenerateNotCrash() {
        Bounds b = Bounds.fromPlacements(List.of());
        assertEquals(1, b.sizeX());
    }
}
