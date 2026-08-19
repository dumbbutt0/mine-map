package com.map2mc.gametest;

import com.map2mc.Map2mc;
import com.map2mc.schem.SchemPaths;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Headless gate for {@code /map2mc probe}: dispatch the REAL command (registration + pipeline) for
 * both committed fixtures against the live gametest world, then assert the JSON scorecard landed at
 * {@code <gameDir>/map2mc/probes/<name>.json} with the documented schema and the fixture ground
 * truth (5×5×5, 62 non-air). Terrain-dependent numbers (site score, guardrail magnitudes) are NOT
 * pinned — the gametest world's terrain isn't ours to freeze — but every schema key must be present
 * and the file contents are logged in full, so the run itself emits the scorecards.
 */
public final class ProbeGameTest {

    /** Every key the fixed schema promises (SPEC §8 consumers read these). */
    private static final String[] SCHEMA_KEYS = {
            "\"schemaVersion\"", "\"schem\"", "\"name\"", "\"width\"", "\"height\"", "\"length\"",
            "\"nonAir\"", "\"footprintAspect\"", "\"placement\"", "\"anchor\"", "\"rotation\"",
            "\"baseY\"", "\"bounds\"", "\"site\"", "\"slope\"", "\"waterOrVoid\"", "\"desert\"",
            "\"bridgeDepth\"", "\"biome\"", "\"biomeBonus\"", "\"total\"", "\"guardrails\"",
            "\"clip\"", "\"float\"", "\"halfBuried\"", "\"underwater\"", "\"bridgeCapExceeded\"",
            "\"flag\"", "\"magnitude\"", "\"foundation\"", "\"fillCells\"", "\"clearCells\""};

    @GameTest
    public void probeFixtureV2(TestContext context) throws Exception {
        probeFixture(context, "fixture_v2.schem", "fixture_v2.json");
    }

    @GameTest
    public void probeFixtureV3(TestContext context) throws Exception {
        probeFixture(context, "fixture_v3.schem", "fixture_v3.json");
    }

    private static void probeFixture(TestContext context, String schem, String json) throws Exception {
        // The gametest game dir is fresh — provision the fixture where the command resolves paths.
        Files.createDirectories(SchemPaths.schematicsDir());
        try (InputStream in = ProbeGameTest.class.getResourceAsStream("/fixtures/" + schem)) {
            Files.copy(in, SchemPaths.schematicsDir().resolve(schem), StandardCopyOption.REPLACE_EXISTING);
        }

        BlockPos at = context.getAbsolutePos(new BlockPos(0, 1, 0));
        ServerCommandSource source = context.getWorld().getServer().getCommandSource()
                .withWorld(context.getWorld()).withSilent();
        int result = context.getWorld().getServer().getCommandManager().getDispatcher().execute(
                "map2mc probe " + schem + " " + at.getX() + " " + at.getY() + " " + at.getZ(),
                source);
        context.assertTrue(result == 1, "probe command failed for " + schem);

        Path out = SchemPaths.probeDir().resolve(json);
        context.assertTrue(Files.exists(out), "scorecard not written: " + out);
        String body = Files.readString(out);
        for (String key : SCHEMA_KEYS) {
            context.assertTrue(body.contains(key), "scorecard missing " + key + " in " + json);
        }
        // Fixture ground truth (CLAUDE.md): 5×5×5 mini-tower, 62 non-air blocks, square footprint.
        context.assertTrue(body.contains("\"width\": 5, \"height\": 5, \"length\": 5, \"nonAir\": 62"),
                "fixture features wrong in " + json);
        context.assertTrue(body.contains("\"footprintAspect\": 1.000"), "aspect wrong in " + json);
        // Emit the scorecard into the gate's log — the run itself shows the artifact.
        Map2mc.LOGGER.info("[map2mc][gametest] {} scorecard:\n{}", schem, body);
        context.complete();
    }
}
