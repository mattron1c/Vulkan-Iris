package net.irisshaders.iris.apiimpl;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.IrisTextVertexSinkImpl;
import net.minecraft.client.gui.screens.Screen;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public class IrisApiV0Impl implements IrisApi {
	public static final IrisApiV0Impl INSTANCE = new IrisApiV0Impl();
	/**
	 * Vulkan Iris: the config other mods get. Not {@link IrisApiV0ConfigImpl}: Polydium, on Vulkan, wraps that class so
	 * it always reports shaders off and ignores changes (true for stock Iris there, not for Vulkan Iris). This class
	 * does what IrisApiV0ConfigImpl does on Iris, on both backends.
	 */
	private static final IrisApiConfig CONFIG = new VulkanIrisApiConfig();

	@Override
	public int getMinorApiRevision() {
		return 3;
	}

	/**
	 * Vulkan Iris (fork addition): a pack is drawn by the Vulkan backend, which runs no GL pipeline. Other mods (Polydium's
	 * occlusion culler, for one) ask this API whether a pack is in use, and must get the same answer they get on GL.
	 */
	public static volatile boolean vulkanPackInUse;
	/** Vulkan Iris: the drawn pack's sunPathRotation (Iris's pipeline reports it on GL). */
	public static volatile float vulkanSunPathRotation;

	@Override
	public boolean isShaderPackInUse() {
		if (vulkanPackInUse) return true;
		if (Iris.getPipelineManager() == null) return false; // Vulkan backend: no GL pipeline manager
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline == null) {
			return false;
		}

		return !(pipeline instanceof VanillaRenderingPipeline);
	}

	@Override
	public boolean isRenderingShadowPass() {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
	}

	@Override
	public Object openMainIrisScreenObj(Object parent) {
		return new ShaderPackScreen((Screen) parent);
	}

	@Override
	public String getMainScreenLanguageKey() {
		return "options.iris.shaderPackSelection";
	}

	@Override
	public IrisApiConfig getConfig() {
		return CONFIG;
	}

	@Override
	public IrisTextVertexSink createTextVertexSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider) {
		return new IrisTextVertexSinkImpl(maxQuadCount, bufferProvider);
	}

	@Override
	public float getSunPathRotation() {
		if (vulkanPackInUse) return vulkanSunPathRotation;
		if (Iris.getPipelineManager() == null) return 0;
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline == null) {
			return 0;
		}

		return pipeline.getSunPathRotation();
	}

	@Override
	public void assignPipeline(RenderPipeline pipeline, IrisProgram program) {
		IrisPipelines.assignPipeline(pipeline, ShaderKey.findBestMatch(pipeline, ProgramId.fromAPI(program)));
	}
}
