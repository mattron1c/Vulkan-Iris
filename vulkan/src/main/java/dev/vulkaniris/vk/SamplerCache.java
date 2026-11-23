package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The handful of immutable samplers shader packs need, created once. Iris on GL sets filter/wrap per texture object;
 * Vulkan separates that into sampler objects, so packs' colortex/shadowtex/noisetex modes map onto these keys.
 */
public final class SamplerCache implements AutoCloseable {
	public enum Filter { NEAREST, LINEAR }

	public enum Address { CLAMP, REPEAT }

	/** {@code shadowCompare}: hardware depth comparison (GL's {@code sampler2DShadow} / {@code shadowHardwareFiltering}). */
	public record Key(Filter filter, Address address, boolean mipmaps, boolean shadowCompare) {
	}

	public static final Key NEAREST_CLAMP = new Key(Filter.NEAREST, Address.CLAMP, false, false);
	public static final Key LINEAR_CLAMP = new Key(Filter.LINEAR, Address.CLAMP, false, false);
	public static final Key LINEAR_REPEAT_MIPS = new Key(Filter.LINEAR, Address.REPEAT, true, false);
	public static final Key SHADOW_PCF = new Key(Filter.LINEAR, Address.CLAMP, false, true);

	private final VkDevice device;
	private final Map<Key, Long> samplers = new HashMap<>();

	public SamplerCache(VkDevice device) {
		this.device = device;
	}

	public synchronized long get(Key k) {
		Long s = samplers.get(k);
		if (s != null) return s;
		try (MemoryStack stack = stackPush()) {
			int f = k.filter() == Filter.LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
			int a = k.address() == Address.REPEAT ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
			VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(f).minFilter(f)
				.mipmapMode(k.filter() == Filter.LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(a).addressModeV(a).addressModeW(a)
				.minLod(0f).maxLod(k.mipmaps() ? VK_LOD_CLAMP_NONE : 0f)
				.compareEnable(k.shadowCompare()).compareOp(k.shadowCompare() ? VK_COMPARE_OP_LESS_OR_EQUAL : VK_COMPARE_OP_ALWAYS)
				.borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateSampler(device, ci, null, lp), "vkCreateSampler(" + k + ")");
			samplers.put(k, lp.get(0));
			return lp.get(0);
		}
	}

	public synchronized int size() {
		return samplers.size();
	}

	@Override
	public synchronized void close() {
		for (long s : samplers.values()) vkDestroySampler(device, s, null);
		samplers.clear();
	}
}
