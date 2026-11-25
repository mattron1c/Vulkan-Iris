package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A graphics pipeline built from already-compiled SPIR-V plus a described resource interface — the Vulkan
 * equivalent of one linked Iris program. Knows nothing about Iris: the caller translates the pack's
 * {@code ShaderResourceLayout} into {@link Binding}s and the translator's attribute map into {@link Attribute}s.
 * Single descriptor set (set 0), dynamic rendering, dynamic viewport/scissor.
 */
public final class GraphicsProgram implements AutoCloseable {
	public enum Stage {
		VERTEX(VK_SHADER_STAGE_VERTEX_BIT), TESS_CONTROL(VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT),
		TESS_EVAL(VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT), GEOMETRY(VK_SHADER_STAGE_GEOMETRY_BIT), FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT);
		public final int bit;

		Stage(int bit) {
			this.bit = bit;
		}
	}

	/** One descriptor binding in set 0. {@code type} is a VK_DESCRIPTOR_TYPE_*. */
	public record Binding(String name, int binding, int type, int count, int stageFlags) {
	}

	/**
	 * {@code binding} 0: the vertex buffer (stride {@link Spec#vertexStride()}); binding {@link #CONSTANT_BINDING}: a buffer
	 * read with stride 0, i.e. the same value for every vertex (GL's value for an attribute the vertex format doesn't have).
	 */
	public record Attribute(int location, int format, int offset, int binding) {
		public Attribute(int location, int format, int offset) {
			this(location, format, offset, 0);
		}
	}

	public static final int CONSTANT_BINDING = 1;

	/** Blend for one colour attachment; {@code null} = no blending. */
	public record Blend(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
		public static final Blend ALPHA = new Blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
	}

	/**
	 * Depth, culling and push constants for geometry passes. {@link #NONE}: fullscreen passes (no depth, no culling).
	 * {@code depthFormat} 0 = no depth attachment.
	 */
	public record Raster(int depthFormat, boolean depthTest, boolean depthWrite, int depthCompare, int cullMode, int frontFace,
						 int pushConstantSize, int colorWriteMask, float depthBiasConstant, float depthBiasSlope) {
		public static final Raster NONE = new Raster(0, false, false, VK_COMPARE_OP_ALWAYS, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, 0);

		public Raster(int depthFormat, boolean depthTest, boolean depthWrite, int depthCompare, int cullMode, int frontFace, int pushConstantSize) {
			this(depthFormat, depthTest, depthWrite, depthCompare, cullMode, frontFace, pushConstantSize, 0xF, 0f, 0f);
		}
	}

	public record Spec(String name, List<Stage> stages, List<byte[]> spirv, List<Binding> bindings, List<Attribute> attributes,
					   int vertexStride, int topology, int[] colorFormats, Blend[] blends, Raster raster) {
		public Spec(String name, List<Stage> stages, List<byte[]> spirv, List<Binding> bindings, List<Attribute> attributes,
					int vertexStride, int topology, int[] colorFormats, Blend[] blends) {
			this(name, stages, spirv, bindings, attributes, vertexStride, topology, colorFormats, blends, Raster.NONE);
		}
	}

	private final VkDevice device;
	private final Spec spec;
	private final boolean core13;
	private final long setLayout;
	private final long layout;
	private final long pipeline;

	public GraphicsProgram(VkDevice device, Spec spec) {
		this.device = device;
		this.spec = spec;
		this.core13 = device.getCapabilities().vkCmdBeginRendering != 0L;
		List<Long> modules = new ArrayList<>();
		long sl = 0, pl = 0;
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(Math.max(1, spec.bindings().size()), stack);
			for (int i = 0; i < spec.bindings().size(); i++) {
				Binding x = spec.bindings().get(i);
				b.get(i).binding(x.binding()).descriptorType(x.type()).descriptorCount(x.count()).stageFlags(x.stageFlags());
			}
			VkDescriptorSetLayoutCreateInfo dsl = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
			if (!spec.bindings().isEmpty()) dsl.pBindings(b);
			VkCheck.check(vkCreateDescriptorSetLayout(device, dsl, null, lp), "vkCreateDescriptorSetLayout(" + spec.name() + ")");
			sl = lp.get(0);
			VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(sl));
			if (spec.raster().pushConstantSize() > 0) {
				VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
				pcr.get(0).stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS).offset(0).size(spec.raster().pushConstantSize());
				plci.pPushConstantRanges(pcr);
			}
			VkCheck.check(vkCreatePipelineLayout(device, plci, null, lp), "vkCreatePipelineLayout(" + spec.name() + ")");
			pl = lp.get(0);

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(spec.stages().size(), stack);
			for (int i = 0; i < spec.stages().size(); i++) {
				ByteBuffer code = ShaderCompiler.toNative(spec.spirv().get(i));
				try {
					VkCheck.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, lp),
						"vkCreateShaderModule(" + spec.name() + " " + spec.stages().get(i) + ")");
				} finally {
					MemoryUtil.memFree(code);
				}
				modules.add(lp.get(0));
				stages.get(i).sType$Default().stage(spec.stages().get(i).bit).module(lp.get(0)).pName(stack.UTF8("main"));
			}

			VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			if (!spec.attributes().isEmpty()) {
				boolean constant = spec.attributes().stream().anyMatch(a -> a.binding() == CONSTANT_BINDING);
				VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(constant ? 2 : 1, stack);
				vb.get(0).binding(0).stride(spec.vertexStride()).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
				if (constant) vb.get(1).binding(CONSTANT_BINDING).stride(0).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
				VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(spec.attributes().size(), stack);
				for (int i = 0; i < spec.attributes().size(); i++) {
					Attribute a = spec.attributes().get(i);
					va.get(i).location(a.location()).binding(a.binding()).format(a.format()).offset(a.offset());
				}
				vi.pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
			}

			int n = spec.colorFormats().length;
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(Math.max(1, n), stack);
			for (int i = 0; i < n; i++) {
				Blend bl = spec.blends() == null ? null : spec.blends()[i];
				blend.get(i).colorWriteMask(spec.raster().colorWriteMask()).blendEnable(bl != null);
				if (bl != null) {
					blend.get(i).srcColorBlendFactor(bl.srcColor()).dstColorBlendFactor(bl.dstColor()).colorBlendOp(VK_BLEND_OP_ADD)
						.srcAlphaBlendFactor(bl.srcAlpha()).dstAlphaBlendFactor(bl.dstAlpha()).alphaBlendOp(VK_BLEND_OP_ADD);
				}
			}
			VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
			if (n > 0) cb.pAttachments(blend);
			Raster r = spec.raster();
			VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().colorAttachmentCount(n)
				.depthAttachmentFormat(r.depthFormat());
			if (n > 0) rendering.pColorAttachmentFormats(stack.ints(spec.colorFormats()));
			if (hasStencil(r.depthFormat())) rendering.stencilAttachmentFormat(r.depthFormat());

			VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			gp.get(0).sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(vi)
				.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(spec.topology()))
				.pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
				.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(VK_POLYGON_MODE_FILL).cullMode(r.cullMode()).frontFace(r.frontFace()).lineWidth(1f)
					.depthBiasEnable(r.depthBiasConstant() != 0f || r.depthBiasSlope() != 0f)
					.depthBiasConstantFactor(r.depthBiasConstant()).depthBiasSlopeFactor(r.depthBiasSlope()))
				.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
				.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(r.depthTest()).depthWriteEnable(r.depthWrite()).depthCompareOp(r.depthCompare()))
				.pColorBlendState(cb)
				.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
				.layout(pl);
			VkCheck.check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, lp), "vkCreateGraphicsPipelines(" + spec.name() + ")");
			this.pipeline = lp.get(0);
		} catch (RuntimeException e) {
			if (pl != 0) vkDestroyPipelineLayout(device, pl, null);
			if (sl != 0) vkDestroyDescriptorSetLayout(device, sl, null);
			throw e;
		} finally {
			for (long m : modules) vkDestroyShaderModule(device, m, null);
		}
		this.setLayout = sl;
		this.layout = pl;
	}

	public Spec spec() { return spec; }

	private static boolean hasStencil(int format) {
		return format == VK_FORMAT_D16_UNORM_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
	}

	// ---- geometry recording: begin once, then many binds/draws, then end

	/**
	 * Begins dynamic rendering into {@code colors} (+ optional {@code depth}), loading and storing their contents. The
	 * viewport is the first attachment's size; the render area and scissor are clamped to the smallest attachment
	 * (GL draws into a framebuffer of mixed sizes clipped to their intersection, e.g. with a scaled colortex).
	 */
	public static void beginRendering(VkCommandBuffer cmd, GpuImage[] colors, GpuImage depth) {
		GpuImage first = colors.length > 0 ? colors[0] : depth;
		int vw = first.width(), vh = first.height();
		int aw = vw, ah = vh;
		for (GpuImage c : colors) {
			aw = Math.min(aw, c.width());
			ah = Math.min(ah, c.height());
		}
		if (depth != null) {
			aw = Math.min(aw, depth.width());
			ah = Math.min(ah, depth.height());
		}
		final int w = aw, h = ah;
		try (MemoryStack stack = stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(Math.max(1, colors.length), stack);
			for (int i = 0; i < colors.length; i++) {
				color.get(i).sType$Default().imageView(colors[i].view()).imageLayout(colors[i].layout())
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			}
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().renderArea(a -> a.extent(e -> e.set(w, h))).layerCount(1);
			if (colors.length > 0) info.pColorAttachments(color);
			if (depth != null) {
				VkRenderingAttachmentInfo d = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth.view())
					.imageLayout(depth.layout()).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
				info.pDepthAttachment(d);
				if (hasStencil(depth.format())) info.pStencilAttachment(d);
			}
			if (cmd.getCapabilities().vkCmdBeginRendering != 0L) VK13.vkCmdBeginRendering(cmd, info);
			else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).width(vw).height(vh).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0).extent(e -> e.set(w, h));
			vkCmdSetScissor(cmd, 0, sc);
		}
	}

	public static void endRendering(VkCommandBuffer cmd) {
		if (cmd.getCapabilities().vkCmdEndRendering != 0L) VK13.vkCmdEndRendering(cmd);
		else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
	}

	/** Binds this pipeline and its descriptor set (inside {@link #beginRendering}). */
	public void bind(VkCommandBuffer cmd, long descriptorSet) {
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
		if (descriptorSet != 0) {
			try (MemoryStack stack = stackPush()) {
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
			}
		}
	}

	public void pushConstants(VkCommandBuffer cmd, ByteBuffer data) {
		vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_ALL_GRAPHICS, 0, data);
	}
	public long setLayout() { return setLayout; }

	/** Draws {@code vertexCount} vertices from {@code vertexBuffer} into {@code outputs} (all the same size), full viewport. */
	public void draw(VkCommandBuffer cmd, long descriptorSet, GpuImage[] outputs, long vertexBuffer, int vertexCount) {
		int w = outputs[0].width(), h = outputs[0].height();
		try (MemoryStack stack = stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(Math.max(1, outputs.length), stack);
			for (int i = 0; i < outputs.length; i++) {
				color.get(i).sType$Default().imageView(outputs[i].view()).imageLayout(outputs[i].layout())
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			}
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
				.renderArea(a -> a.extent(e -> e.set(w, h))).layerCount(1);
			if (outputs.length > 0) info.pColorAttachments(color);
			if (core13) VK13.vkCmdBeginRendering(cmd, info);
			else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			if (descriptorSet != 0) vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).width(w).height(h).maxDepth(1f);
			vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
			sc.get(0).extent(e -> e.set(w, h));
			vkCmdSetScissor(cmd, 0, sc);
			if (vertexBuffer != 0) vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer), stack.longs(0));
			vkCmdDraw(cmd, vertexCount, 1, 0, 0);
			if (core13) VK13.vkCmdEndRendering(cmd);
			else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
		}
	}

	/** Only once no submission using it is pending. */
	@Override
	public void close() {
		vkDestroyPipeline(device, pipeline, null);
		vkDestroyPipelineLayout(device, layout, null);
		vkDestroyDescriptorSetLayout(device, setLayout, null);
	}
}
