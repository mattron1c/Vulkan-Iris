package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.VK_FALSE;

/**
 * VK_EXT_debug_utils messenger. Routes validation-layer and driver messages to {@link VkLog} and counts them, so
 * tests and the diagnostics report can assert "zero validation errors" instead of hoping.
 * Only used when Vulkan Iris owns the instance — a borrowed instance keeps its owner's messenger.
 */
public final class DebugMessenger implements AutoCloseable {
	private static final int MAX_KEPT = 64;

	private final VkInstance instance;
	private final VkLog log;
	private final VkDebugUtilsMessengerCallbackEXT callback;
	private final AtomicInteger errors = new AtomicInteger();
	private final AtomicInteger warnings = new AtomicInteger();
	private final List<String> kept = Collections.synchronizedList(new ArrayList<>());
	private long handle;

	private DebugMessenger(VkInstance instance, VkLog log) {
		this.instance = instance;
		this.log = log;
		this.callback = VkDebugUtilsMessengerCallbackEXT.create(this::onMessage);
	}

	/** Fills a create-info for this messenger; also chained into VkInstanceCreateInfo to catch instance creation messages. */
	static VkDebugUtilsMessengerCreateInfoEXT createInfo(MemoryStack stack, VkDebugUtilsMessengerCallbackEXT cb, boolean verbose) {
		int severities = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
		if (verbose) severities |= VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT;
		return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
			.sType$Default()
			.messageSeverity(severities)
			.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
				| VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
			.pfnUserCallback(cb);
	}

	/** Standalone callback usable before the instance exists (for the instance-creation pNext chain). */
	static VkDebugUtilsMessengerCallbackEXT earlyCallback(VkLog log) {
		return VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
			String msg = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
			if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) log.error("[vk instance] " + msg);
			else log.warn("[vk instance] " + msg);
			return VK_FALSE;
		});
	}

	static DebugMessenger create(VkInstance instance, VkLog log, boolean verbose) {
		DebugMessenger m = new DebugMessenger(instance, log);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			VkCheck.check(vkCreateDebugUtilsMessengerEXT(instance, createInfo(stack, m.callback, verbose), null, p), "vkCreateDebugUtilsMessengerEXT");
			m.handle = p.get(0);
		} catch (RuntimeException e) {
			m.callback.free();
			throw e;
		}
		return m;
	}

	private int onMessage(int severity, int types, long pCallbackData, long pUserData) {
		VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
		String id = data.pMessageIdNameString();
		String text = (id != null ? "[" + id + "] " : "") + data.pMessageString();
		String kind = (types & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0 ? "validation"
			: (types & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) != 0 ? "performance" : "general";
		if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
			errors.incrementAndGet();
			log.error("[vk " + kind + "] " + text);
			keep("ERROR " + kind + ": " + text);
		} else if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
			warnings.incrementAndGet();
			log.warn("[vk " + kind + "] " + text);
			keep("WARN " + kind + ": " + text);
		} else {
			log.debug("[vk " + kind + "] " + text);
		}
		return VK_FALSE; // never abort the call that triggered the message
	}

	private void keep(String s) {
		if (kept.size() < MAX_KEPT) kept.add(s);
	}

	public int errorCount() {
		return errors.get();
	}

	public int warningCount() {
		return warnings.get();
	}

	public List<String> recentMessages() {
		synchronized (kept) {
			return List.copyOf(kept);
		}
	}

	@Override
	public void close() {
		if (handle != 0) {
			vkDestroyDebugUtilsMessengerEXT(instance, handle, null);
			handle = 0;
		}
		callback.free();
	}
}
