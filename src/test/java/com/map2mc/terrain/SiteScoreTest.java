package com.map2mc.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the site scorer — a flat, dry, green spot must rank above steep/water/desert ones. */
class SiteScoreTest {

    @Test
    void flatGreenSiteBeatsSteepWaterAndDesert() {
        double flatGreen = SiteScore.score(1, false, false, 0, SiteScore.biomeBonus("plains"));
        double steep = SiteScore.score(15, false, false, 8, SiteScore.biomeBonus("plains"));
        double water = SiteScore.score(2, true, false, 1, SiteScore.biomeBonus("plains"));
        double desert = SiteScore.score(2, false, true, 1, SiteScore.biomeBonus("desert"));
        assertTrue(flatGreen > steep, "flat beats steep+deep");
        assertTrue(flatGreen > water, "dry beats water");
        assertTrue(flatGreen > desert, "green beats desert");
    }

    @Test
    void biomeBonusRanksLushAboveOcean() {
        assertTrue(SiteScore.biomeBonus("cherry_grove") > SiteScore.biomeBonus("plains"), "cherry is ideal");
        assertTrue(SiteScore.biomeBonus("plains") > SiteScore.biomeBonus("ocean"), "land beats ocean");
        assertTrue(SiteScore.biomeBonus("forest") > SiteScore.biomeBonus("desert"), "lush beats barren");
    }

    @Test
    void lessBridgingScoresHigher() {
        double shallow = SiteScore.score(3, false, false, 1, 0);
        double deep = SiteScore.score(3, false, false, 20, 0);
        assertTrue(shallow > deep, "shallower bridging is preferred");
    }
}
