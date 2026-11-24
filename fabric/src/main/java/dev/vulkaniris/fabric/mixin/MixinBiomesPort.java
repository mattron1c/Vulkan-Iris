// Copied from Iris's net.irisshaders.iris.mixin.MixinBiomes (LGPL-3.0, the Iris team): Polydium strips Iris's copy on Vulkan;
// it numbers the vanilla biomes for the BIOME_* constants packs use in custom uniforms. Applied only when Polydium is present.
package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.uniforms.BiomeUniforms;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biomes.class)
public class MixinBiomesPort {
	@Unique
	private static int currentId = 0;

	@Inject(method = "register", at = @At("TAIL"))
	private static void vulkaniris$registerBiome(String string, CallbackInfoReturnable<ResourceKey<Biome>> cir) {
		BiomeUniforms.getBiomeMap().put(cir.getReturnValue(), currentId++);
	}
}
