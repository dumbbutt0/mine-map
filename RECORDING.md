# Recording & Cinematics

How to take a `map2mc` build from the dev sandbox into a real Minecraft client and record a
timelapse video in the style of the reference (slow orbit around a landmark, bright stylized
daytime, vertical framing).

The key mental model: **three independent layers stacked in one instance.** map2mc places
blocks (server-tick logic), Iris renders the scene, ReplayMod records the camera. They do
not wire into each other — they just coexist in the same `mods/` folder. So "integration"
is really "build a jar + assemble an instance."

---

## A. Build the distributable jar

```bash
export JAVA_HOME=$HOME/.local/jdk-21
cd <repo>
./gradlew build
```

The loadable mod is `build/libs/map2mc-<version>.jar` — the plain remapped jar.
Do **not** use `-sources.jar` or `-dev.jar`; those are Loom's source/unremapped artifacts
and will not load in a production client.

> WSL note: build here, but run the recording client on a **native** OS (e.g. the Windows
> host). OpenGL + shaders over WSLg on a low-RAM box is the fragile path we deliberately
> avoided during headless verification. The jar is fully portable. From Windows the build
> output is reachable at `\\wsl$\<distro>\home\<user>\...\build\libs\`.

---

## B. Assemble the instance (Prism Launcher recommended)

1. New instance → Minecraft **1.21.11** → add the **Fabric** loader.
2. Put these in the instance `mods/` folder, all **1.21.11 + Fabric**:
   - **Fabric API** — current build tagged 1.21.11 (map2mc depends on it).
   - **map2mc-<version>.jar** — the jar from step A.
   - **ReplayMod** — `1.21.11-2.6.26` (native 1.21.11 Fabric release).
   - **Iris** — `1.10.7+1.21.11-fabric` (latest 1.21.11 build; 1.10.6 also works).
   - **Sodium** — `mc1.21.11-0.8.12-fabric`. Iris needs a Sodium built for the **same MC
     version** and refuses to load against a mismatch, so install whichever Sodium your Iris
     build's page lists as required — for 1.21.11 that's the **0.8.12** line, **not** the
     older 0.8.6 (0.8.6 was never built for 1.21.11).
3. Put the shader pack in the **`shaderpacks/`** folder (NOT `mods/`):
   - **Complementary Reimagined** `r5.8.1` (or newer). Leave the `.zip` zipped.

Always source mods/shaders from Modrinth, CurseForge, or the official sites — not mirror
sites. Grab the newest build tagged 1.21.11. (Versions above verified against Modrinth on
2026-06-23: Fabric API `0.141.4`, ReplayMod `2.6.26`, Iris `1.10.7`, Sodium `0.8.12`,
Complementary Reimagined `r5.8.1`.)

---

## C. Record loop

1. Create a **superflat** (or flat staging) world with **Allow Cheats: ON** — `/map2mc`
   commands need operator permission.
2. Enable the shader: Options → Video Settings → Shader Packs → Complementary Reimagined.
3. Drop the landmark `.schem` in `run/.../map2mc/schematics/` (convert `.litematic`/legacy
   `.schematic` to Sponge `.schem` first — see the asset notes in CLAUDE.md).
4. **Start ReplayMod recording** (it captures the integrated-server session client-side).
5. Run `/map2mc hero <file>.schem` and let the paced build run to completion.
6. **Stop recording.** Open the replay from the main menu's Replay Viewer.
7. Set up the camera path + time curve (Section D), then render (Section E).

> Because the build runs on `END_SERVER_TICK`, a client-side install is all you need for
> singleplayer. Only a dedicated-server recording (separate spectator client) would require
> the jar on the server side.

---

## D. Camera path — slow orbit matched to the reference

ReplayMod separates **Position keyframes** (where/how the camera looks) from **Time
keyframes** (how fast the recording plays back). This separation is what creates the
timelapse-with-smooth-orbit effect: a 2-minute real build can play back in 20 seconds while
the camera does one slow, steady circle.

**Orbit (Position keyframes):**
- Fly the spectator camera to ~6–8 points evenly spaced in a circle around the structure,
  all at the same radius, framing the whole build with headroom.
- Add a gentle **vertical rise** across the circle (start low near the base, end higher than
  the top) for the crane feel in the reference.
- At each keyframe, aim the camera at the structure's center so it stays framed; ReplayMod
  smoothly interpolates rotation between keyframes.
- Keep the default **smooth (Catmull-Rom) interpolation** for position — even spacing +
  smooth = constant-feel motion. Close the loop back near the start point.

**Timelapse (Time keyframes):**
- Place a Time keyframe at the recording moment the build *starts* and another where it
  *ends*, then compress the span between them onto a short path duration (e.g. map a 120s
  build onto a 20–25s render). This speeds up the construction while the orbit stays slow.
- If you'd rather show the build at its real pace, skip Time keyframes and just let it play
  1:1 — but the compressed version reads more like the reference.

---

## E. Shader + render settings for the reference look

The reference is bright midday, blue sky with soft clouds, vibrant greens, clean soft
shadows — stylized, not gritty-photoreal. Complementary **Reimagined** defaults are already
close. Tweaks (Shader Pack Settings in-game):

- **Time of day:** lock to midday. In ReplayMod you can freeze world time, or set the world
  to `/time set noon` and `/gamerule doDaylightCycle false` before recording.
- **Shadows:** keep soft; default shadow quality is fine. Avoid long low-sun shadows
  (another reason to lock noon).
- **Bloom:** keep mild (default) — the reference has gentle highlight glow, not heavy bloom.
- **Volumetric fog / god rays:** low or off — the reference air is clear, not hazy.
- **Clouds:** on; the soft puffy clouds are part of the look.
- **Render distance:** push high (24–32 chunks) so surrounding terrain reads in wide orbit
  shots. ReplayMod renders frame-by-frame, so this is affordable even if realtime isn't.

**Render Settings (ReplayMod):**
- **Resolution:** vertical 9:16 — `1080x1920` (or `1440x2560` for crisper output).
- **Framerate:** 60 fps.
- **Path duration:** the 20–25s you set in Section D.
- Render to a lossless/high-bitrate format, then cut/grade in an external editor if desired.

---

## F. One full dry run before automating Phase 3

Do one complete pass — build jar → instance → `/map2mc hero` a real converted Eiffel
`.schem` → shaders on → a short rendered orbit — *before* extending the mod into Phase 3.
That pass is what tells you whether the **pacing** (`blocks-per-tick`) and **block palette**
read well under shaders. If they do, Phase 3's staging + ReplayMod orbit-export are worth
automating. If the pacing feels off, that's a one-line change now rather than a rework later.

---

## G. Matching the reference look — tuning checklist (OBS live capture)

Derived from a real take-vs-reference review (an early OBS capture of the live `/map2mc film`
director). The reference reads as **clean bright midday, clear air, vibrant greens, soft puffy
clouds, a steady portrait orbit**. The common gaps and their exact fixes, biggest first:

**1. Time of day — the single biggest "washed out" fix.**
A low golden sun blooms into a pink haze and kills contrast under shaders. Lock noon before filming:

```
/time set noon
/gamerule doDaylightCycle false
```

**2. Complementary Reimagined shader settings** (Options → Video Settings → Shader Packs →
(Reimagined selected) → Shader Pack Settings). Labels match Reimagined's categories:

- **Bloomy Fog → OFF / low.** This is the #1 cause of the pink, washed-out haze in Complementary.
  Turning it down restores the crisp, clear-air look.
- **Fog → Fog Density low; Border Fog OFF.**
- **Light Shafts (god rays) → low / off** — the reference air is clear, not hazy.
- **Bloom → mild (default)**, not cranked — your highlights were blooming out.
- **Clouds → Volumetric** — the soft puffy reference clouds. Blocky cloud slabs mean vanilla clouds
  are showing instead of the shader's.
- **Auto Exposure / Eye Adaptation → reduce or lock** so the low-contrast wash doesn't creep back.
- **Render distance → 24–32 chunks** (the mod already raises view distance to `filmViewDistance`=24
  while filming; `/map2mc set filmViewDistance 32` if your PC allows).

**3. Portrait 9:16 capture.** The mod's framing math already assumes 9:16 (`filmAspectW/H`=9/16), so
feeding it a 16:9 capture pillarboxes the build into a narrow center strip and wastes the framing.
- In OBS set the **canvas to 1080×1920** and capture a **portrait-shaped** Minecraft window.

**4. Clean frame — no menu / HUD / commands / chat.** The mod **auto-hides the HUD while filming**,
so the only artifacts are the **head** (pause menu, walking to the spot, typing the command) and the
**tail** (the command-output chat lines). Fix either way:
- **Start the OBS recording right after** you run `/map2mc film …`, and **stop before** the end chat
  appears; **or** record continuously and **trim the head/tail** in an editor afterward.
- Press **F1** during setup if you want a clean lead-in frame too.

**5. Calmer aerial establishing shot.** The steep, spinning overhead pan was eased in the defaults
(`filmAerialPitchDeg` 50→38, `filmAerialTurns` 0.30→0.12). To spend less of the runtime in the
overhead phase and more on the hero rising orbit, lower `filmAerialUntilHeightFrac`, or set
`filmAerialEnabled false` for a pure rising orbit. Try live with `/map2mc set <knob> <value>`.

**6. Out of the mod (your call).** The reference's extra polish comes from a **more detailed Eiffel
`.schem`** (full lattice + Champ-de-Mars gardens + planted trees) and a **lush jungle/cherry-blossom
biome**. Swap the schematic and pick a prettier world seed to close the rest of the gap.

> For maximum polish, the **ReplayMod render** path (§D/§E) beats live OBS capture: it renders only
> the camera path to a clean file (no menu/HUD/chat ever), and time-compresses a 2-min build into a
> slow 20–25s orbit. The OBS-live path above is the quick route; ReplayMod is the cinematic one.

---

## H. Cinematic recording with `/map2mc record` (+ `/map2mc scout`)

`/map2mc record` produces a finished-looking timelapse with the camera and site-prep handled for you:

- **The cinematic camera is engaged automatically** (same director as `/map2mc film` — a rising
  spectator orbit), so you're never free-roaming. OBS-capture that view for a clean clip, *or* install
  ServerReplay (below) for a higher-quality ReplayMod render.
- **The site is visibly stripped, then landscaped, then built** — the foundation clear and a lush
  plaza (grass lawn + path motif + biome-appropriate trees + flowers) are **paced** into the timelapse
  instead of an instant circle, so the grounds read like the reference (not an empty disc).
- **With ServerReplay installed**, the build is also captured to a `.mcpr` server-side (no menu/HUD/
  command text in the shot). map2mc drives ServerReplay by dispatching its `/replay` commands (no hard
  dependency); without it, `record` still runs the camera + site-prep and still exports the orbit table.

> The final **Render is still one manual click** in ReplayMod — no tool automates that last step.

### Prerequisites for the `.mcpr` capture (one-time, all **1.21.11 + Fabric**)
- **ServerReplay** — the 1.21.11 build (v3.3.x), from Modrinth/the official repo.
- **Fabric Language Kotlin** — ServerReplay's required dependency.
- **ReplayMod** (`1.21.11-2.6.26`) for the render step (as in §B). (Fabric API is already required.)

A server-side mod loads on the **singleplayer integrated server**, so this works in singleplayer.
*If `record` says "ServerReplay not detected," these two mods aren't installed — that's the only reason
the `.mcpr` capture is skipped.*

### Save recordings under your Videos folder
Set `recordOutputDir` to an absolute path, e.g.:
```
/map2mc set recordOutputDir C:/Users/you/Videos/map2mc
```
map2mc points ServerReplay's `chunk_recording_path` there (and writes its orbit tables there too).
**Restart the game once** after first setting it if ServerReplay had already cached its config.

### Record loop
1. In a world with cheats on, position yourself, then run:
   ```
   /map2mc record <file>.schem
   /map2mc record <file>.schem ~ ~ ~ 0 90      # explicit pos, rotation, duration(s)
   /map2mc record stop                          # end the camera + save early (build keeps going)
   ```
   The build runs at your current pace (`/map2mc pace …`); `[duration]` overrides it.
2. **Don't open the pause menu while it records** — singleplayer pauses the integrated server, which
   pauses the build, the camera, *and* the recording. Just let it run.
3. On completion the mod stops the capture and writes the orbit table, and tells you the paths
   (`.mcpr` under `recordOutputDir` — or ServerReplay's default `recordings/chunks/map2mc_<id>/`;
   orbit table next to it or under `map2mc/orbits/`).
4. **Import the `.mcpr` into ReplayMod** (copy into `.minecraft/replay_recordings/` or open via the
   Replay Viewer), apply the camera path from the orbit table (§D) or keyframe a slow orbit, set the
   shader/render settings (§E, **§G**), and **Render**.

### Pick a good site automatically — `/map2mc scout <file>.schem [radius]`
Builds never look "plopped" when they sit on flat, dry, green ground that needs little bridging/clearing.
`scout` samples a grid of nearby spots (radius in chunks, default 4), scores each on flatness, water/void,
biome, and bridging depth, reports the ranked top sites, then **record-builds at the best one**. Each
probe generates terrain, so the grid is capped (and logged).

### Tuning (`/map2mc set <knob> <value>`)
- `recordSitePrepSeconds` (default 12) — how long the strip + plaza take on camera (0 = instant).
- `plazaDecorate` (default true), `plazaTreeRings` (default 2) — the lush-plaza landscaping.
- `recordChunkMargin` (default 4) — scenery chunks captured around the footprint.
- `recordOutputDir` — where `.mcpr` + orbit tables are saved.
- Orbit table uses `orbitKeyframes`, `orbitRenderSeconds`, `orbitRadiusFactor` (as in `/map2mc orbit`).

> Deferred: writing the camera path straight into the `.mcpr` (so even the ReplayMod keyframing is
> skipped) — pending confirmation that the ServerReplay capture works end-to-end.
