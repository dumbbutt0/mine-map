package com.map2mc.undo;

import com.map2mc.build.Placement;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prior world state a single build overwrote — enough to put everything back exactly.
 *
 * <p>Compact by construction: one entry per touched position, keyed by the packed {@code long} from
 * {@link BlockPos#asLong()}, valued by the prior {@link BlockState} (which are flyweights shared
 * across the world, so storing references is cheap). Only the FIRST prior seen per position is kept
 * ({@code putIfAbsent}), so re-touching a cell still restores the true original. A 12k-block landmark
 * costs ~12k boxed longs + state references — fine; this is per-build, and the manager keeps only a
 * small stack of these.
 */
public final class UndoLog {

    private final String label;
    private final Map<Long, BlockState> priors = new LinkedHashMap<>();

    public UndoLog(String label) {
        this.label = label;
    }

    /** Record the state that was at {@code packedPos} before we overwrote it (first write wins). */
    public void record(long packedPos, BlockState prior) {
        priors.putIfAbsent(packedPos, prior);
    }

    public int size() {
        return priors.size();
    }

    public String label() {
        return label;
    }

    /** The placements that restore the prior world (pos → its original state). */
    public List<Placement> toRestorePlacements() {
        List<Placement> out = new ArrayList<>(priors.size());
        for (Map.Entry<Long, BlockState> e : priors.entrySet()) {
            out.add(new Placement(BlockPos.fromLong(e.getKey()), e.getValue()));
        }
        return out;
    }
}
