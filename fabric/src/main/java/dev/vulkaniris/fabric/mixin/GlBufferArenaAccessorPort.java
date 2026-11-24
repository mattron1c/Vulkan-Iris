package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.gl.arena.GlBufferArena;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The vertex stride a region's geometry arena was created with (Polydium on Vulkan only, see VulkanIrisMixinPlugin). */
@Mixin(value = GlBufferArena.class, remap = false)
public interface GlBufferArenaAccessorPort {
	@Accessor(value = "stride", remap = false)
	int vulkaniris$stride();
}
