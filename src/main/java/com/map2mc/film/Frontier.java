package com.map2mc.film;

import com.map2mc.build.BuildJob;
import com.map2mc.build.Placement;
import com.map2mc.place.Bounds;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * The construction "front": the centroid (+ bounding box) of the last N actually-placed blocks.
 * Derived from the build's ordered placement list via its cursor, so it works for ANY ordering
 * strategy (it reads the real placement order); for {@code ASCENDING_Y} this is the rising top band.
 * Pure value type — the camera aims at {@link #centroid()}.
 *
 * @param centroid block-centre centroid of the window (the camera's aim target)
 * @param bbox     tight bounds of the window
 * @param count    how many blocks were in the window (0 = nothing placed yet → use the fallback)
 */
public record Frontier(Vec3d centroid, Bounds bbox, int count) {

    /** Window start index = {@code max(0, cursor - max(1, windowN))}. Pure; unit-tested. */
    public static int windowStart(int cursor, int windowN) {
        return Math.max(0, cursor - Math.max(1, windowN));
    }

    /** Centroid + bbox of a placement window. Empty window → {@code count 0} at {@code fallback}. */
    public static Frontier ofWindow(List<Placement> window, Vec3d fallback) {
        if (window.isEmpty()) {
            int fx = (int) Math.floor(fallback.x), fy = (int) Math.floor(fallback.y), fz = (int) Math.floor(fallback.z);
            return new Frontier(fallback, new Bounds(fx, fy, fz, fx, fy, fz), 0);
        }
        double sx = 0, sy = 0, sz = 0;
        for (Placement p : window) {
            BlockPos b = p.pos();
            sx += b.getX() + 0.5;
            sy += b.getY() + 0.5;
            sz += b.getZ() + 0.5;
        }
        int c = window.size();
        return new Frontier(new Vec3d(sx / c, sy / c, sz / c), Bounds.fromPlacements(window), c);
    }

    /**
     * Frontier of the last {@code windowN} placed blocks of {@code job}. SERVER-THREAD-ONLY: reads
     * {@code job.placed()} (the cursor) and {@code job.ordered()} in the same tick that advances them
     * ({@code END_SERVER_TICK}), so there is no concurrent mutation. {@code fallback} is the aim when
     * nothing has been placed yet (e.g. the build's centre at its base).
     */
    public static Frontier fromJob(BuildJob job, int windowN, Vec3d fallback) {
        int cursor = job.placed();
        if (cursor <= 0) {
            return ofWindow(List.of(), fallback);
        }
        return ofWindow(job.ordered().subList(windowStart(cursor, windowN), cursor), fallback);
    }
}
