package dev.vulkaniris.vk;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Human-readable dump of a {@link VulkanContext}: what the hardware has, what was enabled, what the self-test saw. */
public final class DiagnosticsReport {
	private DiagnosticsReport() {
	}

	public static String build(VulkanContext ctx, GpuSelfTest.Result selfTest) {
		GpuCapabilities c = ctx.capabilities();
		HardwareProfile hp = HardwareProfile.of(c);
		StringBuilder s = new StringBuilder(8192);
		line(s, "==================== Vulkan Iris diagnostics ====================");
		line(s, "Generated:        " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		line(s, "Java:             " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		line(s, "OS:               " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
		line(s, "Device owner:     " + ctx.owner());
		line(s, "Instance API:     " + GpuCapabilities.apiString(ctx.instanceApiVersion()));
		line(s, "Validation:       " + (ctx.validationActive() ? "ACTIVE (VK_LAYER_KHRONOS_validation)" :
			ctx.owner() == VulkanContext.Owner.VULKAN_IRIS ? "off (install the Vulkan SDK and pass -Dvulkaniris.validation=true)" : "owner-controlled"));
		if (!ctx.instanceLayers().isEmpty()) line(s, "Layers:           " + String.join(", ", ctx.instanceLayers()));

		if (!ctx.candidates().isEmpty()) {
			line(s, "");
			line(s, "-- Physical devices");
			for (DeviceSelector.Candidate cand : ctx.candidates()) {
				line(s, "  GPU" + cand.index() + "  " + cand.caps().name + "  [" + GpuCapabilities.deviceTypeName(cand.caps().deviceType) + "]  "
					+ (cand.suitable() ? "score " + cand.score() : "rejected: " + String.join(", ", cand.rejections()))
					+ (cand.device().address() == ctx.physicalDevice().address() ? "  <== selected" : ""));
			}
		}

		line(s, "");
		line(s, "-- Selected device");
		line(s, "Name:             " + c.name);
		line(s, "Vendor:           " + c.vendor.displayName + String.format(" (0x%04X), device 0x%04X", c.vendorId, c.deviceId));
		line(s, "Type:             " + GpuCapabilities.deviceTypeName(c.deviceType));
		line(s, "Vulkan:           " + GpuCapabilities.apiString(c.apiVersion));
		line(s, "Driver:           " + c.driverVersion + (c.driverName.isEmpty() ? "" : "  (" + c.driverName + " " + c.driverInfo + ")"));
		line(s, "Device UUID:      " + c.deviceUuid);
		line(s, "Subgroup size:    " + c.subgroupSize + String.format("  (ops 0x%X, stages 0x%X)", c.subgroupOperations, c.subgroupStages));

		line(s, "");
		line(s, "-- Memory");
		for (GpuCapabilities.MemoryHeap h : c.memoryHeaps) {
			line(s, String.format("  heap %d: %,8d MiB  %s%s%s", h.index(), h.size() >> 20,
				h.deviceLocal() ? "DEVICE_LOCAL " : "system ", h.hostVisibleType() ? "host-visible " : "",
				h.budget() >= 0 ? String.format(" budget %,d MiB, used %,d MiB", h.budget() >> 20, h.usage() >> 20) : ""));
		}
		line(s, "  unified memory: " + c.isUnifiedMemory() + ", resizable BAR/SAM: " + c.hasResizableBar());

		line(s, "");
		line(s, "-- Queues");
		for (GpuCapabilities.QueueFamily q : c.queueFamilies) {
			line(s, "  family " + q.index() + ": x" + q.count() + "  " + (q.graphics() ? "GRAPHICS " : "") + (q.compute() ? "COMPUTE " : "")
				+ (q.transfer() ? "TRANSFER " : "") + " timestamps=" + q.timestampValidBits() + "b"
				+ (q.index() == ctx.graphicsQueueFamily() ? "  <== in use" : ""));
		}

		line(s, "");
		line(s, "-- Limits relevant to shader packs");
		line(s, "  max 2D image:            " + c.maxImageDimension2D + " (shadowMapResolution ceiling)");
		line(s, "  color attachments:       " + c.maxColorAttachments + " (Iris draws to up to 16 colortex; >8 per pass needs splitting)");
		line(s, "  push constants:          " + c.maxPushConstantsSize + " bytes");
		line(s, "  descriptor sets:         " + c.maxBoundDescriptorSets);
		line(s, "  sampled images / stage:  " + c.maxPerStageDescriptorSampledImages);
		line(s, "  storage images / stage:  " + c.maxPerStageDescriptorStorageImages);
		line(s, "  UBO range:               " + c.maxUniformBufferRange);
		line(s, "  SSBO range:              " + Integer.toUnsignedString(c.maxStorageBufferRange));
		line(s, "  max anisotropy:          " + c.maxSamplerAnisotropy);

		line(s, "");
		line(s, "-- Core features (supported / enabled)");
		for (Map.Entry<String, Boolean> e : c.features.entrySet()) {
			String enabled = ctx.owner() == VulkanContext.Owner.VULKAN_IRIS ? (ctx.enabledFeatures().contains(e.getKey()) ? "  enabled" : "") : "";
			line(s, String.format("  %-44s %s%s", e.getKey(), e.getValue() ? "yes" : "NO", enabled));
		}

		line(s, "");
		line(s, "-- Render-target formats (optimal tiling)   S=sampled F=linear-filter C=color-attach B=blend D=depth-attach W=storage");
		for (GpuCapabilities.FormatSupport f : c.formats) {
			String flags = (f.sampled() ? "S" : "-") + (f.linearFilter() ? "F" : "-") + (f.colorAttachment() ? "C" : "-")
				+ (f.blend() ? "B" : "-") + (f.depthAttachment() ? "D" : "-") + (f.storage() ? "W" : "-");
			String warn = f.name().contains("3-channel") && !f.colorAttachment() ? "   -> not renderable: Vulkan Iris must promote to the 4-channel format" : "";
			line(s, String.format("  %-20s %s%s", f.name(), flags, warn));
		}

		line(s, "");
		line(s, "-- Vendor & optional extensions");
		for (VendorFeatureCatalog.Entry e : VendorFeatureCatalog.ENTRIES) {
			boolean has = c.hasExtension(e.extension());
			if (e.scope() != VendorFeatureCatalog.Scope.CROSS_VENDOR && !has && !e.scope().name().equals(c.vendor.name())) continue;
			line(s, String.format("  [%s] %-40s %-13s %s  (%s)", has ? "x" : " ", e.extension(), e.scope(), e.purpose(), e.plannedPhase()));
		}
		line(s, "  total device extensions: " + c.extensions.size());
		if (!ctx.enabledDeviceExtensions().isEmpty())
			line(s, "  enabled by " + ctx.owner() + ": " + String.join(", ", ctx.enabledDeviceExtensions()));

		line(s, "");
		line(s, "-- Hardware profile (computed; not yet applied to rendering)");
		line(s, "  tier " + hp.tier() + ", async transfer " + hp.asyncTransfer() + ", async compute " + hp.asyncCompute()
			+ ", workgroup " + hp.preferredComputeWorkgroup() + ", suggested shadow res " + hp.suggestedShadowResolution());
		for (String n : hp.notes()) line(s, "  * " + n);

		if (selfTest != null) {
			line(s, "");
			line(s, "-- GPU self-test");
			line(s, "  vkCmdFillBuffer 64 KiB, CPU-verified:            " + (selfTest.bufferFillVerified() ? "PASS" : "FAIL"));
			line(s, "  RGBA16F image clear + layout transitions + copy: " + (selfTest.imageClearVerified() ? "PASS" : "FAIL"));
			line(s, String.format("  GPU time (timestamps): %s   CPU submit->fence: %.1f us   [%s]",
				selfTest.gpuMicros() >= 0 ? String.format("%.1f us", selfTest.gpuMicros()) : "n/a", selfTest.cpuSubmitToFenceMicros(), selfTest.timestampNote()));
		}

		ctx.messenger().ifPresent(m -> {
			line(s, "");
			line(s, "-- Debug messenger: " + m.errorCount() + " error(s), " + m.warningCount() + " warning(s)");
			for (String msg : m.recentMessages()) line(s, "  " + msg);
		});
		line(s, "=================================================================");
		return s.toString();
	}

	private static void line(StringBuilder s, String l) {
		s.append(l).append('\n');
	}
}
