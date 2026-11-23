package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.vulkan.EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Everything Vulkan Iris needs to know about one physical device, read once and kept as plain Java values
 * (no native memory is held after {@link #query} returns).
 *
 * <p>Works for any {@link VkPhysicalDevice} — one we enumerated ourselves, or one borrowed from Minecraft's own
 * Vulkan backend, Polydium or VulkanMod.</p>
 */
public final class GpuCapabilities {
	public record QueueFamily(int index, int flags, int count, int timestampValidBits) {
		public boolean graphics() {
			return (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
		}

		public boolean compute() {
			return (flags & VK_QUEUE_COMPUTE_BIT) != 0;
		}

		public boolean transfer() {
			return (flags & VK_QUEUE_TRANSFER_BIT) != 0;
		}
	}

	public record MemoryHeap(int index, long size, boolean deviceLocal, boolean hostVisibleType, long budget, long usage) {
	}

	public record MemoryType(int index, int heapIndex, int flags) {
	}

	/** Optimal-tiling support for a format, as Iris render targets / depth buffers need it. */
	public record FormatSupport(String name, int vkFormat, boolean sampled, boolean linearFilter, boolean colorAttachment,
								boolean blend, boolean depthAttachment, boolean storage) {
	}

	// identity
	public final String name;
	public final GpuVendor vendor;
	public final int vendorId;
	public final int deviceId;
	public final int deviceType;
	public final int apiVersion;
	public final int driverVersionRaw;
	public final String driverVersion;
	public final String driverName;
	public final String driverInfo;
	public final int driverId;
	public final String deviceUuid;

	// limits that matter for Iris shader packs
	public final int maxImageDimension2D;
	public final int maxColorAttachments;
	public final int maxPushConstantsSize;
	public final int maxBoundDescriptorSets;
	public final int maxUniformBufferRange;
	public final int maxStorageBufferRange;
	public final int maxPerStageDescriptorSampledImages;
	public final int maxPerStageDescriptorStorageImages;
	public final int maxComputeWorkGroupInvocations;
	public final float maxSamplerAnisotropy;
	public final float timestampPeriod;
	public final boolean timestampComputeAndGraphics;
	public final long minUniformBufferOffsetAlignment;
	public final long nonCoherentAtomSize;

	// subgroups
	public final int subgroupSize;
	public final int subgroupStages;
	public final int subgroupOperations;

	// core features (Vulkan 1.0 - 1.3)
	public final Map<String, Boolean> features;

	public final List<QueueFamily> queueFamilies;
	public final List<MemoryHeap> memoryHeaps;
	public final List<MemoryType> memoryTypes;
	public final Set<String> extensions;
	public final List<FormatSupport> formats;

	private GpuCapabilities(Builder b) {
		this.name = b.name;
		this.vendorId = b.vendorId;
		this.vendor = GpuVendor.fromId(b.vendorId);
		this.deviceId = b.deviceId;
		this.deviceType = b.deviceType;
		this.apiVersion = b.apiVersion;
		this.driverVersionRaw = b.driverVersionRaw;
		this.driverVersion = vendor.decodeDriverVersion(b.driverVersionRaw, isWindows());
		this.driverName = b.driverName;
		this.driverInfo = b.driverInfo;
		this.driverId = b.driverId;
		this.deviceUuid = b.deviceUuid;
		this.maxImageDimension2D = b.maxImageDimension2D;
		this.maxColorAttachments = b.maxColorAttachments;
		this.maxPushConstantsSize = b.maxPushConstantsSize;
		this.maxBoundDescriptorSets = b.maxBoundDescriptorSets;
		this.maxUniformBufferRange = b.maxUniformBufferRange;
		this.maxStorageBufferRange = b.maxStorageBufferRange;
		this.maxPerStageDescriptorSampledImages = b.maxPerStageDescriptorSampledImages;
		this.maxPerStageDescriptorStorageImages = b.maxPerStageDescriptorStorageImages;
		this.maxComputeWorkGroupInvocations = b.maxComputeWorkGroupInvocations;
		this.maxSamplerAnisotropy = b.maxSamplerAnisotropy;
		this.timestampPeriod = b.timestampPeriod;
		this.timestampComputeAndGraphics = b.timestampComputeAndGraphics;
		this.minUniformBufferOffsetAlignment = b.minUniformBufferOffsetAlignment;
		this.nonCoherentAtomSize = b.nonCoherentAtomSize;
		this.subgroupSize = b.subgroupSize;
		this.subgroupStages = b.subgroupStages;
		this.subgroupOperations = b.subgroupOperations;
		this.features = Collections.unmodifiableMap(b.features);
		this.queueFamilies = List.copyOf(b.queueFamilies);
		this.memoryHeaps = List.copyOf(b.memoryHeaps);
		this.memoryTypes = List.copyOf(b.memoryTypes);
		this.extensions = Collections.unmodifiableSet(b.extensions);
		this.formats = List.copyOf(b.formats);
	}

	// ------------------------------------------------------------------------------------------------- derived

	public boolean feature(String name) {
		return features.getOrDefault(name, false);
	}

	public boolean hasExtension(String ext) {
		return extensions.contains(ext);
	}

	public boolean isDiscrete() {
		return deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
	}

	public boolean isIntegrated() {
		return deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
	}

	/**
	 * Unified memory (integrated GPUs, Apple silicon): there is no separate system-RAM heap — every heap is
	 * device-local and host-visible. A discrete card with ReBAR also has a host-visible VRAM heap, but it still
	 * exposes a separate system heap, so it is NOT unified (see {@link #hasResizableBar()}).
	 */
	public boolean isUnifiedMemory() {
		if (isIntegrated() || deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) return true;
		if (memoryHeaps.isEmpty()) return false;
		for (MemoryHeap h : memoryHeaps) {
			if (!h.deviceLocal || !h.hostVisibleType) return false;
		}
		return true;
	}

	/**
	 * Resizable BAR / Smart Access Memory: a discrete GPU exposing a DEVICE_LOCAL|HOST_VISIBLE memory type on a heap
	 * larger than the legacy 256 MiB BAR window. Lets uniforms and streaming data be written straight into VRAM.
	 */
	public boolean hasResizableBar() {
		if (!isDiscrete()) return false;
		for (MemoryType t : memoryTypes) {
			int both = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
			if ((t.flags & both) == both && memoryHeaps.get(t.heapIndex).size > 256L * 1024 * 1024) return true;
		}
		return false;
	}

	public long deviceLocalBytes() {
		long total = 0;
		for (MemoryHeap h : memoryHeaps) if (h.deviceLocal) total += h.size;
		return total;
	}

	public boolean supportsDynamicRendering() {
		return feature("dynamicRendering");
	}

	public boolean supportsSynchronization2() {
		return feature("synchronization2");
	}

	/** First queue family that can do graphics + compute (Iris needs both on one queue for compute passes). */
	public OptionalInt graphicsQueueFamily() {
		for (QueueFamily q : queueFamilies) if (q.graphics() && q.compute()) return OptionalInt.of(q.index);
		return OptionalInt.empty();
	}

	/** A transfer-only queue family (DMA engine) usable for async uploads, if the GPU has one. */
	public OptionalInt dedicatedTransferFamily() {
		for (QueueFamily q : queueFamilies) if (q.transfer() && !q.graphics() && !q.compute()) return OptionalInt.of(q.index);
		return OptionalInt.empty();
	}

	/** A compute queue without graphics, usable for async compute. */
	public OptionalInt asyncComputeFamily() {
		for (QueueFamily q : queueFamilies) if (q.compute() && !q.graphics()) return OptionalInt.of(q.index);
		return OptionalInt.empty();
	}

	public Optional<FormatSupport> format(int vkFormat) {
		return formats.stream().filter(f -> f.vkFormat == vkFormat).findFirst();
	}

	public static String apiString(int v) {
		return VK_API_VERSION_MAJOR(v) + "." + VK_API_VERSION_MINOR(v) + "." + VK_API_VERSION_PATCH(v);
	}

	public static String deviceTypeName(int type) {
		return switch (type) {
			case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "Discrete GPU";
			case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "Integrated GPU";
			case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "Virtual GPU";
			case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU (software)";
			default -> "Other";
		};
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	// ------------------------------------------------------------------------------------------------- query

	/**
	 * @param instanceApiVersion the apiVersion the owning VkInstance was created with. *2 queries are core in 1.1;
	 *                           1.2/1.3 feature structs are only chained when both instance and device support them.
	 */
	public static GpuCapabilities query(VkPhysicalDevice pd, int instanceApiVersion) {
		Builder b = new Builder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties baseProps = VkPhysicalDeviceProperties.malloc(stack);
			vkGetPhysicalDeviceProperties(pd, baseProps);
			int deviceApi = baseProps.apiVersion();
			int effectiveApi = Math.min(deviceApi, instanceApiVersion);
			if (effectiveApi < VK_API_VERSION_1_1) {
				throw new VkCheck.VulkanException("Device " + baseProps.deviceNameString() + " only offers Vulkan "
					+ apiString(effectiveApi) + " (instance " + apiString(instanceApiVersion) + "); Vulkan Iris needs 1.1+ to query it");
			}

			b.extensions = enumerateExtensions(pd);

			// ---- properties2 chain: driver (1.2) + subgroup (1.1)
			VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
			VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
			VkPhysicalDeviceIDProperties idProps = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
			boolean haveDriverProps = effectiveApi >= VK_API_VERSION_1_2;
			subgroup.pNext(idProps.address());
			if (haveDriverProps) {
				driver.pNext(subgroup.address());
				props2.pNext(driver.address());
			} else {
				props2.pNext(subgroup.address());
			}
			vkGetPhysicalDeviceProperties2(pd, props2);

			VkPhysicalDeviceProperties p = props2.properties();
			VkPhysicalDeviceLimits l = p.limits();
			b.name = p.deviceNameString();
			b.vendorId = p.vendorID();
			b.deviceId = p.deviceID();
			b.deviceType = p.deviceType();
			b.apiVersion = deviceApi;
			b.driverVersionRaw = p.driverVersion();
			b.maxImageDimension2D = l.maxImageDimension2D();
			b.maxColorAttachments = l.maxColorAttachments();
			b.maxPushConstantsSize = l.maxPushConstantsSize();
			b.maxBoundDescriptorSets = l.maxBoundDescriptorSets();
			b.maxUniformBufferRange = l.maxUniformBufferRange();
			b.maxStorageBufferRange = l.maxStorageBufferRange();
			b.maxPerStageDescriptorSampledImages = l.maxPerStageDescriptorSampledImages();
			b.maxPerStageDescriptorStorageImages = l.maxPerStageDescriptorStorageImages();
			b.maxComputeWorkGroupInvocations = l.maxComputeWorkGroupInvocations();
			b.maxSamplerAnisotropy = l.maxSamplerAnisotropy();
			b.timestampPeriod = l.timestampPeriod();
			b.timestampComputeAndGraphics = l.timestampComputeAndGraphics();
			b.minUniformBufferOffsetAlignment = l.minUniformBufferOffsetAlignment();
			b.nonCoherentAtomSize = l.nonCoherentAtomSize();
			b.subgroupSize = subgroup.subgroupSize();
			b.subgroupStages = subgroup.supportedStages();
			b.subgroupOperations = subgroup.supportedOperations();
			b.deviceUuid = uuid(idProps.deviceUUID());
			if (haveDriverProps) {
				b.driverName = driver.driverNameString();
				b.driverInfo = driver.driverInfoString();
				b.driverId = driver.driverID();
			} else {
				b.driverName = "";
				b.driverInfo = "";
				b.driverId = 0;
			}

			// ---- features2 chain
			VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
			VkPhysicalDeviceDynamicRenderingFeaturesKHR dynKhr = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default();
			VkPhysicalDeviceSynchronization2FeaturesKHR syncKhr = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
			boolean have13 = effectiveApi >= VK_API_VERSION_1_3;
			boolean have12 = effectiveApi >= VK_API_VERSION_1_2;
			long chain = 0;
			if (have13) {
				f13.pNext(chain);
				chain = f13.address();
			} else {
				if (b.extensions.contains("VK_KHR_dynamic_rendering")) {
					dynKhr.pNext(chain);
					chain = dynKhr.address();
				}
				if (b.extensions.contains("VK_KHR_synchronization2")) {
					syncKhr.pNext(chain);
					chain = syncKhr.address();
				}
			}
			if (have12) {
				f12.pNext(chain);
				chain = f12.address();
				f11.pNext(chain);
				chain = f11.address();
			}
			f2.pNext(chain);
			vkGetPhysicalDeviceFeatures2(pd, f2);

			VkPhysicalDeviceFeatures f = f2.features();
			Map<String, Boolean> m = b.features;
			// 1.0
			m.put("samplerAnisotropy", f.samplerAnisotropy());
			m.put("independentBlend", f.independentBlend());
			m.put("depthClamp", f.depthClamp());
			m.put("depthBiasClamp", f.depthBiasClamp());
			m.put("fillModeNonSolid", f.fillModeNonSolid());
			m.put("wideLines", f.wideLines());
			m.put("multiDrawIndirect", f.multiDrawIndirect());
			m.put("drawIndirectFirstInstance", f.drawIndirectFirstInstance());
			m.put("geometryShader", f.geometryShader());
			m.put("tessellationShader", f.tessellationShader());
			m.put("shaderStorageImageWriteWithoutFormat", f.shaderStorageImageWriteWithoutFormat());
			m.put("shaderStorageImageReadWithoutFormat", f.shaderStorageImageReadWithoutFormat());
			m.put("shaderFloat64", f.shaderFloat64());
			m.put("shaderInt64", f.shaderInt64());
			m.put("shaderInt16", f.shaderInt16());
			m.put("textureCompressionBC", f.textureCompressionBC());
			m.put("imageCubeArray", f.imageCubeArray());
			m.put("dualSrcBlend", f.dualSrcBlend());
			m.put("logicOp", f.logicOp());
			m.put("fragmentStoresAndAtomics", f.fragmentStoresAndAtomics());
			m.put("vertexPipelineStoresAndAtomics", f.vertexPipelineStoresAndAtomics());
			if (have12) {
				m.put("shaderDrawParameters", f11.shaderDrawParameters());
				m.put("storageBuffer16BitAccess", f11.storageBuffer16BitAccess());
				m.put("multiview", f11.multiview());
				m.put("timelineSemaphore", f12.timelineSemaphore());
				m.put("descriptorIndexing", f12.descriptorIndexing());
				m.put("runtimeDescriptorArray", f12.runtimeDescriptorArray());
				m.put("descriptorBindingPartiallyBound", f12.descriptorBindingPartiallyBound());
				m.put("shaderSampledImageArrayNonUniformIndexing", f12.shaderSampledImageArrayNonUniformIndexing());
				m.put("descriptorBindingSampledImageUpdateAfterBind", f12.descriptorBindingSampledImageUpdateAfterBind());
				m.put("bufferDeviceAddress", f12.bufferDeviceAddress());
				m.put("hostQueryReset", f12.hostQueryReset());
				m.put("drawIndirectCount", f12.drawIndirectCount());
				m.put("scalarBlockLayout", f12.scalarBlockLayout());
				m.put("uniformBufferStandardLayout", f12.uniformBufferStandardLayout());
				m.put("separateDepthStencilLayouts", f12.separateDepthStencilLayouts());
				m.put("shaderFloat16", f12.shaderFloat16());
				m.put("shaderInt8", f12.shaderInt8());
				m.put("imagelessFramebuffer", f12.imagelessFramebuffer());
			}
			if (have13) {
				m.put("dynamicRendering", f13.dynamicRendering());
				m.put("synchronization2", f13.synchronization2());
				m.put("maintenance4", f13.maintenance4());
				m.put("pipelineCreationCacheControl", f13.pipelineCreationCacheControl());
				m.put("subgroupSizeControl", f13.subgroupSizeControl());
				m.put("shaderDemoteToHelperInvocation", f13.shaderDemoteToHelperInvocation());
			} else {
				m.put("dynamicRendering", b.extensions.contains("VK_KHR_dynamic_rendering") && dynKhr.dynamicRendering());
				m.put("synchronization2", b.extensions.contains("VK_KHR_synchronization2") && syncKhr.synchronization2());
			}

			// ---- queues
			IntBuffer count = stack.mallocInt(1);
			vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
			VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.malloc(count.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(pd, count, qf);
			for (int i = 0; i < qf.capacity(); i++) {
				VkQueueFamilyProperties q = qf.get(i);
				b.queueFamilies.add(new QueueFamily(i, q.queueFlags(), q.queueCount(), q.timestampValidBits()));
			}

			// ---- memory (+ live budget if VK_EXT_memory_budget)
			boolean budget = b.extensions.contains(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
			VkPhysicalDeviceMemoryBudgetPropertiesEXT budgetProps = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceMemoryProperties2 mem2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
			if (budget) mem2.pNext(budgetProps.address());
			vkGetPhysicalDeviceMemoryProperties2(pd, mem2);
			VkPhysicalDeviceMemoryProperties mp = mem2.memoryProperties();
			boolean[] heapHasHostVisible = new boolean[mp.memoryHeapCount()];
			for (int i = 0; i < mp.memoryTypeCount(); i++) {
				VkMemoryType t = mp.memoryTypes(i);
				b.memoryTypes.add(new MemoryType(i, t.heapIndex(), t.propertyFlags()));
				if ((t.propertyFlags() & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) heapHasHostVisible[t.heapIndex()] = true;
			}
			for (int i = 0; i < mp.memoryHeapCount(); i++) {
				VkMemoryHeap h = mp.memoryHeaps(i);
				b.memoryHeaps.add(new MemoryHeap(i, h.size(), (h.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0,
					heapHasHostVisible[i], budget ? budgetProps.heapBudget(i) : -1, budget ? budgetProps.heapUsage(i) : -1));
			}

			// ---- render-target formats Iris shader packs request (see shaderpack InternalTextureFormat)
			for (Object[] fmt : FORMATS) {
				VkFormatProperties fp = VkFormatProperties.malloc(stack);
				vkGetPhysicalDeviceFormatProperties(pd, (int) fmt[1], fp);
				int o = fp.optimalTilingFeatures();
				b.formats.add(new FormatSupport((String) fmt[0], (int) fmt[1],
					(o & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0,
					(o & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0,
					(o & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0,
					(o & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0,
					(o & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0,
					(o & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0));
			}
		}
		return new GpuCapabilities(b);
	}

	/** Iris/OptiFine colortex formats (GL name) -> closest Vulkan format, plus depth formats. */
	private static final Object[][] FORMATS = {
		{"RGBA8", VK_FORMAT_R8G8B8A8_UNORM},
		{"RGB8 (3-channel)", VK_FORMAT_R8G8B8_UNORM},
		{"RGBA16", VK_FORMAT_R16G16B16A16_UNORM},
		{"RGB16 (3-channel)", VK_FORMAT_R16G16B16_UNORM},
		{"RGBA16F", VK_FORMAT_R16G16B16A16_SFLOAT},
		{"RGB16F (3-channel)", VK_FORMAT_R16G16B16_SFLOAT},
		{"RGBA32F", VK_FORMAT_R32G32B32A32_SFLOAT},
		{"RGB32F (3-channel)", VK_FORMAT_R32G32B32_SFLOAT},
		{"R11F_G11F_B10F", VK_FORMAT_B10G11R11_UFLOAT_PACK32},
		{"RGB10_A2", VK_FORMAT_A2B10G10R10_UNORM_PACK32},
		{"RG16F", VK_FORMAT_R16G16_SFLOAT},
		{"RG32F", VK_FORMAT_R32G32_SFLOAT},
		{"R8", VK_FORMAT_R8_UNORM},
		{"R16F", VK_FORMAT_R16_SFLOAT},
		{"R32F", VK_FORMAT_R32_SFLOAT},
		{"R32UI", VK_FORMAT_R32_UINT},
		{"RGBA8_SNORM", VK_FORMAT_R8G8B8A8_SNORM},
		{"DEPTH32F", VK_FORMAT_D32_SFLOAT},
		{"DEPTH24_STENCIL8", VK_FORMAT_D24_UNORM_S8_UINT},
		{"DEPTH32F_STENCIL8", VK_FORMAT_D32_SFLOAT_S8_UINT},
		{"DEPTH16", VK_FORMAT_D16_UNORM},
	};

	static Set<String> enumerateExtensions(VkPhysicalDevice pd) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer n = stack.mallocInt(1);
			VkCheck.check(vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, n, null), "vkEnumerateDeviceExtensionProperties(count)");
			// Heap-allocated: ~260 bytes per extension easily exceeds the 64 KiB LWJGL stack on modern drivers.
			VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0));
			try {
				VkCheck.checkAllowing(vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, n, props), "vkEnumerateDeviceExtensionProperties", VK_INCOMPLETE);
				Set<String> out = new TreeSet<>();
				for (int i = 0; i < n.get(0); i++) out.add(props.get(i).extensionNameString());
				return out;
			} finally {
				props.free();
			}
		}
	}

	private static String uuid(java.nio.ByteBuffer b) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < VK_UUID_SIZE; i++) {
			if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
			sb.append(String.format("%02x", b.get(i) & 0xFF));
		}
		return sb.toString();
	}

	private static final class Builder {
		String name, driverName, driverInfo, deviceUuid;
		int vendorId, deviceId, deviceType, apiVersion, driverVersionRaw, driverId;
		int maxImageDimension2D, maxColorAttachments, maxPushConstantsSize, maxBoundDescriptorSets, maxUniformBufferRange,
			maxStorageBufferRange, maxPerStageDescriptorSampledImages, maxPerStageDescriptorStorageImages, maxComputeWorkGroupInvocations;
		float maxSamplerAnisotropy, timestampPeriod;
		boolean timestampComputeAndGraphics;
		long minUniformBufferOffsetAlignment, nonCoherentAtomSize;
		int subgroupSize, subgroupStages, subgroupOperations;
		final Map<String, Boolean> features = new LinkedHashMap<>();
		final List<QueueFamily> queueFamilies = new ArrayList<>();
		final List<MemoryHeap> memoryHeaps = new ArrayList<>();
		final List<MemoryType> memoryTypes = new ArrayList<>();
		final List<FormatSupport> formats = new ArrayList<>();
		Set<String> extensions = Set.of();
	}
}
