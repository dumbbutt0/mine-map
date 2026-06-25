package com.map2mc.terrain;

import com.map2mc.build.Placement;
import com.map2mc.place.Bounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure foundation geometry: GROUND a fixed structure on uneven terrain WITHOUT warping the structure,
 * and LEVEL a region (the footprint + a margin) so nothing terrain or vegetation looms over/through it.
 * Per column, over {@code region} (= footprint grown by the clear margin):
 * <ul>
 *   <li><b>bridge DOWN</b>: where the SOLID ground is below the base, fill {@code solid .. baseY-1} so
 *       the build rests on solid ground (capped at {@code maxBridge});</li>
 *   <li><b>clear ABOVE up to the VEGETATION top</b>: clear {@code baseY .. vegTop-1} — using the
 *       WORLD_SURFACE height (includes leaves/plants/snow), not just the solid heightmap — so tree
 *       canopy and terrain that would poke above/around the build are removed (the structure later
 *       overwrites the cells it occupies);</li>
 *   <li>the margin border gets the same treatment → a clean leveled plaza, no wall/canopy at the edge.</li>
 * </ul>
 * {@link #plan} is pure (BlockPos + a fill/clear flag), unit-tested with no Minecraft bootstrap.
 */
public final class FoundationGenerator {

    private FoundationGenerator() {
    }

    /** A planned foundation block: {@code fill}=true → material; false → cleared to air. */
    public record Cell(BlockPos pos, boolean fill) {
    }

    /**
     * Pure geometry over {@code region}. {@code solidSurf}/{@code vegTop} are per-column, row-major:
     * {@code index = (z-minZ)*sizeX + (x-minX)} (the indexing {@link TerrainSampler} produces).
     */
    public static List<Cell> plan(Bounds region, int baseY, int[] solidSurf, int[] vegTop, int maxBridge) {
        List<Cell> out = new ArrayList<>();
        int sizeX = region.sizeX();
        // Clear/level a DISC inscribed in the (square) region — a round plaza like the reference, not a
        // rectangle. The sampler sizes the region so this circle comfortably contains the footprint.
        double centerX = (region.minX() + region.maxX() + 1) / 2.0;
        double centerZ = (region.minZ() + region.maxZ() + 1) / 2.0;
        double radius = Math.min(region.sizeX(), region.sizeZ()) / 2.0;
        double radiusSq = radius * radius;
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                double dx = (x + 0.5) - centerX;
                double dz = (z + 0.5) - centerZ;
                if (dx * dx + dz * dz > radiusSq) {
                    continue; // outside the circle → leave the natural terrain
                }
                int gi = (z - region.minZ()) * sizeX + (x - region.minX());
                int solid = solidSurf[gi];
                int veg = vegTop[gi];
                int fillBottom = Math.max(solid, baseY - maxBridge); // bridge DOWN, capped
                for (int y = fillBottom; y < baseY; y++) {
                    out.add(new Cell(new BlockPos(x, y, z), true));
                }
                for (int y = baseY; y < veg; y++) {                  // clear ABOVE up to the vegetation top
                    out.add(new Cell(new BlockPos(x, y, z), false));
                }
            }
        }
        return out;
    }

    /** Production: {@link #plan} mapped to {@link Placement}s ({@code material} for fill, air for clear). */
    public static List<Placement> generate(Bounds region, int baseY, int[] solidSurf, int[] vegTop,
                                           BlockState material, int maxBridge) {
        BlockState air = Blocks.AIR.getDefaultState();
        List<Placement> out = new ArrayList<>();
        for (Cell c : plan(region, baseY, solidSurf, vegTop, maxBridge)) {
            out.add(new Placement(c.pos(), c.fill() ? material : air));
        }
        return out;
    }
}
