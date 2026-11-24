package dev.vulkaniris.fabric;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.polyfrost.polydium.vulkan.VulkanGpuBuffer;
import org.polyfrost.polydium.vulkan.sodium.SodiumVulkan;
import org.polyfrost.polydium.vulkan.sodium.VkCommandList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Receives Polydium's terrain draw ({@code SodiumVulkan.draw}) and turns Sodium's per-region batches into plain
 * {@link PostChain.RegionDraw}s for the pack's terrain programs. It walks the same regions, buffers and batch arrays
 * Polydium's own draw does (found by reading Polydium 1.4.21's bytecode; nothing is copied):
 * <ul>
 *   <li>skip a region with no storage, no device resources or an empty cached batch;</li>
 *   <li>index buffer: the shared quad index buffer, or the region's own when {@code useRegionIndex} (translucent sorting);
 *       32-bit indices; vertex buffer: the region's geometry buffer;</li>
 *   <li>region offset = (region origin − camera int) − camera fraction, per axis; time = now − region creation time;</li>
 *   <li>per batch entry: index count, first index = element pointer / 4, base vertex.</li>
 * </ul>
 * Only loaded when Polydium runs on Vulkan (it is referenced only from a Polydium-gated mixin).
 */
public final class TerrainHook {
	/** Where terrain goes while a pack's terrain programs are active (the Phase 7 self-test sets it). */
	public interface Sink {
		/** Return false to let Polydium draw this pass itself. */
		boolean drawTerrain(int kind, List<PostChain.RegionDraw> draws, PostChain.TerrainInputs inputs);
	}

	public static final int SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2;
	static volatile Sink sink;

	private TerrainHook() {
	}

	public static boolean draw(ChunkRenderListIterable lists, TerrainRenderPass pass, CameraTransform camera, boolean useRegionIndex,
							   SharedQuadIndexBuffer sharedIndex) {
		Sink s = sink;
		if (s == null) return false;
		int kind = pass == DefaultTerrainRenderPasses.SOLID ? SOLID : pass == DefaultTerrainRenderPasses.CUTOUT ? CUTOUT
			: pass == DefaultTerrainRenderPasses.TRANSLUCENT ? TRANSLUCENT : -1;
		if (kind < 0) throw new IllegalStateException("unknown Sodium terrain pass " + pass + " (not implemented yet)");

		List<PostChain.RegionDraw> draws = new ArrayList<>();
		long now = System.currentTimeMillis();
		long shared = useRegionIndex ? 0 : vk(sharedIndex.getBufferObject());
		for (Iterator<ChunkRenderList> it = lists.iterator(pass.isTranslucent()); it.hasNext(); ) {
			RenderRegion region = it.next().getRegion();
			if (region.getStorage(pass) == null) continue;
			RenderRegion.DeviceResources res = region.getResources();
			if (res == null) continue;
			MultiDrawBatch batch = region.getCachedBatch(pass);
			if (batch.isEmpty()) continue;
			int n = batch.size;
			int[] count = new int[n], first = new int[n], base = new int[n];
			for (int i = 0; i < n; i++) {
				count[i] = MemoryUtil.memGetInt(batch.pElementCount + ((long) i << 2));
				first[i] = (int) (MemoryUtil.memGetAddress(batch.pElementPointer + ((long) i << Pointer.POINTER_SHIFT)) >> 2);
				base[i] = MemoryUtil.memGetInt(batch.pBaseVertex + ((long) i << 2));
			}
			int stride = ((dev.vulkaniris.fabric.mixin.GlBufferArenaAccessorPort) res.getGeometryArena()).vulkaniris$stride();
			draws.add(new PostChain.RegionDraw(stride, vk(res.getGeometryBuffer()), useRegionIndex ? vk(res.getIndexBuffer()) : shared,
				vk(SodiumVulkan.chunkData(res)),
				(region.getOriginX() - camera.intX) - camera.fracX,
				(region.getOriginY() - camera.intY) - camera.fracY,
				(region.getOriginZ() - camera.intZ) - camera.fracZ,
				Math.toIntExact(now - region.getCreationTime()), count, first, base));
		}
		GpuTextureView atlas = pass.getAtlas();
		GpuTextureView light = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView();
		return s.drawTerrain(kind, draws, new PostChain.TerrainInputs(view("block atlas", atlas), view("lightmap", light)));
	}

	private static dev.vulkaniris.vk.GpuImage view(String name, GpuTextureView v) {
		var t = v.texture();
		return dev.vulkaniris.vk.GpuImage.external(name, PolydiumAccess.vkImage(t), PolydiumAccess.vkImageView(v), PolydiumAccess.vkFormat(t),
			t.getWidth(0), t.getHeight(0), org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT, PolydiumAccess.TEXTURE_LAYOUT);
	}

	private static long vk(GlBuffer buffer) {
		return ((VulkanGpuBuffer) VkCommandList.gpu(buffer)).vkBuffer();
	}
}
