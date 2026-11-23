package dev.vulkaniris.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkaniris.vk.*;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Phase 3 "first light": Vulkan Iris's own SPIR-V pipeline drawing into Minecraft's main render target through the
 * Vulkan device Polydium owns — no OpenGL anywhere in the path, no second device.
 *
 * <ul>
 *   <li>{@code -Dvulkaniris.firstlight=true}: draw an animated 256×256 test quad in the top-left every frame.</li>
 *   <li>{@code -Dvulkaniris.firstlight.selftest=true}: draw the static pattern once, read the pixels back from the
 *       GPU and check them, save a normal Minecraft screenshot, write {@code vulkaniris-firstlight.txt}, quit.</li>
 * </ul>
 * Off by default: it is a test, not a feature.
 */
public final class FirstLight {
	static final boolean SELFTEST = Boolean.getBoolean("vulkaniris.firstlight.selftest");
	static final boolean ENABLED = SELFTEST || Boolean.getBoolean("vulkaniris.firstlight");

	private static final int X = 16, Y = 16, W = 256, H = 256;
	private static final int WARMUP_FRAMES = 120, TIMEOUT_FRAMES = 3000;

	private enum Phase {WARMUP, DRAWN, VERIFIED, SCREENSHOT, DONE}

	private static final VkLog LOG = new VkLog() {
		public void info(String m) { Iris.logger.info("[Vulkan Iris] " + m); }
		public void warn(String m) { Iris.logger.warn("[Vulkan Iris] " + m); }
		public void error(String m) { Iris.logger.error("[Vulkan Iris] " + m); }
	};

	private static Phase phase = Phase.WARMUP;
	private static int frames;
	private static int doneAtFrame;
	private static VulkanContext ctx;
	private static ShaderCompiler compiler;
	private static FirstLightPass pass;
	private static ImageReadback readback;
	private static long pendingSubmit = -1;
	private static final StringBuilder report = new StringBuilder();
	private static boolean passed;
	private static final long startNanos = System.nanoTime();

	private FirstLight() {
	}

	/** Called at the end of {@code GameRenderer.render}, after the GUI, before the frame is presented. */
	public static void afterGameRender() {
		if (!ENABLED || phase == Phase.DONE) return;
		frames++;
		try {
			tick();
		} catch (Throwable t) {
			fail("exception: " + t);
			Iris.logger.error("[Vulkan Iris] first light crashed", t);
		}
	}

	private static void tick() throws Exception {
		Minecraft mc = Minecraft.getInstance();
		if (phase == Phase.WARMUP) {
			if (frames < WARMUP_FRAMES) return;
			if (!FabricLoader.getInstance().isModLoaded("polydium") || !PolydiumAccess.vulkanActive()) {
				fail("Minecraft is not running on a Vulkan device Vulkan Iris can borrow (Polydium on Vulkan required for this test). "
					+ "Backend: " + RenderSystem.getDevice().getBackendName() + " / " + RenderSystem.getDevice().getRenderer());
				return;
			}
			if (ctx == null) init(mc);
			if (PolydiumAccess.inRenderPass()) return; // never record inside Polydium's open render pass
		}

		if (!SELFTEST) { // overlay mode: draw every frame
			if (!PolydiumAccess.inRenderPass()) record(mc, (System.nanoTime() - startNanos) / 1e9f, true, false);
			return;
		}

		switch (phase) {
			case WARMUP -> {
				record(mc, 0f, false, true);
				pendingSubmit = PolydiumAccess.currentSubmitIndex();
				line("Recorded into Polydium frame command buffer, submission #" + pendingSubmit + " at frame " + frames);
				phase = Phase.DRAWN;
			}
			case DRAWN -> {
				if (PolydiumAccess.submitCompleted(pendingSubmit)) verify();
				else if (frames > TIMEOUT_FRAMES) fail("submission #" + pendingSubmit + " never completed");
			}
			case VERIFIED -> {
				if (PolydiumAccess.inRenderPass()) return;
				// draw again into this frame, then let Minecraft's own screenshot code read the main target
				record(mc, 0f, false, false);
				Path dir = mc.gameDirectory.toPath().resolve("screenshots");
				Screenshot.grab(mc.gameDirectory, "vulkaniris-firstlight.png", mc.getMainRenderTarget(), 1,
					msg -> line("Minecraft screenshot: " + msg.getString() + " (" + dir.resolve("vulkaniris-firstlight.png") + ")"));
				phase = Phase.SCREENSHOT;
				doneAtFrame = frames + 120;
			}
			case SCREENSHOT -> {
				if (frames >= doneAtFrame) finish();
			}
			default -> {
			}
		}
	}

	private static void init(Minecraft mc) {
		ctx = PolydiumAccess.borrow(LOG);
		compiler = ShaderCompiler.forVulkan12();
		RenderTarget target = mc.getMainRenderTarget();
		int format = PolydiumAccess.vkFormat(target.getColorTexture());
		if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM) {
			throw new IllegalStateException("main render target has VkFormat " + format + "; first light only handles RGBA8/BGRA8 UNORM");
		}
		pass = new FirstLightPass(ctx.device(), compiler, format);
		readback = new ImageReadback(ctx.device(), ctx.capabilities(), W, H);
		line("Backend: " + PolydiumAccess.backendDescription());
		line("Borrowed device: " + ctx.capabilities().name + ", queue family " + ctx.graphicsQueueFamily()
			+ ", main target " + target.width + "x" + target.height + " VkFormat " + format);
	}

	private static void record(Minecraft mc, float time, boolean animate, boolean withReadback) {
		RenderTarget target = mc.getMainRenderTarget();
		VkCommandBuffer cmd = PolydiumAccess.frameCommandBuffer();
		long image = PolydiumAccess.vkImage(target.getColorTexture());
		long view = PolydiumAccess.vkImageView(target.getColorTextureView());
		PolydiumAccess.polydiumBarrier(cmd);
		pass.record(cmd, view, PolydiumAccess.TEXTURE_LAYOUT, X, Y, W, H, time, animate);
		PolydiumAccess.polydiumBarrier(cmd);
		if (withReadback) readback.record(cmd, image, PolydiumAccess.TEXTURE_LAYOUT, X, Y);
	}

	private static void verify() {
		boolean bgra = pass.colorFormat() == VK_FORMAT_B8G8R8A8_UNORM;
		int[] centre = rgba(readback.pixel(W / 2, H / 2), bgra);
		int[] left = rgba(readback.pixel(2, H / 2), bgra);
		int[] right = rgba(readback.pixel(W - 3, H / 2), bgra);
		boolean centreOk = ImageReadback.close(centre, FirstLightPass.CENTRE_RGBA, 2);
		boolean rampOk = left[0] < 8 && right[0] > 247;
		passed = centreOk && rampOk;
		line("GPU readback centre " + ImageReadback.rgba(centre) + " (expected ~" + ImageReadback.rgba(FirstLightPass.CENTRE_RGBA)
			+ ") left " + ImageReadback.rgba(left) + " right " + ImageReadback.rgba(right));
		line("Pixel check: " + (passed ? "PASS" : "FAIL") + " (centre " + centreOk + ", red ramp " + rampOk + ")");
		phase = Phase.VERIFIED;
	}

	private static int[] rgba(int[] p, boolean bgra) {
		return bgra ? new int[]{p[2], p[1], p[0], p[3]} : p;
	}

	private static void fail(String why) {
		passed = false;
		line("FAIL: " + why);
		finish();
	}

	private static void finish() {
		phase = Phase.DONE;
		StringBuilder out = new StringBuilder();
		out.append("Vulkan Iris first-light self-test — ").append(ZonedDateTime.now()).append('\n');
		out.append("RESULT: ").append(passed ? "PASS" : "FAIL").append('\n').append(report);
		if (ctx != null) out.append('\n').append(DiagnosticsReport.build(ctx, null));
		String text = out.toString();
		Iris.logger.info("[Vulkan Iris] FIRST LIGHT " + (passed ? "PASS" : "FAIL") + "\n" + report);
		try {
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-firstlight.txt"), text);
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write first-light report", e);
		}
		if (pass != null) {
			// GPU may still reference the pipeline in an in-flight frame: wait for the queue first
			org.lwjgl.vulkan.VK10.vkQueueWaitIdle(ctx.graphicsQueue());
			pass.close();
			readback.close();
			compiler.close();
			ctx.close();
		}
		if (SELFTEST) Minecraft.getInstance().stop();
	}

	private static void line(String s) {
		report.append(s).append('\n');
		LOG.info(s);
	}
}
