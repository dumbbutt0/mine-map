package com.map2mc.build.ordering;

import com.map2mc.build.Placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ripple-from-center order. Primary key is horizontal squared distance from the build center
 * (integer, no sqrt); secondary key is Y ascending so the build still rises as it spreads. Useful
 * for plaza/courtyard reveals around a hero.
 */
public final class RadialOutStrategy implements OrderingStrategy {

    @Override
    public List<Placement> order(List<Placement> in, OrderingContext ctx) {
        final int cx = ctx.center().getX();
        final int cz = ctx.center().getZ();
        Comparator<Placement> order = Comparator
                .comparingInt((Placement p) -> {
                    int dx = p.pos().getX() - cx;
                    int dz = p.pos().getZ() - cz;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(p -> p.pos().getY())
                .thenComparingInt(p -> p.pos().getX());
        List<Placement> out = new ArrayList<>(in);
        out.sort(order);
        return out;
    }

    @Override
    public String name() {
        return "RADIAL_OUT";
    }
}
