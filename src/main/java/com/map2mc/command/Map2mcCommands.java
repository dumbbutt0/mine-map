package com.map2mc.command;

import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.build.BuildJob;
import com.map2mc.build.BuildQueue;
import com.map2mc.build.BuildQueueManager;
import com.map2mc.film.AreaProfile;
import com.map2mc.terrain.FoundationGenerator;
import com.map2mc.terrain.PlazaDecorator;
import com.map2mc.terrain.PlazaPalette;
import com.map2mc.terrain.SiteScout;
import com.map2mc.terrain.TerrainSample;
import com.map2mc.terrain.TerrainSampler;
import com.map2mc.film.DirectorCamera;
import com.map2mc.build.BuildSettings;
import com.map2mc.build.ChunkPlacer;
import com.map2mc.build.Placement;
import com.map2mc.build.ordering.AscendingYStrategy;
import com.map2mc.build.ordering.OrderingContext;
import com.map2mc.place.AnchorMode;
import com.map2mc.place.Bounds;
import com.map2mc.place.LookMath;
import com.map2mc.place.LookMath.SourcePose;
import com.map2mc.orbit.LastBuild;
import com.map2mc.orbit.Orbit;
import com.map2mc.orbit.OrbitExporter;
import com.map2mc.orbit.OrbitFlight;
import com.map2mc.orbit.OrbitKeyframe;
import com.map2mc.pace.Pacing;
import com.map2mc.pace.PaceSetting;
import com.map2mc.place.PlacementResolver;
import com.map2mc.place.ResolvedBuild;
import com.map2mc.preview.PreviewManager;
import com.map2mc.recording.RecordingManager;
import com.map2mc.recording.ReplayBridge;
import com.map2mc.schem.BlockStateResolver;
import com.map2mc.schem.Rotation4;
import com.map2mc.schem.SchemPaths;
import com.map2mc.schem.SchematicData;
import com.map2mc.schem.SchematicReader;
import com.map2mc.schem.SchematicReport;
import com.map2mc.undo.UndoLog;
import com.map2mc.undo.UndoManager;
import com.map2mc.shape.TestShapes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the {@code /map2mc} command tree (server-side).
 *
 * <p>Phase 2.5 reworks placement: coordinates are parsed with the vanilla {@link Vec3ArgumentType}
 * so {@code ~ ~ ~} (relative) and {@code ^ ^ ^N} (local) work for free; {@code hero}/{@code preview}
 * share ONE {@link PlacementResolver} so an outline always matches the build; {@code undo} reverts
 * the last build. The default anchor is the build's horizontal center on the ground; the no-coord
 * {@code hero <file>} drops the build in front of the player, facing them.
 */
public final class Map2mcCommands {

    private static final int DEFAULT_BPT = 8;

    private Map2mcCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal(Map2mc.MOD_ID)
                // ── ping ──────────────────────────────────────────────────────────────────────
                .then(literal("ping").executes(ctx -> {
                    final String msg = "map2mc pong (MC " + Map2mc.MC_VERSION + ")";
                    ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
                    Map2mc.LOGGER.info("[{}] {}", Map2mc.MOD_ID, msg);
                    return 1;
                }))
                // ── testbuild <height> [shape] [bpt] ─────────────────────────────────────────
                .then(literal("testbuild")
                        .then(argument("height", IntegerArgumentType.integer(1, 384))
                                .executes(ctx -> testBuild(ctx, "tower", DEFAULT_BPT))
                                .then(argument("shape", StringArgumentType.word())
                                        .executes(ctx -> testBuild(ctx,
                                                StringArgumentType.getString(ctx, "shape"), DEFAULT_BPT))
                                        .then(argument("bpt", IntegerArgumentType.integer(1, 4096))
                                                .executes(ctx -> testBuild(ctx,
                                                        StringArgumentType.getString(ctx, "shape"),
                                                        IntegerArgumentType.getInteger(ctx, "bpt")))))))
                // ── run controls ─────────────────────────────────────────────────────────────
                .then(literal("status").executes(ctx -> {
                    BuildQueue q = BuildQueueManager.get(ctx.getSource().getWorld());
                    ctx.getSource().sendFeedback(() -> Text.literal("map2mc " + q.statusLine()), false);
                    return 1;
                }))
                .then(literal("pause").executes(ctx -> toggle(ctx, true)))
                .then(literal("resume").executes(ctx -> toggle(ctx, false)))
                .then(literal("cancel").executes(ctx -> {
                    boolean ok = BuildQueueManager.get(ctx.getSource().getWorld()).cancelActive();
                    ctx.getSource().sendFeedback(
                            () -> Text.literal(ok ? "map2mc: cancelled active build" : "map2mc: nothing to cancel"),
                            false);
                    return ok ? 1 : 0;
                }))
                .then(literal("speed").then(argument("bpt", IntegerArgumentType.integer(1, 4096))
                        .executes(ctx -> {
                            int bpt = IntegerArgumentType.getInteger(ctx, "bpt");
                            boolean ok = BuildQueueManager.get(ctx.getSource().getWorld())
                                    .setActiveBlocksPerTick(bpt);
                            ctx.getSource().sendFeedback(() -> Text.literal(ok
                                    ? "map2mc: speed set to " + bpt + " blocks/tick"
                                    : "map2mc: no active build to retune"), false);
                            return ok ? 1 : 0;
                        })))
                // ── census <radius> — headless diagnostic: count placed blocks + Y range ──────
                .then(literal("census").then(argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(Map2mcCommands::census)))
                // ── hero <path> [pos] [rotation] [corner|center] — place & build a .schem ─────
                // No coords  -> in front of the player, on the ground, facing them.
                // With pos   -> default anchor is the build's center on the ground.
                .then(literal("hero").then(argument("path", StringArgumentType.string())
                        .executes(Map2mcCommands::heroDefault)
                        .then(argument("pos", Vec3ArgumentType.vec3(false))
                                .executes(ctx -> heroPos(ctx, 0, AnchorMode.CENTER_GROUND))
                                .then(argument("rotation", IntegerArgumentType.integer(0, 270))
                                        .executes(ctx -> heroPos(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rotation"),
                                                AnchorMode.CENTER_GROUND))
                                        .then(literal("corner").executes(ctx -> heroPos(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rotation"),
                                                AnchorMode.CORNER)))
                                        .then(literal("center").executes(ctx -> heroPos(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rotation"),
                                                AnchorMode.CENTER_GROUND)))))))
                // ── preview [clear] / preview <path> [pos] [rotation] [corner|center] ─────────
                .then(literal("preview")
                        .then(literal("clear").executes(ctx -> {
                            boolean ok = PreviewManager.clear(ctx.getSource().getWorld());
                            ctx.getSource().sendFeedback(() -> Text.literal(ok
                                    ? "map2mc: preview cleared" : "map2mc: no active preview"), false);
                            return ok ? 1 : 0;
                        }))
                        .then(argument("path", StringArgumentType.string())
                                .executes(Map2mcCommands::previewDefault)
                                .then(argument("pos", Vec3ArgumentType.vec3(false))
                                        .executes(ctx -> previewPos(ctx, 0, AnchorMode.CENTER_GROUND))
                                        .then(argument("rotation", IntegerArgumentType.integer(0, 270))
                                                .executes(ctx -> previewPos(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "rotation"),
                                                        AnchorMode.CENTER_GROUND))
                                                .then(literal("corner").executes(ctx -> previewPos(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "rotation"),
                                                        AnchorMode.CORNER)))
                                                .then(literal("center").executes(ctx -> previewPos(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "rotation"),
                                                        AnchorMode.CENTER_GROUND)))))))
                // ── pace <duration|instant> | pace bpt <n> — set NEXT build's pacing policy ────
                .then(literal("pace")
                        .then(literal("bpt").then(argument("n", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    PaceSetting.setManual(n);
                                    ctx.getSource().sendFeedback(
                                            () -> Text.literal("map2mc: pace = " + PaceSetting.describe()), false);
                                    return 1;
                                })))
                        .then(argument("duration", StringArgumentType.word())
                                .executes(Map2mcCommands::pace)))
                // ── orbit [radius] [duration] [keyframes] [fly] — export a filming orbit ───────
                .then(literal("orbit")
                        .executes(ctx -> orbit(ctx, -1, Map2mcConfig.orbitRenderSeconds,
                                Map2mcConfig.orbitKeyframes, false))
                        .then(literal("fly").executes(ctx -> orbit(ctx, -1,
                                Map2mcConfig.orbitRenderSeconds, Map2mcConfig.orbitKeyframes, true)))
                        .then(literal("stop").executes(Map2mcCommands::orbitStop))
                        .then(argument("radius", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> orbit(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                        Map2mcConfig.orbitRenderSeconds, Map2mcConfig.orbitKeyframes, false))
                                .then(argument("duration", IntegerArgumentType.integer(1, 3600))
                                        .executes(ctx -> orbit(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "duration"),
                                                Map2mcConfig.orbitKeyframes, false))
                                        .then(argument("keyframes", IntegerArgumentType.integer(2, 64))
                                                .executes(ctx -> orbit(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "duration"),
                                                        IntegerArgumentType.getInteger(ctx, "keyframes"), false))
                                                .then(literal("fly").executes(ctx -> orbit(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "duration"),
                                                        IntegerArgumentType.getInteger(ctx, "keyframes"), true)))))))
                // ── film <path> [pos] [rotation] [radius] [duration] — build + fly the director ─
                // No coords ⇒ in front of the player, facing them; with pos ⇒ center-on-ground.
                // pos accepts vanilla ~ / ^; the trailing [rotation] [radius] [duration] come from a
                // greedy string so they too accept ~ (Brigadier word/int args can't read a bare ~).
                .then(literal("film")
                        .then(literal("stop").executes(Map2mcCommands::filmStop))
                        .then(argument("path", StringArgumentType.string())
                                .executes(Map2mcCommands::filmDefault)
                                .then(argument("pos", Vec3ArgumentType.vec3(false))
                                        .executes(ctx -> filmAt(ctx, ""))
                                        .then(argument("opts", StringArgumentType.greedyString())
                                                .executes(ctx -> filmAt(ctx,
                                                        StringArgumentType.getString(ctx, "opts")))))))
                // ── record <path> [pos] [rotation] [duration] — build + auto-capture via ServerReplay ─
                // Like film, but instead of flying a live camera it drives the external ServerReplay mod
                // to capture the build to a .mcpr server-side (no OBS); the camera is set later in
                // ReplayMod from the exported orbit table. pos accepts ~/^; trailing [rotation] [duration]
                // come from a greedy string so they accept ~ too.
                .then(literal("record")
                        .then(literal("stop").executes(Map2mcCommands::recordStop))
                        .then(argument("path", StringArgumentType.string())
                                .executes(Map2mcCommands::recordDefault)
                                .then(argument("pos", Vec3ArgumentType.vec3(false))
                                        .executes(ctx -> recordAt(ctx, ""))
                                        .then(argument("opts", StringArgumentType.greedyString())
                                                .executes(ctx -> recordAt(ctx,
                                                        StringArgumentType.getString(ctx, "opts")))))))
                // ── scout <path> [radius] — find a good site nearby, then record-build there ────
                .then(literal("scout")
                        .then(argument("path", StringArgumentType.string())
                                .executes(ctx -> runScout(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "path"), 4))
                                .then(argument("radius", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> runScout(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "path"),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                // ── get / set <key> <value> — live-tune the framing knobs (no rebuild) ─────────
                .then(literal("get").executes(Map2mcCommands::configList))
                .then(literal("set")
                        .executes(Map2mcCommands::configList) // no args ⇒ list all (like get)
                        .then(argument("key", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    String rem = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
                                    Map2mcConfig.keys().forEach(k -> {
                                        if (k.toLowerCase(java.util.Locale.ROOT).startsWith(rem)) {
                                            b.suggest(k);
                                        }
                                    });
                                    return b.buildFuture();
                                })
                                .then(argument("value", StringArgumentType.word())
                                        .executes(Map2mcCommands::configSet))))
                // ── undo — revert the most recent build ───────────────────────────────────────
                .then(literal("undo").executes(Map2mcCommands::undo))
                // ── clear <radius> — wipe a cube around you (undoable) ────────────────────────
                .then(literal("clear").then(argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(Map2mcCommands::clearRegion)))
                // ── dumpschem <path> — parse a .schem and write the Python-style report ───────
                .then(literal("dumpschem").then(argument("path", StringArgumentType.string())
                        .executes(Map2mcCommands::dumpSchem))));
    }

    // ── shared resolution ────────────────────────────────────────────────────────────────────

    /** A loaded schematic plus its resolved placements/bounds for a chosen anchor + rotation. */
    private record Resolved(SchematicData data, ResolvedBuild build) {
    }

    /** Load a .schem and resolve it through the SHARED resolver (used by hero AND preview). */
    private static Resolved load(String name, BlockPos anchor, AnchorMode mode, Rotation4 rot)
            throws Exception {
        Path file = SchemPaths.resolveSchematic(name);
        SchematicData data = SchematicReader.read(file);
        BlockState[] resolved = BlockStateResolver.resolve(data.palette());
        ResolvedBuild build = PlacementResolver.resolve(data, resolved, anchor, mode, rot, true);
        return new Resolved(data, build);
    }

    private static SourcePose poseOf(ServerCommandSource src) {
        // ServerCommandSource.getRotation() is a Vec2f of (pitch=x, yaw=y).
        return new SourcePose(src.getPosition(), src.getRotation().y, src.getRotation().x);
    }

    // ── hero ─────────────────────────────────────────────────────────────────────────────────

    /** {@code /map2mc hero <file>} with no coordinates: in front of the player, facing them. */
    private static int heroDefault(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        BlockPos anchor = BlockPos.ofFloored(
                LookMath.frontOfLookHorizontal(pose, Map2mcConfig.frontDistance));
        Rotation4 rot = LookMath.facingRotation(pose.yaw());
        return runHero(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot,
                "front " + (int) Map2mcConfig.frontDistance + "m, facing you");
    }

    private static int heroPos(CommandContext<ServerCommandSource> ctx, int rotationDeg, AnchorMode mode) {
        ServerCommandSource src = ctx.getSource();
        final Rotation4 rot;
        try {
            rot = Rotation4.fromDegrees(rotationDeg);
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal("map2mc: " + e.getMessage()));
            return 0;
        }
        BlockPos anchor = BlockPos.ofFloored(Vec3ArgumentType.getVec3(ctx, "pos"));
        return runHero(src, StringArgumentType.getString(ctx, "path"), anchor, mode, rot,
                "@ " + anchor.toShortString());
    }

    /** What a successful hero submission yields — shared by {@code hero}, {@code film}, {@code record}. */
    private record HeroSubmit(long id, int count, SchematicData data, Bounds bounds, int etaSec, int bpt,
                              double handoffProgress) {
    }

    /** blocks-per-tick to lay {@code count} blocks over {@code seconds} of wall time (≥1). */
    private static int pacedBpt(int count, int seconds) {
        int ticks = Math.max(1, seconds * 20);
        return Math.max(1, (count + ticks - 1) / ticks);
    }

    /**
     * Lock the world to the configured cinematic time of day (and freeze the daylight cycle) at the start
     * of a film/record, so takes aren't shot under a low, bloomy morning sun. Driven by
     * {@code filmTimeOfDay} (a style choice); {@code off} skips it. Best-effort command dispatch at
     * permission level 4 — logs on failure, never blocks the build.
     */
    private static void applyScene(ServerCommandSource src) {
        int tick = Map2mcConfig.timeOfDayTicks();
        if (tick < 0) {
            return; // filmTimeOfDay = off → leave the world's time alone
        }
        try {
            var dispatcher = src.getServer().getCommandManager().getDispatcher();
            ServerCommandSource silent = src.getServer().getCommandSource().withSilent();
            dispatcher.execute("gamerule doDaylightCycle false", silent);
            dispatcher.execute("time set " + tick, silent);
        } catch (Exception e) {
            Map2mc.LOGGER.warn("[map2mc] could not set cinematic time of day: {}", e.toString());
        }
    }

    /**
     * Load + submit a hero build through the shared pipeline (resolver → terrain foundation → pacing →
     * queue → LastBuild). Returns {@code null} after sending an error on failure. Reused by {@code film}
     * so the camera always tracks exactly the build {@code hero} would have made.
     */
    private static HeroSubmit submitHero(ServerCommandSource src, String name, BlockPos anchor,
                                         AnchorMode mode, Rotation4 rot, int sitePrepSeconds) {
        ServerWorld world = src.getWorld();
        try {
            Resolved r = load(name, anchor, mode, rot);
            List<Placement> structure = r.build().placements();
            if (structure.isEmpty()) {
                src.sendError(Text.literal("map2mc: '" + name + "' has no non-air blocks"));
                return null;
            }

            // Terrain-aware foundation: in a NORMAL (non-superflat) world, sit the build at local grade
            // and lay the foundation (clear trees/level a CIRCULAR plaza + bridge to grade) as a SEPARATE
            // FAST pre-job that lands BEFORE the filmed structure — so the timelapse starts on a clean,
            // cleared site (no canopy in the shot; "the spot is easily created"). The structure is
            // re-resolved at the grade Y (resolver unchanged) and is the ONLY filmed build. Superflat /
            // foundationEnabled=false → drop at the anchor exactly as before.
            List<Placement> placements = structure;        // the FILMED build
            Bounds bounds = r.build().bounds();
            List<Placement> foundation = null;             // site-prep pre-job (clear + level + pad), if any
            List<Placement> decoration = null;             // lush plaza landscaping (lawn/paths/trees), if any
            boolean superflat = world.getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator;
            if (Map2mcConfig.foundationEnabled && !superflat) {
                TerrainSampler.Result ts = TerrainSampler.sample(world, bounds,
                        Map2mcConfig.foundationClearMargin, Map2mcConfig.foundationStyle);
                final TerrainSample s = ts.sample();
                final int baseY = s.medianY();
                final boolean desert = ts.desert();
                placements = load(name, new BlockPos(anchor.getX(), baseY, anchor.getZ()), mode, rot)
                        .build().placements();              // structure re-resolved at grade = filmed build
                bounds = Bounds.fromPlacements(placements);
                foundation = FoundationGenerator.generate(ts.region(), baseY,
                        ts.solidSurf(), ts.vegTop(), ts.material(), Map2mcConfig.foundationMaxBridge);
                // Landscape the cleared disc so it's a lush garden, not an empty circle (over the disc
                // MINUS the build footprint). Biome-aware palette; layout is pure + deterministic.
                if (Map2mcConfig.plazaDecorate) {
                    PlazaPalette palette = PlazaPalette.forBiome(world,
                            new BlockPos(anchor.getX(), baseY, anchor.getZ()));
                    decoration = PlazaDecorator.generate(
                            PlazaDecorator.plan(ts.region(), baseY, bounds, Map2mcConfig.plazaTreeRings),
                            palette);
                }
                if (desert || s.hasWaterOrVoid() || s.maxBridgeDepth(baseY) > Map2mcConfig.foundationMaxBridge
                        || s.slope() > Map2mcConfig.foundationSlopeThreshold) {
                    src.sendFeedback(() -> Text.literal(String.format(
                            "map2mc: sited at grade y=%d (slope %d%s%s) — %s.", baseY, s.slope(),
                            s.hasWaterOrVoid() ? ", water" : "", desert ? ", desert" : "",
                            desert ? "a green, flatter biome matches the reference look better"
                                    : "a flatter, dry spot foundations cleaner")), false);
                }
            }

            // Lay the site-prep FIRST (FIFO → before the structure). When sitePrepSeconds>0 (recording),
            // PACE it so the strip + landscaping are a VISIBLE part of the timelapse instead of an instant
            // blink; otherwise lay it FAST (the pre-cleared-site behavior). Order: foundation (strip +
            // level) → plaza (lawn/paths/trees grow) → structure.
            if (foundation != null && !foundation.isEmpty()) {
                int fbpt = sitePrepSeconds > 0 ? pacedBpt(foundation.size(), sitePrepSeconds)
                        : Math.max(2000, (foundation.size() + 3) / 4);
                BuildSettings fset = new BuildSettings(fbpt, Block.NOTIFY_LISTENERS, true);
                BuildQueueManager.get(world).submit(foundation, new AscendingYStrategy(),
                        OrderingContext.fromPlacements(foundation), fset, "foundation " + name);
            }
            if (decoration != null && !decoration.isEmpty()) {
                int dbpt = sitePrepSeconds > 0 ? pacedBpt(decoration.size(), sitePrepSeconds)
                        : Math.max(2000, (decoration.size() + 3) / 4);
                BuildSettings dset = new BuildSettings(dbpt, Block.NOTIFY_LISTENERS, true);
                BuildQueueManager.get(world).submit(decoration, new AscendingYStrategy(),
                        OrderingContext.fromPlacements(decoration), dset, "plaza " + name);
            }

            int count = placements.size();                  // the structure (the filmed build)
            // Duration-driven pacing is the default; /map2mc pace sets the policy, /map2mc speed
            // still live-retunes after submit. blocksPerTick is derived from the structure block count.
            int bpt = PaceSetting.resolveBpt(count);
            BuildSettings settings = new BuildSettings(bpt, Block.NOTIFY_LISTENERS, true);
            long id = BuildQueueManager.get(world).submit(placements, new AscendingYStrategy(),
                    OrderingContext.fromPlacements(placements), settings, "hero " + name);
            int eta = Pacing.estimateSeconds(count, bpt);
            // Remember this build so /map2mc orbit and /map2mc film can frame a camera around it.
            LastBuild.set(world, bounds, eta, "hero " + name);
            // Handoff from the structure's own per-layer profile (the foundation is now a separate job).
            double handoff = AreaProfile.handoffProgress(
                    AreaProfile.areaPerLayer(placements), Map2mcConfig.filmTransitionFrac);
            return new HeroSubmit(id, count, r.data(), bounds, eta, bpt, handoff);
        } catch (Exception e) {
            Map2mc.LOGGER.error("[map2mc] hero failed for {}", name, e);
            src.sendError(Text.literal("map2mc: hero failed: " + e.getMessage()));
            return null;
        }
    }

    private static int runHero(ServerCommandSource src, String name, BlockPos anchor,
                               AnchorMode mode, Rotation4 rot, String posDesc) {
        HeroSubmit h = submitHero(src, name, anchor, mode, rot, 0); // instant site-prep (non-recording)
        if (h == null) {
            return 0;
        }
        SchematicData d = h.data();
        src.sendFeedback(() -> Text.literal(String.format(
                "map2mc: hero '%s' (v%d %dx%dx%d) — %d blocks, %s/%s, %s; %s -> %d b/t (~%ds) id=%d",
                name, d.version(), d.width(), d.height(), d.length(), h.count(), mode, rot, posDesc,
                PaceSetting.describe(), h.bpt(), h.etaSec(), h.id())), false);
        return 1;
    }

    // ── film (Phase A director camera) ─────────────────────────────────────────────────────────

    /** {@code /map2mc film <file>} no coords: build in front of the player, facing them, fly the director. */
    private static int filmDefault(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        BlockPos anchor = BlockPos.ofFloored(
                LookMath.frontOfLookHorizontal(pose, Map2mcConfig.frontDistance));
        Rotation4 rot = LookMath.facingRotation(pose.yaw());
        return runFilm(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot, -1, -1);
    }

    /**
     * {@code /map2mc film <path> <x y z> [rotation] [radius] [duration]}. {@code <x y z>} accepts
     * vanilla {@code ~}/{@code ^} (via {@code Vec3ArgumentType}). The trailing options, parsed here
     * from a greedy string so a bare {@code ~} is read literally:
     * <ul>
     *   <li>{@code rotation}: {@code ~} = face the player (the default), else {@code 0|90|180|270}</li>
     *   <li>{@code radius}: {@code ~} = dynamic current-extent framing (the default), else a number</li>
     *   <li>{@code duration}: {@code ~} = the current pace (the default), else seconds</li>
     * </ul>
     */
    private static int filmAt(CommandContext<ServerCommandSource> ctx, String opts) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        String[] t = opts.isBlank() ? new String[0] : opts.trim().split("\\s+");
        String rotS = t.length > 0 ? t[0] : "~"; // default: face the player (matches the no-coord case)
        String radS = t.length > 1 ? t[1] : "~"; // default: dynamic framing
        String durS = t.length > 2 ? t[2] : "~"; // default: current pace
        final Rotation4 rot;
        final int radius;
        final int duration;
        try {
            rot = "~".equals(rotS) ? LookMath.facingRotation(pose.yaw()) : Rotation4.fromDegrees(Integer.parseInt(rotS));
            radius = "~".equals(radS) ? -1 : Integer.parseInt(radS);
            duration = "~".equals(durS) ? -1 : Integer.parseInt(durS);
        } catch (IllegalArgumentException e) { // NumberFormatException is a subclass
            src.sendError(Text.literal(
                    "map2mc: film <path> [x y z] [rotation ~|0|90|180|270] [radius ~|n] [duration ~|n]"));
            return 0;
        }
        BlockPos anchor = BlockPos.ofFloored(Vec3ArgumentType.getVec3(ctx, "pos"));
        return runFilm(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot, radius, duration);
    }

    /**
     * Submit the build (reusing the hero pipeline) and start the frontier-tracking director. A player
     * source flies the camera live; a console/dedicated-server source runs the director in log-only
     * mode (the headless gate's path).
     */
    private static int runFilm(ServerCommandSource src, String name, BlockPos anchor, AnchorMode mode,
                               Rotation4 rot, int radius, int durationSecs) {
        ServerWorld world = src.getWorld();
        ServerPlayerEntity player = src.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
        applyScene(src); // lock the cinematic time of day (filmTimeOfDay)
        if (durationSecs > 0) {
            PaceSetting.setDuration(durationSecs); // "film this in N seconds" (sets the build pace)
        }
        HeroSubmit h = submitHero(src, name, anchor, mode, rot, 0); // instant site-prep (film = OBS path)
        if (h == null) {
            return 0;
        }
        // Track the STRUCTURE job specifically (a foundation pre-job may sit ahead of it at the queue head).
        BuildJob job = BuildQueueManager.get(world).byId(h.id());
        if (job == null) {
            src.sendError(Text.literal("map2mc: no active build to film"));
            return 0;
        }
        // The pre-first-block aim is the build's BASE (not its mid-height center): the monotonic
        // frontier-Y clamp would otherwise pin the framing at mid-height and never tighten on the base.
        Vec3d fallbackAim = Vec3d.ofCenter(h.bounds().centerBase());
        // Precompute the aerial→orbit handoff from the schematic's per-layer block counts (the base→
        // vertical narrowing); <0 = no clear transition → the director falls back to the height frac.
        double handoffProgress = h.handoffProgress(); // computed from the actual submitted (combined) blocks
        // radius <= 0 → dynamic current-extent framing (default); > 0 pins the orbit radius.
        DirectorCamera.start(world, player, job, h.bounds(), radius, Map2mcConfig.frontierWindow,
                fallbackAim, h.etaSec(), handoffProgress);

        final String framing = radius > 0
                ? "fixed orbit r=" + radius : "dynamic framing (tracks current extent)";
        final String camState = player != null
                ? "spectator + camera engaged" : "headless: logging director poses (no camera)";
        src.sendFeedback(() -> Text.literal(String.format(
                "map2mc: film '%s' — %d blocks, %s; frontier-tracking %s, window %d. %s; "
                        + "/map2mc film stop to end.",
                name, h.count(), PaceSetting.describe(), framing, Map2mcConfig.frontierWindow, camState)), false);
        return 1;
    }

    private static int filmStop(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        boolean ok = DirectorCamera.stop(src.getWorld(), src.getServer());
        src.sendFeedback(() -> Text.literal(
                ok ? "map2mc: film stopped (build continues)" : "map2mc: not filming"), false);
        return ok ? 1 : 0;
    }

    // ── record (ServerReplay auto-capture) ─────────────────────────────────────────────────────

    /** {@code /map2mc record <file>} no coords: build in front of the player, facing them; auto-capture. */
    private static int recordDefault(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        BlockPos anchor = BlockPos.ofFloored(
                LookMath.frontOfLookHorizontal(pose, Map2mcConfig.frontDistance));
        Rotation4 rot = LookMath.facingRotation(pose.yaw());
        return runRecord(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot, -1);
    }

    /**
     * {@code /map2mc record <path> <x y z> [rotation] [duration]}. {@code <x y z>} accepts vanilla
     * {@code ~}/{@code ^}; the trailing options are parsed from a greedy string so a bare {@code ~} reads:
     * {@code rotation} ({@code ~} = face the player, else {@code 0|90|180|270}) and {@code duration}
     * ({@code ~} = the current pace, else seconds).
     */
    private static int recordAt(CommandContext<ServerCommandSource> ctx, String opts) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        String[] t = opts.isBlank() ? new String[0] : opts.trim().split("\\s+");
        String rotS = t.length > 0 ? t[0] : "~"; // default: face the player
        String durS = t.length > 1 ? t[1] : "~"; // default: current pace
        final Rotation4 rot;
        final int duration;
        try {
            rot = "~".equals(rotS) ? LookMath.facingRotation(pose.yaw()) : Rotation4.fromDegrees(Integer.parseInt(rotS));
            duration = "~".equals(durS) ? -1 : Integer.parseInt(durS);
        } catch (IllegalArgumentException e) { // NumberFormatException is a subclass
            src.sendError(Text.literal(
                    "map2mc: record <path> [x y z] [rotation ~|0|90|180|270] [duration ~|n]"));
            return 0;
        }
        BlockPos anchor = BlockPos.ofFloored(Vec3ArgumentType.getVec3(ctx, "pos"));
        return runRecord(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot, duration);
    }

    /**
     * Submit the build (reusing the hero pipeline), engage the cinematic DIRECTOR camera (like
     * {@code film} — the player is flown, not free-roaming), AND auto-capture via ServerReplay. The
     * site-prep is PACED ({@code recordSitePrepSeconds}) so the strip + plaza landscaping are a visible
     * part of the timelapse. ServerReplay records the chunk area server-side; the director drives the
     * live/OBS view while the {@code .mcpr} is for a ReplayMod re-camera. If ServerReplay is absent the
     * build + director still run (only the {@code .mcpr} capture is skipped).
     */
    private static int runRecord(ServerCommandSource src, String name, BlockPos anchor, AnchorMode mode,
                                 Rotation4 rot, int durationSecs) {
        ServerWorld world = src.getWorld();
        MinecraftServer server = src.getServer();
        ServerPlayerEntity player = src.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
        boolean canRecord = ReplayBridge.available(server);
        applyScene(src); // lock the cinematic time of day (filmTimeOfDay)
        if (durationSecs > 0) {
            PaceSetting.setDuration(durationSecs); // "record this in N seconds" (sets the build pace)
        }
        HeroSubmit h = submitHero(src, name, anchor, mode, rot, Map2mcConfig.recordSitePrepSeconds);
        if (h == null) {
            return 0;
        }
        // Engage the director camera (cinematic spectator orbit) — same framing path as /map2mc film.
        BuildJob job = BuildQueueManager.get(world).byId(h.id());
        if (job == null) {
            src.sendError(Text.literal("map2mc: no active build to record"));
            return 0;
        }
        Vec3d fallbackAim = Vec3d.ofCenter(h.bounds().centerBase());
        DirectorCamera.start(world, player, job, h.bounds(), -1, Map2mcConfig.frontierWindow,
                fallbackAim, h.etaSec(), h.handoffProgress());

        ReplayBridge.ChunkRegion region = ReplayBridge.regionFor(h.bounds(), Map2mcConfig.recordChunkMargin);
        UUID uuid = player == null ? null : player.getUuid();
        // Track the STRUCTURE job (the foundation + plaza pre-jobs sit ahead of it); RecordingManager
        // stops the capture + exports the orbit table when this build leaves the queue.
        RecordingManager.start(world, server, h.id(), "map2mc_" + h.id(), h.bounds(), h.etaSec(),
                "record " + name, uuid, region);

        final String cam = player != null ? "director camera engaged" : "headless (no camera)";
        if (canRecord) {
            src.sendFeedback(() -> Text.literal(String.format(
                    "map2mc: record '%s' — %d blocks, %s; %s, capturing chunks r%d via ServerReplay. "
                            + "Site is stripped + landscaped first, then the build rises. "
                            + "Don't open the pause menu while recording. /map2mc record stop to end.",
                    name, h.count(), PaceSetting.describe(), cam, region.radius())), false);
        } else {
            src.sendFeedback(() -> Text.literal(String.format(
                    "map2mc: record '%s' — %s, building WITHOUT a .mcpr (no ServerReplay). To capture: "
                            + "either enable ReplayMod's 'Record Singleplayer' (Options → ReplayMod Settings) "
                            + "— it's already installed — or add ServerReplay + Fabric Language Kotlin (1.21.11). "
                            + "Or just OBS-capture this director view. Orbit table still exports. See RECORDING.md §H.",
                    name, cam)), false);
        }
        return 1;
    }

    private static int recordStop(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        DirectorCamera.stop(src.getWorld(), src.getServer()); // end the cinematic camera too
        boolean ok = RecordingManager.stop(src.getWorld(), src.getServer());
        src.sendFeedback(() -> Text.literal(ok
                ? "map2mc: recording stopped + saved (build continues)" : "map2mc: not recording"), false);
        return ok ? 1 : 0;
    }

    // ── scout (programmatic site selection) ────────────────────────────────────────────────────

    /**
     * Search the world around the source for a good site for {@code name}, report the ranked top spots,
     * then record-build at the best one. Each probe force-generates chunks, so the grid is capped.
     */
    private static int runScout(ServerCommandSource src, String name, int radius) {
        ServerWorld world = src.getWorld();
        BlockPos origin = BlockPos.ofFloored(src.getPosition());
        final Bounds footprint;
        try {
            footprint = load(name, origin, AnchorMode.CENTER_GROUND, Rotation4.fromDegrees(0)).build().bounds();
        } catch (Exception e) {
            src.sendError(Text.literal("map2mc: scout could not load '" + name + "': " + e.getMessage()));
            return 0;
        }
        src.sendFeedback(() -> Text.literal(String.format(
                "map2mc: scouting near you for '%s' (footprint %dx%d, radius %d chunks) — sampling sites…",
                name, footprint.sizeX(), footprint.sizeZ(), radius)), false);

        List<SiteScout.Candidate> ranked = SiteScout.scout(world, origin, footprint, radius, 25);
        if (ranked.isEmpty()) {
            src.sendError(Text.literal("map2mc: scout found no candidate sites"));
            return 0;
        }
        StringBuilder sb = new StringBuilder("map2mc: top sites — ");
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            SiteScout.Candidate c = ranked.get(i);
            sb.append(String.format("#%d (%d,%d) score%.0f %s slope%d%s%s; ",
                    i + 1, c.center().getX(), c.center().getZ(), c.score(),
                    c.biome().isEmpty() ? "?" : c.biome(), c.slope(),
                    c.waterOrVoid() ? " water" : "", c.desert() ? " desert" : ""));
        }
        final String report = sb.toString();
        src.sendFeedback(() -> Text.literal(report), false);

        final SiteScout.Candidate best = ranked.get(0);
        BlockPos anchor = new BlockPos(best.center().getX(), origin.getY(), best.center().getZ());
        src.sendFeedback(() -> Text.literal(String.format(
                "map2mc: best site (%d, %d) — %s. Building there with the record treatment.",
                best.center().getX(), best.center().getZ(),
                best.biome().isEmpty() ? "?" : best.biome())), false);
        return runRecord(src, name, anchor, AnchorMode.CENTER_GROUND, Rotation4.fromDegrees(0), -1);
    }

    // ── config (live tuning) ───────────────────────────────────────────────────────────────────

    /** {@code /map2mc get} (and {@code /map2mc set} with no args): list every knob + current value. */
    private static int configList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        src.sendFeedback(() -> Text.literal("map2mc config (runtime-only — resets on relaunch):"), false);
        for (String line : Map2mcConfig.describe()) {
            src.sendFeedback(() -> Text.literal("  " + line), false);
        }
        return 1;
    }

    /** {@code /map2mc set <key> <value>}: validate + assign one knob, echo the stored value. */
    private static int configSet(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        try {
            String stored = Map2mcConfig.set(key, value);
            src.sendFeedback(() -> Text.literal(
                    "map2mc: " + key + " = " + stored + "  (runtime-only; /map2mc get lists all)"), false);
            // Setting the output dir is only useful if it's applied NOW (it's a runtime value set after
            // join, so the server-start hook never sees it): point ServerReplay at it immediately.
            if (key.equalsIgnoreCase("recordOutputDir")) {
                ReplayBridge.applyOutputDir(src.getServer());
            }
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal("map2mc set '" + key + "': " + e.getMessage()
                    + "  — keys: " + String.join(", ", Map2mcConfig.keys())));
            return 0;
        }
    }

    // ── preview ──────────────────────────────────────────────────────────────────────────────

    private static int previewDefault(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        SourcePose pose = poseOf(src);
        BlockPos anchor = BlockPos.ofFloored(
                LookMath.frontOfLookHorizontal(pose, Map2mcConfig.frontDistance));
        Rotation4 rot = LookMath.facingRotation(pose.yaw());
        return runPreview(src, StringArgumentType.getString(ctx, "path"),
                anchor, AnchorMode.CENTER_GROUND, rot, "front " + (int) Map2mcConfig.frontDistance + "m");
    }

    private static int previewPos(CommandContext<ServerCommandSource> ctx, int rotationDeg, AnchorMode mode) {
        ServerCommandSource src = ctx.getSource();
        final Rotation4 rot;
        try {
            rot = Rotation4.fromDegrees(rotationDeg);
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal("map2mc: " + e.getMessage()));
            return 0;
        }
        BlockPos anchor = BlockPos.ofFloored(Vec3ArgumentType.getVec3(ctx, "pos"));
        return runPreview(src, StringArgumentType.getString(ctx, "path"), anchor, mode, rot,
                "@ " + anchor.toShortString());
    }

    private static int runPreview(ServerCommandSource src, String name, BlockPos anchor,
                                  AnchorMode mode, Rotation4 rot, String posDesc) {
        ServerWorld world = src.getWorld();
        try {
            Resolved r = load(name, anchor, mode, rot);
            int count = r.build().placements().size();
            if (count == 0) {
                src.sendError(Text.literal("map2mc: '" + name + "' has no non-air blocks"));
                return 0;
            }
            Bounds b = r.build().bounds();
            PreviewManager.show(world, b, Map2mcConfig.previewDurationTicks);
            src.sendFeedback(() -> Text.literal(String.format(
                    "map2mc: preview '%s' — %d blocks, %s/%s %s; box [%d,%d,%d]..[%d,%d,%d], clears in %ds "
                            + "(/map2mc preview clear)",
                    name, count, mode, rot, posDesc, b.minX(), b.minY(), b.minZ(),
                    b.maxX(), b.maxY(), b.maxZ(), Map2mcConfig.previewDurationTicks / 20)), false);
            return 1;
        } catch (Exception e) {
            Map2mc.LOGGER.error("[map2mc] preview failed for {}", name, e);
            src.sendError(Text.literal("map2mc: preview failed: " + e.getMessage()));
            return 0;
        }
    }

    // ── undo / clear ─────────────────────────────────────────────────────────────────────────

    /** {@code /map2mc pace <120|2min|30s|instant>} — duration presets or raw seconds. */
    private static int pace(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String s = StringArgumentType.getString(ctx, "duration").toLowerCase();
        try {
            if (s.equals("instant")) {
                PaceSetting.setInstant();
            } else if (s.endsWith("min")) {
                PaceSetting.setDuration(Integer.parseInt(s.substring(0, s.length() - 3).trim()) * 60);
            } else if (s.endsWith("s")) {
                PaceSetting.setDuration(Integer.parseInt(s.substring(0, s.length() - 1).trim()));
            } else {
                PaceSetting.setDuration(Integer.parseInt(s.trim()));
            }
        } catch (NumberFormatException e) {
            src.sendError(Text.literal(
                    "map2mc: pace takes 120, 2min, 30s, instant, or 'bpt <n>' — got '" + s + "'"));
            return 0;
        }
        src.sendFeedback(() -> Text.literal("map2mc: pace = " + PaceSetting.describe()
                + " (applies to the next hero build; /map2mc speed retunes a running one)"), false);
        return 1;
    }

    /** Compute + export a filming orbit around the most recent build; optionally fly it. */
    private static int orbit(CommandContext<ServerCommandSource> ctx, int radius, int durationSecs,
                             int keyframes, boolean fly) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        LastBuild.Info info = LastBuild.get(world);
        if (info == null) {
            src.sendError(Text.literal("map2mc: no recent build in this world — run /map2mc hero first"));
            return 0;
        }
        Orbit.Params p = Orbit.Params.fromBounds(info.bounds(),
                radius <= 0 ? 0 : radius, Map2mcConfig.orbitRadiusFactor);
        long renderMs = durationSecs * 1000L;
        long buildMs = info.buildSeconds() * 1000L;
        List<OrbitKeyframe> kfs = Orbit.compute(p.cx(), p.cz(), p.aimY(), p.radius(),
                p.camYStart(), p.camYEnd(), keyframes, renderMs, buildMs);
        try {
            Path out = OrbitExporter.export(kfs, p, durationSecs, info);
            src.sendFeedback(() -> Text.literal(String.format(
                    "map2mc: orbit — %d keyframes, radius %d, render %ds (build ~%ds) -> %s",
                    keyframes, (int) p.radius(), durationSecs, info.buildSeconds(), out)), false);
            Map2mc.LOGGER.info("[map2mc] orbit table:\n{}", OrbitExporter.render(kfs, p, durationSecs, info));
        } catch (IOException e) {
            Map2mc.LOGGER.error("[map2mc] orbit export failed", e);
            src.sendError(Text.literal("map2mc: orbit export failed: " + e.getMessage()));
            return 0;
        }
        if (fly) {
            Entity e = src.getEntity();
            if (e instanceof ServerPlayerEntity pl) {
                OrbitFlight.start(pl, world, p, durationSecs);
                src.sendFeedback(() -> Text.literal("map2mc: flying camera (spectator) for "
                        + durationSecs + "s — /map2mc orbit stop to abort"), false);
            } else {
                src.sendError(Text.literal("map2mc: 'fly' requires a player command source"));
            }
        }
        return 1;
    }

    private static int orbitStop(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        Entity e = src.getEntity();
        if (!(e instanceof ServerPlayerEntity pl)) {
            src.sendError(Text.literal("map2mc: 'orbit stop' requires a player command source"));
            return 0;
        }
        boolean ok = OrbitFlight.cancel(pl.getUuid(), src.getServer());
        src.sendFeedback(() -> Text.literal(ok
                ? "map2mc: orbit fly stopped" : "map2mc: no orbit fly in progress"), false);
        return ok ? 1 : 0;
    }

    private static int undo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        // If a build is mid-flight, cancel it first (its partial change-set is pushed for undo).
        boolean cancelled = BuildQueueManager.get(world).cancelActive();
        int restored = UndoManager.undoLast(world);
        if (restored < 0) {
            src.sendFeedback(() -> Text.literal("map2mc: nothing to undo"), false);
            return 0;
        }
        final int n = restored;
        src.sendFeedback(() -> Text.literal("map2mc: undo restored " + n + " blocks"
                + (cancelled ? " (cancelled active build)" : "")), false);
        return 1;
    }

    /** Wipe non-air blocks in a cube around the source, capturing priors so {@code undo} works. */
    private static int clearRegion(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        BlockPos c = BlockPos.ofFloored(src.getPosition());
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        BlockState air = Blocks.AIR.getDefaultState();
        UndoLog log = new UndoLog("clear r=" + r);
        ChunkPlacer placer = new ChunkPlacer(world);
        int removed = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!world.getBlockState(m).isAir()) {
                        placer.place(new Placement(m.toImmutable(), air), Block.NOTIFY_LISTENERS, log);
                        removed++;
                    }
                }
            }
        }
        placer.releaseAll();
        UndoManager.push(world, log);
        final int n = removed;
        src.sendFeedback(() -> Text.literal("map2mc: cleared " + n + " blocks (undoable)"), false);
        return 1;
    }

    // ── diagnostics (unchanged) ────────────────────────────────────────────────────────────────

    private static int dumpSchem(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "path");
        try {
            Path file = SchemPaths.resolveSchematic(name);
            SchematicData data = SchematicReader.read(file);
            String report = SchematicReport.render(data);

            Files.createDirectories(SchemPaths.dumpDir());
            Path out = SchemPaths.dumpDir().resolve(file.getFileName().toString() + ".dump.txt");
            Files.writeString(out, report);

            src.sendFeedback(() -> Text.literal(
                    "map2mc: dumped " + name + " (Sponge v" + data.version() + ") -> " + out), false);
            Map2mc.LOGGER.info("[map2mc] dumpschem {} (v{}):\n{}", name, data.version(), report);
            return 1;
        } catch (Exception e) {
            Map2mc.LOGGER.error("[map2mc] dumpschem failed for {}", name, e);
            src.sendError(Text.literal("map2mc: dumpschem failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int census(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        BlockPos c = BlockPos.ofFloored(src.getPosition());
        int r = IntegerArgumentType.getInteger(ctx, "radius");

        Map<String, Integer> counts = new HashMap<>();
        int total = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState bs = world.getBlockState(m);
                    if (bs.isAir()) {
                        continue;
                    }
                    counts.merge(Registries.BLOCK.getId(bs.getBlock()).toString(), 1, Integer::sum);
                    total++;
                    minY = Math.min(minY, m.getY());
                    maxY = Math.max(maxY, m.getY());
                }
            }
        }
        final int fTotal = total, fMinY = minY, fMaxY = maxY;
        String breakdown = counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .map(e -> e.getKey().replace("minecraft:", "") + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        src.sendFeedback(() -> Text.literal("map2mc census r=" + r + ": " + fTotal + " non-air, Y "
                + (fTotal == 0 ? "-" : fMinY + ".." + fMaxY)), false);
        Map2mc.LOGGER.info("[map2mc] census r={} total={} Y={}..{} :: {}",
                r, fTotal, total == 0 ? "-" : fMinY, total == 0 ? "-" : fMaxY, breakdown);
        return 1;
    }

    private static int toggle(CommandContext<ServerCommandSource> ctx, boolean pause) {
        BuildQueue q = BuildQueueManager.get(ctx.getSource().getWorld());
        boolean ok = pause ? q.pauseActive() : q.resumeActive();
        ctx.getSource().sendFeedback(() -> Text.literal(ok
                ? "map2mc: " + (pause ? "paused" : "resumed")
                : "map2mc: no active build"), false);
        return ok ? 1 : 0;
    }

    private static int testBuild(CommandContext<ServerCommandSource> ctx, String shape, int bpt) {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        int height = IntegerArgumentType.getInteger(ctx, "height");
        BlockPos base = BlockPos.ofFloored(src.getPosition());

        List<Placement> placements = "pyramid".equalsIgnoreCase(shape)
                ? TestShapes.steppedPyramid(height, base)
                : TestShapes.hollowTaperingTower(height, base);
        String shapeLabel = "pyramid".equalsIgnoreCase(shape) ? "pyramid" : "tower";

        BuildSettings settings = new BuildSettings(bpt, Block.NOTIFY_LISTENERS, false);
        long id = BuildQueueManager.get(world).submit(
                placements, new AscendingYStrategy(), OrderingContext.fromPlacements(placements),
                settings, "testbuild " + shapeLabel + " h=" + height);

        final int count = placements.size();
        src.sendFeedback(() -> Text.literal(String.format(
                "map2mc: queued %s (%d blocks) id=%d, ASCENDING_Y @ %d b/t", shapeLabel, count, id, bpt)),
                false);
        return 1;
    }
}
