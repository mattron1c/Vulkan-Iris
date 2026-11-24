package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.PostChainCheck;
import dev.vulkaniris.fabric.VulkanHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris MixinLevelRenderer's hand injection points (LGPL-3.0, credit: the Iris team), for {@link VulkanHand}: the solid
 * hands at the main pass's second {@code endBatch} (after Iris's pre-hand depth copy for depthtex2), the translucent
 * hands at the end of the level. Applied only when Polydium strips Iris's mixins ("Port" suffix).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererHandPort {
	@Inject(method = "method_62214", require = 1,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1))
	private void vulkaniris$solidHand(CallbackInfo ci) {
		if (!VulkanHand.enabled()) return;
		PostChainCheck.beforeHand();
		Minecraft mc = Minecraft.getInstance();
		VulkanHand.get().renderSolid(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), mc.gameRenderer.getMainCamera(), mc.gameRenderer);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
	private void vulkaniris$translucentHand(CallbackInfo ci) {
		if (!VulkanHand.enabled()) return;
		Minecraft mc = Minecraft.getInstance();
		VulkanHand.get().renderTranslucent(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), mc.gameRenderer.getMainCamera(), mc.gameRenderer);
	}
}
