You are an expert Minecraft Java mod developer working in Claude Code. Target: Fabric,
Minecraft 1.21.x, JDK 21. Before scaffolding, verify the CURRENT matching versions of
Fabric Loom, Yarn mappings, and Fabric API for the latest 1.21.x patch — do not hardcode
versions from memory; they drift.

PROJECT: a mod "MapToMinecraft" (mod ID: map2mc). Its job is to assemble real-world-
inspired Minecraft builds and lay them down as CINEMATIC TIMELAPSES — the kind used for
short vertical "is it Minecraft or real life?" landmark-build videos.

=== WHAT ACTUALLY MAKES THESE VIDEOS WORK (read before designing) ===
A reference video (a ~60s vertical Eiffel Tower timelapse) was analyzed. The findings
drive this architecture:
1. The hero structure is a DETAILED, RECOGNIZABLE landmark (full iron lattice). This is a
   curated 3D asset / schematic — it is NOT and CANNOT be produced by extruding a map
   footprint. Footprint extrusion makes generic boxes; landmarks come from imported assets.
2. The build is laid down BOTTOM-UP in structural order (footings -> legs -> arches ->
   platform -> tapering tower -> spire), at a controlled pace. The construction *order* is
   what reads as "being built" rather than "a region filling in." This ordered, paced
   placement is THE core value this mod provides.
3. The beauty (lighting, atmosphere, reflections) comes from a SHADER PACK (Iris) and a
   slow camera orbit recorded with ReplayMod — NOT from this mod. The mod must not try to
   own shaders, cameras, or recording. It only places the right blocks in the right order
   at the right pace, and emits ReplayMod-friendly hooks.
4. Map-import (turning OSM/Google Maps areas into Minecraft) is useful ONLY for generating
   BACKGROUND districts around a hero. It is optional and secondary. Do not let it dominate
   the architecture.

=== GROUND RULES ===
- Build in PHASES. Each phase must end in something that COMPILES and RUNS, with a stated
  verification step. Do not advance until the current phase is verified.
- After each phase: `./gradlew build`, then `./gradlew runClient` smoke test, then git
  commit with a clear message. Stop and report exactly what you verified.
- Maintain a CLAUDE.md: architecture, the build-queue ordering logic, the .schem format
  notes, conventions, and how to run/test. Keep it current.
- Everything modular and well-commented. When a design decision is ambiguous, state your
  assumption inline rather than guessing silently.

=== NON-NEGOTIABLE TECHNICAL DECISIONS ===
- Build queue is the spine: a tick-driven placement engine that takes a set of
  (BlockPos, BlockState) placements and lays them down over time. It MUST support:
    * configurable blocks-per-tick (pacing for timelapse vs. instant)
    * pluggable ORDERING STRATEGIES, with these implemented:
        - ASCENDING_Y (bottom-up) — the cinematic DEFAULT
        - RADIAL_OUT (from a center point outward)
        - SUPPORTS_FIRST (lower/load-bearing before upper) — can start as ascending-Y
    * chunked/throttled placement so large builds never freeze the client
    * pause/resume and a progress readout
- Hero assets use `.schem` (Sponge Schematic, v2 AND v3 — both are common in the wild).
  Implement a READER first (import is the primary use), writer later. The file is gzipped
  big-endian NBT: gunzip -> read with the game's NbtIo -> detect v2 vs v3 by presence of a
  "Blocks" compound -> read the Palette (blockstate-string -> int) and the varint-packed
  block data, ordered index = (y * Length + z) * Width + x. Do NOT confuse with legacy
  `.schematic` (MCEdit, numeric IDs) or Litematica `.litematic`. Vanilla NBT structures are
  size-capped (~48^3) — not for large landmarks.
- ASSETS & LICENSING: the mod is license-agnostic about imported schematics — they are
  user-supplied inputs. NEVER bundle third-party builds in the repo. Document in CLAUDE.md:
  (a) where to get assets — Abfielder (has built-in convert-to-.schem + 3D viewer +
  block counts), Bloxelizer (exports any format), Planet Minecraft, minecraft-schematics;
  (b) that `.litematic`/legacy `.schematic` files must be converted to Sponge `.schem`
  first (via Abfielder, Bloxelizer, the Litematica mod, or WorldEdit //schem); and
  (c) that featuring a downloaded build in published video is the user's responsibility
  re: the creator's license. A `.litematic` reader is a Phase 6 stretch only.
- The mod does NOT manage shaders, cameras, or screen recording. It MAY emit pacing suited
  to ReplayMod and OPTIONALLY export suggested camera keyframes / an orbit path.
- Map-import (Phase 5, optional) MUST use a LOCAL EQUIRECTANGULAR projection centered on
  the bbox (lat ~111320 m/deg, lon ~111320*cos(centerLat) m/deg) — NOT Web Mercator.
  Building heights from OSM use this fallback chain: `height` tag -> `building:levels` *
  meters-per-level (default 3) -> default-by-building-type table. Cache all Overpass/DEM
  responses to disk; respect rate limits; configurable endpoints; no-key DEM default.
- Google Photorealistic 3D Tiles and mesh voxelization are OUT of the core (textured mesh
  voxelizes poorly for fine lattices, plus ToS concerns). Stub interface + CLAUDE.md note
  only; real work is a Phase 6 experiment.

=== PHASE PLAN ===
Phase 0 — Scaffold: Gradle + fabric.mod.json + main mod class + `/map2mc ping`.
  Gate: builds, runClient launches, command prints to chat. Commit.

Phase 1 — THE SPINE: the paced, ordered build queue (no external data yet).
  Implement the tick-driven BuildQueue with blocks-per-tick + the ordering strategies
  above. Test it by programmatically generating a simple tall test shape (e.g. a hollow
  tapering tower or stepped pyramid) and feeding it to the queue.
  Command: `/map2mc testbuild <height>` builds the test shape bottom-up, paced.
  Gate: the test structure visibly builds BOTTOM-UP over several seconds without freezing
  the client; changing blocks-per-tick changes the speed; ASCENDING_Y order is correct.
  Commit.

Phase 2 — HERO ASSET (the MVP that reproduces the reference video):
  SchematicReader supporting Sponge Schematic v2 AND v3 (parse per the format notes above;
  no heavy external dependency needed — the game's NBT classes plus varint decoding). Load
  a landmark schematic, place it at an anchor with rotation (0/90/180/270) and ground-
  snapping, feeding EVERY block through the Phase 1 queue with ASCENDING_Y ordering.
  Command: `/map2mc hero <path> [x y z] [rotation]`.
  TEST FIXTURE: do NOT depend on a downloaded build for the gate. A generator script
  (`make_test_schem.py`, provided) writes a self-made 5x5x5 Sponge v2/v3 `.schem` and can
  verify any `.schem` by parsing it back. Use its output (or port its tiny writer to a
  Java test helper) as a committed test resource — it's a self-made fixture, so no license
  issue. The Java reader must reproduce exactly what that script's reader prints (palette +
  bottom-up layers). Most real landmark files online are `.litematic`/legacy `.schematic`,
  so the user supplies a converted Sponge `.schem` for the actual demo recording.
  Gate: (a) the synthetic fixture round-trips through the reader and builds bottom-up,
  layer counts matching; (b) a user-supplied Eiffel Tower `.schem` builds bottom-up, paced,
  and is recognizable. A reference-style clip is recordable at this point. Commit.

Phase 3 — Cinematic helpers (no shader/camera ownership):
  Flat staging-area generator; an optional "first-block footing markers" intro beat like
  the reference opening; pacing presets (e.g. "30s", "60s", "instant") that compute
  blocks-per-tick from total block count and target duration; OPTIONAL export of a
  suggested ReplayMod orbit (keyframes around the build's bounding box).
  Gate: a build runs at a chosen target duration on a flat staging plot; orbit keyframes
  export and load sanely. Commit.

Phase 4 — Config + GUI (Cloth Config or YACL):
  blocks-per-tick / target duration, ordering strategy, staging options, default hero path,
  scale. Gate: GUI round-trips config; changing pacing changes build speed live. Commit.

Phase 5 — OPTIONAL surround module (map import — clearly marked optional):
  Overpass client (cached) + local equirectangular projection + footprint rasterizer +
  height-inference chain + no-key DEM terrain, ALL feeding the same build queue, composited
  AROUND a hero asset. Add a sane max-area cap with a clear error.
  Gate: a small real bbox builds a recognizable BACKGROUND district around a placed hero,
  grounded on real elevation, paced. Commit.

Phase 6 — Stretch experiments (documented, not core): `.schem` writer/export,
  `.litematic` reader/export, mesh/3D-Tiles voxelizer (with ToS caveats in CLAUDE.md).

=== DELIVERABLE FOR THIS TURN ===
Do Phase 0, Phase 1, and Phase 2 only. Give me the full file tree, the key files, the
CLAUDE.md, and your verification results. Then stop — we'll iterate from there.

Keep code clean, modular, heavily commented. State assumptions inline when you make them.
