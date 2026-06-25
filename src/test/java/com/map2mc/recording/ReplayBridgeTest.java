package com.map2mc.recording;

import com.map2mc.place.Bounds;
import com.map2mc.recording.ReplayBridge.ChunkRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the ServerReplay bridge (no Minecraft runtime). These pin the one fragile
 * coupling — the region math and the exact command strings — so a ServerReplay syntax change is a
 * one-line fix caught by a failing test rather than a silent in-game no-op.
 */
class ReplayBridgeTest {

    @Test
    void regionSquareCoversTheBuildFootprint() {
        // Build occupying blocks x[0..47] z[0..31] -> chunks x[0..2], z[0..1].
        Bounds b = new Bounds(0, 64, 0, 47, 200, 31);
        ChunkRegion r = ReplayBridge.regionFor(b, 0);
        // The recorded square [c-r .. c+r] must contain the whole chunk extent in both axes.
        assertTrue(r.cx() - r.radius() <= 0 && r.cx() + r.radius() >= 2, "covers chunk X span [0..2]");
        assertTrue(r.cz() - r.radius() <= 0 && r.cz() + r.radius() >= 1, "covers chunk Z span [0..1]");
    }

    @Test
    void oneChunkBuildStillRecordsAtLeastRadiusOne() {
        Bounds oneChunk = new Bounds(0, 64, 0, 15, 200, 15); // single chunk (0,0)
        ChunkRegion r = ReplayBridge.regionFor(oneChunk, 0);
        assertEquals(0, r.cx());
        assertEquals(0, r.cz());
        assertEquals(1, r.radius(), "minimum sensible region is 3x3");
    }

    @Test
    void marginExpandsTheRecordedRegion() {
        Bounds oneChunk = new Bounds(0, 64, 0, 15, 200, 15);
        assertEquals(1, ReplayBridge.regionFor(oneChunk, 0).radius());
        assertEquals(4, ReplayBridge.regionFor(oneChunk, 4).radius(), "margin adds chunks each way");
    }

    @Test
    void negativeCoordinatesMapToChunksCorrectly() {
        // Blocks x[-20..-1] -> chunks x[-2..-1]; z[-1..0] -> chunks z[-1..0].
        Bounds b = new Bounds(-20, 64, -1, -1, 200, 0);
        ChunkRegion r = ReplayBridge.regionFor(b, 0);
        assertTrue(r.cx() - r.radius() <= -2 && r.cx() + r.radius() >= -1, "covers chunk X span [-2..-1]");
        assertTrue(r.cz() - r.radius() <= -1 && r.cz() + r.radius() >= 0, "covers chunk Z span [-1..0]");
    }

    @Test
    void startCommandMatchesServerReplaySyntax() {
        ChunkRegion r = new ChunkRegion(3, -5, 7);
        assertEquals("replay start chunks around 3 -5 radius 7 in minecraft:overworld named map2mc_42",
                ReplayBridge.startCommand(r, "minecraft:overworld", "map2mc_42"));
    }

    @Test
    void stopCommandSavesByName() {
        assertEquals("replay stop chunks named map2mc_42 true", ReplayBridge.stopCommand("map2mc_42"));
    }
}
