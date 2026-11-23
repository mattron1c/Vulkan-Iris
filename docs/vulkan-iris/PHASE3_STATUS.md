# Vulkan Iris — Phase 3 status (first real Vulkan render inside Minecraft)

Base decision (user, 2026-11-23): **stay on Minecraft 1.21.11.** The first Vulkan owner is **Polydium 1.4.21**,
which backports Mojang's Vulkan backend. VulkanMod comes later.

## Result: PASS (verified 2026-11-23, RTX 5060, driver 610.88)

Production Minecraft 1.21.11 was launched with a real mods folder: Vulkan Iris + Sodium 0.8.14 + Polydium 1.4.21 +
OneConfig/PolyCompose/Kotlin/Fabric API. Polydium was on Vulkan. Log:

```
Vulkan on NVIDIA GeForce RTX 5060 using dynamic rendering and push descriptors      <- Polydium
[Vulkan Iris] Borrowing Vulkan device from POLYDIUM: NVIDIA GeForce RTX 5060 (Vulkan 1.4.341)
[Vulkan Iris] Borrowed device: ..., queue family 0, main target 854x480 VkFormat 37
[Vulkan Iris] Recorded into Polydium frame command buffer, submission #121 at frame 120
[Vulkan Iris] GPU readback centre (128,128,64,255) (expected ~(128,128,64,255)) left (2,...) right (253,...)
[Vulkan Iris] Pixel check: PASS
[Vulkan Iris] FIRST LIGHT PASS
[Vulkan Iris] Releasing borrowed Vulkan device (owned by POLYDIUM; nothing destroyed)
```

Minecraft's own screenshot of the frame: `iris/fabric/run-prod/screenshots/vulkaniris-firstlight.png`. It shows
the gradient quad composited over the title screen. Full report: `iris/fabric/run-prod/vulkaniris-firstlight.txt`.

What this proves:
- GLSL compiled at runtime to SPIR-V (shaderc)
- Vulkan Iris's own `VkPipeline` (dynamic rendering, push constants)
- recorded into Polydium's frame command buffer
- drawn into Minecraft's main render target on the **one** Vulkan device the game uses (borrowed, not duplicated)
- read back from the GPU and checked
- presented by the game

There is no OpenGL anywhere in the path.

## What was added

- Core (`:vulkan`, no MC deps):
  - `ShaderCompiler`: shaderc GLSL→SPIR-V. Errors include the file, the stage and the surrounding source lines;
    results are cached in memory.
  - `FirstLightPass`: the pipeline described above.
  - `ImageReadback`: GPU→CPU pixel checks.
  - Headless test `FirstLightPassTest` reproduces Polydium's conditions (GENERAL layout, full barriers). **8/8
    core tests pass.**
- Fabric (`dev.vulkaniris.fabric`, kept outside `net.irisshaders.iris.*` because Polydium strips that package on
  Vulkan):
  - `PolydiumAccess` is the only class touching Polydium (runtime API use only; PolyForm Shield).
  - `FirstLight`: overlay mode `-Dvulkaniris.firstlight=true`, self-test mode `...firstlight.selftest=true`.
    Off by default.
  - `MixinGameRendererFirstLight`: hook at `GameRenderer.render` TAIL.
  - `VulkanIrisClient`: client entrypoint.
- Mod identity: id `vulkaniris`, name "Vulkan Iris", `provides: ["iris"]`. The jar bundles the core plus
  lwjgl-vulkan/shaderc 3.3.3 (jar-in-jar).

## Facts learned about Polydium 1.4.21 (from its bytecode and this run)

- `VulkanDevice.active` / `VulkanActive.active` hold the live device. The device has one command encoder;
  `commandBuffer()` is the frame command buffer and `currentSubmitIndex()` / `awaitSubmitCompletion()` track it.
- Every texture lives permanently in `VK_IMAGE_LAYOUT_GENERAL`. Passes are separated by a full ALL_COMMANDS memory
  barrier (`VulkanCommandEncoder.memoryBarrier`).
- **Render targets keep OpenGL's bottom-up row order.** Pixel row 16 in the image is 16 px above the *bottom* of
  the screen. All future passes and readbacks must account for this.

## Toolchain changes (required, not optional)

- Fabric Loom 1.16.3 → **1.19.4**: Polydium/OneConfig are built with Loom 1.19.4 and older Loom refuses them.
- Gradle wrapper 9.4.0 → **9.8.2**, required by Loom 1.19.4.
- **Gradle must now run on JDK 25** (`JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`). Mod code still compiles for Java 21
  via the toolchain.
- Dev Fabric Loader 0.18.1 → 0.19.5, required by OneConfig/PolyCompose.
- `gradlew :fabric:runClient -Ppolydium` (dev runtime) **does not work**: PolyCompose nests Compose UI two jar levels
  deep, which Loom's dev runtime doesn't unpack. Use `gradlew :fabric:prodFirstLight` instead. It launches production
  Minecraft with a real mods folder and quits by itself.

## Not done / not verified

- Shader packs still don't run on Vulkan. Polydium strips Iris's own mixins, so Iris is inert when Polydium is on
  Vulkan, exactly as with stock Iris.
- Only the title screen was tested, not in-world.
- Only NVIDIA was tested. No validation layer (no Vulkan SDK).
- The test quad isn't drawn in normal play.
- No performance gains yet, and none are claimed.

## Next milestone (Phase 4: resources)

Vulkan Iris-owned render targets (`colortex0..15`, depth copies) created through Polydium's VMA allocator, an
image-layout tracker that cooperates with Polydium's always-GENERAL scheme, and a descriptor strategy. The proof
point is one Iris-style composite pass that samples Minecraft's main colour and depth and writes the result back.
The Polydium gate also needs a decision: ask Polyfrost for a hook, or move Iris's Vulkan-mode mixins out of
`net.irisshaders.iris.*`.
