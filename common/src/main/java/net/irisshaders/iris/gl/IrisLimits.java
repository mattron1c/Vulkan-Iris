package net.irisshaders.iris.gl;

public class IrisLimits {
	/**
	 * The maximum number of color textures that a shader pack can write to and read from in gbuffer and composite
	 * programs.
	 * <p>
	 * It's not recommended to raise this higher than 16 until code for avoiding allocation of unused color textures
	 * is implemented.
	 */
	public static final int MAX_COLOR_BUFFERS = 32;


	// Vulkan Iris: turned on when shaders are built for the Vulkan backend (explicit in/out locations, required by SPIR-V).
	// Set once at startup, before any pack loads, so TransformPatcher's cache never mixes both modes.
	public static volatile boolean VK_CONFORMANCE = Boolean.getBoolean("vulkaniris.vkConformance");
}
