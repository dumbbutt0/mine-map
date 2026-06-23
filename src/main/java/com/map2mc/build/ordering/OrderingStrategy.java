package com.map2mc.build.ordering;

import com.map2mc.build.Placement;

import java.util.List;

/**
 * Decides the ORDER in which a build's blocks are laid down. This ordering — applied once when a
 * job is submitted — is what reads as "being built" rather than "a region filling in". The tick
 * loop never re-sorts; it just walks the ordered list.
 */
public interface OrderingStrategy {

    /** Returns a NEW list sorted into placement order. Must not mutate {@code in}. */
    List<Placement> order(List<Placement> in, OrderingContext ctx);

    /** Stable identifier shown in status/logs (e.g. {@code ASCENDING_Y}). */
    String name();
}
