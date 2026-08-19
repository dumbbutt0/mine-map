package com.map2mc.probe;

import com.map2mc.place.Bounds;
import com.map2mc.schem.SchematicData;
import com.map2mc.schem.SchematicReader;
import com.map2mc.terrain.FoundationGenerator;
import com.map2mc.terrain.TerrainSample;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe scorecard, end to end against the committed fixtures: schem features come from the real
 * {@link SchematicReader} (NBT needs no registry bootstrap), terrain/guardrails from synthetic
 * {@link TerrainSample} grids, and the JSON is asserted as a full golden string so the schema is a
 * tested contract (SPEC §8 reads these fields).
 */
class ProbeReportTest {

    /** Fixture ground truth (CLAUDE.md): 5×5×5, 62 non-air blocks. */
    private static final int FIXTURE_DIM = 5;
    private static final int FIXTURE_NON_AIR = 62;

    private static SchematicData readFixture(String name) throws Exception {
        return SchematicReader.read(Path.of(
                ProbeReportTest.class.getResource("/fixtures/" + name).toURI()));
    }

    private static int nonAir(SchematicData d) {
        int n = 0;
        for (int id : d.blockIds()) {
            if (!"minecraft:air".equals(d.palette()[id])) {
                n++;
            }
        }
        return n;
    }

    /** All-flat footprint: every column's surface at {@code y}, no water. */
    private static TerrainSample flat(int y, int columns) {
        int[] surf = new int[columns];
        Arrays.fill(surf, y);
        return TerrainSample.of(surf, new boolean[columns]);
    }

    @Test
    void fixturesHaveTheDocumentedFeatures() throws Exception {
        for (String name : new String[]{"fixture_v2.schem", "fixture_v3.schem"}) {
            SchematicData d = readFixture(name);
            assertEquals(FIXTURE_DIM, d.width(), name + " width");
            assertEquals(FIXTURE_DIM, d.height(), name + " height");
            assertEquals(FIXTURE_DIM, d.length(), name + " length");
            assertEquals(FIXTURE_NON_AIR, nonAir(d), name + " non-air count");
        }
        assertEquals(2, readFixture("fixture_v2.schem").version());
        assertEquals(3, readFixture("fixture_v3.schem").version());
    }

    @Test
    void flatGreenSiteIsCleanAndRendersTheGoldenJson() throws Exception {
        SchematicData d = readFixture("fixture_v2.schem");
        TerrainSample s = flat(64, FIXTURE_DIM * FIXTURE_DIM);
        Bounds sited = new Bounds(-2, 64, -2, 2, 68, 2);
        ProbeReport r = ProbeReport.of("fixture_v2.schem",
                d.width(), d.height(), d.length(), nonAir(d),
                0, 64, 0, 0, sited, s, 64,
                false, "minecraft:plains", 24, List.of());

        // On flat ground at grade, no guardrail may fire (the fable.md sanity bar).
        ProbeReport.Guardrails g = r.guardrails();
        assertFalse(g.clip());
        assertFalse(g.floats());
        assertFalse(g.halfBuried());
        assertFalse(g.underwater());
        assertFalse(g.bridgeCapExceeded());
        assertEquals(115.0, r.total(), 1e-9, "100 base + 15 plains bonus");

        String expected = """
                {
                  "schemaVersion": 1,
                  "schem": { "name": "fixture_v2.schem", "width": 5, "height": 5, "length": 5, "nonAir": 62, "footprintAspect": 1.000 },
                  "placement": { "anchor": [0, 64, 0], "rotation": 0, "baseY": 64, "bounds": { "min": [-2, 64, -2], "max": [2, 68, 2] } },
                  "site": { "slope": 0, "waterOrVoid": false, "desert": false, "bridgeDepth": 0, "biome": "minecraft:plains", "biomeBonus": 15.0, "total": 115.0 },
                  "guardrails": {
                    "clip": { "flag": false, "magnitude": 0 },
                    "float": { "flag": false, "magnitude": 0 },
                    "halfBuried": { "flag": false, "magnitude": 0.000 },
                    "underwater": { "flag": false, "magnitude": 0 },
                    "bridgeCapExceeded": { "flag": false, "magnitude": 0 }
                  },
                  "foundation": { "fillCells": 0, "clearCells": 0 }
                }
                """;
        assertEquals(expected, ProbeExporter.render(r));
    }

    @Test
    void hostileTerrainFiresEveryGuardrailWithItsMagnitude() {
        // Columns at 40/64/70, one water column; base at the median (64), build 5 tall, cap 20.
        TerrainSample s = TerrainSample.of(new int[]{40, 64, 70}, new boolean[]{false, true, false});
        ProbeReport.Guardrails g = ProbeReport.Guardrails.of(s, 64, 5, 20);
        assertTrue(g.clip());
        assertEquals(6, g.clipBlocks(), "70 - 64 above the base");
        assertTrue(g.floats());
        assertEquals(24, g.floatBlocks(), "64 - 40 gap under the lowest column");
        assertTrue(g.halfBuried(), "6 above base >= ceil(5/2)");
        assertEquals(6 / 5.0, g.buriedFrac(), 1e-9);
        assertTrue(g.underwater());
        assertEquals(1, g.waterColumns());
        assertTrue(g.bridgeCapExceeded());
        assertEquals(4, g.bridgeOverCap(), "24 needed vs cap 20");
    }

    @Test
    void foundationPlanCellsAreSplitIntoFillAndClear() throws Exception {
        SchematicData d = readFixture("fixture_v3.schem");
        // One low column bridged (3 fill) + one bushy column cleared (2 clear), via the real planner:
        // 1×2 region, base 64, solid {61, 64}, veg {61, 66}, i.e. a dip and an overhanging bush.
        List<FoundationGenerator.Cell> plan = FoundationGenerator.plan(
                new Bounds(0, 60, 0, 0, 70, 1), 64, new int[]{61, 64}, new int[]{61, 66}, 24);
        TerrainSample s = TerrainSample.of(new int[]{61, 64}, new boolean[2]);
        ProbeReport r = ProbeReport.of("fixture_v3.schem",
                d.width(), d.height(), d.length(), nonAir(d),
                0, 64, 0, 90, new Bounds(0, 64, 0, 4, 68, 4), s, 64,
                false, "minecraft:forest", 24, plan);
        assertEquals(3, r.foundationFillCells(), "bridge 61..63 in the dip column");
        assertEquals(2, r.foundationClearCells(), "clear 64..65 under the veg top");
        assertTrue(r.guardrails().floats());
        assertEquals(3, r.bridgeDepth());
    }
}
