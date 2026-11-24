package dev.vulkaniris.fabric;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vulkaniris.vk.DescriptorRing;
import dev.vulkaniris.vk.DescriptorWrites;
import dev.vulkaniris.vk.DeferredDestroyer;
import dev.vulkaniris.vk.FrameClock;
import dev.vulkaniris.vk.GpuAllocator;
import dev.vulkaniris.vk.GpuBuffer;
import dev.vulkaniris.vk.GpuImage;
import dev.vulkaniris.vk.GraphicsProgram;
import dev.vulkaniris.vk.LayoutTracker;
import dev.vulkaniris.vk.RenderTargetSet;
import dev.vulkaniris.vk.SamplerCache;
import dev.vulkaniris.vk.ShaderCompiler;
import dev.vulkaniris.vk.VulkanContext;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanGlslTranslator;
import net.irisshaders.iris.pipeline.vulkan.VulkanUniformWriter;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs a shader pack's fullscreen programs (begin, prepare, deferred, composite, final) natively on Vulkan, inside
 * the frame command buffer the caller provides. Mirrors Iris's CompositeRenderer/FinalPassRenderer: Iris's own
 * patcher and uniform definitions, the same ping-pong ("flip") rules, clear colours and copy-back after final.
 *
 * <p>Phase 6 scope — there are no gbuffer or shadow passes yet, so the vanilla frame stands in for the gbuffers:
 * it is copied into colortex0 where the gbuffers would have written it, and the main depth buffer serves as
 * depthtex0/1/2. Everything a pass reads that this can't provide yet is listed in {@link #notes()}.</p>
 */
final class PostChain implements AutoCloseable {
	private static final int SLOTS = 4;
	private static final int UBO_ALIGN = 256;
	private static final String COPY_VERTEX = """
		#version 450
		void main() {
			vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
			gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
		}
		""";
	private static final String COPY_FRAGMENT = """
		#version 450
		layout(set = 0, binding = 0) uniform sampler2D scene;
		layout(location = 0) out vec4 color;
		void main() {
			color = texelFetch(scene, ivec2(gl_FragCoord.xy), 0);
		}
		""";

	/** Iris's centerDepth.fsh, Vulkan GLSL: depth at the screen centre, smoothed with the pack's centerDepthHalflife. */
	private static final String CENTER_DEPTH_FRAGMENT = """
		#version 450
		layout(set = 0, binding = 0) uniform sampler2D depth;
		layout(set = 0, binding = 1) uniform sampler2D altDepth;
		layout(set = 0, binding = 2) uniform Params { float lastFrameTime; float decay; } p;
		layout(location = 0) out float iris_fragColor;
		void main() {
			float currentDepth = texture(depth, vec2(0.5)).r;
			float decay2 = 1.0 - exp(-p.decay * p.lastFrameTime);
			float oldDepth = texture(altDepth, vec2(0.5)).r;
			if (isnan(oldDepth)) oldDepth = currentDepth;
			iris_fragColor = mix(oldDepth, currentDepth, decay2);
		}
		""";

	/** How one descriptor of a pass is filled each frame. */
	private sealed interface Source {
	}

	private record UniformBlock() implements Source {
	}

	private record Target(int index) implements Source {
	}

	private record TargetImage(int index) implements Source {
	}

	private record Depth() implements Source {
	}

	private record Fixed(GpuImage image, SamplerCache.Key sampler) implements Source {
	}

	/** A pack shader storage buffer ({@code bufferObject.N} in shaders.properties), shared by every pass, kept across frames. */
	private record Ssbo(GpuBuffer buffer) implements Source {
	}

	/** iris_centerDepthSmooth. */
	private record CenterDepth() implements Source {
	}

	private record Bound(int binding, Source source) {
	}

	private static final class Pass {
		String name;
		boolean isFinal;
		GraphicsProgram program;
		ShaderResourceLayout layout;
		List<Bound> bounds = new ArrayList<>();
		int[] drawBuffers = new int[0];
		ImmutableMap<Integer, Boolean> explicitFlips = ImmutableMap.of();
		GpuBuffer ubo;
		long stride;
		final long[] slotSubmit = new long[SLOTS];
		boolean usesSsbo;
	}

	private record Stage(ProgramArrayId array, TextureStage stage, String preFlips) {
	}

	private static final List<Stage> STAGES = List.of(
		new Stage(ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre"),
		new Stage(ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre"),
		new Stage(ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre"),
		new Stage(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre"));

	private final VulkanContext ctx;
	private final GpuAllocator alloc;
	private final FrameClock clock;
	private final DescriptorRing ring;
	private final SamplerCache samplers;
	private final ProgramSet set;
	private final PackDirectives directives;
	private final VulkanUniformWriter writer = new VulkanUniformWriter();
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private final CustomUniforms customUniforms;
	private final Map<Stage, List<Pass>> passes = new LinkedHashMap<>();
	private Pass finalPass;
	private GraphicsProgram sceneCopy;
	private RenderTargetSet targets;
	private final InternalTextureFormat[] targetFormats = new InternalTextureFormat[RenderTargetSet.MAX_TARGETS];
	private final List<GpuImage> ownedImages = new ArrayList<>();
	private final List<GpuBuffer> ownedBuffers = new ArrayList<>();
	private final List<Runnable> uploads = new ArrayList<>();
	private final List<GpuBuffer> staging = new ArrayList<>();
	private final Set<String> notes = new TreeSet<>();
	private GpuBuffer quad;
	private final int mainColorFormat;
	private long frame;
	private int programCount;

	PostChain(VulkanContext ctx, GpuAllocator alloc, DeferredDestroyer destroyer, FrameClock clock, DescriptorRing ring, SamplerCache samplers,
			  ShaderCompiler compiler, ProgramSet set, int mainColorFormat, int width, int height) {
		this.ctx = ctx;
		this.alloc = alloc;
		this.clock = clock;
		this.ring = ring;
		this.samplers = samplers;
		this.set = set;
		this.directives = set.getPackDirectives();
		this.mainColorFormat = mainColorFormat;

		// Iris's uniforms, exactly as IrisRenderingPipeline + CompositeRenderer register them
		this.customUniforms = set.getPack().customUniforms.build(
			holder -> CommonUniforms.addNonDynamicUniforms(holder, set.getPack().getIdMap(), directives, notifier));
		CommonUniforms.addNonDynamicUniforms(writer, set.getPack().getIdMap(), directives, notifier);
		CommonUniforms.addDynamicUniforms(writer, FogMode.OFF);
		// These dynamic uniforms read OpenGL state (bound texture, blend state) that has no Vulkan equivalent here.
		// In a composite pass on GL blending is off, so blendFunc is 0 there too; the texture ones are reported.
		writer.override("blendFunc", () -> new double[]{0, 0, 0, 0});
		for (String glOnly : List.of("atlasSize", "gtextureSize", "gtextureId")) {
			writer.override(glOnly, () -> new double[]{0, 0});
		}
		writer.attachCustom(customUniforms);

		Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings = directives.getRenderTargetDirectives().getRenderTargetSettings();
		for (int i = 0; i < targetFormats.length; i++) {
			var s = settings.get(i);
			targetFormats[i] = s == null ? InternalTextureFormat.RGBA : s.getInternalFormat();
		}

		try {
			buildAll(compiler, width, height, destroyer);
		} catch (RuntimeException e) {
			close();
			throw e;
		}
	}

	private void buildAll(ShaderCompiler compiler, int width, int height, DeferredDestroyer destroyer) {
		Set<Integer> used = new TreeSet<>();
		used.add(0);
		Set<Integer> flippedOnce = new HashSet<>();
		for (Stage st : STAGES) {
			flippedOnce = new HashSet<>(); // Iris: one CompositeRenderer per stage, each with its own flippedAtLeastOnce
			List<Pass> list = new ArrayList<>();
			for (ProgramSource src : set.getComposite(st.array())) {
				if (src == null || !src.isValid()) continue;
				list.add(build(compiler, src, st.stage(), false, used, flippedOnce));
			}
			passes.put(st, list);
		}
		final Set<Integer> afterComposite = flippedOnce; // FinalPassRenderer gets the composite stage's set
		this.finalPass = set.get(ProgramId.Final).filter(ProgramSource::isValid)
			.map(src -> build(compiler, src, TextureStage.COMPOSITE_AND_FINAL, true, used, afterComposite)).orElse(null);
		if (finalPass == null) notes.add("pack has no final pass: the last colortex0 is copied to the screen (Iris does the same)");
		for (ProgramArrayId a : List.of(ProgramArrayId.Begin, ProgramArrayId.Prepare, ProgramArrayId.Deferred, ProgramArrayId.Composite)) {
			var c = set.getCompute(a);
			if (c != null && Arrays.stream(c).anyMatch(x -> x != null && Arrays.stream(x).anyMatch(Objects::nonNull))) notes.add("compute passes in " + a + " are skipped (not implemented yet)");
		}

		int[] formats = new int[RenderTargetSet.MAX_TARGETS];
		for (int i : used) formats[i] = VkFormats.of(targetFormats[i]);
		this.targets = new RenderTargetSet(alloc, destroyer, formats, width, height);
		if (needsCenterDepth) buildCenterDepth(compiler);

		this.sceneCopy = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec("vulkaniris:scene-to-colortex0",
			List.of(GraphicsProgram.Stage.VERTEX, GraphicsProgram.Stage.FRAGMENT),
			List.of(compiler.compile("vulkaniris:scene.vsh", ShaderCompiler.Stage.VERTEX, COPY_VERTEX),
				compiler.compile("vulkaniris:scene.fsh", ShaderCompiler.Stage.FRAGMENT, COPY_FRAGMENT)),
			List.of(new GraphicsProgram.Binding("scene", 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT)),
			List.of(), 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, new int[]{targets.format(0)}, null));

		// Iris's composite quad: Position (vec3) + UV0 (vec2), 0..1, two triangles
		float[] q = {0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1};
		this.quad = buffer("composite quad", q.length * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
		MemoryUtil.memFloatBuffer(quad.mapped(), q.length).put(q);
		alloc.flush(quad);
	}

	int programCount() {
		return programCount;
	}

	Set<String> notes() {
		return notes;
	}

	Map<String, String> uniformFailures() {
		return writer.failures();
	}

	RenderTargetSet targets() {
		return targets;
	}

	private GpuBuffer buffer(String name, long size, int usage) {
		GpuBuffer b = alloc.createBuffer(name, size, usage, true);
		ownedBuffers.add(b);
		return b;
	}

	// ------------------------------------------------------------------ build

	private Pass build(ShaderCompiler compiler, ProgramSource src, TextureStage stage, boolean isFinal, Set<Integer> used, Set<Integer> flippedOnce) {
		Pass p = new Pass();
		p.name = src.getName();
		p.isFinal = isFinal;
		var d = src.getDirectives();
		if (!isFinal) {
			p.drawBuffers = d.getDrawBuffers();
			p.explicitFlips = d.getExplicitFlips();
			for (int b : p.drawBuffers) used.add(b);
		}
		if (d.getBlendModeOverride().isPresent()) notes.add(p.name + ": blend override ignored (not implemented yet)");
		if (d.getViewportScale() != null && d.getViewportScale().scale() != 1.0f) notes.add(p.name + ": viewport scale ignored (not implemented yet)");
		if (!d.getMipmappedBuffers().isEmpty()) notes.add(p.name + ": mipmapped buffers sampled without mipmaps (not implemented yet)");

		Map<PatchShaderType, String> gl = TransformPatcher.patchComposite(p.name, src.getVertexSource().orElse(null),
			src.getGeometrySource().orElse(null), src.getFragmentSource().orElse(null), stage, directives.getTextureMap());
		var vk = VulkanGlslTranslator.translate(gl, writer::provides);
		p.layout = vk.layout();
		for (String u : writer.unresolved(p.layout)) notes.add(p.name + ": uniform " + u + " has no Vulkan value yet (zero)");

		List<GraphicsProgram.Stage> stages = new ArrayList<>();
		List<byte[]> spirv = new ArrayList<>();
		for (var e : vk.sources().entrySet()) {
			stages.add(switch (e.getKey()) {
				case VERTEX -> GraphicsProgram.Stage.VERTEX;
				case GEOMETRY -> GraphicsProgram.Stage.GEOMETRY;
				case TESS_CONTROL -> GraphicsProgram.Stage.TESS_CONTROL;
				case TESS_EVAL -> GraphicsProgram.Stage.TESS_EVAL;
				case FRAGMENT -> GraphicsProgram.Stage.FRAGMENT;
				case COMPUTE -> throw new IllegalStateException("compute stage in a composite program");
			});
			spirv.add(compiler.compile(p.name + e.getKey().extension, compilerStage(e.getKey()), e.getValue()));
		}

		List<GraphicsProgram.Attribute> attributes = new ArrayList<>();
		for (var a : vk.attributes().entrySet()) {
			switch (a.getKey()) {
				case "Position" -> attributes.add(new GraphicsProgram.Attribute(a.getValue(), VK_FORMAT_R32G32B32_SFLOAT, 0));
				case "UV0" -> attributes.add(new GraphicsProgram.Attribute(a.getValue(), VK_FORMAT_R32G32_SFLOAT, 12));
				default -> throw new IllegalStateException(p.name + ": composite program reads vertex attribute " + a.getKey() + ", which Iris's quad doesn't have");
			}
		}

		List<GraphicsProgram.Binding> bindings = new ArrayList<>();
		for (ShaderResourceLayout.Descriptor desc : p.layout.descriptors()) {
			int type = switch (desc.kind()) {
				case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				case SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				case IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
				case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			};
			if (desc.count() != 1) throw new IllegalStateException(p.name + ": array of " + desc.name() + " (not implemented yet)");
			bindings.add(new GraphicsProgram.Binding(desc.name(), desc.binding(), type, 1, VK_SHADER_STAGE_ALL_GRAPHICS));
			Source source = resolve(p.name, desc, stage, used, flippedOnce);
			if (source instanceof Ssbo) p.usesSsbo = true;
			p.bounds.add(new Bound(desc.binding(), source));
		}

		if (!isFinal) {
			for (int b : p.drawBuffers) if (p.explicitFlips.get(b) != Boolean.FALSE) flippedOnce.add(b);
			p.explicitFlips.forEach((b, flip) -> {
				if (flip) flippedOnce.add(b);
			});
		}

		int[] colorFormats;
		if (isFinal) {
			colorFormats = new int[]{mainColorFormat};
		} else {
			colorFormats = new int[p.drawBuffers.length];
			for (int i = 0; i < colorFormats.length; i++) {
				colorFormats[i] = RenderTargetSet.renderableFormat(ctx, VkFormats.of(targetFormats[p.drawBuffers[i]]));
			}
		}
		p.program = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec(p.name, stages, spirv, bindings, attributes, 20,
			VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, colorFormats, null));
		if (p.layout.uniformBufferSize() > 0) {
			p.stride = alignUp(p.layout.uniformBufferSize(), UBO_ALIGN);
			p.ubo = buffer(p.name + " uniforms", p.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		}
		Arrays.fill(p.slotSubmit, -1);
		programCount++;
		return p;
	}

	private Source resolve(String pass, ShaderResourceLayout.Descriptor d, TextureStage stage, Set<Integer> used, Set<Integer> flippedOnce) {
		String n = d.name();
		if (d.kind() == ShaderResourceLayout.Kind.UNIFORM_BUFFER) {
			if (n.equals(ShaderResourceLayout.UNIFORM_BLOCK)) return new UniformBlock();
			throw new IllegalStateException(pass + ": uniform block " + n + " (not implemented yet)");
		}
		if (d.kind() == ShaderResourceLayout.Kind.STORAGE_BUFFER) return ssbo(pass, d);
		if (d.kind() == ShaderResourceLayout.Kind.IMAGE) {
			if (n.startsWith("colorimg")) {
				int i = Integer.parseInt(n.substring("colorimg".length()));
				used.add(i);
				return new TargetImage(i);
			}
			throw new IllegalStateException(pass + ": image " + n + " (custom images not implemented yet)");
		}
		// Iris CustomTextureSamplerInterceptor: this stage's custom textures win over built-in names, except a colortex
		// (or its legacy alias) that an earlier pass of the stage already wrote
		int target = targetIndex(n);
		var perStage = set.getPack().getCustomTextureDataMap().get(stage);
		if (perStage != null) {
			List<String> names = target >= 0 ? aliases(target) : List.of(n);
			if (target < 0 || !flippedOnce.contains(target)) {
				for (String name : names) {
					if (perStage.containsKey(name)) return customTexture(pass, name, perStage.get(name), d.type());
				}
			}
		}
		if (target >= 0) {
			used.add(target);
			return new Target(target);
		}
		switch (n) {
			case "depthtex0", "gdepthtex" -> {
				return new Depth();
			}
			case "depthtex1", "depthtex2" -> {
				notes.add(n + " = main depth (no separate pre-translucent / pre-hand copy yet)");
				return new Depth();
			}
			case "noisetex" -> {
				return noise();
			}
			default -> {
			}
		}
		if (n.equals("iris_centerDepthSmooth")) {
			needsCenterDepth = true;
			return new CenterDepth();
		}
		if (n.startsWith("shadow") || n.equals("watershadow")) {
			notes.add(n + " bound to a 1x1 'no shadow' stand-in (shadow pass not implemented yet)");
			return dummy(d.type(), 1f); // depth 1.0 = nothing occludes; colour 1.0 = untinted
		}
		for (var image : set.getPack().getIrisCustomImages()) {
			if (n.equals(image.samplerName()) || n.equals(image.name())) {
				throw new IllegalStateException(pass + ": samples pack custom image '" + image.name() + "' (" + image.target()
					+ "), written by the pack's compute passes — custom images and compute are not implemented yet");
			}
		}
		CustomTextureData custom = set.getPack().getIrisCustomTextureDataMap().get(n);
		if (custom != null) return customTexture(pass, n, custom, d.type());
		if (d.type().equalsIgnoreCase("sampler2D")) {
			// GL: a sampler nobody assigned reads texture unit 0, which Iris's composite passes bind to colortex0
			notes.add(pass + ": sampler " + n + " is unassigned in Iris; reads colortex0 like GL texture unit 0");
			used.add(0);
			return new Target(0);
		}
		notes.add(pass + ": sampler " + n + " (" + d.type() + ") unknown, bound to a 1x1 zero texture");
		return dummy(d.type(), 0f);
	}

	private final Map<Integer, Ssbo> ssbos = new HashMap<>();

	/** Iris ShaderStorageBufferHolder: one buffer per {@code layout(binding = N)}, sized by the pack, zero or pack-initialised. */
	private Ssbo ssbo(String pass, ShaderResourceLayout.Descriptor d) {
		Integer index = d.originalBinding();
		if (index == null) throw new IllegalStateException(pass + ": buffer block " + d.name() + " has no layout(binding = N)");
		return ssbos.computeIfAbsent(index, i -> {
			var info = set.getPack().getBufferObjects().get((int) i);
			if (info == null) throw new IllegalStateException(pass + ": buffer block " + d.name() + " uses binding " + i + " but the pack defines no bufferObject." + i);
			long size = info.size();
			if (info.relative()) {
				RenderTarget main = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
				size = (long) Math.ceil(main.width * info.scaleX()) * (long) Math.ceil(main.height * info.scaleY()) * info.size();
				notes.add("bufferObject." + i + " is screen-relative; sized for " + main.width + "x" + main.height + " and not resized yet");
			}
			GpuBuffer buf = alloc.createBuffer("bufferObject." + i, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
			ownedBuffers.add(buf);
			byte[] content = info.content();
			final long fSize = size;
			if (content != null) {
				GpuBuffer stage = alloc.createBuffer("bufferObject." + i + " staging", content.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
				staging.add(stage);
				MemoryUtil.memByteBuffer(stage.mapped(), content.length).put(content);
				alloc.flush(stage);
				uploads.add(() -> {
					try (MemoryStack s = MemoryStack.stackPush()) {
						VkBufferCopy.Buffer r = VkBufferCopy.calloc(1, s);
						r.get(0).size(Math.min(content.length, fSize));
						vkCmdCopyBuffer(cmd, stage.buffer(), buf.buffer(), r);
					}
				});
			} else {
				uploads.add(() -> vkCmdFillBuffer(cmd, buf.buffer(), 0, VK_WHOLE_SIZE, 0));
			}
			return new Ssbo(buf);
		});
	}

	/** Orders storage-buffer reads/writes of earlier passes (and the initial fill/copy) before this pass. */
	private void ssboBarrier() {
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s);
			b.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, b, null, null);
		}
	}

	private boolean needsCenterDepth;
	private GraphicsProgram centerDepthProgram;
	private final GpuImage[] centerDepth = new GpuImage[2];
	private GpuBuffer centerDepthParams;
	private int centerDepthLatest;

	private void buildCenterDepth(ShaderCompiler compiler) {
		int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		for (int i = 0; i < 2; i++) {
			GpuImage img = alloc.createImage("iris_centerDepthSmooth " + i, VK_FORMAT_R32_SFLOAT, 1, 1, usage, VK_IMAGE_ASPECT_COLOR_BIT);
			ownedImages.add(img);
			centerDepth[i] = img;
			uploads.add(() -> clearColor(img, new Vector4f(0f), false)); // GL leaves Iris's texture undefined; drivers give 0
		}
		centerDepthParams = buffer("centerDepth params", (long) UBO_ALIGN * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		centerDepthProgram = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec("vulkaniris:centerDepthSmooth",
			List.of(GraphicsProgram.Stage.VERTEX, GraphicsProgram.Stage.FRAGMENT),
			List.of(compiler.compile("vulkaniris:centerDepth.vsh", ShaderCompiler.Stage.VERTEX, COPY_VERTEX),
				compiler.compile("vulkaniris:centerDepth.fsh", ShaderCompiler.Stage.FRAGMENT, CENTER_DEPTH_FRAGMENT)),
			List.of(new GraphicsProgram.Binding("depth", 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT),
				new GraphicsProgram.Binding("altDepth", 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT),
				new GraphicsProgram.Binding("Params", 2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT)),
			List.of(), 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, new int[]{VK_FORMAT_R32_SFLOAT}, null));
	}

	/** Iris CenterDepthSampler.sampleCenterDepth: once per frame, after the opaque depth exists. */
	private void sampleCenterDepth(GpuImage mainDepth) {
		GpuImage prev = centerDepth[centerDepthLatest], next = centerDepth[1 - centerDepthLatest];
		tracker.use(mainDepth, LayoutTracker.Use.SAMPLED_GRAPHICS).use(prev, LayoutTracker.Use.SAMPLED_GRAPHICS)
			.use(next, LayoutTracker.Use.COLOR_ATTACHMENT, true).flush(cmd);
		int slot = (int) (frame % SLOTS);
		float halfLife = directives.getCenterDepthHalfLife();
		MemoryUtil.memFloatBuffer(centerDepthParams.mapped() + (long) slot * UBO_ALIGN, 2)
			.put(0, SystemTimeUniforms.TIMER.getLastFrameTime()).put(1, (float) (1.0 / ((halfLife * 0.1) / Math.log(2.0))));
		alloc.flush(centerDepthParams);
		long ds = ring.allocate(centerDepthProgram.setLayout());
		try (DescriptorWrites w = new DescriptorWrites(ctx.device(), ds, 3)) {
			w.image(0, 0, mainDepth, samplers.get(SamplerCache.NEAREST_CLAMP))
				.image(1, 0, prev, samplers.get(SamplerCache.NEAREST_CLAMP))
				.buffer(2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, centerDepthParams.buffer(), (long) slot * UBO_ALIGN, 8);
		}
		centerDepthProgram.draw(cmd, ds, new GpuImage[]{next}, 0, 3);
		centerDepthLatest = 1 - centerDepthLatest;
	}

	private static int targetIndex(String n) {
		if (n.startsWith("colortex")) {
			try {
				return Integer.parseInt(n.substring("colortex".length()));
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(n);
	}

	private static List<String> aliases(int target) {
		return target < PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.size()
			? List.of("colortex" + target, PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.get(target)) : List.of("colortex" + target);
	}

	private final Map<String, Fixed> fixedCache = new HashMap<>();

	private Source customTexture(String pass, String name, CustomTextureData data, String samplerType) {
		String cacheKey = "custom:" + name + "@" + System.identityHashCode(data);
		if (data instanceof CustomTextureData.RawData raw && (data instanceof CustomTextureData.RawData2D || data instanceof CustomTextureData.RawData3D)) {
			return fixedCache.computeIfAbsent(cacheKey, k -> {
				boolean is3d = data instanceof CustomTextureData.RawData3D;
				int w, h, depth;
				if (data instanceof CustomTextureData.RawData3D r3) {
					w = r3.getSizeX();
					h = r3.getSizeY();
					depth = r3.getSizeZ();
				} else {
					CustomTextureData.RawData2D r2 = (CustomTextureData.RawData2D) data;
					w = r2.getSizeX();
					h = r2.getSizeY();
					depth = 1;
				}
				String type = samplerType.toLowerCase(Locale.ROOT);
				if (is3d != type.endsWith("3d")) throw new IllegalStateException(pass + ": " + name + " is a " + (is3d ? "3D" : "2D") + " texture but declared " + samplerType);
				RawTextures.Upload up = RawTextures.convert(raw.getContent(), raw.getPixelFormat(), raw.getPixelType(), (long) w * h * depth);
				boolean blur = raw.getFilteringData().shouldBlur(), clamp = raw.getFilteringData().shouldClamp();
				boolean integer = raw.getPixelFormat().isInteger();
				SamplerCache.Key key = new SamplerCache.Key(blur && !integer ? SamplerCache.Filter.LINEAR : SamplerCache.Filter.NEAREST,
					clamp ? SamplerCache.Address.CLAMP : SamplerCache.Address.REPEAT, false, false);
				return new Fixed(uploadRaw("custom " + name, up.format(), w, h, depth, is3d, up.bytes()), key);
			});
		}
		if (data instanceof CustomTextureData.PngData png) {
			return fixedCache.computeIfAbsent(cacheKey, k -> {
				BufferedImage img = decode(png.getContent(), name);
				boolean blur = png.getFilteringData().shouldBlur(), clamp = png.getFilteringData().shouldClamp();
				SamplerCache.Key key = new SamplerCache.Key(blur ? SamplerCache.Filter.LINEAR : SamplerCache.Filter.NEAREST,
					clamp ? SamplerCache.Address.CLAMP : SamplerCache.Address.REPEAT, false, false);
				return new Fixed(upload("custom " + name, img.getWidth(), img.getHeight(), argbToRgba(img)), key);
			});
		}
		notes.add(pass + ": custom texture " + name + " is " + data.getClass().getSimpleName() + " (only PNG loaded yet), bound to a 1x1 zero texture");
		return dummy(samplerType, 0f);
	}

	private static BufferedImage decode(byte[] bytes, String name) {
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
			if (img == null) throw new IllegalStateException("not a readable image");
			return img;
		} catch (Exception e) {
			throw new IllegalStateException("custom texture " + name + ": " + e.getMessage(), e);
		}
	}

	/** Bytes R,G,B,A per pixel; row 0 = the image's top row, which is how GL uploads it too (Polydium keeps GL row order). */
	private static byte[] argbToRgba(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		byte[] out = new byte[w * h * 4];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = img.getRGB(x, y), o = (y * w + x) * 4;
				out[o] = (byte) (c >> 16);
				out[o + 1] = (byte) (c >> 8);
				out[o + 2] = (byte) c;
				out[o + 3] = (byte) (c >>> 24);
			}
		}
		return out;
	}

	private Source noise() {
		return fixedCache.computeIfAbsent("noisetex", k -> {
			CustomTextureData custom = set.getPack().getCustomNoiseTexture();
			if (custom instanceof CustomTextureData.PngData png) {
				BufferedImage img = decode(png.getContent(), "noisetex");
				return new Fixed(upload("noisetex", img.getWidth(), img.getHeight(), argbToRgba(img)), SamplerCache.LINEAR_REPEAT_MIPS);
			}
			if (custom != null) notes.add("custom noise texture of type " + custom.getClass().getSimpleName() + " not loaded yet; using Iris's generated noise");
			// Iris's NativeImageBackedNoiseTexture: Random(0), one int per pixel, x-major, alpha forced to 255
			int size = directives.getNoiseTextureResolution();
			int[] argb = new int[size * size];
			Random random = new Random(0);
			for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) argb[y * size + x] = random.nextInt() | (255 << 24);
			byte[] rgba = new byte[size * size * 4];
			for (int i = 0; i < argb.length; i++) {
				// NativeImage.setPixel takes ARGB and stores it as R,G,B,A bytes
				rgba[i * 4] = (byte) (argb[i] >> 16);
				rgba[i * 4 + 1] = (byte) (argb[i] >> 8);
				rgba[i * 4 + 2] = (byte) argb[i];
				rgba[i * 4 + 3] = (byte) 255;
			}
			return new Fixed(upload("noisetex", size, size, rgba), new SamplerCache.Key(SamplerCache.Filter.LINEAR, SamplerCache.Address.REPEAT, false, false));
		});
	}

	private Source dummy(String samplerType, float value) {
		String type = samplerType.toLowerCase(Locale.ROOT); // the translator reports glsl-transformer's enum names (SAMPLER2D)
		String key = "dummy:" + type + ":" + value;
		return fixedCache.computeIfAbsent(key, k -> {
			if (type.contains("shadow")) {
				GpuImage img = alloc.createImage("dummy depth", VK_FORMAT_D32_SFLOAT, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
				ownedImages.add(img);
				uploads.add(() -> clearDepth(img, value));
				return new Fixed(img, SamplerCache.SHADOW_PCF);
			}
			if (!type.equals("sampler2d") && !type.equals("usampler2d") && !type.equals("isampler2d")) {
				throw new IllegalStateException("sampler type " + samplerType + " (not implemented yet)");
			}
			int fmt = type.startsWith("u") ? VK_FORMAT_R32G32B32A32_UINT : type.startsWith("i") ? VK_FORMAT_R32G32B32A32_SINT : VK_FORMAT_R32G32B32A32_SFLOAT;
			GpuImage img = alloc.createImage("dummy " + type, fmt, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
			ownedImages.add(img);
			uploads.add(() -> clearColor(img, new Vector4f(value), fmt != VK_FORMAT_R32G32B32A32_SFLOAT));
			return new Fixed(img, SamplerCache.NEAREST_CLAMP);
		});
	}

	private GpuImage upload(String name, int w, int h, byte[] rgba) {
		return uploadRaw(name, VK_FORMAT_R8G8B8A8_UNORM, w, h, 1, false, rgba);
	}

	private GpuImage uploadRaw(String name, int format, int w, int h, int depth, boolean is3d, byte[] rgba) {
		int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		GpuImage img = is3d ? alloc.createImage3D(name, format, w, h, depth, usage) : alloc.createImage(name, format, w, h, usage, VK_IMAGE_ASPECT_COLOR_BIT);
		ownedImages.add(img);
		GpuBuffer stage = alloc.createBuffer(name + " staging", rgba.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
		staging.add(stage);
		MemoryUtil.memByteBuffer(stage.mapped(), rgba.length).put(rgba);
		alloc.flush(stage);
		uploads.add(() -> {
			tracker.use(img, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
			try (MemoryStack s = MemoryStack.stackPush()) {
				VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
				r.get(0).imageSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e -> e.set(w, h, depth));
				vkCmdCopyBufferToImage(cmd, stage.buffer(), img.image(), img.layout(), r);
			}
		});
		return img;
	}

	// ------------------------------------------------------------------ record

	private VkCommandBuffer cmd;
	private LayoutTracker tracker;
	private long stagingSubmit = -1;

	/**
	 * Records the whole chain. {@code mainColor} holds the vanilla frame on entry and the pack's final output on exit.
	 * Returns false (recording nothing) if a uniform slot is still in use by an unfinished submission.
	 */
	boolean record(VkCommandBuffer cmd, LayoutTracker tracker, GpuImage mainColor, GpuImage mainDepth) {
		for (List<Pass> list : passes.values()) for (Pass p : list) if (!slotFree(p)) return false;
		if (finalPass != null && !slotFree(finalPass)) return false;
		this.cmd = cmd;
		this.tracker = tracker;
		targets.resize(mainColor.width(), mainColor.height());
		LayoutTracker.releaseExternal(mainColor);
		LayoutTracker.releaseExternal(mainDepth);
		if (!uploads.isEmpty()) {
			uploads.forEach(Runnable::run);
			uploads.clear();
			stagingSubmit = clock.currentIndex();
		} else if (stagingSubmit >= 0 && clock.isComplete(stagingSubmit)) {
			staging.forEach(alloc::destroy);
			staging.clear();
			stagingSubmit = -1;
		}

		notifier.onNewFrame();
		customUniforms.update();
		clearTargets();

		for (var e : passes.entrySet()) {
			directives.getExplicitFlips(e.getKey().preFlips()).forEach((b, flip) -> {
				if (flip && targets.has(b)) targets.flip(b);
			});
			if (e.getKey().stage() == TextureStage.DEFERRED) {
				copyScene(mainColor);
				if (centerDepthProgram != null) sampleCenterDepth(mainDepth);
			}
			for (Pass p : e.getValue()) run(p, mainColor, mainDepth);
		}
		if (finalPass != null) {
			run(finalPass, mainColor, mainDepth);
		} else {
			blitToScreen(targets.read(0), mainColor);
		}
		copyBack();
		frame++;
		this.cmd = null;
		this.tracker = null;
		return true;
	}

	private boolean slotFree(Pass p) {
		long s = p.slotSubmit[(int) (frame % SLOTS)];
		return s < 0 || clock.isComplete(s);
	}

	private void clearTargets() {
		var settings = directives.getRenderTargetDirectives().getRenderTargetSettings();
		for (int i = 0; i < RenderTargetSet.MAX_TARGETS; i++) {
			if (!targets.has(i)) continue;
			var s = settings.get(i);
			if (s != null && !s.shouldClear()) continue;
			Vector4f c = s == null ? null : s.getClearColor().orElse(null);
			if (c == null) {
				if (i == 0) {
					Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
					c = new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1f);
				} else {
					c = i == 1 ? new Vector4f(1f) : new Vector4f(0f);
				}
			}
			boolean integer = VkFormats.isInteger(targetFormats[i]);
			clearColor(targets.read(i), c, integer);
			clearColor(targets.write(i), c, integer);
		}
	}

	private void clearColor(GpuImage img, Vector4f c, boolean integer) {
		tracker.use(img, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkClearColorValue v = VkClearColorValue.calloc(s);
			if (integer) v.int32(0, (int) c.x).int32(1, (int) c.y).int32(2, (int) c.z).int32(3, (int) c.w);
			else v.float32(0, c.x).float32(1, c.y).float32(2, c.z).float32(3, c.w);
			VkImageSubresourceRange.Buffer r = VkImageSubresourceRange.calloc(1, s);
			r.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			vkCmdClearColorImage(cmd, img.image(), img.layout(), v, r);
		}
	}

	private void clearDepth(GpuImage img, float depth) {
		tracker.use(img, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkImageSubresourceRange.Buffer r = VkImageSubresourceRange.calloc(1, s);
			r.get(0).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
			vkCmdClearDepthStencilImage(cmd, img.image(), img.layout(), VkClearDepthStencilValue.calloc(s).depth(depth), r);
		}
	}

	/** Where the gbuffers would have written colortex0: the vanilla frame. */
	private void copyScene(GpuImage mainColor) {
		GpuImage dst = targets.read(0);
		tracker.use(mainColor, LayoutTracker.Use.SAMPLED_GRAPHICS).use(dst, LayoutTracker.Use.COLOR_ATTACHMENT, true).flush(cmd);
		long ds = ring.allocate(sceneCopy.setLayout());
		try (DescriptorWrites w = new DescriptorWrites(ctx.device(), ds, 1)) {
			w.image(0, 0, mainColor, samplers.get(SamplerCache.NEAREST_CLAMP));
		}
		sceneCopy.draw(cmd, ds, new GpuImage[]{dst}, 0, 3);
	}

	private void blitToScreen(GpuImage src, GpuImage mainColor) {
		tracker.use(src, LayoutTracker.Use.TRANSFER_SRC).use(mainColor, LayoutTracker.Use.TRANSFER_DST).flush(cmd);
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkImageBlit.Buffer r = VkImageBlit.calloc(1, s);
			r.get(0).srcSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
				.dstSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
			r.get(0).srcOffsets(1).set(src.width(), src.height(), 1);
			r.get(0).dstOffsets(1).set(mainColor.width(), mainColor.height(), 1);
			vkCmdBlitImage(cmd, src.image(), src.layout(), mainColor.image(), mainColor.layout(), r, VK_FILTER_NEAREST);
		}
	}

	private void run(Pass p, GpuImage mainColor, GpuImage mainDepth) {
		GpuImage[] outs;
		if (p.isFinal) {
			outs = new GpuImage[]{mainColor};
		} else {
			outs = new GpuImage[p.drawBuffers.length];
			for (int i = 0; i < outs.length; i++) outs[i] = targets.write(p.drawBuffers[i]);
		}
		// resolve this frame's images (flips change them), declare every use once, then one barrier batch
		GpuImage[] images = new GpuImage[p.bounds.size()];
		long[] sampler = new long[p.bounds.size()];
		Set<GpuImage> sampled = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<GpuImage> storage = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < images.length; i++) {
			Source s = p.bounds.get(i).source();
			switch (s) {
				case UniformBlock u -> {
				}
				case Target t -> {
					images[i] = targets.read(t.index());
					sampler[i] = samplers.get(VkFormats.isInteger(targetFormats[t.index()]) ? SamplerCache.NEAREST_CLAMP : SamplerCache.LINEAR_CLAMP);
					sampled.add(images[i]);
				}
				case TargetImage t -> {
					images[i] = targets.read(t.index());
					storage.add(images[i]);
				}
				case Depth dep -> {
					images[i] = mainDepth;
					sampler[i] = samplers.get(SamplerCache.NEAREST_CLAMP);
					sampled.add(mainDepth);
				}
				case Fixed f -> {
					images[i] = f.image();
					sampler[i] = samplers.get(f.sampler());
					sampled.add(f.image());
				}
				case Ssbo b -> {
				}
				case CenterDepth c -> {
					images[i] = centerDepth[centerDepthLatest];
					sampler[i] = samplers.get(SamplerCache.LINEAR_CLAMP);
					sampled.add(images[i]);
				}
			}
		}
		for (GpuImage o : outs) {
			if (sampled.contains(o) || storage.contains(o)) throw new IllegalStateException(p.name + " reads and renders to " + o.name());
		}
		for (GpuImage img : sampled) tracker.use(img, LayoutTracker.Use.SAMPLED_GRAPHICS);
		for (GpuImage img : storage) tracker.use(img, LayoutTracker.Use.STORAGE_FRAGMENT);
		for (GpuImage o : outs) tracker.use(o, LayoutTracker.Use.COLOR_ATTACHMENT);
		tracker.flush(cmd);
		if (p.usesSsbo) ssboBarrier();

		int slot = (int) (frame % SLOTS);
		long ds = ring.allocate(p.program.setLayout());
		try (DescriptorWrites w = new DescriptorWrites(ctx.device(), ds, images.length)) {
			for (int i = 0; i < images.length; i++) {
				Bound b = p.bounds.get(i);
				switch (b.source()) {
					case UniformBlock u -> {
						ByteBuffer dst = MemoryUtil.memByteBuffer(p.ubo.mapped() + slot * p.stride, p.layout.uniformBufferSize());
						writer.write(p.layout, dst);
						w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, p.ubo.buffer(), slot * p.stride, p.layout.uniformBufferSize());
					}
					case TargetImage t -> w.storageImage(b.binding(), images[i]);
					case Ssbo sb -> w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, sb.buffer().buffer(), 0, VK_WHOLE_SIZE);
					default -> w.image(b.binding(), 0, images[i], sampler[i]);
				}
			}
		}
		if (p.ubo != null) alloc.flush(p.ubo);
		p.slotSubmit[slot] = clock.currentIndex();
		p.program.draw(cmd, ds, outs, quad.buffer(), 6);

		if (p.isFinal) return;
		// Iris CompositeRenderer: flip every buffer written unless explicitly "false", then explicit "true" flips
		for (int b : p.drawBuffers) if (p.explicitFlips.get(b) != Boolean.FALSE) targets.flip(b);
		p.explicitFlips.forEach((b, flip) -> {
			if (flip && targets.has(b)) targets.flip(b);
		});
	}

	/** Iris FinalPassRenderer: buffers left reading from "alt" are copied back so the next frame starts unflipped. */
	private void copyBack() {
		for (int i = 0; i < RenderTargetSet.MAX_TARGETS; i++) {
			if (!targets.has(i) || !targets.flipped(i)) continue;
			GpuImage src = targets.read(i), dst = targets.write(i);
			tracker.use(src, LayoutTracker.Use.TRANSFER_SRC).use(dst, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
			try (MemoryStack s = MemoryStack.stackPush()) {
				VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
				r.get(0).srcSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
					.dstSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
					.extent(e -> e.set(src.width(), src.height(), 1));
				vkCmdCopyImage(cmd, src.image(), src.layout(), dst.image(), dst.layout(), r);
			}
			targets.flip(i);
		}
	}

	private static ShaderCompiler.Stage compilerStage(PatchShaderType t) {
		return switch (t) {
			case VERTEX -> ShaderCompiler.Stage.VERTEX;
			case GEOMETRY -> ShaderCompiler.Stage.GEOMETRY;
			case TESS_CONTROL -> ShaderCompiler.Stage.TESS_CONTROL;
			case TESS_EVAL -> ShaderCompiler.Stage.TESS_EVALUATION;
			case FRAGMENT -> ShaderCompiler.Stage.FRAGMENT;
			case COMPUTE -> ShaderCompiler.Stage.COMPUTE;
		};
	}

	private static long alignUp(long v, long a) {
		return (v + a - 1) / a * a;
	}

	/** Only once no submission using any of it is pending (callers wait for the queue to idle). */
	@Override
	public void close() {
		for (List<Pass> list : passes.values()) for (Pass p : list) p.program.close();
		if (finalPass != null) finalPass.program.close();
		if (sceneCopy != null) sceneCopy.close();
		if (centerDepthProgram != null) centerDepthProgram.close();
		if (targets != null) targets.close();
		ownedImages.forEach(alloc::destroy);
		ownedBuffers.forEach(alloc::destroy);
		staging.forEach(alloc::destroy);
	}
}
