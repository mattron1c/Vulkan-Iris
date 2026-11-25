package dev.vulkaniris.fabric;

import dev.vulkaniris.vk.FrameClock;
import dev.vulkaniris.vk.VkCheck;
import dev.vulkaniris.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU time per stage of Vulkan Iris's frame, from timestamp queries ({@code -Dvulkaniris.gputime=true}; reported by
 * the bench, {@code prodBenchVulkan -Dvulkaniris.gputime=true}). A mark is written at each stage boundary with {@code vkCmdWriteTimestamp(BOTTOM_OF_PIPE)}, which completes
 * once all earlier commands have. Each interval is the GPU time between two consecutive marks, named
 * "first mark → second mark". Polydium's own work between our hooks (e.g. the opaque world between "prepare end" and
 * "deferred start") is in the interval it falls into; BENCHMARK.md says what each interval holds.
 *
 * <p>Results come back {@link #RING} frames later, read without waiting. A frame whose query slot is still in flight
 * is not timed.
 */
final class GpuTimer implements AutoCloseable {
	/** Opt-in, also in bench mode: the FPS numbers in BENCHMARK.md come from runs without it. */
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.gputime");
	private static final int RING = 8;
	private static final int MARKS = 16;

	private final VulkanContext ctx;
	private final FrameClock clock;
	private final long pool;
	private final double periodNs;
	private final long validMask;
	private final String[][] labels = new String[RING][MARKS];
	private final int[] used = new int[RING];
	private final long[] submit = new long[RING];
	private final boolean[] pending = new boolean[RING];
	private final Map<String, long[]> totals = new LinkedHashMap<>();
	private long frames, skipped, frameNanos;
	private int slot = -1;
	private boolean active;

	private GpuTimer(VulkanContext ctx, FrameClock clock, long pool, double periodNs, int validBits) {
		this.ctx = ctx;
		this.clock = clock;
		this.pool = pool;
		this.periodNs = periodNs;
		this.validMask = validBits >= 64 ? -1L : (1L << validBits) - 1;
	}

	/** Null if timestamps aren't supported on the graphics queue (the report then says so). */
	static GpuTimer create(VulkanContext ctx, FrameClock clock) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(s);
			vkGetPhysicalDeviceProperties(ctx.physicalDevice(), props);
			IntBuffer n = s.mallocInt(1);
			vkGetPhysicalDeviceQueueFamilyProperties(ctx.physicalDevice(), n, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n.get(0), s);
			vkGetPhysicalDeviceQueueFamilyProperties(ctx.physicalDevice(), n, families);
			int validBits = families.get(ctx.graphicsQueueFamily()).timestampValidBits();
			if (validBits == 0) return null;
			LongBuffer lp = s.mallocLong(1);
			VkCheck.check(vkCreateQueryPool(ctx.device(), VkQueryPoolCreateInfo.calloc(s).sType$Default()
				.queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * MARKS), null, lp), "vkCreateQueryPool(timestamps)");
			return new GpuTimer(ctx, clock, lp.get(0), props.limits().timestampPeriod(), validBits);
		}
	}

	/** Start of the frame (outside any rendering): collects the slot's earlier frame, resets the slot, writes the first mark. */
	void frameStart(VkCommandBuffer cmd) {
		slot = (slot + 1) % RING;
		active = false;
		if (pending[slot]) {
			if (!clock.isComplete(submit[slot])) {
				skipped++;
				return;
			}
			collect(slot);
			pending[slot] = false;
		}
		vkCmdResetQueryPool(cmd, pool, slot * MARKS, MARKS);
		used[slot] = 0;
		submit[slot] = clock.currentIndex();
		active = true;
		mark(cmd, "frame start");
	}

	/** A stage boundary (a constant name: the intervals are keyed by the names of their two marks). */
	void mark(VkCommandBuffer cmd, String name) {
		if (!active || used[slot] == MARKS) return;
		labels[slot][used[slot]] = name;
		vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, slot * MARKS + used[slot]);
		used[slot]++;
	}

	void frameEnd() {
		if (active) pending[slot] = true;
		active = false;
	}

	private void collect(int sl) {
		int n = used[sl];
		if (n < 2) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer data = s.mallocLong(n * 2);
			int r = vkGetQueryPoolResults(ctx.device(), pool, sl * MARKS, n, data, 16, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
			if (r != VK_SUCCESS && r != VK_NOT_READY) VkCheck.check(r, "vkGetQueryPoolResults(timestamps)");
			for (int i = 0; i < n; i++) {
				if (data.get(i * 2 + 1) == 0) {
					skipped++;
					return;
				}
			}
			long first = data.get(0) & validMask, prev = first;
			for (int i = 1; i < n; i++) {
				long t = data.get(i * 2) & validMask;
				long[] acc = totals.computeIfAbsent(labels[sl][i - 1] + " → " + labels[sl][i], k -> new long[2]);
				acc[0] += Math.round((t - prev) * periodNs);
				acc[1]++;
				prev = t;
			}
			frameNanos += Math.round((prev - first) * periodNs);
			frames++;
		}
	}

	void reset() {
		totals.clear();
		frames = skipped = frameNanos = 0;
	}

	/** Average GPU ms per timed frame for each interval (an interval missing from some frames is averaged over all of them). */
	String report() {
		if (frames == 0) return "no frames timed (" + skipped + " skipped)";
		StringBuilder b = new StringBuilder(String.format("%d frames timed (%d skipped); GPU ms per frame, from the start of Vulkan Iris's frame to the end of final: %.3f",
			frames, skipped, frameNanos / 1e6 / frames));
		totals.forEach((label, acc) -> b.append(String.format("%n    %-58s %.3f ms%s", label, acc[0] / 1e6 / frames,
			acc[1] == frames ? "" : String.format(" (in %d of the frames)", acc[1]))));
		return b.toString();
	}

	@Override
	public void close() {
		vkDestroyQueryPool(ctx.device(), pool, null);
	}
}
