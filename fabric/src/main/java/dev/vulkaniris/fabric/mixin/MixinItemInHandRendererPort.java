package dev.vulkaniris.fabric.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vulkaniris.fabric.VulkanHand;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Port of Iris's {@code MixinItemInHandRenderer} (LGPL-3.0, credit: the Iris team) for {@link VulkanHand}: draws the hands
 * through VulkanHand's own submit storage and buffers, and skips the hands that belong to the other (solid/translucent)
 * pass. Applied only when Polydium strips Iris's mixins ("Port" suffix).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRendererPort implements VulkanHand.ItemInHand {
	@Shadow
	public abstract void renderHandsWithItems(float f, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LocalPlayer localPlayer, int i);

	@Unique
	private VulkanHand vulkaniris$customRenderer;

	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void vulkaniris$skipOtherPassHands(AbstractClientPlayer player, float f, float g, InteractionHand hand, float h, ItemStack stack, float i,
											   PoseStack poseStack, SubmitNodeCollector collector, int j, CallbackInfo ci) {
		VulkanHand r = vulkaniris$customRenderer;
		if (r == null) return;
		if (r.isRenderingSolid() == r.isHandTranslucent(hand)) ci.cancel();
	}

	@Override
	public void vulkaniris$renderHands(VulkanHand hand, float tickDelta, PoseStack poseStack, SubmitNodeStorage storage, LocalPlayer player, int light) {
		vulkaniris$customRenderer = hand;
		try {
			this.renderHandsWithItems(tickDelta, poseStack, storage, player, light);
		} finally {
			vulkaniris$customRenderer = null;
		}
	}

	@WrapWithCondition(method = "renderHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V"))
	private boolean vulkaniris$wrapHand(FeatureRenderDispatcher instance) {
		return vulkaniris$customRenderer == null;
	}

	@WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"))
	private void vulkaniris$wrapHand2(MultiBufferSource.BufferSource instance, Operation<Void> original) {
		if (vulkaniris$customRenderer == null) original.call(instance);
		else vulkaniris$customRenderer.endRender();
	}
}
