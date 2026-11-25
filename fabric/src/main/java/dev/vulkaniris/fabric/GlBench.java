package dev.vulkaniris.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The "A" side of {@link Bench}: Iris's own GL pipeline (this fork without Polydium is Iris 1.10.8 on Sodium), loading
 * each pack through Iris's config and reload, as a player would. Same world, scene and options as the Vulkan side.
 * Writes {@code vulkaniris-bench-gl.txt} in the game directory, then quits.
 */
public final class GlBench {
	private static final boolean ACTIVE = Bench.ENABLED && !FabricLoader.getInstance().isModLoaded("polydium");
	private static final String WORLD = "vulkaniris-phase6";
	/** As PostChainCheck: frames for Sodium to rebuild every chunk with the pack's programs. */
	private static final int REBUILD_FRAMES = 240;
	private static final int SETTLE_FRAMES = 300;

	private static final StringBuilder report = new StringBuilder();
	private static int frames, worldFrames, packFrames;
	private static boolean worldRequested, done, sceneReady;
	private static List<Path> packs;
	private static int packIndex = -1;
	private static Bench bench;

	private GlBench() {
	}

	/** GameRenderer.render TAIL. */
	public static void afterGameRender() {
		if (!ACTIVE || done) return;
		frames++;
		Minecraft mc = Minecraft.getInstance();
		try {
			if (mc.level == null) {
				if (!worldRequested && frames > 60 && mc.screen != null) {
					worldRequested = true;
					if (!Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
						fail("world '" + WORLD + "' missing: run prodPhase7 once to create it");
						return;
					}
					mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("world open cancelled"));
				}
				return;
			}
			worldFrames++;
			if (worldFrames < SETTLE_FRAMES) return;
			if (!sceneReady) {
				sceneReady = true;
				packs = packs(mc);
				line("A side: Iris " + Iris.getVersion() + " on GL (" + com.mojang.blaze3d.systems.RenderSystem.getDevice().getRenderer() + ", "
					+ com.mojang.blaze3d.systems.RenderSystem.getDevice().getVendor() + ")");
				line("World '" + WORLD + "', player at " + mc.player.blockPosition().toShortString());
				line(packs.size() + " pack(s): " + packs.stream().map(p -> p.getFileName().toString()).toList());
				PostChainCheck.placeScene(mc);
				Bench.freeze(mc);
				Bench.options(mc);
				line("options: " + Bench.settings(mc) + "; measured " + Bench.SECONDS + " s after " + Bench.WARMUP_SECONDS + " s warm-up");
				// control: the same scene with shaders off (vanilla rendering on Sodium, GL)
				line("");
				line("== (no pack: shaders off)");
				Iris.getIrisConfig().setShadersEnabled(false);
				Iris.getIrisConfig().save();
				Iris.reload();
				bench = new Bench();
				packFrames = 0;
				return;
			}
			if (bench == null) return;
			packFrames++;
			if (packFrames == REBUILD_FRAMES) bench.warmUp();
			if (bench.frame()) {
				line("bench: " + bench.result());
				nextPack();
			}
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] GL bench crashed", t);
			fail("exception: " + t);
		}
	}

	private static List<Path> packs(Minecraft mc) throws Exception {
		Path dir = mc.gameDirectory.toPath().resolve("shaderpacks");
		String filter = System.getProperty("vulkaniris.phase6.packs", "");
		List<String> wanted = filter.isBlank() ? List.of() : Arrays.stream(filter.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).toList();
		try (Stream<Path> s = Files.list(dir)) {
			return new ArrayList<>(s.filter(p -> p.toString().endsWith(".zip") || Files.isDirectory(p))
				.filter(p -> !p.getFileName().toString().startsWith("vulkaniris-selftest"))
				.filter(p -> wanted.isEmpty() || wanted.stream().anyMatch(w -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(w)))
				.sorted().toList());
		}
	}

	private static void nextPack() throws Exception {
		packIndex++;
		if (packIndex >= packs.size()) {
			finish();
			return;
		}
		String name = packs.get(packIndex).getFileName().toString();
		line("");
		line("== " + name);
		Iris.getIrisConfig().setShaderPackName(name);
		Iris.getIrisConfig().setShadersEnabled(true);
		Iris.getIrisConfig().save();
		Iris.reload();
		if (!Iris.getCurrentPack().isPresent() || !name.equals(Iris.getCurrentPackName())) {
			line("FAIL: Iris did not load the pack (current: " + Iris.getCurrentPackName() + ")");
			bench = null;
			nextPack();
			return;
		}
		bench = new Bench();
		packFrames = 0;
	}

	private static void fail(String why) {
		line("FAIL: " + why);
		finish();
	}

	private static void finish() {
		done = true;
		try {
			Iris.getIrisConfig().setShadersEnabled(false);
			Iris.getIrisConfig().save();
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-bench-gl.txt"), report.toString());
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write the GL bench report", e);
		}
		Minecraft.getInstance().stop();
	}

	private static void line(String s) {
		Iris.logger.info("[Vulkan Iris] GL bench: " + s);
		report.append(s).append('\n');
	}
}
