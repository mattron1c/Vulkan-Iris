package dev.vulkaniris.fabric;

import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * A/B benchmark ({@code -Dvulkaniris.bench=true}): the same jar, the same world, camera, scene, time of day and options,
 * measured on Iris's GL pipeline ({@link GlBench}, without Polydium) and on Vulkan Iris ({@link PostChainCheck} in
 * bench mode, with Polydium on Vulkan). Wall-clock frame times are taken at the end of {@code GameRenderer.render} on
 * both sides, for {@code vulkaniris.bench.seconds} (default 60) after {@code vulkaniris.bench.warmup} seconds
 * (default 20) once the pack's chunks were rebuilt.
 */
final class Bench {
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.bench");
	static final int SECONDS = Integer.getInteger("vulkaniris.bench.seconds", 60);
	static final int WARMUP_SECONDS = Integer.getInteger("vulkaniris.bench.warmup", 20);

	private long[] frames = new long[1 << 16];
	private int count;
	/** Frames over 20 ms: JVM uptime at their end (as in -Xlog's uptime) and length, to line up with a GC log. */
	private final java.util.List<String> spikes = new java.util.ArrayList<>();
	private long last = -1, warmStart = -1, measureStart = -1, measureEnd = -1;

	/** Starts the warm-up clock (call once the pack is active and its chunks were rebuilt). */
	void warmUp() {
		if (warmStart < 0) warmStart = System.nanoTime();
	}

	boolean warming() {
		return warmStart >= 0;
	}

	/** Frames measured so far. */
	int frames() {
		return Math.max(1, count);
	}

	boolean measuring() {
		return measureStart >= 0 && measureEnd < 0;
	}

	/** Once per frame; returns true once the measurement is over. */
	boolean frame() {
		long now = System.nanoTime();
		if (warmStart < 0 || measureEnd >= 0) return measureEnd >= 0;
		if (measureStart < 0) {
			if (now - warmStart < WARMUP_SECONDS * 1_000_000_000L) return false;
			measureStart = last = now;
			return false;
		}
		if (count == frames.length) frames = Arrays.copyOf(frames, count * 2);
		if (now - last > 20_000_000L && spikes.size() < 40) {
			spikes.add(String.format("%.3fs:%.1fms", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0, (now - last) / 1e6));
		}
		frames[count++] = now - last;
		last = now;
		if (now - measureStart >= SECONDS * 1_000_000_000L) measureEnd = now;
		return measureEnd >= 0;
	}

	/** Average FPS, 1 % low (FPS over the slowest 1 % of frames), median / 99th percentile / worst frame time. */
	String result() {
		if (count == 0) return "no frames measured";
		long[] f = Arrays.copyOf(frames, count);
		Arrays.sort(f);
		double seconds = (measureEnd - measureStart) / 1e9;
		int worst = Math.max(1, count / 100);
		long worstSum = 0;
		for (int i = count - worst; i < count; i++) worstSum += f[i];
		int over20 = 0, over10 = 0;
		for (long x : f) {
			if (x > 20_000_000L) over20++;
			if (x > 10_000_000L) over10++;
		}
		return String.format("%d frames in %.1f s: average %.1f FPS, 1%% low %.1f FPS; frame time median %.2f ms, p99 %.2f ms, max %.2f ms; %d frames > 10 ms, %d > 20 ms",
			count, seconds, count / seconds, worst / (worstSum / 1e9), f[count / 2] / 1e6, f[Math.min(count - 1, (int) (count * 0.99))] / 1e6,
			f[count - 1] / 1e6, over10, over20) + (spikes.isEmpty() ? "" : "; spikes (JVM uptime:length): " + spikes);
	}

	/**
	 * The same options on both sides: no VSync, no frame cap, and no inactivity limit. Minecraft's default ("AFK")
	 * drops an unfocused window with no input to 30 and then 10 FPS, which made a first run measure the limiter.
	 */
	static void options(Minecraft mc) {
		mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		mc.options.enableVsync().set(false);
		mc.getWindow().updateVsync(false);
		// the slider's "unlimited": 260 in vanilla; Polydium widens the slider and treats 260 as a real cap, its unlimited is 1500
		mc.options.framerateLimit().set(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polydium") ? 1500 : 260);
	}

	/** The same world state on both sides: noon, clear weather, frozen cycles. */
	static void freeze(Minecraft mc) {
		PostChainCheck.run(mc, "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear", "time set noon");
	}

	static String settings(Minecraft mc) {
		return "render distance " + mc.options.renderDistance().get() + ", window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
			+ ", vsync " + mc.options.enableVsync().get() + ", frame limit " + mc.options.framerateLimit().get()
			+ ", inactivity limit " + mc.options.inactivityFpsLimit().get() + ", window minimized " + mc.getWindow().isMinimized();
	}
}
