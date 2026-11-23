# Vulkan Iris — Phase 2 status (Vulkan initialisation + diagnostics)

Module: `iris/vulkan` (Gradle subproject `:vulkan`, package `dev.vulkaniris.vk`). It is plain Java 21 + LWJGL
3.3.3 with no Minecraft dependency, so it builds and tests directly against the GPU.

## What was built (implemented and verified)

| Class | Does |
|---|---|
| `VulkanContext` | Owned mode (tests/probe) creates the instance, picks a device and creates a logical device and graphics queue. It enables only the features the hardware has and records them. Borrowed mode (in-game) wraps Mojang's, Polydium's or VulkanMod's device and destroys nothing on close |
| `DeviceSelector` | Enumerates GPUs and rejects ones missing dynamicRendering / synchronization2 / timelineSemaphore / independentBlend, giving the reasons. Scores discrete > integrated. `-Dvulkaniris.device=<index or name>` overrides the choice |
| `GpuCapabilities` | Identity, vendor-decoded driver version, limits, subgroup size, 45 core features, queues, memory heaps with live VRAM budget, 280 extensions, and render-target support for 21 Iris formats |
| `VendorFeatureCatalog` | 37 NVIDIA / AMD / Intel / cross-vendor extensions, each with its purpose and the phase planned to use it |
| `HardwareProfile` | Tier (LOW = integrated / small VRAM), unified memory, ReBAR/SAM, async queues and vendor notes. **Computed and reported, not applied to rendering yet** |
| `DebugMessenger` | VK_EXT_debug_utils. Counts errors and warnings, keeps recent messages, never swallows anything |
| `VkCheck` | Every VkResult is checked and named. Device loss gets its own `VulkanDeviceLostException`, and `VulkanContext.markLost` stops further submits |
| `GpuSelfTest` | Real GPU work read back on the CPU: a 64 KiB `vkCmdFillBuffer`, then a 64×64 RGBA16F image going UNDEFINED→TRANSFER_DST, clear, TRANSFER_SRC, copy and half-float verify. GPU time comes from timestamps |
| `DiagnosticsReport` / `VulkanProbe` | Full text report. `gradlew :vulkan:probe` writes `vulkan/build/vulkaniris-diagnostics.txt` |

## Verified on this machine (RTX 5060, driver 610.88, Vulkan 1.4.341 loader)

- `gradlew :vulkan:test`: **5/5 pass**.
  - pure-logic tests
  - full init, self-test and clean shutdown
  - 3× create/destroy cycle
  - a bad device override fails loudly
- Self-test: buffer fill **PASS**, RGBA16F clear + layout transitions + readback **PASS**, about 11 µs of GPU time.
- Debug messenger: **0 errors, 0 warnings**. Validation layer **not** active: the Vulkan SDK isn't installed.
- `gradlew :fabric:compileJava` still compiles with the new subproject included.
- Found and fixed while testing:
  - LWJGL's 64 KiB default MemoryStack overflows while loading NVIDIA's instance function table. Tests and probe
    now use 256 KiB, the same value VulkanMod uses, and there's a clear error message if it happens anyway.
  - ReBAR was misdetected as unified memory.

## Not done yet (honestly)

- **Nothing renders in Minecraft through Vulkan yet.** Phase 2 is init and diagnostics only.
- The in-game borrow path isn't wired to a Vulkan owner, and the jar isn't bundled into the fabric mod yet. Both
  wait on the base-version decision (audit §10).
- No validation-layer run (needs the Vulkan SDK).
- Tested only on NVIDIA. The AMD/Intel/iGPU code paths are written but have not been run on that hardware.
- No performance gains exist or are claimed.

## Run it

```
cd C:\Claude\VulkanIris\iris
gradlew :vulkan:test
gradlew :vulkan:probe            # add -Pvalidation once the Vulkan SDK is installed
```
(JAVA_HOME = JDK 21.)
