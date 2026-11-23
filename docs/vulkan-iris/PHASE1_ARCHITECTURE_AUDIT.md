# Vulkan Iris — Phase 1 Architecture Audit

Audit date: 2026-11-22. All facts below come from source checked out into `C:\Claude\VulkanIris`:

| Source | Ref | Path |
|---|---|---|
| Iris (fork base) | `IrisShaders/Iris` branch `1.21.11` @ `0a1fcaa` | `iris/` |
| Iris (newest) | `IrisShaders/Iris` branch `26.3-new` @ `654d2c2` (2026-11-21) | `_ref/iris-26.3/` |
| VulkanMod | `xCollateral/VulkanMod` branch `dev` (MC 1.21.11, v0.6.8) | `_ref/vulkanmod/` |
| Sodium | `CaffeineMC/sodium` branch `1.21.11/stable` | `_ref/sodium/` |
| Polydium | your `Polydium-1.4.14-dev.zip` | `_ref/polydium/` |

---

## 0. The three findings that change the plan

1. **Upstream Iris already has a real Vulkan backend.** On the `26.3-new` branch, Iris 1.12.1 has a
   `backend/RenderBackend` interface with two implementations: `opengl/GlRenderBackend` and
   `vulkan/VulkanRenderBackend`. The Vulkan side is 25 files and about 4,200 lines:
   - a shaderc GLSL→SPIR-V compiler with SPIRV-Cross reflection
   - a shader cache
   - graphics and compute pipelines, plus pipeline libraries
   - a sampler heap
   - synchronization
   - mipmap generation
   - world command recording

   It runs on **Mojang's own Vulkan backend** (`com.mojang.renderpearl.backend.vulkan`), which Java Edition ships
   from 26.2. Steps 2–9 of your plan already exist upstream for 26.3. Rewriting them from scratch would
   duplicate that work and make the fork harder to keep in sync.
2. **Polydium is the bridge to 1.21.11–26.1.2.** It backports Mojang's Vulkan backend to those versions as
   about 93 files in `org.polyfrost.polydium.vulkan`. When it runs on Vulkan it **clears the mixin lists of
   every mixin config whose package starts with `net.irisshaders.iris.`** (`IrisVulkanGate`). It then shows the
   message *"Iris cannot run when using Vulkan. Would you like to switch to OpenGL?"* Polydium is licensed
   **PolyForm Shield 1.0.0**, so its code cannot be copied. Runtime interop is fine.
3. **VulkanMod has no OpenGL context at all.** It creates the window with `GLFW_CLIENT_API = GLFW_NO_API`
   (`mixin/window/WindowMixin.java:62`) and replaces vanilla's chunk renderer with its own
   (`render/chunk/WorldRenderer`). Stock Iris makes raw `GL4xC.*` calls from 69 files, so it cannot run there.
   Iris also hard-depends on Sodium, and Sodium cannot run alongside VulkanMod.

## 1. Existing Iris architecture (1.21.11 branch)

- **Build:** Gradle 9.4 with Kotlin DSL, multiloader (`common`, `fabric`, `neoforge`), Loom 1.16.3, Java 21,
  MC 1.21.11, Fabric Loader 0.18.1, Fabric API 0.140.2. Iris 1.10.8. Sodium 0.8.14 is a local jar in
  `custom_sodium/`. Iris doesn't commit that jar; I copied your Sodium 0.8.14 jar there (same SHA-256 as your
  download). **The unmodified fork compiles** (`:fabric:compileJava`, exit 0).
- **Size:** 673 Java files in `common`.
- **Entry points:** `Iris.onEarlyInitialize`, `onRenderSystemInit` (calls `GL.getCapabilities()` straight away),
  `onLoadingComplete`.
- **Pipeline:** `pipeline/PipelineManager` builds an `IrisRenderingPipeline` (1,387 lines). That uses:
  - `CompositeRenderer` for the deferred, composite and composite1..N passes
  - `FinalPassRenderer`
  - `ShadowRenderer` (789 lines)
  - `ShadowCompositeRenderer`
  - `CustomTextureManager`
- **Shader-pack front end (backend-neutral):**
  - `shaderpack/*`: discovery, includes, option parsing, preprocessor, properties, ID maps, material maps,
    language files
  - `pipeline/transform/*`: `TransformPatcher` plus 14 transformers built on `glsl-transformer` 3.0.0-pre3. It
    parses each pack's GLSL into an AST, rewrites it to GLSL 330 core, and handles OptiFine's compatibility
    profile.
- **GL-bound back end:** `gl/*` (47 files) covers `GlFramebuffer`, `GlTexture`, `GlImage`, `GlSampler`,
  `ProgramBuilder`/`Program`, `ProgramUniforms`, `ShaderStorageBuffer`, blending, alpha test, and
  `IrisRenderSystem` (the raw GL wrapper).
- **Uniforms:** `uniforms/*` has 20 providers. Values are pushed as **loose `glUniform*` calls** looked up with
  `glGetUniformLocation`. Vulkan has no loose uniforms, so they must be packed into a UBO or push constants by
  the transformer.
- **Render targets:** `targets/RenderTargets`, `RenderTarget`, `BufferFlipper` (ping-pong), `ClearPass*`,
  `DepthTexture`.
- **Sodium integration:** 20 mixins in `compat/sodium/mixin`, including:
  - `MixinShaderChunkRenderer`, which swaps Sodium's chunk shader for the pack's `gbuffers_terrain`
  - `MixinGlRenderDevice`
  - `MixinChunkVertex`, which installs Iris's extended terrain vertex format
  - `MixinRenderSectionManagerShadow`, which runs the shadow pass over Sodium's section graph
- **Blaze3D usage:** Minecraft 1.21.11 already routes its own rendering through `GpuDevice`/`RenderPass`/
  `RenderPipeline`. Iris uses both those abstractions and raw `GlStateManager` (75 references) and `GlTexture`.

## 2. OpenGL-dependent systems (must be rewritten or abstracted)

| System | Files | Notes |
|---|---|---|
| `gl/*` objects | 47 | framebuffers, textures, images, samplers, programs, SSBOs |
| `IrisRenderSystem` users | 69 | raw GL from mixins, pipeline, uniforms, targets |
| Shader compile/link | `GlShader`, `ProgramBuilder`, `ProgramCreator` | GLSL source goes straight to the driver |
| Uniform upload | `ProgramUniforms`, `gl/uniform/*` | location-based loose uniforms |
| Blend/alpha/colour mask state | `gl/blending/*` | becomes pipeline state (or dynamic state) |
| Framebuffers | `GlFramebuffer`, `RenderTargets` | becomes dynamic-rendering attachments plus layout tracking |
| Depth copies | `DepthCopyStrategy` (`glCopyImageSubData`/blit) | becomes transfer commands plus barriers |
| Compute | `ComputeProgram` (`glDispatchCompute*`, `glMemoryBarrier`) | becomes compute pipelines plus explicit barriers |
| Sodium terrain hook | `MixinShaderChunkRenderer`, `MixinGlRenderDevice` | Sodium itself is GL-only on 1.21.11 |

## 3. Reusable unchanged (backend-neutral)

Shader-pack discovery, zip handling, includes, `#define` options and the options GUI, `shaders.properties`,
ID and material maps, language files, the GLSL preprocessor, the `glsl-transformer` AST passes (they still need
a Vulkan-GLSL output stage), uniform *value* providers (camera, time, celestial, fog, biome, custom uniforms),
shadow matrices and frustum culling, the pass ordering in `PipelineManager`/`CompositeRenderer`, the
config/GUI/keybinds, and the PBR texture loading logic.

## 4. Must be rewritten for Vulkan

Program objects become `VkPipeline`s with explicit layouts. Uniforms become a UBO block, push constants and
descriptor sets. Framebuffers become attachments with image-layout tracking. Every implicit GL sync point needs
an explicit barrier. Blend/depth/cull state goes into pipeline creation (or `extended_dynamic_state3`). The
`#version 330` output gains Vulkan GLSL rules:
- explicit `layout(location/binding/set)`
- no loose uniforms
- no `gl_FragData`
- separate sampler/texture handling

3-channel formats need promoting (verified on your GPU: RGB8/16/16F/32F are **not renderable**). Terrain needs
whatever renders chunks on the Vulkan device in use: Mojang's or Polydium's renderer with Sodium's Vulkan path,
or VulkanMod's own `WorldRenderer`.

## 5. Vulkan architecture proposal

**Never create a second VkDevice in-game.** One of the following always owns the device and swapchain already:
- Mojang's Vulkan (26.2+)
- Polydium (1.21.11–26.1.2)
- VulkanMod

Vulkan Iris **borrows** that owner's device (`VulkanContext.borrow(...)`, done). A device of its own exists only
for headless tests and `gradlew :vulkan:probe`.

```
            shader-pack front end (unchanged Iris: packs, options, properties, transformer AST, uniforms values)
                                              │
                               RenderBackend  (adopt upstream Iris 26.3's interface)
             ┌───────────────────┬────────────┴─────────────┬─────────────────────────┐
     GlRenderBackend     Mojang-Vulkan backend         Polydium adapter           VulkanMod adapter
     (game in GL mode)   (26.2–26.3; upstream code)    (1.21.11–26.1.2; port of   (1.21.11; own chunk
                                                        upstream backend onto      renderer, no Sodium)
                                                        Polydium's backport)
                                              │
                          dev.vulkaniris.vk core (this repo, Phase 2): capabilities, vendor profile,
                          diagnostics, error handling, GPU self-test — shared by every Vulkan adapter
```

The OpenGL backend is **not a silent fallback**. It runs only when the game itself runs on OpenGL. If the game
is on Vulkan and a pass can't run there, Vulkan Iris reports exactly what failed. It does not switch APIs quietly.

**Rejected: own Vulkan device inside a GL game with interop** (`VK_KHR_external_memory_win32` is present on
your GPU). Every frame would need a cross-API copy and a semaphore handoff. That would very likely be *slower*
than plain Iris, which contradicts the performance goal. It is kept as an "evaluate" item only.

## 6. Dependency recommendations

- `org.lwjgl:lwjgl-vulkan:3.3.3`, the same version VulkanMod pins, so Fabric jar-in-jar loads one copy.
- `lwjgl-shaderc` + `lwjgl-spvc` 3.3.3 with natives for GLSL→SPIR-V and reflection. Upstream 26.3 and VulkanMod
  both use these.
- `lwjgl-vma` for allocation (Phase 4). VulkanMod already ships VMA; under Mojang/Polydium, use the owner's
  allocator where it is exposed.
- No validation layer is installed on your machine: the Vulkan SDK is absent and only NVIDIA/Steam layers
  exist. Installing the LunarG Vulkan SDK enables `-Dvulkaniris.validation=true` / `gradlew :vulkan:probe -Pvalidation`.

## 7. Minecraft / loader / client compatibility

| Target | Vulkan owner | Realistic support |
|---|---|---|
| 1.21 – 1.21.10 | VulkanMod (its `1.21` branch, older MCs) | GL mode = upstream Iris behaviour. Vulkan only via a VulkanMod adapter per version |
| 1.21.11 | Polydium **or** VulkanMod | primary target for the Vulkan adapters |
| 26.1 – 26.1.2 | Polydium | Polydium adapter |
| 26.2 – 26.3 | Mojang | upstream Iris Vulkan backend plus Vulkan Iris extras |

Each MC version is a separate build. Iris itself keeps one branch per version, and "1.21–26.3" means roughly
8 or more maintained builds. Sodium has `26.1.2/stable` and `26.2/stable` branches.

**Clients.**
- **Lunar Client:** your `~/.lunarclient/profiles/1.21/mods/fabric-1.21.11/` folder already loads Iris 1.10.8,
  Sodium 0.8.14 and DawnPad. That makes it a Fabric environment, and a well-behaved Fabric mod works there.
- **OneClient (Polyfrost):** installed and Fabric-based.
- **NoRisk:** Fabric-based.
- **Dawn:** not verified. I found no Dawn install to inspect.

Two compatibility facts from the code:
- The fork must declare `"provides": ["iris"]`. Sodium, Distant Horizons and Polydium check for mod id `iris`.
- **Polydium strips all `net.irisshaders.iris.*` mixins on Vulkan.** Vulkan Iris therefore needs its Vulkan-mode
  mixins in its own package (`dev.vulkaniris.*`, started) **and** Polydium must stop treating it as stock Iris.
  The cleanest fix is a detection hook agreed with Polyfrost. Otherwise we have to work around their gate.

## 8. Roadmap

1. ✅ Phase 1 audit (this document).
2. ✅ Phase 2 Vulkan core + diagnostics (below), verified on the RTX 5060.
3. Phase 3: **decide the base** (see §10) and get the first in-game Vulkan output through the borrowed device.
4. Phase 4: resources (VMA, images, layout tracker, descriptor strategy).
5. Phase 5: GLSL→SPIR-V on top of the existing transformer.
6. Phases 6–9: passes (gbuffers → shadow → deferred/composite/final).
7. Phase 10: pack testing, the compatibility matrix and benchmarks.
8. Release work: Modrinth/CurseForge/GitHub pages and licence (see §11).

## 9. Biggest technical risks

1. **Polydium's Iris gate.** It removes our mixins today, and the code isn't ours to change.
2. **VulkanMod has no stable API.** Everything is `public static` internals, which can break between releases.
3. **Sodium terrain on Vulkan:** on 1.21.11 this depends on Polydium's Sodium Vulkan mixins
   (`perf/sodium/vulkan/*`).
4. **Shader-pack GLSL quirks:** loose uniforms, `gl_FragData`, 3-channel formats, `texture2D`. Every pack hits
   different ones.
5. **Performance:** Vulkan is not automatically faster. Gains come from pipeline libraries, fewer state changes
   and better sync, and every claim needs a benchmark. **None is claimed yet.**
6. **Maintenance load** across about 8 MC versions times 3 Vulkan owners.

## 10. Proposed first working prototype — needs your decision

**Recommended:** make **26.3** (upstream `26.3-new`) the development base, because the Vulkan backend already
exists there. Phase 3 there means:
- run a real pack on Mojang's Vulkan
- add the `dev.vulkaniris.vk` diagnostics layer
- measure FPS against stock Iris 1.12.1

Then port down to 1.21.11 on Polydium.
**Alternative:** stay on 1.21.11, which you play on today. Phase 3 is then the much larger job of backporting
upstream's `RenderBackend` abstraction onto the 1.21.11 code before anything renders.

## 11. Release / licence constraints (for the final stage)

Iris is **LGPL-3.0-only**, and so is VulkanMod. A fork of Iris **must stay LGPL-3.0** for all Iris-derived code.
A custom "free to distribute and modify, credit required" licence cannot replace it. LGPL already allows
redistribution and modification and requires keeping the notices, so it covers most of what you asked for. Brand
new, separable code such as `dev.vulkaniris.vk` *could* be dual-licensed under your own licence. Polydium code
(PolyForm Shield) must not be copied in at all. Modrinth/CurseForge/GitHub publishing needs your go-ahead at the
time.
