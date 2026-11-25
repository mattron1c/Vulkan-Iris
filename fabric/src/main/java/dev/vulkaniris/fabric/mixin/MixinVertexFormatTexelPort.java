package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla's immediate vertex buffers (entities, block entities, the hand, text) are created with vertex usage only. The
 * vertex shaders of pack programs read them back as a uniform texel buffer to compute Iris's extended attributes per quad
 * (VulkanGlslTranslator.deriveQuadAttributes), so they also ask for uniform-texel-buffer usage. Nothing else changes:
 * same size, same memory, same uploads.
 */
@Mixin(VertexFormat.class)
public class MixinVertexFormatTexelPort {
	@ModifyVariable(method = "uploadToBuffer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static int vulkaniris$texelUsage(int usage) {
		return (usage & GpuBuffer.USAGE_VERTEX) != 0 ? usage | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER : usage;
	}
}
