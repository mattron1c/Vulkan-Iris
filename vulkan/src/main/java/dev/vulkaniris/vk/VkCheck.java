package dev.vulkaniris.vk;

import static org.lwjgl.vulkan.EXTFullScreenExclusive.VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;
import static org.lwjgl.vulkan.VK12.VK_ERROR_FRAGMENTATION;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_COMPILE_REQUIRED;

/**
 * Every Vulkan call that returns a VkResult goes through here. Errors are never swallowed:
 * a failure throws {@link VulkanException} (or {@link VulkanDeviceLostException} for device loss) carrying the
 * symbolic result name and what we were trying to do.
 */
public final class VkCheck {
	private VkCheck() {
	}

	/** Throws unless {@code result == VK_SUCCESS}. */
	public static void check(int result, String action) {
		if (result != VK_SUCCESS) {
			throw failure(result, action);
		}
	}

	/** Accepts VK_SUCCESS and the given non-error status codes (e.g. VK_TIMEOUT, VK_INCOMPLETE); returns the code. */
	public static int checkAllowing(int result, String action, int... allowed) {
		if (result == VK_SUCCESS) return result;
		for (int a : allowed) {
			if (result == a) return result;
		}
		throw failure(result, action);
	}

	public static VulkanException failure(int result, String action) {
		String message = action + " failed: " + name(result) + " (" + result + ")";
		if (result == VK_ERROR_DEVICE_LOST) {
			return new VulkanDeviceLostException(message);
		}
		return new VulkanException(result, message);
	}

	public static String name(int result) {
		return switch (result) {
			case VK_SUCCESS -> "VK_SUCCESS";
			case VK_NOT_READY -> "VK_NOT_READY";
			case VK_TIMEOUT -> "VK_TIMEOUT";
			case VK_EVENT_SET -> "VK_EVENT_SET";
			case VK_EVENT_RESET -> "VK_EVENT_RESET";
			case VK_INCOMPLETE -> "VK_INCOMPLETE";
			case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
			case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
			case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
			case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
			case VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED";
			case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
			case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
			case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
			case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
			case VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
			case VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
			case VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL";
			case VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN";
			case VK_ERROR_OUT_OF_POOL_MEMORY -> "VK_ERROR_OUT_OF_POOL_MEMORY";
			case VK_ERROR_FRAGMENTATION -> "VK_ERROR_FRAGMENTATION";
			case VK_PIPELINE_COMPILE_REQUIRED -> "VK_PIPELINE_COMPILE_REQUIRED";
			case VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
			case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
			case VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
			case VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
			case VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT -> "VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT";
			default -> "VkResult#" + result;
		};
	}

	public static class VulkanException extends RuntimeException {
		private final int result;

		public VulkanException(int result, String message) {
			super(message);
			this.result = result;
		}

		public VulkanException(String message) {
			this(VK_ERROR_UNKNOWN, message);
		}

		public int result() {
			return result;
		}
	}

	/**
	 * The GPU/driver reset or crashed. All objects created from the device are now unusable; the only recovery is
	 * tearing the whole context down and recreating it (see {@link VulkanContext#isLost()}).
	 */
	public static final class VulkanDeviceLostException extends VulkanException {
		public VulkanDeviceLostException(String message) {
			super(VK_ERROR_DEVICE_LOST, message);
		}
	}
}
