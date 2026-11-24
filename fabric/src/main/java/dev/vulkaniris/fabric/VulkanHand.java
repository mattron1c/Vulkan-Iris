package dev.vulkaniris.fabric;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vulkaniris.fabric.mixin.GameRendererAccessorPort;
import net.irisshaders.iris.pathways.HandRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;

import java.util.function.BooleanSupplier;

/**
 * Iris's hand pass ({@code net.irisshaders.iris.pathways.HandRenderer}, LGPL-3.0, credit: the Iris team) for Polydium's
 * Vulkan backend, where Iris's own mixins that drive it are stripped. Same logic: the hands are drawn inside the world
 * (solid before translucent terrain, translucent at the end of the level) with the projection's depth scaled by 0.125 so
 * they don't clip into blocks, and vanilla's own hand pass after the level is skipped. Iris's
 * {@code HandRenderer.isActive()/isRenderingSolid()} are set while drawing, so IrisPipelines maps the hand's pipelines
 * to the gbuffers_hand programs and Vulkan Iris draws them with the pack (see VanillaHook).
 */
public final class VulkanHand {
	/** Duck interface added to ItemInHandRenderer by MixinItemInHandRendererPort (Iris's ItemInHandInterface). */
	public interface ItemInHand {
		void vulkaniris$renderHands(VulkanHand hand, float tickDelta, PoseStack poseStack, SubmitNodeStorage storage, LocalPlayer player, int light);
	}

	/** True while a pack draws the world (set by whoever drives the pack; the Phase 7 self-test today). */
	static volatile BooleanSupplier enabled = () -> false;

	private static VulkanHand instance;

	private final RenderBuffers bufferSource = new RenderBuffers(Runtime.getRuntime().availableProcessors());
	private final PerspectiveProjectionMatrixBuffer projection = new PerspectiveProjectionMatrixBuffer("hand (Vulkan Iris)");
	private final SubmitNodeStorage submitNodeCollector = new SubmitNodeStorage();
	private final FeatureRenderDispatcher featureRenderDispatcher;
	private boolean active, renderingSolid;

	private VulkanHand() {
		Minecraft mc = Minecraft.getInstance();
		featureRenderDispatcher = new FeatureRenderDispatcher(submitNodeCollector, mc.getBlockRenderer(), bufferSource.bufferSource(), mc.getAtlasManager(),
			bufferSource.outlineBufferSource(), bufferSource.crumblingBufferSource(), mc.font);
	}

	public static boolean enabled() {
		return enabled.getAsBoolean();
	}

	public static VulkanHand get() {
		if (instance == null) instance = new VulkanHand();
		return instance;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isRenderingSolid() {
		return renderingSolid;
	}

	private PoseStack setupState(GameRenderer gameRenderer, Camera camera, float tickDelta) {
		PoseStack poseStack = new PoseStack();
		Matrix4f scale = new Matrix4f().scale(1F, 1F, HandRenderer.DEPTH);
		scale.mul(gameRenderer.getProjectionMatrix(((GameRendererAccessorPort) gameRenderer).vulkaniris$getFov(camera, tickDelta, false)));
		RenderSystem.setProjectionMatrix(projection.getBuffer(scale), ProjectionType.PERSPECTIVE);
		poseStack.setIdentity();
		((GameRendererAccessorPort) gameRenderer).vulkaniris$bobHurt(poseStack, tickDelta);
		if (Minecraft.getInstance().options.bobView().get()) ((GameRendererAccessorPort) gameRenderer).vulkaniris$bobView(poseStack, tickDelta);
		return poseStack;
	}

	private static boolean canRender(Camera camera, GameRenderer gameRenderer) {
		Minecraft mc = Minecraft.getInstance();
		return !(camera.isDetached() || !(camera.entity() instanceof Player) || gameRenderer.isPanoramicMode() || mc.options.hideGui
			|| camera.entity() instanceof LivingEntity living && living.isSleeping() || mc.gameMode.getPlayerMode() == GameType.SPECTATOR);
	}

	public boolean isHandTranslucent(InteractionHand hand) {
		Item item = Minecraft.getInstance().player.getItemBySlot(hand == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND).getItem();
		return item instanceof BlockItem block
			&& ItemBlockRenderTypes.getChunkRenderType(block.getBlock().defaultBlockState()) == ChunkSectionLayer.TRANSLUCENT;
	}

	private boolean isAnyHandTranslucent() {
		return isHandTranslucent(InteractionHand.MAIN_HAND) || isHandTranslucent(InteractionHand.OFF_HAND);
	}

	public void renderSolid(float tickDelta, Camera camera, GameRenderer gameRenderer) {
		if (!canRender(camera, gameRenderer)) return;
		render(tickDelta, camera, gameRenderer, true);
	}

	public void renderTranslucent(float tickDelta, Camera camera, GameRenderer gameRenderer) {
		if (!canRender(camera, gameRenderer) || !isAnyHandTranslucent()) {
			submitNodeCollector.endFrame();
			return;
		}
		render(tickDelta, camera, gameRenderer, false);
		submitNodeCollector.endFrame();
	}

	private void render(float tickDelta, Camera camera, GameRenderer gameRenderer, boolean solid) {
		RenderSystem.backupProjectionMatrix();
		active = true;
		renderingSolid = solid;
		HandRenderer.vulkanActive = true;
		HandRenderer.vulkanSolid = solid;
		try {
			PoseStack poseStack = setupState(gameRenderer, camera, tickDelta);
			poseStack.pushPose();
			Profiler.get().push(solid ? "vulkaniris_hand" : "vulkaniris_hand_translucent");
			RenderSystem.getModelViewStack().pushMatrix();
			RenderSystem.getModelViewStack().set(poseStack.last().pose());
			Minecraft mc = Minecraft.getInstance();
			((ItemInHand) gameRenderer.itemInHandRenderer).vulkaniris$renderHands(this, tickDelta, new PoseStack(), submitNodeCollector, mc.player,
				mc.getEntityRenderDispatcher().getPackedLightCoords(camera.entity(), tickDelta));
			Profiler.get().pop();
			poseStack.popPose();
			RenderSystem.getModelViewStack().popMatrix();
		} finally {
			RenderSystem.restoreProjectionMatrix();
			active = false;
			renderingSolid = false;
			HandRenderer.vulkanActive = false;
			HandRenderer.vulkanSolid = false;
		}
	}

	/** ItemInHandRenderer.renderHandsWithItems' final flush, redirected here while this pass draws (Iris HandRenderer.endRender). */
	public void endRender() {
		featureRenderDispatcher.renderAllFeatures();
		bufferSource.bufferSource().endBatch();
	}
}
