package dev.vulkaniris.vk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which descriptor bindings a SPIR-V module actually uses, the way a GL linker reports "active" uniforms. glslang keeps
 * every declared uniform as an {@code OpVariable} with its set and binding, used or not. A binding counts as used when
 * its variable's id is an operand of any instruction other than debug names, decorations, the declaration itself and
 * the entry point's interface list. A literal operand that happens to equal the id also counts, so this can only
 * over-report use, never miss one.
 */
public final class SpirvUse {
	private static final int MAGIC = 0x07230203;
	private static final int OP_SOURCE = 3, OP_NAME = 5, OP_MEMBER_NAME = 6, OP_STRING = 7, OP_LINE = 8, OP_ENTRY_POINT = 15,
		OP_CONSTANT = 43, OP_VARIABLE = 59, OP_DECORATE = 71, OP_MEMBER_DECORATE = 72, OP_MODULE_PROCESSED = 330;
	private static final int DECORATION_BINDING = 33, DECORATION_DESCRIPTOR_SET = 34;

	private SpirvUse() {
	}

	/** {@code set << 32 | binding} of every binding used by any of {@code modules}. */
	public static Set<Long> usedBindings(List<byte[]> modules) {
		Set<Long> used = new HashSet<>();
		for (byte[] m : modules) used.addAll(usedBindings(m));
		return used;
	}

	public static Set<Long> usedBindings(byte[] module) {
		IntBuffer w = ByteBuffer.wrap(module).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		if (w.limit() < 5 || w.get(0) != MAGIC) throw new IllegalArgumentException("not a SPIR-V module");
		Map<Integer, Integer> set = new HashMap<>(), binding = new HashMap<>();
		Set<Integer> referenced = new HashSet<>();
		for (int pass = 0; pass < 2; pass++) {
			int i = 5;
			while (i < w.limit()) {
				int word = w.get(i), count = word >>> 16, op = word & 0xFFFF;
				if (count == 0 || i + count > w.limit()) throw new IllegalArgumentException("malformed SPIR-V at word " + i);
				if (pass == 0) {
					if (op == OP_DECORATE && count >= 4) {
						int dec = w.get(i + 2);
						if (dec == DECORATION_BINDING) binding.put(w.get(i + 1), w.get(i + 3));
						else if (dec == DECORATION_DESCRIPTOR_SET) set.put(w.get(i + 1), w.get(i + 3));
					}
				} else if (op != OP_SOURCE && op != OP_NAME && op != OP_MEMBER_NAME && op != OP_STRING && op != OP_LINE && op != OP_ENTRY_POINT
					&& op != OP_CONSTANT && op != OP_VARIABLE && op != OP_DECORATE && op != OP_MEMBER_DECORATE && op != OP_MODULE_PROCESSED) {
					for (int k = 1; k < count; k++) {
						int id = w.get(i + k);
						if (binding.containsKey(id)) referenced.add(id);
					}
				}
				i += count;
			}
		}
		Set<Long> used = new HashSet<>();
		for (int id : referenced) used.add((long) set.getOrDefault(id, 0) << 32 | (binding.get(id) & 0xFFFFFFFFL));
		return used;
	}
}
