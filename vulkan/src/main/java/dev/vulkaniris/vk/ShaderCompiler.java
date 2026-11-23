package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * GLSL → SPIR-V via shaderc (Google's glslang wrapper, shipped by LWJGL; Polydium and VulkanMod bundle the same
 * 3.3.3 natives). Errors carry the shader name, stage, compiler output and the offending source lines.
 *
 * <p>Results are cached in memory by a hash of (stage, source, target). An on-disk SPIR-V cache is Phase 5 work.</p>
 */
public final class ShaderCompiler implements AutoCloseable {
	public enum Stage {
		VERTEX(shaderc_glsl_vertex_shader, "vertex"),
		FRAGMENT(shaderc_glsl_fragment_shader, "fragment"),
		GEOMETRY(shaderc_glsl_geometry_shader, "geometry"),
		TESS_CONTROL(shaderc_glsl_tess_control_shader, "tess_control"),
		TESS_EVALUATION(shaderc_glsl_tess_evaluation_shader, "tess_evaluation"),
		COMPUTE(shaderc_glsl_compute_shader, "compute");

		final int kind;
		public final String label;

		Stage(int kind, String label) {
			this.kind = kind;
			this.label = label;
		}
	}

	/** Compile failure with everything needed to fix the shader. */
	public static final class ShaderCompileException extends RuntimeException {
		public final String shaderName;
		public final Stage stage;
		public final String compilerOutput;

		ShaderCompileException(String shaderName, Stage stage, String compilerOutput, String source) {
			super(format(shaderName, stage, compilerOutput, source));
			this.shaderName = shaderName;
			this.stage = stage;
			this.compilerOutput = compilerOutput;
		}

		private static final Pattern LINE = Pattern.compile(":(\\d+): (?:error|warning)");

		private static String format(String name, Stage stage, String output, String source) {
			StringBuilder sb = new StringBuilder();
			sb.append("Failed to compile ").append(stage.label).append(" shader '").append(name).append("':\n").append(output.strip());
			String[] lines = source.split("\n", -1);
			Matcher m = LINE.matcher(output);
			int shown = 0;
			while (m.find() && shown < 3) {
				int line = Integer.parseInt(m.group(1));
				sb.append("\n--- ").append(name).append(" around line ").append(line).append(" ---");
				for (int i = Math.max(1, line - 3); i <= Math.min(lines.length, line + 3); i++) {
					sb.append('\n').append(i == line ? ">> " : "   ").append(String.format("%4d | ", i)).append(lines[i - 1]);
				}
				shown++;
			}
			return sb.toString();
		}
	}

	private final long compiler;
	private final long options;
	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
	private boolean closed;

	/** @param vulkanTarget e.g. {@code shaderc_env_version_vulkan_1_2} */
	public ShaderCompiler(int vulkanTarget, boolean optimize) {
		this.compiler = shaderc_compiler_initialize();
		if (compiler == 0L) throw new IllegalStateException("shaderc_compiler_initialize returned NULL (shaderc natives missing?)");
		this.options = shaderc_compile_options_initialize();
		shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, vulkanTarget);
		shaderc_compile_options_set_optimization_level(options, optimize ? shaderc_optimization_level_performance : shaderc_optimization_level_zero);
		shaderc_compile_options_set_generate_debug_info(options);
	}

	public static ShaderCompiler forVulkan12() {
		return new ShaderCompiler(shaderc_env_version_vulkan_1_2, true);
	}

	/** @return SPIR-V words as bytes (heap copy; safe to keep). */
	public byte[] compile(String name, Stage stage, String source) {
		if (closed) throw new IllegalStateException("ShaderCompiler closed");
		String key = stage.name() + ':' + sha256(source);
		byte[] cached = cache.get(key);
		if (cached != null) return cached;
		long result = shaderc_compile_into_spv(compiler, source, stage.kind, name, "main", options);
		if (result == 0L) throw new IllegalStateException("shaderc_compile_into_spv returned NULL for " + name);
		try {
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				String msg = shaderc_result_get_error_message(result);
				throw new ShaderCompileException(name, stage, msg == null ? "(no message)" : msg, source);
			}
			ByteBuffer spv = shaderc_result_get_bytes(result);
			if (spv == null || spv.remaining() == 0) throw new ShaderCompileException(name, stage, "compiler produced no SPIR-V", source);
			byte[] out = new byte[spv.remaining()];
			spv.get(out);
			if (out.length < 4 || (out[0] & 0xFF) != 0x03 || (out[1] & 0xFF) != 0x02 || (out[2] & 0xFF) != 0x23 || (out[3] & 0xFF) != 0x07) {
				throw new ShaderCompileException(name, stage, "output is not SPIR-V (bad magic number)", source);
			}
			cache.put(key, out);
			return out;
		} finally {
			shaderc_result_release(result);
		}
	}

	/** Copies SPIR-V into native memory for vkCreateShaderModule; caller frees with {@link MemoryUtil#memFree}. */
	public static ByteBuffer toNative(byte[] spirv) {
		ByteBuffer b = MemoryUtil.memAlloc(spirv.length);
		b.put(spirv).flip();
		return b;
	}

	public int cachedModules() {
		return cache.size();
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		shaderc_compile_options_release(options);
		shaderc_compiler_release(compiler);
	}
}
