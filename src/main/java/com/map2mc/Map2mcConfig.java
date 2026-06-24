package com.map2mc;

/**
 * Small, in-memory tunables shared across commands. Defaults are chosen for the recording workflow
 * (a ~2-minute Eiffel, framed in front of the player). These are process-global (not persisted);
 * per-build pacing lives in {@link com.map2mc.pace.PaceSetting} instead.
 */
public final class Map2mcConfig {

    private Map2mcConfig() {
    }

    // ── Phase 2.5: placement ────────────────────────────────────────────────────────────────────
    /** How far in front of the player {@code /map2mc hero <file>} (no coords) drops the build. */
    public static volatile double frontDistance = 10.0;

    /** How long a {@code /map2mc preview} outline lingers before auto-clearing (ticks; 20 = 1s). */
    public static volatile int previewDurationTicks = 600; // ~30s

    // ── Phase 3: cinematic ──────────────────────────────────────────────────────────────────────
    /** Default build duration when pacing is duration-driven (the dry-run target ≈ 2 minutes). */
    public static volatile int defaultDurationSeconds = 120;

    /** Default orbit keyframe count. */
    public static volatile int orbitKeyframes = 8;

    /** Default orbit playback (render) length in seconds — a 2-min build compresses to this. */
    public static volatile int orbitRenderSeconds = 20;

    /** Orbit radius = this × the build's horizontal half-extent, when radius isn't given. */
    public static volatile double orbitRadiusFactor = 2.0;

    // ── Phase A: director camera (frontier-tracking film) ────────────────────────────────────────
    /** How many recently-placed blocks define the construction "frontier" the camera tracks. */
    public static volatile int frontierWindow = 256;

    /** Full revolutions the director orbit makes across the whole build (progress 0→1). */
    public static volatile int filmOrbitTurns = 1;

    /** Blocks the camera sits ABOVE the frontier centroid (so it looks slightly down at the front). */
    public static volatile double filmCameraRise = 10.0;

    /** Blocks above the frontier centroid the camera AIMS (0 = look straight at the front). */
    public static volatile double filmAimLeadY = 2.0;

    /** View (render) distance in chunks to raise the client to WHILE filming, restored on stop.
     *  Reduces "build lost in fog/clip" — but shader volumetric/distance fog is the user's Iris
     *  setting (see RECORDING.md §E), which the mod can't and shouldn't override. */
    public static volatile int filmViewDistance = 24;

    // ── V2-1: aspect-aware vertical + hero framing ───────────────────────────────────────────────
    // The mod CANNOT resize the game window, so the framing is sized for a TARGET aspect the user
    // records at (a portrait MC window, or ReplayMod at 1080×1920) — not the dev window. baseFovDeg
    // is the vertical FOV the math assumes; tune it to match the recording. The recorded look is the
    // real gate.

    /** Target frame aspect numerator/denominator (default 9:16 portrait). */
    public static volatile int filmAspectW = 9;
    public static volatile int filmAspectH = 16;

    /** Vertical FOV (degrees) the framing math assumes; match your recording FOV. */
    public static volatile double filmBaseFovDeg = 70.0;

    /** Safety zoom-out multiplier on the computed distance (>1 pulls back a touch). */
    public static volatile double filmFrameMargin = 1.10;

    /** Headroom above the build top, as a fraction of build height, when fitting by height. */
    public static volatile double filmHeadroomFrac = 0.15;

    /** Hero up-look: camera height as a fraction of the current build height (low → it looms). */
    public static volatile double filmHeroCamFrac = 0.35;

    /** Hero up-look: aim height as a fraction of the current build height (high → camera looks UP). */
    public static volatile double filmHeroAimFrac = 0.85;

    // ── Aerial establishing shot for the low-height phase ────────────────────────────────────────
    // A wide-based build (footprint but little height) seen by the rising orbit is a distant strip on
    // grass. So the low phase gets a high-oblique AERIAL shot looking DOWN at the footprint, then CUTS
    // to the rising orbit once the build is tall enough.

    /** Aerial establishing shot for the low phase (false = pure V2-1 rising orbit). */
    public static volatile boolean filmAerialEnabled = true;

    /** Switch from the aerial shot to the rising orbit at this fraction of FINAL height. */
    public static volatile double filmAerialUntilHeightFrac = 0.22;

    /** Downward elevation (degrees) of the aerial look (high oblique, not flat top-down). Eased from 55
     *  to keep the steep-pitch-pan horizon calmer; lower further (or drop filmAerialTurns) live to taste. */
    public static volatile double filmAerialPitchDeg = 50.0;

    /** Zoom-out multiplier on the aerial footprint framing. */
    public static volatile double filmAerialMargin = 1.10;

    /** Azimuth rotation (turns) across the aerial phase, so it drifts rather than sitting static. */
    public static volatile double filmAerialTurns = 0.30;

    // ── Runtime tuning registry (/map2mc set | get) ──────────────────────────────────────────────
    // Tune the knobs above live while dialing in the framing — no rebuild. Values are RUNTIME-ONLY
    // (process-global, not persisted): they reset on relaunch, so once you like them, bake the
    // numbers in as the defaults above. Single-player shares this JVM with the client, so both
    // client-read (filmViewDistance) and server-read (framing) knobs take effect immediately.

    @FunctionalInterface
    private interface Setter {
        void set(String raw); // throws IllegalArgumentException on a bad/out-of-range value
    }

    private record Knob(String name, java.util.function.Supplier<String> read, Setter write) {
    }

    private static int intIn(String v, int min, int max) {
        final int i;
        try {
            i = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' is not an integer");
        }
        if (i < min || i > max) {
            throw new IllegalArgumentException("must be an integer in [" + min + ", " + max + "]");
        }
        return i;
    }

    private static double dblIn(String v, double min, double max) {
        final double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' is not a number");
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d < min || d > max) {
            throw new IllegalArgumentException("must be a number in [" + min + ", " + max + "]");
        }
        return d;
    }

    private static boolean boolIn(String v) {
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "1", "yes", "y" -> true;
            case "false", "off", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("'" + v + "' is not a boolean (true/false/on/off)");
        };
    }

    // Registry order = the order `/map2mc get` lists them (the V2-1 framing knobs grouped last).
    private static final java.util.List<Knob> KNOBS = java.util.List.of(
            new Knob("frontDistance", () -> String.valueOf(frontDistance), v -> frontDistance = dblIn(v, 0, 256)),
            new Knob("defaultDurationSeconds", () -> String.valueOf(defaultDurationSeconds), v -> defaultDurationSeconds = intIn(v, 1, 3600)),
            new Knob("orbitRadiusFactor", () -> String.valueOf(orbitRadiusFactor), v -> orbitRadiusFactor = dblIn(v, 0.1, 20)),
            new Knob("frontierWindow", () -> String.valueOf(frontierWindow), v -> frontierWindow = intIn(v, 1, 100000)),
            new Knob("filmOrbitTurns", () -> String.valueOf(filmOrbitTurns), v -> filmOrbitTurns = intIn(v, 1, 20)),
            new Knob("filmCameraRise", () -> String.valueOf(filmCameraRise), v -> filmCameraRise = dblIn(v, -256, 256)),
            new Knob("filmAimLeadY", () -> String.valueOf(filmAimLeadY), v -> filmAimLeadY = dblIn(v, -256, 256)),
            new Knob("filmViewDistance", () -> String.valueOf(filmViewDistance), v -> filmViewDistance = intIn(v, 2, 32)),
            new Knob("filmAspectW", () -> String.valueOf(filmAspectW), v -> filmAspectW = intIn(v, 1, 100)),
            new Knob("filmAspectH", () -> String.valueOf(filmAspectH), v -> filmAspectH = intIn(v, 1, 100)),
            new Knob("filmBaseFovDeg", () -> String.valueOf(filmBaseFovDeg), v -> filmBaseFovDeg = dblIn(v, 30, 110)),
            new Knob("filmFrameMargin", () -> String.valueOf(filmFrameMargin), v -> filmFrameMargin = dblIn(v, 0.5, 4.0)),
            new Knob("filmHeadroomFrac", () -> String.valueOf(filmHeadroomFrac), v -> filmHeadroomFrac = dblIn(v, 0, 1)),
            new Knob("filmHeroCamFrac", () -> String.valueOf(filmHeroCamFrac), v -> filmHeroCamFrac = dblIn(v, 0, 1)),
            new Knob("filmHeroAimFrac", () -> String.valueOf(filmHeroAimFrac), v -> filmHeroAimFrac = dblIn(v, 0, 1)),
            new Knob("filmAerialEnabled", () -> String.valueOf(filmAerialEnabled), v -> filmAerialEnabled = boolIn(v)),
            new Knob("filmAerialUntilHeightFrac", () -> String.valueOf(filmAerialUntilHeightFrac), v -> filmAerialUntilHeightFrac = dblIn(v, 0, 1)),
            new Knob("filmAerialPitchDeg", () -> String.valueOf(filmAerialPitchDeg), v -> filmAerialPitchDeg = dblIn(v, 20, 85)),
            new Knob("filmAerialMargin", () -> String.valueOf(filmAerialMargin), v -> filmAerialMargin = dblIn(v, 0.5, 4)),
            new Knob("filmAerialTurns", () -> String.valueOf(filmAerialTurns), v -> filmAerialTurns = dblIn(v, 0, 4)));

    /** Set a knob by (case-insensitive) name from text; validates + assigns; returns the stored value. */
    public static String set(String key, String value) {
        for (Knob k : KNOBS) {
            if (k.name().equalsIgnoreCase(key)) {
                k.write().set(value);
                return k.read().get();
            }
        }
        throw new IllegalArgumentException("unknown key '" + key + "'");
    }

    /** All tunable knob names, in declaration order (for command tab-completion). */
    public static java.util.List<String> keys() {
        return KNOBS.stream().map(Knob::name).toList();
    }

    /** {@code name = value} for every knob (for the {@code /map2mc get} listing). */
    public static java.util.List<String> describe() {
        return KNOBS.stream().map(k -> k.name() + " = " + k.read().get()).toList();
    }
}
