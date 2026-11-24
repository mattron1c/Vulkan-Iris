package net.irisshaders.iris.pipeline.vulkan;

import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.function.FunctionReturn;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.Std140Type;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Vulkan replacement for Iris's GL uniform upload. Iris's own uniform definitions ({@code CommonUniforms},
 * {@code CustomUniforms}) register suppliers on this holder exactly as they would on a GL program; instead of
 * {@code glUniform*} calls, {@link #write} evaluates every supplier the translated program's std140 block
 * ({@code iris_Uniforms}) needs and stores it at the offset {@code VulkanGlslTranslator} assigned.
 *
 * <p>Values are written by the GLSL type the shader declares (int/bool vs float vs double), like a GL driver
 * converting a matching {@code glUniform*} call. Update frequencies and notifiers are ignored: everything is
 * evaluated every frame. A supplier that throws (several dynamic uniforms read GL state that doesn't exist on
 * Vulkan) writes zeros and is reported by {@link #failures()}.</p>
 */
public final class VulkanUniformWriter implements DynamicUniformHolder {
	/** Component values, column-major for matrices, element-major for arrays. */
	@FunctionalInterface
	public interface Source {
		double[] get();
	}

	private final Map<String, Source> sources = new LinkedHashMap<>();
	private final Map<String, UniformType> external = new HashMap<>();
	private final Map<String, String> failures = new HashMap<>();
	private final FunctionReturn held = new FunctionReturn();
	private CustomUniforms custom;

	public void attachCustom(CustomUniforms custom) {
		this.custom = custom;
	}

	/** Supplies a value Iris leaves to the renderer (an "externally managed" uniform), or replaces one. */
	public void override(String name, Source source) {
		sources.put(name, source);
	}

	/** True if a shader uniform of this name will get a real value (used as the translator's "registered" test). */
	public boolean provides(String name) {
		return sources.containsKey(name) || customUniform(name) != null;
	}

	/** Externally managed names nobody has supplied yet. */
	public boolean isExternal(String name) {
		return external.containsKey(name) && !sources.containsKey(name);
	}

	public Map<String, String> failures() {
		return Collections.unmodifiableMap(failures);
	}

	/** Members of {@code layout} that get no value (written as zero). */
	public List<String> unresolved(ShaderResourceLayout layout) {
		List<String> out = new ArrayList<>();
		for (String name : new TreeSet<>(layout.uniforms().keySet())) if (!provides(name)) out.add(name);
		return out;
	}

	/** Fills {@code dst} (at least {@code layout.uniformBufferSize()} bytes from its position 0) for one program. */
	public void write(ShaderResourceLayout layout, ByteBuffer dst) {
		ByteBuffer b = dst.duplicate().order(ByteOrder.nativeOrder());
		for (int i = 0; i < layout.uniformBufferSize(); i++) b.put(i, (byte) 0);
		for (Map.Entry<String, ShaderResourceLayout.Uniform> e : layout.uniforms().entrySet()) {
			double[] v = evaluate(e.getKey());
			if (v != null) put(b, e.getValue().offset(), e.getValue().type(), v);
		}
	}

	private double[] evaluate(String name) {
		Source s = sources.get(name);
		try {
			if (s != null) return s.get();
			CachedUniform c = customUniform(name);
			if (c != null) {
				c.writeTo(held);
				return fromCustom(c, held);
			}
		} catch (RuntimeException ex) {
			failures.putIfAbsent(name, ex.toString());
		}
		return null;
	}

	private CachedUniform customUniform(String name) {
		if (custom == null || !custom.hasVariable(name)) return null;
		Expression e = custom.getVariable(name);
		return e instanceof CachedUniform c ? c : null;
	}

	private static double[] fromCustom(CachedUniform c, FunctionReturn r) {
		Object o = r.objectReturn;
		String t = String.valueOf(c.getType()).toLowerCase();
		if (t.contains("bool")) return new double[]{r.booleanReturn ? 1 : 0};
		if (t.equals("int")) return new double[]{r.intReturn};
		if (t.equals("float")) return new double[]{r.floatReturn};
		double[] v = components(o);
		if (v == null) throw new IllegalStateException("custom uniform " + c.getName() + " has unsupported value " + o);
		return v;
	}

	static double[] components(Object o) {
		if (o instanceof Vector2f v) return new double[]{v.x, v.y};
		if (o instanceof Vector3f v) return new double[]{v.x, v.y, v.z};
		if (o instanceof Vector4f v) return new double[]{v.x, v.y, v.z, v.w};
		if (o instanceof Vector2i v) return new double[]{v.x, v.y};
		if (o instanceof Vector3i v) return new double[]{v.x, v.y, v.z};
		if (o instanceof Vector4i v) return new double[]{v.x, v.y, v.z, v.w};
		if (o instanceof Vector3d v) return new double[]{v.x, v.y, v.z};
		if (o instanceof Matrix4fc m) return mat4(m);
		if (o instanceof Matrix3fc m) return mat3(m);
		return null;
	}

	static double[] mat4(Matrix4fc m) {
		float[] f = m.get(new float[16]);
		double[] d = new double[16];
		for (int i = 0; i < 16; i++) d[i] = f[i];
		return d;
	}

	static double[] mat3(Matrix3fc m) {
		float[] f = m.get(new float[9]);
		double[] d = new double[9];
		for (int i = 0; i < 9; i++) d[i] = f[i];
		return d;
	}

	/** std140 store of {@code v} into a member of type {@code t} at {@code offset}; extra values are dropped, missing ones stay zero. */
	static void put(ByteBuffer b, int offset, Std140Type t, double[] v) {
		String g = t.glsl();
		boolean integer = g.startsWith("int") || g.startsWith("ivec") || g.startsWith("uint") || g.startsWith("uvec")
			|| g.startsWith("bool") || g.startsWith("bvec");
		boolean unsigned = g.startsWith("uint") || g.startsWith("uvec");
		int rows = t.rows(), columns = t.columns(), sb = t.scalarBytes();
		int elements = 1;
		for (int d : t.dimensions()) elements *= d;
		int columnStride = columns > 1 ? t.matrixStride() : 0;
		int elementSize = columns > 1 ? columns * columnStride : sb * (rows == 3 ? 4 : rows);
		int elementStride = t.dimensions().isEmpty() ? elementSize : Std140Type.align(elementSize, 16);
		int k = 0;
		for (int e = 0; e < elements; e++) {
			for (int c = 0; c < columns; c++) {
				for (int r = 0; r < rows; r++, k++) {
					if (k >= v.length) return;
					int at = offset + e * elementStride + c * columnStride + r * sb;
					double x = v[k];
					if (integer) {
						long n = unsigned ? (long) x & 0xFFFFFFFFL : (long) x;
						b.putInt(at, (int) n);
					} else if (sb == 8) {
						b.putDouble(at, x);
					} else {
						b.putFloat(at, (float) x);
					}
				}
			}
		}
	}

	private VulkanUniformWriter add(String name, Source s) {
		sources.putIfAbsent(name, s);
		return this;
	}

	// ---- UniformHolder ----

	@Override
	public UniformHolder uniform1f(UniformUpdateFrequency f, String name, FloatSupplier value) {
		return add(name, () -> new double[]{value.getAsFloat()});
	}

	@Override
	public UniformHolder uniform1f(UniformUpdateFrequency f, String name, IntSupplier value) {
		return add(name, () -> new double[]{value.getAsInt()});
	}

	@Override
	public UniformHolder uniform1f(UniformUpdateFrequency f, String name, DoubleSupplier value) {
		return add(name, () -> new double[]{value.getAsDouble()});
	}

	@Override
	public UniformHolder uniform1i(UniformUpdateFrequency f, String name, IntSupplier value) {
		return add(name, () -> new double[]{value.getAsInt()});
	}

	@Override
	public UniformHolder uniform1b(UniformUpdateFrequency f, String name, BooleanSupplier value) {
		return add(name, () -> new double[]{value.getAsBoolean() ? 1 : 0});
	}

	@Override
	public UniformHolder uniform2f(UniformUpdateFrequency f, String name, Supplier<Vector2f> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniform2i(UniformUpdateFrequency f, String name, Supplier<Vector2i> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniform3f(UniformUpdateFrequency f, String name, Supplier<Vector3f> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniform3i(UniformUpdateFrequency f, String name, Supplier<Vector3i> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniformTruncated3f(UniformUpdateFrequency f, String name, Supplier<Vector4f> value) {
		return add(name, () -> {
			Vector4f v = value.get();
			return new double[]{v.x, v.y, v.z};
		});
	}

	@Override
	public UniformHolder uniform3d(UniformUpdateFrequency f, String name, Supplier<Vector3d> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniform4f(UniformUpdateFrequency f, String name, Supplier<Vector4f> value) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public UniformHolder uniform4fArray(UniformUpdateFrequency f, String name, Supplier<float[]> value) {
		return add(name, () -> floats(value.get()));
	}

	@Override
	public UniformHolder uniformMatrix(UniformUpdateFrequency f, String name, Supplier<Matrix4fc> value) {
		return add(name, () -> mat4(value.get()));
	}

	@Override
	public UniformHolder uniformMatrixFromArray(UniformUpdateFrequency f, String name, Supplier<float[]> value) {
		return add(name, () -> floats(value.get()));
	}

	@Override
	public UniformHolder externallyManagedUniform(String name, UniformType type) {
		external.put(name, type);
		return this;
	}

	// ---- DynamicUniformHolder (notifiers ignored: everything is evaluated each frame) ----

	@Override
	public DynamicUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier n) {
		return add(name, () -> new double[]{value.getAsFloat()});
	}

	@Override
	public DynamicUniformHolder uniform1f(String name, IntSupplier value, ValueUpdateNotifier n) {
		return add(name, () -> new double[]{value.getAsInt()});
	}

	@Override
	public DynamicUniformHolder uniform1f(String name, DoubleSupplier value, ValueUpdateNotifier n) {
		return add(name, () -> new double[]{value.getAsDouble()});
	}

	@Override
	public DynamicUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier n) {
		return add(name, () -> new double[]{value.getAsInt()});
	}

	@Override
	public DynamicUniformHolder uniform2f(String name, Supplier<Vector2f> value, ValueUpdateNotifier n) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public DynamicUniformHolder uniform2i(String name, Supplier<Vector2i> value, ValueUpdateNotifier n) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public DynamicUniformHolder uniform3f(String name, Supplier<Vector3f> value, ValueUpdateNotifier n) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public DynamicUniformHolder uniform4f(String name, Supplier<Vector4f> value, ValueUpdateNotifier n) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public DynamicUniformHolder uniform4fArray(String name, Supplier<float[]> value, ValueUpdateNotifier n) {
		return add(name, () -> floats(value.get()));
	}

	@Override
	public DynamicUniformHolder uniform4i(String name, Supplier<Vector4i> value, ValueUpdateNotifier n) {
		return add(name, () -> components(value.get()));
	}

	@Override
	public DynamicUniformHolder uniformMatrix(String name, Supplier<Matrix4fc> value, ValueUpdateNotifier n) {
		return add(name, () -> mat4(value.get()));
	}

	@Override
	public DynamicUniformHolder uniformMatrix3(String name, Supplier<Matrix3fc> value, ValueUpdateNotifier n) {
		return add(name, () -> mat3(value.get()));
	}

	private static double[] floats(float[] f) {
		double[] d = new double[f.length];
		for (int i = 0; i < f.length; i++) d[i] = f[i];
		return d;
	}
}
