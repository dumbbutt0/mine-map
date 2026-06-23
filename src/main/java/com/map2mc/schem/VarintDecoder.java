package com.map2mc.schem;

/**
 * Decodes the Sponge block-data byte array: one unsigned LEB128 varint per block (7 bits/byte,
 * MSB = "more bytes follow"). The ONE place varint decoding lives.
 */
public final class VarintDecoder {

    private VarintDecoder() {
    }

    /** Decode exactly {@code count} varints from {@code data}. */
    public static int[] decode(byte[] data, int count) {
        int[] out = new int[count];
        int i = 0;
        for (int n = 0; n < count; n++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (i >= data.length) {
                    throw new IllegalArgumentException(
                            "varint block data truncated: wanted " + count + " values, ran out at " + n);
                }
                int b = data[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            out[n] = value;
        }
        return out;
    }
}
