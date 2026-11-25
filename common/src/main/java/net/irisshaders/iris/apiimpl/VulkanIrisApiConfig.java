package net.irisshaders.iris.apiimpl;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.config.IrisConfig;

import java.io.IOException;

/** Iris's API config ({@link IrisApiV0ConfigImpl}'s behaviour), in a class Polydium does not wrap; see IrisApiV0Impl. */
final class VulkanIrisApiConfig implements IrisApiConfig {
	@Override
	public boolean areShadersEnabled() {
		return Iris.getIrisConfig().areShadersEnabled();
	}

	@Override
	public void setShadersEnabledAndApply(boolean enabled) {
		IrisConfig config = Iris.getIrisConfig();
		config.setShadersEnabled(enabled);
		try {
			config.save();
		} catch (IOException e) {
			Iris.logger.error("Error saving configuration file!", e);
		}
		try {
			Iris.reload();
		} catch (IOException e) {
			Iris.logger.error("Error reloading shader pack while applying changes!", e);
		}
	}
}
