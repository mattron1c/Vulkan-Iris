package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Polydium, when it runs on Vulkan, replaces every way into Iris's shader pack screen (Iris's O key, which it registers
 * itself, Iris's Mod Menu and Sodium options entries) with {@code IrisVulkan.placeholder}, a "switch to OpenGL?"
 * prompt, because Iris has no Vulkan renderer. Vulkan Iris is that renderer, so the placeholder opens Iris's real
 * shader pack screen instead, and Polydium's "Vulkan removes shaders" warning says what actually happens.
 * Polydium's code is not copied: this only changes what its methods return.
 */
@Pseudo
@Mixin(targets = "org.polyfrost.polydium.vulkan.IrisVulkan", remap = false)
public class MixinPolydiumIrisScreen {
	@Inject(method = "placeholder", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private static void vulkaniris$shaderPackScreen(Screen parent, CallbackInfoReturnable<Screen> cir) {
		cir.setReturnValue(new ShaderPackScreen(parent));
	}

	@ModifyConstant(method = "selectGraphicsApi", constant = @Constant(stringValue = "Setting the backend to Vulkan will remove your ability to use shaders.\nWould you like to proceed?"),
		remap = false, require = 0)
	private static String vulkaniris$vulkanWarning(String original) {
		return "On Vulkan, shader packs are drawn by Vulkan Iris. Most packs work; a few pack features are not supported yet"
			+ " (see the Vulkan Iris page).\nWould you like to proceed?";
	}
}
