package dev.vulkaniris.fabric;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vulkaniris.vk.GpuImage;
import dev.vulkaniris.vk.GraphicsProgram;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import net.minecraft.client.renderer.RenderPipelines;
import org.polyfrost.polydium.client.perf.SkyFarPlane;
import org.polyfrost.polydium.client.perf.VulkanDynamicState;
import org.polyfrost.polydium.vulkan.VulkanGpuBuffer;
import org.polyfrost.polydium.vulkan.VulkanGpuTextureView;
import org.polyfrost.polydium.vulkan.VulkanRenderPass;
import org.polyfrost.polydium.vulkan.VulkanRenderPipeline;
import org.polyfrost.polydium.vulkan.compat.RenderPassDescriptor;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 7c: draws vanilla geometry (entities, block entities, sky, particles, weather, lines ...) that Polydium records
 * into Minecraft's main target during the world pass with the pack's gbuffers programs instead — what Iris's
 * {@code MixinShaderManager_Overrides} + {@code ExtendedShader} do on GL (same vanilla buffers and uniform blocks, the
 * pack's program and targets).
 *
 * <p>A Polydium render pass is one dynamic-rendering scope on the main colour/depth. At the first redirected draw this
 * ends that scope, Vulkan Iris then draws into the colortex targets (+ main depth), and before Polydium's next own draw
 * (or the pass's close) the scope is resumed with the same attachments (load/store), Polydium's pipeline, viewport and
 * scissor re-bound and its state cache ({@link VulkanDynamicState}) reset so it re-pushes everything. Facts used, read
 * from Polydium 1.4.21's bytecode (nothing copied): draws all go through {@code drawIndexed(IIIII)} / {@code draw(IIII)},
 * which call {@code vkCmdDrawIndexed}/{@code vkCmdDraw} with their arguments unchanged; the viewport is
 * (0, 0, outputWidth, outputHeight); the pipeline is bound at {@code setPipeline}; textures stay in GENERAL.</p>
 */
public final class VanillaHook {
	/** Where redirected draws go (the Phase 7 self-test). */
	public interface Sink {
		/** True while the world is being drawn with the pack (between beginLevel and endLevel). */
		boolean active();

		/** The pack program for this vanilla pipeline, or null to leave the draw to Polydium. */
		ShaderKey key(RenderPipeline pipeline);

		void draw(VkCommandBuffer cmd, ShaderKey key, RenderPipeline pipeline, PostChain.PassState state, boolean depth, boolean indexed,
				  int a, int b, int c, int d, int e);

		/** Ends Vulkan Iris's rendering scope before Polydium resumes. */
		void end(VkCommandBuffer cmd);

		void failed(Throwable t);
	}

	/** Result of {@link #draw}: Polydium's draw call goes ahead untouched. */
	public static final int PASS = 0;
	/** The draw was made with the pack's program: cancel Polydium's. */
	public static final int HANDLED = 1;
	/** Polydium's scope was resumed: its descriptors must be pushed again before its draw. */
	public static final int RESUMED = 2;

	static volatile Sink sink;

	private static final class Current {
		VulkanRenderPass pass;
		GpuTexture colorTexture, depthTexture;
		long colorView, depthView;
		int depthFormat;
		int x, y, w, h;
		int[] scissor;
		boolean suspended;
	}

	private static Current current;
	private static Object rejectedPass;
	private static final java.util.Set<Object> createdPasses = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private static final java.util.Set<Object> closedPasses = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private static String rejectReason;
	private static long redirectedPasses;

	private VanillaHook() {
	}

	static long redirectedPasses() {
		return redirectedPasses;
	}

	private static Map<RenderPipeline, RenderPipeline> originals;

	/**
	 * Polydium draws the sky with its own far-plane copies of vanilla's sky pipelines (public constants in
	 * {@code SkyFarPlane.Pipelines}: same vertex format and blend, depth pushed to the far plane). Iris maps pipelines by
	 * identity, so those are mapped back to the vanilla pipeline they replace; the pack's sky program then draws with
	 * vanilla's state, as on GL.
	 */
	static RenderPipeline original(RenderPipeline p) {
		if (originals == null) {
			Map<RenderPipeline, RenderPipeline> m = new java.util.IdentityHashMap<>();
			m.put(SkyFarPlane.Pipelines.SKY, RenderPipelines.SKY);
			m.put(SkyFarPlane.Pipelines.SUNRISE_SUNSET, RenderPipelines.SUNRISE_SUNSET);
			m.put(SkyFarPlane.Pipelines.STARS, RenderPipelines.STARS);
			m.put(SkyFarPlane.Pipelines.CELESTIAL, RenderPipelines.CELESTIAL);
			m.put(SkyFarPlane.Pipelines.SUN, RenderPipelines.CELESTIAL);
			originals = m;
		}
		return originals.getOrDefault(p, p);
	}

	/** Diagnostics: draws per vanilla pipeline while the pack is active ("[other target]": a pass not on the main target). */
	private static final Map<String, Long> seen = new java.util.TreeMap<>();

	static Map<String, Long> takeSeen() {
		Map<String, Long> m = new java.util.TreeMap<>(seen);
		seen.clear();
		return m;
	}

	/** VulkanCommandEncoder.createRenderPass(descriptor) RETURN: remember a pass that draws into Minecraft's main target. */
	public static void passCreated(Object backend, RenderPassDescriptor d) {
		if (current != null && sink != null) {
			seen.merge("~ pass created while a tracked pass was still open" + (current.pass == backend ? " (same object)" : ""), 1L, Long::sum);
		}
		current = null;
		rejectedPass = backend;
		Sink s = sink;
		if (s != null) {
			createdPasses.add(backend);
			closedPasses.remove(backend);
		}
		if (s == null || !(backend instanceof VulkanRenderPass pass) || !s.active()) {
			rejectReason = "pack inactive at pass creation";
			return;
		}
		var main = Minecraft.getInstance().getMainRenderTarget();
		if (d.colorAttachments().size() != 1) {
			rejectReason = d.colorAttachments().size() + " colour attachments";
			return;
		}
		GpuTextureView color = d.colorAttachments().get(0).textureView();
		if (color == null || color.texture() != main.getColorTexture()) {
			rejectReason = "colour " + (color == null ? "none" : color.texture().getLabel());
			return;
		}
		GpuTextureView depth = d.depthAttachment() == null ? null : d.depthAttachment().textureView();
		if (depth != null && depth.texture() != main.getDepthTexture()) {
			rejectReason = "depth " + depth.texture().getLabel();
			return;
		}
		rejectedPass = null;
		Current c = new Current();
		c.pass = pass;
		c.colorTexture = color.texture();
		c.colorView = ((VulkanGpuTextureView) color).vkImageView();
		if (depth != null) {
			c.depthTexture = depth.texture();
			c.depthView = ((VulkanGpuTextureView) depth).vkImageView();
			c.depthFormat = PolydiumAccess.vkFormat(depth.texture());
		}
		var area = d.renderArea;
		if (area != null) {
			c.x = area.x();
			c.y = area.y();
			c.w = area.width();
			c.h = area.height();
		} else {
			c.w = color.getWidth(0);
			c.h = color.getHeight(0);
		}
		c.scissor = new int[]{c.x, c.y, c.w, c.h};
		current = c;
		redirectedPasses++;
	}

	/** VulkanRenderPass draw HEAD. */
	public static int draw(VulkanRenderPass pass, VkCommandBuffer cmd, VulkanRenderPipeline pipeline, Map<String, GpuBufferSlice> uniforms,
						   Map<String, VulkanRenderPass.TextureViewAndSampler> textures, boolean hasDepth, int outW, int outH,
						   boolean indexed, int a, int b, int c, int d, int e) {
		Current cur = current;
		Sink s = sink;
		boolean tracked = cur != null && cur.pass == pass;
		if (s != null && pipeline != null && s.active()) {
			seen.merge(pipeline.info().getLocation() + (tracked ? "" : " [not redirected: "
				+ (pass == rejectedPass ? rejectReason : closedPasses.contains(pass) ? "drawn after close()"
				: createdPasses.contains(pass) ? "created earlier, superseded" : "never seen at createRenderPass") + "]"), 1L, Long::sum);
		}
		if (!tracked || pipeline == null) return PASS;
		RenderPipeline info = original(pipeline.info());
		ShaderKey key = s == null || !s.active() ? null : s.key(info);
		if (key == null) return resume(pass, cmd, pipeline, hasDepth) ? RESUMED : PASS;
		try {
			if (!cur.suspended) {
				GraphicsProgram.endRendering(cmd);
				PolydiumAccess.polydiumBarrier(cmd);
				cur.suspended = true;
			}
			s.draw(cmd, key, info, new State(uniforms, textures, outW, outH, cur.scissor), hasDepth, indexed, a, b, c, d, e);
			return HANDLED;
		} catch (Throwable t) {
			s.failed(t);
			return resume(pass, cmd, pipeline, hasDepth) ? RESUMED : PASS;
		}
	}

	/** Before any other Polydium draw path: give the pass back to Polydium. */
	public static boolean resume(VulkanRenderPass pass, VkCommandBuffer cmd, VulkanRenderPipeline pipeline, boolean hasDepth) {
		Current cur = current;
		if (cur == null || cur.pass != pass || !cur.suspended) return false;
		cur.suspended = false;
		Sink s = sink;
		if (s != null) s.end(cmd);
		PolydiumAccess.polydiumBarrier(cmd);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
			color.get(0).sType$Default().imageView(cur.colorView).imageLayout(PolydiumAccess.TEXTURE_LAYOUT)
				.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
				.renderArea(r -> r.offset(o -> o.set(cur.x, cur.y)).extent(x -> x.set(cur.w, cur.h))).layerCount(1).pColorAttachments(color);
			if (cur.depthView != 0) {
				VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(cur.depthView)
					.imageLayout(PolydiumAccess.TEXTURE_LAYOUT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
				info.pDepthAttachment(depth);
			}
			if (cmd.getCapabilities().vkCmdBeginRendering != 0L) VK13.vkCmdBeginRendering(cmd, info);
			else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
			VulkanDynamicState.reset();
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).width(cur.w).height(cur.h).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0).offset(o -> o.set(cur.scissor[0], cur.scissor[1])).extent(x -> x.set(cur.scissor[2], cur.scissor[3]));
			vkCmdSetScissor(cmd, 0, sc);
			if (pipeline != null) {
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, hasDepth ? pipeline.withDepthPipeline() : pipeline.withoutDepthPipeline());
			}
		}
		return true;
	}

	/** VulkanRenderPass.close HEAD: Polydium ends its scope next, so it must be open again. */
	public static void close(VulkanRenderPass pass, VkCommandBuffer cmd, VulkanRenderPipeline pipeline, boolean hasDepth) {
		resume(pass, cmd, pipeline, hasDepth);
		if (current != null && current.pass == pass) current = null;
		if (sink != null) closedPasses.add(pass);
	}

	/** VulkanRenderPass.enableScissor TAIL; clamped the way Polydium's setScissor does (negative offset shrinks the extent). */
	public static void scissor(VulkanRenderPass pass, int x, int y, int w, int h) {
		Current cur = current;
		if (cur == null || cur.pass != pass) return;
		if (x < 0) {
			w = Math.max(0, w + x);
			x = 0;
		}
		if (y < 0) {
			h = Math.max(0, h + y);
			y = 0;
		}
		cur.scissor = new int[]{x, y, w, h};
	}

	/** The vanilla pass's bound uniform blocks and textures, as PostChain reads them. */
	private record State(Map<String, GpuBufferSlice> uniforms, Map<String, VulkanRenderPass.TextureViewAndSampler> textures, int viewportWidth,
						 int viewportHeight, int[] scissor) implements PostChain.PassState {
		@Override
		public long[] block(String name) {
			GpuBufferSlice slice = uniforms.get(name);
			if (slice == null) return null;
			if (!(slice.buffer() instanceof VulkanGpuBuffer vb)) {
				throw new IllegalStateException("uniform " + name + " is a " + slice.buffer().getClass().getName() + ", not a Polydium VulkanGpuBuffer");
			}
			return new long[]{vb.vkBuffer(), slice.offset(), slice.length()};
		}

		@Override
		public GpuImage texture(String name) {
			var t = textures.get(name);
			if (t == null) return null;
			VulkanGpuTextureView view = t.view();
			GpuTexture tex = view.texture();
			return GpuImage.external(name, PolydiumAccess.vkImage(tex), view.vkImageView(), PolydiumAccess.vkFormat(tex),
				tex.getWidth(0), tex.getHeight(0), VK_IMAGE_ASPECT_COLOR_BIT, PolydiumAccess.TEXTURE_LAYOUT);
		}

		@Override
		public long sampler(String name) {
			return textures.get(name).sampler().vkSampler();
		}
	}
}
