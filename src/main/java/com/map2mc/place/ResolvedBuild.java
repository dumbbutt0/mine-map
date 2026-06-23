package com.map2mc.place;

import com.map2mc.build.Placement;

import java.util.List;

/**
 * The concrete result of resolving "where does this build go": the exact set of world placements it
 * will occupy, plus their tight {@link Bounds}. Both {@code preview} (draws the bounds) and
 * {@code hero} (queues the placements) consume this from the SAME {@link PlacementResolver} call, so
 * the outline can never disagree with the real build.
 */
public record ResolvedBuild(List<Placement> placements, Bounds bounds) {
}
