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
 * Copies a rectangle of a 4-byte-per-pixel colour image into a host-visible buffer so the CPU can check what the
 * GPU actually wrote. Used by the self-tests; never in the normal render path.
 */
public final class ImageReadback implements AutoCloseable {
	private final VkDevice device;
	private final int width;
	private final int height;
	private final long buffer;
	private final long memory;

	public ImageReadback(VkDevice device, GpuCapabilities caps, int width, int height) {
		this.device = device;
		this.width = width;
		this.height = height;
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size((long) width * height * 4)
				.usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "vkCreateBuffer(readback)");
			buffer = lp.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(device, buffer, req);
			int type = GpuSelfTest.findMemoryType(caps, req.memoryTypeBits(),
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			int r = vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size()).memoryTypeIndex(type), null, lp);
			if (r != VK_SUCCESS) {
				vkDestroyBuffer(device, buffer, null);
				VkCheck.check(r, "vkAllocateMemory(readback)");
			}
			memory = lp.get(0);
			VkCheck.check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory(readback)");
		}
	}

	/** Records the copy plus the transfer→host barrier. The image must already be readable in {@code imageLayout}. */
	public void record(VkCommandBuffer cmd, long image, int imageLayout, int x, int y) {
		record(cmd, image, imageLayout, x, y, VK_IMAGE_ASPECT_COLOR_BIT);
	}

	/** Same, for one aspect. Only formats with 4 bytes per texel in that aspect (RGBA8, R32F, D32_SFLOAT depth). */
	public void record(VkCommandBuffer cmd, long image, int imageLayout, int x, int y, int aspect) {
		try (MemoryStack stack = stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0)
				.imageSubresource(s -> s.aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1))
				.imageOffset(o -> o.set(x, y, 0)).imageExtent(e -> e.set(width, height, 1));
			vkCmdCopyImageToBuffer(cmd, image, imageLayout, buffer, region);
			VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, b, null, null);
		}
	}

	/** RGBA bytes of pixel (px, py) relative to the copied rectangle. Only valid after the GPU finished. */
	public int[] pixel(int px, int py) {
		try (MemoryStack stack = stackPush()) {
			PointerBuffer map = stack.mallocPointer(1);
			VkCheck.check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, map), "vkMapMemory(readback)");
			try {
				ByteBuffer b = MemoryUtil.memByteBuffer(map.get(0), width * height * 4);
				int o = (py * width + px) * 4;
				return new int[]{b.get(o) & 0xFF, b.get(o + 1) & 0xFF, b.get(o + 2) & 0xFF, b.get(o + 3) & 0xFF};
			} finally {
				vkUnmapMemory(device, memory);
			}
		}
	}

	/** The 4 bytes at (px, py) as a little-endian float (R32F / D32_SFLOAT). */
	public float float32(int px, int py) {
		int[] p = pixel(px, py);
		return Float.intBitsToFloat(p[0] | p[1] << 8 | p[2] << 16 | p[3] << 24);
	}

	public static boolean close(int[] actual, int[] expected, int tolerance) {
		for (int i = 0; i < 4; i++) if (Math.abs(actual[i] - expected[i]) > tolerance) return false;
		return true;
	}

	public static String rgba(int[] p) {
		return "(" + p[0] + "," + p[1] + "," + p[2] + "," + p[3] + ")";
	}

	@Override
	public void close() {
		vkDestroyBuffer(device, buffer, null);
		vkFreeMemory(device, memory, null);
	}
}
