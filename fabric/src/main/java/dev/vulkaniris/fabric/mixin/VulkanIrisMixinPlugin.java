package dev.vulkaniris.fabric.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixins named {@code *Port} re-create Iris state mixins that Polydium strips on Vulkan. They are applied only when
 * Polydium is installed; without it Iris's own copies run and the ports would just duplicate them.
 */
public final class VulkanIrisMixinPlugin implements IMixinConfigPlugin {
	private boolean polydium;

	@Override
	public void onLoad(String mixinPackage) {
		polydium = FabricLoader.getInstance().isModLoaded("polydium");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !mixinClassName.endsWith("Port") || polydium;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
