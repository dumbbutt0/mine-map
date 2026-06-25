# Camera scalability — stress-test findings & roadmap

**Question:** can the cinematic camera scale across different `.schems` (shapes) and styles, instead of
being tuned to one tall tower (the Eiffel) and one look (Donut's)?

**Method:** a headless probe (`src/test/java/com/map2mc/film/CameraScalabilityProbeTest.java`) runs the
*existing* shot-decision path — the same pure functions (`FilmDirector.framingForAspect` /
`aerialPose`, `AreaProfile.handoffProgress`) and the same aerial→orbit gate that
`DirectorCamera.tickFilm` uses — across four structure archetypes, and prints what the camera decides
for each. Re-run: `./gradlew test --tests "com.map2mc.film.CameraScalabilityProbeTest"` (table is in the
test's `system-out`).

## Measured (defaults: aspect 9:16, fov 70, aerialUntilHeightFrac 0.12, transitionFrac 0.40, frameMargin 1.0)

| archetype | footprint R | handoff | aerial % | orbit R (min..max) | verdict |
|---|---|---|---|---|---|
| TALL_TOWER 20×220×20 (narrowing) | 10 | **0.63** | **62%** | 132..**203** | camera ~20× footprint away |
| WIDE_LOW 120×30×120 (uniform) | 60 | **−1** | **14%** | 152 | wrong shot type |
| LINEAR 200×20×8 (uniform) | 100 | **−1** | **14%** | 254 | wrong shot type |
| SQUAT_STATUE 30×60×30 (uniform) | 15 | **−1** | **14%** | 38..55 | passable, no detail |

## Findings (the actual couplings — some contradicted the initial guess, which is the point)

1. **The structural aerial→orbit handoff only works for a NARROWING build.** The tower narrows, so
   `AreaProfile.handoffProgress` returns 0.63 and the establishing shot lasts a meaningful 62%. Every
   *uniform* shape (wide, linear, squat) returns **−1** → there's no base→vertical transition to detect,
   so the camera falls back to the height threshold for *all* of them.

2. **The phase split is SHAPE-BLIND.** Because the fallback is purely height-progress, three wildly
   different footprints — 120×120, 200×8, 30×30 — collapse to the **identical 14% aerial**. The gate
   never looks at footprint shape. That's the core non-scalability: a wide/flat plaza (best read from
   *above*) gets the *same* tiny overhead as a squat statue, then both cut to the same rising low orbit.

3. **The shot TYPE is fixed** (aerial-look-down → rising low "hero" orbit) regardless of shape. That's
   right for a tower and wrong for the rest: a wide/low build wants a *high or top-down* orbit; a linear
   build (bridge/wall) wants a *tracking dolly* along its length, not a circle around its midpoint; a
   statue wants *detail push-ins*. The low-looming framing (`heroCamFrac`) is computed from height, which
   is near-meaningless for flat builds.

4. **Fitting a tall build in a 9:16 frame puts the camera ~20× its footprint away** (orbit R 203 vs
   footprint R 10) — even for the *tuned* tower. This is the confirmed "background dominates / distant
   chunks don't load" problem from the live takes. It's largely inherent to a tall subject in a narrow
   vertical frame; the levers are a wider assumed FOV (camera closer for the same fit) or rendering in
   ReplayMod (loads chunks per frame). See RECORDING.md §G/§H.

## Fix list / roadmap (build in this order — each unblocks the next)

1. **StructureProfile + classifier** — compute once per `.schem`: bounding dims, an **aspect class**
   (TALL / WIDE / LINEAR / SQUAT, from the ratios + the area profile), the area-profile beats, hero
   features. This is what lets the system pick *behavior by shape* instead of one height-only gate.
   (Generalizes `AreaProfile`, "the early slice of the V2-2 analyzer".)
2. **Generalized shot library** — make the current aerial + rising-orbit *two entries among several*:
   add a high/top-down orbit (WIDE), a tracking dolly (LINEAR), and detail push-ins (SQUAT). Select by
   the structure class. This is the fix Findings 2–3 demand.
3. **Externalized StyleProfile** — pull the Donut-tuned knobs out of `Map2mcConfig` defaults into a
   named, swappable profile (framing/FOV/pacing/shot preferences; later a CLIP reference set for the
   vision scorer). `filmTimeOfDay` (just shipped) is **field #1** of this profile.
4. **AI pre-flight planner** — sits on top: `(StructureProfile) × (StyleProfile) → shot plan →`
   the existing deterministic executor. A new schem is "classify it"; a new style is "write a profile";
   neither touches camera code. That seam is the actual definition of "scalable" here.

**Hardware ceiling (do alongside #1):** bigger/varied schems = bigger builds, more chunk-loading,
longer captures — on a 7.7 GB box that bites. Add a max block-count / footprint cap with a clear
graceful-fail message so a 200k-block cathedral fails loudly instead of freezing the client.

## What's already programmatic (this round)
- **Time of day** is now auto-locked at film/record start (`filmTimeOfDay`, default `noon`; `off` to
  skip) — the first style parameter, scalable across any schem. No more low-morning-sun bloom.
- **Not** automatable: shader settings (Iris, user-side) and the ReplayMod **render trigger** (GUI-bound).
  The automatable render seam is the camera *path* (deferred `timelines.json` injection); a fully headless
  render would need Flashback (FFmpeg). See RECORDING.md §E/§G/§H.
