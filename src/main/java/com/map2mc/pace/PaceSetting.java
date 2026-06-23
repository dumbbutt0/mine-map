package com.map2mc.pace;

import com.map2mc.Map2mcConfig;

/**
 * The current pacing policy for the NEXT hero build, set by {@code /map2mc pace}. Process-global
 * (the recording workflow runs one build at a time). Duration-driven is the DEFAULT; an explicit
 * blocks-per-tick override is always available, satisfying the brief's "manual b/t must remain".
 *
 * <p>This only chooses the <i>initial</i> blocks-per-tick at submit time; {@code /map2mc speed}
 * still live-retunes the active build via {@link com.map2mc.build.BuildQueue#setActiveBlocksPerTick}.
 */
public final class PaceSetting {

    public enum Mode { DURATION, MANUAL, INSTANT }

    private static Mode mode = Mode.DURATION;
    private static int durationSeconds = Map2mcConfig.defaultDurationSeconds; // 120 by default
    private static int manualBpt = 8;

    private PaceSetting() {
    }

    public static void setDuration(int seconds) {
        mode = Mode.DURATION;
        durationSeconds = Math.max(1, seconds);
    }

    public static void setManual(int bpt) {
        mode = Mode.MANUAL;
        manualBpt = Math.max(1, bpt);
    }

    public static void setInstant() {
        mode = Mode.INSTANT;
    }

    /** Reset to the configured default (duration-driven). Used by tests + a fresh session. */
    public static void reset() {
        mode = Mode.DURATION;
        durationSeconds = Map2mcConfig.defaultDurationSeconds;
        manualBpt = 8;
    }

    /** Resolve the blocks-per-tick for a build of {@code totalNonAir} blocks under this policy. */
    public static int resolveBpt(int totalNonAir) {
        return switch (mode) {
            case DURATION -> Pacing.blocksPerTick(totalNonAir, durationSeconds);
            case MANUAL -> manualBpt;
            case INSTANT -> Math.max(1, totalNonAir);
        };
    }

    public static Mode mode() {
        return mode;
    }

    public static String describe() {
        return switch (mode) {
            case DURATION -> "duration " + durationSeconds + "s";
            case MANUAL -> "manual " + manualBpt + " b/t";
            case INSTANT -> "instant";
        };
    }
}
