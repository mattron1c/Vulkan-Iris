package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.FirstLight;
import dev.vulkaniris.fabric.FrameState;
import dev.vulkaniris.fabric.PackCompileCheck;
import dev.vulkaniris.fabric.PostChainCheck;
import dev.vulkaniris.fabric.ResourceCheck;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lives outside {@code net.irisshaders.iris.*} on purpose: Polydium strips every mixin config in that package when
 * it runs on Vulkan (its IrisVulkanGate), which would silently remove this hook.
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererFirstLight {
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void vulkaniris$startFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		FrameState.startFrame(deltaTracker);
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void vulkaniris$firstLight(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		FirstLight.afterGameRender();
		ResourceCheck.afterGameRender();
		PackCompileCheck.afterGameRender();
		PostChainCheck.afterGameRender();
	}
}
