package com.map2mc.terrain;

import com.map2mc.build.Placement;
import com.map2mc.place.Bounds;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the cleared, leveled plaza disc into a LANDSCAPED ground (so it reads like the reference
 * grounds, not an empty circle): a grass lawn with a simple path motif, rings of trees, and scattered
 * flowers — over the disc MINUS the build footprint (the structure occupies the centre).
 *
 * <p>{@link #plan} is PURE geometry (a {@link BlockPos} + a {@link Deco} kind, no {@link BlockState}),
 * so it unit-tests with no Minecraft bootstrap, exactly like {@link FoundationGenerator#plan}. The
 * biome-specific block palette is applied in {@link #generate} (and chosen by {@link PlazaPalette}).
 * The layout is fully DETERMINISTIC (position/index-driven, no RNG) so a build is reproducible.
 */
public final class PlazaDecorator {

    private PlazaDecorator() {
    }

    /** What a planned plaza cell becomes once the biome palette is applied. */
    public enum Deco {
        LAWN, PATH, TRUNK, LEAF, FLOWER
    }

    /** A planned decoration cell at an absolute position. */
    public record Cell(BlockPos pos, Deco kind) {
    }

    /**
     * Pure plaza layout over the inscribed disc of {@code region}, excluding the {@code footprint}
     * (grown by 1 for clearance). The plaza SURFACE is {@code baseY-1} (the top of the foundation
     * fill); lawn/path go there, flowers + trees stand from {@code baseY} up.
     *
     * @param treeRings number of concentric tree rings (0 = no trees)
     */
    public static List<Cell> plan(Bounds region, int baseY, Bounds footprint, int treeRings) {
        List<Cell> out = new ArrayList<>();
        double cx = (region.minX() + region.maxX() + 1) / 2.0;
        double cz = (region.minZ() + region.maxZ() + 1) / 2.0;
        double radius = Math.min(region.sizeX(), region.sizeZ()) / 2.0;
        double radiusSq = radius * radius;
        // footprint grown by 1 block of clearance so nothing hugs the structure walls
        int fMinX = footprint.minX() - 1, fMaxX = footprint.maxX() + 1;
        int fMinZ = footprint.minZ() - 1, fMaxZ = footprint.maxZ() + 1;

        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                double dx = (x + 0.5) - cx;
                double dz = (z + 0.5) - cz;
                double d2 = dx * dx + dz * dz;
                if (d2 > radiusSq) {
                    continue; // outside the round plaza
                }
                if (x >= fMinX && x <= fMaxX && z >= fMinZ && z <= fMaxZ) {
                    continue; // inside the build footprint (+1) — the structure goes here
                }
                boolean path = isPath(dx, dz, d2, radius);
                out.add(new Cell(new BlockPos(x, baseY - 1, z), path ? Deco.PATH : Deco.LAWN));
                // sparse flowers on the lawn (deterministic checker-ish hash), standing on the surface
                if (!path && ((Math.floorMod(x * 7 + z * 13, 23)) == 0)) {
                    out.add(new Cell(new BlockPos(x, baseY, z), Deco.FLOWER));
                }
            }
        }

        // Trees on concentric rings, evenly spaced by angle; skip any whose base is in the footprint
        // (+1) or off the disc. Deterministic count/positions from the ring index.
        for (int ring = 0; ring < Math.max(0, treeRings); ring++) {
            double t = treeRings <= 1 ? 0.65 : 0.50 + 0.38 * ring / (treeRings - 1);
            double rr = radius * t;
            int n = Math.max(6, (int) Math.round(2.0 * Math.PI * rr / 7.0)); // ~7 blocks apart
            double phase = ring * 0.5; // offset alternate rings so trees don't line up radially
            for (int i = 0; i < n; i++) {
                double ang = 2.0 * Math.PI * i / n + phase;
                int tx = (int) Math.round(cx + rr * Math.cos(ang) - 0.5);
                int tz = (int) Math.round(cz + rr * Math.sin(ang) - 0.5);
                if (tx >= fMinX && tx <= fMaxX && tz >= fMinZ && tz <= fMaxZ) {
                    continue;
                }
                double tdx = (tx + 0.5) - cx, tdz = (tz + 0.5) - cz;
                if (tdx * tdx + tdz * tdz > radiusSq) {
                    continue;
                }
                int height = 4 + Math.floorMod(i + ring, 3); // 4..6, deterministic variation
                addTree(out, tx, tz, baseY, height);
            }
        }
        return out;
    }

    /** A path cell: 8 radial spokes (~1.5 blocks wide) plus one concentric ring at 0.62·radius. */
    private static boolean isPath(double dx, double dz, double d2, double radius) {
        double dist = Math.sqrt(d2);
        if (dist < 2.0) {
            return false; // tiny centre — but the footprint usually covers this anyway
        }
        double ang = Math.atan2(dz, dx);
        double step = Math.PI / 4.0;                       // 8 spokes
        double nearest = Math.round(ang / step) * step;
        double angularOffset = Math.abs(ang - nearest) * dist; // arc-distance from the spoke centreline
        if (angularOffset < 0.9) {
            return true;
        }
        return Math.abs(dist - radius * 0.62) < 1.2;       // a ring path
    }

    /** Append a simple tree: a straight trunk + a small two-tier leaf blob at the top. */
    private static void addTree(List<Cell> out, int tx, int tz, int baseY, int height) {
        int topY = baseY + height - 1;
        for (int y = baseY; y <= topY; y++) {
            out.add(new Cell(new BlockPos(tx, y, tz), Deco.TRUNK));
        }
        // wide tier (radius 2) just below the top, narrow tier (radius 1) at and above the top
        for (int oy = -2; oy <= 1; oy++) {
            int y = topY + oy;
            int rad = oy <= -1 ? 2 : 1;
            for (int ox = -rad; ox <= rad; ox++) {
                for (int oz = -rad; oz <= rad; oz++) {
                    if (ox * ox + oz * oz > rad * rad + 1) {
                        continue; // round off the corners
                    }
                    if (ox == 0 && oz == 0 && y <= topY) {
                        continue; // don't replace the trunk column below the crown tip
                    }
                    out.add(new Cell(new BlockPos(tx + ox, y, tz + oz), Deco.LEAF));
                }
            }
        }
    }

    /** Map a pure plan to {@link Placement}s using a biome palette. */
    public static List<Placement> generate(List<Cell> plan, PlazaPalette palette) {
        List<Placement> out = new ArrayList<>(plan.size());
        for (Cell c : plan) {
            BlockState s = switch (c.kind()) {
                case LAWN -> palette.lawn();
                case PATH -> palette.path();
                case TRUNK -> palette.log();
                case LEAF -> palette.leaves();
                case FLOWER -> palette.flower(c.pos());
            };
            out.add(new Placement(c.pos(), s));
        }
        return out;
    }
}
