package com.map2mc.schem;

import com.map2mc.build.Placement;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns parsed {@link SchematicData} + a resolved palette into a list of absolute-world
 * {@link Placement}s, applying rotation and ground-snapping. The list is then fed to the build
 * queue with ASCENDING_Y so the landmark rises bottom-up.
 */
public final class SchematicPlacer {

    private SchematicPlacer() {
    }

    /**
     * @param resolved  palette index → BlockState (null/air entries are skipped)
     * @param anchor    where the build is rooted (typically the player's position)
     * @param rot       0/90/180/270 rotation
     * @param groundSnap if true, the schematic's lowest occupied layer is aligned to {@code anchor.y}
     */
    public static List<Placement> toPlacements(SchematicData data, BlockState[] resolved,
                                               BlockPos anchor, Rotation4 rot, boolean groundSnap) {
        int w = data.width(), h = data.height(), l = data.length();

        int yOffset = 0;
        if (groundSnap) {
            yOffset = -lowestOccupiedY(data, resolved); // lift so the first non-air layer sits at anchor.y
        }

        List<Placement> out = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockState state = resolved[data.paletteIndexAt(x, y, z)];
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    int rx = rot.rotatedX(x, z, w, l);
                    int rz = rot.rotatedZ(x, z, w, l);
                    BlockState rotated = rot == Rotation4.NONE ? state : state.rotate(rot.vanilla());
                    out.add(new Placement(anchor.add(rx, y + yOffset, rz), rotated));
                }
            }
        }
        return out;
    }

    /** Lowest Y layer that contains any non-air block (0 if the build is empty). */
    private static int lowestOccupiedY(SchematicData data, BlockState[] resolved) {
        for (int y = 0; y < data.height(); y++) {
            for (int z = 0; z < data.length(); z++) {
                for (int x = 0; x < data.width(); x++) {
                    BlockState s = resolved[data.paletteIndexAt(x, y, z)];
                    if (s != null && !s.isAir()) {
                        return y;
                    }
                }
            }
        }
        return 0;
    }
}
