package dev.vulkaniris.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vulkaniris.vk.*;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.time.ZonedDateTime;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 4 in-game self-test ({@code -Dvulkaniris.phase4.selftest=true}): the resource layer running inside
 * Minecraft on Polydium's device. colortex targets come from <em>Polydium's</em> VMA allocator, layouts/barriers from
 * {@link LayoutTracker}, descriptor sets from a {@link DescriptorRing} keyed on Polydium's submit counter, and the
 * same {@link ResourceSelfTest} composite → final pair as the headless test is recorded into the live frame.
 * Then the main window is resized-in-software (targets recreated) and the test repeats, so deferred destruction
 * is checked against real frames in flight. Writes {@code vulkaniris-phase4.txt} and quits.
 */
public final class ResourceCheck {
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.phase4.selftest");
	private static final int WARMUP_FRAMES = 120, TIMEOUT_FRAMES = 3000, ROUNDS = 2;

	private static final VkLog LOG = new VkLog() {
		public void info(String m) { Iris.logger.info("[Vulkan Iris] " + m); }
		public void warn(String m) { Iris.logger.warn("[Vulkan Iris] " + m); }
		public void error(String m) { Iris.logger.error("[Vulkan Iris] " + m); }
	};

	private static boolean done;
	private static int frames, round;
	private static long pending = -1;
	private static boolean passed = true;
	private static final StringBuilder report = new StringBuilder();

	private static VulkanContext ctx;
	private static GpuAllocator alloc;
	private static ShaderCompiler compiler;
	private static FrameClock clock;
	private static DeferredDestroyer destroyer;
	private static DescriptorRing ring;
	private static SamplerCache samplers;
	private static LayoutTracker tracker;
	private static RenderTargetSet targets;
	private static ResourceSelfTest test;
	private static long depthView;
	private static long depthViewImage;

	private ResourceCheck() {
	}

	public static void afterGameRender() {
		if (!ENABLED || done) return;
		frames++;
		try {
			tick();
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] phase 4 self-test crashed", t);
			fail("exception: " + t);
		}
	}

	private static void tick() {
		if (frames < WARMUP_FRAMES) return;
		if (ctx == null) {
			if (!FabricLoader.getInstance().isModLoaded("polydium") || !PolydiumAccess.vulkanActive()) {
				fail("needs Polydium running on Vulkan");
				return;
			}
			init();
		}
		if (destroyer != null) destroyer.collect();
		if (pending >= 0) {
			if (!PolydiumAccess.submitCompleted(pending)) {
				if (frames > TIMEOUT_FRAMES) fail("submission #" + pending + " never completed");
				return;
			}
			ResourceSelfTest.Result r = test.verify();
			line("round " + round + " (targets " + targets.width() + "x" + targets.height() + ", generation " + targets.generation() + "): "
				+ (r.passed() ? "PASS " : "FAIL ") + r.detail());
			passed &= r.passed();
			pending = -1;
			round++;
			if (round >= ROUNDS) {
				finish();
				return;
			}
		}
		if (PolydiumAccess.inRenderPass()) return;
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		int w = main.width, h = main.height;
		if (round == 1) {
			// second round: recreate at a different size while round 0's targets may still be in flight, then back
			int before = destroyer.pending();
			targets.resize(w / 2, h / 2);
			targets.resize(w, h);
			line("resize ×2 queued " + (destroyer.pending() - before) + " deferred destroys while frames are in flight");
		}
		GpuImage color = GpuImage.external("main colour", PolydiumAccess.vkImage(main.getColorTexture()),
			PolydiumAccess.vkImageView(main.getColorTextureView()), PolydiumAccess.vkFormat(main.getColorTexture()), w, h,
			VK_IMAGE_ASPECT_COLOR_BIT, PolydiumAccess.TEXTURE_LAYOUT);
		GpuImage depth = GpuImage.external("main depth", PolydiumAccess.vkImage(main.getDepthTexture()), depthView(main),
			PolydiumAccess.vkFormat(main.getDepthTexture()), w, h, VK_IMAGE_ASPECT_DEPTH_BIT, PolydiumAccess.TEXTURE_LAYOUT);
		VkCommandBuffer cmd = PolydiumAccess.frameCommandBuffer();
		PolydiumAccess.polydiumBarrier(cmd);
		test.record(cmd, tracker, ring, samplers, targets, color, depth);
		PolydiumAccess.polydiumBarrier(cmd);
		pending = PolydiumAccess.currentSubmitIndex();
		line("round " + round + " recorded into Polydium submission #" + pending + " at frame " + frames);
	}

	private static void init() {
		ctx = PolydiumAccess.borrow(LOG);
		alloc = GpuAllocator.borrow(ctx, PolydiumAccess.vma());
		compiler = ShaderCompiler.forVulkan12();
		clock = PolydiumAccess.frameClock();
		destroyer = new DeferredDestroyer(clock, LOG);
		ring = new DescriptorRing(ctx.device(), clock);
		samplers = new SamplerCache(ctx.device());
		tracker = new LayoutTracker();
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		int colorFormat = PolydiumAccess.vkFormat(main.getColorTexture());
		if (main.getDepthTexture() == null) throw new IllegalStateException("main target has no depth texture");
		targets = new RenderTargetSet(alloc, destroyer, ResourceSelfTest.targetFormats(colorFormat), main.width, main.height);
		test = new ResourceSelfTest(ctx, compiler, colorFormat);
		line("Backend: " + PolydiumAccess.backendDescription());
		line("Borrowed device " + ctx.capabilities().name + " + Polydium's VMA allocator; main target " + main.width + "x" + main.height
			+ " colour VkFormat " + colorFormat + ", depth VkFormat " + PolydiumAccess.vkFormat(main.getDepthTexture()));
		line("colortex0/1 allocated through Polydium's VMA: " + (targets.bytes() >> 10) + " KiB");
	}

	/** Our own depth-aspect view of Polydium's depth image (its own view may include stencil). Recreated if the image changes. */
	private static long depthView(RenderTarget main) {
		long image = PolydiumAccess.vkImage(main.getDepthTexture());
		if (image == depthViewImage) return depthView;
		if (depthView != 0) {
			long old = depthView;
			destroyer.defer("depth view", () -> vkDestroyImageView(ctx.device(), old, null));
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateImageView(ctx.device(), VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
				.viewType(VK_IMAGE_VIEW_TYPE_2D).format(PolydiumAccess.vkFormat(main.getDepthTexture()))
				.subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1)), null, lp), "vkCreateImageView(depth)");
			depthView = lp.get(0);
			depthViewImage = image;
			return depthView;
		}
	}

	private static void fail(String why) {
		passed = false;
		line("FAIL: " + why);
		finish();
	}

	private static void finish() {
		done = true;
		if (ctx != null) {
			vkQueueWaitIdle(ctx.graphicsQueue());
			targets.close();
			int drained = destroyer.drainAll();
			line("shutdown: drained " + drained + " deferred destroys, " + destroyer.destroyedTotal() + " total; live images left "
				+ alloc.liveImages() + "; barriers " + tracker.barriersEmitted() + " in " + tracker.batchesEmitted() + " batches; descriptor sets "
				+ ring.setsAllocated() + " from " + ring.poolsCreated() + " pools");
			if (alloc.liveImages() != 0) {
				passed = false;
				line("FAIL: leaked images");
			}
			if (depthView != 0) vkDestroyImageView(ctx.device(), depthView, null);
			test.close();
			ring.close();
			samplers.close();
			compiler.close();
			alloc.close();
			ctx.close();
		}
		String text = "Vulkan Iris Phase 4 resource self-test — " + ZonedDateTime.now() + "\nRESULT: " + (passed ? "PASS" : "FAIL") + "\n" + report;
		Iris.logger.info("[Vulkan Iris] PHASE 4 " + (passed ? "PASS" : "FAIL") + "\n" + report);
		try {
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-phase4.txt"), text);
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write phase 4 report", e);
		}
		Minecraft.getInstance().stop();
	}

	private static void line(String s) {
		report.append(s).append('\n');
		LOG.info(s);
	}
}
