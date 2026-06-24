package com.map2mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /map2mc set|get} tuning registry: assigns, validates ranges, and reports back. */
class Map2mcConfigTest {

    @Test
    void setValidatesAssignsAndReports() {
        double origFov = Map2mcConfig.filmBaseFovDeg;
        double origCam = Map2mcConfig.filmHeroCamFrac;
        try {
            // valid → assigns and returns the stored value as text
            assertEquals("60.0", Map2mcConfig.set("filmBaseFovDeg", "60"));
            assertEquals(60.0, Map2mcConfig.filmBaseFovDeg, 1e-9);

            // key is case-insensitive
            assertEquals("75.0", Map2mcConfig.set("FILMBASEFOVDEG", "75"));
            assertEquals(75.0, Map2mcConfig.filmBaseFovDeg, 1e-9);

            // out of range → fails loudly and does NOT apply
            assertThrows(IllegalArgumentException.class, () -> Map2mcConfig.set("filmBaseFovDeg", "700"));
            assertEquals(75.0, Map2mcConfig.filmBaseFovDeg, 1e-9, "rejected value must not be applied");
            assertThrows(IllegalArgumentException.class, () -> Map2mcConfig.set("filmHeroCamFrac", "5")); // >1
            assertThrows(IllegalArgumentException.class, () -> Map2mcConfig.set("filmBaseFovDeg", "abc"));

            // unknown key → throws
            assertThrows(IllegalArgumentException.class, () -> Map2mcConfig.set("nope", "1"));

            // registry exposes the framing knobs; describe() lines match keys()
            assertTrue(Map2mcConfig.keys().contains("filmBaseFovDeg"));
            assertTrue(Map2mcConfig.keys().contains("filmHeroAimFrac"));
            assertEquals(Map2mcConfig.keys().size(), Map2mcConfig.describe().size());
        } finally {
            Map2mcConfig.filmBaseFovDeg = origFov;
            Map2mcConfig.filmHeroCamFrac = origCam;
        }
    }
}
