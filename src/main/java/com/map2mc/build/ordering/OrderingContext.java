package com.map2mc.build.ordering;

import com.map2mc.build.Placement;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Context a strategy may consult. Currently just the build's center (horizontal centroid at the
 * lowest occupied Y), used by {@link RadialOutStrategy}. Strategies that don't need it ignore it.
 */
public record OrderingContext(BlockPos center) {

    public static OrderingContext fromPlacements(List<Placement> ps) {
        if (ps.isEmpty()) {
            return new OrderingContext(BlockPos.ORIGIN);
        }
        long sx = 0, sz = 0;
        int minY = Integer.MAX_VALUE;
        for (Placement p : ps) {
            sx += p.pos().getX();
            sz += p.pos().getZ();
            minY = Math.min(minY, p.pos().getY());
        }
        int n = ps.size();
        return new OrderingContext(new BlockPos((int) (sx / n), minY, (int) (sz / n)));
    }
}
