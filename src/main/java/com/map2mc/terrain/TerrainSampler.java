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
 * Thin Minecraft wrapper around {@link TerrainSample}: query the LIVE world's surface under a build's
 * footprint into a per-column grid, plus pick a fill material from the dominant surface block. Reading
 * {@code getTopY} loads/generates the chunk synchronously (the build force-loads them again when it
 * places), so no separate force-load is needed here. Server-thread only.
 */
public final class TerrainSampler {

    private TerrainSampler() {
    }

    /** The sampled grid + stats + the chosen fill material. */
    public record Result(TerrainSample sample, int[] surfaceY, BlockState material) {
    }

    /**
     * Sample the surface over {@code footprint}'s XZ. {@code surfaceY} is row-major
     * ({@code index = (z-minZ)*sizeX + (x-minX)}) — the same indexing {@link FoundationGenerator} uses.
     */
    public static Result sample(ServerWorld world, Bounds footprint, String style) {
        // Force the footprint chunks to FULL generation FIRST — getTopY does NOT generate on demand; an
        // ungenerated column reads as the world bottom. (The build force-loads these again when it places.)
        for (int cx = footprint.minX() >> 4; cx <= footprint.maxX() >> 4; cx++) {
            for (int cz = footprint.minZ() >> 4; cz <= footprint.maxZ() >> 4; cz++) {
                world.getChunk(cx, cz);
            }
        }
        int sizeX = footprint.sizeX();
        int n = sizeX * footprint.sizeZ();
        int[] surf = new int[n];
        boolean[] waterOrVoid = new boolean[n];
        Map<Block, Integer> tally = new HashMap<>();
        int bottom = world.getBottomY();
        int i = 0;
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                surf[i] = top;
                BlockState surfaceBlock = world.getBlockState(new BlockPos(x, top - 1, z));
                boolean water = world.getFluidState(new BlockPos(x, top - 1, z)).isOf(Fluids.WATER);
                boolean isVoid = top <= bottom + 1; // no real ground in the column
                waterOrVoid[i] = water || isVoid;
                tally.merge(surfaceBlock.getBlock(), 1, Integer::sum);
                i++;
            }
        }
        return new Result(TerrainSample.of(surf, waterOrVoid), surf, chooseMaterial(style, tally));
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
                    yield Blocks.STONE.getDefaultState();      // nothing solid → stone
                }
                yield best.getDefaultState();
            }
        };
    }
}
