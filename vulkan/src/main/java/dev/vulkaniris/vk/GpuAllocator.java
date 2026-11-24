package dev.vulkaniris.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU memory through the Vulkan Memory Allocator. In-game this wraps the <em>owner's</em> VMA allocator handle
 * (Polydium's {@code VulkanDevice.vma()}), so Vulkan Iris's render targets share the same heaps and budget tracking as
 * the rest of the frame instead of fragmenting VRAM with a second allocator. In tests it creates its own.
 *
 * <p>Tracks bytes Vulkan Iris itself allocated, so the diagnostics can show "Vulkan Iris uses N MiB".</p>
 */
public final class GpuAllocator implements AutoCloseable {
	private final VulkanContext ctx;
	private final long vma;
	private final boolean owned;
	private final AtomicLong imageBytes = new AtomicLong();
	private final AtomicLong bufferBytes = new AtomicLong();
	private final AtomicLong liveImages = new AtomicLong();

	private GpuAllocator(VulkanContext ctx, long vma, boolean owned) {
		this.ctx = ctx;
		this.vma = vma;
		this.owned = owned;
	}

	/** Uses an allocator someone else created for this device; {@link #close()} will not destroy it. */
	public static GpuAllocator borrow(VulkanContext ctx, long vmaHandle) {
		if (vmaHandle == 0L) throw new IllegalArgumentException("null VMA allocator handle");
		return new GpuAllocator(ctx, vmaHandle, false);
	}

	public static GpuAllocator create(VulkanContext ctx) {
		try (MemoryStack stack = stackPush()) {
			VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(stack).set(ctx.instance(), ctx.device());
			VmaAllocatorCreateInfo ci = VmaAllocatorCreateInfo.calloc(stack)
				.physicalDevice(ctx.physicalDevice()).device(ctx.device()).instance(ctx.instance())
				.pVulkanFunctions(fns)
				.vulkanApiVersion(Math.min(ctx.instanceApiVersion(), ctx.capabilities().apiVersion));
			if (ctx.enabledDeviceExtensions().contains("VK_EXT_memory_budget")) ci.flags(VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT);
			PointerBuffer pp = stack.mallocPointer(1);
			VkCheck.check(vmaCreateAllocator(ci, pp), "vmaCreateAllocator");
			return new GpuAllocator(ctx, pp.get(0), true);
		}
	}

	public long handle() {
		return vma;
	}

	public VulkanContext context() {
		return ctx;
	}

	/** Device-local 2D image + full-image view. */
	public GpuImage createImage(String name, int format, int width, int height, int usage, int aspect) {
		if (width <= 0 || height <= 0) throw new IllegalArgumentException(name + ": bad size " + width + "x" + height);
		VkDevice dev = ctx.device();
		try (MemoryStack stack = stackPush()) {
			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
				.extent(e -> e.set(width, height, 1)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			if ((usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0) {
				aci.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT); // render targets: dedicated allocations are faster on most drivers
			}
			LongBuffer pImage = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			VkCheck.check(vmaCreateImage(vma, ici, aci, pImage, pAlloc, info), "vmaCreateImage(" + name + " " + width + "x" + height + ")");
			long image = pImage.get(0), alloc = pAlloc.get(0);
			long view;
			try {
				VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D)
					.format(format).subresourceRange(r -> r.aspectMask(aspect).levelCount(1).layerCount(1));
				VkCheck.check(vkCreateImageView(dev, vci, null, pImage), "vkCreateImageView(" + name + ")");
				view = pImage.get(0);
			} catch (RuntimeException e) {
				vmaDestroyImage(vma, image, alloc);
				throw e;
			}
			imageBytes.addAndGet(info.size());
			liveImages.incrementAndGet();
			return new GpuImage(name, image, view, alloc, info.size(), format, width, height, aspect, false);
		}
	}

	/** 3D texture (pack {@code TEXTURE_3D} custom textures and images). {@link GpuImage#height()} is the y size; depth is the caller's. */
	public GpuImage createImage3D(String name, int format, int width, int height, int depth, int usage) {
		return createImageDims(name, format, 3, width, height, depth, usage);
	}

	/** 1D texture (pack {@code TEXTURE_1D} custom images). */
	public GpuImage createImage1D(String name, int format, int width, int usage) {
		return createImageDims(name, format, 1, width, 1, 1, usage);
	}

	private GpuImage createImageDims(String name, int format, int dims, int width, int height, int depth, int usage) {
		if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException(name + ": bad size " + width + "x" + height + "x" + depth);
		int imageType = dims == 1 ? VK_IMAGE_TYPE_1D : VK_IMAGE_TYPE_3D;
		int viewType = dims == 1 ? VK_IMAGE_VIEW_TYPE_1D : VK_IMAGE_VIEW_TYPE_3D;
		try (MemoryStack stack = stackPush()) {
			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default().imageType(imageType).format(format)
				.extent(e -> e.set(width, height, depth)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer pImage = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			VkCheck.check(vmaCreateImage(vma, ici, aci, pImage, pAlloc, info), "vmaCreateImage(" + name + " " + width + "x" + height + "x" + depth + ")");
			long image = pImage.get(0), alloc = pAlloc.get(0);
			long view;
			try {
				VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(viewType)
					.format(format).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
				VkCheck.check(vkCreateImageView(ctx.device(), vci, null, pImage), "vkCreateImageView(" + name + ")");
				view = pImage.get(0);
			} catch (RuntimeException e) {
				vmaDestroyImage(vma, image, alloc);
				throw e;
			}
			imageBytes.addAndGet(info.size());
			liveImages.incrementAndGet();
			return new GpuImage(name, image, view, alloc, info.size(), format, width, height, VK_IMAGE_ASPECT_COLOR_BIT, false);
		}
	}

	/**
	 * Buffer. {@code hostVisible}: persistently mapped and CPU-writable (staging, per-frame uniforms) — on ReBAR/UMA
	 * VMA places it in device-local host-visible memory, so the GPU reads it without a copy.
	 */
	public GpuBuffer createBuffer(String name, long size, int usage, boolean hostVisible) {
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
			if (hostVisible) aci.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
			LongBuffer pBuf = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			VkCheck.check(vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, info), "vmaCreateBuffer(" + name + " " + size + " B)");
			bufferBytes.addAndGet(info.size());
			return new GpuBuffer(name, pBuf.get(0), pAlloc.get(0), info.size(), hostVisible ? info.pMappedData() : 0L);
		}
	}

	/** Makes CPU writes visible on non-coherent memory (no-op on coherent types). */
	public void flush(GpuBuffer buf) {
		vmaFlushAllocation(vma, buf.allocation(), 0, VK_WHOLE_SIZE);
	}

	public void destroy(GpuBuffer buf) {
		vmaDestroyBuffer(vma, buf.buffer(), buf.allocation());
		bufferBytes.addAndGet(-buf.size());
	}

	public void destroy(GpuImage img) {
		if (img.external()) throw new IllegalArgumentException("refusing to destroy external image " + img.name());
		vkDestroyImageView(ctx.device(), img.view(), null);
		vmaDestroyImage(vma, img.image(), img.allocation());
		imageBytes.addAndGet(-img.bytes());
		liveImages.decrementAndGet();
	}

	public long imageBytes() {
		return imageBytes.get();
	}

	public long liveImages() {
		return liveImages.get();
	}

	public long bufferBytes() {
		return bufferBytes.get();
	}

	@Override
	public void close() {
		if (liveImages.get() != 0) ctx.log().error("GpuAllocator closed with " + liveImages.get() + " live images (" + (imageBytes.get() >> 10) + " KiB leaked)");
		if (owned) vmaDestroyAllocator(vma);
	}
}
