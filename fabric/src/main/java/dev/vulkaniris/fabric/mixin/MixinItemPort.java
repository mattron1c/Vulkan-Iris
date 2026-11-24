package dev.vulkaniris.fabric.mixin;

import net.irisshaders.iris.api.v0.item.IrisItemLightProvider;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Same as Iris's MixinItem: gives every Item Iris's default held-light behaviour, which Iris's heldItem /
 * heldBlockLightValue uniforms cast to. Polydium strips Iris's copy on Vulkan; on OpenGL both apply the same
 * interface, which Mixin merges.
 */
@Mixin(Item.class)
public class MixinItemPort implements IrisItemLightProvider {
}
