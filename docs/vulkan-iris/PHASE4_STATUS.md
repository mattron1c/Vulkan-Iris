# Phase 4 — GPU resources: status

**Result: PASS** — headless (owned device) and in production Minecraft 1.21.11 on Polydium 1.4.21's Vulkan device.

## What was built (`iris/vulkan`, package `dev.vulkaniris.vk`, no Minecraft dependency)

| Class | Role |
|---|---|
| `GpuAllocator` | VMA (LWJGL 3.3.3). In-game it **borrows Polydium's VMA handle**, so our images share the owner's heaps and budget instead of a second allocator. Tracks bytes and live images Vulkan Iris allocated (leak check on close). Images use dedicated allocations for render targets; host-visible buffers are persistently mapped (ReBAR/UMA put them in device-local memory). |
| `GpuImage` / `GpuBuffer` | Image + view + sync state. `GpuImage.external(...)` wraps images the owner keeps (Minecraft's main colour/depth on Polydium): pinned layout (GENERAL), never transitioned or destroyed by us. |
| `LayoutTracker` | Use-based barriers (`COLOR_ATTACHMENT`, `SAMPLED_FRAGMENT`, `TRANSFER_SRC`, `STORAGE_*`, …), batched into one `vkCmdPipelineBarrier` per pass boundary. Read→read on the same layout is elided; `discard` turns old layout into UNDEFINED. External images only get memory dependencies. Combined depth/stencil formats barrier both aspects. Refuses the same image twice in one batch. |
| `FrameClock` | "Which submission is recording / is N complete". In-game = Polydium's own submit counter (no added fences or waits). `FrameClock.Manual` for tests. |
| `DeferredDestroyer` | Frees objects once the submission that recorded them completed; `collect()` once per frame, never blocks. |
| `DescriptorRing` | One descriptor pool per submission, reset wholesale when that submission completes (no per-set frees). Handles OUT_OF_POOL_MEMORY / FRAGMENTED by rotating. |
| `SamplerCache` | Immutable samplers keyed by filter/address/mips/shadow-compare (`NEAREST_CLAMP`, `LINEAR_CLAMP`, `LINEAR_REPEAT_MIPS`, `SHADOW_PCF`). |
| `RenderTargetSet` | Iris `colortex0..15`: main/alt pairs with ping-pong `flip`, resize via deferred destroy, `generation` counter for stale descriptors. Formats the GPU can't render to are promoted 3→4 channel (RGB8→RGBA8, RGB16F→RGBA16F, RGB32F→RGBA32F, …), others fail loudly. STORAGE usage added only where the format supports it. |
| `FullscreenPass` | Shape of every Iris composite/deferred/final program: N combined-image-sampler inputs, M colour outputs, push constants, dynamic rendering, one-triangle draw. UV has GL meaning on Polydium's bottom-up targets (no flips). |
| `OneShotCommands` | Record/submit/wait for owned-device work (tests, probe, startup uploads). |
| `ResourceSelfTest` | End-to-end check used by both tests below. |
| `ImageReadback` | Now also copies a single aspect (depth) and decodes float32. |

## Verification

### Headless — `gradlew :vulkan:test` (13/13 pass, RTX 5060, NVIDIA 610.88)
- `compositeAndFinalThroughOwnedAndExternalImagesSurviveResize`: main colour/depth faked exactly like Polydium's (GENERAL, external),
  depth filled with a per-pixel ramp. composite (main colour+depth → colortex0 inverted, colortex1 R32F depth) then final
  (colortex0 → main colour, left half only). At 640×360, 1280×720 and 333×197 (two resizes): 2048 px inverted, 2048 px untouched,
  4096 depth texels bit-exact (4092 distinct values). Old targets freed after their submission; zero live images at the end; 0 messenger errors.
- `descriptorPoolsAreRecycledNotLeaked`: 300 frames × 40 sets = 12,000 sets from **1** pool (299 resets).
- `threeChannelFormatsArePromotedWhenNotRenderable`: RGB16F → RGBA16F (97), RGB8 → RGBA8 (37) on this GPU.
- `destroysOnlyAfterItsSubmissionCompletes`: ordering of deferred destroys.

### In-game — `gradlew :fabric:prodPhase4` (production MC 1.21.11 + Sodium 0.8.14 + Polydium 1.4.21 on Vulkan)
Report: `iris/fabric/run-prod/vulkaniris-phase4.txt`, log `_ref/phase4-run1.log`.
- Borrowed Polydium's device **and VMA allocator**; colortex0/1 (854×480) = 6912 KiB from Polydium's VMA.
- Main colour VkFormat 37 (RGBA8), depth 126 (D32_SFLOAT), both sampled directly (Blaze3D creates them with usage 15 = sampled + copy + attachment).
- Round 0 (submission #121) and round 1 (#122, after resizing targets twice while frames were in flight): both PASS
  — 2048 inverted, 2048 untouched, 4096 depth texels bit-exact.
- Shutdown: 6 deferred destroys run, 0 live images, 20 barriers in 8 batches, 4 descriptor sets from 1 pool.

## Known limits (honest)
- In-game the test runs on the title screen, so **Minecraft's depth is uniformly 1.0** there; varied depth is verified
  only headless. A world-loaded run comes with Phase 6 (gbuffers), where real depth matters.
- No validation layer on this machine (no Vulkan SDK) — correctness checked by readbacks and the debug messenger only.
- Barrier elision counted 0 in these tests (no read→read sequences); it will be measured with real pack pass graphs.
- Barriers use sync1 `vkCmdPipelineBarrier`; sync2 is required by device selection but not used yet. Revisit when
  profiling shows barrier cost.
- No performance claims: nothing here is benchmarked against Iris yet (Phase 9).

## Decisions
- Borrow the owner's VMA rather than create our own: one allocator per device, shared budget; Polydium's VMA is LWJGL 3.3.3, the same build we bundle.
- Our images use optimal layouts; owner images stay GENERAL because Polydium assumes that everywhere.
- Own depth-aspect view of Polydium's depth image (its view may include stencil), recreated if the image changes.
- 3-channel formats promote rather than fail, matching what GL drivers do silently for packs.
