package com.map2mc.probe;

import com.map2mc.schem.SchemPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders a {@link ProbeReport} as a structured JSON scorecard and writes it under
 * {@code <gameDir>/map2mc/probes/<schem base name>.json}. JSON is hand-written (like
 * {@link com.map2mc.orbit.OrbitExporter} writes its keyframe table) — no JSON library, and
 * {@link #render} is pure so the schema is unit-testable with no Minecraft bootstrap.
 *
 * <p><b>Fixed schema (schemaVersion 1)</b> — consumed by the Phase-R harness (SPEC §8), so field
 * names are a contract:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "schem":     { "name", "width", "height", "length", "nonAir", "footprintAspect" },
 *   "placement": { "anchor": [x,y,z], "rotation", "baseY",
 *                  "bounds": { "min": [x,y,z], "max": [x,y,z] } },
 *   "site":      { "slope", "waterOrVoid", "desert", "bridgeDepth", "biome",
 *                  "biomeBonus", "total" },                       // the SiteScore components
 *   "guardrails":{ "clip":              { "flag", "magnitude" },  // blocks of terrain above base
 *                  "float":             { "flag", "magnitude" },  // deepest gap below base
 *                  "halfBuried":        { "flag", "magnitude" },  // buried depth / build height
 *                  "underwater":        { "flag", "magnitude" },  // water/void column count
 *                  "bridgeCapExceeded": { "flag", "magnitude" } },// blocks past foundationMaxBridge
 *   "foundation":{ "fillCells", "clearCells" }                    // edit-parsimony volumes
 * }
 * }</pre>
 */
public final class ProbeExporter {

    private ProbeExporter() {
    }

    /** Render the scorecard JSON (pure — also what the unit test asserts against). */
    public static String render(ProbeReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append(String.format(Locale.ROOT,
                "  \"schem\": { \"name\": %s, \"width\": %d, \"height\": %d, \"length\": %d, "
                        + "\"nonAir\": %d, \"footprintAspect\": %.3f },\n",
                quote(r.schemName()), r.width(), r.height(), r.length(), r.nonAir(),
                r.footprintAspect()));
        sb.append(String.format(Locale.ROOT,
                "  \"placement\": { \"anchor\": [%d, %d, %d], \"rotation\": %d, \"baseY\": %d, "
                        + "\"bounds\": { \"min\": [%d, %d, %d], \"max\": [%d, %d, %d] } },\n",
                r.anchorX(), r.anchorY(), r.anchorZ(), r.rotationDeg(), r.baseY(),
                r.bounds().minX(), r.bounds().minY(), r.bounds().minZ(),
                r.bounds().maxX(), r.bounds().maxY(), r.bounds().maxZ()));
        sb.append(String.format(Locale.ROOT,
                "  \"site\": { \"slope\": %d, \"waterOrVoid\": %b, \"desert\": %b, "
                        + "\"bridgeDepth\": %d, \"biome\": %s, \"biomeBonus\": %.1f, \"total\": %.1f },\n",
                r.slope(), r.waterOrVoid(), r.desert(), r.bridgeDepth(), quote(r.biome()),
                r.biomeBonus(), r.total()));
        ProbeReport.Guardrails g = r.guardrails();
        sb.append("  \"guardrails\": {\n");
        sb.append(guardrail("clip", g.clip(), String.valueOf(g.clipBlocks()), ","));
        sb.append(guardrail("float", g.floats(), String.valueOf(g.floatBlocks()), ","));
        sb.append(guardrail("halfBuried", g.halfBuried(),
                String.format(Locale.ROOT, "%.3f", g.buriedFrac()), ","));
        sb.append(guardrail("underwater", g.underwater(), String.valueOf(g.waterColumns()), ","));
        sb.append(guardrail("bridgeCapExceeded", g.bridgeCapExceeded(),
                String.valueOf(g.bridgeOverCap()), ""));
        sb.append("  },\n");
        sb.append(String.format(Locale.ROOT,
                "  \"foundation\": { \"fillCells\": %d, \"clearCells\": %d }\n",
                r.foundationFillCells(), r.foundationClearCells()));
        sb.append("}\n");
        return sb.toString();
    }

    private static String guardrail(String name, boolean flag, String magnitude, String comma) {
        return String.format(Locale.ROOT, "    \"%s\": { \"flag\": %b, \"magnitude\": %s }%s\n",
                name, flag, magnitude, comma);
    }

    /** Minimal JSON string quoting — names/biome ids never need more than this. */
    private static String quote(String s) {
        return '"' + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + '"';
    }

    /** Render + write under {@code <gameDir>/map2mc/probes/}; returns the file path. */
    public static Path export(ProbeReport r) throws IOException {
        return export(r, null);
    }

    /** As {@link #export}, but written under {@code dir} when non-null (else the default probes dir). */
    public static Path export(ProbeReport r, Path dir) throws IOException {
        Path target = dir != null ? dir : SchemPaths.probeDir();
        Files.createDirectories(target);
        String base = r.schemName();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        base = slash >= 0 ? base.substring(slash + 1) : base;
        if (base.toLowerCase(Locale.ROOT).endsWith(".schem")) {
            base = base.substring(0, base.length() - ".schem".length());
        }
        Path out = target.resolve(base + ".json");
        Files.writeString(out, render(r));
        return out;
    }
}
