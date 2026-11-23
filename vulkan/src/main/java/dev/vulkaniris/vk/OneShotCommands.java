package dev.vulkaniris.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Record → submit → wait, for owned-device work outside the frame (tests, probe, startup uploads). Never used on a
 * borrowed device during a frame: there all work goes into the owner's frame command buffer.
 */
public final class OneShotCommands implements AutoCloseable {
	private final VulkanContext ctx;
	private final FrameClock.Manual clock;
	private final long pool;
	private final long fence;

	public OneShotCommands(VulkanContext ctx, FrameClock.Manual clock) {
		this.ctx = ctx;
		this.clock = clock;
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateCommandPool(ctx.device(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(ctx.graphicsQueueFamily()), null, lp), "vkCreateCommandPool");
			pool = lp.get(0);
			VkCheck.check(vkCreateFence(ctx.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "vkCreateFence");
			fence = lp.get(0);
		}
	}

	public void run(Consumer<VkCommandBuffer> recorder) {
		VkDevice dev = ctx.device();
		try (MemoryStack stack = stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			VkCheck.check(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "vkAllocateCommandBuffers");
			VkCommandBuffer cmd = new VkCommandBuffer(pp.get(0), dev);
			try {
				VkCheck.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
				recorder.accept(cmd);
				VkCheck.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
				VkCheck.check(vkResetFences(dev, fence), "vkResetFences");
				VkCheck.check(vkQueueSubmit(ctx.graphicsQueue(), VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(pp), fence), "vkQueueSubmit");
				VkCheck.check(vkWaitForFences(dev, stack.longs(fence), true, 10_000_000_000L), "vkWaitForFences");
				clock.advance();
			} finally {
				vkFreeCommandBuffers(dev, pool, pp);
			}
		}
	}

	@Override
	public void close() {
		vkDestroyFence(ctx.device(), fence, null);
		vkDestroyCommandPool(ctx.device(), pool, null);
	}
}
