/*
 * Copied from Iris (net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionArenas, LGPL-3.0-only, credit: the Iris team).
 * Polydium strips Iris's Sodium mixins on Vulkan; Vulkan Iris re-applies this CPU-side chunk meshing code there
 * (only when Polydium runs on Vulkan, see VulkanIrisMixinPlugin) so terrain carries Iris's extended vertex data.
 */
package dev.vulkaniris.fabric.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderRegion.DeviceResources.class)
public class MixinRenderRegionArenasPort {
	@Redirect(method = "<init>", remap = false,
		at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;COMPACT:Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;",
			remap = false))
	private ChunkVertexType iris$useExtendedStride() {
		return WorldRenderingSettings.INSTANCE.getVertexFormat();
	}
}
