package com.map2mc.orbit;

import com.map2mc.schem.SchemPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the orbit as a documented, human-enterable keyframe table.
 *
 * <p><b>Why a table, not a direct {@code .mcpr} write:</b> ReplayMod stores camera paths in an
 * undocumented, version-fragile {@code timelines.json} inside the {@code .mcpr} zip, and map2mc keeps
 * recording user-operated by design. So we emit a table whose columns are exactly ReplayMod's
 * Position-keyframe fields (x, y, z, yaw, pitch, roll) plus the time each keyframe sits at — type
 * these into ReplayMod's keyframe editor (or use {@code /map2mc orbit … fly} to have the mod fly the
 * camera along the same path in-game).
 */
public final class OrbitExporter {

    private OrbitExporter() {
    }

    /** Render the keyframe table to a string (also used for the chat preview). */
    public static String render(List<OrbitKeyframe> kfs, Orbit.Params p, int renderSeconds,
                                LastBuild.Info build) {
        StringBuilder sb = new StringBuilder();
        sb.append("# map2mc orbit — ReplayMod camera keyframes\n");
        sb.append("# build: ").append(build.label())
                .append("  (~").append(build.buildSeconds()).append("s to lay down)\n");
        sb.append(String.format("# center=(%.1f, %.1f) aimY=%.1f radius=%.1f keyframes=%d render=%ds%n",
                p.cx(), p.cz(), p.aimY(), p.radius(), kfs.size(), renderSeconds));
        sb.append("# Enter each row as a ReplayMod Position keyframe; 'render_s' is its time on the\n");
        sb.append("# camera path, 'replay_s' is where it lands in the recorded build timeline.\n");
        sb.append(String.format("#%4s %10s %10s %10s %8s %8s %6s %9s %9s%n",
                "idx", "x", "y", "z", "yaw", "pitch", "roll", "render_s", "replay_s"));
        int i = 0;
        for (OrbitKeyframe k : kfs) {
            sb.append(String.format("%5d %10.2f %10.2f %10.2f %8.2f %8.2f %6.0f %9.2f %9.2f%n",
                    i++, k.x(), k.y(), k.z(), k.yaw(), k.pitch(), k.roll(),
                    k.renderTimeMs() / 1000.0, k.replayTimeMs() / 1000.0));
        }
        return sb.toString();
    }

    /** Render + write the table under {@code <gameDir>/map2mc/orbits/}; returns the file path. */
    public static Path export(List<OrbitKeyframe> kfs, Orbit.Params p, int renderSeconds,
                              LastBuild.Info build) throws IOException {
        Files.createDirectories(SchemPaths.orbitDir());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = SchemPaths.orbitDir().resolve("orbit-" + stamp + ".txt");
        Files.writeString(out, render(kfs, p, renderSeconds, build));
        return out;
    }
}
