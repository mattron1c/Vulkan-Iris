package dev.vulkaniris.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Enumerates physical devices and picks one. Only used when Vulkan Iris owns the instance (tests, probe);
 * in-game the device is always the one Minecraft / Polydium / VulkanMod already chose.
 *
 * <p>Override with {@code -Dvulkaniris.device=<index or name substring>} (e.g. to force the iGPU on a laptop).</p>
 */
public final class DeviceSelector {
	public record Candidate(int index, VkPhysicalDevice device, GpuCapabilities caps, int score, List<String> rejections) {
		public boolean suitable() {
			return rejections.isEmpty();
		}
	}

	private DeviceSelector() {
	}

	public static List<Candidate> enumerate(VkInstance instance, int instanceApi) {
		List<Candidate> out = new ArrayList<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer n = stack.mallocInt(1);
			VkCheck.check(vkEnumeratePhysicalDevices(instance, n, null), "vkEnumeratePhysicalDevices(count)");
			if (n.get(0) == 0) return out;
			PointerBuffer handles = stack.mallocPointer(n.get(0));
			VkCheck.checkAllowing(vkEnumeratePhysicalDevices(instance, n, handles), "vkEnumeratePhysicalDevices", VK_INCOMPLETE);
			for (int i = 0; i < n.get(0); i++) {
				VkPhysicalDevice pd = new VkPhysicalDevice(handles.get(i), instance);
				GpuCapabilities caps = GpuCapabilities.query(pd, instanceApi);
				List<String> why = rejections(caps);
				out.add(new Candidate(i, pd, caps, score(caps), why));
			}
		}
		return out;
	}

	/** Hard requirements of the Vulkan Iris renderer design (dynamic rendering + sync2, same as VulkanMod / Mojang). */
	static List<String> rejections(GpuCapabilities c) {
		List<String> r = new ArrayList<>();
		if (c.apiVersion < VK_API_VERSION_1_2) r.add("Vulkan " + GpuCapabilities.apiString(c.apiVersion) + " < 1.2");
		if (c.graphicsQueueFamily().isEmpty()) r.add("no queue family with graphics+compute");
		if (!c.supportsDynamicRendering()) r.add("no dynamicRendering");
		if (!c.supportsSynchronization2()) r.add("no synchronization2");
		if (!c.feature("timelineSemaphore")) r.add("no timelineSemaphore");
		if (!c.feature("independentBlend")) r.add("no independentBlend (per-buffer blend modes)");
		return r;
	}

	static int score(GpuCapabilities c) {
		int s = switch (c.deviceType) {
			case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 10_000;
			case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 5_000;
			case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2_000;
			case VK_PHYSICAL_DEVICE_TYPE_CPU -> 10;
			default -> 100;
		};
		s += (int) Math.min(c.deviceLocalBytes() >> 28, 999); // +1 per 256 MiB of VRAM
		if (VK_API_VERSION_MINOR(c.apiVersion) >= 3) s += 500;
		return s;
	}

	public static Candidate select(List<Candidate> all) {
		String override = System.getProperty("vulkaniris.device");
		if (override != null && !override.isBlank()) {
			for (Candidate c : all) {
				boolean match = override.trim().equals(Integer.toString(c.index()))
					|| c.caps().name.toLowerCase(Locale.ROOT).contains(override.trim().toLowerCase(Locale.ROOT));
				if (match) {
					if (!c.suitable()) {
						throw new VkCheck.VulkanException("-Dvulkaniris.device=" + override + " selects " + c.caps().name
							+ ", which is unsuitable: " + String.join(", ", c.rejections()));
					}
					return c;
				}
			}
			throw new VkCheck.VulkanException("-Dvulkaniris.device=" + override + " matches no Vulkan device");
		}
		Candidate best = null;
		for (Candidate c : all) {
			if (c.suitable() && (best == null || c.score() > best.score())) best = c;
		}
		if (best == null) {
			StringBuilder sb = new StringBuilder("No suitable Vulkan device. Candidates:");
			if (all.isEmpty()) sb.append(" (none — is a Vulkan driver installed?)");
			for (Candidate c : all) sb.append("\n  ").append(c.caps().name).append(": ").append(String.join(", ", c.rejections()));
			throw new VkCheck.VulkanException(sb.toString());
		}
		return best;
	}
}
