package dev.vulkaniris.fabric.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixins named {@code *Port} re-create Iris mixins that Polydium strips on Vulkan. They are applied only when Polydium
 * actually stripped Iris's mixins (Polydium sets {@code IrisVulkanGate.applied} in its own config plugin's
 * {@code onLoad}, which runs before any {@code shouldApplyMixin}). Otherwise Iris's own copies run and the ports would
 * duplicate them. If that flag cannot be read, Polydium being installed is used instead.
 */
public final class VulkanIrisMixinPlugin implements IMixinConfigPlugin {
	private boolean polydium;
	private Boolean stripped;

	@Override
	public void onLoad(String mixinPackage) {
		polydium = FabricLoader.getInstance().isModLoaded("polydium");
	}

	private boolean irisStripped() {
		if (stripped == null) {
			boolean value = polydium;
			if (polydium) {
				try {
					value = Class.forName("org.polyfrost.polydium.vulkan.IrisVulkanGate", true,
						VulkanIrisMixinPlugin.class.getClassLoader()).getField("applied").getBoolean(null);
				} catch (ReflectiveOperationException | LinkageError e) {
					System.err.println("[Vulkan Iris] could not read Polydium's IrisVulkanGate (" + e + "); assuming Iris mixins are stripped");
				}
			}
			stripped = value;
			System.out.println("[Vulkan Iris] Iris mixins stripped by Polydium: " + value + " (Port mixins " + (value ? "applied" : "skipped") + ")");
		}
		return stripped;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !mixinClassName.endsWith("Port") || irisStripped();
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
