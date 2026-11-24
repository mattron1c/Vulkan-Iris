/*
 * Copied from Iris (net.irisshaders.iris.compat.sodium.mixin.MixinChunkVertexConsumer, LGPL-3.0-only, credit: the Iris team).
 * Polydium strips Iris's Sodium mixins on Vulkan; Vulkan Iris re-applies this CPU-side chunk meshing code there
 * (only when Polydium runs on Vulkan, see VulkanIrisMixinPlugin) so terrain carries Iris's extended vertex data.
 */
package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkVertexConsumer;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ChunkVertexConsumer.class, remap = false)
public class MixinChunkVertexConsumerPort implements BlockSensitiveBufferBuilder {
	@Shadow
	@Final
	private ChunkModelBuilder modelBuilder;

	@Override
	public void beginBlock(int block, byte renderType, byte blockEmission, int localPosX, int localPosY, int localPosZ) {
		((BlockSensitiveBufferBuilder) modelBuilder).beginBlock(block, renderType, blockEmission, localPosX, localPosY, localPosZ);
	}

	@Override
	public void overrideBlock(int block) {
		((BlockSensitiveBufferBuilder) modelBuilder).overrideBlock(block);
	}

	@Override
	public void restoreBlock() {
		((BlockSensitiveBufferBuilder) modelBuilder).restoreBlock();
	}

	@Override
	public void endBlock() {
		((BlockSensitiveBufferBuilder) modelBuilder).endBlock();
	}

	@Override
	public void ignoreMidBlock(boolean b) {
		((BlockSensitiveBufferBuilder) modelBuilder).ignoreMidBlock(b);
	}
}
