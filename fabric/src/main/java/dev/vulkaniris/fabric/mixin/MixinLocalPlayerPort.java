// Copied from Iris's net.irisshaders.iris.mixin.MixinLocalPlayer (LGPL-3.0, the Iris team): Polydium strips Iris's copy on Vulkan,
// and Iris uniforms (e.g. playerMood) cast to the interface it adds. Applied only when Polydium is present.
package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.mixinterface.BiomeAmbienceInterface;
import net.irisshaders.iris.mixinterface.LocalPlayerInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AmbientSoundHandler;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(LocalPlayer.class)
public class MixinLocalPlayerPort implements LocalPlayerInterface {
	@Shadow
	@Final
	private List<AmbientSoundHandler> ambientSoundHandlers;

	@Override
	public float getCurrentConstantMood() {
		for (AmbientSoundHandler ambientSoundHandler : this.ambientSoundHandlers) {
			if (ambientSoundHandler instanceof BiomeAmbientSoundsHandler) {
				return ((BiomeAmbienceInterface) ambientSoundHandler).getConstantMood();
			}
		}

		return 0.0F;
	}
}
