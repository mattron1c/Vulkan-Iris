package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.VanillaHook;
import org.polyfrost.polydium.vulkan.VulkanCommandEncoder;
import org.polyfrost.polydium.vulkan.compat.RenderPassBackend;
import org.polyfrost.polydium.vulkan.compat.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 7c: tells {@link VanillaHook} about each Polydium render pass as it is created, so passes drawing into
 * Minecraft's main target during the world can be redirected. Only hooks Polydium's class; nothing copied.
 * Applied only when Polydium runs on Vulkan ("Port" suffix).
 */
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public class MixinVulkanCommandEncoderPort {
	@Inject(method = "createRenderPass(Lorg/polyfrost/polydium/vulkan/compat/RenderPassDescriptor;)Lorg/polyfrost/polydium/vulkan/compat/RenderPassBackend;",
		at = @At("RETURN"), remap = false)
	private void vulkaniris$created(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPassBackend> cir) {
		VanillaHook.passCreated(cir.getReturnValue(), descriptor);
	}
}
