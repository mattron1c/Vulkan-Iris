package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.vulkaniris.fabric.FrameState;
import dev.vulkaniris.fabric.PostChainCheck;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same injection point and signature as Iris's MixinLevelRenderer#iris$setupPipeline (stripped by Polydium on Vulkan). */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererVulkan {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void vulkaniris$beginLevel(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean bl, Camera camera, Matrix4f modelView,
									   Matrix4f projection, Matrix4f matrix4f3, GpuBufferSlice fog, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
		FrameState.beginLevel(deltaTracker, modelView, projection);
		PostChainCheck.beforeLevel();
	}

	/** Iris MixinLevelRenderer#iris$renderTerrainShadows: the shadow pass, then prepare, before the main terrain setup. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/client/renderer/culling/Frustum;"))
	private void vulkaniris$shadows(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean bl, Camera camera, Matrix4f modelView,
									Matrix4f projection, Matrix4f matrix4f3, GpuBufferSlice fog, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
		PostChainCheck.afterCullFrustum(camera);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void vulkaniris$endLevel(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean bl, Camera camera, Matrix4f modelView,
									 Matrix4f projection, Matrix4f matrix4f3, GpuBufferSlice fog, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
		PostChainCheck.afterLevel();
	}
}
