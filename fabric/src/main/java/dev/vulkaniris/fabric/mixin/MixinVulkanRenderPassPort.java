package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.vulkaniris.fabric.VanillaHook;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.polyfrost.polydium.vulkan.VulkanRenderPass;
import org.polyfrost.polydium.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;

/**
 * Phase 7c: Polydium's vanilla render pass. Its two leaf draw calls ({@code drawIndexed(IIIII)}, {@code draw(IIII)};
 * every other non-indirect draw delegates to them) are offered to {@link VanillaHook}, which draws them with the pack's
 * gbuffers program when one maps. Every other recording path first gives the pass back to Polydium. Only hooks into
 * Polydium's class — no Polydium code is copied. Applied only when Polydium runs on Vulkan ("Port" suffix).
 */
@Mixin(value = VulkanRenderPass.class, remap = false)
public abstract class MixinVulkanRenderPassPort {
	@Shadow
	protected VulkanRenderPipeline pipeline;
	@Shadow
	@Final
	protected HashMap<String, GpuBufferSlice> uniforms;
	@Shadow
	@Final
	protected HashMap<String, VulkanRenderPass.TextureViewAndSampler> textures;
	@Shadow
	private boolean anyDescriptorDirty;
	@Shadow
	@Final
	private boolean hasDepth;
	@Shadow
	@Final
	private int outputWidth;
	@Shadow
	@Final
	private int outputHeight;

	@Shadow
	protected abstract VkCommandBuffer commandBuffer();

	private VulkanRenderPass vulkaniris$self() {
		return (VulkanRenderPass) (Object) this;
	}

	private void vulkaniris$offer(boolean indexed, int a, int b, int c, int d, int e, CallbackInfo ci) {
		int r = VanillaHook.draw(vulkaniris$self(), commandBuffer(), pipeline, uniforms, textures, hasDepth, outputWidth, outputHeight,
			indexed, a, b, c, d, e);
		if (r == VanillaHook.HANDLED) ci.cancel();
		else if (r == VanillaHook.RESUMED) anyDescriptorDirty = true;
	}

	private void vulkaniris$resume() {
		if (VanillaHook.resume(vulkaniris$self(), commandBuffer(), pipeline, hasDepth)) anyDescriptorDirty = true;
	}

	@Inject(method = "drawIndexed(IIIII)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void vulkaniris$drawIndexed(int a, int b, int c, int d, int e, CallbackInfo ci) {
		vulkaniris$offer(true, a, b, c, d, e, ci);
	}

	@Inject(method = "draw(IIII)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void vulkaniris$draw(int a, int b, int c, int d, CallbackInfo ci) {
		vulkaniris$offer(false, a, b, c, d, 0, ci);
	}

	@Inject(method = {"multiDrawIndexed(Ljava/nio/IntBuffer;III)V", "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
		"multiDraw(Ljava/nio/IntBuffer;III)V", "drawIndexedIndirect", "pushConstants"}, at = @At("HEAD"), remap = false)
	private void vulkaniris$other(CallbackInfo ci) {
		vulkaniris$resume();
	}

	@Inject(method = "close", at = @At("HEAD"), remap = false)
	private void vulkaniris$close(CallbackInfo ci) {
		VanillaHook.close(vulkaniris$self(), commandBuffer(), pipeline, hasDepth);
	}

	@Inject(method = "enableScissor", at = @At("TAIL"), remap = false)
	private void vulkaniris$scissor(int x, int y, int w, int h, CallbackInfo ci) {
		VanillaHook.scissor(vulkaniris$self(), x, y, w, h);
	}
	// disableScissor() calls enableScissor(renderArea or 0,0,outputWidth,outputHeight), so the hook above covers it.
}
