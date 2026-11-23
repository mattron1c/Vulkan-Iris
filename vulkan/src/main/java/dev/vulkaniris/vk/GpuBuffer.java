package dev.vulkaniris.vk;

/** A VMA buffer. {@code mapped} is the persistent CPU address for host-visible buffers, else 0. */
public record GpuBuffer(String name, long buffer, long allocation, long size, long mapped) {
}
