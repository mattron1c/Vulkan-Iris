package dev.vulkaniris.vk;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_PATCH;

/** GPU vendor from {@code VkPhysicalDeviceProperties.vendorID} (PCI vendor IDs, plus Khronos IDs for software drivers). */
public enum GpuVendor {
	NVIDIA(0x10DE, "NVIDIA"),
	AMD(0x1002, "AMD"),
	INTEL(0x8086, "Intel"),
	APPLE(0x106B, "Apple"),
	ARM(0x13B5, "ARM"),
	QUALCOMM(0x5143, "Qualcomm"),
	IMAGINATION(0x1010, "Imagination"),
	MICROSOFT(0x1414, "Microsoft (Dozen/WARP)"),
	MESA(0x10005, "Mesa (software)"),
	UNKNOWN(-1, "Unknown");

	public final int pciId;
	public final String displayName;

	GpuVendor(int pciId, String displayName) {
		this.pciId = pciId;
		this.displayName = displayName;
	}

	public static GpuVendor fromId(int vendorId) {
		if (vendorId == 0x1022) return AMD; // AMD's CPU-side ID, seen on some APU drivers
		for (GpuVendor v : values()) {
			if (v.pciId == vendorId) return v;
		}
		return UNKNOWN;
	}

	/**
	 * Decodes {@code driverVersion}. Its packing is vendor specific: NVIDIA uses 10.8.8.6 bits, Intel's Windows driver
	 * uses 18.14 bits, everyone else (AMD, Mesa, ...) uses the standard VK_MAKE_API_VERSION packing.
	 */
	public String decodeDriverVersion(int raw, boolean windows) {
		return switch (this) {
			case NVIDIA -> ((raw >>> 22) & 0x3FF) + "." + ((raw >>> 14) & 0xFF);
			case INTEL -> windows
				? (raw >>> 14) + "." + (raw & 0x3FFF)
				: VK_API_VERSION_MAJOR(raw) + "." + VK_API_VERSION_MINOR(raw) + "." + VK_API_VERSION_PATCH(raw);
			default -> VK_API_VERSION_MAJOR(raw) + "." + VK_API_VERSION_MINOR(raw) + "." + VK_API_VERSION_PATCH(raw);
		};
	}
}
