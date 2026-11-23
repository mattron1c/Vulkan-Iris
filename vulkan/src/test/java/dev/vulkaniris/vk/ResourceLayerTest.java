package dev.vulkaniris.vk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 4: the resource layer on a real GPU. Main colour/depth are wrapped as {@link GpuImage#external external}
 * GENERAL-layout images exactly like Polydium's, colortex targets are owned and move between optimal layouts.
 */
class ResourceLayerTest {
	private static VulkanContext context() {
		return VulkanContext.createOwned(new VulkanContext.Options("resource test", Boolean.getBoolean("vulkaniris.validation"), false), VkLog.STDERR);
	}

	/** Main colour + depth the way Polydium has them, filled with a gradient and a per-pixel depth ramp. */
	private record FakeMain(GpuImage colorOwned, GpuImage depthOwned, GpuImage color, GpuImage depth) {
		static FakeMain create(GpuAllocator alloc, OneShotCommands once, FirstLightPass gradient, int w, int h) {
			int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			GpuImage c = alloc.createImage("main colour", VK_FORMAT_R8G8B8A8_UNORM, w, h, usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
			GpuImage d = alloc.createImage("main depth", VK_FORMAT_D32_SFLOAT, w, h, usage | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
			GpuBuffer staging = alloc.createBuffer("depth upload", (long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
			FloatBuffer f = MemoryUtil.memFloatBuffer(staging.mapped(), w * h);
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) f.put(y * w + x, (x * 7 + y * 13) % 1000 / 1000f);
			alloc.flush(staging);
			once.run(cmd -> {
				try (MemoryStack stack = stackPush()) {
					VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(2, stack);
					for (int i = 0; i < 2; i++) {
						GpuImage img = i == 0 ? c : d;
						b.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
							.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(img.image())
							.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
							.subresourceRange(r -> r.aspectMask(img.aspect()).levelCount(1).layerCount(1));
					}
					vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, b);
					VkClearColorValue black = VkClearColorValue.calloc(stack);
					black.float32(3, 1f);
					vkCmdClearColorImage(cmd, c.image(), VK_IMAGE_LAYOUT_GENERAL, black,
						VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
					VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
					region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).imageExtent(e -> e.set(w, h, 1));
					vkCmdCopyBufferToImage(cmd, staging.buffer(), d.image(), VK_IMAGE_LAYOUT_GENERAL, region);
					FirstLightPass.fullBarrier(cmd);
					gradient.record(cmd, c.view(), VK_IMAGE_LAYOUT_GENERAL, 0, 0, w, h, 0f, false);
					FirstLightPass.fullBarrier(cmd);
				}
			});
			alloc.destroy(staging);
			return new FakeMain(c, d,
				GpuImage.external("main colour (external)", c.image(), c.view(), c.format(), w, h, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_GENERAL),
				GpuImage.external("main depth (external)", d.image(), d.view(), d.format(), w, h, VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_GENERAL));
		}

		void destroy(GpuAllocator alloc) {
			alloc.destroy(colorOwned);
			alloc.destroy(depthOwned);
		}
	}

	@Test
	void compositeAndFinalThroughOwnedAndExternalImagesSurviveResize() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		FrameClock.Manual clock = new FrameClock.Manual();
		try (VulkanContext ctx = context();
			 GpuAllocator alloc = GpuAllocator.create(ctx);
			 ShaderCompiler compiler = ShaderCompiler.forVulkan12();
			 OneShotCommands once = new OneShotCommands(ctx, clock);
			 SamplerCache samplers = new SamplerCache(ctx.device());
			 DescriptorRing ring = new DescriptorRing(ctx.device(), clock);
			 FirstLightPass gradient = new FirstLightPass(ctx.device(), compiler, VK_FORMAT_R8G8B8A8_UNORM);
			 ResourceSelfTest test = new ResourceSelfTest(ctx, compiler, VK_FORMAT_R8G8B8A8_UNORM)) {
			DeferredDestroyer destroyer = new DeferredDestroyer(clock, ctx.log());
			LayoutTracker tracker = new LayoutTracker();
			RenderTargetSet targets = new RenderTargetSet(alloc, destroyer, ResourceSelfTest.targetFormats(VK_FORMAT_R8G8B8A8_UNORM), 640, 360);
			assertEquals(4, alloc.liveImages(), "two targets × main/alt");

			for (int[] size : new int[][]{{640, 360}, {1280, 720}, {333, 197}}) {
				int w = size[0], h = size[1];
				boolean resized = targets.resize(w, h);
				FakeMain main = FakeMain.create(alloc, once, gradient, w, h);
				once.run(cmd -> test.record(cmd, tracker, ring, samplers, targets, main.color(), main.depth()));
				ResourceSelfTest.Result r = test.verify();
				System.out.println(w + "x" + h + (resized ? " (resized)" : "") + ": " + r.detail());
				assertTrue(r.passed(), r.detail());
				main.destroy(alloc);
				destroyer.collect();
				assertEquals(0, destroyer.pending(), "old targets must be freed once their submission completed");
				assertEquals(4, alloc.liveImages(), "resize must not leak images");
			}
			System.out.println("barriers: " + tracker.barriersEmitted() + " in " + tracker.batchesEmitted() + " batches, "
				+ tracker.barriersElided() + " elided; descriptor sets " + ring.setsAllocated() + " from " + ring.poolsCreated()
				+ " pools (" + ring.poolResets() + " resets); colortex memory " + (targets.bytes() >> 10) + " KiB");

			targets.close();
			assertEquals(2, destroyer.pending());
			clock.advance(); // nothing in flight any more
			destroyer.collect();
			assertEquals(0, alloc.liveImages());
			ctx.messenger().ifPresent(m -> assertEquals(0, m.errorCount(), m.recentMessages().toString()));
		}
	}

	@Test
	void destroysOnlyAfterItsSubmissionCompletes() {
		FrameClock.Manual clock = new FrameClock.Manual();
		DeferredDestroyer d = new DeferredDestroyer(clock, VkLog.STDERR);
		int[] ran = {0};
		d.defer("a", () -> ran[0]++);
		assertEquals(0, d.collect(), "same submission still recording");
		clock.advance();
		d.defer("b", () -> ran[0]++);
		assertEquals(1, d.collect());
		assertEquals(1, ran[0]);
		assertEquals(1, d.drainAll());
		assertEquals(2, ran[0]);
	}

	@Test
	void descriptorPoolsAreRecycledNotLeaked() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		FrameClock.Manual clock = new FrameClock.Manual();
		try (VulkanContext ctx = context();
			 ShaderCompiler compiler = ShaderCompiler.forVulkan12();
			 FullscreenPass pass = new FullscreenPass(ctx.device(), compiler, "test:ring.fsh", ResourceSelfTest.FINAL, 1, new int[]{VK_FORMAT_R8G8B8A8_UNORM}, 0);
			 DescriptorRing ring = new DescriptorRing(ctx.device(), clock)) {
			for (int frame = 0; frame < 300; frame++) {
				for (int i = 0; i < 40; i++) ring.allocate(pass.setLayout()); // ~Iris program count per frame
				clock.advance();
			}
			System.out.println("descriptor ring: " + ring.setsAllocated() + " sets, " + ring.poolsCreated() + " pools, " + ring.poolResets() + " resets");
			assertEquals(12_000, ring.setsAllocated());
			assertTrue(ring.poolsCreated() <= 3, "pools must be reused, created " + ring.poolsCreated());
		}
	}

	@Test
	void threeChannelFormatsArePromotedWhenNotRenderable() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		try (VulkanContext ctx = context()) {
			int rgba16f = RenderTargetSet.renderableFormat(ctx, VK_FORMAT_R16G16B16A16_SFLOAT);
			assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT, rgba16f);
			int rgb16f = RenderTargetSet.renderableFormat(ctx, VK_FORMAT_R16G16B16_SFLOAT);
			int rgb8 = RenderTargetSet.renderableFormat(ctx, VK_FORMAT_R8G8B8_UNORM);
			System.out.println("RGB16F -> " + rgb16f + ", RGB8 -> " + rgb8);
			assertTrue(rgb16f == VK_FORMAT_R16G16B16_SFLOAT || rgb16f == VK_FORMAT_R16G16B16A16_SFLOAT);
			assertTrue(rgb8 == VK_FORMAT_R8G8B8_UNORM || rgb8 == VK_FORMAT_R8G8B8A8_UNORM);
		}
	}
}
