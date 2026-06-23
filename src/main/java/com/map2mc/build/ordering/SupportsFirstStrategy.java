package com.map2mc.build.ordering;

import com.map2mc.build.Placement;

import java.util.List;

/**
 * "Load-bearing first" ordering.
 *
 * <p>ASSUMPTION (MVP): for towers and pyramids, lower Y ≈ structural support, so this delegates to
 * {@link AscendingYStrategy}. The class exists as the explicit seam for a future version that does
 * real support analysis (only place a block once the block it rests on exists). Its {@link #name()}
 * still reports {@code SUPPORTS_FIRST} so status/logs reflect the intended strategy.
 */
public final class SupportsFirstStrategy implements OrderingStrategy {

    private final AscendingYStrategy delegate = new AscendingYStrategy();

    @Override
    public List<Placement> order(List<Placement> in, OrderingContext ctx) {
        return delegate.order(in, ctx);
    }

    @Override
    public String name() {
        return "SUPPORTS_FIRST";
    }
}
