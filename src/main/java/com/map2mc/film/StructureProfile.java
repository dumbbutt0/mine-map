package com.map2mc.film;

import com.map2mc.place.Bounds;

import java.util.Locale;

/**
 * Classifies a build's SHAPE so the camera can pick a shot by structure type instead of one
 * tower-tuned, shape-blind gate (see CAMERA_SCALABILITY.md). Pure + deterministic → unit-tested.
 *
 * <p>It emits its REASONING (the raw dims/ratios it decided from), not just a label: the future shot
 * planner reads those features directly, and a misclassification is debuggable from the log line rather
 * than a bare enum. The classifier intentionally produces only the one axis the shot logic currently
 * lacks — the {@link AspectClass}; richer fields (symmetry, hero features, palette) are added later, when
 * a specific shot consumes them.
 */
public record StructureProfile(int sizeX, int sizeY, int sizeZ,
                               double linearRatio, double vertRatio, double handoff,
                               boolean narrowing, AspectClass aspectClass, String reasoning) {

    /** The shape axis the shot selector branches on. */
    public enum AspectClass {
        NARROWING_VERTICAL, // a tower that narrows with height (the Eiffel) — aerial → rising orbit
        TALL,               // tall but ~uniform (statue / pillar / skyscraper)
        WIDE_LOW,           // much wider than tall (a plaza/complex) — wants a high/top-down orbit
        LINEAR,             // one horizontal axis dominates (bridge/wall) — wants a tracking dolly
        SQUAT;              // roughly cubic — orbit + detail push-ins

        /** Parse a manual-override token; {@code null} for "auto"/unknown (→ classify). */
        public static AspectClass fromOverride(String s) {
            return switch (s == null ? "" : s.trim().toLowerCase(Locale.ROOT)) {
                case "narrowing", "narrowing_vertical", "tower" -> NARROWING_VERTICAL;
                case "tall" -> TALL;
                case "wide", "wide_low", "widelow" -> WIDE_LOW;
                case "linear" -> LINEAR;
                case "squat" -> SQUAT;
                default -> null; // "auto" or anything unrecognized
            };
        }
    }

    /** Thresholds (tunable constants; the probe validates the archetype mapping). */
    private static final double LINEAR_RATIO = 3.0; // long axis ≥ 3× the short → linear
    private static final double TALL_RATIO = 1.5;   // height ≥ 1.5× the footprint → tall
    private static final double WIDE_RATIO = 0.6;   // height ≤ 0.6× the footprint → wide-low

    /** Classify from bounds + area profile (auto, no override). */
    public static StructureProfile classify(Bounds b, int[] area, double transitionFrac) {
        return resolve(b, area, transitionFrac, "auto");
    }

    /**
     * Classify, honoring a manual {@code override} ({@code auto} = heuristic). Features are always
     * computed (so the report/reasoning is populated even when forced).
     */
    public static StructureProfile resolve(Bounds b, int[] area, double transitionFrac, String override) {
        int sx = b.sizeX(), sy = b.sizeY(), sz = b.sizeZ();
        double fpLong = Math.max(sx, sz);
        double fpShort = Math.max(1, Math.min(sx, sz));
        double linearRatio = fpLong / fpShort;
        double vertRatio = sy / Math.max(1.0, fpLong);
        double handoff = AreaProfile.handoffProgress(area, transitionFrac);
        boolean narrowing = handoff >= 0.0;

        AspectClass heuristic;
        if (linearRatio >= LINEAR_RATIO) {
            heuristic = AspectClass.LINEAR;
        } else if (vertRatio >= TALL_RATIO) {
            heuristic = narrowing ? AspectClass.NARROWING_VERTICAL : AspectClass.TALL;
        } else if (vertRatio <= WIDE_RATIO) {
            heuristic = AspectClass.WIDE_LOW;
        } else {
            heuristic = AspectClass.SQUAT;
        }

        AspectClass forced = AspectClass.fromOverride(override);
        AspectClass chosen = forced != null ? forced : heuristic;
        String reasoning = String.format(Locale.ROOT,
                "%dx%dx%d  linearRatio=%.2f vertRatio=%.2f handoff=%.2f narrowing=%b → %s%s",
                sx, sy, sz, linearRatio, vertRatio, handoff, narrowing, chosen,
                forced != null ? " (forced via filmStructureClass; heuristic said " + heuristic + ")" : "");
        return new StructureProfile(sx, sy, sz, linearRatio, vertRatio, handoff, narrowing, chosen, reasoning);
    }
}
