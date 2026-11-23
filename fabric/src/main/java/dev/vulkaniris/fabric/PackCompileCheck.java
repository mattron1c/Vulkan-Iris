package dev.vulkaniris.fabric;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vulkaniris.vk.ShaderCompiler;
import dev.vulkaniris.vk.VkCheck;
import dev.vulkaniris.vk.VkLog;
import dev.vulkaniris.vk.VulkanContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisLimits;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanGlslTranslator;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5 in-game test ({@code -Dvulkaniris.phase5.selftest=true}): every pack in {@code shaderpacks/} goes through
 * Iris's own loader and {@link TransformPatcher} (with VK conformance on), then {@link VulkanGlslTranslator}, shaderc,
 * and {@code vkCreateShaderModule} on Polydium's device. Writes per-program results to {@code vulkaniris-phase5.txt}
 * and quits. Compute programs are not covered yet.
 */
public final class PackCompileCheck {
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.phase5.selftest");
	private static final int WARMUP_FRAMES = 60;
	private static final Set<ProgramId> SODIUM = EnumSet.of(ProgramId.TerrainSolid, ProgramId.TerrainCutout, ProgramId.Water,
		ProgramId.Shadow, ProgramId.ShadowSolid, ProgramId.ShadowCutout, ProgramId.ShadowWater);
	private static final VkLog LOG = new VkLog() {
		public void info(String m) { Iris.logger.info("[Vulkan Iris] " + m); }
		public void warn(String m) { Iris.logger.warn("[Vulkan Iris] " + m); }
		public void error(String m) { Iris.logger.error("[Vulkan Iris] " + m); }
	};

	private static int frames;
	private static boolean done;

	private PackCompileCheck() {
	}

	public static void afterGameRender() {
		if (!ENABLED || done || ++frames < WARMUP_FRAMES) return;
		done = true;
		StringBuilder report = new StringBuilder();
		int[] totals = new int[3]; // programs, ok, failed
		try (ShaderCompiler compiler = ShaderCompiler.forVulkan12()) {
			VulkanContext ctx = FabricLoader.getInstance().isModLoaded("polydium") && PolydiumAccess.vulkanActive() ? PolydiumAccess.borrow(LOG) : null;
			report.append(ctx == null ? "No Vulkan device: SPIR-V compiled but not loaded as modules\n"
				: "SPIR-V loaded with vkCreateShaderModule on " + PolydiumAccess.backendDescription() + "\n");
			Iris.initializeForVulkan();
			IrisLimits.VK_CONFORMANCE = true;
			Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
			List<Path> packs;
			try (Stream<Path> s = Files.list(dir)) {
				packs = s.filter(p -> p.toString().endsWith(".zip") || Files.isDirectory(p)).sorted().toList();
			}
			for (Path pack : packs) checkPack(pack, compiler, ctx, report, totals);
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] phase 5 check crashed", t);
			report.append("CRASH: ").append(t).append('\n');
			totals[2]++;
		}
		String head = "Vulkan Iris Phase 5 pack compile check — " + ZonedDateTime.now() + "\nRESULT: " + totals[1] + "/" + totals[0]
			+ " programs compiled to SPIR-V and accepted by the driver, " + totals[2] + " failed\n";
		Iris.logger.info("[Vulkan Iris] PHASE 5 " + head);
		try {
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-phase5.txt"), head + report);
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write phase 5 report", e);
		}
		Minecraft.getInstance().stop();
	}

	private static void checkPack(Path pack, ShaderCompiler compiler, VulkanContext ctx, StringBuilder report, int[] totals) {
		int ok = 0, failed = 0;
		List<String> failures = new ArrayList<>();
		FileSystem zip = null;
		long start = System.nanoTime();
		try {
			Path root;
			if (Files.isDirectory(pack)) {
				root = pack.resolve("shaders");
			} else {
				zip = FileSystems.newFileSystem(pack, PackCompileCheck.class.getClassLoader());
				root = findShaders(zip.getPath("/"));
			}
			if (root == null || !Files.exists(root)) {
				report.append("\n== ").append(pack.getFileName()).append(": no shaders/ directory, skipped\n");
				return;
			}
			ImmutableList<StringPair> env = StandardMacros.createStandardEnvironmentDefines();
			ShaderPack sp = new ShaderPack(root, new HashMap<>(), env, zip != null);
			ProgramSet set = sp.getProgramSet(DimensionId.OVERWORLD);
			var textures = set.getPackDirectives().getTextureMap();
			Set<ProgramSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());

			List<Object[]> jobs = new ArrayList<>(); // {name, source, kind, stage/programId}
			for (var arr : List.of(new Object[]{ProgramArrayId.Begin, TextureStage.BEGIN}, new Object[]{ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP},
				new Object[]{ProgramArrayId.Prepare, TextureStage.PREPARE}, new Object[]{ProgramArrayId.Deferred, TextureStage.DEFERRED},
				new Object[]{ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL})) {
				for (ProgramSource src : set.getComposite((ProgramArrayId) arr[0])) {
					if (src != null && src.isValid() && seen.add(src)) jobs.add(new Object[]{src.getName(), src, "composite", arr[1]});
				}
			}
			for (ProgramId id : ProgramId.values()) {
				if (id.getGroup() == ProgramGroup.Dh) continue;
				set.get(id).filter(ProgramSource::isValid).filter(seen::add).ifPresent(src ->
					jobs.add(new Object[]{src.getName(), src, id == ProgramId.Final ? "composite" : SODIUM.contains(id) ? "sodium" : "vanilla",
						id == ProgramId.Final ? TextureStage.COMPOSITE_AND_FINAL : id}));
			}

			for (Object[] job : jobs) {
				String name = (String) job[0];
				ProgramSource src = (ProgramSource) job[1];
				totals[0]++;
				try {
					Map<PatchShaderType, String> gl = patch(name, src, (String) job[2], job[3], textures);
					if (gl == null) throw new IllegalStateException("Iris produced no sources");
					var vk = VulkanGlslTranslator.translate(gl, n -> true);
					for (var e : vk.sources().entrySet()) {
						byte[] spirv = compiler.compile(pack.getFileName() + "/" + name + e.getKey().extension, stage(e.getKey()), e.getValue());
						if (ctx != null) loadModule(ctx, spirv, name);
					}
					ok++;
					totals[1]++;
				} catch (Throwable t) {
					failed++;
					totals[2]++;
					String msg = String.valueOf(t.getMessage()) + (t instanceof NullPointerException ? stack(t) : "");
					failures.add(name + ": " + t.getClass().getSimpleName() + ": " + (msg.length() > 1500 ? msg.substring(0, 1500) + " …" : msg));
				}
			}
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] pack " + pack.getFileName() + " failed to load", t);
			failures.add("pack failed to load: " + t + stack(t));
			failed++;
			totals[2]++;
		} finally {
			if (zip != null) try { zip.close(); } catch (Exception ignored) { }
		}
		report.append("\n== ").append(pack.getFileName()).append(": ").append(ok).append(" ok, ").append(failed).append(" failed (")
			.append((System.nanoTime() - start) / 1_000_000).append(" ms)\n");
		for (String f : failures) report.append("  FAIL ").append(f.replace("\n", "\n       ")).append('\n');
	}

	private static String stack(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null; c = c.getCause()) {
			sb.append("\n    ").append(c);
			StackTraceElement[] st = c.getStackTrace();
			for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n      at ").append(st[i]);
		}
		return sb.toString();
	}

	private static Path findShaders(Path zipRoot) throws Exception {
		if (Files.isDirectory(zipRoot.resolve("shaders"))) return zipRoot.resolve("shaders");
		try (Stream<Path> s = Files.list(zipRoot)) {
			return s.map(p -> p.resolve("shaders")).filter(Files::isDirectory).findFirst().orElse(null);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Map<PatchShaderType, String> patch(String name, ProgramSource s, String kind, Object stage, Object textures) {
		var tex = (it.unimi.dsi.fastutil.objects.Object2ObjectMap) textures;
		String v = s.getVertexSource().orElse(null), g = s.getGeometrySource().orElse(null), tc = s.getTessControlSource().orElse(null),
			te = s.getTessEvalSource().orElse(null), f = s.getFragmentSource().orElse(null);
		return switch (kind) {
			case "composite" -> TransformPatcher.patchComposite(name, v, g, f, (TextureStage) stage, tex);
			case "sodium" -> TransformPatcher.patchSodium(name, v, g, tc, te, f, alpha((ProgramId) stage), tex);
			default -> {
				boolean lines = stage == ProgramId.Line;
				yield TransformPatcher.patchVanilla(name, v, g, tc, te, f, alpha((ProgramId) stage), lines, stage == ProgramId.Clouds, false,
					new ShaderAttributeInputs(lines ? DefaultVertexFormat.POSITION_COLOR_NORMAL : DefaultVertexFormat.NEW_ENTITY, false, lines, false, false, false), tex);
			}
		};
	}

	private static AlphaTest alpha(ProgramId id) {
		return id == ProgramId.TerrainSolid || id == ProgramId.ShadowSolid ? AlphaTests.OFF : AlphaTests.ONE_TENTH_ALPHA;
	}

	private static ShaderCompiler.Stage stage(PatchShaderType t) {
		return switch (t) {
			case VERTEX -> ShaderCompiler.Stage.VERTEX;
			case GEOMETRY -> ShaderCompiler.Stage.GEOMETRY;
			case TESS_CONTROL -> ShaderCompiler.Stage.TESS_CONTROL;
			case TESS_EVAL -> ShaderCompiler.Stage.TESS_EVALUATION;
			case FRAGMENT -> ShaderCompiler.Stage.FRAGMENT;
			case COMPUTE -> ShaderCompiler.Stage.COMPUTE;
		};
	}

	private static void loadModule(VulkanContext ctx, byte[] spirv, String name) {
		ByteBuffer code = ShaderCompiler.toNative(spirv);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateShaderModule(ctx.device(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, lp),
				"vkCreateShaderModule(" + name + ")");
			vkDestroyShaderModule(ctx.device(), lp.get(0), null);
		} finally {
			MemoryUtil.memFree(code);
		}
	}
}
