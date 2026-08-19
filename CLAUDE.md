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
  - **Terrain-aware foundation** (`com.map2mc.terrain`, in NORMAL non-superflat worlds): so the build
    sits in real generated scenery instead of floating on flatland. `submitHero` samples the LIVE ground
    under the footprint (`TerrainSampler` — force-gen the footprint chunks, then `getTopY(MOTION_BLOCKING_NO_LEAVES)`
    + biome/fluid per column → `TerrainSample`: min/median/max/slope + water/void flag), sites the build
    base at the **median grade**, and generates a foundation (`FoundationGenerator`, pure: bridge DOWN
    where ground < base, clear terrain AND vegetation/leaves ABOVE up to WORLD_SURFACE) over a **CIRCULAR
    plaza** (the sampler squares the region so an inscribed circle of radius `circumscribed footprint +
    foundationClearMargin` contains the build — a round cleared spot like the reference, not a rectangle).
    The foundation is submitted as a **separate FAST pre-job** that lands BEFORE the filmed structure (FIFO,
    high bpt) — so the trees/terrain are cleared *first* and the timelapse builds on a clean site (no canopy
    in the shot). The **structure is re-resolved at the grade Y and otherwise untouched** (resolver
    unchanged) and is the ONLY filmed build; `runFilm` tracks it via `BuildQueue.byId(heroId)` (the
    foundation sits ahead at the head). The area-profile handoff is the structure's own profile.
    Superflat / `foundationEnabled=false` → drops at the anchor as before. Steep/deep/water → warns +
    best-effort (capped at `foundationMaxBridge`). Knobs: `foundationEnabled`, `foundationSlopeThreshold`,
    `foundationMaxBridge`, `foundationStyle` (auto|stone|cobble|dirt).
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
- **Phase A — director camera (`com.map2mc.film`, `com.map2mc.client`):** the camera auto-flies a
  rising orbit that TRACKS the construction front, driven by build PROGRESS (placed/total) — so a
  finished-looking timelapse needs no manual keyframing (if the build hitches, the camera waits).
  - **Server director** (`film/DirectorCamera`, generalizes `OrbitFlight`): each tick computes the
    pose from `FilmDirector.directorPose(progress, frontier, params, …)` (pure, unit-tested),
    teleports the spectator player there (chunk loading), and publishes prev+cur pose to a
    `volatile film/DirectorState`. A console/headless `film` runs LOG-ONLY (no camera) — the
    headless gate's path. **Film end (`restorePlayer`):** default (`filmEndStaysSpectator=false`) RETURNS
    the player to their pre-film gamemode so they aren't stranded in spectator (which can't land):
    fall-safe modes (CREATIVE/SPECTATOR) are restored IN PLACE at the final pose (fly/descend down
    yourself); fall-damage-able modes (SURVIVAL/ADVENTURE) teleport to the pre-film ground spot first
    (+ clear fall distance) so restoring at altitude isn't the old "fell from a high place" death. Set
    `filmEndStaysSpectator=true` to instead leave them floating in spectator to finish the shot by hand.
    The aerial camera is capped below `filmCloudCeilingY` (≈180) so the start isn't lost in cloud. The
    overhead aerial cant is
    steep-pitch-pan geometry, NOT camera roll (the pose's roll is 0); ease `filmAerialPitchDeg`/
    `filmAerialTurns` to calm it.
  - **Client smoothing** (`client/mixin/CameraUpdateMixin` — the mod's FIRST mixin): overrides
    `Camera.update` at `@At("TAIL")` each render frame, lerping prev→cur by the partial-tick, so
    motion is smooth at render fps, not 20 Hz teleport judder. `client/Map2mcClient`
    (`ClientModInitializer`) hides the HUD while filming. (Camera-entity teleport keeps the
    overridden viewpoint near loaded chunks.)
  - **Frontier** = centroid (+bbox) of the last `Map2mcConfig.frontierWindow` (256) placed blocks,
    from `BuildJob.ordered()` + the cursor — no new per-block tracking; works for any ordering.
  - **Dynamic framing** (`FilmDirector.framingFor`): the orbit radius is sized to the build's
    CURRENT extent each tick (full footprint XZ, `baseY..currentTopY`), NOT the finished size — so the
    camera is **tight on the small base (~r86 for the Eiffel) and widens to ~r138 as it rises**,
    instead of circling a speck far out (the original r≈141 problem). The pre-first-block aim is the
    build's `centerBase()` (base, low Y), so the monotonic frontier-Y clamp doesn't pin framing at
    mid-height. A `/map2mc film … <radius>` arg pins it. Client raises view distance to
    `Map2mcConfig.filmViewDistance` while filming (shader fog is the user's Iris setting — RECORDING.md §E).
  - **Geometry reuse:** `Orbit.poseAt` was generalized into `Orbit.lookAt()` + `Orbit.poseAtAiming()`
    so the camera orbits the build centre while AIMING at the frontier — ONE geometry source; the
    existing `OrbitTest` is unchanged (poseAt delegates).
  - **V2-1 aspect-aware vertical + hero framing** (`FilmDirector.framingForAspect`, the live film path):
    the camera distance is sized so the build's **current** extent fills a **target aspect** frame
    (default **9:16**), and the camera sits LOW with the aim HIGH so the structure **looms (looks up)**.
    `radius = max(dV, dW)·margin` where `dV = (span/2 + headroom·span)/tan(vFov/2)` fits the HEIGHT and
    `dW = horizontalRadius/tan(hFov/2)` (`tan(hFov/2)=(W/H)·tan(vFov/2)`) fits the rotated WIDTH — so a
    9:16 tower frames by height (Eiffel ~r120 early → ~r225 full), a 16:9 wide build by width. The mod
    **can't resize the game window**, so aspect/FOV are **config** (`filmAspectW/H`, `filmBaseFovDeg`,
    `filmFrameMargin`, `filmHeadroomFrac`, `filmHeroCamFrac/AimFrac`) the user matches when recording
    (portrait window / ReplayMod 1080×1920). Framing tracks the **true current top** (`Frontier.bbox().maxY()`,
    not the lagging centroid) so the full height stays in frame. `framingFor`/`directorPose` (the V1
    frontier orbit) are kept for V2-3's `--orbit-only` and the shot library.
  - **Aerial establishing shot (low-height phase)** (`FilmDirector.aerialPose`): a wide-based build seen
    by the rising orbit while it has footprint but little height is a distant strip on grass (~half the
    runtime is dead). So `DirectorCamera.tickFilm` runs **two phases by build HEIGHT** (not progress —
    the base eats ~50% of blocks at ~10% height): while `currentHeight < filmAerialUntilHeightFrac·finalHeight`
    a **high-oblique aerial shot looks DOWN at the footprint** (camera framed to the footprint width at
    `camRadius = dW·margin`, `camY = max(camRadius·tan(pitch), top+clearance)` so it's always above the
    build, drifting azimuth `filmAerialTurns`); then a one-time **CUT** (`f.lastPose=null` → `prev==cur`,
    no lerp) hands off to the unchanged V2-1 rising orbit. Knobs: `filmAerialEnabled` (false = pure V2-1),
    `filmAerialPitchDeg` (50), `filmAerialMargin`, `filmAerialTurns`.
    This is an early hand-wiring of V2-2/V2-3's `establishing`+`rising_orbit` shots — fold in when they land.
  - **Handoff = area-profile transition AND min-height** (`film/AreaProfile`, the early slice of the V2-2
    analyzer): the cut fires once BOTH (a) the build has NARROWED past its wide base — the precomputed
    PROGRESS where the schematic's per-layer block count drops below `filmTransitionFrac`×max (so we
    never cut mid-floor; `−1` = no clear transition → height alone) AND (b) it has risen to
    `filmAerialUntilHeightFrac·finalHeight` ("something to look UP at"). For the thin-foundation Eiffel
    the height drives it (cut at progress ~0.41, legs emerged) with the structural check non-binding;
    for a thick base the structural check holds the overhead until the floor is done. (An earlier
    motion-based "verticality" detector was tried and reverted — the Eiffel's legs are simultaneously
    climbing AND horizontally spread, so front-motion can't separate them; the offline area profile can.)
  - `/map2mc film <path> [pos] [rotation] [radius] [duration]` (+ `film stop`).

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

## Commands (all under `/map2mc`; server-side except the `film` camera, which also drives the client)
`ping` · `testbuild <height> [shape pyramid|tower] [bpt]` · `status` · `pause` · `resume` ·
`cancel` · `speed <bpt>` · `census <radius>` (headless diagnostic) ·
`hero <path> [pos] [rotation 0|90|180|270] [corner|center]` (no args ⇒ in front of you, facing you) ·
`preview <path> [pos] [rotation] [corner|center]` · `preview clear` · `undo` · `clear <radius>` ·
`dumpschem <path>` (writes a report that diffs against `make_test_schem.py --verify`) ·
`pace <120|2min|30s|instant>` / `pace bpt <n>` (next build's pacing; default = duration 120s) ·
`orbit [radius] [duration] [keyframes] [fly]` / `orbit stop` (write a ReplayMod keyframe table around
the last build; optional mod-driven spectator fly) ·
`film <path> [pos] [rotation] [radius] [duration]` / `film stop` (build it AND auto-fly the
frontier-tracking director camera; from a player = live fly, from console = log-only). Every film
arg accepts `~`: `pos` → `~`/`^` (vanilla); `rotation` → `~` (face you) or 0/90/180/270; `radius` →
`~` (dynamic) or a number; `duration` → `~` (current pace) or seconds. (Trailing args are parsed
from a greedy string because Brigadier int/word args can't read a bare `~`.)
`record <path> [pos] [rotation] [duration]` / `record stop` (build it with the cinematic director camera
engaged AND auto-capture to a `.mcpr` via the external **ServerReplay** mod — command-dispatch bridge in
`com.map2mc.recording`, no compile-time dep, graceful no-op if absent; the site-prep is PACED
(`recordSitePrepSeconds`) so the strip + a lush biome-aware **plaza** (`com.map2mc.terrain.PlazaDecorator`
/ `PlazaPalette`: lawn + paths + trees + flowers over the cleared disc minus the footprint) are visible
in the timelapse; `.mcpr` + orbit table saved under `recordOutputDir` (map2mc points ServerReplay's
`chunk_recording_path` there via `ReplayBridge.applyOutputDir`)). ·
`scout <path> [radius]` (sample a grid of nearby sites via `TerrainSampler`, rank with the pure
`com.map2mc.terrain.SiteScore` — flatness/no-water/green-biome/shallow-bridging — and record-build at the
best, so builds never look plopped). See RECORDING.md §H.
`probe <path> [pos] [rotation]` (**side-effect-free** siting scorecard — SPEC §8's structured-JSON
channel, `com.map2mc.probe`): resolve the placement, sample the terrain, and write
`<gameDir>/map2mc/probes/<name>.json` (fixed schema, documented in `ProbeExporter`): schem features
(dims / non-air / footprint aspect), the `SiteScore` components each as its own field, guardrails
(`clip`/`float`/`halfBuried`/`underwater`/`bridgeCapExceeded`, each flag + magnitude, from
`TerrainSample` + `FoundationGenerator.plan` fill/clear volumes). Builds NOTHING (only samples
chunks, like `scout`); one summary line in chat/log.
`pos` accepts vanilla `~`/`^` coords. Schematic paths resolve under `<gameDir>/map2mc/schematics/`.
`get` / `set <key> <value>` — live-tune the `Map2mcConfig` knobs while dialing in the framing (no
rebuild): `get` lists every knob + current value; `set` validates against a sane range, assigns, and
echoes the parsed value (out-of-range / bad input fails loudly). The V2-1 framing knobs —
`filmBaseFovDeg` (30–110, match your recording FOV first), `filmFrameMargin`, `filmHeadroomFrac`,
`filmHeroCamFrac`, `filmHeroAimFrac`, `filmAspectW/H` — are the ones to turn. **Runtime-only:** values
reset on relaunch, so once a look is dialed in, bake the numbers into the `Map2mcConfig` defaults (a
persisted config file is the cleaner long-term answer; defaults-bake-in is fine for now).

## Status: Phases 0–3 + Phase A complete and verified
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
./gradlew runGametest      # headless GameTest gate (gametest source set; wired via
                           # fabricApi.configureTests in build.gradle — tests live in
                           # src/gametest/, register through the fabric-gametest entrypoint)
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

## Offline vision scorer (`tools/vision/score.py`) — MEASUREMENT only, NOT in the mod
A standalone Python tool that scores a rendered take with LOCAL vision models and emits a JSON
scorecard. Geometry answers facts (the mod has those); this answers TASTE. It runs offline on finished
renders only — nothing here touches the mod, the camera, or any runtime loop. Closed-loop shot
selection is a LATER task and only if these numbers earn the user's trust.
```bash
python3 tools/vision/score.py --video take.mp4 --out scorecard.json [--fps 1.5]
python3 tools/vision/score.py --video take.mp4 --style ref_frames/ --out scorecard.json  # + style-match
```
- **aesthetic** per frame: LAION aesthetic head on a CLIP ViT-L/14 embedding (~1–10). **style_match**:
  cosine similarity to the `--style` reference frames (the target look; the USER supplies them — bundle
  no copyrighted refs). **dead_color**: a cheap edge/colour heuristic flagging "empty" frames.
- Backbone via `transformers` (CLIP-L/14); aesthetic head auto-downloaded to `~/.cache/map2mc-vision/`.
  GPU if usable, else CPU (the dev box fell back to CPU — old CUDA driver). Degrades gracefully: if the
  models can't load it runs the heuristic and lists missing scores in `summary.scores_missing` (never
  crashes). No paid APIs. Deps in `tools/vision/requirements.txt` (all already present here).
- **Scorecard:** `{ video, model_versions, params, frames:[{t,aesthetic,style_match,edge_density,flags}],
  segments:[{t_start,t_end,aesthetic,style_match,dead_frac,flags}], summary:{mean_aesthetic,
  mean_style_match, worst_segments, dead_spans, off_style_spans, scores_missing} }`.
- **Trust caveat (measured, not assumed):** on the known dead-first-half take the aesthetic correctly
  orders dead < hero (4.57 vs 5.15, monotonic per-segment) but the gap is MODEST, and the edge/colour
  dead-heuristic does NOT separate grass-texture "dead" frames from the tower (similar edge density).
  Treat the aesthetic as a weak directional signal pending the user's own eyeball check; don't build a
  feedback loop on it yet.

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
