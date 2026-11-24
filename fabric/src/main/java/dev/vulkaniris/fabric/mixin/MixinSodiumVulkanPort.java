package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.vulkaniris.fabric.TerrainHook;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polydium's terrain draw ({@code SodiumVulkan.draw}, private, called by {@code SodiumVulkan.render} after Sodium's
 * batches, index capacity and chunk data are prepared). When a shader pack's terrain programs are active, Vulkan Iris
 * draws the same batches with them instead. Only a call into Polydium's class: no Polydium code is copied.
 * Applied only when Polydium runs on Vulkan (see VulkanIrisMixinPlugin; the name ends in "Port" for that reason).
 */
@Mixin(targets = "org.polyfrost.polydium.vulkan.sodium.SodiumVulkan", remap = false)
public class MixinSodiumVulkanPort {
	@Inject(method = "draw", at = @At("HEAD"), cancellable = true, remap = false)
	private static void vulkaniris$draw(ChunkRenderListIterable lists, TerrainRenderPass pass, CameraTransform camera, boolean useRegionIndex,
										SharedQuadIndexBuffer sharedIndex, GpuSampler sampler, GpuBufferSlice globals, CallbackInfo ci) {
		if (TerrainHook.draw(lists, pass, camera, useRegionIndex, sharedIndex)) ci.cancel();
	}
}
