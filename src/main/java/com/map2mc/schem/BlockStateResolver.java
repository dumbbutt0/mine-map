package com.map2mc.schem;

import com.map2mc.Map2mc;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/**
 * Turns palette blockstate strings (e.g. {@code minecraft:oak_log[axis=y]}) into real
 * {@link BlockState}s against the live registry. Done once per palette entry at placement time, so
 * a 12k-block landmark only parses each distinct block once.
 *
 * <p>Implemented manually (registry lookup + {@code StateManager} property parsing) rather than via
 * {@code BlockArgumentParser} — it needs only {@link Registries#BLOCK}, avoiding the command
 * registry-wrapper plumbing, and degrades gracefully (unknown property → keep the default value).
 */
public final class BlockStateResolver {

    private BlockStateResolver() {
    }

    /** Resolve a whole palette; null entries (gaps) stay null. */
    public static BlockState[] resolve(String[] palette) {
        BlockState[] out = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            out[i] = palette[i] == null ? null : parse(palette[i]);
        }
        return out;
    }

    public static BlockState parse(String blockstate) {
        int bracket = blockstate.indexOf('[');
        String id = (bracket < 0 ? blockstate : blockstate.substring(0, bracket)).trim();

        Block block = Registries.BLOCK.get(Identifier.of(id));
        BlockState state = block.getDefaultState();
        if (!id.equals("minecraft:air") && state.isAir()) {
            Map2mc.LOGGER.warn("[map2mc] unknown block '{}' in schematic palette — placing air", id);
        }

        if (bracket >= 0) {
            String props = blockstate.substring(bracket + 1, blockstate.lastIndexOf(']'));
            if (!props.isEmpty()) {
                for (String pair : props.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    Property<?> property = block.getStateManager().getProperty(name);
                    if (property != null) {
                        state = withProperty(state, property, value);
                    }
                }
            }
        }
        return state;
    }

    /** Generic helper so the wildcard property's value type is captured. */
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(v -> state.with(property, v)).orElse(state);
    }
}
