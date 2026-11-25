package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GL's {@code glGenerateMipmap} for an image with a mip chain: each level is a linear blit of the one above it (the
 * box-filter downsample GL drivers use). Iris calls it for a pack's {@code mipmap.<program>.colortexN} buffers just
 * before that program runs (CompositeRenderer.setupMipmapping). Afterwards the whole image is in TRANSFER_SRC layout,
 * which {@link LayoutTracker} knows, so the next use transitions it as usual.
 */
public final class MipChain {
	private MipChain() {
	}

	/** Whether {@code format} supports a linear-filtered blit from and to itself (needed to downsample). */
	public static boolean canGenerate(VulkanContext ctx, int format) {
		int need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
		return (RenderTargetSet.optimalTilingFeatures(ctx, format) & need) == need;
	}

	/** Records the downsample chain from level 0. No-op (false) for an image with one level. */
	public static boolean generate(VkCommandBuffer cmd, LayoutTracker tracker, GpuImage img) {
		int n = img.mipLevels();
		if (n <= 1) return false;
		if (img.external()) throw new IllegalArgumentException("refusing to write mip levels of external image " + img.name());
		// every level into TRANSFER_DST, keeping level 0 (the levels below are rewritten)
		tracker.use(img, LayoutTracker.Use.TRANSFER_DST).flush(cmd);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default().image(img.image())
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
				.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
				.subresourceRange(r -> r.aspectMask(img.aspect()).levelCount(1).layerCount(1));
			VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
			int w = img.width(), h = img.height();
			for (int level = 1; level < n; level++) {
				b.get(0).subresourceRange().baseMipLevel(level - 1);
				vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b);
				int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
				final int lv = level, sw = w, sh = h;
				blit.get(0).srcSubresource(s -> s.aspectMask(img.aspect()).mipLevel(lv - 1).layerCount(1))
					.dstSubresource(s -> s.aspectMask(img.aspect()).mipLevel(lv).layerCount(1));
				blit.get(0).srcOffsets(0).set(0, 0, 0);
				blit.get(0).srcOffsets(1).set(sw, sh, 1);
				blit.get(0).dstOffsets(0).set(0, 0, 0);
				blit.get(0).dstOffsets(1).set(nw, nh, 1);
				vkCmdBlitImage(cmd, img.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, img.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
				w = nw;
				h = nh;
			}
			// the last level was only written: bring it to TRANSFER_SRC like the others
			b.get(0).subresourceRange().baseMipLevel(n - 1);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b);
		}
		img.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		img.lastStages = VK_PIPELINE_STAGE_TRANSFER_BIT;
		img.lastAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
		return true;
	}
}
