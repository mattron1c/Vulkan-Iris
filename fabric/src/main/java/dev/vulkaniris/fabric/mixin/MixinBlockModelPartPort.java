/*
 * Copied from Iris (net.irisshaders.iris.mixin.MixinBlockModelPart, LGPL-3.0-only, credit: the Iris team).
 * Polydium strips Iris's mixins on Vulkan; the ported chunk meshing mixins cast model parts to IrisModelPart.
 */
package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.compat.general.IrisModelPart;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockModelPart.class)
public interface MixinBlockModelPartPort extends IrisModelPart {
}
