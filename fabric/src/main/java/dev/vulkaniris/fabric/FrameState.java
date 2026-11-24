package dev.vulkaniris.fabric;

import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.util.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Fills Iris's {@link CapturedRenderingState} and frame timers on Vulkan. Iris's own mixins that do this
 * (MixinGameRenderer, MixinLevelRenderer, MixinFogRenderer) are stripped by Polydium, so Vulkan Iris's mixins call
 * these with the same values from the same injection points. Copied behaviour, not new semantics.
 */
public final class FrameState {
	private static boolean levelThisFrame;
	private static Boolean vulkan;

	private FrameState() {
	}

	/**
	 * Only on a Vulkan backend: on OpenGL Iris's own mixins run, and doing it twice would e.g. advance frameCounter
	 * twice per frame.
	 */
	public static boolean active() {
		if (vulkan == null) {
			vulkan = StandardMacros.isVulkanBackend();
			if (vulkan) {
				net.irisshaders.iris.uniforms.IrisExclusiveUniforms.SHOULD_RENDER_BLOCK_OUTLINE = () ->
					((dev.vulkaniris.fabric.mixin.GameRendererAccessorPort) net.minecraft.client.Minecraft.getInstance().gameRenderer)
						.vulkaniris$shouldRenderBlockOutline();
			}
		}
		return vulkan;
	}

	/** GameRenderer.render HEAD (Iris: MixinGameRenderer#iris$startFrame). */
	public static void startFrame(DeltaTracker delta) {
		if (!active()) return;
		CapturedRenderingState.INSTANCE.setRealTickDelta(delta.getGameTimeDeltaPartialTick(true));
		SystemTimeUniforms.COUNTER.beginFrame();
		SystemTimeUniforms.TIMER.beginFrame(Util.getNanos());
		levelThisFrame = false;
	}

	/** LevelRenderer.renderLevel HEAD (Iris: MixinLevelRenderer#iris$setupPipeline, state part only). */
	public static void beginLevel(DeltaTracker delta, Matrix4f modelView, Matrix4f projection) {
		if (!active()) return;
		IrisTimeUniforms.updateTime();
		CapturedRenderingState.INSTANCE.setGbufferModelView(modelView);
		CapturedRenderingState.INSTANCE.setGbufferProjection(projection);
		CapturedRenderingState.INSTANCE.setTickDelta(delta.getGameTimeDeltaPartialTick(false));
		// cloudTime needs the cloud texture width (read through an Iris accessor mixin, also stripped): 0 for now.
		CapturedRenderingState.INSTANCE.setCloudTime(0);
		levelThisFrame = true;
	}

	public static boolean levelRenderedThisFrame() {
		return levelThisFrame;
	}

	/** FogRenderer.setupFog RETURN (Iris: MixinFogRenderer). */
	public static void fog(Camera camera, Vector4f fogColor) {
		if (!active()) return;
		if (camera.getFluidInCamera() == FogType.WATER) {
			float density = 0.05F;
			if (camera.entity() instanceof LocalPlayer p) density -= p.getWaterVision() * p.getWaterVision() * 0.03F;
			CapturedRenderingState.INSTANCE.setFogDensity(density);
		} else {
			CapturedRenderingState.INSTANCE.setFogDensity(-1.0F);
		}
		CapturedRenderingState.INSTANCE.setFogColor(fogColor.x, fogColor.y, fogColor.z);
	}
}
