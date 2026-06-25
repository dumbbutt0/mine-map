package com.map2mc.terrain;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Biome-aware block palette for {@link PlazaDecorator}. Minecraft-coupled (chosen from the site's
 * biome family), so it lives apart from the pure geometry and isn't unit-tested. Leaves are forced
 * {@code persistent} so the planted background trees don't decay after placement.
 */
public record PlazaPalette(BlockState lawn, BlockState path, BlockState log, BlockState leaves,
                           List<BlockState> flowers) {

    /** Deterministic per-position flower pick (no RNG → reproducible builds). */
    public BlockState flower(BlockPos pos) {
        if (flowers.isEmpty()) {
            return Blocks.POPPY.getDefaultState();
        }
        int i = Math.floorMod(pos.getX() * 31 + pos.getZ() * 17, flowers.size());
        return flowers.get(i);
    }

    public static PlazaPalette forBiome(ServerWorld world, BlockPos center) {
        String b = biomePath(world, center);
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState pathB = Blocks.DIRT_PATH.getDefaultState();
        List<BlockState> defFlowers = List.of(
                Blocks.POPPY.getDefaultState(), Blocks.DANDELION.getDefaultState(),
                Blocks.CORNFLOWER.getDefaultState(), Blocks.OXEYE_DAISY.getDefaultState());

        if (b.contains("cherry")) {
            return new PlazaPalette(grass, pathB, Blocks.CHERRY_LOG.getDefaultState(),
                    persistent(Blocks.CHERRY_LEAVES),
                    List.of(Blocks.PINK_PETALS.getDefaultState(), Blocks.ALLIUM.getDefaultState()));
        }
        if (b.contains("jungle")) {
            return new PlazaPalette(grass, pathB, Blocks.JUNGLE_LOG.getDefaultState(),
                    persistent(Blocks.JUNGLE_LEAVES), defFlowers);
        }
        if (b.contains("taiga") || b.contains("snow") || b.contains("grove") || b.contains("frozen")) {
            return new PlazaPalette(grass, pathB, Blocks.SPRUCE_LOG.getDefaultState(),
                    persistent(Blocks.SPRUCE_LEAVES), defFlowers);
        }
        if (b.contains("savanna") || b.contains("badlands")) {
            return new PlazaPalette(grass, pathB, Blocks.ACACIA_LOG.getDefaultState(),
                    persistent(Blocks.ACACIA_LEAVES), defFlowers);
        }
        if (b.contains("birch")) {
            return new PlazaPalette(grass, pathB, Blocks.BIRCH_LOG.getDefaultState(),
                    persistent(Blocks.BIRCH_LEAVES), defFlowers);
        }
        // default temperate (plains / forest / meadow / windswept / …)
        return new PlazaPalette(grass, pathB, Blocks.OAK_LOG.getDefaultState(),
                persistent(Blocks.OAK_LEAVES), defFlowers);
    }

    private static BlockState persistent(Block leaves) {
        return leaves.getDefaultState().with(Properties.PERSISTENT, true);
    }

    /** The biome id path (e.g. {@code cherry_grove}) at {@code pos}, lowercased; "" if unknown. */
    public static String biomePath(ServerWorld world, BlockPos pos) {
        RegistryEntry<Biome> e = world.getBiome(pos);
        Optional<RegistryKey<Biome>> key = e.getKey();
        return key.map(k -> k.getValue().getPath().toLowerCase(Locale.ROOT)).orElse("");
    }
}
