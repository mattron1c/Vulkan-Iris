package dev.vulkaniris.fabric.mixin;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.polyfrost.polydium.vulkan.VulkanBackend;
import org.polyfrost.polydium.vulkan.VulkanPhysicalDevice;
import org.polyfrost.polydium.vulkan.init.VulkanFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;

/**
 * Shader packs set blend modes per colour buffer ({@code blend.<program>.<buffer>}), which on Vulkan needs the
 * {@code independentBlend} device feature. Polydium creates the device and does not request it, so this adds it to
 * Polydium's feature list when the GPU supports it (practically every desktop GPU). If the GPU does not, nothing is
 * added and Vulkan Iris falls back to one blend state per pipeline ({@code GraphicsProgram.sharedBlendPrograms}).
 */
@Mixin(value = VulkanBackend.class, remap = false)
public class MixinPolydiumDeviceFeatures {
	@Inject(method = "requiredFeatures", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private static void vulkaniris$independentBlend(VulkanPhysicalDevice device, CallbackInfoReturnable<Set<VulkanFeature>> cir) {
		boolean supported;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures f = VkPhysicalDeviceFeatures.calloc(stack);
			vkGetPhysicalDeviceFeatures(device.vkPhysicalDevice(), f);
			supported = f.independentBlend();
		}
		dev.vulkaniris.fabric.PolydiumAccess.independentBlendEnabled = supported;
		net.irisshaders.iris.features.VulkanFeatures.independentBlend = supported;
		if (!supported) return;
		Set<VulkanFeature> features = new HashSet<>(cir.getReturnValue());
		features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "independentBlend", VkPhysicalDeviceFeatures.INDEPENDENTBLEND));
		cir.setReturnValue(features);
	}
}
