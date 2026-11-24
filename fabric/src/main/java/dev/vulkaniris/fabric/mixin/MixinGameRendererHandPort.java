package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vulkaniris.fabric.VulkanHand;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iris MixinGameRenderer#iris$disableVanillaHandRendering (LGPL-3.0, credit: the Iris team): while a pack draws the
 * world, the hands were already drawn inside it by {@link VulkanHand}, so vanilla's own hand pass after the level is
 * skipped. Applied only when Polydium strips Iris's mixins ("Port" suffix).
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererHandPort {
	@Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
	private void vulkaniris$skipVanillaHands(ItemInHandRenderer renderer, float tickDelta, PoseStack poseStack, SubmitNodeCollector collector,
											 LocalPlayer player, int light) {
		if (VulkanHand.enabled()) return;
		renderer.renderHandsWithItems(tickDelta, poseStack, collector, player, light);
	}
}
