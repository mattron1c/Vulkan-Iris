package dev.vulkaniris.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;

/** Vulkan Iris's own client entrypoint (Iris itself initialises from mixins). */
public final class VulkanIrisClient implements ClientModInitializer {
	/** True when Polydium removed Iris's mixins (it then runs on Vulkan, and Vulkan Iris draws the shader pack). */
	static boolean irisStripped;

	@Override
	public void onInitializeClient() {
		String version = FabricLoader.getInstance().getModContainer("vulkaniris")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		boolean polydium = FabricLoader.getInstance().isModLoaded("polydium");
		boolean vulkanmod = FabricLoader.getInstance().isModLoaded("vulkanmod");
		irisStripped = polydium && irisVulkanGate();
		Iris.logger.info("[Vulkan Iris] " + version + " — Vulkan owners present: polydium=" + polydium + ", vulkanmod=" + vulkanmod
			+ ", Iris mixins stripped (Vulkan mode): " + irisStripped + (FirstLight.ENABLED ? " — first-light test ENABLED" : ""));
		if (irisStripped) {
			// Iris's early init (config, key bindings) runs from a mixin Polydium removed: do it here, before options load
			Iris.initializeForVulkan();
			Iris.registerKeybindsForVulkan();
		}
	}

	/** Polydium's own flag: set when its mixin plugin removed Iris's mixins because it runs on Vulkan. */
	private static boolean irisVulkanGate() {
		try {
			return Class.forName("org.polyfrost.polydium.vulkan.IrisVulkanGate", true, VulkanIrisClient.class.getClassLoader())
				.getField("applied").getBoolean(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			Iris.logger.warn("[Vulkan Iris] could not read Polydium's IrisVulkanGate (" + e + "); assuming Iris runs on Vulkan");
			return true;
		}
	}
}
