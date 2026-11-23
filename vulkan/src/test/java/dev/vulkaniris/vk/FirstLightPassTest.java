package dev.vulkaniris.vk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs the Phase 3 pass exactly the way it runs inside Minecraft on Polydium — image permanently in
 * VK_IMAGE_LAYOUT_GENERAL, full memory barriers around the pass, LOAD/STORE — and checks the pixels.
 */
class FirstLightPassTest {
	private static final int SIZE = 512, X = 64, Y = 64, W = 256, H = 256;

	@Test
	void compileErrorsNameTheShaderAndShowTheLine() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		try (ShaderCompiler c = ShaderCompiler.forVulkan12()) {
			String bad = "#version 450\nlayout(location=0) out vec4 o;\nvoid main() {\n    o = vec4(undefinedThing);\n}\n";
			ShaderCompiler.ShaderCompileException e = assertThrows(ShaderCompiler.ShaderCompileException.class,
				() -> c.compile("test:bad.fsh", ShaderCompiler.Stage.FRAGMENT, bad));
			assertTrue(e.getMessage().contains("test:bad.fsh"), e.getMessage());
			assertTrue(e.getMessage().contains("fragment"), e.getMessage());
			assertTrue(e.getMessage().contains(">>    4 |"), e.getMessage());
			System.out.println(e.getMessage());

			byte[] a = c.compile("v", ShaderCompiler.Stage.VERTEX, FirstLightPass.VERTEX);
			byte[] b = c.compile("v", ShaderCompiler.Stage.VERTEX, FirstLightPass.VERTEX);
			assertSame(a, b, "second compile must come from the cache");
		}
	}

	@Test
	void drawsVerifiedGradientIntoGeneralLayoutImage() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"));
		try (VulkanContext ctx = VulkanContext.createOwned(new VulkanContext.Options("first light test", Boolean.getBoolean("vulkaniris.validation"), false), VkLog.STDERR);
			 ShaderCompiler compiler = ShaderCompiler.forVulkan12();
			 FirstLightPass pass = new FirstLightPass(ctx.device(), compiler, VK_FORMAT_R8G8B8A8_UNORM);
			 ImageReadback readback = new ImageReadback(ctx.device(), ctx.capabilities(), SIZE, SIZE)) {
			VkDevice dev = ctx.device();
			long image = 0, mem = 0, view = 0, pool = 0, fence = 0;
			try (MemoryStack stack = stackPush()) {
				LongBuffer lp = stack.mallocLong(1);
				VkCheck.check(vkCreateImage(dev, VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
					.format(VK_FORMAT_R8G8B8A8_UNORM).extent(e -> e.set(SIZE, SIZE, 1)).mipLevels(1).arrayLayers(1)
					.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
					.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
					.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, lp), "vkCreateImage");
				image = lp.get(0);
				VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
				vkGetImageMemoryRequirements(dev, image, req);
				VkCheck.check(vkAllocateMemory(dev, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
					.memoryTypeIndex(GpuSelfTest.findMemoryType(ctx.capabilities(), req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lp), "alloc");
				mem = lp.get(0);
				VkCheck.check(vkBindImageMemory(dev, image, mem, 0), "bind");
				VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
				VkCheck.check(vkCreateImageView(dev, VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
					.viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM).subresourceRange(range), null, lp), "view");
				view = lp.get(0);

				VkCheck.check(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(ctx.graphicsQueueFamily()), null, lp), "pool");
				pool = lp.get(0);
				PointerBuffer pp = stack.mallocPointer(1);
				VkCheck.check(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
					.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cmd");
				VkCommandBuffer cmd = new VkCommandBuffer(pp.get(0), dev);
				VkCheck.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin");

				// same state Polydium leaves textures in: GENERAL, cleared
				VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack);
				toGeneral.get(0).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image).subresourceRange(range)
					.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
				vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toGeneral);
				VkClearColorValue black = VkClearColorValue.calloc(stack);
				black.float32(3, 1.0f);
				vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_GENERAL, black, range);

				FirstLightPass.fullBarrier(cmd);
				pass.record(cmd, view, VK_IMAGE_LAYOUT_GENERAL, X, Y, W, H, 0f, false);
				FirstLightPass.fullBarrier(cmd);
				readback.record(cmd, image, VK_IMAGE_LAYOUT_GENERAL, 0, 0);
				VkCheck.check(vkEndCommandBuffer(cmd), "end");

				VkCheck.check(vkCreateFence(dev, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "fence");
				fence = lp.get(0);
				VkCheck.check(vkQueueSubmit(ctx.graphicsQueue(), VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(pp), fence), "submit");
				VkCheck.check(vkWaitForFences(dev, lp.put(0, fence), true, 5_000_000_000L), "wait");

				int[] centre = readback.pixel(X + W / 2, Y + H / 2);
				int[] outside = readback.pixel(8, 8);
				int[] left = readback.pixel(X + 2, Y + H / 2);
				int[] right = readback.pixel(X + W - 3, Y + H / 2);
				System.out.println("first light centre " + ImageReadback.rgba(centre) + " outside " + ImageReadback.rgba(outside)
					+ " left " + ImageReadback.rgba(left) + " right " + ImageReadback.rgba(right));
				assertTrue(ImageReadback.close(centre, FirstLightPass.CENTRE_RGBA, 2), "centre " + ImageReadback.rgba(centre));
				assertArrayEquals(new int[]{0, 0, 0, 255}, outside, "pixels outside the rectangle must be preserved (LOAD/STORE)");
				assertTrue(left[0] < 8 && right[0] > 247, "red must ramp left→right: " + ImageReadback.rgba(left) + " → " + ImageReadback.rgba(right));
				ctx.messenger().ifPresent(m -> assertEquals(0, m.errorCount(), m.recentMessages().toString()));
			} finally {
				if (fence != 0) vkDestroyFence(dev, fence, null);
				if (pool != 0) vkDestroyCommandPool(dev, pool, null);
				if (view != 0) vkDestroyImageView(dev, view, null);
				if (image != 0) vkDestroyImage(dev, image, null);
				if (mem != 0) vkFreeMemory(dev, mem, null);
			}
		}
	}
}
