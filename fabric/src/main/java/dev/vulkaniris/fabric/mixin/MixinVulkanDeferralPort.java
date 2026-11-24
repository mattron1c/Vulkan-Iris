package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.VanillaHook;
import org.polyfrost.polydium.client.perf.VulkanDeferral;
import org.polyfrost.polydium.vulkan.VulkanRenderPass;
import org.polyfrost.polydium.vulkan.compat.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 7c: Polydium merges consecutive render passes on the same targets. A closed pass's rendering is kept open
 * ({@code VulkanDeferral.suspendPass}) and the next pass on those targets is a new {@code VulkanRenderPass} built by
 * {@code continueSuspendedPass(descriptor)} instead of {@code VulkanCommandEncoder.createRenderPass(descriptor)} (Polydium
 * wraps that call). Those passes are reported to {@link VanillaHook} too; a null return means "not continued" and
 * Polydium then creates the pass normally. Only hooks Polydium's class; nothing copied. Applied only on Polydium Vulkan.
 */
@Mixin(value = VulkanDeferral.class, remap = false)
public class MixinVulkanDeferralPort {
	@Inject(method = "continueSuspendedPass", at = @At("RETURN"), remap = false)
	private void vulkaniris$continued(RenderPassDescriptor descriptor, CallbackInfoReturnable<VulkanRenderPass> cir) {
		if (cir.getReturnValue() != null) VanillaHook.passCreated(cir.getReturnValue(), descriptor);
	}
}
