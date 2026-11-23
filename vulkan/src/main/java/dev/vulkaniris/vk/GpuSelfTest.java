package dev.vulkaniris.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Proves the context can do real GPU work, end to end, and reads the results back to check them:
 * <ol>
 *   <li>allocate host-visible memory, have the GPU {@code vkCmdFillBuffer} it, verify every word on the CPU;</li>
 *   <li>create an RGBA16F image (a typical Iris colortex format), transition UNDEFINED → TRANSFER_DST, clear it,
 *       transition → TRANSFER_SRC, copy to a buffer and verify the half-float pixels;</li>
 *   <li>bracket the work with timestamp queries and report GPU time.</li>
 * </ol>
 * Nothing here can "succeed" without the GPU actually executing the commands.
 */
public final class GpuSelfTest {
	public record Result(boolean bufferFillVerified, boolean imageClearVerified, double gpuMicros, double cpuSubmitToFenceMicros,
						 String timestampNote) {
	}

	private static final int FILL_WORDS = 16 * 1024; // 64 KiB
	private static final int FILL_PATTERN = 0x1A15C0DE;
	private static final int IMG = 64;
	// RGBA16F clear colour (1.0, 0.5, 0.25, 1.0) -> IEEE half bits
	private static final short[] EXPECTED_HALF = {0x3C00, 0x3800, 0x3400, 0x3C00};

	private GpuSelfTest() {
	}

	public static Result run(VulkanContext ctx) {
		VkDevice dev = ctx.device();
		GpuCapabilities caps = ctx.capabilities();
		long cmdPool = 0, fence = 0, queryPool = 0;
		long fillBuf = 0, fillMem = 0, image = 0, imageMem = 0, readBuf = 0, readMem = 0;
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);

			// ---- buffers / image
			fillBuf = createBuffer(dev, FILL_WORDS * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT, lp);
			fillMem = bindBufferMemory(ctx, fillBuf, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, lp);
			long readSize = (long) IMG * IMG * 8;
			readBuf = createBuffer(dev, readSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT, lp);
			readMem = bindBufferMemory(ctx, readBuf, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, lp);

			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
				.imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R16G16B16A16_SFLOAT)
				.extent(e -> e.width(IMG).height(IMG).depth(1)).mipLevels(1).arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			VkCheck.check(vkCreateImage(dev, ici, null, lp), "vkCreateImage(RGBA16F self-test)");
			image = lp.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(dev, image, req);
			imageMem = allocate(ctx, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, lp);
			VkCheck.check(vkBindImageMemory(dev, image, imageMem, 0), "vkBindImageMemory");

			// ---- timestamps (only if this queue family supports them)
			int tsBits = caps.queueFamilies.get(ctx.graphicsQueueFamily()).timestampValidBits();
			String tsNote;
			if (tsBits > 0) {
				VkQueryPoolCreateInfo qci = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
				VkCheck.check(vkCreateQueryPool(dev, qci, null, lp), "vkCreateQueryPool");
				queryPool = lp.get(0);
				tsNote = tsBits + " valid bits, period " + caps.timestampPeriod + " ns/tick";
			} else {
				tsNote = "queue family has no timestamp support";
			}

			// ---- command buffer
			VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(ctx.graphicsQueueFamily());
			VkCheck.check(vkCreateCommandPool(dev, pci, null, lp), "vkCreateCommandPool");
			cmdPool = lp.get(0);
			PointerBuffer pp = stack.mallocPointer(1);
			VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
				.commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
			VkCheck.check(vkAllocateCommandBuffers(dev, ai, pp), "vkAllocateCommandBuffers");
			VkCommandBuffer cmd = new VkCommandBuffer(pp.get(0), dev);

			VkCheck.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
			if (queryPool != 0) {
				vkCmdResetQueryPool(cmd, queryPool, 0, 2);
				vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
			}

			vkCmdFillBuffer(cmd, fillBuf, 0, VK_WHOLE_SIZE, FILL_PATTERN);

			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			imageBarrier(stack, cmd, image, range, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
			VkClearColorValue clear = VkClearColorValue.calloc(stack);
			clear.float32(0, 1.0f).float32(1, 0.5f).float32(2, 0.25f).float32(3, 1.0f);
			vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
			imageBarrier(stack, cmd, image, range, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
				.imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
				.imageOffset(o -> o.set(0, 0, 0)).imageExtent(e -> e.width(IMG).height(IMG).depth(1));
			vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readBuf, region);

			// make transfer writes visible to the host
			VkMemoryBarrier.Buffer hostBarrier = VkMemoryBarrier.calloc(1, stack);
			hostBarrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, hostBarrier, null, null);

			if (queryPool != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
			VkCheck.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

			// ---- submit + wait
			VkCheck.check(vkCreateFence(dev, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "vkCreateFence");
			fence = lp.get(0);
			VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(pp);
			long t0 = System.nanoTime();
			submitChecked(ctx, vkQueueSubmit(ctx.graphicsQueue(), submit, fence), "vkQueueSubmit(self-test)");
			int wait = vkWaitForFences(dev, lp.put(0, fence), true, 5_000_000_000L);
			long t1 = System.nanoTime();
			if (wait == VK_TIMEOUT) throw new VkCheck.VulkanException(VK_TIMEOUT, "Self-test GPU work did not finish within 5 s (hung GPU?)");
			submitChecked(ctx, wait, "vkWaitForFences(self-test)");

			// ---- verify buffer fill
			boolean fillOk = verifyFill(dev, fillMem, stack);
			// ---- verify image clear
			boolean imageOk = verifyImage(dev, readMem, readSize, stack);

			// ---- timestamps
			double gpuUs = -1;
			if (queryPool != 0) {
				LongBuffer ts = stack.mallocLong(2);
				int r = vkGetQueryPoolResults(dev, queryPool, 0, 2, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
				VkCheck.check(r, "vkGetQueryPoolResults");
				long mask = tsBits >= 64 ? -1L : (1L << tsBits) - 1;
				long ticks = (ts.get(1) & mask) - (ts.get(0) & mask);
				gpuUs = ticks * (double) caps.timestampPeriod / 1000.0;
			}
			return new Result(fillOk, imageOk, gpuUs, (t1 - t0) / 1000.0, tsNote);
		} finally {
			if (fence != 0) vkDestroyFence(dev, fence, null);
			if (cmdPool != 0) vkDestroyCommandPool(dev, cmdPool, null);
			if (queryPool != 0) vkDestroyQueryPool(dev, queryPool, null);
			if (image != 0) vkDestroyImage(dev, image, null);
			if (imageMem != 0) vkFreeMemory(dev, imageMem, null);
			if (fillBuf != 0) vkDestroyBuffer(dev, fillBuf, null);
			if (fillMem != 0) vkFreeMemory(dev, fillMem, null);
			if (readBuf != 0) vkDestroyBuffer(dev, readBuf, null);
			if (readMem != 0) vkFreeMemory(dev, readMem, null);
		}
	}

	private static void submitChecked(VulkanContext ctx, int result, String what) {
		try {
			VkCheck.check(result, what);
		} catch (VkCheck.VulkanDeviceLostException e) {
			ctx.markLost(e);
			throw e;
		}
	}

	private static boolean verifyFill(VkDevice dev, long mem, MemoryStack stack) {
		PointerBuffer map = stack.mallocPointer(1);
		VkCheck.check(vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, map), "vkMapMemory(fill)");
		try {
			ByteBuffer b = MemoryUtil.memByteBuffer(map.get(0), FILL_WORDS * 4);
			for (int i = 0; i < FILL_WORDS; i++) {
				if (b.getInt(i * 4) != FILL_PATTERN) return false;
			}
			return true;
		} finally {
			vkUnmapMemory(dev, mem);
		}
	}

	private static boolean verifyImage(VkDevice dev, long mem, long size, MemoryStack stack) {
		PointerBuffer map = stack.mallocPointer(1);
		VkCheck.check(vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, map), "vkMapMemory(image readback)");
		try {
			ByteBuffer b = MemoryUtil.memByteBuffer(map.get(0), (int) size);
			for (int px = 0; px < IMG * IMG; px++) {
				for (int c = 0; c < 4; c++) {
					if (b.getShort(px * 8 + c * 2) != EXPECTED_HALF[c]) return false;
				}
			}
			return true;
		} finally {
			vkUnmapMemory(dev, mem);
		}
	}

	private static void imageBarrier(MemoryStack stack, VkCommandBuffer cmd, long image, VkImageSubresourceRange range,
									 int oldLayout, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
		VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
		b.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
			.srcAccessMask(srcAccess).dstAccessMask(dstAccess)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.image(image).subresourceRange(range);
		vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
	}

	private static long createBuffer(VkDevice dev, long size, int usage, LongBuffer lp) {
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			VkCheck.check(vkCreateBuffer(dev, bci, null, lp), "vkCreateBuffer(" + size + " bytes)");
			return lp.get(0);
		}
	}

	private static long bindBufferMemory(VulkanContext ctx, long buffer, int props, LongBuffer lp) {
		try (MemoryStack stack = stackPush()) {
			VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(ctx.device(), buffer, req);
			long mem = allocate(ctx, req, props, lp);
			VkCheck.check(vkBindBufferMemory(ctx.device(), buffer, mem, 0), "vkBindBufferMemory");
			return mem;
		}
	}

	private static long allocate(VulkanContext ctx, VkMemoryRequirements req, int props, LongBuffer lp) {
		int type = findMemoryType(ctx.capabilities(), req.memoryTypeBits(), props);
		try (MemoryStack stack = stackPush()) {
			VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size()).memoryTypeIndex(type);
			VkCheck.check(vkAllocateMemory(ctx.device(), mai, null, lp), "vkAllocateMemory(" + req.size() + " bytes, type " + type + ")");
			return lp.get(0);
		}
	}

	static int findMemoryType(GpuCapabilities caps, int typeBits, int required) {
		for (GpuCapabilities.MemoryType t : caps.memoryTypes) {
			if ((typeBits & (1 << t.index())) != 0 && (t.flags() & required) == required) return t.index();
		}
		throw new VkCheck.VulkanException(VK_ERROR_FEATURE_NOT_PRESENT, "No memory type with flags 0x" + Integer.toHexString(required)
			+ " in mask 0x" + Integer.toHexString(typeBits));
	}
}
