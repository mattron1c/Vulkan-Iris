package net.irisshaders.iris.features;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.minecraft.client.resources.language.I18n;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public enum FeatureFlags {
	SEPARATE_HARDWARE_SAMPLERS(() -> true, () -> true),
	HIGHER_SHADOWCOLOR(() -> true, () -> true),
	// Vulkan Iris: on the Vulkan backend the answers are what Vulkan Iris implements, not GL queries (there is no GL
	// context; IrisRenderSystem is GL-only)
	CUSTOM_IMAGES(() -> true, () -> vulkan() || IrisRenderSystem.supportsImageLoadStore()),
	PER_BUFFER_BLENDING(() -> true, () -> vulkan() ? VulkanFeatures.independentBlend : IrisRenderSystem.supportsBufferBlending()),
	COMPUTE_SHADERS(() -> true, () -> vulkan() || IrisRenderSystem.supportsCompute()),
	// tessellation programs aren't implemented in Vulkan Iris yet
	TESSELLATION_SHADERS(() -> !vulkan(), () -> vulkan() || IrisRenderSystem.supportsTesselation()),
	ENTITY_TRANSLUCENT(() -> true, () -> true),
	REVERSED_CULLING(() -> true, () -> true),
	BLOCK_EMISSION_ATTRIBUTE(() -> true, () -> true),
	CAN_DISABLE_WEATHER(() -> true, () -> true),
	SSBO(() -> true, () -> vulkan() || IrisRenderSystem.supportsSSBO()),
	FADE_VARIABLE(() -> true, () -> true),
	TEXTURE_FILTERING(() -> true, () -> true),
	UNKNOWN(() -> false, () -> false);

	private static boolean vulkan() {
		return net.irisshaders.iris.gl.shader.StandardMacros.isVulkanBackend();
	}

	private final BooleanSupplier irisRequirement;
	private final BooleanSupplier hardwareRequirement;

	FeatureFlags(BooleanSupplier irisRequirement, BooleanSupplier hardwareRequirement) {
		this.irisRequirement = irisRequirement;
		this.hardwareRequirement = hardwareRequirement;
	}

	public static String getInvalidStatus(List<FeatureFlags> invalidFeatureFlags) {
		boolean unsupportedHardware = false, unsupportedIris = false;
		FeatureFlags[] flags = invalidFeatureFlags.toArray(new FeatureFlags[0]);
		for (FeatureFlags flag : flags) {
			unsupportedIris |= !flag.irisRequirement.getAsBoolean();
			unsupportedHardware |= !flag.hardwareRequirement.getAsBoolean();
		}

		if (unsupportedIris) {
			if (unsupportedHardware) {
				return I18n.get("iris.unsupported.irisorpc");
			}

			return I18n.get("iris.unsupported.iris");
		} else if (unsupportedHardware) {
			return I18n.get("iris.unsupported.pc");
		} else {
			return null;
		}
	}

	public static boolean isInvalid(String name) {
		try {
			return !FeatureFlags.valueOf(name.toUpperCase(Locale.US)).isUsable();
		} catch (IllegalArgumentException e) {
			return true;
		}
	}

	public static FeatureFlags getValue(String value) {
		if (value.equalsIgnoreCase("TESSELATION_SHADERS")) {
			// fix the sins of the past
			value = "TESSELLATION_SHADERS";
		}

		try {
			return FeatureFlags.valueOf(value.toUpperCase(Locale.US));
		} catch (IllegalArgumentException e) {
			return FeatureFlags.UNKNOWN;
		}
	}

	public String getHumanReadableName() {
		return StringUtils.capitalize(name().replace("_", " ").toLowerCase());
	}

	public boolean isUsable() {
		return irisRequirement.getAsBoolean() && hardwareRequirement.getAsBoolean();
	}
}
