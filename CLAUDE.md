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
- **Phase 2.5 — placement targeting (`com.map2mc.place`, `preview`, `undo`):** make placement
  visible and cheap to undo. ONE shared `PlacementResolver` turns (anchor + `AnchorMode` + rotation)
  into the exact world positions; `preview` and `hero` BOTH call it so an outline always matches the
  build (`SchematicPlacer` now just delegates to it — no second copy of the transform).
  - **Anchor modes** (`AnchorMode`): `CORNER` = the given point is the build's min corner (the old
    behavior); `CENTER_GROUND` = the point is the build's horizontal **center** and, with ground-snap,
    its vertical **base**. `CENTER_GROUND` is the default for both `hero`/`preview` and the no-coord case.
  - **Coords** use the vanilla `Vec3ArgumentType.vec3(false)`, so `~ ~ ~` (relative) and `^ ^ ^N`
    (local) resolve against the command source for free. No-coord `hero <file>` drops the build a
    configurable distance in front of the player (`Map2mcConfig.frontDistance`, ground-snapped),
    rotated to **face the player** (`LookMath.facingRotation`; convention: a schem's `+Z` face is its
    "front").
  - **Preview** (`com.map2mc.preview.PreviewManager`): a server-spawned `END_ROD` particle outline of
    the build's `Bounds`, re-emitted each few ticks and auto-clearing (`Map2mcConfig.previewDurationTicks`,
    ~30s) or via `/map2mc preview clear`. No blocks are placed.
  - **Undo** (`com.map2mc.undo.{UndoLog,UndoManager}`): every build captures each overwritten block's
    PRIOR state in `ChunkPlacer.place`; on completion/cancel the log is pushed to a small per-world
    stack (**depth `UndoManager.MAX_DEPTH` = 5**). `/map2mc undo` cancels any active build then restores
    the most recent log (replayed through `ChunkPlacer`). `/map2mc clear <radius>` is an undoable
    region wipe.
- **Phase 3 — cinematic (`com.map2mc.pace`, `com.map2mc.orbit`):** turn the paced engine into a
  recording tool.
  - **Duration-driven pacing** (`pace/{Pacing,PaceSetting}`): the user sets a target *duration*; the
    engine derives `blocksPerTick = ceil(totalNonAir / (durationSeconds × 20))`. DURATION is the
    **default** (`Map2mcConfig.defaultDurationSeconds` = 120s); MANUAL (`pace bpt <n>`) and INSTANT
    remain. `hero` resolves the bpt from `PaceSetting` at submit; `/map2mc speed <n>` still
    live-retunes a running build.
  - **Orbit export** (`orbit/{Orbit,OrbitKeyframe,OrbitExporter,OrbitFlight,LastBuild}`): from the
    last hero build's `Bounds` (`LastBuild`, per world), compute a slow circular camera path — N
    keyframes on a radius around the centre, always aimed at it, rising gently, with build-time mapped
    onto a short render time. **DECISION:** emit a *documented keyframe table* under
    `<gameDir>/map2mc/orbits/` (columns = ReplayMod Position-keyframe fields) rather than writing
    ReplayMod's undocumented, version-fragile `timelines.json`; plus an optional mod-driven spectator
    `fly` that samples the SAME `Orbit.poseAt`, so what you film matches the table. `Orbit.poseAt` is
    the single geometry source (unit-tested: on-circle + look-at-centre).

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
| Gradle | 9.6.0 (wrapper) — Loom 1.17 requires Gradle **9**, not 8 (its plugin marker wants api-version 9.5.0) |
| Java | 21 (Temurin) |

Mappings: **Yarn** (per the brief). All Minecraft API names in this repo are Yarn names.

## Verified Yarn API signatures (1.21.11 / Yarn build.6 — verified via javap of the named MC jar)
- Commands: `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT`; literals/args
  via `net.minecraft.server.command.CommandManager.literal/argument`.
- Tick hook: `net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK`;
  shutdown: `ServerLifecycleEvents.SERVER_STOPPING`.
- Chunk forceload: `ServerWorld.setChunkForced(int x, int z, boolean)`; `new ChunkPos(BlockPos)`,
  `new ChunkPos(long)`, `ChunkPos.toLong()`.
- **NBT read: `NbtIo.readCompressed(Path, NbtSizeTracker)`; tracker = `NbtSizeTracker.ofUnlimitedBytes()`**
  (NOT `ofUnlimited()`).
- **NBT getters return `Optional` in 1.21.11.** Use the defaulted/`*OrEmpty` variants:
  `getShort(k, short)`, `getInt(k, int)` → primitive; `getByteArray(k)`/`getIntArray(k)` →
  `Optional<...>`; `getCompoundOrEmpty(k)` → `NbtCompound`; `getKeys()` → `Set<String>`;
  `contains(k)` → boolean.
- Block resolve (manual, no command registry-wrapper): `Registries.BLOCK.get(Identifier.of(id))`
  (DefaultedRegistry → returns air for unknown), `Block.getStateManager().getProperty(name)`,
  `Property.parse(String)` → `Optional<T>`, `BlockState.with(Property<T>, T)`,
  `BlockState.rotate(BlockRotation)`, `BlockState.isAir()`.
- Rotations: `net.minecraft.util.BlockRotation.{NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90}`.
- `Identifier.of(String)` (not `new Identifier(...)`); `BlockPos.ofFloored(Vec3d)`.
- **Phase 2.5/3 (verified via javap):** position args `net.minecraft.command.argument.Vec3ArgumentType`
  — `vec3(boolean centerIntegers)` as the arg, `Vec3ArgumentType.getVec3(ctx, name)` → `Vec3d`
  (resolves `~`/`^` against the source). Source pose: `ServerCommandSource.getPosition()` → `Vec3d`,
  `getRotation()` → `Vec2f` of **(x=pitch, y=yaw)**. Particles:
  `ServerWorld.spawnParticles(T, boolean force, boolean important, double x,y,z, int count, double dx,dy,dz, double speed)`
  (forced/long-distance overload; `ParticleTypes.END_ROD`). `BlockPos.asLong()`/`fromLong(long)` for
  compact undo keys. Player camera move (Phase 3 fly):
  `ServerPlayerEntity.teleport(ServerWorld, double x,y,z, Set<PositionFlag>, float yaw, float pitch, boolean)`
  with `PositionFlag.{X,Y,Z,Y_ROT,X_ROT}`.

## Commands (all under `/map2mc`, server-side)
`ping` · `testbuild <height> [shape pyramid|tower] [bpt]` · `status` · `pause` · `resume` ·
`cancel` · `speed <bpt>` · `census <radius>` (headless diagnostic) ·
`hero <path> [pos] [rotation 0|90|180|270] [corner|center]` (no args ⇒ in front of you, facing you) ·
`preview <path> [pos] [rotation] [corner|center]` · `preview clear` · `undo` · `clear <radius>` ·
`dumpschem <path>` (writes a report that diffs against `make_test_schem.py --verify`) ·
`pace <120|2min|30s|instant>` / `pace bpt <n>` (next build's pacing; default = duration 120s) ·
`orbit [radius] [duration] [keyframes] [fly]` / `orbit stop` (write a ReplayMod keyframe table around
the last build; optional mod-driven spectator fly).
`pos` accepts vanilla `~`/`^` coords. Schematic paths resolve under `<gameDir>/map2mc/schematics/`.

## Status: Phases 0–3 complete and verified
- P0: scaffold + ping (headless server smoke).
- P1: build queue + ordering; 8 unit tests + headless paced bottom-up pyramid (680 blocks).
- P2: `.schem` v2/v3 reader; `dumpschem` byte-identical to the Python oracle; `hero` builds the
  fixture (62 blocks) and rotates. 11 unit tests total.
- P2.5: shared `PlacementResolver` (corner/center anchor, rotation, ground-snap) + `~`/`^` coords +
  facing default + particle `preview` + `undo`. 27 unit tests. Headless smoke proved: hero `^ ^ ^8`
  placed 62 blocks (census 6786→6848), `undo` restored exactly 62 (→6786), `preview` box equals the
  hero placement region, no-coord hero placed in front facing the player, cancel+undo restored the
  partial. (The in-scope Eiffel `Copper_Eiffel_Tower.schem` = 86×222×85, **34,840 non-air blocks**.)
- P3: duration-driven pacing + orbit export. 38 unit tests. Headless gate: the Eiffel at
  `pace 120` built to completion in **116 s** (target 120 s; 15 b/t), manual override (`pace bpt`,
  `speed`) works, and `orbit` wrote a keyframe table (on-circle + look-at-centre, time-compressed).

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

> **Headless gotcha (cost real debugging time):** a dedicated `runServer` with no players online
> **pauses after 60 s** — vanilla `pause-when-empty-seconds` (default 60). That stops
> `END_SERVER_TICK`, so any build running >60 s appears to "freeze" mid-way (gametime stalls at
> ~1199). It is NOT a mod bug. For any headless run that must tick past a minute (e.g. the ~2-minute
> Eiffel), put **`pause-when-empty-seconds=0`** in `run/server.properties`. Drive the server through a
> FIFO and start `./gradlew runServer < fifo &` BEFORE opening the writer fd, or the pipe deadlocks.

**Recording a real cinematic timelapse** (native client + Iris shaders + ReplayMod orbit — the
three-independent-layers model): see [`RECORDING.md`](RECORDING.md). Build the plain
`build/libs/map2mc-<version>.jar` (not `-sources`/`-dev`) and run the recording client on a native
OS, not WSLg.

## Conventions
- Base package `com.map2mc`; one logger `Map2mc.LOGGER` (`org.slf4j`, name `map2mc`).
- **All block placement + the tick loop run on the server thread.** Heavy I/O (`.schem` parsing)
  happens once in the command handler, off the tick loop; only finished placement lists are queued.
- Everything modular and heavily commented; state assumptions inline.
- Encapsulate the `.schem` index formula in exactly one place and unit-test it.
- Likewise the placement transform lives in exactly one place (`place/PlacementResolver`); `preview`,
  `hero`, and the legacy `SchematicPlacer` all route through it so an outline can never disagree with
  the build. Its position math is pure (boolean occupancy, no `BlockState`) so it unit-tests with no
  Minecraft bootstrap.
- Commit after each phase's gate passes.
