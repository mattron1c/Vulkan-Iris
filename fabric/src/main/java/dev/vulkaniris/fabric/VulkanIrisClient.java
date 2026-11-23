package dev.vulkaniris.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;

/** Vulkan Iris's own client entrypoint (Iris itself initialises from mixins). */
public final class VulkanIrisClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		String version = FabricLoader.getInstance().getModContainer("vulkaniris")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		boolean polydium = FabricLoader.getInstance().isModLoaded("polydium");
		boolean vulkanmod = FabricLoader.getInstance().isModLoaded("vulkanmod");
		Iris.logger.info("[Vulkan Iris] " + version + " — Vulkan owners present: polydium=" + polydium + ", vulkanmod=" + vulkanmod
			+ (FirstLight.ENABLED ? " — first-light test ENABLED" : ""));
	}
}
