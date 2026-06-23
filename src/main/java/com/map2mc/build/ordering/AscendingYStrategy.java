package com.map2mc.build.ordering;

import com.map2mc.build.Placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bottom-up order — the cinematic default. Sorts by Y ascending, tie-broken by (z, x) for a
 * stable, deterministic sweep within each layer. This is what makes a landmark read as rising
 * out of the ground (footings → legs → tower → spire).
 */
public final class AscendingYStrategy implements OrderingStrategy {

    private static final Comparator<Placement> ORDER =
            Comparator.comparingInt((Placement p) -> p.pos().getY())
                    .thenComparingInt(p -> p.pos().getZ())
                    .thenComparingInt(p -> p.pos().getX());

    @Override
    public List<Placement> order(List<Placement> in, OrderingContext ctx) {
        List<Placement> out = new ArrayList<>(in);
        out.sort(ORDER);
        return out;
    }

    @Override
    public String name() {
        return "ASCENDING_Y";
    }
}
