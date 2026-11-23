package dev.vulkaniris.vk;

import java.util.ArrayDeque;

/**
 * Frees GPU objects only once every submission that could have used them completed. Used for render targets on
 * resize, pipelines on shader-pack reload and descriptor pools. Never blocks: {@link #collect()} is called once per
 * frame and runs whatever became safe.
 */
public final class DeferredDestroyer {
	private record Entry(long index, String what, Runnable destroy) {
	}

	private final FrameClock clock;
	private final VkLog log;
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private long destroyed;

	public DeferredDestroyer(FrameClock clock, VkLog log) {
		this.clock = clock;
		this.log = log;
	}

	/** Destroys {@code destroy} once the submission being recorded right now has completed. */
	public synchronized void defer(String what, Runnable destroy) {
		queue.addLast(new Entry(clock.currentIndex(), what, destroy));
	}

	/** Runs everything whose submission finished. Entries are ordered by index, so this stops at the first pending one. */
	public synchronized int collect() {
		int n = 0;
		while (!queue.isEmpty() && clock.isComplete(queue.peekFirst().index())) {
			run(queue.pollFirst());
			n++;
		}
		return n;
	}

	/** Runs everything now. Only call after the device is idle (shutdown, device lost). */
	public synchronized int drainAll() {
		int n = queue.size();
		while (!queue.isEmpty()) run(queue.pollFirst());
		return n;
	}

	public synchronized int pending() {
		return queue.size();
	}

	public synchronized long destroyedTotal() {
		return destroyed;
	}

	private void run(Entry e) {
		try {
			e.destroy().run();
			destroyed++;
		} catch (RuntimeException ex) {
			log.error("Deferred destroy of " + e.what() + " failed: " + ex);
		}
	}
}
