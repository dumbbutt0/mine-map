package com.map2mc.schem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders a {@link SchematicData} as the exact textual report produced by the Python reference
 * parser ({@code make_test_schem.py --verify}). This lets the Java reader be diffed byte-for-byte
 * against the oracle — the headless Phase 2 gate. Any divergence in v2/v3 detection, varint
 * decoding, palette inversion, or storage-order indexing shows up as a diff.
 */
public final class SchematicReport {

    /** Symbol map mirrors the Python fixture's {@code sym} dict; anything else renders as '?'. */
    private static final Map<String, String> SYM = Map.of(
            "minecraft:air", ".",
            "minecraft:polished_diorite", "#",
            "minecraft:waxed_exposed_copper", "C",
            "minecraft:oak_log[axis=y]", "L");

    private SchematicReport() {
    }

    public static String render(SchematicData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("  format     : Sponge v").append(d.version()).append('\n');
        sb.append("  dimensions : ").append(d.width()).append(" x ").append(d.height())
                .append(" x ").append(d.length()).append("  (W x H x L)").append('\n');
        sb.append("  dataVersion: ").append(d.dataVersion()).append('\n');

        // palette, sorted by id
        StringJoiner pj = new StringJoiner(", ");
        for (int id = 0; id < d.palette().length; id++) {
            if (d.palette()[id] != null) {
                pj.add(id + "=" + d.palette()[id]);
            }
        }
        sb.append("  palette    : ").append(pj).append('\n');

        // block count, in the order block types first appear in storage order (matches Python dict)
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < d.volume(); i++) {
            counts.merge(d.palette()[d.blockIds()[i]], 1, Integer::sum);
        }
        StringJoiner cj = new StringJoiner(", ");
        counts.forEach((block, count) -> {
            String sym = SYM.getOrDefault(block, "?");
            String shortName = block.substring(block.lastIndexOf(':') + 1);
            cj.add(sym + "(" + shortName + ")=" + count);
        });
        sb.append("  block count: ").append(cj).append('\n');

        sb.append("  bottom-up layers (y=0 first):").append('\n');
        for (int y = 0; y < d.height(); y++) {
            sb.append("    y=").append(y).append('\n');
            for (int z = 0; z < d.length(); z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < d.width(); x++) {
                    row.append(SYM.getOrDefault(d.palette()[d.paletteIndexAt(x, y, z)], "?"));
                }
                sb.append("      ").append(row).append('\n');
            }
        }
        return sb.toString();
    }
}
