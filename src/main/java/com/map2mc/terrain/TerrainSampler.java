package com.map2mc.terrain;

import com.map2mc.place.Bounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Thin Minecraft wrapper around {@link TerrainSample}: sample the LIVE world over a build's footprint
 * grown by a clear {@code margin}, into per-column grids of the SOLID surface (MOTION_BLOCKING_NO_LEAVES,
 * for grade/bridging) and the VEGETATION top (WORLD_SURFACE, for clearing leaves/plants), plus a fill
 * material and a desert flag. Reading {@code getTopY} needs the chunk generated, so the region chunks are
 * force-generated first (the build force-loads them again when it places). Server-thread only.
 */
public final class TerrainSampler {

    private TerrainSampler() {
    }

    /** The sampled region + grids + stats (graded over the inner footprint) + material + desert flag. */
    public record Result(Bounds region, TerrainSample sample, int[] solidSurf, int[] vegTop,
                         BlockState material, boolean desert) {
    }

    /**
     * @param footprint the build's XZ footprint; the sampled/leveled {@code region} is a SQUARE centred
     *                  on the footprint with half-side = circumscribed footprint radius + {@code margin},
     *                  so the generator's inscribed-circle plaza contains the footprint. Grids are
     *                  row-major over {@code region} ({@code index = (z-minZ)*sizeX + (x-minX)}); the
     *                  {@link TerrainSample} (grade) is taken over the INNER footprint only.
     */
    public static Result sample(ServerWorld world, Bounds footprint, int margin, String style) {
        // Square region centred on the footprint, sized so the inscribed circle (the round plaza the
        // generator clears) contains the whole footprint: radius = circumscribed footprint radius + margin.
        int cxC = (footprint.minX() + footprint.maxX()) / 2;
        int czC = (footprint.minZ() + footprint.maxZ()) / 2;
        int r = (int) Math.ceil(Math.hypot(footprint.sizeX() / 2.0, footprint.sizeZ() / 2.0)) + margin;
        Bounds region = new Bounds(cxC - r, footprint.minY(), czC - r,
                cxC + r, footprint.maxY(), czC + r);
        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
                world.getChunk(cx, cz); // FULL generation; getTopY does not generate on demand
            }
        }
        int sizeX = region.sizeX();
        int[] solid = new int[sizeX * region.sizeZ()];
        int[] veg = new int[sizeX * region.sizeZ()];
        boolean[] waterOrVoid = new boolean[sizeX * region.sizeZ()];
        Map<Block, Integer> tally = new HashMap<>();
        int bottom = world.getBottomY();
        int i = 0;
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                int s = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z); // solid ground
                int v = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);             // incl. leaves/plants
                solid[i] = s;
                veg[i] = Math.max(v, s);
                waterOrVoid[i] = world.getFluidState(new BlockPos(x, s - 1, z)).isOf(Fluids.WATER)
                        || s <= bottom + 1;
                tally.merge(world.getBlockState(new BlockPos(x, s - 1, z)).getBlock(), 1, Integer::sum);
                i++;
            }
        }
        // grade (baseY) from the inner footprint columns only
        int[] innerSolid = new int[footprint.sizeX() * footprint.sizeZ()];
        boolean[] innerWaterVoid = new boolean[innerSolid.length];
        int j = 0;
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                int gi = (z - region.minZ()) * sizeX + (x - region.minX());
                innerSolid[j] = solid[gi];
                innerWaterVoid[j] = waterOrVoid[gi];
                j++;
            }
        }
        Block dom = tally.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Blocks.STONE);
        boolean desert = dom == Blocks.SAND || dom == Blocks.RED_SAND || dom == Blocks.SANDSTONE
                || dom == Blocks.RED_SANDSTONE || dom == Blocks.TERRACOTTA;
        return new Result(region, TerrainSample.of(innerSolid, innerWaterVoid), solid, veg,
                chooseMaterial(style, tally), desert);
    }

    /** Fill material: a fixed choice, or (auto) the dominant solid surface block under the footprint. */
    private static BlockState chooseMaterial(String style, Map<Block, Integer> tally) {
        return switch (style == null ? "auto" : style.toLowerCase(Locale.ROOT)) {
            case "stone" -> Blocks.STONE.getDefaultState();
            case "cobble", "cobblestone" -> Blocks.COBBLESTONE.getDefaultState();
            case "dirt" -> Blocks.DIRT.getDefaultState();
            default -> {
                Block best = tally.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Blocks.STONE);
                if (best == Blocks.GRASS_BLOCK) {
                    yield Blocks.DIRT.getDefaultState();      // grass top → dirt fill underneath
                }
                if (best == Blocks.AIR || best == Blocks.WATER) {
                    yield Blocks.STONE.getDefaultState();
                }
                yield best.getDefaultState();
            }
        };
    }
}
