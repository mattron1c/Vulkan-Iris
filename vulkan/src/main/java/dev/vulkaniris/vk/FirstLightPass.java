package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Phase 3 "first light": the smallest real Vulkan Iris render. A graphics pipeline built from GLSL compiled to
 * SPIR-V at runtime draws a UV gradient quad into a rectangle of an existing colour image, using dynamic rendering
 * with LOAD/STORE so everything outside the rectangle is preserved.
 *
 * <p>The image is supplied by the caller — in-game that is Minecraft's main render target, owned by Polydium.
 * Barriers before/after are the caller's responsibility, because they depend on the owner's sync scheme.</p>
 */
public final class FirstLightPass implements AutoCloseable {
	static final String VERTEX = """
		#version 450
		layout(location = 0) out vec2 uv;
		void main() {
		    // two triangles covering the viewport: (0,0)(1,0)(1,1) (0,0)(1,1)(0,1)
		    int i = gl_VertexIndex;
		    vec2 p = vec2((i == 1 || i == 2 || i == 4) ? 1.0 : 0.0,
		                  (i == 2 || i == 4 || i == 5) ? 1.0 : 0.0);
		    uv = p;
		    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	static final String FRAGMENT = """
		#version 450
		layout(location = 0) in vec2 uv;
		layout(location = 0) out vec4 color;
		layout(push_constant) uniform Params { float time; float animate; } params;
		void main() {
		    float b = params.animate > 0.5 ? 0.5 + 0.5 * sin(params.time) : 0.25;
		    color = vec4(uv.x, uv.y, b, 1.0);
		}
		""";

	/** Expected UNORM8 value of the static pattern at the rectangle centre: (≈128, ≈128, 64, 255). */
	public static final int[] CENTRE_RGBA = {128, 128, 64, 255};

	private final VkDevice device;
	private final int colorFormat;
	private final long vertModule;
	private final long fragModule;
	private final long layout;
	private final long pipeline;
	private final boolean core13;

	public FirstLightPass(VkDevice device, ShaderCompiler compiler, int colorFormat) {
		this.device = device;
		this.colorFormat = colorFormat;
		this.core13 = device.getCapabilities().vkCmdBeginRendering != 0L;
		if (!core13 && device.getCapabilities().vkCmdBeginRenderingKHR == 0L) {
			throw new VkCheck.VulkanException(VK_ERROR_FEATURE_NOT_PRESENT, "Device has neither Vulkan 1.3 nor VK_KHR_dynamic_rendering loaded");
		}
		long vm = 0, fm = 0, pl = 0;
		try (MemoryStack stack = stackPush()) {
			vm = module(stack, compiler.compile("vulkaniris:first_light.vsh", ShaderCompiler.Stage.VERTEX, VERTEX));
			fm = module(stack, compiler.compile("vulkaniris:first_light.fsh", ShaderCompiler.Stage.FRAGMENT, FRAGMENT));

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, stack);
			pc.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(8);
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(pc), null, lp),
				"vkCreatePipelineLayout(first light)");
			pl = lp.get(0);

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(stack.UTF8("main"));

			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blend.get(0).blendEnable(false).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

			VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
				.colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(colorFormat));

			VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			gp.get(0).sType$Default()
				.pNext(rendering.address())
				.pStages(stages)
				.pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
				.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
				.pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
				.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f))
				.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
				.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default())
				.pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
				.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
				.layout(pl);
			VkCheck.check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, lp), "vkCreateGraphicsPipelines(first light)");
			this.pipeline = lp.get(0);
		} catch (RuntimeException e) {
			if (pl != 0) vkDestroyPipelineLayout(device, pl, null);
			if (fm != 0) vkDestroyShaderModule(device, fm, null);
			if (vm != 0) vkDestroyShaderModule(device, vm, null);
			throw e;
		}
		this.vertModule = vm;
		this.fragModule = fm;
		this.layout = pl;
	}

	public int colorFormat() {
		return colorFormat;
	}

	/**
	 * Records the pass. {@code imageLayout} is the layout the image is in (and stays in) — Polydium keeps every
	 * texture in VK_IMAGE_LAYOUT_GENERAL.
	 */
	public void record(VkCommandBuffer cmd, long imageView, int imageLayout, int x, int y, int w, int h, float time, boolean animate) {
		try (MemoryStack stack = stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
			color.get(0).sType$Default().imageView(imageView).imageLayout(imageLayout)
				.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
				.renderArea(a -> a.offset(o -> o.set(x, y)).extent(e -> e.set(w, h)))
				.layerCount(1).pColorAttachments(color);
			if (core13) vkCmdBeginRendering(cmd, info);
			else vkCmdBeginRenderingKHR(cmd, info);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).x(x).y(y).width(w).height(h).minDepth(0f).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0).offset(o -> o.set(x, y)).extent(e -> e.set(w, h));
			vkCmdSetScissor(cmd, 0, sc);
			vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, stack.floats(time, animate ? 1f : 0f));
			vkCmdDraw(cmd, 6, 1, 0, 0);

			if (core13) vkCmdEndRendering(cmd);
			else vkCmdEndRenderingKHR(cmd);
		}
	}

	/** Full ALL_COMMANDS → ALL_COMMANDS memory barrier (what Polydium itself places between passes). */
	public static void fullBarrier(VkCommandBuffer cmd) {
		try (MemoryStack stack = stackPush()) {
			VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, b, null, null);
		}
	}

	private long module(MemoryStack stack, byte[] spirv) {
		ByteBuffer code = ShaderCompiler.toNative(spirv);
		try {
			LongBuffer lp = stack.mallocLong(1);
			VkCheck.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, lp), "vkCreateShaderModule");
			return lp.get(0);
		} finally {
			MemoryUtil.memFree(code);
		}
	}

	@Override
	public void close() {
		vkDestroyPipeline(device, pipeline, null);
		vkDestroyPipelineLayout(device, layout, null);
		vkDestroyShaderModule(device, fragModule, null);
		vkDestroyShaderModule(device, vertModule, null);
	}
}
