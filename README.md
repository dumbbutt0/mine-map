# map2mc — MapToMinecraft

A Fabric mod (Minecraft **1.21.11**, Java 21) that assembles landmark schematics and lays them down
as **cinematic, bottom-up timelapses** — with an auto-flying "director" camera that tracks the
construction front. The engine behind "is it Minecraft or real life?" landmark-build shorts.

## What it does
- **Builds** Sponge `.schem` (v2 **and** v3) landmarks block-by-block, **bottom-up**, at a
  controllable pace — `/map2mc hero <file>`, duration-driven pacing (`/map2mc pace 120`).
- **Auto-films** the build: a frontier-tracking orbit whose framing tracks the *current* built
  extent (tight on the base, widening as it rises), in a clean spectator camera — `/map2mc film`.
  From a player it flies live (record in OBS/ReplayMod); from console it logs the pose sequence.
- **Placement tooling**: relative/local `~`/`^` coords, center/corner anchoring, a particle
  `preview` of where the build will land, and `undo`.
- **Exports** ReplayMod orbit keyframes (`/map2mc orbit`).

The mod owns block placement, ordering, pacing, and the in-game camera. Shaders (Iris) and screen
recording are the user's side — see [RECORDING.md](RECORDING.md) for the native-client + Iris +
ReplayMod runbook.

## Build & run
Requires **JDK 21** (the Gradle wrapper bootstraps Gradle 9). `JAVA_HOME` must point at a JDK 21.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build        # → build/libs/map2mc-<version>.jar
./gradlew runClient    # dev client
./gradlew test         # unit tests
```

**Auto-install to your mods folder** (so you never copy the jar by hand): set `map2mcModsDir` in
`~/.gradle/gradle.properties` (or the `MAP2MC_MODS_DIR` env var) to your Minecraft `mods/` folder.
Then `./gradlew build` — or the faster `./gradlew deployMod` (no tests) — copies the remapped jar
there automatically. If unset, the deploy step simply skips. (On WSL this writes to the Windows
`mods/` under `/mnt/c/...`; a WSL→Windows symlink does **not** work — the launcher can't follow it.)

See **[CLAUDE.md](CLAUDE.md)** for the architecture, the `.schem` format notes, the full command
list, and the verified toolchain (Fabric 1.21.11 / Loom 1.17 / Yarn / Gradle 9).

## License
MIT — see [LICENSE](LICENSE). Imported third-party schematics are user-supplied inputs and are **not**
bundled here; featuring a downloaded build in a published video is the user's responsibility re: the
creator's license.
