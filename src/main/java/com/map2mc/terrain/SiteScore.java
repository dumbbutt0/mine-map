package com.map2mc.terrain;

import java.util.Locale;

/**
 * Pure scorer for how well a candidate site suits a fixed build — higher is better. No Minecraft
 * access, so it unit-tests on plain inputs. The "wonky / plopped" look comes from a site that forces
 * heavy bridging or clearing (steep / deep) or sits in a mismatched biome (water / desert / badlands);
 * a flat, dry, green spot lets the foundation barely touch the terrain. The weights encode exactly that.
 */
public final class SiteScore {

    private SiteScore() {
    }

    /**
     * @param slope        terrain max−min over the footprint (flatter = better)
     * @param waterOrVoid  any footprint column over water or with no solid ground (a big disqualifier)
     * @param desert       sandy/badlands surface (looks bare/out of place for most landmarks)
     * @param bridgeDepth  how far the lowest column sits below the chosen base (fill needed = ruggedness)
     * @param biomeBonus   from {@link #biomeBonus(String)} — rewards lush land, penalizes ocean/peaks
     */
    public static double score(int slope, boolean waterOrVoid, boolean desert, int bridgeDepth,
                               double biomeBonus) {
        double s = 100.0;
        s -= Math.max(0, slope) * 4.0;
        s -= Math.max(0, bridgeDepth) * 3.0;
        if (waterOrVoid) {
            s -= 50.0;
        }
        if (desert) {
            s -= 25.0;
        }
        return s + biomeBonus;
    }

    /** Biome-family adjustment by id path: lush land rewarded, water/steep/barren penalized. */
    public static double biomeBonus(String biomePath) {
        String b = biomePath == null ? "" : biomePath.toLowerCase(Locale.ROOT);
        if (b.contains("ocean") || b.contains("river") || b.contains("swamp") || b.contains("beach")) {
            return -30;
        }
        if (b.contains("badlands") || b.contains("desert")) {
            return -20;
        }
        if (b.contains("peaks") || b.contains("mountain") || b.contains("windswept")) {
            return -15; // typically steep — wonky footing for a big footprint
        }
        if (b.contains("cherry")) {
            return 25; // the reference look
        }
        if (b.contains("meadow") || b.contains("plains") || b.contains("forest") || b.contains("grove")
                || b.contains("taiga") || b.contains("savanna") || b.contains("jungle") || b.contains("birch")) {
            return 15;
        }
        return 0;
    }
}
