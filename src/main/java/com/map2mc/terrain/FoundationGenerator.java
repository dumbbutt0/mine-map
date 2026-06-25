package com.map2mc.terrain;

import com.map2mc.build.Placement;
import com.map2mc.place.Bounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure foundation geometry: GROUND a fixed structure on uneven terrain WITHOUT warping the structure.
 * Given the footprint, the chosen base Y (the structure's lowest layer sits here), and the per-column
 * surface Y, it plans cells that:
 * <ul>
 *   <li><b>bridge DOWN</b>: where the ground is below the base, fill {@code surfaceY .. baseY-1} so the
 *       structure rests on solid ground instead of floating (capped at {@code maxBridge} deep);</li>
 *   <li><b>clear ABOVE</b>: where the ground rises above the base, clear {@code baseY .. surfaceY-1}
 *       — WITHIN THE FOOTPRINT ONLY — so the structure isn't buried (the structure later overwrites
 *       the cells it actually occupies);</li>
 *   <li>flat ground needs neither — the structure rests on the natural surface.</li>
 * </ul>
 * {@link #plan} is pure (BlockPos + a fill/clear flag), unit-tested with no Minecraft bootstrap;
 * {@link #generate} attaches the block states. The blocks go through the normal paced build queue
 * combined with the structure (ascending-Y → the foundation rises first).
 */
public final class FoundationGenerator {

    private FoundationGenerator() {
    }

    /** A planned foundation block: {@code fill}=true → material; false → cleared to air. */
    public record Cell(BlockPos pos, boolean fill) {
    }

    /** Pure geometry: which cells to fill (bridge down) or clear (above), per the surface grid. */
    public static List<Cell> plan(Bounds footprint, int baseY, int[] surfaceY, int maxBridge) {
        List<Cell> out = new ArrayList<>();
        int sizeX = footprint.sizeX();
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                int surf = surfaceY[(z - footprint.minZ()) * sizeX + (x - footprint.minX())];
                int fillBottom = Math.max(surf, baseY - maxBridge); // bridge DOWN, capped
                for (int y = fillBottom; y < baseY; y++) {
                    out.add(new Cell(new BlockPos(x, y, z), true));
                }
                for (int y = baseY; y < surf; y++) {                // clear ABOVE, footprint only
                    out.add(new Cell(new BlockPos(x, y, z), false));
                }
            }
        }
        return out;
    }

    /** Production: {@link #plan} mapped to {@link Placement}s ({@code material} for fill, air for clear). */
    public static List<Placement> generate(Bounds footprint, int baseY, int[] surfaceY,
                                           BlockState material, int maxBridge) {
        BlockState air = Blocks.AIR.getDefaultState();
        List<Placement> out = new ArrayList<>();
        for (Cell c : plan(footprint, baseY, surfaceY, maxBridge)) {
            out.add(new Placement(c.pos(), c.fill() ? material : air));
        }
        return out;
    }
}
