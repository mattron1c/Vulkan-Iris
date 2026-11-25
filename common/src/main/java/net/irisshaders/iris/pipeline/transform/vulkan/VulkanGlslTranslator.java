package net.irisshaders.iris.pipeline.transform.vulkan;

import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.initializer.NestedInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.pipeline.transform.Glsl;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout.Descriptor;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout.Kind;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout.Uniform;
import net.irisshaders.iris.pipeline.transform.Std140Type;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Vulkan Iris, Phase 5: turns the GLSL Iris already produced for OpenGL (TransformPatcher output, core profile,
 * built with {@code IrisLimits.VK_CONFORMANCE} so every varying and fragment output has an explicit location) into
 * Vulkan GLSL that shaderc compiles to SPIR-V.
 *
 * <ul>
 *   <li>Loose uniforms → one std140 block {@code iris_Uniforms} (set 0, binding 0), members laid out by
 *       {@link Std140Type}; references become {@code iris_uniformData.name}.</li>
 *   <li>Samplers, images, uniform/storage blocks → set 0, bindings 1.. (one binding per name, shared by all stages).</li>
 *   <li>Uniforms with an initialiser that Iris never sets → {@code const} (GL keeps the initialiser; a UBO can't).</li>
 *   <li>{@code #version 450 core}, {@code gl_VertexID/gl_InstanceID} → Vulkan names, null-initialised globals,
 *       no {@code const} around {@code transpose()}.</li>
 *   <li>Clip space: GL's z ∈ [−w, w] is remapped to Vulkan's [0, w] in the last pre-raster stage, giving the same
 *       depth-buffer values GL writes with the default depth range. Y needs no flip: Polydium stores targets in GL row
 *       order, so Vulkan's NDC y = −1 lands on GL's bottom row.</li>
 * </ul>
 *
 * Adapted from upstream Iris's {@code VulkanResourceTransformer}/{@code VulkanShaderPatcher} (branch 26.3-new,
 * LGPL-3.0-only, credit: the Iris team). The upstream descriptor-heap and push-constant planning is not ported; this
 * uses plain per-program bindings.
 */
public final class VulkanGlslTranslator {
	public static final int SET = 0;
	public static final int UNIFORM_BINDING = 0;

	/** {@code attributes}: vertex input name → location, for building the pipeline's vertex input state. */
	public record Result(Map<PatchShaderType, String> sources, ShaderResourceLayout layout, Map<String, Integer> attributes,
						 Map<String, ShaderResourceLayout.Uniform> pushConstants, int pushConstantSize) {
	}

	/** Blaze3D / Iris attribute names first, in a fixed order, so the same vertex format maps to the same locations. */
	private static final List<String> KNOWN_ATTRIBUTES = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal",
		"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock", "iris_Entity");

	private static final SingleASTTransformer<JobParameters> parser = new SingleASTTransformer<>();

	private VulkanGlslTranslator() {
	}

	/** {@code registered}: uniform names Iris will supply values for (others with initialisers become const). */
	public static Result translate(Map<PatchShaderType, String> sources, Predicate<String> registered) {
		return translate(sources, registered, List.of());
	}

	/**
	 * {@code pushConstants}: loose uniforms that change per draw (e.g. Sodium's {@code u_RegionOffset}). They go into one
	 * {@code layout(push_constant)} block, in the given order with std140 alignment, instead of {@code iris_Uniforms}.
	 */
	public static synchronized Result translate(Map<PatchShaderType, String> sources, Predicate<String> registered, List<String> pushConstants) {
		Map<PatchShaderType, TranslationUnit> trees = new EnumMap<>(PatchShaderType.class);
		sources.forEach((stage, src) -> {
			if (src != null) trees.put(stage, parser.parseSeparateTranslationUnit(src));
		});

		// GL tolerates a user identifier called "sampler"; Vulkan GLSL reserves it
		String samplerName = Glsl.uniqueName(trees.values(), "iris_legacySampler");
		for (var tree : trees.values()) {
			for (var id : tree.getRoot().identifierIndex.getStream("sampler").toList()) id.setName(samplerName);
		}

		Map<String, Std140Type> uniformTypes = new TreeMap<>();
		Map<String, Resource> resources = new TreeMap<>();
		Map<TranslationUnit, List<String>> stageUniforms = new LinkedHashMap<>();
		List<DeclarationExternalDeclaration> loose = new ArrayList<>();

		for (var entry : trees.entrySet()) {
			TranslationUnit tree = entry.getValue();
			var root = tree.getRoot();
			root.indexBuildSession(() -> {
				renameBuiltinOverloads(tree);
				constTranspose(tree);
				stripPrecision(tree);
				resolveDefaults(tree, registered);
				for (var external : List.copyOf(tree.getChildren())) {
					if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
					if (declaration.getDeclaration() instanceof TypeAndInitDeclaration variables) {
						TypeQualifier q = variables.getType().getTypeQualifier();
						if (!Glsl.hasStorage(q, StorageType.UNIFORM)) continue;
						var type = variables.getType().getTypeSpecifier();
						if (type instanceof BuiltinFixedTypeSpecifier opaque) {
							Kind kind = switch (opaque.type.kind) {
								case SAMPLER -> Kind.SAMPLER;
								case IMAGE -> Kind.IMAGE;
								default -> throw new IllegalArgumentException("unsupported opaque uniform " + opaque.type);
							};
							for (var member : List.copyOf(variables.getMembers())) {
								// declared but never used: an inactive uniform on GL, which binds nothing for it. Dropping it
								// also avoids false "samples what it renders to" conflicts (e.g. an unused colortex6 in gbuffers)
								if (root.identifierIndex.get(member.getName().getName()).size() <= 1) continue;
								var split = new TypeAndInitDeclaration(variables.getType().cloneInto(root), Stream.of(member.cloneInto(root)));
								tree.getChildren().add(tree.getChildren().indexOf(declaration), new DeclarationExternalDeclaration(split));
								int count = Glsl.dimensions(root, member.getArraySpecifier(), type.getArraySpecifier()).stream().reduce(1, Math::multiplyExact);
								resource(resources, member.getName().getName(), kind, opaque.type.name(), count, split.getType().getTypeQualifier(), entry.getKey());
							}
							declaration.detachAndDelete();
						} else if (type instanceof BuiltinNumericTypeSpecifier numeric) {
							for (var member : variables.getMembers()) {
								String name = member.getName().getName();
								Std140Type t = Glsl.uniformType(numeric, member, root);
								Std140Type prev = uniformTypes.putIfAbsent(name, t);
								if (prev != null && !prev.equals(t)) throw new IllegalArgumentException("uniform " + name + " has different types across stages");
								stageUniforms.computeIfAbsent(tree, k -> new ArrayList<>()).add(name);
							}
							loose.add(declaration);
						} else {
							throw new IllegalArgumentException("struct uniforms are not supported yet: " + ASTPrinter.print(PrintType.COMPACT, declaration));
						}
					} else if (declaration.getDeclaration() instanceof InterfaceBlockDeclaration block) {
						TypeQualifier q = block.getTypeQualifier();
						Kind kind = Glsl.hasStorage(q, StorageType.BUFFER) ? Kind.STORAGE_BUFFER
							: Glsl.hasStorage(q, StorageType.UNIFORM) ? Kind.UNIFORM_BUFFER : null;
						if (kind == null) continue;
						int count = Glsl.dimensions(root, block.getArraySpecifier()).stream().reduce(1, Math::multiplyExact);
						resource(resources, block.getBlockName().getName(), kind, "block", count, q, entry.getKey());
					}
				}
			});
		}

		// std140 layout of the loose uniforms
		Map<String, Uniform> pushed = new LinkedHashMap<>();
		StringBuilder pushBody = new StringBuilder();
		int pushOffset = 0;
		for (String name : pushConstants) {
			Std140Type t = uniformTypes.remove(name);
			if (t == null) continue;
			pushOffset = Std140Type.align(pushOffset, t.alignment());
			pushed.put(name, new Uniform(pushOffset, t));
			pushBody.append(t.declaration(name));
			pushOffset = Math.addExact(pushOffset, t.size());
		}
		Map<String, Uniform> uniforms = new LinkedHashMap<>();
		StringBuilder body = new StringBuilder();
		int offset = 0, alignment = 16;
		for (var e : uniformTypes.entrySet()) {
			offset = Std140Type.align(offset, e.getValue().alignment());
			alignment = Math.max(alignment, e.getValue().alignment());
			uniforms.put(e.getKey(), new Uniform(offset, e.getValue()));
			body.append(e.getValue().declaration(e.getKey()));
			offset = Math.addExact(offset, e.getValue().size());
		}

		List<Descriptor> descriptors = new ArrayList<>();
		if (!uniforms.isEmpty()) {
			if (resources.containsKey(ShaderResourceLayout.UNIFORM_BLOCK)) throw new IllegalArgumentException("reserved block name " + ShaderResourceLayout.UNIFORM_BLOCK);
			descriptors.add(new Descriptor(ShaderResourceLayout.UNIFORM_BLOCK, Kind.UNIFORM_BUFFER, "block", 1, SET, UNIFORM_BINDING, null, trees.keySet()));
		}
		int binding = UNIFORM_BINDING + 1;
		for (Resource r : resources.values()) {
			int b = binding++;
			// the pack's own layout(binding = N): Iris binds buffer blocks (bufferObject.N) and images by it
			Integer original = null;
			for (var q : r.qualifiers) {
				var part = Glsl.layout(q, "binding");
				if (part != null && part.getExpression() != null) {
					int n = Glsl.integer(part.getExpression(), q.getRoot());
					if (original != null && original != n) throw new IllegalArgumentException(r.name + " has different bindings across stages");
					original = n;
				}
			}
			for (var q : r.qualifiers) {
				q.getRoot().indexBuildSession(() -> {
					Glsl.removeLayout(q, "location", "set", "binding");
					Glsl.setLayout(q, "set", SET);
					Glsl.setLayout(q, "binding", b);
				});
			}
			descriptors.add(new Descriptor(r.name, r.kind, r.type, r.count, SET, b, original, r.stages));
		}

		Map<String, Integer> attributes = assignLocations(trees);

		String instance = Glsl.uniqueName(trees.values(), "iris_uniformData");
		String pushInstance = Glsl.uniqueName(trees.values(), "iris_pushData");
		PatchShaderType clipStage = lastPreRasterStage(trees.keySet());
		Map<PatchShaderType, String> out = new EnumMap<>(PatchShaderType.class);
		for (var entry : trees.entrySet()) {
			TranslationUnit tree = entry.getValue();
			var root = tree.getRoot();
			root.indexBuildSession(() -> {
				for (String name : stageUniforms.getOrDefault(tree, List.of())) {
					for (var id : root.identifierIndex.get(name).stream().toList()) {
						if (id.getParent() instanceof ReferenceExpression ref && !Glsl.isShadowed(ref, name)) {
							ref.replaceByAndDelete(parser.parseExpression(root, (pushed.containsKey(name) ? pushInstance : instance) + "." + name));
						}
					}
				}
				for (var d : loose) if (d.getRoot() == root) d.detachAndDelete();
				if (!uniforms.isEmpty()) {
					tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "layout(std140, set = " + SET + ", binding = "
						+ UNIFORM_BINDING + ") uniform " + ShaderResourceLayout.UNIFORM_BLOCK + " {" + body + "} " + instance + ";");
				}
				if (!pushed.isEmpty()) {
					tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "layout(push_constant, std140) uniform IrisPushConstants {"
						+ pushBody + "} " + pushInstance + ";");
				}
				var vs = tree.getVersionStatement();
				if (vs.version.number < Version.GLSL45.number) vs.version = Version.GLSL45;
				vs.profile = Profile.CORE;
				root.rename("gl_VertexID", "gl_VertexIndex");
				// identical to GL's gl_InstanceID because Vulkan Iris always draws with firstInstance = 0
				root.rename("gl_InstanceID", "gl_InstanceIndex");
				if (entry.getKey() == clipStage) remapClipDepth(tree, entry.getKey());
				initializeGlobals(tree);
			});
			String glsl = emulateNvGpuShader5(ASTPrinter.print(PrintType.INDENTED, tree));
			if (entry.getKey() == PatchShaderType.FRAGMENT) glsl = readOnlyFragDepth(glsl);
			out.put(entry.getKey(), glsl);
		}
		return new Result(out, new ShaderResourceLayout(Std140Type.align(offset, alignment), uniforms, descriptors), attributes,
			pushed, Std140Type.align(pushOffset, 4));
	}

	// ------------------------------------------------------------------ extended vertex attributes derived per quad

	/** Per-draw push constants of {@link #deriveQuadAttributes}: (mode 0/3/4, first vertex, stride words, position word). */
	public static final String QUAD_INFO = "iris_QuadInfo";
	/** (UV0 word, normal word or -1, 0, 0). */
	public static final String QUAD_INFO2 = "iris_QuadInfo2";
	/** The draw's own vertex buffer as 32-bit words (a uniform texel buffer view of it). */
	public static final String VERTEX_DATA = "iris_VertexData";

	private static final Set<String> DERIVABLE = Set.of("at_tangent", "mc_midTexCoord");

	/** {@code names}: the vertex inputs that became derived globals (empty: the sources are unchanged). */
	public record Derived(Map<PatchShaderType, String> sources, Set<String> names) {
	}

	/**
	 * Iris fills {@code at_tangent} and {@code mc_midTexCoord} on the CPU for every quad (and triangle) of its extended
	 * vertex formats ({@code MixinBufferBuilder.fillExtendedData}, using FabricMC's {@code NormalHelper}). On Polydium the
	 * vanilla vertex buffers keep vanilla's layout, so the same values are computed here in the vertex shader instead:
	 * the inputs become globals, filled at the start of {@code main} from the draw's own vertices (the quad or triangle
	 * the vertex belongs to), read through {@link #VERTEX_DATA}. With mode 0 (a vertex format or mode Iris doesn't
	 * extend) they read GL's default (0, 0, 0, 1), as before.
	 */
	public static synchronized Derived deriveQuadAttributes(Map<PatchShaderType, String> sources) {
		String vsh = sources.get(PatchShaderType.VERTEX);
		if (vsh == null) return new Derived(sources, Set.of());
		TranslationUnit tree = parser.parseSeparateTranslationUnit(vsh);
		var root = tree.getRoot();
		Map<String, String> found = new LinkedHashMap<>();
		root.indexBuildSession(() -> {
			for (var external : List.copyOf(tree.getChildren())) {
				if (!(external instanceof DeclarationExternalDeclaration d) || !(d.getDeclaration() instanceof TypeAndInitDeclaration v)) continue;
				var q = v.getType().getTypeQualifier();
				if (!Glsl.hasStorage(q, StorageType.IN) && !Glsl.hasStorage(q, StorageType.ATTRIBUTE)) continue;
				if (v.getMembers().size() != 1) continue;
				var member = v.getMembers().get(0);
				String name = member.getName().getName();
				if (!DERIVABLE.contains(name) || root.identifierIndex.get(name).size() <= 1) continue;
				if (member.getArraySpecifier() != null || !(v.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier)) continue;
				found.put(name, ASTPrinter.print(PrintType.COMPACT, v.getType().getTypeSpecifier()).trim());
				d.detachAndDelete();
			}
		});
		if (found.isEmpty()) return new Derived(sources, Set.of());
		StringBuilder init = new StringBuilder("iris_deriveQuad();");
		StringBuilder globals = new StringBuilder();
		for (var e : found.entrySet()) {
			globals.append(e.getValue()).append(' ').append(e.getKey()).append(";\n");
			init.append(e.getKey()).append(" = ").append(e.getValue()).append('(')
				.append(e.getKey().equals("at_tangent") ? "iris_quadTangent" : "iris_quadMidTex").append(");");
		}
		root.indexBuildSession(() -> {
			List<String> nodes = new ArrayList<>();
			for (String n : QUAD_HELPERS.split("@@")) if (!n.isBlank()) nodes.add(n);
			for (String g : globals.toString().split(";")) if (!g.isBlank()) nodes.add(g.strip() + ";");
			tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, nodes.toArray(String[]::new));
			tree.prependMainFunctionBody(parser, init.toString());
		});
		Map<PatchShaderType, String> out = new EnumMap<>(PatchShaderType.class);
		out.putAll(sources);
		out.put(PatchShaderType.VERTEX, ASTPrinter.print(PrintType.INDENTED, tree));
		return new Derived(out, found.keySet());
	}

	/**
	 * GLSL port of Iris MixinBufferBuilder.fillExtendedData and FabricMC NormalHelper (Apache-2.0):
	 * quads: face normal from the diagonals, tangent from vertices 0-1-2 against it; triangles: per-vertex normal,
	 * positions projected onto its plane (computeTangentSmooth); both: the mean UV, and the tangent stored like
	 * NormI8.pack (truncated to 1/127 steps, read back as normalized bytes).
	 */
	private static final String QUAD_HELPERS = """
		uniform usamplerBuffer iris_VertexData;
		@@
		uniform ivec4 iris_QuadInfo;
		@@
		uniform ivec4 iris_QuadInfo2;
		@@
		vec4 iris_quadTangent = vec4(0.0, 0.0, 0.0, 1.0);
		@@
		vec4 iris_quadMidTex = vec4(0.0, 0.0, 0.0, 1.0);
		@@
		vec3 iris_quadPos(int v) {
			int w = v * iris_QuadInfo.z + iris_QuadInfo.w;
			return uintBitsToFloat(uvec3(texelFetch(iris_VertexData, w).r, texelFetch(iris_VertexData, w + 1).r, texelFetch(iris_VertexData, w + 2).r));
		}
		@@
		vec2 iris_quadUv(int v) {
			int w = v * iris_QuadInfo.z + iris_QuadInfo2.x;
			return uintBitsToFloat(uvec2(texelFetch(iris_VertexData, w).r, texelFetch(iris_VertexData, w + 1).r));
		}
		@@
		vec3 iris_quadNormal(int v) {
			if (iris_QuadInfo2.y < 0) return vec3(0.0, 1.0, 0.0);
			uint p = texelFetch(iris_VertexData, v * iris_QuadInfo.z + iris_QuadInfo2.y).r;
			return max(vec3(float(int(p << 24u) >> 24), float(int(p << 16u) >> 24), float(int(p << 8u) >> 24)) / 127.0, vec3(-1.0));
		}
		@@
		float iris_quadRsqrt(float v) {
			return v == 0.0 ? 1.0 : inversesqrt(v);
		}
		@@
		vec4 iris_quadTangentFor(vec3 n, vec3 p0, vec3 p1, vec3 p2, vec2 t0, vec2 t1, vec2 t2) {
			vec3 e1 = p1 - p0;
			vec3 e2 = p2 - p0;
			vec2 d1 = t1 - t0;
			vec2 d2 = t2 - t0;
			float fdenom = d1.x * d2.y - d2.x * d1.y;
			float f = fdenom == 0.0 ? 1.0 : 1.0 / fdenom;
			vec3 t = f * (d2.y * e1 - d1.y * e2);
			t *= iris_quadRsqrt(dot(t, t));
			vec3 b = f * (-d2.x * e1 + d1.x * e2);
			b *= iris_quadRsqrt(dot(b, b));
			vec3 pb = vec3(t.y * n.z - t.z * n.y, t.z * n.x - t.x * n.z, t.x * n.y - t.y * n.x);
			return vec4(t, dot(b, pb) < 0.0 ? -1.0 : 1.0);
		}
		@@
		void iris_deriveQuad() {
			int mode = iris_QuadInfo.x;
			if (mode == 0) return;
			int base = iris_QuadInfo.y + ((gl_VertexID - iris_QuadInfo.y) / mode) * mode;
			vec2 mid = vec2(0.0);
			for (int i = 0; i < mode; i++) mid += iris_quadUv(base + i);
			mid /= float(mode);
			vec3 p0 = iris_quadPos(base);
			vec3 p1 = iris_quadPos(base + 1);
			vec3 p2 = iris_quadPos(base + 2);
			vec4 t;
			if (mode == 4) {
				vec3 p3 = iris_quadPos(base + 3);
				vec3 n = normalize(cross(p2 - p0, p3 - p1));
				t = iris_quadTangentFor(n, p0, p1, p2, iris_quadUv(base), iris_quadUv(base + 1), iris_quadUv(base + 2));
			} else {
				vec3 n = iris_quadNormal(gl_VertexID);
				p0 -= dot(p0, n) * n;
				p1 -= dot(p1, n) * n;
				p2 -= dot(p2, n) * n;
				t = iris_quadTangentFor(n, p0, p1, p2, iris_quadUv(base), iris_quadUv(base + 1), iris_quadUv(base + 2));
			}
			vec4 q = trunc(t * 127.0) / 127.0;
			iris_quadTangent = mix(q, vec4(0.0), isnan(q));
			iris_quadMidTex = vec4(mid, 0.0, 1.0);
		}
		""";

	private static final java.util.regex.Pattern FRAG_DEPTH_WRITE = java.util.regex.Pattern.compile("gl_FragDepth[ \\t\\n]*(=[^=]|[-+*/]=)");

	/**
	 * A fragment shader that reads gl_FragDepth but never writes it: on GL the read is undefined and the fixed-function
	 * depth is used; SPIR-V forbids it. Reads become gl_FragCoord.z, the value GL would have kept. (Noble, Bliss)
	 */
	static String readOnlyFragDepth(String glsl) {
		if (!glsl.contains("gl_FragDepth") || FRAG_DEPTH_WRITE.matcher(glsl).find()) return glsl;
		return glsl.replace("gl_FragDepth", "gl_FragCoord.z");
	}

	/** User functions named like GLSL 4.x builtins collide on Vulkan's stricter overload rules. (Upstream transformLate; fixes miniature) */
	private static void renameBuiltinOverloads(TranslationUnit tree) {
		for (String name : List.of("tanh", "fma", "min3", "max3", "mid3", "round")) {
			boolean declared = tree.getRoot().nodeIndex.getStream(io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition.class)
				.anyMatch(f -> f.getFunctionPrototype().getName().getName().equals(name));
			if (declared) tree.getRoot().rename(name, Glsl.uniqueName(List.of(tree), "iris_legacy_" + name));
		}
	}

	/** Desktop GLSL ignores precision qualifiers, but glslang rejects overloads that differ only in them. (miniature) */
	private static void stripPrecision(TranslationUnit tree) {
		var root = tree.getRoot();
		for (var part : root.nodeIndex.getStream(io.github.douira.glsl_transformer.ast.node.type.qualifier.PrecisionQualifier.class).toList()) {
			var q = part.getAncestor(TypeQualifier.class);
			part.detachAndDelete();
			if (q != null && q.getParts().isEmpty()) q.detachAndDelete();
		}
		for (var d : root.nodeIndex.getStream(io.github.douira.glsl_transformer.ast.node.declaration.PrecisionDeclaration.class).toList()) {
			if (d.getParent() instanceof DeclarationExternalDeclaration ext) ext.detachAndDelete();
			else d.detachAndDelete();
		}
	}

	private static final java.util.regex.Pattern NV_GPU_SHADER5 = java.util.regex.Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_NV_gpu_shader5[ \\t]*:[ \\t]*\\w+[ \\t]*$");

	/**
	 * GL_NV_gpu_shader5 has no Vulkan GLSL equivalent (packs such as Noble enable it on any NVIDIA GPU for 8/16-bit
	 * types). Its small types are emulated with 32-bit ones: same results except deliberate overflow/bit tricks,
	 * which packs gate behind their own checks. Explicit 16-bit arithmetic is a later per-device optimisation.
	 */
	static String emulateNvGpuShader5(String glsl) {
		var m = NV_GPU_SHADER5.matcher(glsl);
		if (!m.find()) return glsl;
		StringBuilder defs = new StringBuilder("// Vulkan Iris: GL_NV_gpu_shader5 small types emulated at 32 bits\n");
		String[][] map = {{"float16_t", "float"}, {"f16vec2", "vec2"}, {"f16vec3", "vec3"}, {"f16vec4", "vec4"},
			{"f16mat2", "mat2"}, {"f16mat3", "mat3"}, {"f16mat4", "mat4"},
			{"int8_t", "int"}, {"i8vec2", "ivec2"}, {"i8vec3", "ivec3"}, {"i8vec4", "ivec4"},
			{"uint8_t", "uint"}, {"u8vec2", "uvec2"}, {"u8vec3", "uvec3"}, {"u8vec4", "uvec4"},
			{"int16_t", "int"}, {"i16vec2", "ivec2"}, {"i16vec3", "ivec3"}, {"i16vec4", "ivec4"},
			{"uint16_t", "uint"}, {"u16vec2", "uvec2"}, {"u16vec3", "uvec3"}, {"u16vec4", "uvec4"}};
		for (String[] e : map) defs.append("#define ").append(e[0]).append(' ').append(e[1]).append('\n');
		return m.replaceAll(java.util.regex.Matcher.quoteReplacement(defs.toString().stripTrailing()));
	}

	private record Io(TypeAndInitDeclaration decl, String name, int slots) {
	}

	/**
	 * Explicit locations for every stage interface. Iris's VK-conformance pass gives each varying one location, which
	 * overlaps for matrices/arrays; here one global name → location map spans all stages (so producer and consumer
	 * agree by construction) and each name reserves as many locations as its type occupies. Fragment outputs keep
	 * Iris's locations (they are colour attachment indices). Vertex inputs get attribute locations.
	 */
	private static Map<String, Integer> assignLocations(Map<PatchShaderType, TranslationUnit> trees) {
		Map<String, Integer> varyings = new LinkedHashMap<>();
		Map<String, Integer> sizes = new LinkedHashMap<>();
		Map<PatchShaderType, List<Io>> ios = new EnumMap<>(PatchShaderType.class);
		Map<String, Integer> attributeSlots = new LinkedHashMap<>();
		for (var entry : trees.entrySet()) {
			PatchShaderType stage = entry.getKey();
			var root = entry.getValue().getRoot();
			List<Io> list = new ArrayList<>();
			List<DeclarationExternalDeclaration> unusedInputs = new ArrayList<>();
			for (var external : entry.getValue().getChildren()) {
				if (!(external instanceof DeclarationExternalDeclaration d) || !(d.getDeclaration() instanceof TypeAndInitDeclaration v)) continue;
				var q = v.getType().getTypeQualifier();
				boolean in = Glsl.hasStorage(q, StorageType.IN), outQ = Glsl.hasStorage(q, StorageType.OUT);
				if (!in && !outQ || !(v.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric)) continue;
				if (stage == PatchShaderType.FRAGMENT && outQ || stage == PatchShaderType.COMPUTE) continue;
				if (v.getMembers().size() != 1) throw new IllegalArgumentException("split in/out declarations first: " + ASTPrinter.print(PrintType.COMPACT, d));
				var member = v.getMembers().get(0);
				String name = member.getName().getName();
				if (name.startsWith("gl_")) continue;
				boolean perVertex = in && (stage == PatchShaderType.GEOMETRY || stage == PatchShaderType.TESS_CONTROL || stage == PatchShaderType.TESS_EVAL)
					|| outQ && stage == PatchShaderType.TESS_CONTROL;
				int slots = slots(numeric, member, root, perVertex);
				if (stage == PatchShaderType.VERTEX && in && root.identifierIndex.get(name).size() <= 1) {
					// declared but never read (e.g. at_tangent after Iris's Sodium patch renames its uses): an inactive
					// attribute on GL; dropped here so the pipeline needs no vertex data for it
					unusedInputs.add(d);
					continue;
				}
				if (stage == PatchShaderType.VERTEX && in) {
					attributeSlots.merge(name, slots, Math::max);
					list.add(new Io(v, "attr:" + name, slots));
				} else {
					sizes.merge(name, slots, Math::max);
					list.add(new Io(v, name, slots));
				}
			}
			ios.put(stage, list);
			if (!unusedInputs.isEmpty()) root.indexBuildSession(() -> unusedInputs.forEach(DeclarationExternalDeclaration::detachAndDelete));
		}
		int next = 0;
		for (var e : sizes.entrySet()) {
			varyings.put(e.getKey(), next);
			next += e.getValue();
		}
		Map<String, Integer> attributes = new LinkedHashMap<>();
		int attr = 0;
		List<String> order = new ArrayList<>(attributeSlots.keySet());
		order.sort(java.util.Comparator.comparingInt((String n) -> KNOWN_ATTRIBUTES.contains(n) ? KNOWN_ATTRIBUTES.indexOf(n) : KNOWN_ATTRIBUTES.size()).thenComparing(n -> n));
		for (String n : order) {
			attributes.put(n, attr);
			attr += attributeSlots.get(n);
		}
		for (var entry : ios.entrySet()) {
			var root = trees.get(entry.getKey()).getRoot();
			root.indexBuildSession(() -> {
				for (Io io : entry.getValue()) {
					int loc = io.name().startsWith("attr:") ? attributes.get(io.name().substring(5)) : varyings.get(io.name());
					var q = io.decl().getType().getTypeQualifier();
					Glsl.removeLayout(q, "location");
					Glsl.setLayout(q, "location", loc);
				}
			});
		}
		return attributes;
	}

	/** Interface locations a declaration occupies (GLSL 4.60 §4.4.1: matrices one per column, dvec3/4 two, arrays per element). */
	private static int slots(BuiltinNumericTypeSpecifier numeric, io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember member,
							 io.github.douira.glsl_transformer.ast.query.Root root, boolean perVertex) {
		var type = numeric.type;
		int[] shape = type.getDimensions();
		int columns = type.isMatrix() ? shape[0] : 1;
		int rows = type.isScalar() ? 1 : shape[type.isMatrix() ? 1 : 0];
		int perColumn = type.getBitDepth() == 64 && rows > 2 ? 2 : 1;
		int elements = 1;
		try {
			List<Integer> dims = Glsl.dimensions(root, member.getArraySpecifier(), numeric.getArraySpecifier());
			for (int i = perVertex ? 1 : 0; i < dims.size(); i++) elements = Math.multiplyExact(elements, dims.get(i));
		} catch (RuntimeException unsized) {
			elements = 1; // unsized per-vertex array (in vec4 x[]) — one element per vertex
		}
		return columns * perColumn * elements;
	}

	private static PatchShaderType lastPreRasterStage(Set<PatchShaderType> stages) {
		if (stages.contains(PatchShaderType.GEOMETRY)) return PatchShaderType.GEOMETRY;
		if (stages.contains(PatchShaderType.TESS_EVAL)) return PatchShaderType.TESS_EVAL;
		return stages.contains(PatchShaderType.VERTEX) ? PatchShaderType.VERTEX : null;
	}

	private static final String CLIP_REMAP = "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);";

	private static void remapClipDepth(TranslationUnit tree, PatchShaderType stage) {
		var root = tree.getRoot();
		if (stage == PatchShaderType.GEOMETRY) {
			String emit = Glsl.uniqueName(List.of(tree), "iris_vkEmitVertex");
			for (var id : root.identifierIndex.getStream("EmitVertex").toList()) {
				if (id.getParent() instanceof FunctionCallExpression) id.setName(emit);
			}
			tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, "void " + emit + "() { " + CLIP_REMAP + " EmitVertex(); }");
		} else {
			String main = Glsl.uniqueName(List.of(tree), "iris_vkMain");
			root.rename("main", main);
			tree.getChildren().add(parser.parseExternalDeclaration(root, "void main() { " + main + "(); " + CLIP_REMAP + " }"));
		}
	}

	/** Uniforms with initialisers that Iris doesn't supply keep their GL value as constants. (Upstream resolveDefaults, simplified.) */
	private static void resolveDefaults(TranslationUnit tree, Predicate<String> registered) {
		var root = tree.getRoot();
		for (var external : List.copyOf(tree.getChildren())) {
			if (!(external instanceof DeclarationExternalDeclaration declaration) || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration variables)
				|| !Glsl.hasStorage(variables.getType().getTypeQualifier(), StorageType.UNIFORM)
				|| !(variables.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier)
				|| variables.getMembers().stream().noneMatch(m -> m.getInitializer() != null)) {
				continue;
			}
			for (var member : variables.getMembers()) {
				var copy = member.cloneInto(root);
				var type = variables.getType().cloneInto(root);
				if (copy.getInitializer() != null) {
					if (registered.test(copy.getName().getName())) {
						copy.getInitializer().detachAndDelete();
					} else {
						var q = type.getTypeQualifier();
						for (var part : List.copyOf(q.getParts())) if (part instanceof StorageQualifier) part.detachAndDelete();
						q.getParts().add(new StorageQualifier(StorageType.CONST));
						Glsl.removeLayout(q, "location", "set", "binding", "offset");
					}
				}
				tree.getChildren().add(tree.getChildren().indexOf(declaration), new DeclarationExternalDeclaration(new TypeAndInitDeclaration(type, Stream.of(copy))));
			}
			declaration.detachAndDelete();
		}
	}

	/** {@code const mat3 m = transpose(...)} is legal on GL drivers, not in SPIR-V GLSL. (Upstream transformConstTranspose.) */
	private static void constTranspose(TranslationUnit tree) {
		for (var call : tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()) {
			if (call.getFunctionName() == null || !call.getFunctionName().getName().equals("transpose")) continue;
			var init = call.getAncestor(ExpressionInitializer.class);
			var decl = init == null ? null : init.getAncestor(TypeAndInitDeclaration.class);
			if (decl == null || decl.getType().getTypeQualifier() == null) continue;
			var q = decl.getType().getTypeQualifier();
			for (var part : List.copyOf(q.getParts())) {
				if (part instanceof StorageQualifier s && s.storageType == StorageType.CONST) part.detachAndDelete();
			}
			if (q.getParts().isEmpty()) q.detachAndDelete();
		}
	}

	/** Plain globals are zero on GL drivers in practice; make that explicit. (Upstream initializeGlobals.) */
	private static void initializeGlobals(TranslationUnit tree) {
		boolean any = false;
		for (var external : List.copyOf(tree.getChildren())) {
			if (!(external instanceof DeclarationExternalDeclaration declaration) || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration variables)
				|| variables.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier) {
				continue;
			}
			var q = variables.getType().getTypeQualifier();
			if (q != null && q.getParts().stream().anyMatch(StorageQualifier.class::isInstance)) continue;
			for (var member : variables.getMembers()) {
				if (member.getInitializer() == null && !member.getName().getName().startsWith("gl_")) {
					member.setInitializer(new NestedInitializer());
					any = true;
				}
			}
		}
		if (any) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_ALL, "#extension GL_EXT_null_initializer : require\n");
	}

	private static final class Resource {
		final String name, type;
		final Kind kind;
		final int count;
		final List<TypeQualifier> qualifiers = new ArrayList<>();
		final Set<PatchShaderType> stages = EnumSet.noneOf(PatchShaderType.class);

		Resource(String name, Kind kind, String type, int count) {
			this.name = name;
			this.kind = kind;
			this.type = type;
			this.count = count;
		}
	}

	private static void resource(Map<String, Resource> resources, String name, Kind kind, String type, int count, TypeQualifier q, PatchShaderType stage) {
		Resource r = resources.computeIfAbsent(name, n -> new Resource(n, kind, type, count));
		if (r.kind != kind || r.count != count || !r.type.equals(type)) throw new IllegalArgumentException("conflicting declarations of " + name + " across stages");
		r.qualifiers.add(q);
		r.stages.add(stage);
	}
}
