# Vulkan Iris

A fork of [Iris Shaders](https://github.com/IrisShaders/Iris) that renders shader packs through a native Vulkan
backend, borrowing the Vulkan device of the renderer that owns it (Polydium today) instead of wrapping OpenGL.

**Work in progress — not usable for playing yet.** Status per phase: [docs/vulkan-iris](docs/vulkan-iris).

- `vulkan/` — the Vulkan core (`dev.vulkaniris.vk`), plain Java 21 + LWJGL, unit-tested on the real GPU (`gradlew :vulkan:test`).
- `fabric/src/main/java/dev/vulkaniris/fabric` — Minecraft side: device borrowing, in-game self-tests.
- In-game self-tests: `gradlew :fabric:prodFirstLight`, `gradlew :fabric:prodPhase4`, `gradlew :fabric:prodPhase5`, `gradlew :fabric:prodPhase6` (needs the jars listed in `run-deps/README.txt`).

Gradle must run on JDK 25 (mods target Java 21). Based on Iris 1.10.8 for Minecraft 1.21.11.

Licensed like Iris: LGPL-3.0-only (see LICENSE). Credit: the Iris team and contributors, whose history this repository keeps.
