package com.map2mc.place;

import com.map2mc.build.Placement;
import com.map2mc.schem.Rotation4;
import com.map2mc.schem.SchematicData;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * THE single source of truth for "input point (+rotation +anchor mode) → the concrete world
 * positions a build will occupy". Both {@code /map2mc preview} and {@code /map2mc hero} call this,
 * so a preview outline always matches the eventual build exactly. {@link com.map2mc.schem.SchematicPlacer}
 * delegates here too — there is no second copy of this logic.
 *
 * <p>The transform has three independent parts:
 * <ol>
 *   <li><b>Rotation</b> of each local cell's footprint (and of directional block states) via
 *       {@link Rotation4}.</li>
 *   <li><b>Vertical base alignment</b> (ground-snap): lift so the lowest occupied layer sits at
 *       {@code anchor.y}.</li>
 *   <li><b>Horizontal anchoring</b>: {@link AnchorMode#CORNER} roots the min corner at the anchor;
 *       {@link AnchorMode#CENTER_GROUND} centers the (rotation-aware) footprint on the anchor.</li>
 * </ol>
 *
 * <p>The position geometry ({@link #computePositions}) is pure — no {@link BlockState}, no registry —
 * so it is unit-tested with a boolean occupancy grid and hand-fed anchors (no Minecraft bootstrap).
 * {@link #resolve} layers the resolved block states on top of those positions in the same order.
 */
public final class PlacementResolver {

    private PlacementResolver() {
    }

    /** Full resolve: positions (shared core) + the rotated block state at each. */
    public static ResolvedBuild resolve(SchematicData data, BlockState[] resolved, BlockPos anchor,
                                        AnchorMode mode, Rotation4 rot, boolean groundSnap) {
        int w = data.width(), h = data.height(), l = data.length();
        boolean[] occ = occupancy(data, resolved);

        int yOff = groundSnap ? -lowestOccupiedY(occ, w, h, l) : 0;
        int[] hOff = horizontalOffset(mode, rot, w, l);

        // Iterate in the SAME (y, z, x) order as computePositions so the i-th placement's position
        // matches the i-th position that a preview would draw.
        List<Placement> out = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (!occ[idx(x, y, z, w, l)]) {
                        continue;
                    }
                    BlockState state = resolved[data.paletteIndexAt(x, y, z)];
                    int rx = rot.rotatedX(x, z, w, l);
                    int rz = rot.rotatedZ(x, z, w, l);
                    BlockState rotated = rot == Rotation4.NONE ? state : state.rotate(rot.vanilla());
                    out.add(new Placement(anchor.add(rx + hOff[0], y + yOff, rz + hOff[1]), rotated));
                }
            }
        }
        return new ResolvedBuild(out, Bounds.fromPlacements(out));
    }

    /**
     * Pure geometry core: the world positions a build occupies, given an occupancy grid (one boolean
     * per cell in storage order {@code (y*l+z)*w+x}). Identical math to {@link #resolve}; this is the
     * testable seam. Returns positions in {@code (y, z, x)} order, skipping empty cells.
     */
    public static List<BlockPos> computePositions(int w, int h, int l, boolean[] occ, BlockPos anchor,
                                                  AnchorMode mode, Rotation4 rot, boolean groundSnap) {
        int yOff = groundSnap ? -lowestOccupiedY(occ, w, h, l) : 0;
        int[] hOff = horizontalOffset(mode, rot, w, l);
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (!occ[idx(x, y, z, w, l)]) {
                        continue;
                    }
                    int rx = rot.rotatedX(x, z, w, l);
                    int rz = rot.rotatedZ(x, z, w, l);
                    out.add(anchor.add(rx + hOff[0], y + yOff, rz + hOff[1]));
                }
            }
        }
        return out;
    }

    /**
     * Horizontal offset {x,z}. CORNER → {0,0}. CENTER_GROUND → shift the rotation-aware footprint so
     * its center sits on the anchor. The rotated footprint's X-extent is {@code l} for 90°/270°
     * (cells' rotatedX ranges over [0, l-1]) else {@code w}; Z-extent is {@code w} for 90°/270° else
     * {@code l}. Integer division picks the lower-middle cell for even footprints.
     */
    static int[] horizontalOffset(AnchorMode mode, Rotation4 rot, int w, int l) {
        if (mode != AnchorMode.CENTER_GROUND) {
            return new int[]{0, 0};
        }
        int footprintX = rot.swapsXZ() ? l : w;
        int footprintZ = rot.swapsXZ() ? w : l;
        return new int[]{-(footprintX / 2), -(footprintZ / 2)};
    }

    private static int idx(int x, int y, int z, int w, int l) {
        return (y * l + z) * w + x;
    }

    /** Build a boolean occupancy grid from the resolved palette (null/air → empty). */
    private static boolean[] occupancy(SchematicData data, BlockState[] resolved) {
        int w = data.width(), h = data.height(), l = data.length();
        boolean[] occ = new boolean[w * h * l];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockState s = resolved[data.paletteIndexAt(x, y, z)];
                    occ[idx(x, y, z, w, l)] = s != null && !s.isAir();
                }
            }
        }
        return occ;
    }

    /** Lowest Y layer that contains any occupied cell (0 if none). */
    static int lowestOccupiedY(boolean[] occ, int w, int h, int l) {
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    if (occ[idx(x, y, z, w, l)]) {
                        return y;
                    }
                }
            }
        }
        return 0;
    }
}
