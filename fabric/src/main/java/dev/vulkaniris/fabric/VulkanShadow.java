package dev.vulkaniris.fabric;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vulkaniris.fabric.mixin.LevelRendererAccessorPort;
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
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Iterator;

/**
 * Phase 7d, CPU side of Iris's {@code ShadowRenderer.renderShadows} (LGPL-3.0, credit: the Iris team): the shadow
 * model-view and projection, Iris's shadow frustum choice ({@code createShadowFrustum}), then, with Iris's shadow state
 * set, Sodium's terrain setup and opaque layer, the entities and block entities (Iris's extract / submit / feature
 * dispatch, ported), and the translucent layer. Sodium's draws reach Polydium's {@code SodiumVulkan.draw}, which
 * {@link TerrainHook} hands to the pack's shadow programs; the entity draws reach Polydium render passes, which
 * {@link VanillaHook} hands to the pack's shadow entity programs (Iris IrisPipelines' shadow map). Sodium and Polydium
 * are only called (their public API), never copied.
 * <p>
 * Not done yet (reported by the caller): shadow mipmaps.
 */
public final class VulkanShadow {
	private VulkanShadow() {
	}

	/** The shadow view of one frame: Iris's matrices and the frustum Sodium culls sections with. */
	public record View(Matrix4f modelView, Matrix4f projection, Frustum frustum, String culling, boolean voxelization) {
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
		Frustum frustum = frustum(directives, s, voxelization, projection, culling, s.getDistanceRenderMul());
		Vector3d cam = CameraUniforms.getUnshiftedCameraPosition();
		frustum.prepare(cam.x, cam.y, cam.z);
		return new View(modelView, projection, frustum, culling[0], voxelization);
	}

	/** Iris ShadowRenderer.createShadowFrustum (terrain), with the same choices and the same culling description. */
	private static Frustum frustum(PackDirectives directives, PackShadowDirectives s, boolean voxelization, Matrix4f projection, String[] info,
								   float renderMultiplier) {
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
	public static void draw(PackDirectives directives, View view, Camera camera, CameraRenderState cameraState, boolean translucent) {
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
			// -Dvulkaniris.phase7.noentityshadow=true: A/B switch, terrain only
			if (!Boolean.getBoolean("vulkaniris.phase7.noentityshadow")) drawEntities(directives, view, camera, cameraState, cam);
			if (translucent && s.shouldRenderTranslucent()) drawLayer(renderer, ChunkSectionLayerGroup.TRANSLUCENT, matrices, cam, sampler);
		} finally {
			mc.smartCull = smartCull;
			ShadowRenderer.ACTIVE = false;
		}
	}

	// ---------------------------------------------------------------- entities and block entities (Iris ShadowRenderer)

	private static RenderBuffers buffers;
	private static LevelRenderState levelRenderState;
	private static SubmitNodeStorage submitNodeStorage;
	private static FeatureRenderDispatcher featureRenderDispatcher;
	private static PerspectiveProjectionMatrixBuffer projectionBuffer;
	static int renderedEntities, renderedBlockEntities;
	static String entityCulling = "";
	/** Self-test hooks around the entity draws (null in normal use). */
	static Runnable beforeEntities, afterEntities;

	/**
	 * Iris ShadowRenderer.renderShadows between the opaque and translucent terrain: the entities (or only the player) and
	 * block entities visible from the shadow view, submitted with the shadow model-view into the shadow pass's own submit
	 * storage and buffers, then drawn with the model-view stack at identity and the shadow projection set (Iris's
	 * IrisRenderSystem.setShadowProjection; its GL-side buffer isn't created on Vulkan, so a buffer of the same vanilla
	 * class is used here).
	 */
	private static void drawEntities(PackDirectives directives, View view, Camera camera, CameraRenderState cameraState, Vector3d cam) {
		PackShadowDirectives s = directives.getShadowDirectives();
		Minecraft mc = Minecraft.getInstance();
		if (buffers == null) {
			// Iris ShadowRenderer's constructor
			buffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
			levelRenderState = new LevelRenderState();
			submitNodeStorage = new SubmitNodeStorage();
			featureRenderDispatcher = new FeatureRenderDispatcher(submitNodeStorage, mc.getBlockRenderer(), buffers.bufferSource(), mc.getAtlasManager(),
				new OutlineBufferSource(), buffers.crumblingBufferSource(), mc.font);
			projectionBuffer = new PerspectiveProjectionMatrixBuffer("Vulkan Iris shadow map projection");
		}
		levelRenderState.cameraRenderState.blockPos = cameraState.blockPos;
		levelRenderState.cameraRenderState.pos = cameraState.pos;
		levelRenderState.cameraRenderState.orientation = cameraState.orientation;
		levelRenderState.cameraRenderState.entityPos = cameraState.entityPos;
		levelRenderState.cameraRenderState.initialized = cameraState.initialized;

		// a constrained frustum when the pack asks for a shorter entity shadow distance, else the terrain one
		float entityMul = s.getEntityShadowDistanceMul();
		Frustum entityFrustum = view.frustum();
		entityCulling = "same as terrain";
		if (entityMul != 1.0F && entityMul >= 0.0F) {
			String[] info = new String[1];
			entityFrustum = frustum(directives, s, view.voxelization(), view.projection(), info, s.getDistanceRenderMul() * entityMul);
			entityFrustum.prepare(cam.x, cam.y, cam.z);
			entityCulling = info[0];
		}
		levelRenderState.reset();
		if (s.shouldRenderEntities()) {
			extractVisibleEntities(camera, entityFrustum, mc.getDeltaTracker());
		} else if (s.shouldRenderPlayer()) {
			Player player = mc.player;
			float g = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			if (!player.isSpectator() && !player.isInvisible()) {
				levelRenderState.entityRenderStates.add(mc.getEntityRenderDispatcher().extractEntity(player, g));
			}
			if (player.getVehicle() != null) {
				levelRenderState.entityRenderStates.add(mc.getEntityRenderDispatcher().extractEntity(player.getVehicle(), g));
			}
		}

		PoseStack modelView = ShadowRenderer.createShadowModelView(directives.getSunPathRotation(), s.getIntervalSize(), s.getNearPlane(), s.getFarPlane());
		for (EntityRenderState state : levelRenderState.entityRenderStates) {
			mc.getEntityRenderDispatcher().submit(state, levelRenderState.cameraRenderState, state.x - cam.x, state.y - cam.y, state.z - cam.z,
				modelView, submitNodeStorage);
		}
		renderedEntities = levelRenderState.entityRenderStates.size();

		boolean lightsOnly = !s.shouldRenderBlockEntities() && s.shouldRenderLightBlockEntities();
		if (s.shouldRenderBlockEntities() || s.shouldRenderLightBlockEntities()) {
			((LevelRendererAccessorPort) mc.levelRenderer).vulkaniris$extractVisibleBlockEntities(camera, CapturedRenderingState.INSTANCE.getTickDelta(), levelRenderState);
			if (lightsOnly) {
				Iterator<BlockEntityRenderState> it = levelRenderState.blockEntityRenderStates.iterator();
				while (it.hasNext()) if (it.next().blockState.getLightEmission() == 0) it.remove();
			}
		}
		Vec3 pos = camera.position();
		for (BlockEntityRenderState state : levelRenderState.blockEntityRenderStates) {
			BlockPos p = state.blockPos;
			modelView.pushPose();
			modelView.translate(p.getX() - pos.x(), p.getY() - pos.y(), p.getZ() - pos.z());
			mc.getBlockEntityRenderDispatcher().submit(state, modelView, submitNodeStorage, levelRenderState.cameraRenderState);
			modelView.popPose();
		}
		renderedBlockEntities = levelRenderState.blockEntityRenderStates.size();

		if (beforeEntities != null) beforeEntities.run();
		GpuBufferSlice oldProjection = RenderSystem.getProjectionMatrixBuffer();
		ProjectionType oldType = RenderSystem.getProjectionType();
		RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(view.projection()), ProjectionType.ORTHOGRAPHIC);
		RenderSystem.getModelViewStack().pushMatrix();
		RenderSystem.getModelViewStack().identity();
		try {
			featureRenderDispatcher.renderAllFeatures();
			buffers.bufferSource().endBatch();
		} finally {
			RenderSystem.getModelViewStack().popMatrix();
			RenderSystem.setProjectionMatrix(oldProjection, oldType);
		}
		if (afterEntities != null) afterEntities.run();
	}

	/** Iris ShadowRenderer.extractVisibleEntities. */
	private static void extractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 vec3 = camera.position();
		double d = vec3.x(), e = vec3.y(), f = vec3.z();
		TickRateManager tickRateManager = mc.level.tickRateManager();
		Entity.setViewScale(Mth.clamp((double) mc.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * mc.options.entityDistanceScaling().get());
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof AbstractClientPlayer acp && acp.isSpectator()) continue;
			if (mc.getEntityRenderDispatcher().shouldRender(entity, frustum, d, e, f) || entity.hasIndirectPassenger(mc.player)) {
				BlockPos blockPos = entity.blockPosition();
				if (mc.level.isOutsideBuildHeight(blockPos.getY()) || mc.levelRenderer.isSectionCompiledAndVisible(blockPos)) {
					if (entity.tickCount == 0) {
						entity.xOld = entity.getX();
						entity.yOld = entity.getY();
						entity.zOld = entity.getZ();
					}
					float g = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
					levelRenderState.entityRenderStates.add(mc.getEntityRenderDispatcher().extractEntity(entity, g));
				}
			}
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
