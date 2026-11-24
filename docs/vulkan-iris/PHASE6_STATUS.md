# Phase 6 — Native post chain (deferred / composite / final): status

**Result: 10 / 10 real shader packs run their setup → begin → prepare → deferred → composite → final chain natively on
Vulkan, including their compute shaders and custom images, on Polydium's live in-world frame** (RTX 5060, NVIDIA 610.88, Vulkan 1.4.341, Minecraft 1.21.11, Polydium 1.4.21).

The first Phase 6 run reached 8 / 10. Noble was black because its exposure comes from a compute shader (`composite99.csh`), and Solas
samples a compute-written 3D image. Adding compute programs and custom images fixed both. It also exposed a crash whenever the crosshair
was on a block: Iris's block ID map (`currentSelectedBlockId`) was never set on Vulkan.

Test: `./gradlew :fabric:prodPhase6` (`-Dvulkaniris.phase6.selftest`), optionally `-Dvulkaniris.phase6.packs=bsl,noble`.
It opens or creates the world `vulkaniris-phase6` (seed 6, creative, peaceful) and waits 300 frames. It captures the
vanilla frame, then for each pack builds the chain, runs it for 90 frames on the live frame, reads back the result and
writes `run-prod/vulkaniris-phase6/<pack>.png` and `run-prod/vulkaniris-phase6.txt`.
Pass rule: the output has at least 16 distinct colours (it is not black or flat), and every screenshot was also checked by eye.

| Pack | Programs | Build | Result | Notes |
|---|---|---|---|---|
| Bliss 2.1.2 (Chocapic13 edit) | 17 | 3.4 s | pass | |
| BSL 10.1.3 | 9 | 0.5 s | pass | |
| Complementary Reimagined r5.9.3 | 9 | 1.1 s | pass | |
| Complementary Unbound r5.9.3 | 9 | 0.9 s | pass | |
| MakeUp UltraFast 9.4b | 6 | 0.3 s | pass | dark/foggy look: no gbuffer data yet |
| Mellow 3.5 | 9 | 0.5 s | pass | |
| miniature 2.19 | 1 | 0.03 s | pass | its only post program passes colortex0 through when there is no gbuffer data, so it matches vanilla |
| Noble 1.9.9 | 36 (3 compute) | 2.6 s | pass | blue cast: its deferred lighting reads gbuffer material data that doesn't exist yet |
| Sildur's Vibrant 2.02 Extreme | 6 | 0.4 s | pass | dark: no gbuffer data yet |
| Solas 3.7b | 9 | 0.9 s | pass | `floodfill_img` exists, but is filled only by `shadowcomp` (Phase 7), so it stays 0 |

Build times include Iris's patching, the translator, shaderc and pipeline creation, with no SPIR-V cache. The test
also records CPU command-recording time (0.2–1.4 ms per frame), but that is **not a benchmark**. No GPU timings were taken.

Screenshots (854×480, as read back from the GPU): [vanilla](phase6/vanilla.png), [BSL](phase6/bsl.png),
[Complementary Reimagined](phase6/complementary-reimagined.png), [Bliss](phase6/bliss.png), [Noble](phase6/noble.png), [Solas](phase6/solas.png).

## What runs, per frame
Vulkan Iris runs this inside Polydium's frame, after `LevelRenderer.renderLevel` and before the GUI. All images stay
in `VK_IMAGE_LAYOUT_GENERAL` (Polydium's convention), and barriers come from `LayoutTracker`.
1. Render targets are resized when needed, then staged custom-texture/SSBO uploads run (their staging buffers are freed after the submit).
2. Iris's `FrameUpdateNotifier.onNewFrame()` and `CustomUniforms.update()` run.
3. colortex targets are cleared with Iris's rules: colortex0 = fog colour with alpha 1, colortex1 = white, everything else = 0, unless the pack sets its own clear colour.
4. Pack images with `clear = true` are cleared to 0. On the first frame, `setup` compute programs run once (Iris's `dispatch(1, 1)`).
5. Stages run in order: begin → prepare → *(scene copy into colortex0 + centre-depth sample)* → deferred → composite.
   Each stage applies its pre-flips first. The scene copy stands in for gbuffers until Phase 7. At each program index, that index's
   compute programs are dispatched first, then its fragment program (an index with only computes is a compute-only pass, as in Iris).
6. `final` (after `final` computes) draws to Polydium's main colour target. A pack without `final` gets colortex0 blitted to the screen.
7. Buffers left flipped are copied back, as Iris does.

Pipeline per program: Iris `TransformPatcher` → `VulkanGlslTranslator` (now also records each SSBO's original
`layout(binding=N)`) → shaderc → `GraphicsProgram` (dynamic rendering, one colour attachment per draw buffer, with
formats from the pack's buffer formats). The draw is Iris's composite quad (Position vec3 + UV0 vec2, 6 vertices).
Uniforms go in a std140 `iris_Uniforms` block, written per pass into a 4-slot ring.

### Compute and custom images
- `ComputeProgram` (vulkan module): a compute pipeline from SPIR-V. It uses the same Iris patcher (`patchCompute`) → translator → shaderc path as the graphics programs.
- Work groups follow Iris's `ComputeProgram.getWorkGroups`: absolute `workGroups`, or `workGroupsRender` relative to the screen, or screen ÷ local size.
  The local size is read from the shader's `layout(local_size_*) in;`. Indirect dispatch (`indirect.<pass>`) reads from the pack's SSBO, which is created with indirect usage.
- Iris issues `glMemoryBarrier` before every dispatch and after a pass's computes. Vulkan Iris issues the same buffer memory barrier, and image hazards go through `LayoutTracker`.
  An image a program both samples and writes stays in `GENERAL` for both.
- Custom images (`image.<name>`) are created as 1D/2D/3D storage + sampled images in the pack's internal format, after checking that the format supports storage on this GPU.
  Programs reach them as storage images by name and as samplers by sampler name (`GlImage`: nearest for integer formats, else linear, clamp). Images with `clear = true` are zeroed every frame.

### Uniforms
These are Iris's own uniform code, reused without GL. `VulkanUniformWriter` implements Iris's `DynamicUniformHolder`, so
`CommonUniforms`, `IrisExclusiveUniforms`, the matrix and time uniforms and the pack's `CustomUniforms` register into
it exactly as they do on GL. It records suppliers and writes their values at the offsets the translator assigned.

Because Polydium strips every Iris mixin, Vulkan Iris now drives Iris's state from its own hooks:
- `FrameState`: frame counter and timers, world time, gbuffer matrices, tick delta and fog colour.
- `*Port` mixins, applied only when Polydium is loaded: item light, local player, biome category/precipitation, and the
  biome ambient-sound handler. These were copied from Iris and credited.
- Its own `shouldRenderBlockOutline` accessor, because touching Iris's stripped accessor interface fails class loading.

### Inputs a pass can sample
Resolved in Iris's precedence order:
- the stage's custom textures: these override colortex/legacy names unless that buffer was already flipped earlier in the stage (Iris's `CustomTextureSamplerInterceptor`)
- `colortexN` and the legacy names
- `colorimgN` as storage images
- `depthtex0`/`gdepthtex`
- `noisetex`: the pack's PNG, or Iris's generated noise
- `iris_centerDepthSmooth`: Iris's 1×1 smoothed centre depth, with the same half-life maths
- pack PNG / raw 2D / raw 3D custom textures
- SSBOs (`bufferObject.N`), with their initial contents
- an unassigned `sampler2D` reads colortex0, as GL texture unit 0 does

## Not implemented yet (each is reported as a note in the test output, never faked silently)
- **gbuffers and shadows (Phase 7).** colortex0 holds the vanilla scene, other gbuffer outputs are their clear colour,
  and `shadow*` samplers get a 1×1 "no shadow" stand-in. Packs therefore look flatter or darker than on GL.
- **`shadowcomp`** (shadow-stage compute) waits for the shadow pass. Packs that fill voxel or floodfill images there (BSL, Bliss, Complementary, Solas) see those images as 0.
- Screen-relative custom images are sized once and not resized with the window.
- Validation layers were not run: the Khronos validation layer is not installed on the test machine.
- `depthtex1`/`depthtex2` = main depth (no pre-translucent or pre-hand depth copies yet).
- Per-program blend overrides, viewport scale and mipmapped buffers (`*MipmapEnabled`) are ignored.
- Screen-relative SSBOs are sized once and not resized with the window.
- Uniforms that read GL state (`blendFunc`, `atlasSize`, `gtextureSize`, `gtextureId`) are 0, as are `cloudTime` and
  uniforms Iris itself does not provide (e.g. Noble's `gbufferPreviousModelViewInverse`; GL leaves these 0 too).
- The test's "differs from vanilla" figure is information only (the world animates between captures).
- The chain is driven by the self-test only. There is no user-facing pack selection on Vulkan yet.
