package dev.vulkaniris.vk;

/**
 * Tells resource code which GPU submission is being recorded and whether earlier ones finished. Every
 * deferred destroy and every descriptor pool reuse is keyed on these indices, so Vulkan Iris never frees something
 * the GPU may still read.
 *
 * <p>In-game this is the Vulkan owner's own submit counter (Polydium: {@code VulkanCommandEncoder.currentSubmitIndex()}),
 * so no extra fences or CPU waits are added to the frame.</p>
 */
public interface FrameClock {
	/** Index of the submission currently being recorded. Monotonic. */
	long currentIndex();

	/** True once submission {@code index} has finished executing on the GPU. */
	boolean isComplete(long index);

	/**
	 * Clock for code that submits and waits synchronously (tests, the probe, one-shot uploads): call
	 * {@link #advance()} after every wait-for-idle; everything before the current index is complete.
	 */
	final class Manual implements FrameClock {
		private long current = 1;

		@Override
		public long currentIndex() {
			return current;
		}

		@Override
		public boolean isComplete(long index) {
			return index < current;
		}

		/** The submission for {@link #currentIndex()} has been waited on. */
		public void advance() {
			current++;
		}
	}
}
