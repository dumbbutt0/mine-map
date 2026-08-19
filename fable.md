# Fable capability test — `/map2mc probe` JSON scorecard

A self-contained, headless-verifiable task to judge how capable Fable 5 is on *this*
codebase. Chosen because it's the real next bottleneck (`SPEC.md` §8: the RL harness is
blocked on the mod emitting machine-readable JSON, not human chat feedback), it reuses
existing pure logic, and it never hits the non-headless render wall — so "pasted proof"
is natural and the output is real Phase-R progress, not throwaway.

## How to run
```bash
export JAVA_HOME=$HOME/.local/jdk-21
cd /home/dummy/clones/mine-map
claude            # then /model -> Fable 5, and paste the block below
```

## Paste this into Fable

> You are working in the `map2mc` Fabric Minecraft mod (MC 1.21.11, Java 21, Gradle 9/Loom). Before writing anything, orient yourself:
> - Read `SPEC.md` §8 and §10, and `CLAUDE.md` (architecture, conventions, phase status, headless/toolchain gotchas).
> - Read `src/main/java/com/map2mc/command/Map2mcCommands.java` (the command surface — see `scout` ~687, `census` ~966), and the `terrain/` package: `SiteScore.java`, `SiteScout.java`, `TerrainSampler.java`, `FoundationGenerator.java`.
> - Read `orbit/OrbitExporter.java` — match its JSON-writing style; do not add a JSON library.
>
> **Goal.** Add a `/map2mc probe <schem> [pos] [rotation]` command that samples terrain and computes placement **without building anything**, then writes a structured JSON scorecard to `<gameDir>/map2mc/probes/<name>.json` and logs a one-line summary. The JSON must contain, with a fixed documented schema:
> - **schem features**: width/height/length, non-air block count, footprint aspect ratio;
> - **SiteScore components**: slope, waterOrVoid, desert, bridgeDepth, biome, and total — each as its own field;
> - **guardrails** (derived from `TerrainSampler` + `FoundationGenerator`): `clip`, `float`, `halfBuried`, `underwater`, `bridgeCapExceeded` — each a boolean **plus** its magnitude.
> - Add a unit test (and/or GameTest) asserting the JSON's shape and values against the committed fixtures `src/test/resources/fixtures/fixture_v2.schem` and `fixture_v3.schem`.
>
> **Constraints — non-negotiable:**
> - Reuse the existing pure functions; do **not** re-derive terrain/score/guardrail math. `probe` must be side-effect-free except writing the JSON file (no block placement, no camera, no recording).
> - Follow repo conventions: single logger, pure-function style, no new dependencies, JSON hand-written in `OrbitExporter`'s idiom.
> - Only the 5×5×5 fixtures exist in the tree — **do not assume** any large user schem (e.g. the Eiffel one) is present.
> - Don't touch the camera/film/recording packages or anything requiring a native client.
>
> **Finish line (the run is done ONLY when all of these are pasted into this chat):**
> 1. `export JAVA_HOME=$HOME/.local/jdk-21 && ./gradlew test runGametest` — full output showing your new test **green**.
> 2. The **actual contents** of a generated `map2mc/probes/*.json` for both fixtures (run the command headlessly, or have the GameTest emit it).
> 3. A short diff/summary of every file you changed and why.
> — **or**, if blocked, stop after **40 assistant turns** and paste: what's done, the exact failure output, and your top two hypotheses. Never claim success without the pasted green run — a solution without pasted proof is unfinished.

## How to judge Fable from the result
- **Orientation** — did it read the terrain classes and *reuse* them, or reinvent the geometry? (Reinventing = weak.)
- **Convention-fit** — single logger, no new deps, JSON style matches `OrbitExporter`?
- **Honesty of proof** — real pasted green run + real JSON, or narrated success? (The tell.)
- **Guardrail correctness** — sane values on the fixtures (a 5×5×5 on flat ground shouldn't flag `underwater`)?
- **Scope discipline** — stayed out of camera/rendering, didn't chase the big-schem rabbit hole?

Clears it cleanly with pasted proof in well under 40 turns → strong signal. And the JSON it
produces is genuine M1 groundwork for the Phase-R reward channel.

## Gotchas (so a stall isn't mistaken for incapability)
- `JAVA_HOME` must point at JDK 21 every invocation; Loom 1.17 needs Gradle 9 (wrapper handles it).
- Headless server pauses after 60s with no players — set `pause-when-empty-seconds=0`; drive via FIFO with the reader started before the writer fd.
- `runClient` under WSLg is RAM-tight (`-Xmx2G`); prefer GameTests over the client for this task.
- Only tiny fixtures are committed; the large user schems are not in the tree.
