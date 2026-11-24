package dev.vulkaniris.fabric;

import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Pack raw textures ({@code .dat}/{@code .bin} with a pixel format/type in shaders.properties) as Vulkan upload data.
 * The texels are uploaded in their source layout (what GL receives in glTexImage); GL would then convert them to
 * the declared internal format, which for every combination supported here holds the same values. RGB data is
 * expanded to RGBA with alpha = 1, as GL does when sampling an RGB texture. Anything else is refused, not guessed.
 */
final class RawTextures {
	record Upload(int format, byte[] bytes) {
	}

	private RawTextures() {
	}

	static Upload convert(byte[] src, PixelFormat pf, PixelType pt, long texels) {
		int channels = pf.getComponentCount();
		boolean integer = pf.isInteger();
		boolean bgr = pf == PixelFormat.BGR || pf == PixelFormat.BGRA || pf == PixelFormat.BGR_INTEGER || pf == PixelFormat.BGRA_INTEGER;
		int size = switch (pt) {
			case BYTE, UNSIGNED_BYTE -> 1;
			case SHORT, UNSIGNED_SHORT, HALF_FLOAT -> 2;
			case INT, UNSIGNED_INT, FLOAT -> 4;
			default -> throw new IllegalStateException("raw texture pixel type " + pt + " (not implemented yet)");
		};
		int outChannels = channels == 3 ? 4 : channels;
		byte[] out = new byte[Math.toIntExact(texels * outChannels * size)];
		byte[] alphaOne = alphaOne(pt, integer);
		for (long t = 0; t < texels; t++) {
			int si = (int) (t * channels * size), di = (int) (t * outChannels * size);
			for (int c = 0; c < channels; c++) {
				int from = bgr && c < 3 ? 2 - c : c; // BGR(A) → RGB(A)
				System.arraycopy(src, si + from * size, out, di + c * size, size);
			}
			if (channels == 3) System.arraycopy(alphaOne, 0, out, di + 3 * size, size);
		}
		return new Upload(format(outChannels, pt, integer), out);
	}

	private static byte[] alphaOne(PixelType pt, boolean integer) {
		ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
		switch (pt) {
			case BYTE -> b.put((byte) (integer ? 1 : 127));
			case UNSIGNED_BYTE -> b.put((byte) (integer ? 1 : 255));
			case SHORT -> b.putShort((short) (integer ? 1 : 32767));
			case UNSIGNED_SHORT -> b.putShort((short) (integer ? 1 : 65535));
			case HALF_FLOAT -> b.putShort((short) 0x3C00);
			case INT -> b.putInt(integer ? 1 : Integer.MAX_VALUE);
			case UNSIGNED_INT -> b.putInt(integer ? 1 : -1);
			case FLOAT -> b.putFloat(1f);
			default -> throw new IllegalStateException(pt.toString());
		}
		byte[] r = new byte[b.position()];
		b.flip().get(r);
		return r;
	}

	private static int format(int channels, PixelType pt, boolean integer) {
		int[] f = switch (pt) {
			case UNSIGNED_BYTE -> integer ? new int[]{VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8A8_UINT}
				: new int[]{VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};
			case BYTE -> integer ? new int[]{VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8A8_SINT}
				: new int[]{VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8A8_SNORM};
			case UNSIGNED_SHORT -> integer ? new int[]{VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16A16_UINT}
				: new int[]{VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16A16_UNORM};
			case SHORT -> integer ? new int[]{VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16A16_SINT}
				: new int[]{VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16A16_SNORM};
			case HALF_FLOAT -> new int[]{VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT};
			case UNSIGNED_INT -> integer ? new int[]{VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32A32_UINT} : null;
			case INT -> integer ? new int[]{VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32A32_SINT} : null;
			case FLOAT -> new int[]{VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT};
			default -> null;
		};
		if (f == null) throw new IllegalStateException("raw texture " + pt + (integer ? " integer" : " normalized") + " data (not implemented yet)");
		return f[channels == 1 ? 0 : channels == 2 ? 1 : 2];
	}
}
