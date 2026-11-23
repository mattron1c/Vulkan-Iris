# Phase 5 — Shader packs to SPIR-V: status

**Result: 275 / 275 programs from 10 real shader packs compile to SPIR-V and are accepted by the driver**
(`vkCreateShaderModule` on Polydium's device, RTX 5060, NVIDIA 610.88, Minecraft 1.21.11).

| Pack | Programs | Result | Time |
|---|---|---|---|
| Bliss 2.1.2 (Chocapic13 edit) | 35 | all pass | 2.8 s |
| BSL 10.1.3 | 26 | all pass | 0.9 s |
| Complementary Reimagined r5.9.3 | 30 | all pass | 4.2 s |
| Complementary Unbound r5.9.3 | 30 | all pass | 4.3 s |
| MakeUp UltraFast 9.4b | 25 | all pass | 0.6 s |
| Mellow 3.5 | 26 | all pass | 0.9 s |
| miniature 2.19 | 12 | all pass | 0.2 s |
| Noble 1.9.9 | 53 | all pass | 2.4 s |
| Sildur's Vibrant 2.02 Extreme | 15 | all pass | 0.3 s |
| Solas 3.7b | 23 | all pass | 1.6 s |

Times are cold (no SPIR-V disk cache yet) and include Iris's own patching.

## Pipeline
Pack → Iris's own loader (`ShaderPack`, `ProgramSet`) → Iris `TransformPatcher` (composite / sodium / vanilla paths,
with `IrisLimits.VK_CONFORMANCE` on) → `VulkanGlslTranslator` → shaderc (Vulkan 1.2 target, optimised) → `vkCreateShaderModule`.

`VulkanGlslTranslator` (`common/.../pipeline/transform/vulkan`), adapted from upstream Iris 26.3-new (LGPL, credited):
- loose uniforms → one std140 block `iris_Uniforms` (set 0, binding 0), returned as a `ShaderResourceLayout` with offsets;
- samplers / images / UBOs / SSBOs → set 0, one binding per name shared by all stages;
- own location assignment: one name→location map across all stages, sized by type (matrices, arrays, dvec3/4) —
  Iris's VK-conformance pass gives every varying one slot, which overlapped for matrices; vertex attributes get fixed
  locations (Blaze3D/Iris names first) returned to the caller for the vertex input state;
- clip-space z remap `z = (z + w) / 2` in the last pre-raster stage (matches GL depth values); no y flip (Polydium stores GL row order);
- `#version 450 core`, `gl_VertexID/gl_InstanceID`, null-initialised globals, no `const` around `transpose()`;
- Vulkan-only fixes found by this test: precision qualifiers stripped (miniature), user functions named like builtins
  renamed (miniature), `GL_NV_gpu_shader5` small types emulated at 32 bits (Noble assumes it on any NVIDIA GPU),
  read-only `gl_FragDepth` → `gl_FragCoord.z` (Noble, Bliss).

Other changes needed:
- Iris never initialises on Polydium/Vulkan: Polydium strips every `net.irisshaders.iris.*` mixin, including the one
  calling `Iris.onEarlyInitialize`. Added `Iris.initializeForVulkan()` (version + config, no keybinds/GL). Phase 6 must
  drive Iris from Vulkan Iris's own hooks.
- `StandardMacros` queried the GL version/extensions: on a Vulkan backend it now reports GLSL 460 and only extension
  macros the backend really provides.
- Upstream Iris bug fixed: `VanillaTransformer` renamed `gl_ModelViewMatrix` to a dotted name, which glsl-transformer
  rejects, for every non-chunk vanilla program.

Run it: `gradlew :fabric:prodPhase5` (packs in `fabric/run-prod/shaderpacks`, report `vulkaniris-phase5.txt`).

## Not verified / limits
- Compiling ≠ rendering correctly. Nothing here has drawn a pack's output yet (Phase 6).
- Compute programs (`.csh`) are not in this test yet.
- All uniforms are treated as Iris-registered (initialisers dropped); Phase 6 passes the real uniform set.
- Default pack options only.
- No validation layer on this machine; the driver accepting a module doesn't prove the SPIR-V is fully valid.
- The 32-bit emulation of NV small types changes behaviour only for code relying on 8/16-bit overflow.
