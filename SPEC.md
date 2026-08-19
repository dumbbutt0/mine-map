# SPEC — Phase R: RLAIF policy for cinematic siting & presentation

> **One line:** stop hand-tuning `Map2mcConfig` and the siting heuristics. Train a small policy that,
> given a `.schem` + the terrain under it, **decides how to place, adapt, and film it** so the result
> looks like it *belongs in the world* — scored by a cheap learned "taste" model distilled from a
> vision LLM, with a human-judged holdout as the ship gate.

This builds directly on the shipped engine (Phases 0–3 + Phase A in `CLAUDE.md`). The mod stays the
deterministic **hands**; the new policy is the **brain** that drives it through the existing command
+ config surface. **Mine-map is NOT an LLM** — it's a small actor-critic network.

---

## 0. Why this is tractable (the core decision)

A 100³ build is 1,000,000 block decisions — RL hates that. We never let the policy touch blocks.
The mod already turns a `.schem` + a handful of **parameters** into a fully sited, foundationed,
decorated, filmed build. The policy only outputs **those parameters** (~20–40 numbers). That
parameter vector already half-exists as `Map2mcConfig` knobs + the siting inputs — we are exposing
and *learning* them instead of hand-setting them.

```
.schem + world  →  Terrain Scan  →  [POLICY]  →  ~30 params  →  map2mc (deterministic)  →  render  →  reward
                                       ↑___________________________________________________________|
                                                          PPO updates weights
```

---

## 1. Honest inventory — what EXISTS vs what's NEW

**Already built (reuse, do not reinvent):**
- **Terrain scan** — `com.map2mc.terrain.TerrainSampler` → `TerrainSample{min/median/max/slope, water/void}` per footprint. This is the seed of the state.
- **Siting** — `SiteScore` already ranks candidate sites on flatness / no-water / green-biome / shallow-bridging; `scout` grids nearby sites. **This is a ready-made objective reward term.**
- **Scene adaptation (the "generators" you thought you didn't have):** `FoundationGenerator` (bridge-down / clear-up over a circular plaza), `PlazaDecorator`/`PlazaPalette` (lawn, paths, trees, flowers). Knobs: `foundationEnabled/Style/SlopeThreshold/MaxBridge`, `foundationClearMargin`.
- **Camera / cinematography** — `film/DirectorCamera`, `FilmDirector` (aspect-aware framing, aerial establishing shot + cut, frontier tracking), `orbit` export. Knobs: `filmAspectW/H`, `filmBaseFovDeg`, `filmFrameMargin`, `filmHeadroomFrac`, `filmHeroCamFrac/AimFrac`, `filmAerialPitchDeg/Turns/Margin`, radius.
- **Pacing** — duration-driven (`pace/Pacing`).
- **Schem analysis** — `film/AreaProfile` already computes per-layer block counts (the "narrows past its base" profile). Free schem features for the state.
- **Recording** — `com.map2mc.recording` ServerReplay bridge → `.mcpr`; headless server via FIFO (`pause-when-empty-seconds=0`).
- **Proto-coach** — `tools/vision/score.py`: LAION aesthetic head on CLIP ViT-L/14 + style-match + dead-color heuristic. **Explicitly cautioned as a weak signal — do not close a loop on it as-is** (`CLAUDE.md`). It becomes the *initialization* of the trainable coach, not the coach.
- **Control surface** — `/map2mc get`/`set <knob> <val>`, `record`, `film`, `scout`, `hero`. **This IS the action API** — the RL harness drives the mod exactly like a human typing commands.

**New (this SPEC):**
1. **Op/param registry** — one schema that declares the policy's action vector (which knobs + choices, ranges) and translates an action → a sequence of `/map2mc set …` + `record/film` commands.
2. **State encoder** — TerrainSample + a local heightmap/slope/water/biome patch + schem features → tensor.
3. **The policy** — actor-critic net (Python). Nothing like it exists today (all current siting is heuristic).
4. **RL harness** — rollout driver (Python ↔ mod over the FIFO), PPO, BC pretrain.
5. **Trainable coach** — a reward model distilled from a vision-LLM rubric (upgrades `score.py` from fixed → learned).
6. **A few genuinely-new ops** if wanted later — richer roads/gardens/ambient beyond the current plaza.

---

## 2. Action space (the ~30 numbers)

Two heads, trained in separate stages (§6):

**Placement + scene-adaptation head (Stage A — "does it belong?")**
```
anchor_offset      : dx, dz around the scout-chosen site        (continuous, small range)
base_y_mode        : {median, min, max, custom+delta}           (categorical + delta)
rotation           : {0, 90, 180, 270}                          (categorical)
mirror             : {none, x, z}                               (categorical)
foundation_style   : {auto, stone, cobble, dirt}                (categorical)
foundation_margin  : foundationClearMargin                      (continuous)
slope_threshold    : foundationSlopeThreshold                   (continuous)
max_bridge         : foundationMaxBridge                        (continuous)
plaza_density      : vegetation/flower/path density             (continuous 0..1)
plaza_palette      : PlazaPalette choice                        (categorical)
[future ops: road_count, garden_region, ambient_density]
```

**Camera head (Stage B — "does it have emotion?")**
```
aspect             : filmAspectW/H                              (categorical: 9:16, 16:9, 1:1)
fov                : filmBaseFovDeg                             (continuous 30..110)
frame_margin       : filmFrameMargin                           (continuous)
headroom           : filmHeadroomFrac                           (continuous)
aerial_pitch       : filmAerialPitchDeg                         (continuous)
aerial_turns       : filmAerialTurns                            (continuous)
hero_cam/aim_frac  : filmHeroCamFrac, filmHeroAimFrac           (continuous)
radius             : {dynamic, fixed value}                     (mixed)
time_of_day        : sunrise..night                             (continuous/categorical)
duration           : pace seconds                               (continuous)
```

**Hard rule:** the action is only ever knobs + op choices routed through the deterministic mod.
No free-form terraforming, no per-voxel edits. That constraint is what keeps RL feasible.

---

## 3. State (what the policy sees)

```
terrain_patch  : local heightmap + slope map + water mask + biome id, N×N around the site
                 (extend TerrainSampler; it already gives min/median/max/slope + water/void)
schem_features : dims (W,H,L), non-air count, per-layer profile (AreaProfile), footprint aspect
site_context   : SiteScore components (flatness, water-proximity, biome, bridging depth)
```
Encoder: 3D/2D CNN (or sparse-voxel) on the terrain patch + a small MLP on schem/site features → concat embedding → actor & critic heads.

---

## 4. Reward (cheap → expensive)

```
R =  w_g · guardrails        FREE, dense, from the mod's own geometry:
                               clip / float / half-buried / underwater / bridge-cap-exceeded
                               (TerrainSampler + FoundationGenerator already know all of these)
   + w_s · SiteScore          FREE — reuse the existing siting objective directly
   − w_p · edit_parsimony     FREE — penalize terrain destroyed: cleared+bridged volume / footprint.
                               ★ This is how "terrain is a big part of the aesthetic" enters the
                                 objective: bulldozing to a flat pad COSTS reward, so the policy
                                 prefers move / rotate / foundation-supports over erasing the land.
   + w_c · coach(stills|video) FAST — the learned taste model (§5), 90% of scored rollouts
   + w_l · visionLLM_rubric    EXPENSIVE, ~10% of rollouts — refreshes/audits the coach
   + w_d · diversity_bonus     FREE — entropy over palette/rotation/site choices → protects range
   − β   · KL(policy‖reference) anti reward-hacking leash
```

**Rubric axes (both the vision LLM and the coach output these — never one "beauty" scalar):**
- Build: `integration/seam` · `terrain_fit` · `palette_coherence` · `foundation_believability`
- Camera: `reveal` · `framing` · `lighting/mood` · `motion` · `pacing` · `silhouette`

"Emotion" = the weighted camera axes. "Belongs" = the weighted build axes. Every score carries a
one-line rationale so failures are debuggable (same reason ICE > pass/fail in the audit pipeline).

---

## 5. The coach (reward-model distillation)

- **Init** from `tools/vision/score.py` (CLIP-L/14 aesthetic + style-match) — but make it *trainable*:
  a small head that predicts the **vision-LLM rubric vector** from frame embeddings.
- Loop: vision LLM labels ~10% of rollouts → coach trains to predict those labels → coach scores the
  other 90% fast/local. Periodically re-audit coach↔LLM agreement; when it drifts, refresh.
- **Honor the existing caution:** `score.py`'s numbers are a weak signal today. Do not let the coach
  gate anything until it earns trust on a held-out set (§7). It shapes training; it does not ship.

---

## 6. Training procedure

**BC first (do NOT RL from scratch).** From your existing good recordings, recover the params you
*can* observe — site/anchor, rotation, foundation style, camera knobs (`filmAspect`, fov, radius),
time, duration — and supervised-train the policy to output them. You **can't** cleanly recover
implicit per-op params humans set by hand; BC the recoverable dims, let RL discover the rest. This
alone is the biggest convergence lever.

**Staged curriculum (splits build-quality from camera so credit assignment is clean):**
- **Stage A** — fixed canonical camera. Train **placement + scene-adaptation together** (they're one
  creative unit — where you place depends on how you terraform). Reward = guardrails + SiteScore +
  edit-parsimony + build-axis coach. Score on **cheap canonical stills**, not full cinematics.
  - Optional: unlock ops progressively (placement+foundation first, then plaza/decorative).
- **Stage B** — freeze the build; train the **camera head** on the camera-axis rubric. Only here do
  you pay for full ReplayMod/ServerReplay cinematic renders.
- **Stage C (optional)** — joint fine-tune with **separate reward channels** per head.

**Algorithm:** PPO (clipped, on-policy), KL leash to the BC reference policy, entropy/diversity bonus,
early-stop on the **held-out gate** (not on train reward — that's how you catch overoptimization).

---

## 7. Data splits & ship gate

Four buckets (so you learn *which* adaptability axis is weak):
```
train schem × train seed          → training
unseen schem × train seed         → schem generalization
train schem × unseen seed         → terrain generalization  ← the land-adaptation you care about
unseen schem × unseen seed        → SHIP GATE (human + a held-out LLM the loop never saw)
```
**Ship rule:** ready ⇔ the human-judged score holds on **unseen × unseen**. The training coach/LLM
that shaped the policy may never be the referee (or the policy just learns to fool that judge).

---

## 8. Integration architecture (the real bottleneck is here, not the ML)

```
┌─ Python (new) ──────────────────┐        ┌─ Java mod (exists) ─────────────┐
│ policy → action → command list  │  FIFO  │ /map2mc set <knob> <v> …        │
│ rollout driver ─────────────────┼───────▶│ /map2mc record <schem> <pos> …  │
│                                 │        │  → build + ServerReplay .mcpr    │
│ reward: guardrails+SiteScore    │◀───────┼─ render/stills out (render/)     │
│         +coach(+10% visionLLM)  │  files │ headless server, pause=0, FIFO   │
│ PPO update                      │        └──────────────────────────────────┘
└─────────────────────────────────┘
```

- The mod's `get`/`set`/`record`/`scout`/`film` commands ARE the action API — drive them over the
  documented FIFO headless path (`pause-when-empty-seconds=0`).
- Guardrails & SiteScore come back as **structured JSON from the mod** (it already computes them) —
  cheap, no vision needed.
- **★ Critical infra dependency — headless render at scale.** Stage A can use cheap canonical
  **screenshots** (even shader-less) for the coach. But Stage B's cinematic scoring needs
  `.mcpr → mp4`, and `CLAUDE.md` currently documents cinematic rendering as a *native-client + Iris +
  ReplayMod, user-driven, not-WSLg* step. **Before Stage B, confirm a scriptable headless render path
  exists** (batch ReplayMod render, or an offscreen client). If it doesn't, that's a workstream of its
  own and it gates the camera stage. The ML is easier than this pipe.

---

## 9. Failure modes to design against (all real)

- **Over-flatten to a slab** — the cheap way to look "clean," and it destroys the terrain aesthetic.
  → edit-parsimony penalty (§4). Non-negotiable.
- **Reward-model overoptimization (Goodhart)** — policy climbs coach error. → KL leash + coach refresh
  + early-stop on the held-out gate.
- **Mode collapse** — one safe palette/site forever, kills your "broad customizable range."
  → diversity bonus; watch output entropy.
- **Guardrail gaming** — float the build to dodge the clip penalty; camera hides bad geometry off-frame.
  → explicit float/gap penalties; Stage-A canonical camera can't hide geometry.
- **Coach drift** — track coach↔LLM agreement; refresh when it decays.

---

## 10. Milestones (ordered by dependency, not ambition)

- **M1 — prove the harness. No coach, no vision LLM, no new ops.**
  Wire Python ↔ mod over the FIFO. Action = placement only (`anchor_offset, rotation, mirror, base_y`).
  Reward = **guardrails + SiteScore only** (both already exist in the mod). BC-init from good recordings.
  Fixed canonical camera; score nothing visual yet. **Goal: the loop closes and the policy learns to
  position/orient schems onto real terrain without clipping/floating, beating hand-set siting.**
- **M2 — scene adaptation + trainable coach.** Add foundation/plaza params to the action space. Stand up
  the coach (distill a first batch of vision-LLM rubric labels; init from `score.py`). Turn on
  edit-parsimony. Reward = 90/10 coach/LLM on **canonical stills**.
- **M3 — foundations as the hero op.** Push the foundation/support params hardest; this + edit-parsimony
  is where "meets the slope instead of erasing it" emerges. Retrain Stage A.
- **M4 — decorative ops** (richer paths/gardens/ambient beyond the current plaza) → full Stage-A action space.
- **M5 — Stage B: camera/emotion.** Freeze build, train camera head on the camera rubric. **Requires the
  headless cinematic render path (§8).**
- **M6 — ship gate.** Unseen × unseen, human + held-out LLM. Ship only if it holds.

**Start at M1.** It needs only your renderer/harness, the existing mod, and good recordings — no coach,
no generators, no LLM. It de-risks the entire loop before you invest in the reward model or new ops.

---

## 11. Non-goals / invariants
- The mod never becomes an LLM and never owns per-voxel generation.
- The policy only ever emits parameters routed through deterministic mod ops.
- No feedback loop ships on the coach until it earns trust on the held-out gate (extends the existing
  `score.py` trust caveat).
- Geometry answers facts (guardrails); the coach answers taste. Keep them separate.
