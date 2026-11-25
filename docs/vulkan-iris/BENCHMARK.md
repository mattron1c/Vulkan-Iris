# Vulkan Iris — A/B benchmark

No performance claim about Vulkan Iris is made without these numbers. Where Vulkan Iris is slower, this page says so.

## Method
- **A: Iris on GL.** The same jar as B, run without Polydium. This fork is Iris 1.10.8 for Minecraft 1.21.11, and
  without Polydium its own GL pipeline runs on Sodium 0.8. `GlBench` loads each pack through Iris's config and
  `Iris.reload()`, as a player would, and checks that Iris reports the pack as loaded.
  - Task: `gradlew :fabric:prodBenchGl`
  - Report: `fabric/run-prod/vulkaniris-bench-gl.txt`
- **B: Vulkan Iris on Polydium's Vulkan backend.** The Phase 7 harness in bench mode draws the world with the pack's
  gbuffers, shadow and post programs. The self-test readback probe is off, and no screenshot is taken until
  measuring ends.
  - Task: `gradlew :fabric:prodBenchVulkan`
  - Report: `fabric/run-prod/vulkaniris-phase7.txt`, the `bench:` lines
- **Same scene.**
  - world `vulkaniris-phase6` (seed 6), the same player position and camera
  - the same summoned entities and chest, sword in the main hand and stained glass in the off hand
  - noon, clear weather, daylight and weather cycles frozen
  - render distance 16, window 854×480, VSync off, frame limit off
  - the same `run-prod` game directory, options and pack options (each pack's defaults)
- **Timing.** After each pack loads, there are 240 frames for Sodium to rebuild the chunks with the pack's programs.
  Then a 20 s warm-up, then 60 s measured (`-Dvulkaniris.bench.warmup`, `-Dvulkaniris.bench.seconds`).
  - The frame time is the wall-clock time between two ends of `GameRenderer.render`, on both sides.
  - It covers the whole frame (CPU, the GPU wait, the present), not the pack alone.
- **Reported:**
  - average FPS
  - 1 % low (FPS over the slowest 1 % of frames)
  - median, 99th percentile and worst frame time
- **Diagnostics:** `-Dvulkaniris.bench.gclog=<file>` (GC log), `-Dvulkaniris.bench.jvm="<flags>"` (extra JVM flags, e.g. JFR or
  another GC). The report also says how many pipelines were built on first use while measuring (0 in the packs checked).
- **GPU time per stage, B only:** `-Dvulkaniris.gputime=true` (see [GPU time per stage](#gpu-time-per-stage-b-only)).
- **GPU time per stage on GL:** the same flag on `prodBenchGl` (`GlStageTimer` in Iris's pipeline).
- Packs run one after another in one game session per side, in the same order. A and B never run at the same time.

## Results — 2026-11-24, NVIDIA RTX 5060 (driver 610.88), after the frame-spike fix

A = Iris 1.10.8 on GL (Sodium 0.8). B = Vulkan Iris on Polydium 1.4.21 (Vulkan 1.4.341). 60 s per pack after a 20 s
warm-up; the scene, options and order are the same on both sides. "Δ" is B against A. A is the run below (unchanged
code on the GL side); B was re-run after the fix described in [Frame-time spikes](#frame-time-spikes-found-and-fixed).

| pack | A average FPS | B average FPS | Δ average | A 1 % low | B 1 % low | Δ 1 % low | A / B worst frame |
|---|---|---|---|---|---|---|---|
| *no pack (shaders off): the backend alone* | 1065 | 2824 | +165 % | 453 | 745 | +64 % | 12.8 / 20.0 ms |
| Bliss | 423 | 476 | +12 % | 255 | 274 | +7 % | 9.7 / 11.9 ms |
| BSL | 415 | 382 | −8 % | 265 | 246 | −7 % | 8.5 / 9.3 ms |
| Complementary Reimagined | 412 | 366 | −11 % | 250 | 227 | −9 % | 8.2 / 11.6 ms |
| Complementary Unbound | 414 | 351 | −15 % | 257 | 236 | −8 % | 9.7 / 8.6 ms |
| MakeUp UltraFast | 586 | 817 | +39 % | 333 | 427 | +28 % | 8.5 / 19.1 ms |
| Mellow | 836 | 1073 | +28 % | 430 | 526 | +22 % | 11.2 / 7.1 ms |
| miniature | 569 | 1055 | +85 % | 313 | 411 | +31 % | 11.4 / 23.2 ms |
| Noble | 269 | 192 | −29 % | 189 | 145 | −23 % | 8.4 / 12.0 ms |
| Sildur's Vibrant Extreme | 389 | 433 | +11 % | 228 | 266 | +17 % | 11.4 / 7.2 ms |
| Solas | 338 | 283 | −16 % | 220 | 185 | −16 % | 8.3 / 14.3 ms |

### What this shows, and what it doesn't
- **Vulkan Iris is not faster than Iris overall.** It depends on the pack.
  - Average FPS is higher in 5 packs (+11 % to +85 %) and lower in 5 (−8 % to −29 %).
  - 1 % lows are higher in 5 packs (+7 % to +31 %) and lower in 5 (−7 % to −23 %).
  - Worst frames are now 7–23 ms on B, against 8–13 ms on A. B no longer has the 30–68 ms spikes of the first run.
- **Where B is slower:** Noble (−29 %), Solas (−16 %), Complementary Unbound (−15 %), Complementary Reimagined (−11 %)
  and BSL (−8 %). These are the heavier deferred packs, so the cost is in Vulkan Iris's pack work (its passes, barriers
  and per-draw overhead), not in the backend. Finding where needs GPU per-stage timestamps, which are not measured yet.
- **The backend alone is much faster on Vulkan.** Without shaders, Polydium's Vulkan renderer runs at 2.65× Sodium's GL
  frame rate. The packs where B wins most are the light ones (miniature, Mellow, MakeUp), where the backend's share of
  the frame is largest.
- **The work isn't identical yet.** B skips work that A does:
  - Iris's extended entity IDs
  - mipmaps of mipmapped buffers
  - PBR atlases (B uses Iris's default normal / specular)
  - viewport scale

  Each pack's Phase 7 notes list these. They mostly make B's frame *cheaper* than A's, so they don't explain B's losses.
- **Limits of this run:**
  - one run per side, one GPU, one resolution
  - one run per side, one GPU, one resolution
  - wall-clock frame times include the present and the CPU

## GPU time per stage (B only)
`gradlew :fabric:prodBenchVulkan -Dvulkaniris.gputime=true` writes a timestamp (`vkCmdWriteTimestamp`, bottom of pipe)
at each boundary of Vulkan Iris's frame. Results are read back 8 frames later without waiting. The report gives the
average GPU time between consecutive marks over the measured 60 s; in this run every frame was timed (none skipped).

What each interval holds:
- **begin:** uploads, uniforms, clears, `setup`, the `begin` stage
- **shadow pass:** `shadow.csh`, shadow clears, shadow terrain and entities, `shadowcomp`
- **opaque world:** from the end of `prepare` to `deferred`. This is Polydium and Sodium drawing the opaque world
  (terrain, entities, block entities, sky, particles) with the pack's gbuffers programs, plus Polydium's own work in
  between.
- **deferred:** the depth copy, centre depth and the `deferred` stage
- **translucent + hand:** translucent terrain, translucent entities, clouds, weather and the hand, with the pack's programs
- **composite + final:** the `composite` stage, `final` and the copy-back

The `prepare` stage and the gaps between hooks were under 0.02 ms in every pack except Solas (0.06 ms before the
shadow pass); they are left out below. Times in ms per frame. RTX 5060, 854×480, 2026-11-25.

| pack | B FPS | B median frame | GPU frame | begin | shadow pass | opaque world | deferred | translucent + hand | composite + final |
|---|---|---|---|---|---|---|---|---|---|
| Bliss | 460 | 2.04 ms | 2.12 ms | 0.01 | 0.50 | 0.67 | 0.23 | 0.12 | 0.58 |
| BSL | 363 | 2.59 ms | 2.70 ms | 0.01 | 0.80 | 0.91 | 0.10 | 0.67 | 0.19 |
| Complementary Reimagined | 351 | 2.68 ms | 2.80 ms | 0.02 | 0.77 | 1.28 | 0.11 | 0.30 | 0.31 |
| Complementary Unbound | 328 | 2.89 ms | 3.00 ms | 0.02 | 0.85 | 1.28 | 0.10 | 0.43 | 0.31 |
| MakeUp UltraFast | 771 | 1.22 ms | 1.23 ms | 0.00 | 0.27 | 0.59 | 0.06 | 0.14 | 0.16 |
| Mellow | 995 | 0.95 ms | 0.81 ms | 0.00 | 0.18 | 0.45 | 0.02 | 0.09 | 0.06 |
| miniature | 999 | 0.92 ms | 0.82 ms | 0.01 | 0.19 | 0.50 | 0.01 | 0.11 | 0.01 |
| Noble | 164 | 6.04 ms | 6.03 ms | 0.03 | 1.46 | 0.79 | 2.65 | 0.23 | 0.84 |
| Sildur's Vibrant Extreme | 405 | 2.32 ms | 2.41 ms | 0.02 | 1.11 | 0.61 | 0.24 | 0.27 | 0.16 |
| Solas | 304 | 3.12 ms | 3.23 ms | 0.07 | 0.82 | 1.51 | 0.20 | 0.32 | 0.25 |

- **B is GPU-bound in every pack.**
  - In the heavier packs, the GPU frame is about the wall-clock median frame (within 0.1 ms).
  - In the light packs (Mellow, miniature) the GPU frame is 0.1 ms shorter, so the CPU and the present are a small
    share of the frame.
  - So B's losses against A are GPU time, not CPU overhead.
- **Where it goes.** The shadow pass and the opaque world together are 55–84 % of the GPU frame in every pack but
  Noble (37 %).
  - Noble's deferred stage takes 2.65 ms, 44 % of its frame.
  - BSL's translucent pass takes 0.67 ms, more than its deferred and composite stages together.
- **Against A:** see [GPU time per stage: A against B](#gpu-time-per-stage-a-against-b).
- **This run was slower than the untimed one** by 3–14 % average FPS (Noble 191 → 164; it also had one 144 ms frame).
  - It isn't known whether that is the timestamps' cost or run-to-run variance.
  - So the timing is opt-in, and the FPS table above comes from runs without it.

## GPU time per stage: A against B
The same marks on GL: `GlStageTimer` writes `glQueryCounter(GL_TIMESTAMP)` at the same boundaries of Iris's own
pipeline (`IrisRenderingPipeline`): `beginLevelRendering`, `renderShadows`, `beginTranslucents` and
`finalizeLevelRendering`. Run `gradlew :fabric:prodBenchGl -Dvulkaniris.gputime=true`. Every frame was timed on both
sides (none skipped). Each cell is A / B in ms per frame.

| pack | GPU frame | begin | shadow pass | opaque world | deferred | translucent + hand | composite + final |
|---|---|---|---|---|---|---|---|
| Bliss | 2.24 / 2.12 | 0.07 / 0.01 | 0.62 / 0.50 | 0.54 / 0.67 | 0.23 / 0.23 | 0.09 / 0.12 | 0.68 / 0.57 |
| BSL | 2.33 / 2.70 | 0.07 / 0.01 | 0.98 / 0.80 | 0.69 / 0.91 | 0.12 / 0.10 | 0.14 / 0.67 | 0.32 / 0.19 |
| Complementary Reimagined | 2.30 / 2.80 | 0.08 / 0.02 | 0.77 / 0.77 | 0.83 / 1.28 | 0.14 / 0.11 | 0.09 / 0.30 | 0.40 / 0.31 |
| Complementary Unbound | 2.29 / 3.00 | 0.08 / 0.02 | 0.78 / 0.85 | 0.80 / 1.28 | 0.14 / 0.10 | 0.09 / 0.43 | 0.39 / 0.31 |
| MakeUp UltraFast | 1.21 / 1.23 | 0.01 / 0.00 | 0.25 / 0.27 | 0.58 / 0.59 | 0.07 / 0.06 | 0.08 / 0.14 | 0.20 / 0.16 |
| Mellow | 0.71 / 0.81 | 0.02 / 0.00 | — / 0.18 | 0.49 / 0.45 | 0.03 / 0.02 | 0.07 / 0.09 | 0.10 / 0.06 |
| miniature | 0.89 / 0.82 | 0.04 / 0.01 | 0.21 / 0.19 | 0.52 / 0.50 | 0.01 / 0.01 | 0.09 / 0.11 | 0.01 / 0.01 |
| Noble | 3.63 / 6.03 | 0.24 / 0.03 | 1.42 / 1.46 | 0.58 / 0.79 | 0.65 / 2.65 | 0.12 / 0.23 | 0.58 / 0.84 |
| Sildur's Vibrant Extreme | 2.48 / 2.41 | 0.09 / 0.01 | 1.30 / 1.11 | 0.57 / 0.61 | 0.28 / 0.24 | 0.10 / 0.27 | 0.14 / 0.16 |
| Solas | 2.85 / 3.23 | 0.11 / 0.07 | 0.93 / 0.82 | 0.99 / 1.51 | 0.32 / 0.20 | 0.09 / 0.32 | 0.40 / 0.24 |

Where B loses GPU time against A:
- **Noble's deferred stage: 2.65 against 0.65 ms.** That is +2.0 ms, all of Noble's gap (GPU frame 6.03 against 3.63 ms).
- **The translucent pass, in every pack:** 0.07–0.14 ms on A against 0.09–0.67 ms on B. The gap is largest in BSL
  (+0.53 ms), Complementary Unbound (+0.34) and Complementary Reimagined (+0.22), Solas (+0.22) and Sildur's (+0.18).
- **The opaque world, in most packs:** Solas +0.52 ms, Complementary +0.45–0.48 ms, BSL +0.22 ms, Noble +0.21 ms,
  Bliss +0.13 ms. This is Polydium and Sodium drawing with the pack's gbuffers programs, plus Vulkan Iris's per-draw
  work (descriptor writes, push constants and its per-quad derived attributes).
- **Mellow ran a shadow pass that Iris doesn't (0.18 ms).** Fixed, see below.

B is at or below A in the begin stage in every pack. It is also at or below A in the shadow pass, except
Complementary Unbound (+0.06 ms), Noble (+0.04) and MakeUp (+0.01). In composite + final it is at or below A
except Noble (+0.25) and Sildur's (+0.02).

### Fixed: a shadow pass Iris doesn't run (Mellow)
- Iris creates its shadow targets, and so a `ShadowRenderer`, only in two cases. Otherwise a pack's shadow programs
  never run.
  - `shadow.enabled` is true.
  - A non-shadow program has an *active* shadow sampler: `IrisSamplers.hasShadowSamplers`, asked of the linked GL
    program.
- Vulkan Iris ran the shadow pass whenever the pack had a shadow program.
- Mellow (default options) has `shadow.fsh`, and its `gbuffers_terrain` declares `shadowtex0`, but no code reads it.
  So GL drops the sampler, and Iris draws no shadows.
- **Fix:**
  - `PostChain.decideShadowPass` applies Iris's rule.
  - "Active" comes from the SPIR-V: `SpirvUse` reports the bindings that any instruction reads.
  - It's unit-tested in `SpirvUseTest`, with and without optimisation.
- **Result:** Mellow now has no shadow pass, as on GL. The other 9 packs keep theirs, as on GL. The report names the
  programs and samplers that keep each pack's shadow pass on. prodPhase7 11/11, prodPhase6 10/10.
- The FPS tables above were measured before this fix, so Mellow's B numbers include that extra shadow pass.

## Frame-time spikes: found and fixed
The first run (below) had a 20–68 ms frame every 2–3 s on B, in every pack, and none in the no-pack control.
- **The spikes followed GC cycles.** A GC log (`-Dvulkaniris.bench.gclog=gc.log`) put each spike right after a G1 young
  pause (5–8 ms each). The spikes were 20–30 ms, so the pause alone didn't explain them.
- **It wasn't the pause.** The spikes stayed with ZGC (`-Dvulkaniris.bench.jvm=-XX:+UseZGC`), whose pauses are
  under 1 ms. They still lined up with GC cycles.
- **JFR showed the cause** (`-Dvulkaniris.bench.jvm=-XX:StartFlightRecording=filename=bench.jfr,settings=profile`).
  - In spike frames, the render thread was in `WeakHashMap.expungeStaleEntries`, called from Vulkan Iris's
    `VanillaHook`.
  - `VanillaHook` kept three `WeakHashMap`s keyed by `VulkanRenderPass`: passes created, passes closed (diagnostics only)
    and each pass's vertex buffer. Blaze3D makes hundreds of passes a frame.
  - After each GC, the next map access on the render thread purged hundreds of thousands of stale entries at once.
- **Fix:**
  - The vertex buffer is kept only for the redirected pass, which is the only one that needs it, in its per-pass state.
  - The diagnostic sets are identity sets, cleared at 4096 entries.
  - Nothing is weak any more.
- **Result, B only, first run → this run:**
  - 1 % lows went up in every pack, by 9–65 %: BSL 182 → 246, Mellow 319 → 527, Noble 122 → 145, Solas 170 → 185.
  - Worst frames went from 30–68 ms to 7–23 ms.
  - Average FPS changed by −5 % to +3 %, within what one run per side can tell apart.
- The bench report lists each frame over 20 ms with its JVM uptime, so it can be matched against a GC log or a JFR
  recording.

## First run — 2026-11-24, before the frame-spike fix (kept for the record)

A = Iris 1.10.8 on GL (Sodium 0.8). B = Vulkan Iris on Polydium 1.4.21 (Vulkan 1.4.341). 60 s per pack after a 20 s
warm-up; the scene, options and order are the same on both sides. "Δ" is B against A.

| pack | A average FPS | B average FPS | Δ average | A 1 % low | B 1 % low | Δ 1 % low | A / B worst frame |
|---|---|---|---|---|---|---|---|
| *no pack (shaders off): the backend alone* | 1065 | 2846 | +167 % | 453 | 772 | +70 % | 12.8 / 16.9 ms |
| Bliss | 423 | 472 | +12 % | 255 | 207 | −19 % | 9.7 / 42.9 ms |
| BSL | 415 | 381 | −8 % | 265 | 182 | −31 % | 8.5 / 43.7 ms |
| Complementary Reimagined | 412 | 365 | −11 % | 250 | 180 | −28 % | 8.3 / 30.0 ms |
| Complementary Unbound | 414 | 341 | −17 % | 257 | 172 | −33 % | 9.7 / 42.1 ms |
| MakeUp UltraFast | 587 | 802 | +37 % | 333 | 276 | −17 % | 8.5 / 58.8 ms |
| Mellow | 836 | 1041 | +25 % | 430 | 319 | −26 % | 11.2 / 59.8 ms |
| miniature | 569 | 1093 | +92 % | 313 | 335 | +7 % | 11.4 / 62.5 ms |
| Noble | 269 | 196 | −27 % | 189 | 122 | −35 % | 8.4 / 35.1 ms |
| Sildur's Vibrant Extreme | 389 | 449 | +15 % | 228 | 203 | −11 % | 11.4 / 68.4 ms |
| Solas | 338 | 297 | −12 % | 220 | 170 | −22 % | 8.3 / 54.6 ms |

### What the first run showed
- **Vulkan Iris is not faster than Iris overall.**
  - Average FPS is higher in 5 packs (+12 % to +92 %) and lower in 5 (−8 % to −27 %).
  - The 1 % lows are worse in 9 of 10 packs.
  - B has frame-time spikes of 30–68 ms, where A stays under 12 ms. Those spikes are the first thing to fix.
- **The backend alone is much faster on Vulkan.** Without shaders, Polydium's Vulkan renderer runs at 2.7× Sodium's GL
  frame rate. So the packs where B is slower lose time in Vulkan Iris's own work, not in the backend.
  - The packs where B wins most are the light ones (miniature, Mellow, MakeUp), where the backend's share of the frame is largest.
- **The work isn't identical yet.** B skips work that A does:
  - Iris's extended entity IDs
  - mipmaps of mipmapped buffers
  - PBR atlases (B uses Iris's default normal / specular)
  - viewport scale

  Each pack's Phase 7 notes list these. They mostly make B's frame *cheaper* than A's, so they don't explain B's losses.
- **Limits of this run:**
  - one run per side, one GPU, one resolution
  - no GPU per-stage times yet, so it doesn't show *where* B spends its time
  - wall-clock frame times include the present and the CPU
