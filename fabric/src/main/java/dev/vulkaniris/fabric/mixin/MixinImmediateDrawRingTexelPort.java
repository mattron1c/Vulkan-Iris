package dev.vulkaniris.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.polyfrost.polydium.client.perf.ImmediateDrawRing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Polydium uploads vanilla's immediate draws (entities, block entities, the hand, text) into one persistent ring buffer
 * (usage 98: vertex, index, map-write) instead of vanilla's per-format buffers. Pack vertex shaders read those vertices
 * back as a uniform texel buffer to compute Iris's extended attributes per quad (VulkanGlslTranslator.deriveQuadAttributes),
 * so the ring also asks for uniform-texel-buffer usage. Same size, memory and uploads.
 */
@Mixin(value = ImmediateDrawRing.class, remap = false)
public class MixinImmediateDrawRingTexelPort {
	@ModifyConstant(method = "upload", constant = @Constant(intValue = 98), remap = false)
	private static int vulkaniris$texelUsage(int usage) {
		return usage | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
	}
}
