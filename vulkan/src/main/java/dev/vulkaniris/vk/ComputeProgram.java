package dev.vulkaniris.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pipeline from already-compiled SPIR-V and a described resource interface: the compute twin of
 * {@link GraphicsProgram}. Single descriptor set (set 0). Like GraphicsProgram it knows nothing about Iris.
 */
public final class ComputeProgram implements AutoCloseable {
	private final VkDevice device;
	private final String name;
	private final long setLayout;
	private final long layout;
	private final long pipeline;

	public ComputeProgram(VkDevice device, String name, byte[] spirv, List<GraphicsProgram.Binding> bindings) {
		this.device = device;
		this.name = name;
		long sl = 0, pl = 0, module = 0;
		try (MemoryStack stack = stackPush()) {
			LongBuffer lp = stack.mallocLong(1);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(Math.max(1, bindings.size()), stack);
			for (int i = 0; i < bindings.size(); i++) {
				GraphicsProgram.Binding x = bindings.get(i);
				b.get(i).binding(x.binding()).descriptorType(x.type()).descriptorCount(x.count()).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo dsl = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
			if (!bindings.isEmpty()) dsl.pBindings(b);
			VkCheck.check(vkCreateDescriptorSetLayout(device, dsl, null, lp), "vkCreateDescriptorSetLayout(" + name + ")");
			sl = lp.get(0);
			VkCheck.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(sl)), null, lp),
				"vkCreatePipelineLayout(" + name + ")");
			pl = lp.get(0);
			ByteBuffer code = ShaderCompiler.toNative(spirv);
			try {
				VkCheck.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, lp),
					"vkCreateShaderModule(" + name + ")");
			} finally {
				MemoryUtil.memFree(code);
			}
			module = lp.get(0);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
			ci.get(0).sType$Default().layout(pl).stage(s -> s.sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(lp.get(0)).pName(stack.UTF8("main")));
			VkCheck.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, lp), "vkCreateComputePipelines(" + name + ")");
			this.pipeline = lp.get(0);
		} catch (RuntimeException e) {
			if (pl != 0) vkDestroyPipelineLayout(device, pl, null);
			if (sl != 0) vkDestroyDescriptorSetLayout(device, sl, null);
			throw e;
		} finally {
			if (module != 0) vkDestroyShaderModule(device, module, null);
		}
		this.setLayout = sl;
		this.layout = pl;
	}

	public String name() { return name; }
	public long setLayout() { return setLayout; }

	private void bind(VkCommandBuffer cmd, long descriptorSet) {
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		if (descriptorSet != 0) {
			try (MemoryStack stack = stackPush()) {
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(descriptorSet), null);
			}
		}
	}

	public void dispatch(VkCommandBuffer cmd, long descriptorSet, int x, int y, int z) {
		bind(cmd, descriptorSet);
		vkCmdDispatch(cmd, x, y, z);
	}

	public void dispatchIndirect(VkCommandBuffer cmd, long descriptorSet, long buffer, long offset) {
		bind(cmd, descriptorSet);
		vkCmdDispatchIndirect(cmd, buffer, offset);
	}

	/** Only once no submission using it is pending. */
	@Override
	public void close() {
		vkDestroyPipeline(device, pipeline, null);
		vkDestroyPipelineLayout(device, layout, null);
		vkDestroyDescriptorSetLayout(device, setLayout, null);
	}
}
