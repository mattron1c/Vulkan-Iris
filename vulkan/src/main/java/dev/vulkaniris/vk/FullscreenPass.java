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
 * One fullscreen fragment program: N combined-image-sampler inputs (set 0, bindings 0..N-1), M colour outputs
 * (locations 0..M-1), optional push constants. This is the shape of every Iris composite/deferred/final program.
 *
 * <p>Orientation: the vertex shader emits {@code uv} with v=0 at memory row 0. Polydium stores render targets in GL
 * row order (row 0 = bottom), so uv here has exactly GL's meaning and packs need no flips.</p>
 */
public final class FullscreenPass implements AutoCloseable {
	public static final String VERTEX = """
		#version 450
		layout(location = 0) out vec2 uv;
		void main() {
		    // one oversized triangle covering the viewport
		    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
		    uv = p;
		    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	private final VkDevice device;
	private final String name;
	private final int inputCount;
	private final int[] outputFormats;
	private final int pushConstantBytes;
	private final boolean core13;
	private final long setLayout;
	private final long layout;
	private final long pipeline;

	public FullscreenPass(VkDevice device, ShaderCompiler compiler, String name, String fragmentGlsl, int inputCount,
						  int[] outputFormats, int pushConstantBytes) {
		this.device = device;
		this.name = name;
		this.inputCount = inputCount;
		this.outputFormats = outputFormats.clone();
		this.pushConstantBytes = pushConstantBytes;
		this.core13 = device.getCapabilities().vkCmdBeginRendering != 0L;
		if (!core13 && device.getCapabilities().vkCmdBeginRenderingKHR == 0L) {
			throw new VkCheck.VulkanException(VK_ERROR_FEATURE_NOT_PRESENT, "Device has neither Vulkan 1.3 nor VK_KHR_dynamic_rendering loaded");
		}
		long vm = 0, fm = 0, sl = 0, pl = 0;
		try (MemoryStack stack = stackPush()) {
			vm = module(stack, compiler.compile("vulkaniris:fullscreen.vsh", ShaderCompiler.Stage.VERTEX, VERTEX));
			fm = module(stack, compiler.compile(name, ShaderCompiler.Stage.FRAGMENT, fragmentGlsl));
			LongBuffer lp = stack.mallocLong(1);

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(Math.max(inputCount, 1), stack);
			for (int i = 0; i < inputCount; i++) {
				bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			}
			VkDescriptorSetLayoutCreateInfo dsl = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
			if (inputCount > 0) dsl.pBindings(bindings);
			VkCheck.check(vkCreateDescriptorSetLayout(device, dsl, null, lp), "vkCreateDescriptorSetLayout(" + name + ")");
			sl = lp.get(0);

			VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(sl));
			if (pushConstantBytes > 0) {
				VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, stack);
				pc.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(pushConstantBytes);
				plci.pPushConstantRanges(pc);
			}
			VkCheck.check(vkCreatePipelineLayout(device, plci, null, lp), "vkCreatePipelineLayout(" + name + ")");
			pl = lp.get(0);

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(stack.UTF8("main"));

			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(outputFormats.length, stack);
			for (int i = 0; i < outputFormats.length; i++) {
				blend.get(i).blendEnable(false).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
			}
			VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
				.colorAttachmentCount(outputFormats.length).pColorAttachmentFormats(stack.ints(outputFormats));

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
			VkCheck.check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, lp), "vkCreateGraphicsPipelines(" + name + ")");
			this.pipeline = lp.get(0);
		} catch (RuntimeException e) {
			if (pl != 0) vkDestroyPipelineLayout(device, pl, null);
			if (sl != 0) vkDestroyDescriptorSetLayout(device, sl, null);
			if (fm != 0) vkDestroyShaderModule(device, fm, null);
			if (vm != 0) vkDestroyShaderModule(device, vm, null);
			throw e;
		}
		// modules are baked into the pipeline
		vkDestroyShaderModule(device, fm, null);
		vkDestroyShaderModule(device, vm, null);
		this.setLayout = sl;
		this.layout = pl;
	}

	public String name() { return name; }
	public long setLayout() { return setLayout; }
	public int inputCount() { return inputCount; }

	/** Declares the layouts this pass needs. Call before {@link LayoutTracker#flush}, then write descriptors, then {@link #record}. */
	public void prepare(LayoutTracker tracker, GpuImage[] inputs, GpuImage[] outputs, boolean discardOutputs) {
		if (inputs.length != inputCount) throw new IllegalArgumentException(name + ": expected " + inputCount + " inputs, got " + inputs.length);
		for (GpuImage in : inputs) tracker.use(in, LayoutTracker.Use.SAMPLED_FRAGMENT);
		for (GpuImage out : outputs) tracker.use(out, LayoutTracker.Use.COLOR_ATTACHMENT, discardOutputs);
	}

	/**
	 * Draws into {@code outputs} (all the same size). Viewport = whole output, scissor = the given rectangle, so
	 * {@code gl_FragCoord} is always in absolute output pixels. {@code pushConstants} may be null.
	 */
	public void record(VkCommandBuffer cmd, long descriptorSet, GpuImage[] outputs, int sx, int sy, int sw, int sh, ByteBuffer pushConstants) {
		if (outputs.length != outputFormats.length) throw new IllegalArgumentException(name + ": expected " + outputFormats.length + " outputs");
		int w = outputs[0].width(), h = outputs[0].height();
		try (MemoryStack stack = stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(outputs.length, stack);
			for (int i = 0; i < outputs.length; i++) {
				if (outputs[i].format() != outputFormats[i]) throw new IllegalArgumentException(name + ": output " + i + " is " + outputs[i]);
				color.get(i).sType$Default().imageView(outputs[i].view()).imageLayout(outputs[i].layout())
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			}
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
				.renderArea(a -> a.offset(o -> o.set(sx, sy)).extent(e -> e.set(sw, sh)))
				.layerCount(1).pColorAttachments(color);
			if (core13) vkCmdBeginRendering(cmd, info);
			else vkCmdBeginRenderingKHR(cmd, info);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			if (inputCount > 0) vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).x(0).y(0).width(w).height(h).minDepth(0f).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0).offset(o -> o.set(sx, sy)).extent(e -> e.set(sw, sh));
			vkCmdSetScissor(cmd, 0, sc);
			if (pushConstants != null && pushConstantBytes > 0) vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushConstants);
			vkCmdDraw(cmd, 3, 1, 0, 0);

			if (core13) vkCmdEndRendering(cmd);
			else vkCmdEndRenderingKHR(cmd);
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

	/** Only once no submission using it is pending (use {@link DeferredDestroyer}). */
	@Override
	public void close() {
		vkDestroyPipeline(device, pipeline, null);
		vkDestroyPipelineLayout(device, layout, null);
		vkDestroyDescriptorSetLayout(device, setLayout, null);
	}
}
