package com.map2mc.command;

import com.map2mc.Map2mc;
import com.map2mc.Map2mcConfig;
import com.map2mc.build.BuildQueue;
import com.map2mc.build.BuildQueueManager;
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
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static int runHero(ServerCommandSource src, String name, BlockPos anchor,
                               AnchorMode mode, Rotation4 rot, String posDesc) {
        ServerWorld world = src.getWorld();
        try {
            Resolved r = load(name, anchor, mode, rot);
            List<Placement> placements = r.build().placements();
            if (placements.isEmpty()) {
                src.sendError(Text.literal("map2mc: '" + name + "' has no non-air blocks"));
                return 0;
            }
            int count = placements.size();
            // Duration-driven pacing is the default; /map2mc pace sets the policy, /map2mc speed
            // still live-retunes after submit. blocksPerTick is derived from the non-air count.
            int bpt = PaceSetting.resolveBpt(count);
            BuildSettings settings = new BuildSettings(bpt, Block.NOTIFY_LISTENERS, true);
            long id = BuildQueueManager.get(world).submit(placements, new AscendingYStrategy(),
                    OrderingContext.fromPlacements(placements), settings, "hero " + name);
            SchematicData d = r.data();
            int eta = Pacing.estimateSeconds(count, bpt);
            // Remember this build so /map2mc orbit can frame a camera around it.
            LastBuild.set(world, r.build().bounds(), eta, "hero " + name);
            src.sendFeedback(() -> Text.literal(String.format(
                    "map2mc: hero '%s' (v%d %dx%dx%d) — %d blocks, %s/%s, %s; %s -> %d b/t (~%ds) id=%d",
                    name, d.version(), d.width(), d.height(), d.length(), count, mode, rot, posDesc,
                    PaceSetting.describe(), bpt, eta, id)), false);
            return 1;
        } catch (Exception e) {
            Map2mc.LOGGER.error("[map2mc] hero failed for {}", name, e);
            src.sendError(Text.literal("map2mc: hero failed: " + e.getMessage()));
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
