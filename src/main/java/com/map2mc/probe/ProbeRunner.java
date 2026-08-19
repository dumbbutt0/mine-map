package com.map2mc.probe;

import com.map2mc.Map2mcConfig;
import com.map2mc.place.AnchorMode;
import com.map2mc.place.Bounds;
import com.map2mc.place.PlacementResolver;
import com.map2mc.place.ResolvedBuild;
import com.map2mc.schem.BlockStateResolver;
import com.map2mc.schem.Rotation4;
import com.map2mc.schem.SchemPaths;
import com.map2mc.schem.SchematicData;
import com.map2mc.schem.SchematicReader;
import com.map2mc.terrain.FoundationGenerator;
import com.map2mc.terrain.PlazaPalette;
import com.map2mc.terrain.TerrainSample;
import com.map2mc.terrain.TerrainSampler;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

import java.nio.file.Path;

/**
 * The {@code /map2mc probe} pipeline: resolve WHERE a schematic would go (the same
 * {@link PlacementResolver} + median-grade siting {@code submitHero} uses), sample the terrain, and
 * assemble the {@link ProbeReport} scorecard — WITHOUT placing a block, queuing a build, or touching
 * the camera. The only world interaction is {@link TerrainSampler#sample}, whose chunk
 * force-generation is inherent to reading heightmaps (exactly what {@code scout} already does).
 * Shared by the command handler and the probe GameTest so both exercise the identical path.
 * Server-thread only (the sampler requires it).
 */
public final class ProbeRunner {

    private ProbeRunner() {
    }

    /** A computed scorecard plus where {@link ProbeExporter} wrote it. */
    public record Probed(ProbeReport report, Path file) {
    }

    /**
     * Probe {@code name} at ({@code anchor}, {@code mode}, {@code rot}); write the JSON scorecard
     * under {@code <gameDir>/map2mc/probes/}. Throws on unreadable schematic / empty build / IO —
     * callers report the message (command: {@code sendError}; gametest: fail).
     */
    public static Probed probe(ServerWorld world, String name, BlockPos anchor, AnchorMode mode,
                               Rotation4 rot) throws Exception {
        SchematicData data = SchematicReader.read(SchemPaths.resolveSchematic(name));
        BlockState[] resolved = BlockStateResolver.resolve(data.palette());
        ResolvedBuild build = PlacementResolver.resolve(data, resolved, anchor, mode, rot, true);
        if (build.placements().isEmpty()) {
            throw new IllegalArgumentException("'" + name + "' has no non-air blocks");
        }

        // Terrain + siting: sample the footprint, then sit the base at the median grade — the same
        // decision submitHero makes (superflat / foundation off ⇒ stay at the resolved anchor).
        Bounds bounds = build.bounds();
        TerrainSampler.Result ts = TerrainSampler.sample(world, bounds,
                Map2mcConfig.foundationClearMargin, Map2mcConfig.foundationStyle);
        TerrainSample s = ts.sample();
        boolean superflat = world.getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator;
        int baseY = Map2mcConfig.foundationEnabled && !superflat ? s.medianY() : bounds.minY();
        if (baseY != bounds.minY()) {
            bounds = PlacementResolver.resolve(data, resolved,
                    new BlockPos(anchor.getX(), baseY, anchor.getZ()), mode, rot, true).bounds();
        }

        String biome = PlazaPalette.biomePath(world,
                new BlockPos((bounds.minX() + bounds.maxX()) / 2, baseY,
                        (bounds.minZ() + bounds.maxZ()) / 2));
        ProbeReport report = ProbeReport.of(name, data.width(), data.height(), data.length(),
                build.placements().size(), anchor.getX(), anchor.getY(), anchor.getZ(),
                rot.degrees(), bounds, s, baseY, ts.desert(), biome,
                Map2mcConfig.foundationMaxBridge,
                FoundationGenerator.plan(ts.region(), baseY, ts.solidSurf(), ts.vegTop(),
                        Map2mcConfig.foundationMaxBridge));
        return new Probed(report, ProbeExporter.export(report));
    }

    /** The one-line human summary the command echoes/logs alongside the JSON path. */
    public static String summary(Probed p) {
        ProbeReport r = p.report();
        ProbeReport.Guardrails g = r.guardrails();
        StringBuilder flags = new StringBuilder();
        if (g.clip()) {
            flags.append(" clip+").append(g.clipBlocks());
        }
        if (g.floats()) {
            flags.append(" float+").append(g.floatBlocks());
        }
        if (g.halfBuried()) {
            flags.append(" halfBuried");
        }
        if (g.underwater()) {
            flags.append(" underwater(").append(g.waterColumns()).append(')');
        }
        if (g.bridgeCapExceeded()) {
            flags.append(" bridgeCap+").append(g.bridgeOverCap());
        }
        String biome = r.biome();
        return String.format(java.util.Locale.ROOT,
                "probe '%s' — score %.1f (slope %d, bridge %d, %s), guardrails:%s -> %s",
                r.schemName(), r.total(), r.slope(), r.bridgeDepth(),
                biome.substring(biome.indexOf(':') + 1),
                flags.isEmpty() ? " clean" : flags, p.file());
    }
}
