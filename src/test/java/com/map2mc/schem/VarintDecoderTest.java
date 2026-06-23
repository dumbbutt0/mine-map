package com.map2mc.schem;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-logic tests for the LEB128 varint decoder — the part of the Sponge reader most prone to
 * off-by-one / continuation-bit bugs. No Minecraft needed.
 */
class VarintDecoderTest {

    /** Reference encoder (mirrors make_test_schem.py's varint()). */
    private static byte[] encode(int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = n & 0x7F;
            n >>>= 7;
            if (n != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                break;
            }
        }
        return out.toByteArray();
    }

    private static byte[] concat(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            out.writeBytes(encode(v));
        }
        return out.toByteArray();
    }

    @Test
    void decodesMixedWidthValuesRoundTrip() {
        int[] values = {0, 1, 2, 127, 128, 129, 255, 256, 300, 16383, 16384, 2097151};
        int[] decoded = VarintDecoder.decode(concat(values), values.length);
        assertArrayEquals(values, decoded);
    }

    @Test
    void encodingWidthsAreAsExpected() {
        assertEquals(1, encode(0).length);
        assertEquals(1, encode(127).length);   // last single-byte value
        assertEquals(2, encode(128).length);   // first two-byte value
        assertEquals(3, encode(16384).length); // first three-byte value
    }

    @Test
    void truncatedContinuationThrows() {
        byte[] data = {(byte) 0x80}; // continuation bit set, but no following byte
        assertThrows(IllegalArgumentException.class, () -> VarintDecoder.decode(data, 1));
    }
}
