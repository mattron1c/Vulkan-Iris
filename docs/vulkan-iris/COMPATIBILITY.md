# Compatibility with other mods

Every row is from an automated in-game run of `gradlew :fabric:prodLive` (the player's path, no test harness) with the
listed mods added from a real mods folder (`-Dvulkaniris.compat.mods=<folder>`). The run:

1. selects BSL through Iris's config and reloads (what the shader pack screen's Apply does)
2. reloads again (R)
3. opens the shader pack screen through Polydium's O key, then closes it
4. turns shaders off and on (K)
5. switches to Complementary Reimagined
6. resizes the window
7. quits normally

A step passes when the pack is (or, with shaders off, is not) drawn for 240 frames in a row with no Vulkan Iris
error, and the run passes when quitting frees every Vulkan Iris image. A mod listed here loaded and ran next to Vulkan
Iris through those steps. That is not proof that every feature of that mod looks right with every shader pack.

## Set 1 — 2026-11-25: PASS, 8/8 steps, 0 images left at shutdown

Minecraft 1.21.11, Fabric Loader 0.19.5, Sodium 0.8.14, Polydium 1.4.21 on Vulkan (NVIDIA RTX 5060).
271 mods loaded, counting Fabric API modules and nested libraries. Top-level mods:

Almanac, Architectury, AsyncLogger, BadOptimizations, Better Block Entities, C2ME, ClientKits, Cloth Config,
ConsumableOptimizer, Crash Assistant, CrashExploitFixer, DawnPad, Debugify, EntityCulling, ExploitPreventer, Fabric
API, Fabric Language Kotlin, Fast IP Ping, FerriteCore, Fzzy Config, GeckoLib, Gnetum, IdleTweaks, ImmediatelyFast,
Ixeris, Krypton, Ksyxis, LetMeDespawn, Lithium, Lithostitched, ModernFix, Mod Menu, MoreCulling, Clumps, NoRollback,
NERB, OneConfig, PacketFixer, Particle Core, PVPBOTSX, Placeholder API, PolyCompose, Polydium, PolyReflex,
PvPOptimizer, Reese's Sodium Options, Resourcify, ScalableLux, ServerCore, ShatterLib, Sodium Extra, Sodium LOD Fixer,
Spotify Widget, STFU, Tectonic, ThreadTweak, ToadLib, VMP, WalksyLib, Windows Priority Boost, XLPackets, YACL,
ZConfig, ZFastNoise.

Left out on purpose: **Voxy** (and its add-ons). It draws its LODs with OpenGL itself, which cannot run while
Polydium uses Vulkan, with or without Vulkan Iris. The same applies to **Distant Horizons**.

Seen in the log, not caused by Vulkan Iris: two `GL ERROR @ Pre render: Cannot query extension without a current
OpenGL context` lines at startup. They appear only with the added mods (zero times in runs without them). Another mod
queries GL extensions, which do not exist on Vulkan. Rendering was not affected.

## Set 2 — 2026-11-25: PASS, 8/8 steps

Same setup, a second real mods folder (a PvP/utility client profile), 339 mods loaded counting Fabric API modules
and nested libraries. Top-level mods besides Fabric API, Sodium and
Polydium:

AppleSkin, Ambient Sounds, Animatica, Axiom, BadOptimizations, Better Block Entities, Bobby, C2ME, Chat Heads,
Cloth Config, Controlling, CreativeCore, Cull Leaves, Debugify, Entity Model Features, Entity Texture Features,
EntityCulling, Essential, Flashback, Forge Config API Port, Krypton, Litematica (+ MaLiLib, Litematica Printer),
Lithium, Mod Menu, MoreCulling, No Chat Reports, Not Enough Crashes, OneConfig, Presence Footsteps, ScalableLux,
Searchables, Shulker Box Tooltip, Simple Voice Chat, Sodium Extra, Sound Physics Remastered, spark, STFU, ThreadTweak,
VMP, Visuality, XL Packets, YACL, ZFastNoise and about 40 small PvP/HUD tweaks.

Seen in the log, not caused by Vulkan Iris: a `ClassCastException` from Cart Optimizer's own packet mixin, and
Complementary's `Unknown variable: BIOME_SULFUR_CAVES` warning (a biome 1.21.11 doesn't have; stock Iris logs it too).

## Not compatible (by design)

| Mod | Why |
|---|---|
| Iris (the original) | Vulkan Iris *is* Iris (it declares `provides: iris`); install one or the other. |
| OptiFine / OptiFabric | Replaces the renderer; Iris already refuses it. |
| VulkanMod | A different Vulkan renderer without Sodium; Vulkan Iris draws through Polydium. |
| Voxy, Distant Horizons | OpenGL-only renderers; they need Polydium on OpenGL, where Vulkan Iris is plain Iris. |
