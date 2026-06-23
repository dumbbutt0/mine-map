package com.map2mc.schem;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves user-supplied schematic paths under {@code <gameDir>/map2mc/schematics/}, rejecting any
 * path that escapes that directory (e.g. via {@code ..}). Also exposes the dump output directory.
 */
public final class SchemPaths {

    private SchemPaths() {
    }

    public static Path schematicsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("map2mc").resolve("schematics");
    }

    public static Path dumpDir() {
        return FabricLoader.getInstance().getGameDir().resolve("map2mc").resolve("dump");
    }

    /** Resolve {@code name} under the schematics dir; throw if it escapes the directory. */
    public static Path resolveSchematic(String name) throws IOException {
        Path base = schematicsDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("schematic path escapes the schematics directory: " + name);
        }
        return resolved;
    }
}
