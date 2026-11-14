# 光影渲染 RenderLab: project guidance

A tabletop photo studio in PICO OS 6 Full Space (Mixed Stage) (Spatial SDK BOM 6.1.9), package `io.embodit.renderlab`,
v0.1.2. Design contract: spatial-app-factory `apps/render-lab/design-package.json` (design-1.2; gate B 2026-11-12 all
defaults: self-computed shadow + score, procedural subjects, no .glb import, 1 cm dashed fence). `v2[]` not built (no glb
import, no Tripo plaster head, no custom IBL, no export, no spot light, no material sliders, no turntable, no SDK shadows).
Skeleton from the factory's puzzle-box project (CloudAir lineage: two containers, theme, sound pool, board-input template).

## Surfaces (Full Space since 2026-11-12, user change)
The user moved the app from a Shared Space volume to Full Space ("Shared Space 交互很难用射线操控"): one Mixed Stage
(passthrough, manifest `pico.spatial.stage.*`) + one Planar control window. All studio geometry is unchanged and local to
the `root` entity; `Place.studio(head)` puts that root 0.55 m ahead, 0.55 m below the eye (studio floor ~0.80 m above the
floor for a 1.65 m eye), yaw only, at the first valid head pose and again on Settings [叫回面前] (`Graph.recall`).
- **Studio** (`root`): floor top local y -0.30, back wall z -0.31, subject at (0, -0.30, -0.10); three light orbs on a sphere
  R 0.24 around C (0, -0.24, -0.10), az ±135°, el 10-80°, >= 28° apart; hint strip on the wall's top edge (tap it to
  reopen the window when the window is closed); material shelf + tappable upper wall only in free light.
- **Orb input, three ways at once**: direct pinch on the orb (system DirectPinch), eye-hand (GazePinch) and hand ray
  (RayBasedPinch) come through the board-input template (`StudioListener`, world hand travel turned into studio-local
  travel); the controller uses the system-drawn ray and the app's own pick (`ControllerDrag`: studio-local ray / sphere
  pick, trigger grab, the orb follows the ray at its grab distance). Since v0.1.2 the app draws no ray or hit dot (one
  ray per controller, D41 correction); grabbing an orb is not a tool mode, so the system ray stays on. The system's copy of a controller press the app already handled is
  ignored.
- **Control window** (`RenderLabControl`, Planar 600 x 572 dp): native TabBar, pages 打光 / 题卡 / 设置. Every open waits
  for a level head (`LevelGate`, "look straight ahead" plate on the line of sight). Exit closes the window and the Stage,
  removes the tasks, exits the process. The head pose is used only for placing the studio and the level gate.

## Code
| Path | What |
|---|---|
| `domain/Studio.kt` | every metre, orb sphere / clamp / 28° + eye-angle exclusion, lights, materials, backdrops |
| `domain/Mesh.kt` | procedural meshes (box, UV sphere, lathe + Catmull-Rom profile, cone, tube, torus arc); the 3 subjects + their convex parts |
| `domain/Shadow.kt` | self-computed shadow: per-part projection onto floor / wall, convex hull, clip, 1 cm raster, IoU, eye visibility, fence outline |
| `domain/Shots.kt` | the 8 shots (copied from the design package), PCG random shot, `ShotEval` (target, 7/14/24° thresholds, checks, hint) |
| `domain/Place.kt` | studio placement from the head (yaw only), LevelGate, ray / sphere |
| `platform/Tracking.kt` | HeadTracker, ControllerInput |
| `domain/Lab.kt` | `LabFlow`: the single state (S00-S12), drag / tap / sliders / shoot / next / again / free light / save |
| `ui/studio/*` | `StudioScene` (entities, ECS per-frame `onFrame`, shadow + fence meshes rebuilt off the main thread), `StudioScreen` (board-input wiring, strip text) |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `import_cc0_sounds.py`, `build_icon.py`, `symbols.py`, `rl.sh` (debug harness) |

Lights: key = `DirectionalLightComponent` (no SDK shadow), fill / rim = `PointLightComponent`. Intensities are lux-like
(`LightLevels`: key 800 / 1600 / 2800, fill 300 / 700 / 1400, rim 800 / 1600 / 2800); values around 1 do nothing (N2).

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest   # 35 JVM tests: shadow / scoring / thresholds / visibility / PCG 1000, flow, layout + D35, placement + level gate, hit replay
python3 tools/import_cc0_sounds.py ; python3 tools/build_icon.py --icon512 icon.png
DEV=emulator-5560 tools/rl.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow render-lab` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.renderlab.DEBUG -p io.embodit.renderlab --es cmd <cmd> --es a "<arg>"` (or
`tools/rl.sh cmd <cmd> [arg]`). Log tag `RenderLab` (a `STATE S0x` line on every change).

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | |
| S01 第一次 | `goto S01` | clears progress, L1 at its start, key orb breathes |
| S02 打光中 | `goto "S02 L5"` (any L1-L8) / `shot L3` | |
| S03 拖灯中 | `goto S03` (held: beam + readout), then `release` | `hold "key 30 40"` holds any position |
| S06 对上了 | `goto S06` (L3 solved) | fence green and solid, [拍下] green and breathing |
| S07 拍下 | `goto S07` (3 stars) · `goto S07ONE` (off target) | |
| S08 题卡 | `goto S08` · `card 0..9` (8 = random, 9 = free) | |
| S09 自由打光 | `goto S09` (three lights on) · `goto S09GLOW` (glow glaze vase on black) | |
| S10 全部三星 | `goto S10` | |
| S11 / S12 | `goto S11` · `goto S12` · `goto CLEAR` (clear-progress confirm) · `no` | |
| 控制窗关掉 | `closepanel` · `reopen` · `recall` | the strip says how to reopen (and takes the tap) |

Other: `solve`, `shoot`, `next`, `again`, `orb "<key|fill|rim> <az> <el>"` (through dragStart / dragMove / dragEnd),
`place`, `select`, `level`, `kelvin`, `subject`, `material 0-4`, `backdrop`, `allstars`, `tap <holder>`,
`replay "<orb:key|sw:2|wall> [hand-ray|eye-hand|controller]"`, `lang zh|en|system`, `volume 0-3`, `mute`, `page`,
`testf on|off` (F↑ on strip and plates), `striptex "<text>"` (writes the strip texture to files/striptex.png), `keylevels "<l> <m> <h>"`, `pointscale <k>`, `n2 on|off`, `dump`, `evidence`.

## Known
- v0.1.2 (headset 2026-11-13): no app-drawn controller ray / hit dot, the system ray only (one per controller).
- v0.1.1 (QA #1 rework): primary / secondary buttons 64 dp, side-by-side gap `BUTTON_GAP` 16 dp; confirm pages are a full-page card;
  stars share the overlap line in the align card; button text 叫回面前 / Recall; orb plates 16 x 3.2 cm dark pills; the
  "look straight ahead" plate is clamped to -12° pitch (`Place.levelPlate`) so it hangs above the studio.
- English 3D strip text looks slanted right of screen centre in emulator captures; the texture itself is upright
  (`striptex`), so treat it as an emulator compositing issue until the headset says otherwise.
- The studio sits below eye level: emulator shots of it need `pico-cli capture camera move --text "pitch down 30 degrees"`.
- 3D drags / pinches are not reachable by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through the
  same LabFlow calls; the three inputs (controller ray, hand ray, eye-hand) need the headset. Panel buttons are real
  `pico-cli interact act` clicks.
- The emulator's pass-through IBL is strong; light / shade reading and over-exposure are judged on the headset (N3).
