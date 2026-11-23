package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Turns "this pass uses image X as Y" into the minimal set of image barriers, batched into a single
 * {@code vkCmdPipelineBarrier}. This is where Vulkan Iris earns back the cost Iris pays on GL (implicit driver
 * hazard tracking on every bind): read→read on the same layout costs nothing, only real hazards emit barriers, and
 * each pass boundary is one call.
 *
 * <p>Images Vulkan Iris owns move between optimal layouts. {@link GpuImage#external() External} images (Minecraft's
 * own targets on Polydium) are never transitioned — their layout is pinned (GENERAL) and only memory dependencies are
 * emitted.</p>
 */
public final class LayoutTracker {
	private static final int WRITE_ACCESS = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
		| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_MEMORY_WRITE_BIT;

	public enum Use {
		COLOR_ATTACHMENT(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
			VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT),
		DEPTH_ATTACHMENT(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
			VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
			VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT),
		SAMPLED_FRAGMENT(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT),
		SAMPLED_COMPUTE(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT),
		STORAGE_COMPUTE(VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT),
		STORAGE_FRAGMENT(VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT),
		TRANSFER_SRC(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT),
		TRANSFER_DST(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

		public final int layout, stages, access;

		Use(int layout, int stages, int access) {
			this.layout = layout;
			this.stages = stages;
			this.access = access;
		}

		boolean readOnly() {
			return (access & WRITE_ACCESS) == 0;
		}
	}

	private record Pending(GpuImage img, int oldLayout, int newLayout, int srcAccess, int dstAccess) {
	}

	private final List<Pending> pending = new ArrayList<>();
	private int srcStages, dstStages;
	private long barriersEmitted, batchesEmitted, elided;

	/**
	 * Declares the next use of {@code img}. {@code discard}: previous contents are not needed (e.g. a target that is
	 * about to be fully overwritten), letting the driver skip decompression/preservation.
	 */
	public LayoutTracker use(GpuImage img, Use use, boolean discard) {
		int newLayout = img.external() ? img.layout : use.layout;
		boolean sameLayout = img.layout == newLayout;
		boolean prevReadOnly = (img.lastAccess & WRITE_ACCESS) == 0;
		if (sameLayout && prevReadOnly && use.readOnly() && !img.external()) {
			// read after read: no hazard, just remember the extra reader stages for the next writer
			img.lastStages |= use.stages;
			img.lastAccess |= use.access;
			elided++;
			return this;
		}
		for (Pending p : pending) {
			if (p.img() == img) throw new IllegalStateException(img.name() + " used twice in one barrier batch; flush() between the uses");
		}
		int oldLayout = (discard && !img.external()) ? VK_IMAGE_LAYOUT_UNDEFINED : img.layout;
		pending.add(new Pending(img, oldLayout, newLayout, img.lastAccess & WRITE_ACCESS, use.access));
		srcStages |= img.lastStages;
		dstStages |= use.stages;
		img.layout = newLayout;
		img.lastStages = use.stages;
		img.lastAccess = use.access;
		return this;
	}

	public LayoutTracker use(GpuImage img, Use use) {
		return use(img, use, false);
	}

	/** Emits all pending barriers as one {@code vkCmdPipelineBarrier}. No-op when nothing is pending. */
	public void flush(VkCommandBuffer cmd) {
		if (pending.isEmpty()) return;
		try (MemoryStack stack = stackPush()) {
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(pending.size(), stack);
			for (int i = 0; i < pending.size(); i++) {
				Pending p = pending.get(i);
				b.get(i).sType$Default().oldLayout(p.oldLayout()).newLayout(p.newLayout())
					.srcAccessMask(p.srcAccess()).dstAccessMask(p.dstAccess())
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
					.image(p.img().image())
					.subresourceRange(r -> r.aspectMask(barrierAspect(p.img())).baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
						.baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS));
			}
			vkCmdPipelineBarrier(cmd, srcStages == 0 ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : srcStages, dstStages, 0, null, null, b);
		}
		barriersEmitted += pending.size();
		batchesEmitted++;
		pending.clear();
		srcStages = dstStages = 0;
	}

	/** Barriers on combined depth/stencil formats must name both aspects (we don't enable separateDepthStencilLayouts). */
	static int barrierAspect(GpuImage img) {
		return switch (img.format()) {
			case VK_FORMAT_D16_UNORM_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT ->
				VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
			default -> img.aspect();
		};
	}

	/**
	 * Hands an external image back to its owner: marks it as possibly touched by anything, so our next use of it
	 * waits on whatever the owner did in between. (The owner's own full barriers cover the other direction.)
	 */
	public static void releaseExternal(GpuImage img) {
		if (!img.external()) throw new IllegalArgumentException(img.name() + " is not external");
		img.lastStages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
		img.lastAccess = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
	}

	public long barriersEmitted() { return barriersEmitted; }
	public long batchesEmitted() { return batchesEmitted; }
	public long barriersElided() { return elided; }
}
