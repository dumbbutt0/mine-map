package com.map2mc.schem;

/**
 * Parsed contents of a Sponge {@code .schem} (v2 or v3), independent of Minecraft block states —
 * the palette is kept as the raw blockstate STRINGS so the reader needs no live registry and can
 * be diffed against the Python reference parser. {@link com.map2mc.schem.BlockStateResolver}
 * turns the strings into real {@code BlockState}s at placement time.
 *
 * @param version    2 or 3 (detected by presence of a {@code Blocks} compound)
 * @param dataVersion the Minecraft data version stamped in the file
 * @param width      X span, @param height Y span, @param length Z span
 * @param offset     the schematic Offset (length-3, {0,0,0} if absent)
 * @param palette    index → full blockstate string (e.g. {@code minecraft:oak_log[axis=y]})
 * @param blockIds   one palette index per block, in storage order (x fastest, then z, then y)
 */
public record SchematicData(
        int version,
        int dataVersion,
        int width,
        int height,
        int length,
        int[] offset,
        String[] palette,
        int[] blockIds) {

    /** Palette index at a local cell. The ONE place the storage-order formula lives. */
    public int paletteIndexAt(int x, int y, int z) {
        return blockIds[(y * length + z) * width + x];
    }

    public int volume() {
        return width * height * length;
    }
}
