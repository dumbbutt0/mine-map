package com.map2mc.place;

import com.map2mc.build.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * An inclusive axis-aligned block bounding box in world space. Pure data — used by the preview
 * outline (Phase 2.5) and the orbit camera (Phase 3). Coordinates are block coordinates; the
 * <b>occupied volume</b> spans {@code [min, max]} inclusive, so the visual box that encloses the
 * blocks runs from {@code min} to {@code max + 1} (a block at x occupies [x, x+1)).
 */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos max() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    /** Horizontal centroid at the base (lowest) Y — the natural anchor for ordering/orbit. */
    public BlockPos centerBase() {
        return new BlockPos((minX + maxX) / 2, minY, (minZ + maxZ) / 2);
    }

    /** Geometric center of the enclosing box (block-corner space, so +1 on the max side). */
    public Vec3d center() {
        return new Vec3d((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0);
    }

    /** Largest horizontal half-extent — a sensible basis for a default orbit radius. */
    public double horizontalRadius() {
        return Math.max(sizeX(), sizeZ()) / 2.0;
    }

    /**
     * Sample points along the 12 edges of the enclosing box at roughly {@code step}-block spacing,
     * for drawing a particle outline. Uses block-corner coordinates (min .. max+1).
     */
    public List<Vec3d> edgeSamples(double step) {
        double x0 = minX, y0 = minY, z0 = minZ;
        double x1 = maxX + 1.0, y1 = maxY + 1.0, z1 = maxZ + 1.0;
        List<Vec3d> pts = new ArrayList<>();
        // 4 edges along X, 4 along Z, 4 along Y
        for (double[] yz : new double[][]{{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) {
            sampleAxis(pts, x0, x1, step, t -> new Vec3d(t, yz[0], yz[1]));
        }
        for (double[] xy : new double[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            sampleAxis(pts, z0, z1, step, t -> new Vec3d(xy[0], xy[1], t));
        }
        for (double[] xz : new double[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            sampleAxis(pts, y0, y1, step, t -> new Vec3d(xz[0], t, xz[1]));
        }
        return pts;
    }

    private interface PointFn {
        Vec3d at(double t);
    }

    private static void sampleAxis(List<Vec3d> out, double from, double to, double step, PointFn fn) {
        double span = to - from;
        int n = Math.max(1, (int) Math.round(span / step));
        for (int i = 0; i <= n; i++) {
            out.add(fn.at(from + span * i / n));
        }
    }

    /** Tight bounds over a placement list (the exact occupied extent). */
    public static Bounds fromPlacements(List<Placement> ps) {
        if (ps.isEmpty()) {
            return new Bounds(0, 0, 0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Placement p : ps) {
            BlockPos b = p.pos();
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
