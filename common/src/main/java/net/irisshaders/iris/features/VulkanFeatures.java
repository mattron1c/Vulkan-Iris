package net.irisshaders.iris.features;

/** Vulkan Iris: device facts {@link FeatureFlags} needs on the Vulkan backend, set by Vulkan Iris's Polydium hooks. */
public final class VulkanFeatures {
	/** Whether Polydium's device has independentBlend (set while the device is created). */
	public static volatile boolean independentBlend = true;

	private VulkanFeatures() {
	}
}
