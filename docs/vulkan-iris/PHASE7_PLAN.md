# Phase 7 — gbuffers and shadows on Polydium: plan

Goal: the world itself is drawn with the pack's `gbuffers_*` programs into the colortex targets, and the shadow pass
renders the shadow maps. Once that works, packs render as they do on Iris and performance can be compared fairly.

## How Polydium draws the world (found by inspecting Polydium 1.4.21's classes; nothing is copied)
- **Terrain.** Sodium 0.8.14 still builds the chunk meshes. Polydium's `MixinVulkanSodium08ChunkRenderer` sends Sodium's
  chunk draw to `org.polyfrost.polydium.vulkan.sodium.SodiumVulkan.render(CommandList, ChunkRenderListIterable,
  TerrainRenderPass, CameraTransform, boolean, SharedQuadIndexBuffer, …)`.
  - That method builds one blaze3d `RenderPipeline` per `TerrainRenderPass` (Sodium's compact vertex format, `u_BlockTex`/`u_LightTex`, a `ChunkData` uniform).
  - It draws each region's `MultiDrawBatch` through `VulkanRenderPass`, with `vkCmdDrawMultiIndexedEXT` or indirect draws.
  - `VkCommandList.gpu(GlBuffer)` maps Sodium's region buffers to blaze3d `GpuBuffer`s, and through those to `VkBuffer`s.
- **Everything else** (entities, block entities, hand, sky, clouds, particles, weather, outlines) goes through vanilla
  blaze3d `RenderPipeline`s on `VulkanRenderPass` / `VulkanCommandEncoder`. That is the same abstraction Iris 1.21.11
  intercepts on GL (`IrisPipelines`: vanilla pipeline → pack program).
- Polydium strips every Iris mixin, including `mixins.iris.compat.sodium.json` (20 mixins). That set includes Iris's extended chunk
  vertex format (`MixinChunkVertex`, `MixinChunkVertexConsumer`, `MixinChunkMeshBuildTask`, the block and fluid renderers),
  which supplies `mc_Entity`, `mc_midTexCoord`, `at_tangent` and the normals packs read.

## Slices (each one tested in-world with screenshots, like Phase 6)
1. **7a: extended terrain vertices.** *(done, see [PHASE7_STATUS.md](PHASE7_STATUS.md))* Port Iris's Sodium vertex-format mixins as `*Port` mixins, applied only on Polydium.
   They are CPU meshing code with no GL. Test: mesh a chunk, then decode block IDs and mid-texcoords from the vertex buffer.
2. **7b: terrain gbuffers.** *(done: 10 / 10 packs, see [PHASE7_STATUS.md](PHASE7_STATUS.md); it hooks `SodiumVulkan.draw`, not `render`)* When a pack is active, wrap `SodiumVulkan.render` and draw the same multi-draw batches with
   the pack's `gbuffers_terrain` / `_solid` / `_cutout` / `gbuffers_water` programs. Program fallbacks follow Iris's `ProgramId` chain.
   - Translation reuses Iris's `patchSodium` → `VulkanGlslTranslator`.
   - Output goes to the colortex MRT plus the main depth.
   - Then deferred runs on real gbuffer data: no more scene copy into colortex0, and depthtex1 comes from a pre-translucent depth copy.
3. **7c: other geometry.** *(done for entities, block entities, sky and particles: 10 / 10 packs, see [PHASE7_STATUS.md](PHASE7_STATUS.md#slice-7c--vanilla-geometry-with-the-packs-programs); clouds, the hand and Iris's fallback shader followed in "7c gaps"; still open: extended entity attributes / entity IDs)* Swap vanilla `RenderPipeline`s on `VulkanRenderPass` for pack programs, using Iris's own `IrisPipelines` mapping:
   - `gbuffers_entities`, `_block`, `_hand`, `_skybasic`, `_skytextured`, `_clouds`, `_particles`, `_weather`, `_basic`, `_textured`, `_beaconbeam`, `_armor_glint`, `_spidereyes`
   - plus Iris's `entityId`/`blockEntityId` uniforms
4. **7d: shadow pass.** *(done for terrain and entities: 10 / 10 packs, see [PHASE7_STATUS.md](PHASE7_STATUS.md#slice-7d--shadow-pass-terrain); entities and block entities followed; fragment shadowcomp with flipping, shadow.csh, shadowcolorimg and HW samplers followed, tested with a self-test pack; still open: shadow mipmaps)*
   - CPU side: Iris's `ShadowRenderer` logic, its shadow matrices, its culling and Sodium's section graph for the shadow view (Iris's `MixinRenderSectionManagerShadow`, ported).
   - GPU side: `shadow_*` programs into `shadowtex0/1` and `shadowcolor0/1`, then `shadowcomp` computes. These already work in the post chain.
5. **7e: correctness sweep** over all 10 packs:
   - Compare against Iris on GL with the same world, seed, camera and time.
   - Check the Iris-only features: hand depth, `depthtex1`/`2`, blend overrides, mipmapped buffers, viewport scale.
   - *Done:* `at_tangent` / `mc_midTexCoord` derived per quad in the vertex shader (Noble's black glass), see [PHASE7_STATUS.md](PHASE7_STATUS.md#slice-7e--derived-at_tangent--mc_midtexcoord).

## Performance — how it will be measured (no claim until measured)
**Measured:** see [BENCHMARK.md](BENCHMARK.md). In short, B is faster in 5 packs and slower in 5; the 30–68 ms
frame spikes of the first run were Vulkan Iris's own WeakHashMaps and are fixed. GPU per-stage times are not measured yet.

Being "faster than Iris" can only be shown once both render the same frame, after 7b–7d. Method:
- **Same scene.** World `vulkaniris-phase6` (seed 6), a fixed camera and fixed time, the same pack and options, the same resolution, VSync off.
- **A: Iris 1.10.8 on GL** (Sodium, no Polydium Vulkan) **vs B: Vulkan Iris on Polydium Vulkan.**
- **Measure:** average FPS and 1 % lows over 60 s after warm-up, and the CPU frame time. Read GPU time per stage from timestamp queries on Vulkan and `GL_TIME_ELAPSED` on GL.
- **Report** per pack, on every GPU tested. Where Vulkan Iris is slower, say so.

Where Vulkan Iris can realistically win, and what Phase 6 already does:
- Barriers are only emitted for real hazards (`LayoutTracker`), where GL validates state on every bind.
- One command buffer is recorded per frame, with pre-built pipelines and no per-draw shader rebinds.
- Uniforms are written once per pass into a ring buffer.
- Sodium's multi-draw is kept as `vkCmdDrawMultiIndexedEXT` or indirect draws.

Still to do for speed:
- a SPIR-V and pipeline cache, so packs don't recompile cold each time (build time is 0.3–3.4 s per pack today)
- pipeline libraries or pipeline-cache priming
- async pack compilation

## Verification still missing
- **Khronos validation layer.** Not installed on the test machine; it comes with the LunarG Vulkan SDK. Every phase should be re-run under it.
- **GPUs.** Only an NVIDIA RTX 5060 has been tested. AMD, Intel and integrated GPUs are untested.
