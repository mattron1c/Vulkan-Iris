package net.irisshaders.iris.gl.sampler;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;

public class SamplerLimits {
	private static SamplerLimits instance;
	private final int maxTextureUnits;
	private final int maxDrawBuffers;
	private final int maxShaderStorageUnits;

	private SamplerLimits() {
		if (net.irisshaders.iris.gl.shader.StandardMacros.isVulkanBackend()) {
			// Vulkan Iris: GL limits don't exist on Vulkan (no GL context; Polydium stubs GL calls). Nothing on the Vulkan
			// path should need them, so whoever got here is logged to be fixed.
			net.irisshaders.iris.Iris.logger.warn("[Vulkan Iris] GL sampler limits requested on the Vulkan backend", new Throwable("caller"));
			this.maxTextureUnits = 0;
			this.maxDrawBuffers = 0;
			this.maxShaderStorageUnits = 0;
			return;
		}
		this.maxTextureUnits = GlStateManager._getInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
		this.maxDrawBuffers = GlStateManager._getInteger(GL20C.GL_MAX_DRAW_BUFFERS);
		this.maxShaderStorageUnits = IrisRenderSystem.supportsSSBO() ? GlStateManager._getInteger(GL45C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
	}

	public static SamplerLimits get() {
		if (instance == null) {
			instance = new SamplerLimits();
		}

		return instance;
	}

	public int getMaxTextureUnits() {
		return maxTextureUnits;
	}

	public int getMaxDrawBuffers() {
		return maxDrawBuffers;
	}

	public int getMaxShaderStorageUnits() {
		return maxShaderStorageUnits;
	}
}
