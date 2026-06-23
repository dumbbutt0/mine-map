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
}
