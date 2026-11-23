package dev.vulkaniris.vk;

import java.util.List;

/**
 * Device extensions Vulkan Iris knows how to make use of, grouped by who ships them.
 *
 * <p>Phase 2 only <em>detects</em> these. Each entry says which later phase is expected to use it, so the
 * diagnostics report is honest about "your GPU supports X" versus "Vulkan Iris uses X".
 * Vendor upscalers (DLSS, FSR, XeSS) are not Vulkan extensions — they are SDKs with their own licences — so they
 * are listed in the report as notes rather than detected here.</p>
 */
public final class VendorFeatureCatalog {
	private VendorFeatureCatalog() {
	}

	public enum Scope {CROSS_VENDOR, NVIDIA, AMD, INTEL}

	public record Entry(String extension, Scope scope, String purpose, String plannedPhase) {
	}

	public static final List<Entry> ENTRIES = List.of(
		// --- cross-vendor, directly performance relevant -------------------------------------------------------
		new Entry("VK_EXT_graphics_pipeline_library", Scope.CROSS_VENDOR, "Link pre-compiled pipeline parts; removes most shader-pack load and first-draw hitches", "Phase 9/10"),
		new Entry("VK_KHR_pipeline_library", Scope.CROSS_VENDOR, "Required by graphics_pipeline_library", "Phase 9/10"),
		new Entry("VK_EXT_shader_object", Scope.CROSS_VENDOR, "Pipeline-free shader binding; alternative to pipeline permutation explosion", "evaluate"),
		new Entry("VK_EXT_extended_dynamic_state3", Scope.CROSS_VENDOR, "Blend/depth/cull as dynamic state; fewer pipelines per shader-pack program", "Phase 9/10"),
		new Entry("VK_KHR_push_descriptor", Scope.CROSS_VENDOR, "Descriptor updates without pool allocation for per-draw data", "Phase 4"),
		new Entry("VK_EXT_descriptor_buffer", Scope.CROSS_VENDOR, "Descriptors in plain GPU memory; cheapest binding model on NVIDIA/AMD", "evaluate"),
		new Entry("VK_EXT_memory_budget", Scope.CROSS_VENDOR, "Real VRAM budget/usage per heap for the diagnostics overlay and allocator", "Phase 2 (used)"),
		new Entry("VK_EXT_memory_priority", Scope.CROSS_VENDOR, "Keep render targets resident when VRAM is oversubscribed", "Phase 4"),
		new Entry("VK_EXT_pageable_device_local_memory", Scope.CROSS_VENDOR, "Lets the driver page out low-priority allocations instead of failing", "Phase 4"),
		new Entry("VK_KHR_calibrated_timestamps", Scope.CROSS_VENDOR, "Correlate GPU timestamps with CPU time for frame profiling", "Phase 10"),
		new Entry("VK_EXT_calibrated_timestamps", Scope.CROSS_VENDOR, "Older name of calibrated_timestamps", "Phase 10"),
		new Entry("VK_KHR_fragment_shading_rate", Scope.CROSS_VENDOR, "Variable-rate shading for expensive composite/volumetric passes (opt-in, alters image)", "Phase 10 (opt-in)"),
		new Entry("VK_EXT_mesh_shader", Scope.CROSS_VENDOR, "Mesh/task shaders; possible GPU-driven terrain culling", "research"),
		new Entry("VK_KHR_ray_query", Scope.CROSS_VENDOR, "Ray queries from fragment/compute shaders (hardware RT shadows/reflections)", "research"),
		new Entry("VK_KHR_acceleration_structure", Scope.CROSS_VENDOR, "BLAS/TLAS required by ray_query", "research"),
		new Entry("VK_EXT_device_fault", Scope.CROSS_VENDOR, "Driver-provided fault report after VK_ERROR_DEVICE_LOST", "Phase 3"),
		new Entry("VK_KHR_present_wait", Scope.CROSS_VENDOR, "Frame pacing / latency control (owner's swapchain only)", "evaluate"),
		new Entry("VK_KHR_external_memory_win32", Scope.CROSS_VENDOR, "Share images with OpenGL on Windows (interop experiments only)", "evaluate"),
		new Entry("VK_KHR_external_memory_fd", Scope.CROSS_VENDOR, "Share images with OpenGL on Linux (interop experiments only)", "evaluate"),
		new Entry("VK_KHR_external_semaphore_win32", Scope.CROSS_VENDOR, "Cross-API sync on Windows (interop experiments only)", "evaluate"),
		new Entry("VK_KHR_external_semaphore_fd", Scope.CROSS_VENDOR, "Cross-API sync on Linux (interop experiments only)", "evaluate"),

		// --- NVIDIA ----------------------------------------------------------------------------------------------
		new Entry("VK_NV_low_latency2", Scope.NVIDIA, "NVIDIA Reflex low-latency mode (needs swapchain owner cooperation)", "research"),
		new Entry("VK_NV_device_diagnostic_checkpoints", Scope.NVIDIA, "Breadcrumbs showing which pass was running when the GPU hung", "Phase 3"),
		new Entry("VK_NV_device_diagnostics_config", Scope.NVIDIA, "Nsight Aftermath crash dumps", "Phase 3 (debug mode)"),
		new Entry("VK_NV_shader_sm_builtins", Scope.NVIDIA, "SM/warp IDs in shaders (profiling only)", "debug"),
		new Entry("VK_NV_ray_tracing_invocation_reorder", Scope.NVIDIA, "Shader execution reordering for RT workloads (RTX 40+)", "research"),
		new Entry("VK_NVX_image_view_handle", Scope.NVIDIA, "Prerequisite used by the DLSS/Streamline integration path", "research"),
		new Entry("VK_NVX_binary_import", Scope.NVIDIA, "Prerequisite used by the DLSS/Streamline integration path", "research"),

		// --- AMD -------------------------------------------------------------------------------------------------
		new Entry("VK_AMD_anti_lag", Scope.AMD, "AMD Anti-Lag latency reduction (needs swapchain owner cooperation)", "research"),
		new Entry("VK_AMD_shader_info", Scope.AMD, "Per-shader VGPR/SGPR/ISA statistics for shader-pack diagnostics", "Phase 5 (debug mode)"),
		new Entry("VK_AMD_buffer_marker", Scope.AMD, "Breadcrumbs showing which pass was running when the GPU hung", "Phase 3"),
		new Entry("VK_AMD_shader_core_properties", Scope.AMD, "CU/wave counts for compute dispatch sizing", "Phase 9"),
		new Entry("VK_AMD_shader_core_properties2", Scope.AMD, "Active CU count", "Phase 9"),
		new Entry("VK_AMD_device_coherent_memory", Scope.AMD, "Coherent memory for breadcrumb buffers", "Phase 3"),
		new Entry("VK_AMD_memory_overallocation_behavior", Scope.AMD, "Control VRAM overcommit behaviour", "Phase 4"),

		// --- Intel -----------------------------------------------------------------------------------------------
		new Entry("VK_INTEL_performance_query", Scope.INTEL, "Hardware performance counters for Arc/Xe profiling", "debug"),
		new Entry("VK_INTEL_shader_integer_functions2", Scope.INTEL, "Extra integer ops in shaders", "evaluate")
	);
}
