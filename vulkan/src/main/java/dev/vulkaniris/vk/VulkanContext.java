package dev.vulkaniris.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * The Vulkan instance / physical device / logical device / graphics queue Vulkan Iris renders with.
 *
 * <p><b>Ownership.</b> In-game, Vulkan Iris never creates its own device: Minecraft's Vulkan backend (26.2+),
 * Polydium's backport (1.21.11-26.1.2) or VulkanMod already owns one, plus the swapchain, and a second device would
 * double VRAM for shared resources and force cross-device copies every frame. {@link #borrow} wraps the owner's
 * handles and {@link #close()} then destroys nothing of theirs.
 * {@link #createOwned} exists for the headless test-suite and {@code gradlew :vulkan:probe}.</p>
 */
public final class VulkanContext implements AutoCloseable {
	public enum Owner {
		/** Created by Vulkan Iris itself (tests / probe only). */
		VULKAN_IRIS,
		/** Minecraft's own Vulkan backend (Java Edition 26.2+). */
		MINECRAFT,
		/** Polydium's backport of Minecraft's Vulkan backend (1.21.11 - 26.1.2). */
		POLYDIUM,
		/** VulkanMod's renderer. */
		VULKANMOD
	}

	public record Options(String appName, boolean validation, boolean verboseMessages) {
		public static Options fromSystemProperties() {
			return new Options("Vulkan Iris", Boolean.getBoolean("vulkaniris.validation"), Boolean.getBoolean("vulkaniris.debug"));
		}
	}

	private final Owner owner;
	private final VkLog log;
	private final VkInstance instance;
	private final int instanceApiVersion;
	private final VkPhysicalDevice physicalDevice;
	private final VkDevice device;
	private final VkQueue graphicsQueue;
	private final int graphicsQueueFamily;
	private final GpuCapabilities caps;
	private final Set<String> enabledInstanceExtensions;
	private final Set<String> enabledDeviceExtensions;
	private final Set<String> enabledFeatures;
	private final List<String> instanceLayers;
	private final boolean validationActive;
	private final DebugMessenger messenger;
	private final List<DeviceSelector.Candidate> candidates;
	private boolean closed;
	private volatile boolean lost;

	private VulkanContext(Owner owner, VkLog log, VkInstance instance, int instanceApiVersion, VkPhysicalDevice physicalDevice,
						  VkDevice device, VkQueue graphicsQueue, int graphicsQueueFamily, GpuCapabilities caps,
						  Set<String> instanceExts, Set<String> deviceExts, Set<String> features, List<String> layers,
						  boolean validationActive, DebugMessenger messenger, List<DeviceSelector.Candidate> candidates) {
		this.owner = owner;
		this.log = log;
		this.instance = instance;
		this.instanceApiVersion = instanceApiVersion;
		this.physicalDevice = physicalDevice;
		this.device = device;
		this.graphicsQueue = graphicsQueue;
		this.graphicsQueueFamily = graphicsQueueFamily;
		this.caps = caps;
		this.enabledInstanceExtensions = Collections.unmodifiableSet(instanceExts);
		this.enabledDeviceExtensions = Collections.unmodifiableSet(deviceExts);
		this.enabledFeatures = Collections.unmodifiableSet(features);
		this.instanceLayers = List.copyOf(layers);
		this.validationActive = validationActive;
		this.messenger = messenger;
		this.candidates = List.copyOf(candidates);
	}

	// =================================================================================================== borrow

	/**
	 * Wraps a device someone else created. {@code enabledDeviceExtensions} should be what the owner actually enabled
	 * (not merely what the GPU supports); pass an empty set if unknown and the report will say so.
	 */
	public static VulkanContext borrow(Owner owner, VkLog log, VkDevice device, VkQueue graphicsQueue, int graphicsQueueFamily,
									   int ownerInstanceApiVersion, Set<String> enabledDeviceExtensions) {
		if (owner == Owner.VULKAN_IRIS) throw new IllegalArgumentException("borrow() is for external owners");
		VkPhysicalDevice pd = device.getPhysicalDevice();
		VkInstance instance = pd.getInstance();
		GpuCapabilities caps = GpuCapabilities.query(pd, ownerInstanceApiVersion);
		log.info("Borrowing Vulkan device from " + owner + ": " + caps.name + " (Vulkan " + GpuCapabilities.apiString(caps.apiVersion) + ")");
		return new VulkanContext(owner, log, instance, ownerInstanceApiVersion, pd, device, graphicsQueue, graphicsQueueFamily, caps,
			Set.of(), new TreeSet<>(enabledDeviceExtensions), Set.of(), List.of(), false, null, List.of());
	}

	// =================================================================================================== own

	public static VulkanContext createOwned(Options options, VkLog log) {
		VkInstance instance = null;
		DebugMessenger messenger = null;
		VkDevice device = null;
		VkDebugUtilsMessengerCallbackEXT early = null;
		try (MemoryStack stack = stackPush()) {
			// ---- loader version
			IntBuffer ip = stack.mallocInt(1);
			VkCheck.check(vkEnumerateInstanceVersion(ip), "vkEnumerateInstanceVersion");
			int loaderApi = ip.get(0);
			if (loaderApi < VK_API_VERSION_1_1) {
				throw new VkCheck.VulkanException("Vulkan loader is " + GpuCapabilities.apiString(loaderApi) + "; Vulkan Iris needs 1.1+");
			}
			int instanceApi = Math.min(loaderApi, VK_API_VERSION_1_3);
			log.info("Vulkan loader " + GpuCapabilities.apiString(loaderApi) + ", requesting instance API " + GpuCapabilities.apiString(instanceApi));

			// ---- layers
			List<String> availableLayers = enumerateInstanceLayers();
			List<String> layers = new ArrayList<>();
			boolean validation = false;
			if (options.validation()) {
				if (availableLayers.contains("VK_LAYER_KHRONOS_validation")) {
					layers.add("VK_LAYER_KHRONOS_validation");
					validation = true;
				} else {
					log.warn("Validation requested but VK_LAYER_KHRONOS_validation is not installed. Install the Vulkan SDK "
						+ "(https://vulkan.lunarg.com/) to get validation. Continuing WITHOUT validation.");
				}
			}

			// ---- instance extensions
			Set<String> availableInstExts = enumerateInstanceExtensions();
			Set<String> instExts = new TreeSet<>();
			boolean debugUtils = availableInstExts.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			if (debugUtils) instExts.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			boolean portability = availableInstExts.contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
			if (portability) instExts.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME); // MoltenVK (macOS)

			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
				.pApplicationName(stack.UTF8(options.appName()))
				.applicationVersion(VK_MAKE_API_VERSION(0, 0, 2, 0))
				.pEngineName(stack.UTF8("Vulkan Iris"))
				.engineVersion(VK_MAKE_API_VERSION(0, 0, 2, 0))
				.apiVersion(instanceApi);
			VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default()
				.pApplicationInfo(app)
				.ppEnabledLayerNames(strings(stack, layers))
				.ppEnabledExtensionNames(strings(stack, instExts));
			if (portability) ci.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
			if (debugUtils) {
				early = DebugMessenger.earlyCallback(log);
				ci.pNext(DebugMessenger.createInfo(stack, early, options.verboseMessages()).address());
			}
			PointerBuffer pp = stack.mallocPointer(1);
			VkCheck.check(vkCreateInstance(ci, null, pp), "vkCreateInstance");
			long instanceHandle = pp.get(0);
			try {
				instance = new VkInstance(instanceHandle, ci);
			} catch (OutOfMemoryError oom) {
				// LWJGL builds the instance's function table on the thread-local MemoryStack; on drivers exposing
				// 250+ device extensions that needs more than LWJGL's 64 KiB default (VulkanMod raises it to 256 KiB).
				vkDestroyInstanceRaw(instanceHandle);
				throw new VkCheck.VulkanException("LWJGL MemoryStack too small to load Vulkan (" + oom.getMessage()
					+ "). Start the JVM with -Dorg.lwjgl.system.stackSize=256 or set Configuration.STACK_SIZE before LWJGL is first used.");
			}
			if (debugUtils) messenger = DebugMessenger.create(instance, log, options.verboseMessages());

			// ---- physical device
			List<DeviceSelector.Candidate> candidates = DeviceSelector.enumerate(instance, instanceApi);
			for (DeviceSelector.Candidate c : candidates) {
				log.info("  GPU" + c.index() + ": " + c.caps().name + " [" + GpuCapabilities.deviceTypeName(c.caps().deviceType) + ", Vulkan "
					+ GpuCapabilities.apiString(c.caps().apiVersion) + "]" + (c.suitable() ? " score=" + c.score() : " REJECTED: " + String.join(", ", c.rejections())));
			}
			DeviceSelector.Candidate chosen = DeviceSelector.select(candidates);
			GpuCapabilities caps = chosen.caps();
			VkPhysicalDevice pd = chosen.device();
			int deviceApi = Math.min(instanceApi, caps.apiVersion);
			boolean core13 = deviceApi >= VK_API_VERSION_1_3;

			// ---- device extensions: required (pre-1.3 only) + useful optional ones
			Set<String> devExts = new TreeSet<>();
			if (!core13) {
				devExts.add("VK_KHR_dynamic_rendering");
				devExts.add("VK_KHR_synchronization2");
			}
			for (String opt : List.of("VK_EXT_memory_budget", "VK_EXT_memory_priority")) {
				if (caps.hasExtension(opt)) devExts.add(opt);
			}

			// ---- features: enable exactly what we need and the hardware has; record it
			Set<String> enabled = new TreeSet<>();
			VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			VkPhysicalDeviceFeatures f = f2.features();
			if (caps.feature("samplerAnisotropy")) { f.samplerAnisotropy(true); enabled.add("samplerAnisotropy"); }
			f.independentBlend(true); enabled.add("independentBlend"); // required (checked by selector)
			if (caps.feature("depthClamp")) { f.depthClamp(true); enabled.add("depthClamp"); }
			if (caps.feature("fillModeNonSolid")) { f.fillModeNonSolid(true); enabled.add("fillModeNonSolid"); }
			if (caps.feature("multiDrawIndirect")) { f.multiDrawIndirect(true); enabled.add("multiDrawIndirect"); }
			if (caps.feature("drawIndirectFirstInstance")) { f.drawIndirectFirstInstance(true); enabled.add("drawIndirectFirstInstance"); }
			if (caps.feature("geometryShader")) { f.geometryShader(true); enabled.add("geometryShader"); }
			if (caps.feature("tessellationShader")) { f.tessellationShader(true); enabled.add("tessellationShader"); }
			if (caps.feature("shaderStorageImageWriteWithoutFormat")) { f.shaderStorageImageWriteWithoutFormat(true); enabled.add("shaderStorageImageWriteWithoutFormat"); }
			if (caps.feature("fragmentStoresAndAtomics")) { f.fragmentStoresAndAtomics(true); enabled.add("fragmentStoresAndAtomics"); }
			if (caps.feature("imageCubeArray")) { f.imageCubeArray(true); enabled.add("imageCubeArray"); }

			VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			f12.timelineSemaphore(true); enabled.add("timelineSemaphore");
			if (caps.feature("hostQueryReset")) { f12.hostQueryReset(true); enabled.add("hostQueryReset"); }
			if (caps.feature("drawIndirectCount")) { f12.drawIndirectCount(true); enabled.add("drawIndirectCount"); }
			if (caps.feature("scalarBlockLayout")) { f12.scalarBlockLayout(true); enabled.add("scalarBlockLayout"); }
			if (caps.feature("separateDepthStencilLayouts")) { f12.separateDepthStencilLayouts(true); enabled.add("separateDepthStencilLayouts"); }
			if (caps.feature("descriptorIndexing")) {
				f12.descriptorIndexing(true);
				enabled.add("descriptorIndexing");
				if (caps.feature("runtimeDescriptorArray")) { f12.runtimeDescriptorArray(true); enabled.add("runtimeDescriptorArray"); }
				if (caps.feature("descriptorBindingPartiallyBound")) { f12.descriptorBindingPartiallyBound(true); enabled.add("descriptorBindingPartiallyBound"); }
				if (caps.feature("shaderSampledImageArrayNonUniformIndexing")) { f12.shaderSampledImageArrayNonUniformIndexing(true); enabled.add("shaderSampledImageArrayNonUniformIndexing"); }
			}

			long tail;
			VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
			VkPhysicalDeviceDynamicRenderingFeaturesKHR dyn = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default();
			VkPhysicalDeviceSynchronization2FeaturesKHR sync2 = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
			if (core13) {
				f13.dynamicRendering(true).synchronization2(true);
				if (caps.feature("maintenance4")) { f13.maintenance4(true); enabled.add("maintenance4"); }
				if (caps.feature("pipelineCreationCacheControl")) { f13.pipelineCreationCacheControl(true); enabled.add("pipelineCreationCacheControl"); }
				tail = f13.address();
			} else {
				dyn.dynamicRendering(true);
				sync2.synchronization2(true).pNext(dyn.address());
				tail = sync2.address();
			}
			enabled.add("dynamicRendering");
			enabled.add("synchronization2");
			f12.pNext(tail);
			f2.pNext(f12.address());

			int gfx = caps.graphicsQueueFamily().getAsInt();
			VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
			queues.get(0).sType$Default().queueFamilyIndex(gfx).pQueuePriorities(stack.floats(1.0f));

			VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
				.pNext(f2.address())
				.pQueueCreateInfos(queues)
				.ppEnabledExtensionNames(strings(stack, devExts));
			VkCheck.check(vkCreateDevice(pd, dci, null, pp), "vkCreateDevice(" + caps.name + ")");
			device = new VkDevice(pp.get(0), pd, dci, deviceApi);

			vkGetDeviceQueue(device, gfx, 0, pp);
			VkQueue queue = new VkQueue(pp.get(0), device);
			log.info("Vulkan device ready: " + caps.name + " (" + caps.vendor.displayName + ", driver " + caps.driverVersion
				+ ", Vulkan " + GpuCapabilities.apiString(deviceApi) + ", queue family " + gfx + ")");

			return new VulkanContext(Owner.VULKAN_IRIS, log, instance, instanceApi, pd, device, queue, gfx, caps,
				instExts, devExts, enabled, layers, validation, messenger, candidates);
		} catch (RuntimeException e) {
			// unwind whatever was created, newest first
			if (device != null) vkDestroyDevice(device, null);
			if (messenger != null) messenger.close();
			if (instance != null) vkDestroyInstance(instance, null);
			if (early != null) {
				early.free();
				early = null;
			}
			throw e;
		} finally {
			// The loader also routes vkDestroyInstance messages through the messenger chained at vkCreateInstance,
			// so that callback must stay alive until close(): stash it with the instance.
			if (early != null && instance != null) EARLY_CALLBACKS.put(instance, early);
		}
	}

	// LWJGL callbacks are native thunks; the early one must outlive vkDestroyInstance, so we hold it here.
	private static final Map<VkInstance, VkDebugUtilsMessengerCallbackEXT> EARLY_CALLBACKS = Collections.synchronizedMap(new IdentityHashMap<>());

	// =================================================================================================== accessors

	public Owner owner() { return owner; }
	public VkLog log() { return log; }
	public VkInstance instance() { return instance; }
	public int instanceApiVersion() { return instanceApiVersion; }
	public VkPhysicalDevice physicalDevice() { return physicalDevice; }
	public VkDevice device() { return device; }
	public VkQueue graphicsQueue() { return graphicsQueue; }
	public int graphicsQueueFamily() { return graphicsQueueFamily; }
	public GpuCapabilities capabilities() { return caps; }
	public Set<String> enabledInstanceExtensions() { return enabledInstanceExtensions; }
	public Set<String> enabledDeviceExtensions() { return enabledDeviceExtensions; }
	public Set<String> enabledFeatures() { return enabledFeatures; }
	public List<String> instanceLayers() { return instanceLayers; }
	public boolean validationActive() { return validationActive; }
	public Optional<DebugMessenger> messenger() { return Optional.ofNullable(messenger); }
	public List<DeviceSelector.Candidate> candidates() { return candidates; }
	public boolean isLost() { return lost; }

	/** Records device loss so callers stop submitting; recovery = close() and re-create (owned) or wait for the owner. */
	public void markLost(VkCheck.VulkanDeviceLostException e) {
		if (!lost) {
			lost = true;
			log.error("VULKAN DEVICE LOST: " + e.getMessage() + " — all GPU work from Vulkan Iris is stopped until the context is recreated.");
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		if (owner != Owner.VULKAN_IRIS) {
			log.info("Releasing borrowed Vulkan device (owned by " + owner + "; nothing destroyed)");
			return;
		}
		if (!lost) {
			int r = vkDeviceWaitIdle(device);
			if (r != VK_SUCCESS) log.error("vkDeviceWaitIdle during shutdown: " + VkCheck.name(r));
		}
		vkDestroyDevice(device, null);
		if (messenger != null) messenger.close();
		vkDestroyInstance(instance, null);
		VkDebugUtilsMessengerCallbackEXT early = EARLY_CALLBACKS.remove(instance);
		if (early != null) early.free();
		log.info("Vulkan context destroyed cleanly");
	}

	// =================================================================================================== helpers

	/** Destroys an instance we only have the raw handle for (VkInstance wrapper construction failed). */
	private static void vkDestroyInstanceRaw(long handle) {
		long fn = org.lwjgl.vulkan.VK.getFunctionProvider().getFunctionAddress("vkDestroyInstance");
		if (fn != 0L) org.lwjgl.system.JNI.callPPV(handle, 0L, fn);
	}

	static List<String> enumerateInstanceLayers() {
		try (MemoryStack stack = stackPush()) {
			IntBuffer n = stack.mallocInt(1);
			VkCheck.check(vkEnumerateInstanceLayerProperties(n, null), "vkEnumerateInstanceLayerProperties(count)");
			VkLayerProperties.Buffer props = VkLayerProperties.malloc(n.get(0));
			try {
				VkCheck.checkAllowing(vkEnumerateInstanceLayerProperties(n, props), "vkEnumerateInstanceLayerProperties", VK_INCOMPLETE);
				List<String> out = new ArrayList<>();
				for (int i = 0; i < n.get(0); i++) out.add(props.get(i).layerNameString());
				return out;
			} finally {
				props.free();
			}
		}
	}

	static Set<String> enumerateInstanceExtensions() {
		try (MemoryStack stack = stackPush()) {
			IntBuffer n = stack.mallocInt(1);
			VkCheck.check(vkEnumerateInstanceExtensionProperties((CharSequence) null, n, null), "vkEnumerateInstanceExtensionProperties(count)");
			VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0));
			try {
				VkCheck.checkAllowing(vkEnumerateInstanceExtensionProperties((CharSequence) null, n, props), "vkEnumerateInstanceExtensionProperties", VK_INCOMPLETE);
				Set<String> out = new TreeSet<>();
				for (int i = 0; i < n.get(0); i++) out.add(props.get(i).extensionNameString());
				return out;
			} finally {
				props.free();
			}
		}
	}

	private static PointerBuffer strings(MemoryStack stack, Collection<String> values) {
		if (values.isEmpty()) return null;
		PointerBuffer pb = stack.mallocPointer(values.size());
		for (String v : values) pb.put(stack.UTF8(v));
		return pb.flip();
	}
}
