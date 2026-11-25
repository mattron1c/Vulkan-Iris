package dev.vulkaniris.vk;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link SpirvUse}: declared-but-unused bindings are not reported (GL's "active uniform"), used ones are. */
class SpirvUseTest {
	private static final String FRAGMENT = """
		#version 460
		layout(set = 0, binding = 3) uniform sampler2D shadowtex0;   // declared, never read
		layout(set = 0, binding = 4) uniform sampler2D colortex0;    // read directly
		layout(set = 0, binding = 5) uniform sampler2D shadowcolor0; // read only through a helper function
		layout(set = 1, binding = 2) uniform Block { vec4 tint; };   // read
		layout(set = 1, binding = 7) uniform Unused { vec4 never; }; // declared, never read
		layout(location = 0) in vec2 uv;
		layout(location = 0) out vec4 color;
		vec4 helper(vec2 p) { return texture(shadowcolor0, p); }
		void main() {
			color = texture(colortex0, uv) * tint + helper(uv);
		}
		""";

	private static long key(int set, int binding) {
		return (long) set << 32 | binding;
	}

	@Test
	void reportsOnlyBindingsTheShaderReads() {
		for (boolean optimize : new boolean[]{false, true}) {
			try (ShaderCompiler c = new ShaderCompiler(org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2, optimize)) {
				byte[] spirv = c.compile("test:use.fsh", ShaderCompiler.Stage.FRAGMENT, FRAGMENT);
				assertEquals(Set.of(key(0, 4), key(0, 5), key(1, 2)), SpirvUse.usedBindings(spirv), "optimize=" + optimize);
			}
		}
	}

	@Test
	void rejectsNonSpirv() {
		assertThrows(IllegalArgumentException.class, () -> SpirvUse.usedBindings(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20}));
	}
}
