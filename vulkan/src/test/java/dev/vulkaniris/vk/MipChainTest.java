package dev.vulkaniris.vk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * {@link MipChain}: an 8x8 image, left half white and right half black, downsampled to 4 levels. Every level must be
 * the box-filtered average of the one above it, as with glGenerateMipmap: level 1 keeps the exact halves, the 1x1
 * level is mid grey. Also checks the render targets a pack mipmaps get their chain.
 */
class MipChainTest {
	@Test
	void levelsAreDownsampledLevelZero() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		FrameClock.Manual clock = new FrameClock.Manual();
		try (VulkanContext ctx = VulkanContext.createOwned(new VulkanContext.Options("mip test", Boolean.getBoolean("vulkaniris.validation"), false), VkLog.STDERR);
			 GpuAllocator alloc = GpuAllocator.create(ctx);
			 OneShotCommands once = new OneShotCommands(ctx, clock)) {
			assertTrue(MipChain.canGenerate(ctx, VK_FORMAT_R8G8B8A8_UNORM));
			int levels = GpuAllocator.fullMipChain(8, 8);
			assertEquals(4, levels);
			GpuImage img = alloc.createImage("mips", VK_FORMAT_R8G8B8A8_UNORM, 8, 8,
				VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT, levels);
			assertNotEquals(img.view(), img.sampleView(), "sampling view covers all levels, the attachment view only level 0");
			GpuBuffer staging = alloc.createBuffer("level 0", 8 * 8 * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
			ByteBuffer px = MemoryUtil.memByteBuffer(staging.mapped(), 8 * 8 * 4);
			for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
				byte v = (byte) (x < 4 ? 255 : 0);
				px.put((y * 8 + x) * 4, v).put((y * 8 + x) * 4 + 1, v).put((y * 8 + x) * 4 + 2, v).put((y * 8 + x) * 4 + 3, (byte) 255);
			}
			alloc.flush(staging);
			LayoutTracker tracker = new LayoutTracker();
			ImageReadback level1 = new ImageReadback(ctx.device(), ctx.capabilities(), 4, 4);
			ImageReadback level3 = new ImageReadback(ctx.device(), ctx.capabilities(), 1, 1);
			once.run(cmd -> {
				tracker.use(img, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
					region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e -> e.set(8, 8, 1));
					vkCmdCopyBufferToImage(cmd, staging.buffer(), img.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
				}
				assertTrue(MipChain.generate(cmd, tracker, img));
				assertEquals(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, img.layout());
				level1.record(cmd, img.image(), img.layout(), 0, 0, VK_IMAGE_ASPECT_COLOR_BIT, 1);
				level3.record(cmd, img.image(), img.layout(), 0, 0, VK_IMAGE_ASPECT_COLOR_BIT, 3);
			});
			assertArrayEquals(new int[]{255, 255, 255, 255}, level1.pixel(1, 2), "level 1, left half");
			assertArrayEquals(new int[]{0, 0, 0, 255}, level1.pixel(2, 2), "level 1, right half");
			int[] grey = level3.pixel(0, 0);
			assertTrue(ImageReadback.close(grey, new int[]{128, 128, 128, 255}, 2), "1x1 level is the average: " + ImageReadback.rgba(grey));

			// a target the pack mipmaps (and only that one) gets a full chain
			DeferredDestroyer destroyer = new DeferredDestroyer(clock, ctx.log());
			int[] formats = new int[RenderTargetSet.MAX_TARGETS];
			formats[0] = formats[3] = VK_FORMAT_R8G8B8A8_UNORM;
			boolean[] mipmapped = new boolean[RenderTargetSet.MAX_TARGETS];
			mipmapped[3] = true;
			RenderTargetSet targets = new RenderTargetSet(alloc, destroyer, formats, 640, 360, (i, w, h) -> new int[]{w, h}, mipmapped);
			assertEquals(1, targets.read(0).mipLevels());
			assertEquals(10, targets.read(3).mipLevels(), "640x360 → 10 levels");
			targets.close();
			clock.advance();
			destroyer.collect();

			level1.close();
			level3.close();
			alloc.destroy(staging);
			alloc.destroy(img);
			ctx.messenger().ifPresent(m -> assertEquals(0, m.errorCount(), m.recentMessages().toString()));
		}
	}
}
