package dev.vulkaniris.fabric;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.pipeline.fallback.ShaderSynthesizer;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import dev.vulkaniris.vk.ComputeProgram;
import dev.vulkaniris.vk.DescriptorRing;
import dev.vulkaniris.vk.DescriptorWrites;
import dev.vulkaniris.vk.DeferredDestroyer;
import dev.vulkaniris.vk.FrameClock;
import dev.vulkaniris.vk.GpuAllocator;
import dev.vulkaniris.vk.GpuBuffer;
import dev.vulkaniris.vk.GpuImage;
import dev.vulkaniris.vk.GraphicsProgram;
import dev.vulkaniris.vk.LayoutTracker;
import dev.vulkaniris.vk.VkCheck;
import java.nio.LongBuffer;
import dev.vulkaniris.vk.RenderTargetSet;
import dev.vulkaniris.vk.SamplerCache;
import dev.vulkaniris.vk.ShaderCompiler;
import dev.vulkaniris.vk.VulkanContext;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanGlslTranslator;
import net.irisshaders.iris.pipeline.vulkan.VulkanUniformWriter;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/** A pack custom image ({@code image.<name>} in shaders.properties): storage image by name, sampler by samplerName. */
	private record CustomImage(GpuImage image, boolean integer) implements Source {
	}

	/** Terrain programs: the block atlas (tex/texture/gtexture, and GL texture unit 0). */
	private record Atlas() implements Source {
	}

	/** Terrain programs: the lightmap. */
	private record Lightmap() implements Source {
	}

	/** Depth before translucent terrain: depthtex1/2 in composites, every depthtex in gbuffers (see notes). */
	/** depthtex2: the depth before the hand (Iris renderTargets.copyPreHandDepth, at beginHand). */
	private record DepthNoHand() implements Source {
	}

	private record DepthNoTranslucent() implements Source {
	}

	/** Terrain programs: Sodium's per-region chunk fade block {@code iris_ChunkData}. */
	private record ChunkData() implements Source {
	}

	private record Bound(int binding, Source source) {
	}

	/** One Sodium region's draw for a terrain pass (see {@link TerrainHook}). Buffers are Polydium's VkBuffers. */
	record RegionDraw(int stride, long vertexBuffer, long indexBuffer, long chunkData, float offsetX, float offsetY, float offsetZ, int time,
					  int[] count, int[] firstIndex, int[] baseVertex) {
	}

	/** Polydium's textures a terrain pass samples: the pass's block atlas and the lightmap. */
	record TerrainInputs(GpuImage atlas, GpuImage lightmap) {
	}

	/** One of Iris's SodiumPrograms passes (TERRAIN, TERRAIN_CUTOUT, TRANSLUCENT). */
	private static final class Gbuffer {
		String name;
		GraphicsProgram program;
		ShaderResourceLayout layout;
		List<Bound> bounds = new ArrayList<>();
		int[] drawBuffers;
		GpuBuffer ubo;
		long stride;
		final long[] slotSubmit = new long[SLOTS];
		float alphaTest;
		int regionOffset = -1, currentTime = -1, pushSize;
		boolean usesSsbo;
	}

	/** One Iris compute program ({@code <pass>.csh}, {@code <pass>_a.csh} ...), dispatched before its pass. */
	private static final class Compute {
		String name;
		ComputeProgram program;
		ShaderResourceLayout layout;
		List<Bound> bounds = new ArrayList<>();
		GpuBuffer ubo;
		long stride;
		final long[] slotSubmit = new long[SLOTS];
		int[] localSize;
		Vector3i workGroups;
		Vector2f relative;
		IndirectPointer indirect;
		Ssbo indirectBuffer;
	}

	private static final class Pass {
		String name;
		boolean isFinal;
		/** null for a compute-only pass (Iris ComputeOnlyPass: compute programs at an index with no fragment program). */
		GraphicsProgram program;
		List<Compute> computes = new ArrayList<>();
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
	/** Uniforms for the terrain programs: Iris's SodiumShader set (PER_VERTEX fog, builtin replacements, Sodium matrices). */
	private final VulkanUniformWriter gbufferWriter = new VulkanUniformWriter();
	private static final ProgramId[] TERRAIN_IDS = {ProgramId.TerrainSolid, ProgramId.TerrainCutout, ProgramId.Water};
	private final Gbuffer[] terrain = new Gbuffer[3];
	/** Iris SodiumPrograms' SHADOW / SHADOW_CUTOUT / SHADOW_TRANS passes (shadow_solid / _cutout / _water, falling back to shadow). */
	private static final ProgramId[] SHADOW_IDS = {ProgramId.ShadowSolid, ProgramId.ShadowCutout, ProgramId.ShadowWater};
	private final Gbuffer[] shadowTerrain = new Gbuffer[3];
	/** Phase 7d: the pack has a shadow pass (Iris: shadow programs, and shadow.enabled not false). Decided before anything is built. */
	private final boolean shadowPass;
	private final PackShadowDirectives shadowDirectives;
	private final int shadowResolution;
	/** shadowtex0 (rendered into) and shadowtex1 (its copy before translucent shadow terrain); Iris ShadowRenderTargets. */
	private GpuImage shadowDepth, shadowDepthNoTranslucent;
	private final GpuImage[] shadowColor = new GpuImage[PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS];
	private List<Compute> shadowComposite = List.of();
	/** While true, the terrain uniforms read the shadow view (Iris sets the shadow matrices into ChunkRenderMatrices). */
	private boolean shadowing;
	private final Matrix4f shadowModelView = new Matrix4f(), shadowProjection = new Matrix4f();
	/** The resource being bound reads watershadow (Iris IrisSamplers: then "shadow" is shadowtex1, otherwise shadowtex0). */
	private boolean bindingWaterShadow;
	private boolean shadowBegun, shadowTranslucentCopied, prepareDone;
	private long shadowFrames;
	private ChunkVertexType vertexFormat = ChunkMeshFormats.COMPACT;
	private final int mainDepthFormat;
	private final boolean withGbuffers;
	private final DeferredDestroyer destroyer;
	private boolean resolvingGbuffer;
	private int atlasWidth = 1, atlasHeight = 1;
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private final CustomUniforms customUniforms;
	private final Map<Stage, List<Pass>> passes = new LinkedHashMap<>();
	private Pass finalPass;
	private List<Compute> setup = List.of();
	private boolean setupDone;
	private final List<ComputeProgram> computePrograms = new ArrayList<>();
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
		this(ctx, alloc, destroyer, clock, ring, samplers, compiler, set, mainColorFormat, 0, width, height, false);
	}

	/**
	 * {@code withGbuffers}: also build the pack's terrain programs (Phase 7). The caller must then route Polydium's terrain
	 * draws to {@link #drawTerrain} and drive the frame with {@link #beginLevel} / {@link #beforeTranslucent} /
	 * {@link #endLevel}, and rebuild chunks in {@link #vertexFormat()}.
	 */
	PostChain(VulkanContext ctx, GpuAllocator alloc, DeferredDestroyer destroyer, FrameClock clock, DescriptorRing ring, SamplerCache samplers,
			  ShaderCompiler compiler, ProgramSet set, int mainColorFormat, int mainDepthFormat, int width, int height, boolean withGbuffers) {
		this.mainDepthFormat = mainDepthFormat;
		this.destroyer = destroyer;
		this.withGbuffers = withGbuffers;
		this.ctx = ctx;
		this.alloc = alloc;
		this.clock = clock;
		this.ring = ring;
		this.samplers = samplers;
		this.set = set;
		this.directives = set.getPackDirectives();
		this.mainColorFormat = mainColorFormat;
		this.shadowDirectives = directives.getShadowDirectives();
		this.shadowResolution = shadowDirectives.getResolution();
		// IrisRenderingPipeline: a ShadowRenderer exists when shadow.enabled isn't false; SodiumPrograms then draws
		// terrain with the pack's shadow programs. Without a shadow program there is nothing to draw it with here.
		this.shadowPass = withGbuffers && shadowDirectives.isShadowEnabled().orElse(true)
			&& new ProgramFallbackResolver(set).resolveNullable(ProgramId.ShadowSolid) != null;

		// IrisRenderingPipeline: the pack's id maps (block ids are read by uniforms like currentSelectedBlockId)
		var idMap = set.getPack().getIdMap();
		WorldRenderingSettings.INSTANCE.setEntityIds(idMap.getEntityIdMap());
		WorldRenderingSettings.INSTANCE.setItemIds(idMap.getItemIdMap());
		WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(idMap.getBlockProperties(), idMap.getTagEntries()));
		WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(idMap.getBlockRenderTypeMap()));

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
		if (withGbuffers) {
			setupGbufferUniforms();
			setupVanillaUniforms();
		}

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
		// Iris IrisRenderingPipeline: setup programs run once, before everything else
		this.setup = buildComputes(compiler, set.getSetup(), TextureStage.SETUP, used, Set.of());
		if (withGbuffers) {
			buildTerrain(compiler, used);
			// without terrain programs colortex0 gets the vanilla frame before deferred, which would overwrite pack entity draws
			if (hasTerrain()) buildVanilla(compiler, used);
			if (hasShadowPass()) buildShadowComposite(compiler, used);
		}
		for (Stage st : STAGES) {
			flippedOnce = new HashSet<>(); // Iris: one CompositeRenderer per stage, each with its own flippedAtLeastOnce
			List<Pass> list = new ArrayList<>();
			ProgramSource[] sources = set.getComposite(st.array());
			ComputeSource[][] computes = set.getCompute(st.array());
			for (int i = 0; i < Math.max(sources.length, computes.length); i++) {
				ProgramSource src = i < sources.length ? sources[i] : null;
				ComputeSource[] cs = i < computes.length ? computes[i] : null;
				// Iris CompositeRenderer: an index's computes see the same flippedAtLeastOnce snapshot as its fragment program
				List<Compute> built = buildComputes(compiler, cs, st.stage(), used, Set.copyOf(flippedOnce));
				if (src == null || !src.isValid()) {
					if (!built.isEmpty()) {
						Pass p = new Pass();
						p.name = built.get(0).name;
						p.computes = built;
						list.add(p);
					}
					continue;
				}
				Pass p = build(compiler, src, st.stage(), false, used, flippedOnce);
				p.computes = built;
				list.add(p);
			}
			passes.put(st, list);
		}
		final Set<Integer> afterComposite = flippedOnce; // FinalPassRenderer gets the composite stage's set
		List<Compute> finalComputes = buildComputes(compiler, set.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, used, Set.copyOf(afterComposite));
		this.finalPass = set.get(ProgramId.Final).filter(ProgramSource::isValid)
			.map(src -> build(compiler, src, TextureStage.COMPOSITE_AND_FINAL, true, used, afterComposite)).orElse(null);
		if (finalPass != null) {
			finalPass.computes = finalComputes;
		} else {
			notes.add("pack has no final pass: the last colortex0 is copied to the screen (Iris does the same)");
			if (!finalComputes.isEmpty()) {
				Pass p = new Pass();
				p.name = "final compute";
				p.computes = finalComputes;
				passes.get(STAGES.get(STAGES.size() - 1)).add(p);
			}
		}
		buildCustomImages();

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

		List<GraphicsProgram.Binding> bindings = bindResources(p.name, p.layout, stage, used, flippedOnce, VK_SHADER_STAGE_ALL_GRAPHICS, p.bounds);
		p.usesSsbo = p.bounds.stream().anyMatch(b -> b.source() instanceof Ssbo);

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
		// Iris CompositeRenderer: blending is off unless the program's blend directive (or a per-buffer one) turns it on
		GraphicsProgram.Blend[] blends = new GraphicsProgram.Blend[colorFormats.length];
		GraphicsProgram.Blend base = d.getBlendModeOverride().map(PostChain::blend).orElse(null);
		Arrays.fill(blends, base);
		if (!isFinal) {
			for (var info : d.getBufferBlendOverrides()) {
				for (int i = 0; i < p.drawBuffers.length; i++) {
					if (p.drawBuffers[i] == info.index()) blends[i] = blend(new BlendModeOverride(info.blendMode()));
				}
			}
		}
		for (int i = 0; i < blends.length; i++) {
			if (blends[i] != null && !isFinal && VkFormats.isInteger(targetFormats[p.drawBuffers[i]])) {
				throw new IllegalStateException(p.name + ": blending into integer colortex" + p.drawBuffers[i] + " (Vulkan forbids it; GL ignores it)");
			}
		}
		p.program = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec(p.name, stages, spirv, bindings, attributes, 20,
			VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, colorFormats, blends));
		if (p.layout.uniformBufferSize() > 0) {
			p.stride = alignUp(p.layout.uniformBufferSize(), UBO_ALIGN);
			p.ubo = buffer(p.name + " uniforms", p.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		}
		Arrays.fill(p.slotSubmit, -1);
		programCount++;
		return p;
	}

	private List<GraphicsProgram.Binding> bindResources(String name, ShaderResourceLayout layout, TextureStage stage, Set<Integer> used,
														Set<Integer> flippedOnce, int stageFlags, List<Bound> out) {
		List<GraphicsProgram.Binding> bindings = new ArrayList<>();
		bindingWaterShadow = layout.descriptors().stream().anyMatch(x -> x.name().equals("watershadow"));
		for (ShaderResourceLayout.Descriptor desc : layout.descriptors()) {
			int type = switch (desc.kind()) {
				case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				case SAMPLER -> desc.type().endsWith("samplerBuffer") ? VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				case IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
				case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			};
			if (desc.count() != 1) throw new IllegalStateException(name + ": array of " + desc.name() + " (not implemented yet)");
			bindings.add(new GraphicsProgram.Binding(desc.name(), desc.binding(), type, 1, stageFlags));
			if (desc.kind() == ShaderResourceLayout.Kind.IMAGE) storageBindings.computeIfAbsent(out, k -> new BitSet()).set(out.size());
			out.add(new Bound(desc.binding(), resolve(name, desc, stage, used, flippedOnce)));
		}
		return bindings;
	}

	private static final Pattern LOCAL_SIZE = Pattern.compile("layout\\s*\\(([^)]*local_size_[xyz][^)]*)\\)\\s*in\\s*;");
	private static final Pattern LOCAL_SIZE_PART = Pattern.compile("local_size_([xyz])\\s*=\\s*([^,\\s)]+)");

	/** GL_COMPUTE_WORK_GROUP_SIZE, read from the shader's own {@code layout(local_size_*) in;} (each axis defaults to 1, as in GLSL). */
	static int[] localSize(String name, String glsl) {
		int[] size = {1, 1, 1};
		Matcher m = LOCAL_SIZE.matcher(glsl);
		boolean found = false;
		while (m.find()) {
			found = true;
			Matcher part = LOCAL_SIZE_PART.matcher(m.group(1));
			while (part.find()) {
				int axis = part.group(1).charAt(0) - 'x';
				try {
					size[axis] = Integer.decode(part.group(2).replaceAll("[uU]$", ""));
				} catch (NumberFormatException e) {
					throw new IllegalStateException(name + ": local_size_" + part.group(1) + " = " + part.group(2) + " is not a constant number (not implemented yet)");
				}
			}
		}
		if (!found) throw new IllegalStateException(name + ": compute shader declares no layout(local_size_*) in");
		return size;
	}

	private List<Compute> buildComputes(ShaderCompiler compiler, ComputeSource[] sources, TextureStage stage, Set<Integer> used, Set<Integer> flippedOnce) {
		List<Compute> out = new ArrayList<>();
		if (sources == null) return out;
		for (ComputeSource src : sources) {
			if (src == null || src.getSource().isEmpty()) continue;
			Compute c = new Compute();
			c.name = src.getName();
			String gl = TransformPatcher.patchCompute(c.name, src.getSource().get(), stage, directives.getTextureMap());
			var vk = VulkanGlslTranslator.translate(Map.of(PatchShaderType.COMPUTE, gl), writer::provides);
			c.layout = vk.layout();
			for (String u : writer.unresolved(c.layout)) notes.add(c.name + ": uniform " + u + " has no Vulkan value yet (zero)");
			String translated = vk.sources().get(PatchShaderType.COMPUTE);
			c.localSize = localSize(c.name, translated);
			byte[] spirv = compiler.compile(c.name + ".csh", ShaderCompiler.Stage.COMPUTE, translated);
			List<GraphicsProgram.Binding> bindings = bindResources(c.name, c.layout, stage, used, flippedOnce, VK_SHADER_STAGE_COMPUTE_BIT, c.bounds);
			c.program = new ComputeProgram(ctx.device(), c.name, spirv, bindings);
			computePrograms.add(c.program);
			if (c.layout.uniformBufferSize() > 0) {
				c.stride = alignUp(c.layout.uniformBufferSize(), UBO_ALIGN);
				c.ubo = buffer(c.name + " uniforms", c.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
			}
			c.workGroups = src.getWorkGroups();
			c.relative = src.getWorkGroupRelative();
			c.indirect = src.getIndirectPointer();
			if (c.indirect != null) c.indirectBuffer = ssbo(c.name, c.indirect.buffer(), "indirect dispatch");
			Arrays.fill(c.slotSubmit, -1);
			programCount++;
			out.add(c);
		}
		return out;
	}

	/** Iris ComputeProgram.getWorkGroups. */
	private static Vector3i workGroups(Compute c, float width, float height) {
		if (c.workGroups != null) return c.workGroups;
		if (c.relative != null) {
			return new Vector3i((int) Math.ceil(Math.ceil(width * c.relative.x) / c.localSize[0]), (int) Math.ceil(Math.ceil(height * c.relative.y) / c.localSize[1]), 1);
		}
		return new Vector3i((int) Math.ceil(width / c.localSize[0]), (int) Math.ceil(height / c.localSize[1]), 1);
	}

	// ------------------------------------------------------------------ terrain gbuffers (Iris SodiumPrograms / SodiumShader)

	private static double[] mat4(Matrix4f m) {
		float[] f = m.get(new float[16]);
		double[] d = new double[16];
		for (int i = 0; i < 16; i++) d[i] = f[i];
		return d;
	}

	/** Iris SodiumShader's uniforms, on top of the same common/custom ones the composite passes get. */
	private void setupGbufferUniforms() {
		var idMap = set.getPack().getIdMap();
		CommonUniforms.addNonDynamicUniforms(gbufferWriter, idMap, directives, notifier);
		CommonUniforms.addDynamicUniforms(gbufferWriter, FogMode.PER_VERTEX);
		BuiltinReplacementUniforms.addBuiltinReplacementUniforms(gbufferWriter);
		// GL state Vulkan doesn't have: the pass's blend state and bound texture. Reported in notes when a program uses them.
		gbufferWriter.override("blendFunc", () -> new double[]{0, 0, 0, 0});
		gbufferWriter.override("atlasSize", () -> new double[]{atlasWidth, atlasHeight});
		gbufferWriter.override("gtextureSize", () -> new double[]{atlasWidth, atlasHeight});
		gbufferWriter.override("gtextureId", () -> new double[]{0});
		CapturedRenderingState s = CapturedRenderingState.INSTANCE;
		// Iris ShadowRenderer: in the shadow pass Sodium's ChunkRenderMatrices hold the shadow view and projection
		gbufferWriter.override("iris_ModelViewMatrix", () -> mat4(terrainModelView()));
		gbufferWriter.override("iris_ModelViewMatrixInverse", () -> mat4(terrainModelView().invert()));
		gbufferWriter.override("iris_ProjectionMatrix", () -> mat4(terrainProjection()));
		gbufferWriter.override("iris_ProjectionMatrixInverse", () -> mat4(terrainProjection().invert()));
		gbufferWriter.override("iris_NormalMatrix", () -> {
			Matrix3f n = terrainModelView().invert().transpose3x3(new Matrix3f());
			float[] f = n.get(new float[9]);
			double[] d = new double[9];
			for (int i = 0; i < 9; i++) d[i] = f[i];
			return d;
		});
		// SodiumShader.setupState: undo Sodium's one-texel coordinate offset, shrunk by one sub-texel (5 bits of precision)
		gbufferWriter.override("u_TexCoordShrink", () -> {
			double subTexel = 1 << 5, offset = 1.0 / CompactChunkVertex.TEXTURE_MAX_VALUE;
			return new double[]{offset - (1.0 / atlasWidth) / subTexel, offset - (1.0 / atlasHeight) / subTexel};
		});
		gbufferWriter.override("iris_TexelSize", () -> new double[]{1.0 / atlasWidth, 1.0 / atlasHeight});
		gbufferWriter.attachCustom(customUniforms);
	}

	private Matrix4f terrainModelView() {
		return shadowing ? new Matrix4f(shadowModelView) : new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
	}

	private Matrix4f terrainProjection() {
		return shadowing ? new Matrix4f(shadowProjection) : new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
	}

	private static AlphaTest terrainAlpha(int kind, ProgramSource source) {
		return source.getDirectives().getAlphaTestOverride().orElse(
			kind == TerrainHook.TRANSLUCENT ? AlphaTests.NON_ZERO_ALPHA : kind == TerrainHook.CUTOUT ? AlphaTests.HALF_ALPHA : AlphaTest.ALWAYS);
	}

	/**
	 * Iris SodiumPrograms: resolve gbuffers_terrain_solid / _cutout / gbuffers_water through Iris's fallback chain,
	 * patch with patchSodium, then pick the chunk vertex format from the attributes the programs read
	 * (FormatAnalyzer, as Iris does after linking).
	 */
	private void buildTerrain(ShaderCompiler compiler, Set<Integer> used) {
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(set);
		// 0-2: gbuffers terrain solid / cutout / water; 3-5: shadow solid / cutout / water (Iris SodiumPrograms.Pass)
		ProgramSource[] sources = new ProgramSource[6];
		VulkanGlslTranslator.Result[] vk = new VulkanGlslTranslator.Result[6];
		AlphaTest[] alpha = new AlphaTest[6];
		boolean blockId = false, normal = false, midUv = false, midBlock = false;
		for (int k = 0; k < (shadowPass ? 6 : 3); k++) {
			ProgramSource src = resolver.resolveNullable(k < 3 ? TERRAIN_IDS[k] : SHADOW_IDS[k - 3]);
			if (src == null || !src.isValid()) continue;
			sources[k] = src;
			if (src.getTessEvalSource().isPresent() || src.getTessControlSource().isPresent()) {
				throw new IllegalStateException(src.getName() + ": tessellation in terrain programs (not implemented yet)");
			}
			alpha[k] = terrainAlpha(k % 3, src);
			Map<PatchShaderType, String> gl = TransformPatcher.patchSodium(src.getName(), src.getVertexSource().orElse(null),
				src.getGeometrySource().orElse(null), null, null, src.getFragmentSource().orElse(null), alpha[k], directives.getTextureMap());
			vk[k] = VulkanGlslTranslator.translate(gl, gbufferWriter::provides, List.of("u_RegionOffset", "iris_CurrentTime"));
			// Iris SodiumPrograms: the chunk format carries what any of its passes reads, shadow passes included
			var a = vk[k].attributes();
			blockId |= a.containsKey("mc_Entity");
			normal |= a.containsKey("iris_Normal");
			midUv |= a.containsKey("mc_midTexCoord");
			midBlock |= a.containsKey("at_midBlock");
		}
		boolean any = false, all = true;
		for (int k = 0; k < 3; k++) {
			any |= sources[k] != null;
			all &= sources[k] != null;
		}
		if (!any) {
			notes.add("pack has no terrain programs: terrain is drawn by Polydium and copied into colortex0");
			if (shadowPass) notes.add("shadow pass skipped: the pack has shadow programs but no terrain programs (not implemented yet)");
			return;
		}
		if (!all) {
			// Polydium's own terrain pipeline reads Sodium's compact 20-byte vertices, so it can't draw the passes the pack leaves out
			throw new IllegalStateException("pack has terrain programs for some passes only (" + Arrays.toString(Arrays.copyOf(sources, 3)) + "); not implemented yet");
		}
		if (shadowPass && (sources[3] == null || sources[4] == null || sources[5] == null)) {
			throw new IllegalStateException("pack has shadow programs for some terrain passes only (" + Arrays.toString(Arrays.copyOfRange(sources, 3, 6)) + "); not implemented yet");
		}
		vertexFormat = FormatAnalyzer.createFormat(blockId, normal, midUv, midBlock);
		int stride = vertexFormat.getVertexFormat().getStride();
		// FormatAnalyzer's order: block id, normal, mid uv, mid block, 4 bytes each after Sodium's 20
		int off = 20;
		int blockIdOffset = blockId ? off : -1;
		if (blockId) off += 4;
		int normalOffset = normal ? off : -1;
		if (normal) off += 4;
		int midUvOffset = midUv ? off : -1;
		if (midUv) off += 4;
		int midBlockOffset = midBlock ? off : -1;
		if (midBlock) off += 4;
		if (off != stride) throw new IllegalStateException("vertex stride " + stride + " != " + off + " computed from FormatAnalyzer's layout");

		for (int k = 0; k < (shadowPass ? 6 : 3); k++) {
			boolean shadow = k >= 3;
			int kind = k % 3;
			ProgramSource src = sources[k];
			Gbuffer g = new Gbuffer();
			g.name = src.getName();
			g.alphaTest = alpha[k].reference();
			var d = src.getDirectives();
			// Iris SodiumPrograms.createFramebuffer: unknown draw buffers = {0} (gbuffers) or {0, 1} (shadow)
			g.drawBuffers = d.hasUnknownDrawBuffers() ? (shadow ? new int[]{0, 1} : new int[]{0}) : d.getDrawBuffers();
			if (!shadow) for (int b : g.drawBuffers) used.add(b);
			g.layout = vk[k].layout();
			for (String u : gbufferWriter.unresolved(g.layout)) notes.add(g.name + ": uniform " + u + " has no Vulkan value yet (zero)");
			for (String u : List.of("blendFunc")) if (g.layout.uniforms().containsKey(u)) notes.add(g.name + ": uniform " + u + " reads GL blend state; written as 0");
			var push = vk[k].pushConstants();
			if (push.containsKey("u_RegionOffset")) g.regionOffset = push.get("u_RegionOffset").offset();
			if (push.containsKey("iris_CurrentTime")) g.currentTime = push.get("iris_CurrentTime").offset();
			g.pushSize = vk[k].pushConstantSize();

			List<GraphicsProgram.Stage> stages = new ArrayList<>();
			List<byte[]> spirv = new ArrayList<>();
			for (var e : vk[k].sources().entrySet()) {
				stages.add(switch (e.getKey()) {
					case VERTEX -> GraphicsProgram.Stage.VERTEX;
					case GEOMETRY -> GraphicsProgram.Stage.GEOMETRY;
					case FRAGMENT -> GraphicsProgram.Stage.FRAGMENT;
					default -> throw new IllegalStateException(g.name + ": stage " + e.getKey() + " in a terrain program");
				});
				spirv.add(compiler.compile(g.name + (shadow ? "_" + SHADOW_IDS[kind].name() : "") + e.getKey().extension, compilerStage(e.getKey()), e.getValue()));
			}
			List<GraphicsProgram.Attribute> attributes = new ArrayList<>();
			for (var a : vk[k].attributes().entrySet()) {
				int loc = a.getValue();
				int kk = k;
				attributes.add(switch (a.getKey()) {
					// Sodium's compact vertex (DefaultChunkMeshAttributes)
					case "a_Position" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R32G32_UINT, 0);
					case "a_Color" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R8G8B8A8_UNORM, 8);
					case "a_TexCoord" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R16G16_UINT, 12);
					case "a_LightAndData" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R8G8B8A8_UINT, 16);
					// Iris's extended attributes (IrisChunkMeshAttributes)
					case "mc_Entity" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R32_UINT, blockIdOffset);
					case "iris_Normal" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R32_UINT, normalOffset);
					case "mc_midTexCoord" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R16G16_USCALED, midUvOffset);
					case "at_midBlock" -> new GraphicsProgram.Attribute(loc, VK_FORMAT_R8G8B8A8_SSCALED, midBlockOffset);
					default -> throw new IllegalStateException(g.name + ": terrain program reads vertex attribute " + a.getKey() + ", which Sodium's chunk vertex doesn't have: "
						+ vk[kk].sources().get(PatchShaderType.VERTEX).lines().filter(l -> l.contains(a.getKey())).limit(6).toList());
				});
			}

			resolvingGbuffer = true;
			List<GraphicsProgram.Binding> bindings;
			try {
				bindings = bindResources(g.name, g.layout, TextureStage.GBUFFERS_AND_SHADOW, used, Set.of(), VK_SHADER_STAGE_ALL_GRAPHICS, g.bounds);
			} finally {
				resolvingGbuffer = false;
			}
			g.usesSsbo = g.bounds.stream().anyMatch(b -> b.source() instanceof Ssbo);

			int[] colorFormats = new int[g.drawBuffers.length];
			GraphicsProgram.Blend[] blends = new GraphicsProgram.Blend[g.drawBuffers.length];
			// Sodium's translucent pass blends like vanilla's translucent terrain; solid and cutout don't blend
			GraphicsProgram.Blend base = kind == TerrainHook.TRANSLUCENT ? GraphicsProgram.Blend.ALPHA : null;
			if (d.getBlendModeOverride().isPresent()) base = blend(d.getBlendModeOverride().get());
			for (int i = 0; i < colorFormats.length; i++) {
				colorFormats[i] = shadow ? shadowColorImage(g.drawBuffers[i]).format()
					: RenderTargetSet.renderableFormat(ctx, VkFormats.of(targetFormats[g.drawBuffers[i]]));
				blends[i] = base;
			}
			for (var info : d.getBufferBlendOverrides()) {
				for (int i = 0; i < g.drawBuffers.length; i++) {
					if (g.drawBuffers[i] == info.index()) blends[i] = blend(new BlendModeOverride(info.blendMode()));
				}
			}
			if (d.getViewportScale() != null && d.getViewportScale().scale() != 1.0f) notes.add(g.name + ": viewport scale ignored (not implemented yet)");
			// GL draws counter-clockwise front faces in GL window space; Polydium keeps GL's row order without a y flip,
			// which mirrors the winding in Vulkan's framebuffer space, so GL's front faces are clockwise here.
			// Iris ShadowRenderer disables backface culling for the whole shadow pass.
			var raster = new GraphicsProgram.Raster(shadow ? VK_FORMAT_D32_SFLOAT : mainDepthFormat, true, true, VK_COMPARE_OP_LESS_OR_EQUAL,
				shadow ? VK_CULL_MODE_NONE : VK_CULL_MODE_BACK_BIT, VK_FRONT_FACE_CLOCKWISE, g.pushSize);
			g.program = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec(g.name, stages, spirv, bindings, attributes, stride,
				VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, colorFormats, blends, raster));
			if (g.layout.uniformBufferSize() > 0) {
				g.stride = alignUp(g.layout.uniformBufferSize(), UBO_ALIGN);
				g.ubo = buffer(g.name + " uniforms", g.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
			}
			Arrays.fill(g.slotSubmit, -1);
			programCount++;
			(shadow ? shadowTerrain : terrain)[kind] = g;
		}
		notes.add("terrain drawn with the pack's programs (" + terrain[0].name + ", " + terrain[1].name + ", " + terrain[2].name
			+ "), vertex stride " + stride);
		if (shadowPass) {
			notes.add("shadow pass: terrain drawn with " + shadowTerrain[0].name + " / " + shadowTerrain[1].name + " / " + shadowTerrain[2].name
				+ " into a " + shadowResolution + "x" + shadowResolution + " shadow map, shadow distance " + shadowDirectives.getDistance());
		}
	}

	/** Iris ShadowCompositeRenderer: shadowcomp computes (fragment shadowcomp passes are reported, not drawn yet). */
	private void buildShadowComposite(ShaderCompiler compiler, Set<Integer> used) {
		for (ProgramSource src : set.getComposite(ProgramArrayId.ShadowComposite)) {
			if (src != null && src.isValid()) notes.add(src.getName() + ": fragment shadowcomp programs are not drawn yet (not implemented yet)");
		}
		List<Compute> list = new ArrayList<>();
		for (ComputeSource[] cs : set.getCompute(ProgramArrayId.ShadowComposite)) {
			list.addAll(buildComputes(compiler, cs, TextureStage.SHADOWCOMP, used, Set.of()));
		}
		shadowComposite = list;
		for (ComputeSource c : set.getShadowCompute()) {
			if (c != null && c.getSource().isPresent()) notes.add(c.getName() + ": shadow compute programs (shadow.csh) are not run yet (not implemented yet)");
		}
		if (!list.isEmpty()) notes.add("shadowcomp: " + list.size() + " compute program(s) run after the shadow pass");
	}

	/** Iris BlendModeOverride (GL blend factors) as a Vulkan blend; OFF = no blending. */
	private static GraphicsProgram.Blend blend(BlendModeOverride o) {
		BlendMode m = o.getBlendMode();
		if (m == null) return null;
		return new GraphicsProgram.Blend(glBlend(m.srcRgb()), glBlend(m.dstRgb()), glBlend(m.srcAlpha()), glBlend(m.dstAlpha()));
	}

	private static int glBlend(int gl) {
		return switch (gl) {
			case 0 -> VK_BLEND_FACTOR_ZERO;
			case 1 -> VK_BLEND_FACTOR_ONE;
			case 0x0300 -> VK_BLEND_FACTOR_SRC_COLOR;
			case 0x0301 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
			case 0x0302 -> VK_BLEND_FACTOR_SRC_ALPHA;
			case 0x0303 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
			case 0x0304 -> VK_BLEND_FACTOR_DST_ALPHA;
			case 0x0305 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
			case 0x0306 -> VK_BLEND_FACTOR_DST_COLOR;
			case 0x0307 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
			case 0x0308 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
			default -> throw new IllegalStateException("GL blend factor 0x" + Integer.toHexString(gl) + " (not implemented yet)");
		};
	}

	/** True when the pack's terrain programs were built: terrain must then be drawn through {@link #drawTerrain}. */
	boolean hasTerrain() {
		return terrain[0] != null;
	}

	/** The chunk vertex format the terrain programs need (Sodium's compact one if there are none). */
	ChunkVertexType vertexFormat() {
		return vertexFormat;
	}

	// ------------------------------------------------------------------ 7c: vanilla geometry (Iris IrisPipelines / ShaderCreator / ExtendedShader)

	/** What a vanilla draw has bound on Polydium's render pass at the time of the draw. */
	interface PassState {
		/** The uniform buffer vanilla set under {@code name} (DynamicTransforms, Projection, Fog, Globals, CloudInfo): {VkBuffer, offset, length}, or null. */
		long[] block(String name);

		/** The texture vanilla bound to {@code name} (Sampler0/1/2), or null. */
		GpuImage texture(String name);

		/** The VkSampler vanilla bound with {@code name}'s texture. */
		long sampler(String name);

		/** Viewport size and the current scissor rectangle {x, y, w, h}. */
		int viewportWidth();

		int viewportHeight();

		int[] scissor();
	}

	/** A vanilla uniform block ({@code iris_<name>} in the patched program) that the render pass set. */
	private record PassBlock(String name) implements Source {
	}

	/** A texel buffer the vanilla pass set as a uniform (clouds' {@code CloudFaces}: R8I), read through our own buffer view. */
	private record PassTexel(String name, int format) implements Source {
	}

	private final Map<List<Long>, Long> texelViews = new HashMap<>();

	private long texelView(long buffer, long offset, long range, int format) {
		List<Long> key = List.of(buffer, offset, range, (long) format);
		Long v = texelViews.get(key);
		if (v != null) return v;
		if (texelViews.size() >= 32) {
			// the buffer moved (e.g. clouds rebuilt): drop the old views once the frames using them are done
			List<Long> old = new ArrayList<>(texelViews.values());
			destroyer.defer("texel buffer views", () -> old.forEach(x -> vkDestroyBufferView(ctx.device(), x, null)));
			texelViews.clear();
		}
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer lp = s.mallocLong(1);
			VkCheck.check(vkCreateBufferView(ctx.device(), VkBufferViewCreateInfo.calloc(s).sType$Default().buffer(buffer).format(format)
				.offset(offset).range(range), null, lp), "vkCreateBufferView");
			texelViews.put(key, lp.get(0));
			return lp.get(0);
		}
	}

	/** A vanilla sampler (Sampler0 albedo, Sampler1 overlay, Sampler2 lightmap) that the render pass bound. */
	private record PassSampler(String name) implements Source {
	}

	/** One pack program for an Iris ShaderKey; pipelines are made per vanilla RenderPipeline it replaces (vanilla's state, the pack's shaders). */
	private static final class Vanilla {
		ShaderKey key;
		/** A shadow-pass key (Iris IrisPipelines' shadow map): draws into shadowcolorN and shadowtex0. */
		boolean shadow;
		String name;
		ShaderResourceLayout layout;
		List<Bound> bounds = new ArrayList<>();
		int[] drawBuffers;
		GpuBuffer ubo;
		long stride;
		final long[] slotSubmit = new long[SLOTS];
		long filledFrame = -1, feedbackFrame = -1;
		float alphaTest;
		boolean usesSsbo;
		List<GraphicsProgram.Stage> stages;
		List<byte[]> spirv;
		List<GraphicsProgram.Binding> bindings;
		Map<String, Integer> attributes;
		/** Pack blend directive (Iris ExtendedShader applies it over vanilla's blend); empty Optional inside = "off". */
		Optional<BlendModeOverride> blendOverride = Optional.empty();
		final Map<Integer, BlendModeOverride> bufferBlends = new HashMap<>();
		final Map<RenderPipeline, GraphicsProgram[]> variants = new IdentityHashMap<>();
		final Map<RenderPipeline, Boolean> usesConstants = new IdentityHashMap<>();
		long draws;
	}

	private final Map<ShaderKey, Vanilla> vanilla = new EnumMap<>(ShaderKey.class);
	private final Map<ShaderKey, String> vanillaFailures = new EnumMap<>(ShaderKey.class);
	private final VulkanUniformWriter vanillaWriter = new VulkanUniformWriter();
	private ShaderAttributeInputs resolvingVanilla;
	private PassState passState;
	private GpuBuffer constantAttributes;
	private GpuImage[] vanillaOuts;
	private boolean vanillaOpen, vanillaOpenDepth;
	private GpuImage vanillaOpenDepthImage;
	private final Set<String> unmappedPipelines = new TreeSet<>();

	/** Iris ShaderCreator.create's uniforms (CommonUniforms PER_VERTEX, custom, builtin replacements, VanillaUniforms) and ExtendedShader's matrices. */
	private void setupVanillaUniforms() {
		var idMap = set.getPack().getIdMap();
		CommonUniforms.addNonDynamicUniforms(vanillaWriter, idMap, directives, notifier);
		CommonUniforms.addDynamicUniforms(vanillaWriter, FogMode.PER_VERTEX);
		BuiltinReplacementUniforms.addBuiltinReplacementUniforms(vanillaWriter);
		VanillaUniforms.addVanillaUniforms(vanillaWriter);
		vanillaWriter.override("blendFunc", () -> new double[]{0, 0, 0, 0});
		vanillaWriter.override("atlasSize", () -> new double[]{atlasWidth, atlasHeight});
		vanillaWriter.override("gtextureSize", () -> new double[]{atlasWidth, atlasHeight});
		vanillaWriter.override("gtextureId", () -> new double[]{0});
		// ExtendedShader.iris$setupState: inverse model-view and normal matrix from RenderSystem's model-view, projection inverse
		// from the captured gbuffer projection
		vanillaWriter.override("iris_ModelViewMatInverse", () -> mat4(new Matrix4f(RenderSystem.getModelViewMatrix()).invert()));
		vanillaWriter.override("iris_NormalMat", () -> {
			Matrix3f n = new Matrix4f(RenderSystem.getModelViewMatrix()).invert().transpose3x3(new Matrix3f());
			float[] f = n.get(new float[9]);
			double[] d = new double[9];
			for (int i = 0; i < 9; i++) d[i] = f[i];
			return d;
		});
		vanillaWriter.override("iris_ProjMatInverse", () -> mat4(new Matrix4f(ShadowRenderingState.areShadowsCurrentlyBeingRendered()
			? shadowProjection : CapturedRenderingState.INSTANCE.getGbufferProjection()).invert()));
		// FallbackShader's own uniforms: the key's alpha reference (set per program before its uniforms are written) and fog
		vanillaWriter.override("AlphaTestValue", () -> new double[]{CapturedRenderingState.INSTANCE.getCurrentAlphaTest()});
		vanillaWriter.override("FogDensity", () -> new double[]{Math.max(0f, CapturedRenderingState.INSTANCE.getFogDensity())});
		vanillaWriter.override("FogIsExp2", () -> new double[]{CapturedRenderingState.INSTANCE.getFogDensity() >= 0 ? 1 : 0});
		vanillaWriter.attachCustom(customUniforms);
	}

	/** The ShaderKeys Iris maps the world's vanilla pipelines to (IrisPipelines' main map), minus the ones Sodium/Polydium terrain covers. */
	private static boolean isVanillaKey(ShaderKey k) {
		if (k.isShadow()) return false;
		return switch (k) {
			// hand programs: drawn inside the world by VulkanHand (Iris's HandRenderer port), like on GL
			// other mods' formats, and Sodium's own clouds
			case IE_COMPAT, MEKANISM_FLAME, CLOUDS_SODIUM -> false;
			default -> true;
		};
	}

	/**
	 * Iris IrisRenderingPipeline.createShader / ShaderCreator.create for every main-pass ShaderKey: the key's program through
	 * Iris's fallback chain, patched with patchVanilla, translated and compiled. Pipelines are made at first use, per vanilla
	 * RenderPipeline (vertex format, topology, blend, depth and cull are vanilla's, as on GL).
	 */
	private void buildVanilla(ShaderCompiler compiler, Set<Integer> used) {
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(set);
		for (ShaderKey key : ShaderKey.values()) {
			// Iris IrisRenderingPipeline: shadow keys get createShadowShader when the pack has a shadow pass
			if (key.isShadow() ? !shadowPass || key == ShaderKey.IE_COMPAT_SHADOW : !isVanillaKey(key)) continue;
			Optional<ProgramSource> found = resolver.resolve(key.getProgram());
			if (found.isEmpty()) {
				try {
					vanilla.put(key, buildFallbackProgram(compiler, key, used));
					fallbacks.add(key.getName());
				} catch (RuntimeException e) {
					vanillaFailures.put(key, "Iris's synthesized fallback shader: " + e.getMessage());
				}
				continue;
			}
			ProgramSource src = found.get();
			try {
				vanilla.put(key, buildVanillaProgram(compiler, key, src, resolver, used));
			} catch (RuntimeException e) {
				vanillaFailures.put(key, src.getName() + ": " + e.getMessage());
			}
		}
		for (var e : vanillaFailures.entrySet()) notes.add("gbuffers " + e.getKey().getName() + ": not drawn with the pack (" + e.getValue() + ")");
		if (!fallbacks.isEmpty()) notes.add("no pack program in the fallback chain, drawn with Iris's synthesized fallback shader (colortex0): " + fallbacks);
		int[] constants = {0, 0, 0, Float.floatToIntBits(1f), 0, 0, 0, 1};
		constantAttributes = buffer("GL default vertex attributes", 32, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
		MemoryUtil.memIntBuffer(constantAttributes.mapped(), constants.length).put(constants);
		alloc.flush(constantAttributes);
	}

	private final List<String> fallbacks = new ArrayList<>();

	/**
	 * Iris IrisRenderingPipeline.createFallbackShader / ShaderCreator.createFallback: when no program in the key's fallback
	 * chain exists, Iris synthesizes one (ShaderSynthesizer — Iris's own code, used as is) that writes colortex0 only.
	 * Its loose uniforms (AlphaTestValue, FogDensity, FogIsExp2) are FallbackShader's, set the same way.
	 */
	private Vanilla buildFallbackProgram(ShaderCompiler compiler, ShaderKey key, Set<Integer> used) {
		Vanilla v = new Vanilla();
		v.key = key;
		v.shadow = key.isShadow();
		v.name = "fallback (" + key.getName() + ")";
		v.alphaTest = key.getAlphaTest().reference();
		ShaderAttributeInputs inputs = new ShaderAttributeInputs(key.getVertexFormat(), key.shouldIgnoreLightmap(), false, key == ShaderKey.GLINT,
			key.isText(), false);
		boolean isLeash = key.getVertexFormat() == com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
		String vsh = ShaderSynthesizer.vsh(true, inputs, key.getFogMode(), key.hasDiffuseLighting(), isLeash);
		// GLSL uniform initializers (FallbackShader always sets both before drawing): the values come from the uniform writer
		String fsh = ShaderSynthesizer.fsh(inputs, key.getFogMode(), key.getAlphaTest(), key.isIntensity(), isLeash)
			.replace("uniform float FogDensity = 1.0;", "uniform float FogDensity;").replace("uniform int FogIsExp2 = 1;", "uniform int FogIsExp2;")
			// its single output is colortex0 (Iris binds a one-attachment framebuffer); Vulkan needs the location spelled out
			.replace("out vec4 fragColor;", "layout(location = 0) out vec4 fragColor;");
		Map<PatchShaderType, String> gl = new EnumMap<>(PatchShaderType.class);
		gl.put(PatchShaderType.VERTEX, vsh);
		gl.put(PatchShaderType.FRAGMENT, fsh);
		var vk = VulkanGlslTranslator.translate(gl, vanillaWriter::provides);
		v.layout = vk.layout();
		v.attributes = vk.attributes();
		for (String u : vanillaWriter.unresolved(v.layout)) notes.add(v.name + ": uniform " + u + " has no Vulkan value yet (zero)");
		v.drawBuffers = new int[]{0};
		if (v.shadow) v.blendOverride = Optional.of(BlendModeOverride.OFF);
		else used.add(0);
		v.stages = new ArrayList<>();
		v.spirv = new ArrayList<>();
		for (var e : vk.sources().entrySet()) {
			v.stages.add(e.getKey() == PatchShaderType.VERTEX ? GraphicsProgram.Stage.VERTEX : GraphicsProgram.Stage.FRAGMENT);
			v.spirv.add(compiler.compile(v.name + e.getKey().extension, compilerStage(e.getKey()), e.getValue()));
		}
		resolvingGbuffer = true;
		resolvingVanilla = inputs;
		try {
			v.bindings = bindResources(v.name, v.layout, TextureStage.GBUFFERS_AND_SHADOW, used, Set.of(), VK_SHADER_STAGE_ALL_GRAPHICS, v.bounds);
		} finally {
			resolvingGbuffer = false;
			resolvingVanilla = null;
		}
		if (v.layout.uniformBufferSize() > 0) {
			v.stride = alignUp(v.layout.uniformBufferSize(), UBO_ALIGN);
			v.ubo = buffer(v.name + " uniforms", v.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		}
		Arrays.fill(v.slotSubmit, -1);
		programCount++;
		return v;
	}

	private Vanilla buildVanillaProgram(ShaderCompiler compiler, ShaderKey key, ProgramSource src, ProgramFallbackResolver resolver, Set<Integer> used) {
		if (src.getTessEvalSource().isPresent() || src.getTessControlSource().isPresent()) {
			throw new IllegalStateException("tessellation (not implemented yet)");
		}
		Vanilla v = new Vanilla();
		v.key = key;
		v.shadow = key.isShadow();
		v.name = src.getName() + " (" + key.getName() + ")";
		var d = src.getDirectives();
		AlphaTest alpha = d.getAlphaTestOverride().orElse(key.getAlphaTest());
		v.alphaTest = alpha.reference();
		boolean isLines = key.getProgram() == ProgramId.Line && resolver.has(ProgramId.Line);
		// IrisRenderingPipeline.createShadowShader passes isGlint = false
		ShaderAttributeInputs inputs = new ShaderAttributeInputs(key.getVertexFormat(), key.shouldIgnoreLightmap(), isLines, !v.shadow && key.isGlint(),
			key.isText(), key == ShaderKey.IE_COMPAT);
		Map<PatchShaderType, String> gl = TransformPatcher.patchVanilla(v.name, src.getVertexSource().orElseThrow(), src.getGeometrySource().orElse(null),
			null, null, src.getFragmentSource().orElseThrow(), alpha, isLines, key == ShaderKey.CLOUDS, true, inputs, directives.getTextureMap());
		var vk = VulkanGlslTranslator.translate(gl, vanillaWriter::provides);
		v.layout = vk.layout();
		v.attributes = vk.attributes();
		for (String u : vanillaWriter.unresolved(v.layout)) notes.add(v.name + ": uniform " + u + " has no Vulkan value yet (zero)");
		// ShaderCreator.createShadow: unknown draw buffers = {0, 1} in the shadow framebuffer
		v.drawBuffers = v.shadow && d.hasUnknownDrawBuffers() ? new int[]{0, 1} : d.getDrawBuffers();
		if (!v.shadow) for (int b : v.drawBuffers) used.add(b);
		v.stages = new ArrayList<>();
		v.spirv = new ArrayList<>();
		for (var e : vk.sources().entrySet()) {
			v.stages.add(switch (e.getKey()) {
				case VERTEX -> GraphicsProgram.Stage.VERTEX;
				case GEOMETRY -> GraphicsProgram.Stage.GEOMETRY;
				case FRAGMENT -> GraphicsProgram.Stage.FRAGMENT;
				default -> throw new IllegalStateException("stage " + e.getKey());
			});
			v.spirv.add(compiler.compile(v.name + e.getKey().extension, compilerStage(e.getKey()), e.getValue()));
		}
		resolvingGbuffer = true;
		resolvingVanilla = inputs;
		try {
			v.bindings = bindResources(v.name, v.layout, TextureStage.GBUFFERS_AND_SHADOW, used, Set.of(), VK_SHADER_STAGE_ALL_GRAPHICS, v.bounds);
		} finally {
			resolvingGbuffer = false;
			resolvingVanilla = null;
		}
		v.usesSsbo = v.bounds.stream().anyMatch(b -> b.source() instanceof Ssbo);
		// Iris ShaderCreator: the program's blend directive, else the program id's default override; then per-buffer ones
		Optional<BlendModeOverride> o = d.getBlendModeOverride();
		if (o.isEmpty() && key.getProgram().getBlendModeOverride() != null) o = Optional.of(key.getProgram().getBlendModeOverride());
		v.blendOverride = o;
		for (var info : d.getBufferBlendOverrides()) {
			for (int i = 0; i < v.drawBuffers.length; i++) if (v.drawBuffers[i] == info.index()) v.bufferBlends.put(i, new BlendModeOverride(info.blendMode()));
		}
		if (d.getViewportScale() != null && d.getViewportScale().scale() != 1.0f) notes.add(v.name + ": viewport scale ignored (not implemented yet)");
		if (v.layout.uniformBufferSize() > 0) {
			v.stride = alignUp(v.layout.uniformBufferSize(), UBO_ALIGN);
			v.ubo = buffer(v.name + " uniforms", v.stride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
		}
		Arrays.fill(v.slotSubmit, -1);
		programCount++;
		return v;
	}

	/** The pack program Iris would draw this vanilla pipeline with, or null (then Polydium draws it as vanilla). */
	ShaderKey vanillaKey(RenderPipeline pipeline) {
		ShaderKey key = IrisPipelines.getPipeline(null, pipeline);
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered() && (key == null || !vanilla.containsKey(key))) {
			shadowDropped.add(pipeline.getLocation() + (key == null ? " (no shadow mapping in Iris)" : " (" + key.getName() + ": " + vanillaFailures.get(key) + ")"));
			return null;
		}
		if (key == null) {
			unmappedPipelines.add(pipeline.getLocation().toString());
			return null;
		}
		return vanilla.containsKey(key) ? key : null;
	}

	/** Pipelines drawn in the shadow pass that Vulkan Iris has no shadow program for; they are skipped. */
	final Set<String> shadowDropped = new TreeSet<>();

	Set<String> unmappedPipelines() {
		return unmappedPipelines;
	}

	Map<String, Long> vanillaDraws() {
		Map<String, Long> m = new TreeMap<>();
		for (Vanilla v : vanilla.values()) if (v.draws > 0) m.put(v.name, v.draws);
		return m;
	}

	private static final String[] FLOAT_DEFAULTS = {"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock"};

	/** Iris's attribute names for vanilla's vertex elements (VertexFormatExtension.bindAttributesIris). */
	private static VertexFormatElement element(String attribute) {
		return switch (attribute) {
			case "iris_Position" -> VertexFormatElement.POSITION;
			case "iris_Color" -> VertexFormatElement.COLOR;
			case "iris_UV0" -> VertexFormatElement.UV0;
			case "iris_UV1" -> VertexFormatElement.UV1;
			case "iris_UV2" -> VertexFormatElement.UV2;
			case "iris_Normal" -> VertexFormatElement.NORMAL;
			case "iris_LineWidth" -> VertexFormatElement.LINE_WIDTH;
			// vanilla's own names (Iris's synthesized fallback shader)
			case "Position" -> VertexFormatElement.POSITION;
			case "Color" -> VertexFormatElement.COLOR;
			case "UV0" -> VertexFormatElement.UV0;
			case "UV1" -> VertexFormatElement.UV1;
			case "UV2" -> VertexFormatElement.UV2;
			case "Normal" -> VertexFormatElement.NORMAL;
			case "LineWidth" -> VertexFormatElement.LINE_WIDTH;
			default -> null;
		};
	}

	/** GL's vertex attribute setup for a vanilla element: integer UVs are read as integers, other non-float types normalized. */
	private static int vkFormat(VertexFormatElement e) {
		int n = e.count();
		boolean integer = e.usage() == VertexFormatElement.Usage.UV;
		return switch (e.type()) {
			case FLOAT -> new int[]{VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT}[n - 1];
			case UBYTE -> (integer ? new int[]{VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT}
				: new int[]{VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM})[n - 1];
			case BYTE -> (integer ? new int[]{VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8_SINT, VK_FORMAT_R8G8B8A8_SINT}
				: new int[]{VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8_SNORM, VK_FORMAT_R8G8B8A8_SNORM})[n - 1];
			case USHORT -> (integer ? new int[]{VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT, VK_FORMAT_R16G16B16A16_UINT}
				: new int[]{VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM, VK_FORMAT_R16G16B16A16_UNORM})[n - 1];
			case SHORT -> (integer ? new int[]{VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16_SINT, VK_FORMAT_R16G16B16A16_SINT}
				: new int[]{VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16_SNORM, VK_FORMAT_R16G16B16A16_SNORM})[n - 1];
			case UINT -> new int[]{VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT}[n - 1];
			case INT -> new int[]{VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32A32_SINT}[n - 1];
		};
	}

	private static int topology(VertexFormat.Mode mode) {
		return switch (mode) {
			// vanilla draws quads and its widened lines as indexed triangles
			case QUADS, TRIANGLES, LINES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
			case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
			case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
			case DEBUG_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
			case DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
			case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
		};
	}

	private static int vkBlend(String factor) {
		return switch (factor) {
			case "ZERO" -> VK_BLEND_FACTOR_ZERO;
			case "ONE" -> VK_BLEND_FACTOR_ONE;
			case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
			case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
			case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
			case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
			case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
			case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
			case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
			case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
			case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
			case "CONSTANT_COLOR" -> VK_BLEND_FACTOR_CONSTANT_COLOR;
			case "ONE_MINUS_CONSTANT_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
			case "CONSTANT_ALPHA" -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
			case "ONE_MINUS_CONSTANT_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
			default -> throw new IllegalStateException("blend factor " + factor + " (not implemented yet)");
		};
	}

	private static int depthCompare(DepthTestFunction f) {
		return switch (f) {
			case NO_DEPTH_TEST -> VK_COMPARE_OP_ALWAYS;
			case EQUAL_DEPTH_TEST -> VK_COMPARE_OP_EQUAL;
			case LEQUAL_DEPTH_TEST -> VK_COMPARE_OP_LESS_OR_EQUAL;
			case LESS_DEPTH_TEST -> VK_COMPARE_OP_LESS;
			case GREATER_DEPTH_TEST -> VK_COMPARE_OP_GREATER;
		};
	}

	/** The pack program with vanilla's fixed-function state for {@code p}, into the program's draw buffers (+ main depth). */
	private GraphicsProgram variant(Vanilla v, RenderPipeline p, boolean depth) {
		GraphicsProgram[] arr = v.variants.computeIfAbsent(p, k -> new GraphicsProgram[2]);
		int i = depth ? 1 : 0;
		if (arr[i] != null) return arr[i];
		VertexFormat format = p.getVertexFormat();
		List<GraphicsProgram.Attribute> attributes = new ArrayList<>();
		boolean constants = false;
		for (var a : v.attributes.entrySet()) {
			VertexFormatElement e = element(a.getKey());
			if (e != null && format.contains(e)) {
				attributes.add(new GraphicsProgram.Attribute(a.getValue(), vkFormat(e), format.getOffset(e)));
				continue;
			}
			// GL: an attribute the vertex format doesn't supply reads the current value, (0, 0, 0, 1) by default
			boolean integer = a.getKey().equals("iris_UV1") || a.getKey().equals("iris_UV2") || a.getKey().equals("iris_Entity")
				|| a.getKey().equals("UV1") || a.getKey().equals("UV2");
			if (e == null && !integer && !Arrays.asList(FLOAT_DEFAULTS).contains(a.getKey())) {
				throw new IllegalStateException(v.name + " reads vertex attribute " + a.getKey() + " (not implemented yet)");
			}
			if (Arrays.asList(FLOAT_DEFAULTS).contains(a.getKey()) || a.getKey().equals("iris_Entity")) {
				notes.add(v.name + ": " + a.getKey() + " = GL's default (0,0,0,1): Iris's extended entity vertex format isn't ported yet");
			}
			attributes.add(new GraphicsProgram.Attribute(a.getValue(), integer ? VK_FORMAT_R32G32B32A32_SINT : VK_FORMAT_R32G32B32A32_SFLOAT,
				integer ? 16 : 0, GraphicsProgram.CONSTANT_BINDING));
			constants = true;
		}
		v.usesConstants.put(p, constants);

		int n = v.drawBuffers.length;
		int[] colorFormats = new int[n];
		GraphicsProgram.Blend[] blends = new GraphicsProgram.Blend[n];
		GraphicsProgram.Blend base = p.getBlendFunction().map(b -> new GraphicsProgram.Blend(vkBlend(b.sourceColor().name()), vkBlend(b.destColor().name()),
			vkBlend(b.sourceAlpha().name()), vkBlend(b.destAlpha().name()))).orElse(null);
		if (v.blendOverride.isPresent()) base = blend(v.blendOverride.get());
		for (int k = 0; k < n; k++) {
			colorFormats[k] = v.shadow ? shadowColorImage(v.drawBuffers[k]).format()
				: RenderTargetSet.renderableFormat(ctx, VkFormats.of(targetFormats[v.drawBuffers[k]]));
			blends[k] = v.bufferBlends.containsKey(k) ? blend(v.bufferBlends.get(k)) : base;
			boolean integer = v.shadow ? VkFormats.isInteger(shadowDirectives.getColorSamplingSettings().get(v.drawBuffers[k]).getFormat())
				: VkFormats.isInteger(targetFormats[v.drawBuffers[k]]);
			if (blends[k] != null && integer) {
				// GL ignores blending for integer attachments; Vulkan forbids enabling it
				blends[k] = null;
			}
		}
		if (p.getPolygonMode() != com.mojang.blaze3d.platform.PolygonMode.FILL) notes.add(p.getLocation() + ": polygon mode " + p.getPolygonMode() + " drawn filled (not implemented yet)");
		if (p.getColorLogic() != com.mojang.blaze3d.platform.LogicOp.NONE) notes.add(p.getLocation() + ": colour logic op " + p.getColorLogic() + " ignored (not implemented yet)");
		boolean test = depth && p.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST;
		int mask = (p.isWriteColor() ? VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT : 0) | (p.isWriteAlpha() ? VK_COLOR_COMPONENT_A_BIT : 0);
		var raster = new GraphicsProgram.Raster(depth ? (v.shadow ? VK_FORMAT_D32_SFLOAT : mainDepthFormat) : 0, test, depth && p.isWriteDepth(), depthCompare(p.getDepthTestFunction()),
			p.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE, VK_FRONT_FACE_CLOCKWISE, 0, mask, p.getDepthBiasConstant(), p.getDepthBiasScaleFactor());
		arr[i] = new GraphicsProgram(ctx.device(), new GraphicsProgram.Spec(v.name + " / " + p.getLocation(), v.stages, v.spirv, v.bindings, attributes,
			format.getVertexSize(), topology(p.getVertexFormatMode()), colorFormats, blends, raster));
		return arr[i];
	}

	/**
	 * One vanilla draw with the pack's program (Iris ExtendedShader: vanilla's buffers, uniform blocks and textures; the
	 * pack's shaders and targets). Called while Polydium's own rendering is suspended; consecutive draws into the same
	 * targets share one dynamic-rendering scope. {@code indexed}: vkCmdDrawIndexed(a..e), else vkCmdDraw(a..d).
	 */
	void drawVanilla(VkCommandBuffer cmd, LayoutTracker tracker, ShaderKey key, RenderPipeline pipeline, PassState st, boolean depth,
					 boolean indexed, int a, int b, int c, int d, int e) {
		if (!levelBegun) throw new IllegalStateException("vanilla draw before beginLevel");
		Vanilla v = vanilla.get(key);
		if (v.shadow && !shadowBegun) throw new IllegalStateException(v.name + ": shadow draw outside beginShadow / endShadow");
		this.cmd = cmd;
		this.tracker = tracker;
		this.currentDepth = mainDepth;
		passState = st;
		GpuImage[] outs = new GpuImage[v.drawBuffers.length];
		for (int i = 0; i < outs.length; i++) outs[i] = v.shadow ? shadowColorImage(v.drawBuffers[i]) : targets.read(v.drawBuffers[i]);
		GpuImage depthAttachment = v.shadow ? shadowDepthImage(0) : mainDepthAttachment;
		Resolved r = new Resolved(v.bounds, mainDepth);
		if (depth && r.sampled.contains(depthAttachment)) {
			throw new IllegalStateException(v.name + " samples " + depthAttachment.name() + " while drawing into it (not implemented yet)");
		}
		boolean sameTargets = vanillaOpen && vanillaOpenDepth == depth && vanillaOpenDepthImage == depthAttachment && Arrays.equals(outs, vanillaOuts);
		for (GpuImage o : outs) {
			if (r.storage.contains(o)) throw new IllegalStateException(v.name + " uses " + o.name() + " as a storage image and renders to it (not implemented)");
			if (r.sampled.contains(o)) {
				GpuImage copy = feedbackCopy(o);
				if (v.feedbackFrame != frame) {
					// once per program per frame (GL would see every earlier draw; see notes)
					endVanilla();
					copyColor(o, copy);
					v.feedbackFrame = frame;
					sameTargets = false;
				}
				r.replace(o, copy);
				notes.add(v.name + ": samples " + o.name() + " while drawing into it (GL feedback loop); reads a copy taken at the program's first draw of the frame");
			}
		}
		r.declare(LayoutTracker.Use.SAMPLED_GRAPHICS, LayoutTracker.Use.STORAGE_FRAGMENT);
		if (!sameTargets || tracker.hasPending()) {
			if (vanillaOpen) {
				GraphicsProgram.endRendering(cmd);
				vanillaOpen = false;
			}
			for (GpuImage o : outs) tracker.use(o, LayoutTracker.Use.COLOR_ATTACHMENT);
			if (depth) tracker.use(depthAttachment, LayoutTracker.Use.DEPTH_ATTACHMENT);
			tracker.flush(cmd);
			if (v.usesSsbo) memoryBarrier();
			GraphicsProgram.beginRendering(cmd, outs, depth ? depthAttachment : null);
			vanillaOpen = true;
			vanillaOpenDepth = depth;
			vanillaOpenDepthImage = depthAttachment;
			vanillaOuts = outs;
		}

		GraphicsProgram program = variant(v, pipeline, depth);
		int slot = (int) (frame % SLOTS);
		boolean fill = v.filledFrame != frame;
		if (fill) {
			v.slotSubmit[slot] = clock.currentIndex();
			v.filledFrame = frame;
			CapturedRenderingState.INSTANCE.setCurrentAlphaTest(v.alphaTest);
		}
		long ds = ring.allocate(program.setLayout());
		r.write(ds, v.bounds, v.layout, v.ubo, v.stride, slot, vanillaWriter, fill, 0);
		program.bind(cmd, ds);
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (v.usesConstants.get(pipeline)) vkCmdBindVertexBuffers(cmd, GraphicsProgram.CONSTANT_BINDING, s.longs(constantAttributes.buffer()), s.longs(0));
			VkViewport.Buffer vp = VkViewport.calloc(1, s);
			// the shadow pass draws over the whole shadow map (Iris ShadowRenderer.setupShadowViewport)
			int vw = v.shadow ? shadowResolution : st.viewportWidth(), vh = v.shadow ? shadowResolution : st.viewportHeight();
			vp.get(0).width(vw).height(vh).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			int[] sc = v.shadow ? new int[]{0, 0, shadowResolution, shadowResolution} : st.scissor();
			VkRect2D.Buffer rect = VkRect2D.calloc(1, s);
			rect.get(0).offset(o -> o.set(sc[0], sc[1])).extent(x -> x.set(sc[2], sc[3]));
			vkCmdSetScissor(cmd, 0, rect);
		}
		if (indexed) vkCmdDrawIndexed(cmd, a, b, c, d, e);
		else vkCmdDraw(cmd, a, b, c, d);
		v.draws++;
		passState = null;
	}

	/** Ends Vulkan Iris's rendering scope for vanilla draws, if one is open (before Polydium resumes its own pass). */
	void endVanilla() {
		if (vanillaOpen) {
			GraphicsProgram.endRendering(cmd);
			vanillaOpen = false;
		}
		vanillaOuts = null;
	}

	/** {@link #endVanilla} for the caller, then hands the external images back to Polydium. */
	void endVanilla(VkCommandBuffer cmd) {
		this.cmd = cmd;
		endVanilla();
		endSegment();
	}

	// ------------------------------------------------------------------ custom images (Iris GlImage)

	private record ImageEntry(ImageInformation info, GpuImage image, boolean integer) {
	}

	private final Map<String, ImageEntry> customImages = new LinkedHashMap<>();

	private ImageEntry imageEntry(String pass, ImageInformation info) {
		return customImages.computeIfAbsent(info.name(), k -> {
			int format = VkFormats.of(info.internalTextureFormat());
			int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			requireStorageFormat(info.name(), format);
			int w = info.width(), h = info.height(), d = info.depth();
			if (info.isRelative()) {
				RenderTarget main = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
				w = (int) (main.width * info.relativeWidth());
				h = (int) (main.height * info.relativeHeight());
				notes.add("image " + info.name() + " is screen-relative; sized for " + main.width + "x" + main.height + " and not resized yet");
			}
			GpuImage img = switch (info.target()) {
				case TEXTURE_1D -> alloc.createImage1D("image " + info.name(), format, w, usage);
				case TEXTURE_2D, TEXTURE_RECTANGLE -> alloc.createImage("image " + info.name(), format, w, h, usage, VK_IMAGE_ASPECT_COLOR_BIT);
				case TEXTURE_3D -> alloc.createImage3D("image " + info.name(), format, w, h, d, usage);
			};
			ownedImages.add(img);
			boolean integer = VkFormats.isInteger(info.internalTextureFormat());
			// GL allocates the image with no data; drivers give zeros, and Iris clears "clear" images to 0 every frame anyway
			uploads.add(() -> clearColor(img, new Vector4f(0f), integer));
			return new ImageEntry(info, img, integer);
		});
	}

	private void requireStorageFormat(String name, int format) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkFormatProperties props = VkFormatProperties.calloc(s);
			vkGetPhysicalDeviceFormatProperties(ctx.physicalDevice(), format, props);
			if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
				throw new IllegalStateException("image " + name + ": format " + format + " cannot be a storage image on this GPU");
			}
		}
	}

	/** {@code bySampler}: n is a sampler name (IrisSamplers.addCustomImages), else an image name (IrisImages.addCustomImages). */
	private CustomImage customImage(String pass, String n, boolean bySampler) {
		for (ImageInformation info : set.getPack().getIrisCustomImages()) {
			if (bySampler ? n.equals(info.samplerName()) : n.equals(info.name())) {
				ImageEntry e = imageEntry(pass, info);
				return new CustomImage(e.image(), e.integer());
			}
		}
		return null;
	}

	/** Images the pack declares but no Phase 6 program touches still exist on GL; create them so shadow/gbuffer phases find them. */
	private void buildCustomImages() {
		for (ImageInformation info : set.getPack().getIrisCustomImages()) imageEntry("pack", info);
	}

	private void clearCustomImages() {
		for (ImageEntry e : customImages.values()) {
			if (e.info().clear()) clearColor(e.image(), new Vector4f(0f), e.integer());
		}
	}

	/** Iris IrisSamplers.addLevelSamplers for a vanilla program, and vanilla's uniform blocks; null = the common rules decide. */
	private Source resolveVanilla(String pass, ShaderResourceLayout.Descriptor d) {
		String n = d.name();
		ShaderAttributeInputs in = resolvingVanilla;
		if (d.kind() == ShaderResourceLayout.Kind.UNIFORM_BUFFER) {
			return switch (n) {
				case "iris_DynamicTransforms", "iris_Projection", "iris_Fog", "iris_Globals", "iris_CloudInfo" -> new PassBlock(n.substring("iris_".length()));
				// Iris's synthesized fallback shader uses vanilla's block names
				case "DynamicTransforms", "Projection", "Fog", "Globals", "Lighting" -> new PassBlock(n);
				default -> null;
			};
		}
		if (d.kind() != ShaderResourceLayout.Kind.SAMPLER) return null;
		return switch (n) {
			case "tex", "texture", "gtexture", "u_MainSampler" -> in.hasTex() ? new PassSampler("Sampler0") : white();
			case "gcolor", "colortex0" -> in.hasTex() ? null : white();
			case "lightmap" -> in.hasLight() ? new PassSampler("Sampler2") : white();
			case "iris_overlay" -> in.hasOverlay() ? new PassSampler("Sampler1") : white();
			// fallback shader (vanilla names; FallbackShader binds units 0/1/2)
			case "Sampler0" -> in.hasTex() ? new PassSampler("Sampler0") : white();
			case "Sampler1" -> in.hasOverlay() ? new PassSampler("Sampler1") : white();
			case "Sampler2" -> in.hasLight() ? new PassSampler("Sampler2") : white();
			// vanilla CloudRenderer: setUniform("CloudFaces", slice), UniformType.TEXEL_BUFFER, TextureFormat.RED8I
			case "CloudFaces" -> new PassTexel("CloudFaces", VK_FORMAT_R8_SINT);
			default -> null;
		};
	}

	/** Iris's white pixel (ExtendedShader binds it where a program has no texture). */
	private Source white() {
		return solid("white pixel", 1f, 1f, 1f, 1f);
	}

	/** What an unassigned sampler reads in a gbuffer program: GL texture unit 0 (the block atlas for Sodium, Sampler0 for vanilla). */
	private Source unitZero() {
		if (resolvingVanilla == null) return new Atlas();
		return resolvingVanilla.hasTex() ? new PassSampler("Sampler0") : white();
	}

	private Source resolve(String pass, ShaderResourceLayout.Descriptor d, TextureStage stage, Set<Integer> used, Set<Integer> flippedOnce) {
		String n = d.name();
		if (resolvingVanilla != null) {
			Source s = resolveVanilla(pass, d);
			if (s != null) return s;
		}
		if (d.kind() == ShaderResourceLayout.Kind.UNIFORM_BUFFER) {
			if (n.equals(ShaderResourceLayout.UNIFORM_BLOCK)) return new UniformBlock();
			if (resolvingGbuffer && n.equals("iris_ChunkData")) return new ChunkData();
			throw new IllegalStateException(pass + ": uniform block " + n + " (not implemented yet)");
		}
		if (resolvingGbuffer && d.kind() == ShaderResourceLayout.Kind.SAMPLER) {
			// Iris addGbufferOrShadowSamplers (terrain): the atlas and lightmap are Polydium's own textures
			switch (n) {
				case "tex", "texture", "gtexture" -> {
					return new Atlas();
				}
				case "lightmap" -> {
					return new Lightmap();
				}
				case "normals" -> {
					// Iris PBRTextureManager default when the resource pack has no _n maps: (127, 127, 255, 255)
					notes.add(pass + ": normals = Iris's flat default normal (PBR atlases not loaded on Vulkan yet)");
					return solid("default normals", 127 / 255f, 127 / 255f, 1f, 1f);
				}
				case "specular" -> {
					notes.add(pass + ": specular = Iris's default (0) (PBR atlases not loaded on Vulkan yet)");
					return solid("default specular", 0f, 0f, 0f, 0f);
				}
				case "depthtex0", "depthtex1", "depthtex2", "gdepthtex" -> {
					notes.add(pass + ": " + n + " = depth before translucent terrain (GL would sample the depth buffer being drawn, a feedback loop)");
					return new DepthNoTranslucent();
				}
				default -> {
				}
			}
		}
		if (d.kind() == ShaderResourceLayout.Kind.STORAGE_BUFFER) return ssbo(pass, d);
		if (d.kind() == ShaderResourceLayout.Kind.IMAGE) {
			if (n.startsWith("colorimg")) {
				int i = Integer.parseInt(n.substring("colorimg".length()));
				used.add(i);
				return new TargetImage(i);
			}
			CustomImage ci = customImage(pass, n, false);
			if (ci != null) return ci;
			throw new IllegalStateException(pass + ": image " + n + " is neither colorimgN nor a pack image (shadowcolorimg needs the shadow pass, not implemented yet)");
		}
		// Iris CustomTextureSamplerInterceptor: this stage's custom textures win over built-in names, except a colortex
		// (or its legacy alias) that an earlier pass of the stage already wrote
		int target = targetIndex(n);
		if (resolvingGbuffer && target >= 0 && target < 4 && d.kind() == ShaderResourceLayout.Kind.SAMPLER) {
			// Iris IrisSamplers.addRenderTargetSamplers: colortex0-3 can only be sampled in fullscreen passes, so in a gbuffer
			// program the name stays unassigned and reads texture unit 0, the block atlas (unless a custom texture claims it)
			var gbufferTextures = set.getPack().getCustomTextureDataMap().get(stage);
			if (gbufferTextures == null || aliases(target).stream().noneMatch(gbufferTextures::containsKey)) {
				notes.add(pass + ": " + n + " is not sampleable in gbuffers on Iris (colortex0-3); reads GL texture unit 0 (albedo)");
				return unitZero();
			}
		}
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
				if (withGbuffers) return n.equals("depthtex2") ? new DepthNoHand() : new DepthNoTranslucent();
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
			if (shadowPass) {
				Source s = shadowSource(pass, n, d);
				if (s != null) return s;
			}
			notes.add(n + " bound to a 1x1 'no shadow' stand-in (" + (shadowPass ? "not implemented yet" : "the pack has no shadow pass on Vulkan") + ")");
			return dummy(d.type(), 1f); // depth 1.0 = nothing occludes; colour 1.0 = untinted
		}
		CustomImage sampledImage = customImage(pass, n, true);
		if (sampledImage != null) return sampledImage;
		CustomTextureData custom = set.getPack().getIrisCustomTextureDataMap().get(n);
		if (custom != null) return customTexture(pass, n, custom, d.type());
		if (resolvingGbuffer && d.type().equalsIgnoreCase("sampler2D")) {
			// GL: an unassigned sampler reads texture unit 0, which Iris's Sodium path binds to the block atlas
			notes.add(pass + ": sampler " + n + " is unassigned in Iris; reads GL texture unit 0 (albedo)");
			return unitZero();
		}
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
		return ssbo(pass, index, "buffer block " + d.name());
	}

	private Ssbo ssbo(String pass, int index, String what) {
		return ssbos.computeIfAbsent(index, i -> {
			var info = set.getPack().getBufferObjects().get((int) i);
			if (info == null) throw new IllegalStateException(pass + ": " + what + " uses buffer " + i + " but the pack defines no bufferObject." + i);
			long size = info.size();
			if (info.relative()) {
				RenderTarget main = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
				size = (long) Math.ceil(main.width * info.scaleX()) * (long) Math.ceil(main.height * info.scaleY()) * info.size();
				notes.add("bufferObject." + i + " is screen-relative; sized for " + main.width + "x" + main.height + " and not resized yet");
			}
			GpuBuffer buf = alloc.createBuffer("bufferObject." + i, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, false);
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

	private static final int SHADER_STAGES = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

	/**
	 * Iris's glMemoryBarrier(SHADER_IMAGE_ACCESS | TEXTURE_FETCH | SHADER_STORAGE) around compute, for buffer memory:
	 * earlier shader and transfer writes (storage buffers, their initial fill/copy) become visible to the next shaders and
	 * indirect dispatches. Image hazards and layouts are the LayoutTracker's job.
	 */
	private void memoryBarrier() {
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s);
			b.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
			vkCmdPipelineBarrier(cmd, SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, SHADER_STAGES | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, b, null, null);
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

	/** A 1x1 RGBA8 texture of one colour, nearest/clamp. */
	private Source solid(String name, float r, float g, float b, float a) {
		return fixedCache.computeIfAbsent("solid:" + name, k -> new Fixed(upload(name, 1, 1,
			new byte[]{(byte) Math.round(r * 255), (byte) Math.round(g * 255), (byte) Math.round(b * 255), (byte) Math.round(a * 255)}), SamplerCache.NEAREST_CLAMP));
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
		if (!slotsFree()) return false;
		beginLevel(cmd, tracker, mainColor, mainDepth, null);
		beforeTranslucent(cmd, tracker);
		endLevel(cmd, tracker);
		return true;
	}

	private boolean slotsFree() {
		for (List<Pass> list : passes.values()) for (Pass p : list) if (!slotFree(p)) return false;
		if (finalPass != null && !slotFree(finalPass)) return false;
		for (Compute c : setup) if (!slotFree(c.slotSubmit)) return false;
		for (Gbuffer g : terrain) if (g != null && !slotFree(g.slotSubmit)) return false;
		for (Vanilla v : vanilla.values()) if (!slotFree(v.slotSubmit)) return false;
		return true;
	}

	/** With gbuffers a frame can't be skipped halfway: wait (rarely) for the uniform slot's earlier submission. */
	private void awaitSlots() {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (!slotsFree()) {
			if (System.nanoTime() > deadline) throw new IllegalStateException("uniform slot still in flight after 5 s");
			Thread.onSpinWait();
		}
	}

	private GpuImage mainColor, mainDepth, mainDepthAttachment, depthNoTranslucent, depthNoHand;
	private boolean levelBegun, translucentDone, handCopied;
	private TerrainInputs terrainInputs;
	private static final SamplerCache.Key ATLAS_SAMPLER = new SamplerCache.Key(SamplerCache.Filter.NEAREST, SamplerCache.Address.REPEAT, true, false);

	boolean levelBegun() {
		return levelBegun;
	}

	boolean translucentDone() {
		return translucentDone;
	}

	/**
	 * Start of the world frame: uploads, uniforms, clears, setup, begin and prepare. {@code depthAttachment}: a view of
	 * the main depth that covers all its aspects, for drawing terrain (null without gbuffers). Each of these calls records
	 * into the frame command buffer between Polydium's passes; the caller puts Polydium's full barrier around each.
	 */
	void beginLevel(VkCommandBuffer cmd, LayoutTracker tracker, GpuImage mainColor, GpuImage mainDepth, GpuImage depthAttachment) {
		if (levelBegun) throw new IllegalStateException("beginLevel twice in one frame");
		if (hasTerrain()) awaitSlots();
		this.mainColor = mainColor;
		this.mainDepth = mainDepth;
		this.mainDepthAttachment = depthAttachment;
		levelBegun = true;
		translucentDone = false;
		handCopied = false;
		segment(cmd, tracker);
		targets.resize(mainColor.width(), mainColor.height());
		if (withGbuffers) ensureDepthCopy();
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
		clearCustomImages();
		if (!setupDone) {
			// Iris: setup programs run once, dispatched as dispatch(1, 1)
			if (!setup.isEmpty()) {
				for (Compute c : setup) dispatch(c, 1, 1);
				memoryBarrier();
			}
			setupDone = true;
		}
		clearTargets();

		// begin here; prepare runs after the shadow pass (Iris: renderShadows, then prepareRenderer), see prepare()
		prepareDone = false;
		for (var e : passes.entrySet()) {
			if (e.getKey().stage() == TextureStage.BEGIN) runStage(e.getKey(), e.getValue());
		}
		endSegment();
	}

	private void runStage(Stage st, List<Pass> list) {
		directives.getExplicitFlips(st.preFlips()).forEach((b, flip) -> {
			if (flip && targets.has(b)) targets.flip(b);
		});
		for (Pass p : list) run(p, mainColor, mainDepth);
	}

	/**
	 * After opaque terrain, before translucent terrain (Iris runs deferred there): depth copy for depthtex1, centre depth,
	 * then the deferred stage. Without terrain programs the vanilla frame is first copied into colortex0.
	 */
	void beforeTranslucent(VkCommandBuffer cmd, LayoutTracker tracker) {
		if (!levelBegun || translucentDone) throw new IllegalStateException("beforeTranslucent out of order");
		segment(cmd, tracker);
		ensurePrepared();
		translucentDone = true;
		if (!hasTerrain()) copyScene(mainColor);
		if (depthNoTranslucent != null) copyDepth(mainDepth, depthNoTranslucent);
		if (centerDepthProgram != null) sampleCenterDepth(mainDepth);
		for (var e : passes.entrySet()) {
			if (e.getKey().stage() == TextureStage.DEFERRED) runStage(e.getKey(), e.getValue());
		}
		endSegment();
	}

	/** Iris beginHand: depthtex2 = the depth of everything opaque except the hand, copied just before the hand pass. */
	void beforeHand(VkCommandBuffer cmd, LayoutTracker tracker) {
		if (!levelBegun) throw new IllegalStateException("beforeHand outside the level");
		if (depthNoHand == null) return;
		segment(cmd, tracker);
		copyDepth(mainDepth, depthNoHand);
		handCopied = true;
		endSegment();
	}

	/** After the world: composite, final (into the main colour target) and Iris's copy-back. */
	void endLevel(VkCommandBuffer cmd, LayoutTracker tracker) {
		if (!levelBegun) throw new IllegalStateException("endLevel without beginLevel");
		if (!translucentDone) beforeTranslucent(cmd, tracker);
		segment(cmd, tracker);
		ensurePrepared();
		for (var e : passes.entrySet()) {
			if (e.getKey().stage() == TextureStage.COMPOSITE_AND_FINAL) runStage(e.getKey(), e.getValue());
		}
		if (finalPass != null) {
			run(finalPass, mainColor, mainDepth);
		} else {
			blitToScreen(targets.read(0), mainColor);
		}
		copyBack();
		frame++;
		levelBegun = false;
		endSegment();
	}

	private void segment(VkCommandBuffer cmd, LayoutTracker tracker) {
		this.cmd = cmd;
		this.tracker = tracker;
		this.currentDepth = mainDepth;
	}

	/** Polydium owns the external images between segments: forget what the tracker knew about them. */
	private void endSegment() {
		LayoutTracker.releaseExternal(mainColor);
		LayoutTracker.releaseExternal(mainDepth);
		if (mainDepthAttachment != null) LayoutTracker.releaseExternal(mainDepthAttachment);
		this.cmd = null;
		this.tracker = null;
	}

	/** depthtex1: the depth buffer before translucent terrain (Iris copies it the same way, after opaque terrain). */
	private void ensureDepthCopy() {
		if (depthNoTranslucent != null && depthNoTranslucent.width() == mainDepth.width() && depthNoTranslucent.height() == mainDepth.height()) return;
		if (depthNoTranslucent != null) {
			GpuImage old = depthNoTranslucent;
			ownedImages.remove(old);
			destroyer.defer("depthtex1 copy", () -> alloc.destroy(old));
		}
		depthNoTranslucent = alloc.createImage("depthtex1 (no translucents)", mainDepth.format(), mainDepth.width(), mainDepth.height(),
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
		ownedImages.add(depthNoTranslucent);
		clearDepth(depthNoTranslucent, 1f);
		if (depthNoHand != null) {
			GpuImage old = depthNoHand;
			ownedImages.remove(old);
			destroyer.defer("depthtex2 copy", () -> alloc.destroy(old));
		}
		depthNoHand = alloc.createImage("depthtex2 (no hand)", mainDepth.format(), mainDepth.width(), mainDepth.height(),
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
		ownedImages.add(depthNoHand);
		clearDepth(depthNoHand, 1f);
	}

	private void copyDepth(GpuImage src, GpuImage dst) {
		tracker.use(src, LayoutTracker.Use.TRANSFER_SRC).use(dst, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
			r.get(0).srcSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1))
				.dstSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1))
				.extent(e -> e.set(src.width(), src.height(), 1));
			vkCmdCopyImage(cmd, src.image(), src.layout(), dst.image(), dst.layout(), r);
		}
	}

	private final ByteBuffer push = MemoryUtil.memAlloc(128);
	private final Map<GpuImage, GpuImage> feedbackCopies = new IdentityHashMap<>();

	/** A same-size, same-format copy target for a colortex image a gbuffer program samples while drawing into it. */
	private GpuImage feedbackCopy(GpuImage src) {
		GpuImage copy = feedbackCopies.get(src);
		if (copy != null && copy.width() == src.width() && copy.height() == src.height()) return copy;
		if (copy != null) {
			GpuImage old = copy;
			ownedImages.remove(old);
			destroyer.defer("feedback copy", () -> alloc.destroy(old));
		}
		copy = alloc.createImage(src.name() + " (pre-pass copy)", src.format(), src.width(), src.height(),
			VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
		ownedImages.add(copy);
		feedbackCopies.put(src, copy);
		return copy;
	}

	private void copyColor(GpuImage src, GpuImage dst) {
		tracker.use(src, LayoutTracker.Use.TRANSFER_SRC).use(dst, LayoutTracker.Use.TRANSFER_DST, true).flush(cmd);
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
			r.get(0).srcSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
				.dstSubresource(x -> x.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
				.extent(e -> e.set(src.width(), src.height(), 1));
			vkCmdCopyImage(cmd, src.image(), src.layout(), dst.image(), dst.layout(), r);
		}
	}
	private long staleRegions;

	/** Region draws skipped because the region was still meshed in another vertex format. */
	long staleRegions() {
		return staleRegions;
	}

	/**
	 * One Sodium terrain pass with the pack's program (Iris SodiumShader + Sodium's DefaultChunkRenderer draw loop):
	 * one descriptor set per region (its chunk data differs), region offset and time as push constants, then the
	 * region's batch as indexed draws. Writes go to the targets' current side, as Iris's gbuffer framebuffers do.
	 */
	void drawTerrain(VkCommandBuffer cmd, LayoutTracker tracker, int kind, List<RegionDraw> draws, TerrainInputs inputs) {
		if (!levelBegun) throw new IllegalStateException("terrain drawn before beginLevel");
		segment(cmd, tracker);
		ensurePrepared();
		terrainInputs = inputs;
		atlasWidth = inputs.atlas().width();
		atlasHeight = inputs.atlas().height();
		Gbuffer g = terrain[kind];
		GpuImage[] outs = new GpuImage[g.drawBuffers.length];
		for (int i = 0; i < outs.length; i++) outs[i] = targets.read(g.drawBuffers[i]);
		drawRegions(g, outs, mainDepthAttachment, draws);
		endSegment();
	}

	/**
	 * Draws one Sodium pass's region batches with {@code g} into {@code outs} + {@code depthAttachment}: one descriptor
	 * set per region (its chunk data differs), region offset and time as push constants, then the batch as indexed draws.
	 */
	private void drawRegions(Gbuffer g, GpuImage[] outs, GpuImage depthAttachment, List<RegionDraw> draws) {
		Resolved r = new Resolved(g.bounds, mainDepth);
		for (GpuImage o : outs) {
			if (r.storage.contains(o)) throw new IllegalStateException(g.name + " uses " + o.name() + " as a storage image and renders to it (not implemented)");
			if (r.sampled.contains(o)) {
				// GL feedback loop (the program samples the target it draws into). In practice GL returns what the buffer
				// held before the draw, except for texels the same draw already wrote; Vulkan forbids it outright, so the
				// program samples a copy taken just before the pass instead
				GpuImage copy = feedbackCopy(o);
				copyColor(o, copy);
				r.replace(o, copy);
				notes.add(g.name + ": samples " + o.name() + " while drawing into it (GL feedback loop); reads a copy taken before the pass");
			}
		}
		if (r.sampled.contains(depthAttachment)) {
			throw new IllegalStateException(g.name + " samples " + depthAttachment.name() + " while drawing into it (not implemented yet)");
		}
		r.declare(LayoutTracker.Use.SAMPLED_GRAPHICS, LayoutTracker.Use.STORAGE_FRAGMENT);
		for (GpuImage o : outs) tracker.use(o, LayoutTracker.Use.COLOR_ATTACHMENT);
		tracker.use(depthAttachment, LayoutTracker.Use.DEPTH_ATTACHMENT);
		tracker.flush(cmd);
		if (g.usesSsbo) memoryBarrier();

		int slot = (int) (frame % SLOTS);
		g.slotSubmit[slot] = clock.currentIndex();
		CapturedRenderingState.INSTANCE.setCurrentAlphaTest(g.alphaTest);
		boolean fill = true;
		GraphicsProgram.beginRendering(cmd, outs, depthAttachment);
		try (MemoryStack s = MemoryStack.stackPush()) {
			int stride = g.program.spec().vertexStride();
			for (RegionDraw d : draws) {
				// a region meshed before the switch to the pack's format (chunks rebuild over a few frames): its vertices
				// have another stride and drawing them would read past its buffer, so it is skipped until rebuilt
				if (d.stride() != stride) {
					staleRegions++;
					continue;
				}
				long ds = ring.allocate(g.program.setLayout());
				r.write(ds, g.bounds, g.layout, g.ubo, g.stride, slot, gbufferWriter, fill, d.chunkData());
				fill = false;
				g.program.bind(cmd, ds);
				if (g.pushSize > 0) {
					for (int i = 0; i < g.pushSize; i++) push.put(i, (byte) 0);
					if (g.regionOffset >= 0) push.putFloat(g.regionOffset, d.offsetX()).putFloat(g.regionOffset + 4, d.offsetY()).putFloat(g.regionOffset + 8, d.offsetZ());
					if (g.currentTime >= 0) push.putInt(g.currentTime, d.time());
					g.program.pushConstants(cmd, push.slice(0, g.pushSize));
				}
				vkCmdBindVertexBuffers(cmd, 0, s.longs(d.vertexBuffer()), s.longs(0));
				vkCmdBindIndexBuffer(cmd, d.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
				for (int i = 0; i < d.count().length; i++) vkCmdDrawIndexed(cmd, d.count()[i], 1, d.firstIndex()[i], d.baseVertex()[i], 0);
			}
		}
		GraphicsProgram.endRendering(cmd);
	}

	// ------------------------------------------------------------------ 7d: shadow pass (Iris ShadowRenderer / ShadowRenderTargets)

	/** True when the shadow terrain programs were built: the caller must then drive {@link #beginShadow} / {@link #endShadow}. */
	boolean hasShadowPass() {
		return shadowPass && shadowTerrain[0] != null;
	}

	int shadowResolution() {
		return shadowResolution;
	}

	PackDirectives directives() {
		return directives;
	}

	/**
	 * Iris ShadowRenderer's voxelization guess: the shadow program has a geometry shader, or the shadow cutout program
	 * uses images (IrisRenderingPipeline: ExtendedShader.hasActiveImages for SHADOW_TERRAIN_CUTOUT).
	 */
	boolean shadowVoxelization() {
		if (!hasShadowPass()) return false;
		boolean geometry = shadowTerrain[0].program.spec().stages().contains(GraphicsProgram.Stage.GEOMETRY);
		boolean images = shadowTerrain[1].layout.descriptors().stream().anyMatch(x -> x.kind() == ShaderResourceLayout.Kind.IMAGE);
		return geometry || images;
	}

	long shadowFrames() {
		return shadowFrames;
	}

	/** shadowtex0 (0) and its pre-translucent copy shadowtex1 (1): DEPTH32 at the pack's shadowMapResolution. */
	private GpuImage shadowDepthImage(int which) {
		if (shadowDepth == null) {
			shadowDepth = alloc.createImage("shadowtex0", VK_FORMAT_D32_SFLOAT, shadowResolution, shadowResolution,
				VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				VK_IMAGE_ASPECT_DEPTH_BIT);
			shadowDepthNoTranslucent = alloc.createImage("shadowtex1", VK_FORMAT_D32_SFLOAT, shadowResolution, shadowResolution,
				VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
			ownedImages.add(shadowDepth);
			ownedImages.add(shadowDepthNoTranslucent);
			// Iris ShadowRenderer.configureDepthSampler: shadow maps read (depth, depth, depth, 1) (old packs read .z)
			alloc.addDepthSwizzleView(shadowDepth);
			alloc.addDepthSwizzleView(shadowDepthNoTranslucent);
			GpuImage a = shadowDepth, b = shadowDepthNoTranslucent;
			uploads.add(() -> {
				clearDepth(a, 1f);
				clearDepth(b, 1f);
			});
		}
		return which == 0 ? shadowDepth : shadowDepthNoTranslucent;
	}

	/** Iris ShadowRenderTargets.create: shadowcolorN in the pack's format, created when a program draws to or samples it. */
	private GpuImage shadowColorImage(int index) {
		if (index < 0 || index >= shadowColor.length) throw new IllegalStateException("shadowcolor" + index + " is past Iris's limit of " + shadowColor.length);
		if (shadowColor[index] == null) {
			PackShadowDirectives.SamplingSettings st = shadowDirectives.getColorSamplingSettings().computeIfAbsent(index, i -> new PackShadowDirectives.SamplingSettings());
			int fmt = RenderTargetSet.renderableFormat(ctx, VkFormats.of(st.getFormat()));
			GpuImage img = alloc.createImage("shadowcolor" + index, fmt, shadowResolution, shadowResolution,
				VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				VK_IMAGE_ASPECT_COLOR_BIT);
			ownedImages.add(img);
			shadowColor[index] = img;
			boolean integer = VkFormats.isInteger(st.getFormat());
			// Iris: a new shadow target needs a full clear, whatever its clear setting
			uploads.add(() -> clearColor(img, st.getClearColor(), integer));
			if (st.getMipmap()) notes.add("shadowcolor" + index + ": mipmaps requested; sampled without mipmaps (not implemented yet)");
		}
		return shadowColor[index];
	}

	/** Iris IrisSamplers.addShadowSamplers / ShadowRenderTargets.getSamplerFor, for the samplers Vulkan Iris draws. */
	private Source shadowSource(String pass, String n, ShaderResourceLayout.Descriptor d) {
		String type = d.type().toLowerCase(Locale.ROOT);
		int depth = switch (n) {
			case "shadowtex0", "watershadow" -> 0;
			case "shadowtex1" -> 1;
			// with watershadow declared, "shadow" is the opaque-only map; otherwise it is shadowtex0
			case "shadow" -> bindingWaterShadow ? 1 : 0;
			default -> -1;
		};
		if (depth >= 0) {
			PackShadowDirectives.DepthSamplingSettings ds = shadowDirectives.getDepthSamplingSettings().get(depth);
			boolean compare = type.contains("shadow");
			if (compare != ds.getHardwareFiltering()) {
				// GL: compare mode comes from shadowHardwareFiltering, the result from the GLSL sampler type; a mismatch is
				// undefined on GL. Vulkan needs the sampler to match the type, so the type decides
				notes.add(pass + ": " + n + " is a " + d.type() + " but shadowHardwareFiltering" + depth + " = " + ds.getHardwareFiltering()
					+ " (undefined on GL); sampled " + (compare ? "with" : "without") + " depth compare");
			}
			if (ds.getMipmap()) notes.add(n + ": mipmaps requested; sampled without mipmaps (not implemented yet)");
			SamplerCache.Filter filter = ds.getNearest() ? SamplerCache.Filter.NEAREST : SamplerCache.Filter.LINEAR;
			return new Fixed(shadowDepthImage(depth), new SamplerCache.Key(filter, SamplerCache.Address.CLAMP, false, compare));
		}
		int color = -1;
		if (n.equals("shadowcolor")) color = 0;
		else if (n.matches("shadowcolor\\d+")) color = Integer.parseInt(n.substring("shadowcolor".length()));
		if (color >= 0) {
			PackShadowDirectives.SamplingSettings st = shadowDirectives.getColorSamplingSettings().computeIfAbsent(color, i -> new PackShadowDirectives.SamplingSettings());
			// Iris binds GlSampler.LINEAR (clamp); Vulkan can't filter integer formats linearly
			return new Fixed(shadowColorImage(color), VkFormats.isInteger(st.getFormat()) ? SamplerCache.NEAREST_CLAMP : SamplerCache.LINEAR_CLAMP);
		}
		return null; // shadowtex0HW / shadowtex1HW / DH / shadowcolorimg: reported by the caller
	}

	/**
	 * Iris ShadowRenderer.renderShadows, GPU side, start: the shadow view for this frame and IrisRenderingPipeline's
	 * per-frame shadow clears (depth always, shadowcolors that clear).
	 */
	void beginShadow(VkCommandBuffer cmd, LayoutTracker tracker, Matrix4f modelView, Matrix4f projection) {
		if (!levelBegun) throw new IllegalStateException("shadow pass before beginLevel");
		if (!hasShadowPass()) throw new IllegalStateException("beginShadow without a shadow pass");
		segment(cmd, tracker);
		shadowModelView.set(modelView);
		shadowProjection.set(projection);
		clearDepth(shadowDepthImage(0), 1f);
		for (int i = 0; i < shadowColor.length; i++) {
			if (shadowColor[i] == null) continue;
			PackShadowDirectives.SamplingSettings st = shadowDirectives.getColorSamplingSettings().get(i);
			if (st != null && !st.getClear()) continue;
			Vector4f c = st == null ? new Vector4f(1f) : st.getClearColor();
			clearColor(shadowColor[i], c, st != null && VkFormats.isInteger(st.getFormat()));
		}
		shadowBegun = true;
		shadowTranslucentCopied = false;
		endSegment();
	}

	/** One Sodium terrain pass drawn from the shadow view with the pack's shadow program (Iris SodiumPrograms SHADOW*). */
	void drawShadowTerrain(VkCommandBuffer cmd, LayoutTracker tracker, int kind, List<RegionDraw> draws, TerrainInputs inputs) {
		if (!shadowBegun) throw new IllegalStateException("shadow terrain outside beginShadow / endShadow");
		segment(cmd, tracker);
		terrainInputs = inputs;
		atlasWidth = inputs.atlas().width();
		atlasHeight = inputs.atlas().height();
		// Iris ShadowRenderer: shadowtex1 = the shadow depth before translucent terrain
		if (kind == TerrainHook.TRANSLUCENT && !shadowTranslucentCopied) {
			copyDepth(shadowDepth, shadowDepthNoTranslucent);
			shadowTranslucentCopied = true;
		}
		Gbuffer g = shadowTerrain[kind];
		GpuImage[] outs = new GpuImage[g.drawBuffers.length];
		for (int i = 0; i < outs.length; i++) outs[i] = shadowColorImage(g.drawBuffers[i]);
		shadowing = true;
		try {
			drawRegions(g, outs, shadowDepth, draws);
		} finally {
			shadowing = false;
		}
		endSegment();
	}

	/** Self-test: copies shadowtex0 (depth aspect) into {@code rb}, in the shadow pass, so the CPU can see what was drawn. */
	void probeShadowDepth(VkCommandBuffer cmd, LayoutTracker tracker, dev.vulkaniris.vk.ImageReadback rb) {
		if (!shadowBegun) throw new IllegalStateException("shadow depth probe outside the shadow pass");
		segment(cmd, tracker);
		tracker.use(shadowDepth, LayoutTracker.Use.TRANSFER_SRC).flush(cmd);
		rb.record(cmd, shadowDepth.image(), shadowDepth.layout(), 0, 0, VK_IMAGE_ASPECT_DEPTH_BIT);
		endSegment();
	}

	/** End of Iris ShadowRenderer.renderShadows: the pre-translucent copy if translucent terrain wasn't drawn, then shadowcomp. */
	void endShadow(VkCommandBuffer cmd, LayoutTracker tracker) {
		if (!shadowBegun) throw new IllegalStateException("endShadow without beginShadow");
		segment(cmd, tracker);
		if (!shadowTranslucentCopied) copyDepth(shadowDepth, shadowDepthNoTranslucent);
		shadowTranslucentCopied = true;
		if (!shadowComposite.isEmpty()) {
			// Iris ShadowCompositeRenderer: computes dispatched against the shadow map's size
			for (Compute c : shadowComposite) dispatch(c, shadowResolution, shadowResolution);
			memoryBarrier();
		}
		shadowBegun = false;
		shadowFrames++;
		endSegment();
	}

	/** Iris IrisRenderingPipeline.renderShadows ends with the prepare stage (after the shadow pass, if any). */
	void prepare(VkCommandBuffer cmd, LayoutTracker tracker) {
		if (!levelBegun) throw new IllegalStateException("prepare before beginLevel");
		if (prepareDone) return;
		segment(cmd, tracker);
		ensurePrepared();
		endSegment();
	}

	boolean prepareDone() {
		return prepareDone;
	}

	/** Inside a segment: runs the prepare stage if the frame hasn't yet (a fallback if the shadow hook didn't run). */
	private void ensurePrepared() {
		if (prepareDone) return;
		prepareDone = true;
		for (var e : passes.entrySet()) {
			if (e.getKey().stage() == TextureStage.PREPARE) runStage(e.getKey(), e.getValue());
		}
	}

	private boolean slotFree(Pass p) {
		if (p.program != null && !slotFree(p.slotSubmit)) return false;
		for (Compute c : p.computes) if (!slotFree(c.slotSubmit)) return false;
		return true;
	}

	private boolean slotFree(long[] slotSubmit) {
		long s = slotSubmit[(int) (frame % SLOTS)];
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

	/** This frame's images for a program's bindings (flips change which image a colortex name means). */
	private final class Resolved {
		final GpuImage[] images;
		final long[] sampler;
		final Set<GpuImage> sampled = Collections.newSetFromMap(new IdentityHashMap<>());
		final Set<GpuImage> storage = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean usesBuffers;

		Resolved(List<Bound> bounds, GpuImage mainDepth) {
			images = new GpuImage[bounds.size()];
			sampler = new long[bounds.size()];
			for (int i = 0; i < images.length; i++) {
				switch (bounds.get(i).source()) {
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
					case Ssbo b -> usesBuffers = true;
					case CenterDepth c -> {
						images[i] = centerDepth[centerDepthLatest];
						sampler[i] = samplers.get(SamplerCache.LINEAR_CLAMP);
						sampled.add(images[i]);
					}
					case CustomImage ci -> {
						images[i] = ci.image();
						// GlImage: NEAREST for integer formats, LINEAR otherwise, clamp to edge
						sampler[i] = samplers.get(ci.integer() ? SamplerCache.NEAREST_CLAMP : SamplerCache.LINEAR_CLAMP);
						(isStorageBinding(bounds, i) ? storage : sampled).add(images[i]);
					}
					// Polydium's atlas and lightmap stay in GENERAL and were written in earlier Polydium passes, which the
					// full barrier around Vulkan Iris's recording orders; they are not tracked here
					case Atlas a -> {
						images[i] = terrainInputs.atlas();
						sampler[i] = samplers.get(ATLAS_SAMPLER);
					}
					case Lightmap l -> {
						images[i] = terrainInputs.lightmap();
						sampler[i] = samplers.get(SamplerCache.LINEAR_CLAMP);
					}
					case DepthNoTranslucent dn -> {
						images[i] = depthNoTranslucent != null ? depthNoTranslucent : mainDepth;
						sampler[i] = samplers.get(SamplerCache.NEAREST_CLAMP);
						sampled.add(images[i]);
					}
					case DepthNoHand dh -> {
						// no hand pass this frame: the pre-translucent copy holds the same depth
						images[i] = handCopied ? depthNoHand : depthNoTranslucent != null ? depthNoTranslucent : mainDepth;
						sampler[i] = samplers.get(SamplerCache.NEAREST_CLAMP);
						sampled.add(images[i]);
					}
					case ChunkData c -> {
					}
					case PassTexel pt -> {
					}
					case PassBlock pb -> {
					}
					// vanilla's own textures (Polydium keeps them in GENERAL, written in earlier passes behind its full barriers)
					case PassSampler ps -> {
						GpuImage img = passState.texture(ps.name());
						if (img == null) {
							notes.add("a vanilla pass bound no " + ps.name() + "; the program reads Iris's white pixel");
							images[i] = ((Fixed) white()).image();
							sampler[i] = samplers.get(SamplerCache.NEAREST_CLAMP);
						} else {
							images[i] = img;
							sampler[i] = passState.sampler(ps.name());
						}
					}
				}
			}
			// an image that is both sampled and a storage image in one program stays in GENERAL, which serves both
			sampled.removeAll(storage);
		}

		/** Samples {@code copy} wherever this program would sample {@code from}. */
		void replace(GpuImage from, GpuImage copy) {
			for (int i = 0; i < images.length; i++) if (images[i] == from) images[i] = copy;
			sampled.remove(from);
			sampled.add(copy);
		}

		void declare(LayoutTracker.Use sampledUse, LayoutTracker.Use storageUse) {
			for (GpuImage img : sampled) tracker.use(img, sampledUse);
			for (GpuImage img : storage) tracker.use(img, storageUse);
		}

		void write(long ds, List<Bound> bounds, ShaderResourceLayout layout, GpuBuffer ubo, long stride, int slot) {
			write(ds, bounds, layout, ubo, stride, slot, writer, true, 0);
		}

		/** {@code fill}: evaluate the uniforms into the slot (once per program per frame); {@code chunkData}: iris_ChunkData buffer. */
		void write(long ds, List<Bound> bounds, ShaderResourceLayout layout, GpuBuffer ubo, long stride, int slot,
				   VulkanUniformWriter uniforms, boolean fill, long chunkData) {
			try (DescriptorWrites w = new DescriptorWrites(ctx.device(), ds, images.length)) {
				for (int i = 0; i < images.length; i++) {
					Bound b = bounds.get(i);
					switch (b.source()) {
						case UniformBlock u -> {
							if (fill) {
								ByteBuffer dst = MemoryUtil.memByteBuffer(ubo.mapped() + slot * stride, layout.uniformBufferSize());
								uniforms.write(layout, dst);
							}
							w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, ubo.buffer(), slot * stride, layout.uniformBufferSize());
						}
						// Sodium's ChunkData: ivec4 u_chunkFades[64] (std140), the same buffer Iris binds on GL
						case ChunkData c -> w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, chunkData, 0, 64 * 16);
						case PassBlock pb -> {
							long[] blk = passState.block(pb.name());
							if (blk == null) throw new IllegalStateException("the vanilla pass set no " + pb.name() + " uniform block");
							w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, blk[0], blk[1], blk[2]);
						}
						case PassTexel pt -> {
							long[] blk = passState.block(pt.name());
							if (blk == null) throw new IllegalStateException("the vanilla pass set no " + pt.name() + " texel buffer");
							w.texelBuffer(b.binding(), texelView(blk[0], blk[1], blk[2], pt.format()));
						}
						case TargetImage t -> w.storageImage(b.binding(), images[i]);
						case Ssbo sb -> w.buffer(b.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, sb.buffer().buffer(), 0, VK_WHOLE_SIZE);
						case CustomImage ci -> {
							if (isStorageBinding(bounds, i)) w.storageImage(b.binding(), images[i]);
							else w.image(b.binding(), 0, images[i], sampler[i]);
						}
						default -> w.image(b.binding(), 0, images[i], sampler[i]);
					}
				}
			}
			if (ubo != null) alloc.flush(ubo);
		}
	}

	/** Which bindings are storage images, per program: filled at build time from the layout's descriptor kinds. */
	private final Map<List<Bound>, BitSet> storageBindings = new IdentityHashMap<>();

	private boolean isStorageBinding(List<Bound> bounds, int i) {
		BitSet bits = storageBindings.get(bounds);
		return bits != null && bits.get(i);
	}

	private void dispatch(Compute c, float width, float height) {
		Resolved r = new Resolved(c.bounds, currentDepth);
		r.declare(LayoutTracker.Use.SAMPLED_COMPUTE, LayoutTracker.Use.STORAGE_COMPUTE);
		tracker.flush(cmd);
		memoryBarrier(); // Iris ComputeProgram.dispatch: a full memory barrier before every dispatch
		int slot = (int) (frame % SLOTS);
		long ds = ring.allocate(c.program.setLayout());
		r.write(ds, c.bounds, c.layout, c.ubo, c.stride, slot);
		c.slotSubmit[slot] = clock.currentIndex();
		if (c.indirect != null) {
			c.program.dispatchIndirect(cmd, ds, c.indirectBuffer.buffer().buffer(), c.indirect.offset());
		} else {
			Vector3i g = workGroups(c, width, height);
			if (g.x > 0 && g.y > 0 && g.z > 0) c.program.dispatch(cmd, ds, g.x, g.y, g.z);
		}
	}

	private GpuImage currentDepth;

	private void run(Pass p, GpuImage mainColor, GpuImage mainDepth) {
		currentDepth = mainDepth;
		if (!p.computes.isEmpty()) {
			for (Compute c : p.computes) dispatch(c, mainColor.width(), mainColor.height());
			memoryBarrier(); // Iris CompositeRenderer: memoryBarrier after a pass's computes
		}
		if (p.program == null) return;
		GpuImage[] outs;
		if (p.isFinal) {
			outs = new GpuImage[]{mainColor};
		} else {
			outs = new GpuImage[p.drawBuffers.length];
			for (int i = 0; i < outs.length; i++) outs[i] = targets.write(p.drawBuffers[i]);
		}
		Resolved r = new Resolved(p.bounds, mainDepth);
		for (GpuImage o : outs) {
			if (r.sampled.contains(o) || r.storage.contains(o)) throw new IllegalStateException(p.name + " reads and renders to " + o.name());
		}
		r.declare(LayoutTracker.Use.SAMPLED_GRAPHICS, LayoutTracker.Use.STORAGE_FRAGMENT);
		for (GpuImage o : outs) tracker.use(o, LayoutTracker.Use.COLOR_ATTACHMENT);
		tracker.flush(cmd);
		if (p.usesSsbo) memoryBarrier();

		int slot = (int) (frame % SLOTS);
		long ds = ring.allocate(p.program.setLayout());
		r.write(ds, p.bounds, p.layout, p.ubo, p.stride, slot);
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
		for (List<Pass> list : passes.values()) for (Pass p : list) if (p.program != null) p.program.close();
		if (finalPass != null) finalPass.program.close();
		computePrograms.forEach(ComputeProgram::close);
		for (Gbuffer g : terrain) if (g != null && g.program != null) g.program.close();
		for (Vanilla v : vanilla.values()) for (GraphicsProgram[] arr : v.variants.values()) for (GraphicsProgram p : arr) if (p != null) p.close();
		for (long view : texelViews.values()) vkDestroyBufferView(ctx.device(), view, null);
		texelViews.clear();
		MemoryUtil.memFree(push);
		if (sceneCopy != null) sceneCopy.close();
		if (centerDepthProgram != null) centerDepthProgram.close();
		if (targets != null) targets.close();
		ownedImages.forEach(alloc::destroy);
		ownedBuffers.forEach(alloc::destroy);
		staging.forEach(alloc::destroy);
	}
}
