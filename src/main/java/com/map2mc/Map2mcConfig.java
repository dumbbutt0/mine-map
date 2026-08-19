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
    public static volatile int filmViewDistance = 32;

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

    /** Zoom multiplier on the computed framing distance: 1.0 = the build fills the frame edge-to-edge,
     *  &gt;1 pulls back. Tightened from 1.10 so the build DOMINATES the frame (was reading "too far"). */
    public static volatile double filmFrameMargin = 1.0;

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

    /** Min height (fraction of FINAL height) before the aerial→orbit cut — "something to look UP at".
     *  Combined with the area-profile transition (cut once BOTH the base is done AND this is reached). */
    public static volatile double filmAerialUntilHeightFrac = 0.12;

    /** Downward elevation (degrees) of the aerial look (high oblique, not flat top-down). Eased 55→50→38
     *  to keep the steep-pitch-pan horizon calmer (the canted/spinning aerial in early takes); lower
     *  further (or drop filmAerialTurns) live to taste. */
    public static volatile double filmAerialPitchDeg = 38.0;

    /** Zoom-out multiplier on the aerial footprint framing. */
    public static volatile double filmAerialMargin = 1.10;

    /** Azimuth rotation (turns) across the aerial phase, so it drifts rather than sitting static. Eased
     *  0.30→0.12 so the overhead establishing shot drifts gently instead of visibly spinning. */
    public static volatile double filmAerialTurns = 0.12;

    /** Area-profile handoff: a layer counts as "narrowed" (base→vertical) below this fraction of the
     *  widest layer. The aerial→orbit cut fires at the cumulative-block progress of that transition;
     *  filmAerialUntilHeightFrac stays a height backstop (and the sole trigger when there's no clear
     *  transition). Lower → only a sharper narrowing counts; the transition Y itself is set by the build. */
    public static volatile double filmTransitionFrac = 0.40;

    /** Absolute Y the aerial establishing camera is capped below, so the build start isn't "mixed into
     *  the clouds" (vanilla clouds sit at y≈192). */
    public static volatile int filmCloudCeilingY = 180;

    /** When a film/record ends, leave the player FLOATING in spectator (true), or RETURN them to their
     *  pre-film gamemode (false, the default — creative restored in place so they can fly down and land;
     *  survival lands at the pre-film ground spot first). Set true to finish a shot by hand in spectator. */
    public static volatile boolean filmEndStaysSpectator = false;

    // ── Terrain-aware foundation (normal generated worlds) ───────────────────────────────────────
    // In a non-superflat world the build samples the live ground and lays a foundation that grounds it
    // at local grade (bridge low side, clear high side) so it sits naturally instead of floating.

    /** Generate a terrain-integrating foundation in non-superflat worlds (false = drop at the anchor). */
    public static volatile boolean foundationEnabled = true;

    /** Ground slope (blocks, max−min) above which the site counts as notably sloped (informational warn). */
    public static volatile int foundationSlopeThreshold = 4;

    /** Max depth the foundation will bridge below the base before warning the spot is too steep/deep. */
    public static volatile int foundationMaxBridge = 24;

    /** Foundation fill material: {@code auto} (dominant surface block) | {@code stone|cobble|dirt}. */
    public static volatile String foundationStyle = "auto";

    /** Blocks beyond the footprint the foundation also LEVELS (clear terrain + vegetation, fill to grade)
     *  — a plaza border so no terrain wall or tree canopy hugs/looms over the build. */
    public static volatile int foundationClearMargin = 3;

    // ── Auto-capture (ServerReplay, via /map2mc record) ──────────────────────────────────────────
    /** Extra chunks of surrounding scenery captured around the build footprint by {@code /map2mc record}
     *  (a larger value frames more landscape in the recorded .mcpr). */
    public static volatile int recordChunkMargin = 4;

    /** Seconds to spend VISIBLY laying the site-prep (strip + plaza) during {@code /map2mc record}, so
     *  it's part of the timelapse instead of an instant blink. 0 = instant (hero/film always use 0). */
    public static volatile int recordSitePrepSeconds = 12;

    /** Absolute dir to save recordings under. When set, map2mc points ServerReplay's
     *  chunk_recording_path here (auto-patched at server start) and writes its orbit tables here too.
     *  Empty = ServerReplay's default + {@code <gameDir>/map2mc/orbits}. Baked to the user's Videos dir. */
    public static volatile String recordOutputDir = "C:/Users/dumbb/Videos/map2mc";

    // ── Plaza landscaping (lush grounds around the build, not an empty circle) ────────────────────
    /** Landscape the cleared plaza (grass lawn + path motif + biome trees + flowers) instead of a bare disc. */
    public static volatile boolean plazaDecorate = true;

    /** Concentric rings of trees planted around the build on the plaza (0 = none). */
    public static volatile int plazaTreeRings = 2;

    // ── Scene / style (the first field of a future swappable "style profile") ─────────────────────
    /** Time of day to lock at the start of {@code /map2mc record} and {@code /map2mc film} (so takes
     *  aren't shot under a low, bloomy morning sun). A keyword (noon/day/sunset/night/midnight/sunrise/
     *  golden), a raw tick (0–24000), or {@code off} to leave the world time alone. This is a STYLE
     *  choice (a warm look may want golden); it will move into a named style profile when that lands. */
    public static volatile String filmTimeOfDay = "noon";

    /** Override the structure-shape classification that picks the camera's shot:
     *  {@code auto} (classify) | narrowing | tall | wide | linear | squat. Lets you cheaply correct a
     *  misread shape without waiting on a classifier fix (same idea as the live framing knobs). */
    public static volatile String filmStructureClass = "auto";

    /** Resolve {@link #filmTimeOfDay} to a daytime tick, or -1 to leave time unchanged ({@code off}). */
    public static int timeOfDayTicks() {
        String v = filmTimeOfDay == null ? "" : filmTimeOfDay.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "off" -> -1;
            case "day" -> 1000;
            case "noon" -> 6000;
            case "sunset" -> 12000;
            case "golden" -> 12300;
            case "night" -> 13000;
            case "midnight" -> 18000;
            case "sunrise" -> 23000;
            default -> {
                try {
                    yield Math.floorMod(Integer.parseInt(v), 24000);
                } catch (NumberFormatException e) {
                    yield 6000; // unreachable (setter validates) — default to noon
                }
            }
        };
    }

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

    private static final java.util.Set<String> TIME_KEYWORDS = java.util.Set.of(
            "off", "day", "noon", "sunset", "golden", "night", "midnight", "sunrise");

    /** Validate a time-of-day value: a keyword, {@code off}, or a tick in [0, 24000]. */
    private static String timeOfDayIn(String v) {
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (TIME_KEYWORDS.contains(s)) {
            return s;
        }
        try {
            int t = Integer.parseInt(s);
            if (t < 0 || t > 24000) {
                throw new IllegalArgumentException("tick must be in [0, 24000]");
            }
            return s;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be a tick [0,24000] or one of " + String.join("|", TIME_KEYWORDS));
        }
    }

    private static String strIn(String v, String... allowed) {
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(s)) {
                return s;
            }
        }
        throw new IllegalArgumentException("must be one of " + String.join("|", allowed));
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
            new Knob("filmAerialTurns", () -> String.valueOf(filmAerialTurns), v -> filmAerialTurns = dblIn(v, 0, 4)),
            new Knob("filmTransitionFrac", () -> String.valueOf(filmTransitionFrac), v -> filmTransitionFrac = dblIn(v, 0.01, 1)),
            new Knob("filmCloudCeilingY", () -> String.valueOf(filmCloudCeilingY), v -> filmCloudCeilingY = intIn(v, 64, 320)),
            new Knob("filmEndStaysSpectator", () -> String.valueOf(filmEndStaysSpectator), v -> filmEndStaysSpectator = boolIn(v)),
            new Knob("foundationEnabled", () -> String.valueOf(foundationEnabled), v -> foundationEnabled = boolIn(v)),
            new Knob("foundationSlopeThreshold", () -> String.valueOf(foundationSlopeThreshold), v -> foundationSlopeThreshold = intIn(v, 0, 256)),
            new Knob("foundationMaxBridge", () -> String.valueOf(foundationMaxBridge), v -> foundationMaxBridge = intIn(v, 1, 256)),
            new Knob("foundationStyle", () -> foundationStyle, v -> foundationStyle = strIn(v, "auto", "stone", "cobble", "dirt")),
            new Knob("foundationClearMargin", () -> String.valueOf(foundationClearMargin), v -> foundationClearMargin = intIn(v, 0, 64)),
            new Knob("recordChunkMargin", () -> String.valueOf(recordChunkMargin), v -> recordChunkMargin = intIn(v, 0, 32)),
            new Knob("recordSitePrepSeconds", () -> String.valueOf(recordSitePrepSeconds), v -> recordSitePrepSeconds = intIn(v, 0, 600)),
            new Knob("recordOutputDir", () -> recordOutputDir.isEmpty() ? "(default)" : recordOutputDir, v -> recordOutputDir = v.trim()),
            new Knob("plazaDecorate", () -> String.valueOf(plazaDecorate), v -> plazaDecorate = boolIn(v)),
            new Knob("plazaTreeRings", () -> String.valueOf(plazaTreeRings), v -> plazaTreeRings = intIn(v, 0, 6)),
            new Knob("filmTimeOfDay", () -> filmTimeOfDay, v -> filmTimeOfDay = timeOfDayIn(v)),
            new Knob("filmStructureClass", () -> filmStructureClass,
                    v -> filmStructureClass = strIn(v, "auto", "narrowing", "tall", "wide", "linear", "squat")));

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
