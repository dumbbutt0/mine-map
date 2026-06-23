package com.map2mc.command;

import com.map2mc.Map2mc;
import com.map2mc.build.BuildQueue;
import com.map2mc.build.BuildQueueManager;
import com.map2mc.build.BuildSettings;
import com.map2mc.build.Placement;
import com.map2mc.build.ordering.AscendingYStrategy;
import com.map2mc.build.ordering.OrderingContext;
import com.map2mc.shape.TestShapes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the {@code /map2mc} command tree.
 *
 * <p>Commands run on the server thread (Brigadier executes them there), which is also where all
 * block placement happens — so handlers talk to {@link BuildQueueManager} directly. Phase 1 adds
 * {@code testbuild} plus the run controls ({@code status/pause/resume/cancel/speed}).
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
                        .executes(Map2mcCommands::census))));
    }

    /**
     * Counts non-air blocks in a cube around the command source and reports a per-block-type
     * breakdown plus the Y range. Used to verify a build headlessly (total matches the expected
     * count; Y range matches the structure's extent) without launching the GUI.
     */
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
