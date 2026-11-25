package dev.vulkaniris.fabric.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vulkan Iris's own copy of the LevelRendererAccessor call Iris's shadow pass needs (Iris's accessor is stripped on Vulkan):
 * ShadowRenderer.extractVisibleBlockEntities collects the block entities of the sections visible from the shadow view.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessorPort {
	@Invoker("extractVisibleBlockEntities")
	void vulkaniris$extractVisibleBlockEntities(Camera camera, float tickDelta, LevelRenderState levelRenderState);
}
