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
	public record Result(Map<PatchShaderType, String> sources, ShaderResourceLayout layout, Map<String, Integer> attributes) {
	}

	/** Blaze3D / Iris attribute names first, in a fixed order, so the same vertex format maps to the same locations. */
	private static final List<String> KNOWN_ATTRIBUTES = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal",
		"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock", "iris_Entity");

	private static final SingleASTTransformer<JobParameters> parser = new SingleASTTransformer<>();

	private VulkanGlslTranslator() {
	}

	/** {@code registered}: uniform names Iris will supply values for (others with initialisers become const). */
	public static synchronized Result translate(Map<PatchShaderType, String> sources, Predicate<String> registered) {
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
		PatchShaderType clipStage = lastPreRasterStage(trees.keySet());
		Map<PatchShaderType, String> out = new EnumMap<>(PatchShaderType.class);
		for (var entry : trees.entrySet()) {
			TranslationUnit tree = entry.getValue();
			var root = tree.getRoot();
			root.indexBuildSession(() -> {
				for (String name : stageUniforms.getOrDefault(tree, List.of())) {
					for (var id : root.identifierIndex.get(name).stream().toList()) {
						if (id.getParent() instanceof ReferenceExpression ref && !Glsl.isShadowed(ref, name)) {
							ref.replaceByAndDelete(parser.parseExpression(root, instance + "." + name));
						}
					}
				}
				for (var d : loose) if (d.getRoot() == root) d.detachAndDelete();
				if (!uniforms.isEmpty()) {
					tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "layout(std140, set = " + SET + ", binding = "
						+ UNIFORM_BINDING + ") uniform " + ShaderResourceLayout.UNIFORM_BLOCK + " {" + body + "} " + instance + ";");
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
		return new Result(out, new ShaderResourceLayout(Std140Type.align(offset, alignment), uniforms, descriptors), attributes);
	}

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
				if (stage == PatchShaderType.VERTEX && in) {
					attributeSlots.merge(name, slots, Math::max);
					list.add(new Io(v, "attr:" + name, slots));
				} else {
					sizes.merge(name, slots, Math::max);
					list.add(new Io(v, name, slots));
				}
			}
			ios.put(stage, list);
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
