package dev.vulkaniris.fabric;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vulkaniris.vk.FrameClock;
import dev.vulkaniris.vk.VkLog;
import dev.vulkaniris.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.polyfrost.polydium.vulkan.VulkanActive;
import org.polyfrost.polydium.vulkan.VulkanCommandEncoder;
import org.polyfrost.polydium.vulkan.VulkanConst;
import org.polyfrost.polydium.vulkan.VulkanDevice;
import org.polyfrost.polydium.vulkan.VulkanGpuTexture;
import org.polyfrost.polydium.vulkan.VulkanGpuTextureView;

import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/**
 * The only class that touches Polydium's Vulkan backport (PolyForm Shield licensed: we call its public classes at
 * runtime, nothing is copied or bundled). Never load this class unless {@code polydium} is installed.
 *
 * <p>Facts it relies on, read from Polydium 1.4.21's bytecode: {@code VulkanDevice.active} is the live device;
 * every {@code VulkanGpuTexture} stays in {@code VK_IMAGE_LAYOUT_GENERAL}; Polydium separates passes with a full
 * ALL_COMMANDS memory barrier ({@code VulkanCommandEncoder.memoryBarrier}); {@code commandBuffer()} is the frame
 * command buffer being recorded and {@code currentSubmitIndex()} identifies its submission.</p>
 */
public final class PolydiumAccess {
	/** Image layout Polydium keeps every texture in. */
	static final int TEXTURE_LAYOUT = VK_IMAGE_LAYOUT_GENERAL;

	private PolydiumAccess() {
	}

	static boolean vulkanActive() {
		return VulkanActive.active && VulkanDevice.active != null;
	}

	/**
	 * Set by MixinPolydiumDeviceFeatures while Polydium creates its device: whether {@code independentBlend} was
	 * added to the device's features. Null if that mixin did not run (then it is not assumed).
	 */
	public static volatile Boolean independentBlendEnabled;

	static VulkanContext borrow(VkLog log) {
		dev.vulkaniris.vk.GraphicsProgram.independentBlend = Boolean.TRUE.equals(independentBlendEnabled);
		log.info("independentBlend on Polydium's device: " + (independentBlendEnabled == null ? "not requested (feature mixin did not run)"
			: independentBlendEnabled ? "enabled" : "not supported by the GPU (per-buffer blend modes share one state)"));
		VulkanDevice d = VulkanDevice.active;
		int instanceApi = d.instance().vkInstance().getCapabilities().apiVersion;
		Set<String> exts = d.getDeviceInfo().underlyingExtensions();
		return VulkanContext.borrow(VulkanContext.Owner.POLYDIUM, log, d.vkDevice(), d.graphicsQueue().vkQueue(),
			d.graphicsQueue().queueFamilyIndex(), instanceApi, exts);
	}

	static String backendDescription() {
		VulkanDevice d = VulkanDevice.active;
		return d.getBackendName() + " " + d.getVersion() + " on " + d.getRenderer();
	}

	private static VulkanCommandEncoder encoder() {
		return VulkanDevice.active.createCommandEncoder(); // returns the device's single encoder (no allocation)
	}

	static boolean inRenderPass() {
		return encoder().isInRenderPass();
	}

	static VkCommandBuffer frameCommandBuffer() {
		return encoder().commandBuffer();
	}

	static long currentSubmitIndex() {
		return encoder().currentSubmitIndex();
	}

	static boolean submitCompleted(long index) {
		return encoder().awaitSubmitCompletion(index, 0L);
	}

	/** Polydium's VMA allocator (LWJGL VMA 3.3.3, same build Vulkan Iris bundles). */
	static long vma() {
		return VulkanDevice.active.vma();
	}

	/** Polydium's own submit counter as a {@link FrameClock}: no extra fences or waits in the frame. */
	static FrameClock frameClock() {
		return new FrameClock() {
			@Override
			public long currentIndex() {
				return currentSubmitIndex();
			}

			@Override
			public boolean isComplete(long index) {
				return submitCompleted(index);
			}
		};
	}

	/** Full barriers Vulkan Iris added to the frame command buffer (bench diagnostics). */
	static long barriers;

	static void polydiumBarrier(VkCommandBuffer cmd) {
		barriers++;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cmd, stack);
		}
	}

	static long vkImage(GpuTexture texture) {
		return ((VulkanGpuTexture) texture).vkImage();
	}

	static long vkImageView(GpuTextureView view) {
		return ((VulkanGpuTextureView) view).vkImageView();
	}

	static int vkFormat(GpuTexture texture) {
		return VulkanConst.toVk(((VulkanGpuTexture) texture).gpuFormat());
	}
}
