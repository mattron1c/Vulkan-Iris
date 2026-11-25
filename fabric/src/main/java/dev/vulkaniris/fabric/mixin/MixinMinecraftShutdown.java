package dev.vulkaniris.fabric.mixin;

import dev.vulkaniris.fabric.PostChainCheck;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Live mode: Vulkan Iris's GPU objects are freed at the start of shutdown, before Polydium destroys its device. */
@Mixin(Minecraft.class)
public class MixinMinecraftShutdown {
	@Inject(method = "close", at = @At("HEAD"))
	private void vulkaniris$shutdown(CallbackInfo ci) {
		PostChainCheck.shutdown();
	}
}
