package dev.vulkaniris.vk;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code gradlew :vulkan:probe} — Phase 2 end-to-end check outside Minecraft:
 * create instance + device, run {@link GpuSelfTest}, print the diagnostics report, shut down cleanly.
 * Exit code 0 only if every step passed and the debug messenger saw no errors.
 */
public final class VulkanProbe {
	private VulkanProbe() {
	}

	public static void main(String[] args) throws Exception {
		int exit = 0;
		try (VulkanContext ctx = VulkanContext.createOwned(VulkanContext.Options.fromSystemProperties(), VkLog.STDERR)) {
			GpuSelfTest.Result r = GpuSelfTest.run(ctx);
			String report = DiagnosticsReport.build(ctx, r);
			System.out.println(report);
			if (args.length > 0) {
				Path out = Path.of(args[0]);
				Files.writeString(out, report);
				System.out.println("Report written to " + out.toAbsolutePath());
			}
			if (!r.bufferFillVerified() || !r.imageClearVerified()) exit = 2;
			if (ctx.messenger().map(DebugMessenger::errorCount).orElse(0) > 0) exit = 3;
		}
		System.exit(exit);
	}
}
