package com.map2mc.schem;

import com.map2mc.build.Placement;
import com.map2mc.place.AnchorMode;
import com.map2mc.place.PlacementResolver;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Thin compatibility shim over {@link PlacementResolver}. The rotation + ground-snap logic that used
 * to live here moved into the shared resolver (Phase 2.5) so {@code preview}, {@code hero}, and
 * {@code undo} all go through ONE code path. This keeps the original {@code toPlacements(...)} entry
 * point (corner-anchored) working for existing callers/tests.
 */
public final class SchematicPlacer {

    private SchematicPlacer() {
    }

    /**
     * Corner-anchored placement list (the original Phase 2 behavior). Delegates to
     * {@link PlacementResolver#resolve} with {@link AnchorMode#CORNER}.
     *
     * @param anchor    the build's minimum corner (typically the player's position)
     * @param rot       0/90/180/270 rotation
     * @param groundSnap if true, the schematic's lowest occupied layer aligns to {@code anchor.y}
     */
    public static List<Placement> toPlacements(SchematicData data, BlockState[] resolved,
                                               BlockPos anchor, Rotation4 rot, boolean groundSnap) {
        return PlacementResolver.resolve(data, resolved, anchor, AnchorMode.CORNER, rot, groundSnap)
                .placements();
    }
}
