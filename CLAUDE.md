# map2mc — MapToMinecraft

A Fabric mod (Minecraft 1.21.x, Java 21) that assembles landmark builds and lays them down as
**cinematic, bottom-up timelapses** — the engine behind "is it Minecraft or real life?" landmark
shorts. The mod owns **block placement, ordering, and pacing only**. Shaders (Iris), camera, and
recording (ReplayMod) are deliberately **out of scope** and performed by the user in-game.

## What "recreate the videos" means here
The mod produces the *paced, ordered construction* (the part that reads as "being built"). The
user films the running build with a shader pack and a slow ReplayMod camera orbit to get the
final cinematic. The mod may later export suggested ReplayMod orbit keyframes (Phase 3+), but
never owns the camera or recording.

## Architecture (by phase)
- **Phase 0 — scaffold:** `Map2mc` (`ModInitializer`) + `command/Map2mcCommands` (`/map2mc ping`).
- **Phase 1 — the spine (`com.map2mc.build`):** a tick-driven `BuildQueue` that takes a set of
  `(BlockPos, BlockState)` placements and lays them down over time.
  - Pacing: `blocksPerTick` (× 20 TPS = blocks/sec). Amortized across ticks ⇒ never freezes.
  - Ordering (`build/ordering`): `OrderingStrategy` with `AscendingY` (bottom-up, **the cinematic
    default**), `RadialOut` (from a center outward), `SupportsFirst` (load-bearing first; MVP
    aliases AscendingY). Strategy is applied **once at submit time**; the tick loop never sorts.
  - `ChunkPlacer` does `setBlockState` on the **server thread** and force-loads each target chunk
    on first touch, releasing on completion/cancel/stop.
  - Controls: `/map2mc testbuild <height>`, `status`, `pause`, `resume`, `cancel`, `speed`.
- **Phase 2 — hero asset (`com.map2mc.schem`):** `SchematicReader` for Sponge `.schem` v2 **and**
  v3, fed block-by-block through the Phase 1 queue with `AscendingY`.
  - `/map2mc hero <path> [x y z] [rotation]`, `/map2mc dumpschem <path>`.

## .schem format notes (GROUND TRUTH — follow the fixtures, not generic Sponge docs)
GZIP-compressed big-endian NBT. **Detect by presence of a `Blocks` compound ⇒ v3, else v2.**
- **v2:** root compound NAMED `Schematic`; top-level `Version`(=2), `DataVersion`,
  `Width`/`Height`/`Length`(short), optional `Offset`(int[3]), `PaletteMax`, `Palette`
  (blockstate-string → int), `BlockData`(byte[]), `BlockEntities`(list), optional `Metadata`.
- **v3:** root compound UNNAMED (`""`) → one child `Schematic` → same headers + a `Blocks`
  compound = `{ Palette, Data(byte[]), BlockEntities }`. (Renames vs v2: data under `Blocks`,
  `BlockData`→`Data`, `PaletteMax` dropped.)
- Block data: one **LEB128 varint** per block (7 bits/byte, MSB = continue), in order
  `index = (y * Length + z) * Width + x` (x fastest, then z, then y). Width=X, Height=Y, Length=Z.
- Reader only (writer is out of scope). Reject legacy MCEdit `.schematic` (numeric IDs) and
  Litematica `.litematic` with a clear error.

The Python reference parser/generator `make_test_schem.py` (repo root) is the oracle: it prints
format/dims/dataVersion, the palette sorted by id, per-block counts, and a bottom-up ASCII layer
view. The Java reader's `/map2mc dumpschem` output must `diff` empty against
`python3 make_test_schem.py --verify <file>`. Fixtures: `src/test/resources/fixtures/fixture_v{2,3}.schem`
(a self-made 5×5×5 mini-tower — y0 polished_diorite base; y1–3 four waxed_exposed_copper corner
legs; y4 copper platform + one `oak_log[axis=y]` at center). They are our own creation — no license.

## Toolchain (pinned in `gradle.properties`; verified live 2026-06-23)
| Component | Version |
|---|---|
| Minecraft | 1.21.11 (latest stable 1.21.x; brief targets 1.21.x) |
| Fabric Loader | 0.19.3 |
| Yarn mappings | 1.21.11+build.6 |
| Fabric API | 0.141.4+1.21.11 |
| Fabric Loom | 1.17.12 |
| Gradle | 8.14.5 (wrapper) |
| Java | 21 (Temurin) |

Mappings: **Yarn** (per the brief). All Minecraft API names in this repo are Yarn names.

## Verified Yarn API signatures (record drift here as found)
- Commands: `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT`;
  literals via `net.minecraft.server.command.CommandManager.literal`.
- _(Phase 1)_ tick hook: `net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK`.
- _(Phase 2)_ NBT read: `net.minecraft.nbt.NbtIo.readCompressed(Path, NbtSizeTracker)` — confirm
  the `NbtSizeTracker` factory (`ofUnlimited()` vs `of(long)`) at implementation time.

## Build & run (this WSL2 box)
JDK 21 is at `~/.local/jdk-21`; Gradle is bootstrapped via the wrapper. Every invocation needs
`JAVA_HOME` pointed at the JDK:

```bash
export JAVA_HOME=$HOME/.local/jdk-21
cd /home/dummy/clones/mine-map

./gradlew build            # compile + assemble (Phase gate: must succeed)
./gradlew runClient        # launches under WSLg, capped at -Xmx2G (visual gates)
./gradlew runGametest      # headless logic gates (Phase 1+; added with the tests)
```

Verification is **headless-first**: GameTests + the `dumpschem`-vs-Python diff prove logic without
a GUI; `runClient` under WSLg (`-Xmx2G`, RAM is tight at ~7.7 GiB) is used only for the inherently
visual "visibly builds bottom-up" gates.

## Conventions
- Base package `com.map2mc`; one logger `Map2mc.LOGGER` (`org.slf4j`, name `map2mc`).
- **All block placement + the tick loop run on the server thread.** Heavy I/O (`.schem` parsing)
  happens once in the command handler, off the tick loop; only finished placement lists are queued.
- Everything modular and heavily commented; state assumptions inline.
- Encapsulate the `.schem` index formula in exactly one place and unit-test it.
- Commit after each phase's gate passes.
