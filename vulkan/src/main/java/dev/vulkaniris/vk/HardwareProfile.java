package dev.vulkaniris.vk;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-GPU tuning decisions derived from {@link GpuCapabilities}. Later phases read these instead of sprinkling
 * vendor checks through the renderer. Phase 2 only computes and reports them — none is applied to rendering yet,
 * and none is a performance claim.
 */
public record HardwareProfile(
	Tier tier,
	boolean unifiedMemory,
	boolean resizableBar,
	boolean asyncTransfer,
	boolean asyncCompute,
	int preferredComputeWorkgroup,
	int suggestedShadowResolution,
	boolean pipelineLibraries,
	List<String> notes
) {
	public enum Tier {
		/** Integrated GPU or < 4 GiB VRAM: favour memory savings and fewer passes. */
		LOW,
		/** Mainstream discrete (~4-11 GB). */
		MID,
		/** >= ~12 GB discrete. */
		HIGH
	}

	public static HardwareProfile of(GpuCapabilities c) {
		List<String> notes = new ArrayList<>();
		// Drivers report a little less than the marketing size (an "8 GB" card shows ~7.7 GiB), so thresholds are in MiB.
		long vramMiB = c.deviceLocalBytes() >> 20;
		boolean uma = c.isUnifiedMemory();
		Tier tier = uma || vramMiB < 3_500 ? Tier.LOW : vramMiB >= 11_500 ? Tier.HIGH : Tier.MID;

		boolean rebar = c.hasResizableBar();
		if (uma) notes.add("Unified memory: uploads can be written in place, no staging copy needed");
		else if (rebar) notes.add("Resizable BAR / SAM active: per-frame uniforms can live in VRAM written directly by the CPU");
		else notes.add("No ReBAR: per-frame data goes through a host-visible ring + copy (enable ReBAR/SAM in BIOS to change)");

		boolean asyncTransfer = c.dedicatedTransferFamily().isPresent();
		boolean asyncCompute = c.asyncComputeFamily().isPresent();
		if (asyncTransfer) notes.add("Dedicated transfer queue: texture/shader-pack uploads can overlap rendering");

		// Wave/warp size: NVIDIA & Intel 32 (Intel can also run 8/16), AMD 64 on GCN, 32/64 on RDNA.
		int wg = Math.max(c.subgroupSize, 32) * 2;

		int shadow = switch (tier) {
			case LOW -> 1024;
			case MID -> 2048;
			case HIGH -> 4096;
		};

		boolean gpl = c.hasExtension("VK_EXT_graphics_pipeline_library") && c.hasExtension("VK_KHR_pipeline_library");
		if (gpl) notes.add("Graphics pipeline libraries available: shader-pack pipelines can be linked instead of fully compiled");

		switch (c.vendor) {
			case NVIDIA -> {
				notes.add("NVIDIA: prefer few large submits; descriptor indexing is cheap");
				if (c.hasExtension("VK_NV_low_latency2")) notes.add("NVIDIA Reflex (VK_NV_low_latency2) exposed by driver");
				notes.add("DLSS needs the NVIDIA Streamline SDK + licence — not bundled; not implemented");
			}
			case AMD -> {
				notes.add("AMD: avoid VK_IMAGE_LAYOUT_GENERAL on render targets (disables DCC compression)");
				if (c.hasExtension("VK_AMD_anti_lag")) notes.add("AMD Anti-Lag (VK_AMD_anti_lag) exposed by driver");
				notes.add("FSR is an open shader library (MIT), a candidate for a later upscaling pass — not implemented");
			}
			case INTEL -> {
				notes.add("Intel: keep render-target count low on Xe-LP iGPUs; Arc benefits from ReBAR heavily");
				notes.add("XeSS needs Intel's SDK — not bundled; not implemented");
			}
			default -> {
			}
		}
		if (tier == Tier.LOW) notes.add("LOW tier: suggest shader-pack profile LOW/MEDIUM and shadowMapResolution <= 1024");

		return new HardwareProfile(tier, uma, rebar, asyncTransfer, asyncCompute, wg, shadow, gpl, List.copyOf(notes));
	}
}
