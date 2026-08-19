package com.map2mc.probe;

import com.map2mc.place.Bounds;
import com.map2mc.terrain.FoundationGenerator;
import com.map2mc.terrain.SiteScore;
import com.map2mc.terrain.TerrainSample;

import java.util.List;

/**
 * Everything a {@code /map2mc probe} scorecard carries, as pure data — the SPEC §8 "structured JSON
 * from the mod" reward channel (guardrails + SiteScore), computed WITHOUT placing a block. The
 * factories here only combine numbers the existing pure classes already produce
 * ({@link TerrainSample}, {@link SiteScore}, {@link FoundationGenerator#plan}); nothing re-derives
 * terrain math, so this unit-tests with no Minecraft bootstrap.
 *
 * @param schemName          the schematic name as given to the command
 * @param width X, @param height Y, @param length Z spans of the (unrotated) schematic
 * @param nonAir             resolved non-air block count (what the build queue would place)
 * @param footprintAspect    rotated footprint sizeX / sizeZ
 * @param anchorX/Y/Z        the anchor the user asked for (pre-siting)
 * @param rotationDeg        0/90/180/270
 * @param baseY              the Y the build would be sited at (median grade, or the resolved base
 *                           when the terrain foundation is off / the world is superflat)
 * @param bounds             the sited build's world bounds
 * @param slope..biomeBonus  the exact {@link SiteScore} inputs, each reported as its own field
 * @param total              {@link SiteScore#score} over those inputs
 * @param guardrails         geometric red flags, each a boolean + its magnitude
 * @param foundationFillCells/ClearCells volume the foundation would bridge/clear
 *                           ({@link FoundationGenerator#plan} cell counts — the edit-parsimony seed)
 */
public record ProbeReport(
        String schemName, int width, int height, int length, int nonAir, double footprintAspect,
        int anchorX, int anchorY, int anchorZ, int rotationDeg, int baseY, Bounds bounds,
        int slope, boolean waterOrVoid, boolean desert, int bridgeDepth, String biome,
        double biomeBonus, double total,
        Guardrails guardrails, int foundationFillCells, int foundationClearCells) {

    /**
     * Geometric guardrails (SPEC §4), each as flag + magnitude so a reward can weight them:
     *
     * @param clip               terrain pokes above the build's base into the structure;
     *                           {@code clipBlocks} = how far above (blocks)
     * @param floats             ground sits below the base somewhere (a gap the foundation must
     *                           bridge); {@code floatBlocks} = the deepest gap
     * @param halfBuried         the terrain above the base reaches ≥ half the build height;
     *                           {@code buriedFrac} = that depth / build height
     * @param underwater         any footprint column is water or void; {@code waterColumns} = count
     * @param bridgeCapExceeded  the gap under the lowest column exceeds {@code foundationMaxBridge}
     *                           (the exact condition {@code submitHero} warns on);
     *                           {@code bridgeOverCap} = blocks past the cap
     */
    public record Guardrails(boolean clip, int clipBlocks,
                             boolean floats, int floatBlocks,
                             boolean halfBuried, double buriedFrac,
                             boolean underwater, int waterColumns,
                             boolean bridgeCapExceeded, int bridgeOverCap) {

        /** Derive every guardrail from the footprint's {@link TerrainSample} at a chosen base Y. */
        public static Guardrails of(TerrainSample s, int baseY, int buildHeight, int maxBridge) {
            int clipBlocks = Math.max(0, s.maxY() - baseY);
            int floatBlocks = s.maxBridgeDepth(baseY);
            double buriedFrac = buildHeight <= 0 ? 0 : (double) clipBlocks / buildHeight;
            int overCap = Math.max(0, floatBlocks - maxBridge);
            return new Guardrails(
                    clipBlocks > 0, clipBlocks,
                    floatBlocks > 0, floatBlocks,
                    clipBlocks >= Math.ceilDiv(Math.max(1, buildHeight), 2), buriedFrac,
                    s.hasWaterOrVoid(), s.waterOrVoidColumns(),
                    overCap > 0, overCap);
        }
    }

    /**
     * Assemble a report from the already-computed pieces. {@code slope}/{@code waterOrVoid}/
     * {@code bridgeDepth} come off {@code sample} (at {@code baseY}), the score from
     * {@link SiteScore} — the SAME calls {@code SiteScout} makes per candidate.
     */
    public static ProbeReport of(String schemName, int width, int height, int length, int nonAir,
                                 int anchorX, int anchorY, int anchorZ, int rotationDeg,
                                 Bounds sited, TerrainSample sample, int baseY,
                                 boolean desert, String biome, int maxBridge,
                                 List<FoundationGenerator.Cell> foundationPlan) {
        int bridgeDepth = sample.maxBridgeDepth(baseY);
        double biomeBonus = SiteScore.biomeBonus(biome);
        double total = SiteScore.score(sample.slope(), sample.hasWaterOrVoid(), desert,
                bridgeDepth, biomeBonus);
        int fill = 0;
        for (FoundationGenerator.Cell c : foundationPlan) {
            if (c.fill()) {
                fill++;
            }
        }
        return new ProbeReport(schemName, width, height, length, nonAir,
                sited.sizeZ() == 0 ? 0 : (double) sited.sizeX() / sited.sizeZ(),
                anchorX, anchorY, anchorZ, rotationDeg, baseY, sited,
                sample.slope(), sample.hasWaterOrVoid(), desert, bridgeDepth, biome,
                biomeBonus, total,
                Guardrails.of(sample, baseY, sited.sizeY(), maxBridge),
                fill, foundationPlan.size() - fill);
    }
}
