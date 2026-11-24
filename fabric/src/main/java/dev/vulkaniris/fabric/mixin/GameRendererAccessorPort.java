package dev.vulkaniris.fabric.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vulkan Iris's own copy of the one GameRendererAccessor call Iris's uniforms need (Iris's accessor is stripped on Vulkan). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessorPort {
	@Invoker("shouldRenderBlockOutline")
	boolean vulkaniris$shouldRenderBlockOutline();
}
