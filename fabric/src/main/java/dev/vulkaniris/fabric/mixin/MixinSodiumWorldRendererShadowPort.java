package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Port of Iris's {@code net.irisshaders.iris.compat.sodium.mixin.MixinSodiumWorldRenderer}
 * (LGPL-3.0, credit: the Iris team), unchanged apart from its name: in the shadow pass the section graph is rebuilt when
 * the sun moved, and the end-of-setup rebuild is skipped, and Sodium's
 * entity-visibility check (camera based) is skipped, the shadow frustum having culled entities already. Applied only when Polydium runs on Vulkan ("Port" suffix).
 */
@Mixin(SodiumWorldRenderer.class)
public class MixinSodiumWorldRendererShadowPort {
	@Unique
	private float vulkaniris$lastSunAngle;

	@Redirect(method = "setupTerrain", remap = false,
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;needsUpdate()Z", ordinal = 0,
			remap = false))
	private boolean vulkaniris$forceChunkGraphRebuildInShadowPass(RenderSectionManager instance) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			float sunAngle = Minecraft.getInstance().gameRenderer.getMainCamera().attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, CapturedRenderingState.INSTANCE.getTickDelta());
			if (vulkaniris$lastSunAngle != sunAngle) {
				vulkaniris$lastSunAngle = sunAngle;
				return true;
			}
		}

		return instance.needsUpdate();
	}

	@Redirect(method = "setupTerrain", remap = false,
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;needsUpdate()Z", ordinal = 1,
			remap = false))
	private boolean vulkaniris$forceEndGraphRebuild(RenderSectionManager instance) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		} else {
			return instance.needsUpdate();
		}
	}

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private <T extends Entity, S extends EntityRenderState> void vulkaniris$skipEntityCheck(EntityRenderer<T, S> renderer, T entity, CallbackInfoReturnable<Boolean> cir) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) cir.setReturnValue(true);
	}
}
