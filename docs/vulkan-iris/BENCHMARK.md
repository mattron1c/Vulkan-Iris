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
- **Not measured yet:** GPU time per stage (timestamp queries on Vulkan, `GL_TIME_ELAPSED` on GL), and CPU time split
  from GPU time. Without them, the numbers below don't say *where* the time goes.
- Packs run one after another in one game session per side, in the same order. A and B never run at the same time.

## Results — 2026-11-24, NVIDIA RTX 5060 (driver 610.88), one run per side

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

### What this shows, and what it doesn't
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
