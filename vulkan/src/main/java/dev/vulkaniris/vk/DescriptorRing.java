package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Per-frame descriptor sets without per-set frees. Sets are allocated from the current pool, each pool is tagged
 * with the last submission that used it, and a pool is reset in one call once that submission completed. Iris
 * rebinds samplers per program every frame; this makes that a bump allocation.
 */
public final class DescriptorRing implements AutoCloseable {
	private static final int MAX_SETS = 512;

	private record Tagged(long pool, long lastUse) {
	}

	private final VkDevice device;
	private final FrameClock clock;
	private final ArrayDeque<Tagged> retired = new ArrayDeque<>();
	private final List<Long> all = new ArrayList<>();
	private long current;
	private long currentTag = -1;
	private long setsAllocated, poolsCreated, poolResets;

	public DescriptorRing(VkDevice device, FrameClock clock) {
		this.device = device;
		this.clock = clock;
		this.current = acquirePool();
	}

	public synchronized long allocate(long setLayout) {
		long frame = clock.currentIndex();
		if (currentTag != -1 && currentTag != frame) rotate(); // keep one pool per submission so whole pools free at once
		currentTag = frame;
		try (MemoryStack stack = stackPush()) {
			LongBuffer out = stack.mallocLong(1);
			VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
			int r = vkAllocateDescriptorSets(device, ai.descriptorPool(current), out);
			if (r == VK_ERROR_OUT_OF_POOL_MEMORY || r == VK_ERROR_FRAGMENTED_POOL) {
				rotate();
				currentTag = frame;
				r = vkAllocateDescriptorSets(device, ai.descriptorPool(current), out);
			}
			VkCheck.check(r, "vkAllocateDescriptorSets");
			setsAllocated++;
			return out.get(0);
		}
	}

	private void rotate() {
		retired.addLast(new Tagged(current, currentTag));
		current = acquirePool();
	}

	private long acquirePool() {
		Tagged oldest = retired.peekFirst();
		if (oldest != null && clock.isComplete(oldest.lastUse())) {
			retired.pollFirst();
			VkCheck.check(vkResetDescriptorPool(device, oldest.pool(), 0), "vkResetDescriptorPool");
			poolResets++;
			return oldest.pool();
		}
		try (MemoryStack stack = stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
			sizes.get(4).type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(MAX_SETS);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_SETS * 8);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(MAX_SETS);
			// vanilla gbuffer programs bind vanilla's uniform blocks (DynamicTransforms, Projection, Fog, Globals ...) + their own
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(MAX_SETS * 6);
			sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(MAX_SETS);
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
				.maxSets(MAX_SETS).pPoolSizes(sizes), null, lp), "vkCreateDescriptorPool");
			all.add(lp.get(0));
			poolsCreated++;
			return lp.get(0);
		}
	}

	/** Writes combined image samplers 0..n-1, each in the layout its image is currently in. */
	public static void writeImages(VkDevice device, long set, long sampler, GpuImage... images) {
		try (MemoryStack stack = stackPush()) {
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(images.length, stack);
			for (int i = 0; i < images.length; i++) {
				VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
				ii.get(0).sampler(sampler).imageView(images[i].view()).imageLayout(images[i].layout());
				w.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
			}
			vkUpdateDescriptorSets(device, w, null);
		}
	}

	public synchronized long setsAllocated() { return setsAllocated; }
	public synchronized long poolsCreated() { return poolsCreated; }
	public synchronized long poolResets() { return poolResets; }

	/** Only after the device is idle. */
	@Override
	public synchronized void close() {
		for (long p : all) vkDestroyDescriptorPool(device, p, null);
		all.clear();
		retired.clear();
	}
}
