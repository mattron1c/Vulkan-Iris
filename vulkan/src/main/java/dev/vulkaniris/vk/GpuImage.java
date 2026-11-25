package dev.vulkaniris.vk;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A 2D image plus its layout/sync state. Either created by {@link GpuAllocator} (Vulkan Iris owns it and
 * {@link LayoutTracker} moves it between optimal layouts), or {@link #external} — owned by the Vulkan owner
 * (Minecraft's main colour/depth on Polydium) and pinned to a fixed layout we must never change.
 */
public final class GpuImage {
	private final String name;
	private final long image;
	private final long view;
	private final long allocation;
	private final long bytes;
	private final int format;
	private final int width;
	private final int height;
	private final int aspect;
	private final boolean external;

	// sync state, maintained by LayoutTracker
	int layout = VK_IMAGE_LAYOUT_UNDEFINED;
	int lastStages = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
	int lastAccess = 0;

	GpuImage(String name, long image, long view, long allocation, long bytes, int format, int width, int height, int aspect, boolean external) {
		this.name = name;
		this.image = image;
		this.view = view;
		this.allocation = allocation;
		this.bytes = bytes;
		this.format = format;
		this.width = width;
		this.height = height;
		this.aspect = aspect;
		this.external = external;
	}

	/**
	 * Wraps an image someone else owns. {@code fixedLayout} is the layout the owner keeps it in (Polydium: GENERAL);
	 * {@code view} may be one Vulkan Iris created (e.g. a depth-only view) — the caller destroys that separately.
	 */
	public static GpuImage external(String name, long image, long view, int format, int width, int height, int aspect, int fixedLayout) {
		GpuImage g = new GpuImage(name, image, view, 0L, 0L, format, width, height, aspect, true);
		g.layout = fixedLayout;
		g.lastStages = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
		g.lastAccess = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
		return g;
	}

	public String name() { return name; }
	public long image() { return image; }
	public long view() { return view; }

	/** A second view used only for sampling (e.g. a swizzled depth view); 0 = sample through {@link #view()}. */
	long sampleView;

	/** The view descriptors sample through: the sampling view if one was made, else the image's view. */
	public long sampleView() { return sampleView != 0L ? sampleView : view; }
	public long allocation() { return allocation; }
	public long bytes() { return bytes; }
	public int format() { return format; }
	public int width() { return width; }
	public int height() { return height; }
	public int aspect() { return aspect; }
	public boolean external() { return external; }
	public int layout() { return layout; }

	@Override
	public String toString() {
		return name + "[" + width + "x" + height + " fmt " + format + (external ? " external" : "") + " layout " + layout + "]";
	}
}
