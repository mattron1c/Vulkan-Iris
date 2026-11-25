package dev.vulkaniris.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ShadowCullState;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.CullEverythingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Phase 7d, CPU side of Iris's {@code ShadowRenderer.renderShadows} for terrain (LGPL-3.0, credit: the Iris team): the
 * shadow model-view and projection, Iris's shadow frustum choice ({@code createShadowFrustum}), then Sodium's terrain
 * setup and its opaque and translucent layer draws with Iris's shadow state set. Sodium's draws reach Polydium's
 * {@code SodiumVulkan.draw}, which {@link TerrainHook} hands to the pack's shadow programs while the state is set.
 * Sodium and Polydium are only called (their public API), never copied.
 * <p>
 * Not done yet (reported by the caller): entities, block entities and the player in the shadow map, shadow mipmaps.
 */
public final class VulkanShadow {
	private VulkanShadow() {
	}

	/** The shadow view of one frame: Iris's matrices and the frustum Sodium culls sections with. */
	public record View(Matrix4f modelView, Matrix4f projection, Frustum frustum, String culling) {
	}

	/**
	 * Iris ShadowRenderer: createShadowModelView, the ortho (or, with shadow.fov, perspective) projection, and the shadow
	 * frustum prepared at the camera. {@code voxelization}: Iris's "pack has voxelization" guess (a geometry shader or
	 * image stores in the shadow programs).
	 */
	public static View view(PackDirectives directives, boolean voxelization) {
		PackShadowDirectives s = directives.getShadowDirectives();
		float near = s.getNearPlane(), far = s.getFarPlane();
		Matrix4f modelView = new Matrix4f(ShadowRenderer.createShadowModelView(directives.getSunPathRotation(), s.getIntervalSize(), near, far).last().pose());
		Matrix4f projection = s.getFov() != null ? ShadowMatrices.createPerspectiveMatrix(s.getFov())
			: ShadowMatrices.createOrthoMatrix(s.getDistance(), Mth.equal(near, -1.0f) ? -DHCompat.getRenderDistance() * 16 : near,
			Mth.equal(far, -1.0f) ? DHCompat.getRenderDistance() * 16 : far);

		String[] culling = new String[1];
		Frustum frustum = frustum(directives, s, voxelization, projection, culling);
		Vector3d cam = CameraUniforms.getUnshiftedCameraPosition();
		frustum.prepare(cam.x, cam.y, cam.z);
		return new View(modelView, projection, frustum, culling[0]);
	}

	/** Iris ShadowRenderer.createShadowFrustum (terrain), with the same choices and the same culling description. */
	private static Frustum frustum(PackDirectives directives, PackShadowDirectives s, boolean voxelization, Matrix4f projection, String[] info) {
		float renderMultiplier = s.getDistanceRenderMul();
		float halfPlaneLength = s.getDistance();
		ShadowCullState cull = s.getCullingState();
		int renderDistanceBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
		if ((cull == ShadowCullState.DEFAULT && voxelization) || cull == ShadowCullState.DISTANCE) {
			double distance = halfPlaneLength * renderMultiplier;
			String reason = cull == ShadowCullState.DISTANCE ? "(set by shader pack)" : "(voxelization detected)";
			if (distance <= 0 || distance > renderDistanceBlocks) {
				info[0] = "culling disabled " + reason;
				return new NonCullingFrustum();
			}
			info[0] = "distance only " + reason + ", " + distance + " blocks";
			return new BoxCullingFrustum(new BoxCuller(distance));
		}
		boolean hasSafeZone = cull == ShadowCullState.SAFE_ZONE;
		if (hasSafeZone && renderMultiplier < 0) renderMultiplier = 1.0f;
		double distance = (hasSafeZone ? s.getVoxelDistance() : halfPlaneLength) * renderMultiplier;
		if (renderMultiplier < 0) distance = IrisVideoSettings.shadowDistance * 16;
		BoxCuller boxCuller;
		if (distance >= renderDistanceBlocks && !hasSafeZone) {
			boxCuller = null;
		} else {
			if (distance == 0.0 && !hasSafeZone) {
				info[0] = "no shadows rendered";
				return new CullEverythingFrustum();
			}
			boxCuller = new BoxCuller(distance);
		}
		info[0] = (hasSafeZone ? "Safe Zone" : "Advanced") + " Frustum Culling, distance " + (boxCuller == null ? "render distance" : distance + " blocks");
		Vector4f light = new CelestialUniforms(directives.getSunPathRotation()).getShadowLightPositionInWorldSpace();
		Vector3f lightVector = new Vector3f(light.x(), light.y(), light.z()).normalize();
		Matrix4f projView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection()).mul(CapturedRenderingState.INSTANCE.getGbufferModelView());
		if (hasSafeZone) {
			return new SafeZoneCullingFrustum(projView, projection, lightVector, boxCuller, new BoxCuller(halfPlaneLength * renderMultiplier));
		}
		return new AdvancedShadowCullingFrustum(projView, projection, lightVector, boxCuller);
	}

	/**
	 * Iris ShadowRenderer.renderShadows, terrain: with Iris's shadow state set, Sodium builds the shadow render lists
	 * (Iris's ported shadow mixins keep them apart from the player's), then draws the opaque and, if the pack wants it,
	 * the translucent layer from the shadow view. Smart culling is off as in Iris.
	 */
	public static void drawTerrain(PackDirectives directives, View view, Camera camera, boolean translucent) {
		PackShadowDirectives s = directives.getShadowDirectives();
		Minecraft mc = Minecraft.getInstance();
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instance();
		ChunkRenderMatrices matrices = new ChunkRenderMatrices(view.projection(), view.modelView());
		Vector3d cam = CameraUniforms.getUnshiftedCameraPosition();
		GpuSampler sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, true);

		ShadowRenderer.MODELVIEW = new Matrix4f(view.modelView());
		ShadowRenderer.PROJECTION = new Matrix4f(view.projection());
		ShadowRenderer.FRUSTUM = view.frustum();
		ShadowRenderer.renderDistance = s.getDistanceRenderMul() < 0 ? IrisVideoSettings.shadowDistance
			: (int) ((s.getDistance() * s.getDistanceRenderMul()) / 16);
		boolean smartCull = mc.smartCull;
		ShadowRenderer.ACTIVE = true;
		try {
			mc.smartCull = false;
			RenderDevice.enterManagedCode();
			try {
				renderer.setupTerrain(camera, ((ViewportProvider) view.frustum()).sodium$createViewport(),
					((FogStorage) mc.gameRenderer).sodium$getFogParameters(), false, false, matrices);
			} finally {
				RenderDevice.exitManagedCode();
			}
			mc.smartCull = smartCull;
			if (s.shouldRenderTerrain()) drawLayer(renderer, ChunkSectionLayerGroup.OPAQUE, matrices, cam, sampler);
			if (translucent && s.shouldRenderTranslucent()) drawLayer(renderer, ChunkSectionLayerGroup.TRANSLUCENT, matrices, cam, sampler);
		} finally {
			mc.smartCull = smartCull;
			ShadowRenderer.ACTIVE = false;
		}
	}

	private static void drawLayer(SodiumWorldRenderer renderer, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, Vector3d cam, GpuSampler sampler) {
		RenderDevice.enterManagedCode();
		try {
			renderer.drawChunkLayer(group, matrices, cam.x, cam.y, cam.z, sampler);
		} finally {
			RenderDevice.exitManagedCode();
		}
	}
}
