package com.map2mc.place;

/**
 * How the input point is interpreted when resolving where a build lands.
 *
 * <ul>
 *   <li>{@link #CORNER} — the point is the build's minimum (NW-bottom) corner. This is the original
 *       Phase 2 behavior; explicit and predictable for scripted placement.</li>
 *   <li>{@link #CENTER_GROUND} — the point is the build's horizontal <b>center</b> and (combined with
 *       ground-snap) its vertical <b>base</b>. This is the default for the player-facing case: you
 *       look at a spot and the build straddles it on the ground.</li>
 * </ul>
 *
 * <p>Vertical placement (base alignment) is governed by the separate {@code groundSnap} flag on
 * {@link PlacementResolver}; {@code CENTER_GROUND} is meant to be paired with {@code groundSnap=true}.
 */
public enum AnchorMode {
    CORNER,
    CENTER_GROUND
}
