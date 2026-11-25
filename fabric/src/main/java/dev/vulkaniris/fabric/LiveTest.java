package dev.vulkaniris.fabric;

import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Live-mode test ({@code -Dvulkaniris.live.test=true}, gradlew :fabric:prodLive): the player's path, with none of the
 * Phase 6/7 harness. It selects packs through Iris's own config and reload (what the shader pack screen does), presses
 * the same actions as Iris's keys (R reload, K toggle off and on), switches pack, and finally quits the game normally,
 * which must free Vulkan Iris's objects (MixinMinecraftShutdown). Each step waits for {@link PostChainCheck#liveActive}
 * to match what Iris's config says, and takes a vanilla screenshot (screenshots/vulkaniris-live-*.png).
 * Report: vulkaniris-live.txt.
 */
public final class LiveTest {
	static final boolean ENABLED = Boolean.getBoolean("vulkaniris.live.test");
	private static final String WORLD = "vulkaniris-phase6";
	/** Frames a step may take (pack build + chunk rebuild) before it fails. */
	private static final int STEP_TIMEOUT = 3000;
	/** Frames to let the step's result render before checking it and taking the screenshot. */
	private static final int SETTLE = 240;

	private static final StringBuilder report = new StringBuilder();
	private static int frames, stepFrames, activeFrames, step = -1;
	private static boolean worldRequested, finished, allPassed = true;
	private static int failuresAtStep;
	private static List<String> packs;

	private LiveTest() {
	}

	private record Step(String name, Runnable action, boolean expectActive) {
	}

	private static List<Step> steps;

	public static void afterGameRender() {
		if (!ENABLED || finished) return;
		frames++;
		Minecraft mc = Minecraft.getInstance();
		try {
			if (mc.level == null) {
				if (!worldRequested && frames > 60 && mc.screen != null) {
					worldRequested = true;
					if (!Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
						fail("world '" + WORLD + "' missing (run prodPhase7 once to create it)");
						return;
					}
					line("opening world '" + WORLD + "'");
					mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("world open cancelled"));
				}
				if (frames > 20_000) fail("timed out before the world opened");
				return;
			}
			if (steps == null) {
				if (frames < 200) return;
				setUp(mc);
			}
			stepFrames++;
			Step s = steps.get(step);
			boolean active = PostChainCheck.liveActive();
			activeFrames = active == s.expectActive ? activeFrames + 1 : 0;
			if (PostChainCheck.liveFailures > failuresAtStep) {
				failuresAtStep = PostChainCheck.liveFailures;
				fail(s.name + ": Vulkan Iris reported a failure (see log)");
				return;
			}
			if (activeFrames >= SETTLE) {
				// other mods ask Iris's public API: it must agree with what is drawn
				var api = net.irisshaders.iris.api.v0.IrisApi.getInstance();
				if (api.isShaderPackInUse() != s.expectActive || api.getConfig().areShadersEnabled() != s.expectActive) {
					fail(s.name + ": Iris API says pack in use " + api.isShaderPackInUse() + ", shaders enabled " + api.getConfig().areShadersEnabled()
						+ "; expected " + s.expectActive);
					return;
				}
				String shot = "vulkaniris-live-" + (step + 1) + (s.name.startsWith("gallery: ") ? "-" + s.name.substring(9).replaceAll("\\.zip$", "").replaceAll("[^A-Za-z0-9_.-]", "_") : "") + ".png";
				Screenshot.grab(mc.gameDirectory, shot, mc.getMainRenderTarget(), 1, c -> {
				});
				line("PASS step " + (step + 1) + ": " + s.name + " (shader pack drawn: " + active + ", after " + stepFrames + " frames; "
					+ "screenshots/" + shot + ")");
				nextStep();
			} else if (stepFrames > STEP_TIMEOUT) {
				fail(s.name + ": expected the pack drawn=" + s.expectActive + ", still " + active + " after " + stepFrames + " frames");
			}
		} catch (Throwable t) {
			Iris.logger.error("[Vulkan Iris] live test crashed", t);
			fail("exception: " + t);
		}
	}

	private static void setUp(Minecraft mc) {
		PostChainCheck.run(mc, "gamerule doDaylightCycle false", "weather clear", "time set noon");
		String list = System.getProperty("vulkaniris.live.packs", "BSL_v10.1.3.zip,ComplementaryReimagined_r5.9.3.zip");
		packs = Arrays.stream(list.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
		line("packs: " + packs + "; Iris mixins stripped by Polydium: " + VulkanIrisClient.irisStripped
			+ "; Iris key bindings registered: " + Iris.keybindsRegistered());
		String first = packs.get(0), second = packs.size() > 1 ? packs.get(1) : first;
		String gallery = System.getProperty("vulkaniris.live.gallery");
		if (gallery != null) {
			// -Dvulkaniris.live.gallery=1600x900: one screenshot per pack at that window size (store page images)
			String[] wh = gallery.split("x");
			// no tutorial toast over the store images
			mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
			mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
			mc.getToastManager().clear();
			org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
			List<Step> g = new java.util.ArrayList<>();
			g.add(new Step("shaders off (vanilla on Polydium)", () -> select(first, false), false));
			for (String pk : packs) g.add(new Step("gallery: " + pk, () -> select(pk, true), true));
			steps = g;
			step = -1;
			nextStep();
			return;
		}
		steps = List.of(
			new Step("select " + first + " and reload (shader pack screen: Apply)", () -> select(first, true), true),
			new Step("reload again (R)", LiveTest::reload, true),
			new Step("open the shader pack screen (O, through Polydium's key)", LiveTest::openScreen, true),
			new Step("close it", () -> Minecraft.getInstance().setScreen(null), true),
			new Step("shaders off (K)", () -> select(first, false), false),
			new Step("shaders on (K)", () -> select(first, true), true),
			new Step("switch to " + second, () -> select(second, true), true),
			new Step("resize the window", LiveTest::resize, true)
		);
		step = -1;
		nextStep();
	}

	private static void nextStep() {
		step++;
		stepFrames = 0;
		activeFrames = 0;
		if (step >= steps.size()) {
			finish();
			return;
		}
		line("step " + (step + 1) + ": " + steps.get(step).name);
		steps.get(step).action.run();
	}

	private static void select(String pack, boolean enabled) {
		try {
			Iris.getIrisConfig().setShaderPackName(pack);
			Iris.getIrisConfig().setShadersEnabled(enabled);
			Iris.getIrisConfig().save();
			Iris.reload();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void reload() {
		try {
			Iris.reload();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void openScreen() {
		try {
			Object screen = Class.forName("org.polyfrost.polydium.vulkan.IrisVulkan").getMethod("placeholder", net.minecraft.client.gui.screens.Screen.class)
				.invoke(null, (Object) null);
			line("  Polydium's shader pack entry opened " + screen.getClass().getName());
			if (!(screen instanceof net.irisshaders.iris.gui.screen.ShaderPackScreen)) {
				fail("Polydium's entry did not open Iris's shader pack screen");
				return;
			}
			Minecraft.getInstance().setScreen((net.minecraft.client.gui.screens.Screen) screen);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void resize() {
		var w = Minecraft.getInstance().getWindow();
		org.lwjgl.glfw.GLFW.glfwSetWindowSize(w.handle(), w.getScreenWidth() - 100, w.getScreenHeight() - 60);
	}

	private static void fail(String why) {
		allPassed = false;
		line("FAIL: " + why);
		finish();
	}

	private static void finish() {
		if (finished) return;
		finished = true;
		String head = "Vulkan Iris live-mode test — " + ZonedDateTime.now() + "\nRESULT: " + (allPassed && steps != null ? "PASS" : "FAIL")
			+ " — " + Math.max(0, step) + "/" + (steps == null ? 0 : steps.size()) + " steps\n";
		try {
			Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("vulkaniris-live.txt"), head + report);
		} catch (Exception e) {
			Iris.logger.error("[Vulkan Iris] could not write the live report", e);
		}
		Iris.logger.info("[Vulkan Iris] LIVE " + head + report);
		// a normal quit: Minecraft.close runs MixinMinecraftShutdown, which logs "live mode shut down"
		Minecraft.getInstance().stop();
	}

	private static void line(String s) {
		report.append(s).append('\n');
		Iris.logger.info("[Vulkan Iris] live test: " + s);
	}
}
