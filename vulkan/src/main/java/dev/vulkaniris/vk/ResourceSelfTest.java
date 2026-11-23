package dev.vulkaniris.vk;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 4 end-to-end check of the resource layer, identical headless and in-game:
 * <ol>
 *   <li>composite: samples the main colour + main depth (external, GENERAL) and writes inverted colour to
 *       colortex0 and depth to an R32F colortex1 (owned, optimal layouts, ping-pong flip);</li>
 *   <li>final: samples colortex0 and writes it back into the main colour, scissored to the left half;</li>
 *   <li>readbacks before and after prove: left half = 255 − before, right half untouched, colortex1 == depth bit-exactly.</li>
 * </ol>
 * Exercises allocator, render targets, layout tracker, descriptor ring, samplers and both image ownership models.
 */
public final class ResourceSelfTest implements AutoCloseable {
	static final String COMPOSITE = """
		#version 450
		layout(set = 0, binding = 0) uniform sampler2D mainColor;
		layout(set = 0, binding = 1) uniform sampler2D mainDepth;
		layout(location = 0) in vec2 uv;
		layout(location = 0) out vec4 colortex0;
		layout(location = 1) out vec4 colortex1;
		void main() {
		    ivec2 p = ivec2(gl_FragCoord.xy);
		    vec4 c = texelFetch(mainColor, p, 0);
		    colortex0 = vec4(1.0 - c.rgb, c.a);
		    colortex1 = vec4(texelFetch(mainDepth, p, 0).r, 0.0, 0.0, 1.0);
		}
		""";
	static final String FINAL = """
		#version 450
		layout(set = 0, binding = 0) uniform sampler2D colortex0;
		layout(location = 0) in vec2 uv;
		layout(location = 0) out vec4 fragColor;
		void main() {
		    fragColor = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0);
		}
		""";

	public static final int REGION = 64;

	public record Result(boolean passed, String detail) {
	}

	private final VkDevice device;
	private final FullscreenPass composite;
	private final FullscreenPass fin;
	private final ImageReadback colorBefore, depthBefore, colorAfter, depthCopy;
	private int regionX, regionY, splitX;

	public ResourceSelfTest(VulkanContext ctx, ShaderCompiler compiler, int mainColorFormat) {
		this.device = ctx.device();
		this.composite = new FullscreenPass(device, compiler, "vulkaniris:selftest/composite.fsh", COMPOSITE, 2,
			new int[]{mainColorFormat, VK_FORMAT_R32_SFLOAT}, 0);
		this.fin = new FullscreenPass(device, compiler, "vulkaniris:selftest/final.fsh", FINAL, 1, new int[]{mainColorFormat}, 0);
		this.colorBefore = new ImageReadback(device, ctx.capabilities(), REGION, REGION);
		this.depthBefore = new ImageReadback(device, ctx.capabilities(), REGION, REGION);
		this.colorAfter = new ImageReadback(device, ctx.capabilities(), REGION, REGION);
		this.depthCopy = new ImageReadback(device, ctx.capabilities(), REGION, REGION);
	}

	/** colortex formats this test needs: colortex0 = main colour format, colortex1 = R32F. */
	public static int[] targetFormats(int mainColorFormat) {
		return new int[]{mainColorFormat, VK_FORMAT_R32_SFLOAT};
	}

	/**
	 * Records the whole test into {@code cmd}. The readback region straddles the final pass's scissor edge, so it
	 * contains both rewritten and untouched pixels. Call {@link #verify()} once the submission completed.
	 */
	public void record(VkCommandBuffer cmd, LayoutTracker tracker, DescriptorRing ring, SamplerCache samplers,
					   RenderTargetSet targets, GpuImage mainColor, GpuImage mainDepth) {
		int w = mainColor.width(), h = mainColor.height();
		if (targets.width() != w || targets.height() != h) throw new IllegalArgumentException("targets " + targets.width() + "x" + targets.height() + " vs main " + w + "x" + h);
		splitX = w / 2;
		regionX = Math.max(0, Math.min(w - REGION, splitX - REGION / 2));
		regionY = Math.max(0, Math.min(h - REGION, h / 2 - REGION / 2));
		long nearest = samplers.get(SamplerCache.NEAREST_CLAMP);

		tracker.use(mainColor, LayoutTracker.Use.TRANSFER_SRC).use(mainDepth, LayoutTracker.Use.TRANSFER_SRC).flush(cmd);
		colorBefore.record(cmd, mainColor.image(), mainColor.layout(), regionX, regionY);
		depthBefore.record(cmd, mainDepth.image(), mainDepth.layout(), regionX, regionY, VK_IMAGE_ASPECT_DEPTH_BIT);

		GpuImage[] compOut = {targets.write(0), targets.write(1)};
		GpuImage[] compIn = {mainColor, mainDepth};
		composite.prepare(tracker, compIn, compOut, true);
		tracker.flush(cmd);
		long set = ring.allocate(composite.setLayout());
		DescriptorRing.writeImages(device, set, nearest, compIn);
		composite.record(cmd, set, compOut, 0, 0, w, h, null);
		targets.flip(0);
		targets.flip(1);

		GpuImage[] finIn = {targets.read(0)};
		GpuImage[] finOut = {mainColor};
		fin.prepare(tracker, finIn, finOut, false);
		tracker.flush(cmd);
		set = ring.allocate(fin.setLayout());
		DescriptorRing.writeImages(device, set, nearest, finIn);
		fin.record(cmd, set, finOut, 0, 0, splitX, h, null);

		tracker.use(mainColor, LayoutTracker.Use.TRANSFER_SRC).use(targets.read(1), LayoutTracker.Use.TRANSFER_SRC).flush(cmd);
		colorAfter.record(cmd, mainColor.image(), mainColor.layout(), regionX, regionY);
		depthCopy.record(cmd, targets.read(1).image(), targets.read(1).layout(), regionX, regionY);

		LayoutTracker.releaseExternal(mainColor);
		LayoutTracker.releaseExternal(mainDepth);
	}

	public Result verify() {
		int changed = 0, untouched = 0, distinctDepth = 0;
		float firstDepth = depthBefore.float32(0, 0);
		for (int y = 0; y < REGION; y++) {
			for (int x = 0; x < REGION; x++) {
				int ax = regionX + x;
				int[] b = colorBefore.pixel(x, y), a = colorAfter.pixel(x, y);
				if (ax < splitX) {
					int[] want = {255 - b[0], 255 - b[1], 255 - b[2], b[3]};
					if (!ImageReadback.close(a, want, 1)) {
						return new Result(false, "inverted pixel (" + ax + "," + (regionY + y) + ") before " + ImageReadback.rgba(b)
							+ " after " + ImageReadback.rgba(a) + " want " + ImageReadback.rgba(want));
					}
					changed++;
				} else {
					if (!java.util.Arrays.equals(a, b)) {
						return new Result(false, "pixel (" + ax + "," + (regionY + y) + ") outside the scissor changed: "
							+ ImageReadback.rgba(b) + " → " + ImageReadback.rgba(a));
					}
					untouched++;
				}
				float d = depthBefore.float32(x, y), c = depthCopy.float32(x, y);
				if (Float.floatToRawIntBits(d) != Float.floatToRawIntBits(c)) {
					return new Result(false, "depth (" + ax + "," + (regionY + y) + ") " + d + " but colortex1 has " + c);
				}
				if (d != firstDepth) distinctDepth++;
			}
		}
		return new Result(true, "region " + REGION + "x" + REGION + " at (" + regionX + "," + regionY + "): " + changed + " px inverted, "
			+ untouched + " px untouched, " + REGION * REGION + " depth texels bit-exact (" + distinctDepth + " differ from the first, depth "
			+ firstDepth + ")");
	}

	@Override
	public void close() {
		composite.close();
		fin.close();
		colorBefore.close();
		depthBefore.close();
		colorAfter.close();
		depthCopy.close();
	}
}
