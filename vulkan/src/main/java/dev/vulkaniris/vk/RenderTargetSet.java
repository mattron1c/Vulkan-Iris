package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iris's {@code colortex0..15}: each target is a main/alt image pair that ping-pongs between composite passes
 * (Iris's "flip"), all sized to the main framebuffer. Formats come from the shader pack; formats the GPU can't
 * render to are promoted (3-channel → 4-channel) rather than failing, matching what GL drivers do silently.
 *
 * <p>Resizing never stalls: old images go to the {@link DeferredDestroyer} and are freed once the GPU is done with
 * them, while the new ones are used from the very next command.</p>
 */
public final class RenderTargetSet implements AutoCloseable {
	public static final int MAX_TARGETS = 16;

	private final GpuAllocator allocator;
	private final DeferredDestroyer destroyer;
	private final int[] requestedFormats;
	private final int[] formats;
	private final GpuImage[] main;
	private final GpuImage[] alt;
	private final boolean[] flipped;
	private int width, height;
	private int generation;
	private final Sizer sizer;
	/** Targets a pass samples with mipmaps (Iris {@code mipmap.<program>.colortexN}): created with a full mip chain. */
	private final boolean[] mipmapped;

	/** The size of colortex{index} for a {@code width} x {@code height} screen (Iris: size.buffer.colortexN). */
	@FunctionalInterface
	public interface Sizer {
		int[] size(int index, int width, int height);
	}

	/** {@code formats[i]} = requested VkFormat for colortex{i}, or 0 when the pack doesn't use that target. */
	public RenderTargetSet(GpuAllocator allocator, DeferredDestroyer destroyer, int[] formats, int width, int height) {
		this(allocator, destroyer, formats, width, height, (i, w, h) -> new int[]{w, h});
	}

	/** As above; each target is {@code sizer}'s size for the screen size, which {@link #width()} / {@link #height()} keep. */
	public RenderTargetSet(GpuAllocator allocator, DeferredDestroyer destroyer, int[] formats, int width, int height, Sizer sizer) {
		this(allocator, destroyer, formats, width, height, sizer, new boolean[formats.length]);
	}

	/** As above; {@code mipmapped[i]}: colortex{i} gets a mip chain if its format can be blit with linear filtering. */
	public RenderTargetSet(GpuAllocator allocator, DeferredDestroyer destroyer, int[] formats, int width, int height, Sizer sizer, boolean[] mipmapped) {
		this.sizer = sizer;
		this.mipmapped = new boolean[formats.length];
		if (formats.length > MAX_TARGETS) throw new IllegalArgumentException("at most " + MAX_TARGETS + " colortex targets");
		this.allocator = allocator;
		this.destroyer = destroyer;
		this.requestedFormats = formats.clone();
		this.formats = new int[formats.length];
		this.main = new GpuImage[formats.length];
		this.alt = new GpuImage[formats.length];
		this.flipped = new boolean[formats.length];
		for (int i = 0; i < formats.length; i++) {
			if (formats[i] == 0) continue;
			this.formats[i] = renderableFormat(allocator.context(), formats[i]);
			if (this.formats[i] != formats[i]) {
				allocator.context().log().info("colortex" + i + ": format " + formats[i] + " is not renderable on this GPU, using " + this.formats[i]);
			}
			if (i < mipmapped.length && mipmapped[i]) {
				this.mipmapped[i] = MipChain.canGenerate(allocator.context(), this.formats[i]);
				if (!this.mipmapped[i]) allocator.context().log().info("colortex" + i + ": format " + this.formats[i] + " can't be blit with linear filtering; no mipmaps");
			}
		}
		allocate(width, height);
	}

	/** Returns {@code fmt} if it can be a sampled colour attachment, else the nearest 4-channel format that can. */
	public static int renderableFormat(VulkanContext ctx, int fmt) {
		if (renderable(ctx, fmt)) return fmt;
		int promoted = switch (fmt) {
			case VK_FORMAT_R8G8B8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
			case VK_FORMAT_R8G8B8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
			case VK_FORMAT_R16G16B16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
			case VK_FORMAT_R16G16B16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
			case VK_FORMAT_R16G16B16_SFLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
			case VK_FORMAT_R32G32B32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
			case VK_FORMAT_R32G32B32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
			case VK_FORMAT_R32G32B32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
			default -> 0;
		};
		if (promoted != 0 && renderable(ctx, promoted)) return promoted;
		throw new VkCheck.VulkanException(VK_ERROR_FORMAT_NOT_SUPPORTED, "VkFormat " + fmt + " can't be used as a sampled colour target on " + ctx.capabilities().name);
	}

	private static boolean renderable(VulkanContext ctx, int fmt) {
		int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
		return (optimalTilingFeatures(ctx, fmt) & need) == need;
	}

	static int optimalTilingFeatures(VulkanContext ctx, int fmt) {
		try (MemoryStack stack = stackPush()) {
			VkFormatProperties props = VkFormatProperties.malloc(stack);
			vkGetPhysicalDeviceFormatProperties(ctx.physicalDevice(), fmt, props);
			return props.optimalTilingFeatures();
		}
	}

	private void allocate(int w, int h) {
		for (int i = 0; i < formats.length; i++) {
			if (formats[i] == 0) continue;
			int fmt = formats[i];
			int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			// packs can bind colortex as image2D (imageLoad/imageStore) — only possible where the format supports storage
			if ((optimalTilingFeatures(allocator.context(), fmt) & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0) usage |= VK_IMAGE_USAGE_STORAGE_BIT;
			int[] size = sizer.size(i, w, h);
			int tw = Math.max(1, size[0]), th = Math.max(1, size[1]);
			int levels = mipmapped[i] ? GpuAllocator.fullMipChain(tw, th) : 1;
			main[i] = allocator.createImage("colortex" + i, fmt, tw, th, usage, VK_IMAGE_ASPECT_COLOR_BIT, levels);
			alt[i] = allocator.createImage("colortex" + i + "alt", fmt, tw, th, usage, VK_IMAGE_ASPECT_COLOR_BIT, levels);
		}
		width = w;
		height = h;
		Arrays.fill(flipped, false);
	}

	/** Recreates every target at the new size. No-op if unchanged. Returns true if anything was recreated. */
	public boolean resize(int w, int h) {
		if (w == width && h == height) return false;
		retire();
		allocate(w, h);
		generation++;
		return true;
	}

	private void retire() {
		for (int i = 0; i < formats.length; i++) {
			GpuImage m = main[i], a = alt[i];
			if (m == null) continue;
			destroyer.defer("colortex" + i + " " + width + "x" + height, () -> {
				allocator.destroy(m);
				allocator.destroy(a);
			});
			main[i] = alt[i] = null;
		}
	}

	public boolean has(int i) {
		return i < formats.length && formats[i] != 0;
	}

	/** Image passes read colortex{i} from (the result of the previous pass). */
	public GpuImage read(int i) {
		return flipped[i] ? alt[i] : main[i];
	}

	/** Image passes write colortex{i} to. */
	public GpuImage write(int i) {
		return flipped[i] ? main[i] : alt[i];
	}

	/** After a pass wrote target {@code i}: swap so the next pass reads what was just written. */
	public void flip(int i) {
		flipped[i] = !flipped[i];
	}

	/** True when {@link #read} is currently the alternate image. */
	public boolean flipped(int i) {
		return flipped[i];
	}

	public int format(int i) { return formats[i]; }
	public int requestedFormat(int i) { return requestedFormats[i]; }
	public int width() { return width; }
	public int height() { return height; }
	/** Bumped on every resize; descriptor sets built against an older generation are stale. */
	public int generation() { return generation; }

	public long bytes() {
		long b = 0;
		for (int i = 0; i < formats.length; i++) if (main[i] != null) b += main[i].bytes() + alt[i].bytes();
		return b;
	}

	/** Defers destruction like a resize does; call {@link DeferredDestroyer#collect()} afterwards. */
	@Override
	public void close() {
		retire();
	}
}
