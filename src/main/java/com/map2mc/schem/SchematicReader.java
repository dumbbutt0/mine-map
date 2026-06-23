package com.map2mc.schem;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a Sponge Schematic ({@code .schem}) v2 or v3 into a {@link SchematicData}.
 *
 * <p>Format (GROUND TRUTH, matching the project fixtures — NOT generic Sponge docs):
 * GZIP big-endian NBT. Detect by presence of a {@code Blocks} compound ⇒ v3, else v2.
 * <ul>
 *   <li><b>v2</b>: root is the Schematic compound; top-level {@code Palette} (string→int) and
 *       {@code BlockData} (byte[]).</li>
 *   <li><b>v3</b>: root wraps one child {@code Schematic}; palette/data live in a {@code Blocks}
 *       compound as {@code Palette} and {@code Data}.</li>
 * </ul>
 * Block data is LEB128 varints, one per block, ordered {@code (y*Length + z)*Width + x}.
 */
public final class SchematicReader {

    private SchematicReader() {
    }

    public static SchematicData read(Path path) throws IOException {
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) {
            throw new IOException("not a valid NBT file: " + path);
        }
        return parse(root);
    }

    /** Parse a decompressed schematic root compound. Package-visible for tests. */
    static SchematicData parse(NbtCompound root) throws IOException {
        // v3 nests everything under "Schematic"; v2's root IS the schematic compound.
        NbtCompound schem = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;

        boolean v3 = schem.contains("Blocks");
        int version = v3 ? 3 : 2;
        NbtCompound blocks = v3 ? schem.getCompoundOrEmpty("Blocks") : schem;

        int width = schem.getShort("Width", (short) 0);
        int height = schem.getShort("Height", (short) 0);
        int length = schem.getShort("Length", (short) 0);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("bad schematic dimensions " + width + "x" + height + "x" + length
                    + " (not a Sponge .schem? legacy .schematic/.litematic are not supported)");
        }
        int dataVersion = schem.getInt("DataVersion", 0);
        int[] offset = schem.getIntArray("Offset").orElse(new int[]{0, 0, 0});

        // Palette: full blockstate string -> int id. Invert into an index-keyed array.
        NbtCompound paletteNbt = blocks.getCompoundOrEmpty("Palette");
        Map<Integer, String> byId = new HashMap<>();
        int maxId = -1;
        for (String key : paletteNbt.getKeys()) {
            int id = paletteNbt.getInt(key, 0);
            byId.put(id, key);
            maxId = Math.max(maxId, id);
        }
        String[] palette = new String[maxId + 1];
        byId.forEach((id, str) -> palette[id] = str);

        // Block data: v3 -> Blocks.Data, v2 -> top-level BlockData.
        byte[] blockData = (v3 ? blocks.getByteArray("Data") : schem.getByteArray("BlockData"))
                .orElseThrow(() -> new IOException("schematic has no block data"));
        int[] blockIds = VarintDecoder.decode(blockData, width * height * length);

        return new SchematicData(version, dataVersion, width, height, length, offset, palette, blockIds);
    }
}
