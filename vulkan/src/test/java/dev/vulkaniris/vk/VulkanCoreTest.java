package dev.vulkaniris.vk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

class VulkanCoreTest {
	// ------------------------------------------------------------------ pure logic (no GPU)

	@Test
	void resultNamesAndDeviceLostType() {
		assertEquals("VK_ERROR_OUT_OF_DEVICE_MEMORY", VkCheck.name(VK_ERROR_OUT_OF_DEVICE_MEMORY));
		assertInstanceOf(VkCheck.VulkanDeviceLostException.class, VkCheck.failure(VK_ERROR_DEVICE_LOST, "submit"));
		VkCheck.VulkanException e = assertThrows(VkCheck.VulkanException.class, () -> VkCheck.check(VK_ERROR_INITIALIZATION_FAILED, "vkCreateDevice"));
		assertTrue(e.getMessage().contains("vkCreateDevice") && e.getMessage().contains("VK_ERROR_INITIALIZATION_FAILED"));
		assertEquals(VK_TIMEOUT, VkCheck.checkAllowing(VK_TIMEOUT, "wait", VK_TIMEOUT));
	}

	@Test
	void driverVersionDecoding() {
		// NVIDIA packs 10.8.8.6 bits: 610.88 -> 610<<22 | 88<<14
		assertEquals("610.88", GpuVendor.NVIDIA.decodeDriverVersion((610 << 22) | (88 << 14), true));
		// Intel Windows packs 18.14 bits: 101.6130
		assertEquals("101.6130", GpuVendor.INTEL.decodeDriverVersion((101 << 14) | 6130, true));
		// AMD / Mesa use the standard packing
		assertEquals("2.0.302", GpuVendor.AMD.decodeDriverVersion(VK_MAKE_API_VERSION(0, 2, 0, 302), false));
		assertEquals(GpuVendor.AMD, GpuVendor.fromId(0x1002));
		assertEquals(GpuVendor.INTEL, GpuVendor.fromId(0x8086));
		assertEquals(GpuVendor.UNKNOWN, GpuVendor.fromId(0xBEEF));
	}

	// ------------------------------------------------------------------ real GPU

	private static void assumeGpu() {
		Assumptions.assumeFalse(Boolean.getBoolean("vulkaniris.skipGpuTests"), "GPU tests disabled");
	}

	@Test
	void initialisesSelectsDeviceRunsWorkAndShutsDownCleanly() {
		assumeGpu();
		List<String> errors = new ArrayList<>();
		VkLog capture = new VkLog() {
			public void info(String m) { VkLog.STDERR.info(m); }
			public void warn(String m) { VkLog.STDERR.warn(m); }
			public void error(String m) { errors.add(m); VkLog.STDERR.error(m); }
		};
		VulkanContext ctx = VulkanContext.createOwned(new VulkanContext.Options("Vulkan Iris test", Boolean.getBoolean("vulkaniris.validation"), false), capture);
		try {
			GpuCapabilities c = ctx.capabilities();
			assertFalse(c.name.isBlank());
			assertTrue(c.supportsDynamicRendering() && c.supportsSynchronization2());
			assertTrue(ctx.graphicsQueueFamily() >= 0);
			assertTrue(c.maxColorAttachments >= 8, "Iris packs need >= 8 colour attachments");
			assertNotEquals(0L, ctx.device().address());

			GpuSelfTest.Result r = GpuSelfTest.run(ctx);
			assertTrue(r.bufferFillVerified(), "GPU fill readback mismatch");
			assertTrue(r.imageClearVerified(), "GPU RGBA16F clear readback mismatch");

			String report = DiagnosticsReport.build(ctx, r);
			assertTrue(report.contains(c.name));
			System.out.println(report);

			ctx.messenger().ifPresent(m -> assertEquals(0, m.errorCount(), "debug messenger errors: " + m.recentMessages()));
		} finally {
			ctx.close();
		}
		assertTrue(errors.isEmpty(), "errors logged: " + errors);
		ctx.close(); // idempotent
	}

	@Test
	void repeatedCreateDestroyDoesNotLeakOrFail() {
		assumeGpu();
		for (int i = 0; i < 3; i++) {
			try (VulkanContext ctx = VulkanContext.createOwned(new VulkanContext.Options("cycle " + i, false, false), VkLog.STDERR)) {
				assertTrue(GpuSelfTest.run(ctx).bufferFillVerified());
			}
		}
	}

	@Test
	void unknownDeviceOverrideFailsLoudly() {
		assumeGpu();
		String old = System.getProperty("vulkaniris.device");
		System.setProperty("vulkaniris.device", "no-such-gpu-xyz");
		try {
			VkCheck.VulkanException e = assertThrows(VkCheck.VulkanException.class,
				() -> VulkanContext.createOwned(new VulkanContext.Options("override", false, false), VkLog.STDERR).close());
			assertTrue(e.getMessage().contains("no-such-gpu-xyz"));
		} finally {
			if (old == null) System.clearProperty("vulkaniris.device");
			else System.setProperty("vulkaniris.device", old);
		}
	}
}
