package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Batches the writes for one descriptor set into a single {@code vkUpdateDescriptorSets}. */
public final class DescriptorWrites implements AutoCloseable {
	private final VkDevice device;
	private final long set;
	private final MemoryStack stack;
	private final VkWriteDescriptorSet.Buffer writes;
	private int n;

	public DescriptorWrites(VkDevice device, long set, int capacity) {
		this.device = device;
		this.set = set;
		this.stack = stackPush();
		this.writes = VkWriteDescriptorSet.calloc(Math.max(1, capacity), stack);
	}

	/** Combined image sampler, using the layout the image is in right now (call after the layout transitions). */
	public DescriptorWrites image(int binding, int arrayElement, GpuImage img, long sampler) {
		VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
		ii.get(0).sampler(sampler).imageView(img.view()).imageLayout(img.layout());
		writes.get(n++).sType$Default().dstSet(set).dstBinding(binding).dstArrayElement(arrayElement).descriptorCount(1)
			.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
		return this;
	}

	public DescriptorWrites storageImage(int binding, GpuImage img) {
		VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
		ii.get(0).imageView(img.view()).imageLayout(img.layout());
		writes.get(n++).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
			.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(ii);
		return this;
	}

	public DescriptorWrites buffer(int binding, int type, long buffer, long offset, long range) {
		VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
		bi.get(0).buffer(buffer).offset(offset).range(range);
		writes.get(n++).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(type).pBufferInfo(bi);
		return this;
	}

	@Override
	public void close() {
		try {
			if (n > 0) vkUpdateDescriptorSets(device, writes.limit(n), null);
		} finally {
			stack.close();
		}
	}
}
