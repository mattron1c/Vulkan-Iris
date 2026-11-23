package dev.vulkaniris.vk;

/**
 * Minimal logging sink so the Vulkan backend does not depend on Minecraft's logging stack.
 * Inside the game, {@code common} adapts this to Iris's logger; in tests/probe it prints to stderr.
 */
public interface VkLog {
	void info(String message);

	void warn(String message);

	void error(String message);

	default void debug(String message) {
	}

	VkLog STDERR = new VkLog() {
		@Override
		public void info(String message) {
			System.err.println("[VulkanIris/INFO] " + message);
		}

		@Override
		public void warn(String message) {
			System.err.println("[VulkanIris/WARN] " + message);
		}

		@Override
		public void error(String message) {
			System.err.println("[VulkanIris/ERROR] " + message);
		}

		@Override
		public void debug(String message) {
			if (Boolean.getBoolean("vulkaniris.debug")) {
				System.err.println("[VulkanIris/DEBUG] " + message);
			}
		}
	};
}
