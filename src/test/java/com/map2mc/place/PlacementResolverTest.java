package com.map2mc.place;

import com.map2mc.schem.Rotation4;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the shared placement geometry ({@link PlacementResolver#computePositions}).
 * Uses a boolean occupancy grid so no BlockState/registry/bootstrap is needed — the same trick the
 * Phase 1 ordering/pacing tests use. Covers corner vs center anchor, each rotation, and ground-snap.
 */
class PlacementResolverTest {

    private static final BlockPos A = new BlockPos(100, 64, 200);

    /** Occupancy index in schematic storage order (x fastest, then z, then y). */
    private static int idx(int x, int y, int z, int w, int l) {
        return (y * l + z) * w + x;
    }

    /** A horizontal 3-long bar along X (w=3, l=1, h=1) at the origin cell. */
    private static boolean[] barAlongX() {
        boolean[] occ = new boolean[3];
        occ[idx(0, 0, 0, 3, 1)] = true;
        occ[idx(1, 0, 0, 3, 1)] = true;
        occ[idx(2, 0, 0, 3, 1)] = true;
        return occ;
    }

    private static Set<BlockPos> set(List<BlockPos> ps) {
        return new HashSet<>(ps);
    }

    @Test
    void cornerAnchorNoRotation() {
        List<BlockPos> ps = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CORNER, Rotation4.NONE, true);
        assertEquals(Set.of(A.add(0, 0, 0), A.add(1, 0, 0), A.add(2, 0, 0)), set(ps));
    }

    @Test
    void centerAnchorCentersFootprint() {
        // footprint X=3 -> offX = -(3/2) = -1; Z=1 -> offZ = 0. Bar straddles the anchor.
        List<BlockPos> ps = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CENTER_GROUND, Rotation4.NONE, true);
        assertEquals(Set.of(A.add(-1, 0, 0), A.add(0, 0, 0), A.add(1, 0, 0)), set(ps));
    }

    @Test
    void rotationCw90TurnsBarAlongZ() {
        // CW90: rotatedX = l-1-z = 0; rotatedZ = x -> the X-bar becomes a Z-bar (corner-anchored).
        List<BlockPos> ps = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CORNER, Rotation4.CW90, true);
        assertEquals(Set.of(A.add(0, 0, 0), A.add(0, 0, 1), A.add(0, 0, 2)), set(ps));
    }

    @Test
    void rotationCw90CenteredSwapsFootprint() {
        // CW90 swaps footprint: X-extent = l = 1 (offX 0), Z-extent = w = 3 (offZ -1).
        List<BlockPos> ps = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CENTER_GROUND, Rotation4.CW90, true);
        assertEquals(Set.of(A.add(0, 0, -1), A.add(0, 0, 0), A.add(0, 0, 1)), set(ps));
    }

    @Test
    void rotationCw180MirrorsAlongX() {
        // CW180: rotatedX = w-1-x -> {2,1,0}; same set as none for a symmetric bar, corner-anchored.
        List<BlockPos> ps = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CORNER, Rotation4.CW180, true);
        assertEquals(Set.of(A.add(0, 0, 0), A.add(1, 0, 0), A.add(2, 0, 0)), set(ps));
    }

    @Test
    void groundSnapLiftsLowestOccupiedLayerToAnchorY() {
        // single cell at y=1 (w=1,l=1,h=2). With snap, it lands at anchor.y; without, at anchor.y+1.
        boolean[] occ = new boolean[2];
        occ[idx(0, 1, 0, 1, 1)] = true;
        List<BlockPos> snapped = PlacementResolver.computePositions(
                1, 2, 1, occ, A, AnchorMode.CORNER, Rotation4.NONE, true);
        List<BlockPos> raw = PlacementResolver.computePositions(
                1, 2, 1, occ, A, AnchorMode.CORNER, Rotation4.NONE, false);
        assertEquals(Set.of(A.add(0, 0, 0)), set(snapped));
        assertEquals(Set.of(A.add(0, 1, 0)), set(raw));
    }

    @Test
    void resolverIsDeterministic_previewMatchesHero() {
        // Both /preview and /hero call resolve() (which routes through this core). Same inputs MUST
        // yield identical position lists — the guarantee that the outline matches the real build.
        List<BlockPos> a = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CENTER_GROUND, Rotation4.CW90, true);
        List<BlockPos> b = PlacementResolver.computePositions(
                3, 1, 1, barAlongX(), A, AnchorMode.CENTER_GROUND, Rotation4.CW90, true);
        assertEquals(a, b);
    }
}
