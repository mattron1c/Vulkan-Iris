package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.FrameState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Same hook as Iris's MixinFogRenderer (stripped by Polydium on Vulkan). */
@Mixin(FogRenderer.class)
public class MixinFogRendererVulkan {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void vulkaniris$fog(Camera camera, int i, DeltaTracker deltaTracker, float f, ClientLevel level, CallbackInfoReturnable<Vector4f> cir) {
		FrameState.fog(camera, cir.getReturnValue());
	}
}
