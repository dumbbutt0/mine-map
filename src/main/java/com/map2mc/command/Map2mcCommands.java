package com.map2mc.command;

import com.map2mc.Map2mc;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the {@code /map2mc} command tree.
 *
 * <p>Commands run on the server thread (Brigadier executes them there), which is also
 * where all block placement happens — so command handlers may talk to the build engine
 * directly without any cross-thread handoff. Phase 0 only wires {@code ping}; later
 * phases add {@code testbuild}, {@code hero}, {@code dumpschem}, and the run controls.
 */
public final class Map2mcCommands {

    private Map2mcCommands() {
    }

    /** Hook command registration into the Fabric lifecycle. Called once from the initializer. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal(Map2mc.MOD_ID)
                .then(literal("ping").executes(ctx -> {
                    final String msg = "map2mc pong (MC " + Map2mc.MC_VERSION + ")";
                    // Feedback goes to the player/console; the log line is the headless confirmation.
                    ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
                    Map2mc.LOGGER.info("[{}] {}", Map2mc.MOD_ID, msg);
                    return 1;
                })));
    }
}
