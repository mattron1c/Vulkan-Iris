package dev.vulkaniris.fabric;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vulkaniris.vk.DeferredDestroyer;
import dev.vulkaniris.vk.DescriptorRing;
import dev.vulkaniris.vk.FrameClock;
import dev.vulkaniris.vk.GpuAllocator;
import dev.vulkaniris.vk.GpuImage;
import dev.vulkaniris.vk.ImageReadback;
import dev.vulkaniris.vk.LayoutTracker;
import dev.vulkaniris.vk.SamplerCache;
import dev.vulkaniris.vk.ShaderCompiler;
import dev.vulkaniris.vk.VkCheck;
import dev.vulkaniris.vk.VkLog;
import dev.vulkaniris.vk.VulkanContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisLimits;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.LongBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 6 in-game test ({@code -Dvulkaniris.phase6.selftest=true}): opens (or creates, fixed seed) a test world,
 * then for every pack in {@code shaderpacks/} (or {@code -Dvulkaniris.phase6.packs=a,b} name filters) runs the pack's
 * begin/prepare/deferred/composite/final programs natively on Polydium's frame for {@link #FRAMES_PER_PACK} frames
 * and saves the final image to {@code vulkaniris-phase6/<pack>.png} next to a vanilla capture. Report:
 * {@code vulkaniris-phase6.txt}. A pack passes when every program was built and recorded, all its frames
 * completed, and its output is neither identical to vanilla nor a flat colour.
 */
public final class PostChainCheck {
	/** Phase 7: terrain is drawn with the pack's gbuffers programs (TerrainHook), and the report/PNGs go to vulkaniris-phase7. */
	static final boolean GBUFFERS = Boolean.getBoolean("vulkaniris.phase7.selftest");
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.phase6.selftest") || GBUFFERS;
	private static final String PHASE = GBUFFERS ? "phase7" : "phase6";
	private static final String WORLD = "vulkaniris-phase6";
	/** Phase 7: frames to let Sodium rebuild every chunk in the pack's vertex format before counting. */
	private static final int REBUILD_FRAMES = 240;
	private static final int SETTLE_FRAMES = 300;
	private static final int FRAMES_PER_PACK = 90;
	private static final int TIMEOUT_FRAMES = 20_000;
	private static final VkLog LOG = new VkLog() {
		public void info(String m) { Iris.logger.info("[Vulkan Iris] " + m); }
		public void warn(String m) { Iris.logger.warn("[Vulkan Iris] " + m); }
		public void error(String m) { Iris.logger.error("[Vulkan Iris] " + m); }
	};

	private static final StringBuilder report = new StringBuilder();
	private static int frames, worldFrames;
	private static boolean worldRequested, done, allPassed = true;
	private static int passedPacks;

	private static VulkanContext ctx;
	private static GpuAllocator alloc;
	private static ShaderCompiler compiler;
	private static FrameClock clock;
	private static DeferredDestroyer destroyer;
	private static DescriptorRing ring;
	private static SamplerCache samplers;
	private static LayoutTracker tracker;
	private static long depthView, depthViewImage;
	private static int rebuildFrames, terrainPasses;
	private static boolean terrainFailed;

	private static List<Path> packs;
	private static int packIndex = -1; // -1 = vanilla capture
	private static PostChain chain;
	private static String packName;
	private static int packFrames, skippedFrames;
	private static long recordNanos;
	private static ImageReadback readback;
	private static long readbackSubmit = -1;
	private static byte[] vanilla;
	private static int width, height;

	private PostChainCheck() {
	}

	/** GameRenderer.render TAIL: world setup and timeouts. */
	public static void afterGameRender() {
		if (ENABLED && done && !tornDown && finishSubmit >= 0 && PolydiumAccess.submitCompleted(finishSubmit)) teardown();
		if (!ENABLED || done) return;
		frames++;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				if (!worldRequested && frames > 60 && mc.screen != null) openWorld(mc);
			} else {
				worldFrames++;
			}
			if (frames > TIMEOUT_FRAMES) fail("timed out (world frames " + worldFrames + ", pack index " + packIndex + ")");
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] phase 6 test crashed", t);
			fail("exception: " + t);
		}
	}

	private static void openWorld(Minecraft mc) {
		worldRequested = true;
		if (Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
			line("Opening test world '" + WORLD + "'");
			mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("world open cancelled"));
		} else {
			line("Creating test world '" + WORLD + "' (seed 6, creative, normal preset)");
			WorldDataConfiguration data = WorldDataConfiguration.DEFAULT;
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(data.enabledFeatures()), data);
			mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(6L, true, false),
				WorldPresets::createNormalWorldDimensions, mc.screen);
		}
	}

	/** LevelRenderer.renderLevel RETURN: the vanilla frame is in the main target. */
	public static void afterLevel() {
		if (!ENABLED || done || worldFrames < SETTLE_FRAMES) return;
		try {
			tick();
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] phase 6 test crashed", t);
			fail("exception: " + t);
		}
	}

	private static void tick() throws Exception {
		if (ctx == null) {
			if (!FabricLoader.getInstance().isModLoaded("polydium") || !PolydiumAccess.vulkanActive()) {
				fail("needs Polydium running on Vulkan");
				return;
			}
			init();
		}
		destroyer.collect();
		if (chain != null && chain.levelBegun() && (readbackSubmit >= 0 || PolydiumAccess.inRenderPass())) {
			// terrain already began this frame's chain: it must be finished in this frame's command buffer
			VkCommandBuffer c = PolydiumAccess.frameCommandBuffer();
			if (PolydiumAccess.inRenderPass()) throw new IllegalStateException("level ended inside a Polydium render pass");
			PolydiumAccess.polydiumBarrier(c);
			chain.endLevel(c, tracker);
			PolydiumAccess.polydiumBarrier(c);
		}
		if (readbackSubmit >= 0) {
			if (!PolydiumAccess.submitCompleted(readbackSubmit)) return;
			readbackSubmit = -1;
			finishCapture();
			return;
		}
		if (PolydiumAccess.inRenderPass()) return;
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		if (packIndex >= 0 && chain == null) {
			startPack();
			return;
		}
		width = main.width;
		height = main.height;
		GpuImage color = GpuImage.external("main colour", PolydiumAccess.vkImage(main.getColorTexture()),
			PolydiumAccess.vkImageView(main.getColorTextureView()), PolydiumAccess.vkFormat(main.getColorTexture()), width, height,
			VK_IMAGE_ASPECT_COLOR_BIT, PolydiumAccess.TEXTURE_LAYOUT);
		GpuImage depth = GpuImage.external("main depth", PolydiumAccess.vkImage(main.getDepthTexture()), depthView(main),
			PolydiumAccess.vkFormat(main.getDepthTexture()), width, height, VK_IMAGE_ASPECT_DEPTH_BIT, PolydiumAccess.TEXTURE_LAYOUT);
		VkCommandBuffer cmd = PolydiumAccess.frameCommandBuffer();
		PolydiumAccess.polydiumBarrier(cmd);
		boolean capture;
		if (chain != null && chain.hasTerrain()) {
			long t0 = System.nanoTime();
			if (!chain.levelBegun()) chain.beginLevel(cmd, tracker, color, depth, depthAttachment(main));
			chain.endLevel(cmd, tracker);
			recordNanos += System.nanoTime() - t0;
			if (rebuildFrames < REBUILD_FRAMES) rebuildFrames++;
			else packFrames++;
			capture = packFrames >= FRAMES_PER_PACK;
		} else if (chain != null) {
			long t0 = System.nanoTime();
			boolean recorded = chain.record(cmd, tracker, color, depth);
			if (recorded) {
				recordNanos += System.nanoTime() - t0;
				packFrames++;
			} else {
				skippedFrames++;
			}
			capture = packFrames >= FRAMES_PER_PACK;
		} else {
			capture = true; // vanilla
		}
		if (capture) {
			if (readback != null) readback.close();
			readback = new ImageReadback(ctx.device(), ctx.capabilities(), width, height);
			tracker.use(color, LayoutTracker.Use.TRANSFER_SRC).flush(cmd);
			readback.record(cmd, color.image(), color.layout(), 0, 0);
			readbackSubmit = PolydiumAccess.currentSubmitIndex();
		}
		LayoutTracker.releaseExternal(color);
		LayoutTracker.releaseExternal(depth);
		PolydiumAccess.polydiumBarrier(cmd);
	}

	private static void init() throws Exception {
		ctx = PolydiumAccess.borrow(LOG);
		alloc = GpuAllocator.borrow(ctx, PolydiumAccess.vma());
		compiler = ShaderCompiler.forVulkan12();
		clock = PolydiumAccess.frameClock();
		destroyer = new DeferredDestroyer(clock, LOG);
		ring = new DescriptorRing(ctx.device(), clock);
		samplers = new SamplerCache(ctx.device());
		tracker = new LayoutTracker();
		Iris.initializeForVulkan();
		IrisLimits.VK_CONFORMANCE = true;
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
		String filter = System.getProperty("vulkaniris.phase6.packs", "");
		List<String> wanted = filter.isBlank() ? List.of() : Arrays.stream(filter.split(",")).map(String::trim).map(String::toLowerCase).toList();
		try (Stream<Path> s = Files.list(dir)) {
			packs = s.filter(p -> p.toString().endsWith(".zip") || Files.isDirectory(p))
				.filter(p -> wanted.isEmpty() || wanted.stream().anyMatch(w -> p.getFileName().toString().toLowerCase().contains(w)))
				.sorted().toList();
		}
		Files.createDirectories(outDir());
		var mc = Minecraft.getInstance();
		line("Backend: " + PolydiumAccess.backendDescription());
		line("World '" + WORLD + "', player at " + mc.player.blockPosition().toShortString() + ", day time " + mc.level.getDayTime()
			+ ", main target " + mc.getMainRenderTarget().width + "x" + mc.getMainRenderTarget().height);
		line(packs.size() + " pack(s): " + packs.stream().map(p -> p.getFileName().toString()).toList());
	}

	private static Path outDir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-" + PHASE);
	}

	private static void startPack() {
		Path pack = packs.get(packIndex);
		packName = pack.getFileName().toString();
		packFrames = 0;
		skippedFrames = 0;
		recordNanos = 0;
		line("");
		line("== " + packName);
		FileSystem zip = null;
		long t0 = System.nanoTime();
		try {
			Path root;
			if (Files.isDirectory(pack)) {
				root = pack.resolve("shaders");
			} else {
				zip = FileSystems.newFileSystem(pack, PostChainCheck.class.getClassLoader());
				root = findShaders(zip.getPath("/"));
			}
			if (root == null || !Files.exists(root)) throw new IllegalStateException("no shaders/ directory");
			ImmutableList<StringPair> env = StandardMacros.createStandardEnvironmentDefines();
			ProgramSet set = new ShaderPack(root, new HashMap<>(), env, zip != null).getProgramSet(DimensionId.OVERWORLD);
			RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
			chain = new PostChain(ctx, alloc, destroyer, clock, ring, samplers, compiler, set,
				PolydiumAccess.vkFormat(main.getColorTexture()), PolydiumAccess.vkFormat(main.getDepthTexture()), main.width, main.height, GBUFFERS);
			line("built " + chain.programCount() + " programs (Iris patch → Vulkan GLSL → SPIR-V → pipeline) in " + ms(System.nanoTime() - t0));
			if (chain.hasTerrain()) startTerrain();
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] phase 6: " + packName + " failed to build", t);
			line("FAIL: could not build the chain: " + t);
			allPassed = false;
			if (chain != null) {
				chain.close();
				chain = null;
			}
			nextPack();
		} finally {
			if (zip != null) {
				try {
					zip.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private static void finishCapture() throws Exception {
		byte[] rgba = readback.bytes();
		if (packIndex < 0) {
			vanilla = rgba;
			save("vanilla", rgba);
			line("vanilla frame captured: " + stats(rgba));
			nextPack();
			return;
		}
		save(packName.replaceAll("\\.zip$", ""), rgba);
		int differing = 0, total = width * height;
		for (int i = 0; i < total; i++) {
			int o = i * 4;
			if (Math.abs(rgba[o] - vanilla[o]) + Math.abs(rgba[o + 1] - vanilla[o + 1]) + Math.abs(rgba[o + 2] - vanilla[o + 2]) > 6) differing++;
		}
		String st = stats(rgba);
		// "differs from vanilla" is information only: the world moves between captures, and a pack whose post chain
		// passes colortex0 through without gbuffer data (miniature) legitimately reproduces the scene
		int distinct = distinctColours(rgba);
		boolean flat = distinct < 16;
		boolean pass = !flat && !(GBUFFERS && terrainFailed);
		line(packFrames + " frames recorded" + (skippedFrames > 0 ? " (" + skippedFrames + " skipped: uniform slot still in flight)" : "")
			+ ", CPU record time " + String.format("%.2f", recordNanos / 1e6 / Math.max(1, packFrames)) + " ms/frame (not a benchmark)");
		line("output: " + st + ", differs from vanilla in " + (100L * differing / total) + "% of pixels");
		for (String n : chain.notes()) line("  note: " + n);
		chain.uniformFailures().forEach((k, v) -> line("  uniform " + k + " threw " + v + " (written as 0)"));
		line(pass ? "PASS (renders; see notes for what is not implemented yet)"
			: flat ? "FAIL: output is (nearly) a flat colour (" + distinct + " distinct)" : "FAIL: terrain programs did not draw (see above)");
		if (pass) passedPacks++;
		allPassed &= pass;
		if (chain.hasTerrain()) line("terrain: " + terrainPasses + " terrain passes drawn with the pack's programs over " + (rebuildFrames + packFrames)
			+ " frames; " + chain.staleRegions() + " region draws skipped while chunks were still in the old vertex format");
		stopTerrain();
		// this frame's command buffer (not submitted yet) may already hold the chain's terrain draws: destroy it only
		// once that submission has finished
		PostChain old = chain;
		destroyer.defer("pack chain " + packName, old::close);
		chain = null;
		nextPack();
	}

	private static void nextPack() {
		packIndex++;
		if (packIndex >= packs.size()) finish();
	}

	private static int distinctColours(byte[] rgba) {
		Set<Integer> distinct = new java.util.HashSet<>();
		for (int i = 0; i < rgba.length / 4 && distinct.size() < 4096; i++) {
			distinct.add((rgba[i * 4] & 255) << 16 | (rgba[i * 4 + 1] & 255) << 8 | (rgba[i * 4 + 2] & 255));
		}
		return distinct.size();
	}

	private static String stats(byte[] rgba) {
		long[] sum = new long[3];
		Set<Integer> distinct = new java.util.HashSet<>();
		int n = rgba.length / 4;
		for (int i = 0; i < n; i++) {
			int r = rgba[i * 4] & 255, g = rgba[i * 4 + 1] & 255, b = rgba[i * 4 + 2] & 255;
			sum[0] += r;
			sum[1] += g;
			sum[2] += b;
			if (distinct.size() < 4096) distinct.add(r << 16 | g << 8 | b);
		}
		return "mean rgb (" + sum[0] / n + "," + sum[1] / n + "," + sum[2] / n + "), distinct=" + (distinct.size() >= 4096 ? "4096+" : distinct.size()) + " ";
	}

	/** PNG with row 0 at the top: Polydium stores render targets in GL order (row 0 = bottom), so rows are flipped. */
	private static void save(String name, byte[] rgba) throws Exception {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			int src = height - 1 - y;
			for (int x = 0; x < width; x++) {
				int o = (src * width + x) * 4;
				img.setRGB(x, y, (rgba[o] & 255) << 16 | (rgba[o + 1] & 255) << 8 | (rgba[o + 2] & 255));
			}
		}
		Path file = outDir().resolve(name + ".png");
		ImageIO.write(img, "png", file.toFile());
		line("saved " + Minecraft.getInstance().gameDirectory.toPath().relativize(file));
	}

	private static Path findShaders(Path zipRoot) throws Exception {
		if (Files.isDirectory(zipRoot.resolve("shaders"))) return zipRoot.resolve("shaders");
		try (Stream<Path> s = Files.list(zipRoot)) {
			return s.map(p -> p.resolve("shaders")).filter(Files::isDirectory).findFirst().orElse(null);
		}
	}

	/** Phase 7: rebuild chunks in the pack's vertex format and take over Polydium's terrain draw. */
	private static void startTerrain() {
		rebuildFrames = 0;
		terrainPasses = 0;
		terrainFailed = false;
		WorldRenderingSettings.INSTANCE.setVertexFormat(chain.vertexFormat());
		TerrainHook.sink = PostChainCheck::drawTerrain;
		Minecraft.getInstance().levelRenderer.allChanged();
		line("terrain programs active; chunks rebuilding in the pack's vertex format (stride "
			+ chain.vertexFormat().getVertexFormat().getStride() + " bytes)");
	}

	private static void stopTerrain() {
		if (TerrainHook.sink == null) return;
		TerrainHook.sink = null;
		WorldRenderingSettings.INSTANCE.setVertexFormat(ChunkMeshFormats.COMPACT);
		Minecraft.getInstance().levelRenderer.allChanged();
	}

	/** TerrainHook.Sink: Polydium's terrain pass, drawn with the pack's program inside Polydium's frame. */
	private static boolean drawTerrain(int kind, List<PostChain.RegionDraw> draws, PostChain.TerrainInputs inputs) {
		if (chain == null || terrainFailed) return true; // the chunk format no longer matches Polydium's pipeline
		try {
			if (PolydiumAccess.inRenderPass()) throw new IllegalStateException("terrain draw inside a Polydium render pass");
			RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
			GpuImage color = GpuImage.external("main colour", PolydiumAccess.vkImage(main.getColorTexture()),
				PolydiumAccess.vkImageView(main.getColorTextureView()), PolydiumAccess.vkFormat(main.getColorTexture()), main.width, main.height,
				VK_IMAGE_ASPECT_COLOR_BIT, PolydiumAccess.TEXTURE_LAYOUT);
			GpuImage depth = GpuImage.external("main depth", PolydiumAccess.vkImage(main.getDepthTexture()), depthView(main),
				PolydiumAccess.vkFormat(main.getDepthTexture()), main.width, main.height, VK_IMAGE_ASPECT_DEPTH_BIT, PolydiumAccess.TEXTURE_LAYOUT);
			VkCommandBuffer cmd = PolydiumAccess.frameCommandBuffer();
			PolydiumAccess.polydiumBarrier(cmd);
			if (!chain.levelBegun()) chain.beginLevel(cmd, tracker, color, depth, depthAttachment(main));
			if (kind == TerrainHook.TRANSLUCENT && !chain.translucentDone()) chain.beforeTranslucent(cmd, tracker);
			chain.drawTerrain(cmd, tracker, kind, draws, inputs);
			PolydiumAccess.polydiumBarrier(cmd);
			terrainPasses++;
		} catch (Throwable t) {
			terrainFailed = true;
			Iris.logger.error("[Vulkan Iris] phase 7 terrain draw failed", t);
			line("FAIL: terrain draw: " + t);
			allPassed = false;
		}
		return true;
	}

	/** Polydium's own view of the main depth (all aspects), for drawing into it. */
	private static GpuImage depthAttachment(RenderTarget main) {
		return GpuImage.external("main depth attachment", PolydiumAccess.vkImage(main.getDepthTexture()),
			PolydiumAccess.vkImageView(main.getDepthTextureView()), PolydiumAccess.vkFormat(main.getDepthTexture()), main.width, main.height,
			VK_IMAGE_ASPECT_DEPTH_BIT, PolydiumAccess.TEXTURE_LAYOUT);
	}

	/** Our own depth-aspect view of Polydium's depth image. Recreated if the image changes. */
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

	private static String ms(long nanos) {
		return String.format("%.0f ms", nanos / 1e6);
	}

	private static void fail(String why) {
		allPassed = false;
		line("FAIL: " + why);
		finish();
	}

	/**
	 * Ends the test. The frame being recorded may still reference Vulkan Iris's objects, and Polydium keeps recording
	 * it after this returns, so they are destroyed (and the game stopped) only once that frame's submission finished.
	 */
	private static void finish() {
		if (done) return;
		done = true;
		if (ctx != null) {
			stopTerrain();
			finishSubmit = PolydiumAccess.currentSubmitIndex();
		} else {
			teardown();
		}
	}

	private static long finishSubmit = -1;
	private static boolean tornDown;

	private static void teardown() {
		if (tornDown) return;
		tornDown = true;
		if (ctx != null) {
			vkQueueWaitIdle(ctx.graphicsQueue());
			if (chain != null) chain.close();
			if (readback != null) readback.close();
			destroyer.drainAll();
			if (depthView != 0) vkDestroyImageView(ctx.device(), depthView, null);
			ring.close();
			samplers.close();
			compiler.close();
			line("live images left after shutdown: " + alloc.liveImages());
			alloc.close();
			ctx.close();
		}
		int total = packs == null ? 0 : packs.size();
		String head = (GBUFFERS ? "Vulkan Iris Phase 7 terrain gbuffers test — " : "Vulkan Iris Phase 6 post chain test — ") + ZonedDateTime.now() + "\nRESULT: " + (allPassed && total > 0 ? "PASS" : "FAIL")
			+ " — " + passedPacks + "/" + total + " packs rendered their deferred/composite/final chain on Vulkan\n";
		Iris.logger.info("[Vulkan Iris] PHASE 6 " + head + report);
		try {
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-" + PHASE + ".txt"), head + report);
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write phase 6 report", e);
		}
		Minecraft.getInstance().stop();
	}

	private static void line(String s) {
		report.append(s).append('\n');
		LOG.info(s);
	}
}
