// Copied from Iris's net.irisshaders.iris.mixin.MixinBiome (LGPL-3.0, the Iris team): Polydium strips Iris's copy on Vulkan,
// and Iris's biome uniforms (biome_category, rainfall) cast to the interface it adds. Applied only when Polydium is present.
package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.mixinterface.ExtendedBiome;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = Biome.class, priority = 990)
public class MixinBiomePort implements ExtendedBiome {
	@Shadow
	@Final
	private Biome.ClimateSettings climateSettings;
	@Unique
	private int biomeCategory = -1;

	@Override
	public int getBiomeCategory() {
		return biomeCategory;
	}

	@Override
	public void setBiomeCategory(int biomeCategory) {
		this.biomeCategory = biomeCategory;
	}

	@Override
	public float getDownfall() {
		return this.climateSettings.downfall();
	}
}
