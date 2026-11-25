package net.irisshaders.iris.pipeline;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vulkan Iris addition (benchmark only): GPU time per stage of Iris's own GL pipeline, from {@code GL_TIMESTAMP}
 * queries ({@code glQueryCounter}), with the same mark names as the Vulkan side's {@code GpuTimer}, so the two
 * benchmark reports can be read side by side. Opt-in: {@code -Dvulkaniris.gputime=true}; otherwise every call is a
 * no-op. Results are read {@link #RING} frames later, only once available (never a blocking read); a frame whose slot
 * is still in flight is not timed.
 */
public final class GlStageTimer {
	public static final boolean ENABLED = Boolean.getBoolean("vulkaniris.gputime") || "passes".equals(System.getProperty("vulkaniris.gputime"));
	/** {@code -Dvulkaniris.gputime=passes}: also a mark after every composite-stage program (Vulkan Iris addition). */
	public static final boolean PER_PASS = "passes".equals(System.getProperty("vulkaniris.gputime"));
	private static final int RING = 8;
	private static final int MARKS = 96;

	private static int[] queries;
	private static final String[][] labels = new String[RING][MARKS];
	private static final int[] used = new int[RING];
	private static final boolean[] pending = new boolean[RING];
	private static final Map<String, long[]> totals = new LinkedHashMap<>();
	private static long frames, skipped, frameNanos;
	private static int slot = -1;
	private static boolean active;

	private GlStageTimer() {
	}

	/** Start of Iris's level rendering: collects the slot's earlier frame, then writes the first mark. */
	public static void frameStart() {
		if (!ENABLED) return;
		if (queries == null) {
			queries = new int[RING * MARKS];
			GL15.glGenQueries(queries);
		}
		if (active) frameEnd(); // a frame that never reached finalizeLevelRendering
		slot = (slot + 1) % RING;
		if (pending[slot]) {
			if (GL15.glGetQueryObjecti(queries[slot * MARKS + used[slot] - 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
				skipped++;
				pending[slot] = false;
				return;
			}
			collect(slot);
			pending[slot] = false;
		}
		used[slot] = 0;
		active = true;
		mark("frame start");
	}

	public static void mark(String name) {
		if (!ENABLED || !active || used[slot] == MARKS) return;
		labels[slot][used[slot]] = name;
		GL33.glQueryCounter(queries[slot * MARKS + used[slot]], GL33.GL_TIMESTAMP);
		used[slot]++;
	}

	public static void frameEnd() {
		if (!ENABLED || !active) return;
		pending[slot] = used[slot] >= 2;
		active = false;
	}

	private static void collect(int sl) {
		int n = used[sl];
		long first = GL33.glGetQueryObjecti64(queries[sl * MARKS], GL15.GL_QUERY_RESULT), prev = first;
		for (int i = 1; i < n; i++) {
			long t = GL33.glGetQueryObjecti64(queries[sl * MARKS + i], GL15.GL_QUERY_RESULT);
			long[] acc = totals.computeIfAbsent(labels[sl][i - 1] + " → " + labels[sl][i], k -> new long[2]);
			acc[0] += t - prev;
			acc[1]++;
			prev = t;
		}
		frameNanos += prev - first;
		frames++;
	}

	public static void reset() {
		totals.clear();
		frames = skipped = frameNanos = 0;
	}

	/** Same format as the Vulkan side's report. */
	public static String report() {
		if (frames == 0) return "no frames timed (" + skipped + " skipped)";
		StringBuilder b = new StringBuilder(String.format("%d frames timed (%d skipped); GPU ms per frame, from the start of Iris's level rendering to the end of final: %.3f",
			frames, skipped, frameNanos / 1e6 / frames));
		totals.forEach((label, acc) -> b.append(String.format("%n    %-58s %.3f ms%s", label, acc[0] / 1e6 / frames,
			acc[1] == frames ? "" : String.format(" (in %d of the frames)", acc[1]))));
		return b.toString();
	}
}
