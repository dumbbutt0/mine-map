package com.map2mc.terrain;

import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.place.Bounds;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Searches the CURRENT world for a good spot to drop a fixed-footprint build, so the result doesn't
 * look plopped. Samples a grid of candidate centres around an origin with the existing
 * {@link TerrainSampler}, scores each with the pure {@link SiteScore}, and returns them best-first.
 *
 * <p>Each candidate force-generates its footprint chunks (via the sampler), so the candidate count is
 * capped and logged. Seed-level selection across different worlds is out of scope (it would need
 * regenerating whole worlds) — scout the world you're in.
 */
public final class SiteScout {

    private SiteScout() {
    }

    /** A scored candidate site (centre at local grade) with the inputs behind the score, for reporting. */
    public record Candidate(BlockPos center, double score, int slope, boolean waterOrVoid,
                            boolean desert, int bridgeDepth, String biome) {
    }

    /**
     * @param origin     centre of the search (typically the player); the build footprint size is taken
     *                   from {@code footprint}
     * @param radius     search half-extent in CHUNKS
     * @param maxCandidates hard cap on probes (each force-gens chunks)
     */
    public static List<Candidate> scout(ServerWorld world, BlockPos origin, Bounds footprint,
                                        int radius, int maxCandidates) {
        int sizeX = footprint.sizeX();
        int sizeZ = footprint.sizeZ();
        int step = Math.max(16, Math.max(sizeX, sizeZ));        // candidates at least a footprint apart
        int span = Math.max(1, radius) * 16;
        int gridHalf = Math.max(1, Math.min(2, span / Math.max(1, step))); // 3×3 or 5×5
        List<Candidate> out = new ArrayList<>();
        int probed = 0;
        boolean capped = false;
        for (int gz = -gridHalf; gz <= gridHalf && !capped; gz++) {
            for (int gx = -gridHalf; gx <= gridHalf; gx++) {
                if (probed >= maxCandidates) {
                    capped = true;
                    break;
                }
                int cx = origin.getX() + gx * step;
                int cz = origin.getZ() + gz * step;
                int halfX = sizeX / 2;
                int halfZ = sizeZ / 2;
                Bounds fp = new Bounds(cx - halfX, footprint.minY(), cz - halfZ,
                        cx - halfX + sizeX - 1, footprint.maxY(), cz - halfZ + sizeZ - 1);
                TerrainSampler.Result ts = TerrainSampler.sample(world, fp,
                        Map2mcConfig.foundationClearMargin, Map2mcConfig.foundationStyle);
                TerrainSample s = ts.sample();
                String biome = PlazaPalette.biomePath(world, new BlockPos(cx, s.medianY(), cz));
                int bridge = s.maxBridgeDepth(s.medianY());
                double score = SiteScore.score(s.slope(), s.hasWaterOrVoid(), ts.desert(), bridge,
                        SiteScore.biomeBonus(biome));
                out.add(new Candidate(new BlockPos(cx, s.medianY(), cz), score, s.slope(),
                        s.hasWaterOrVoid(), ts.desert(), bridge, biome));
                probed++;
            }
        }
        if (capped) {
            Map2mc.LOGGER.info("[map2mc][scout] candidate grid capped at {} (radius {} chunks)",
                    maxCandidates, radius);
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out;
    }
}
