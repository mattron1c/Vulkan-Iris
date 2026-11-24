/*
 * Copied from Iris (net.irisshaders.iris.mixin.fabric.MixinFluidRendererImpl, LGPL-3.0-only, credit: the Iris team).
 * Re-applied on Polydium Vulkan, where Polydium strips Iris's mixins (see VulkanIrisMixinPlugin).
 */
package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl;
import net.irisshaders.iris.vertices.sodium.terrain.VertexEncoderInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FluidRendererImpl.class)
public class MixinFluidRendererImplPort implements VertexEncoderInterface {
	@Shadow
	@Final
	private DefaultFluidRenderer defaultRenderer;

	@Override
	public void beginBlock(int blockId, byte isFluid, byte lightEmission, int x, int y, int z) {
		((VertexEncoderInterface) this.defaultRenderer).beginBlock(blockId, isFluid, lightEmission, x, y, z);
	}

	@Override
	public void overrideBlock(int anInt) {
		((VertexEncoderInterface) this.defaultRenderer).overrideBlock(anInt);
	}

	@Override
	public void restoreBlock() {
		((VertexEncoderInterface) this.defaultRenderer).restoreBlock();
	}
}
