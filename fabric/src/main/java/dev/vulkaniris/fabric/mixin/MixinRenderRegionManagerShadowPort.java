package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.irisshaders.iris.mixinterface.ShadowRenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Port of Iris's {@code net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionManager} (LGPL-3.0, credit: the Iris team), unchanged apart from its name:
 * Sodium's separate shadow render lists and batches, keyed on Iris's {@code ShadowRenderingState}. Polydium strips Iris's
 * own mixins, so Vulkan Iris applies this copy ("Port" suffix: only when Polydium runs on Vulkan) for the 7d shadow pass.
 */
@Mixin(RenderRegionManager.class)
public class MixinRenderRegionManagerShadowPort {
	@Redirect(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;clearAllCachedBatches()V"))
	private void iris$forceClear(RenderRegion instance) {
		((ShadowRenderRegion) instance).iris$forceClearAllBatches();
	}
}
