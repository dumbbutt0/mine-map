package com.map2mc.schem;

import net.minecraft.util.BlockRotation;

/**
 * The four cardinal rotations a hero asset may be placed at. Bundles the vanilla
 * {@link BlockRotation} (used to rotate directional block states like logs/stairs) with the
 * footprint coordinate transform, so position and state rotation can never desync.
 */
public enum Rotation4 {
    NONE(BlockRotation.NONE),
    CW90(BlockRotation.CLOCKWISE_90),
    CW180(BlockRotation.CLOCKWISE_180),
    CW270(BlockRotation.COUNTERCLOCKWISE_90);

    private final BlockRotation vanilla;

    Rotation4(BlockRotation vanilla) {
        this.vanilla = vanilla;
    }

    public BlockRotation vanilla() {
        return vanilla;
    }

    /** Whether this rotation swaps the footprint's width and length (90°/270°). */
    public boolean swapsXZ() {
        return this == CW90 || this == CW270;
    }

    public static Rotation4 fromDegrees(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return switch (d) {
            case 0 -> NONE;
            case 90 -> CW90;
            case 180 -> CW180;
            case 270 -> CW270;
            default -> throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270 — got " + degrees);
        };
    }

    /** Rotate a local cell (x,z) within a w×l footprint (clockwise from above). */
    public int rotatedX(int x, int z, int w, int l) {
        return switch (this) {
            case NONE -> x;
            case CW90 -> l - 1 - z;
            case CW180 -> w - 1 - x;
            case CW270 -> z;
        };
    }

    public int rotatedZ(int x, int z, int w, int l) {
        return switch (this) {
            case NONE -> z;
            case CW90 -> x;
            case CW180 -> l - 1 - z;
            case CW270 -> w - 1 - x;
        };
    }
}
