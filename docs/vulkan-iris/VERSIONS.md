# Minecraft versions: what Vulkan Iris supports, and why

**Supported and tested:** Minecraft **1.21.11** on Fabric, with **Sodium 0.8.14** and **Polydium 1.4.21** (its
Vulkan backend). Every test and benchmark in this repository ran on that combination.

**Not supported:** every other version. One jar cannot cover "1.21 – 26.3". Iris, Sodium and Minecraft's renderer
change between those versions, so each version needs its own build. Vulkan Iris does not draw anything itself. It
takes over a Vulkan device that another mod or the game already runs, and which one does that depends on the version:

| Minecraft | Who runs Vulkan | State for Vulkan Iris |
|---|---|---|
| 1.21 – 1.21.10 | Only VulkanMod, which replaces the whole renderer (its own chunk renderer, no Sodium) | Not possible with this design. Vulkan Iris draws through Sodium's terrain path on Polydium's Mojang-style Vulkan device. A VulkanMod version would be a separate renderer integration, written and tested per VulkanMod release. Polydium does not support these versions. |
| **1.21.11** | **Polydium** (backport of Mojang's Vulkan backend) | **This build.** |
| 26.1 – 26.1.2 | Polydium | Possible in principle: Polydium supports these versions. It needs a port of the whole fork onto Iris's and Sodium's 26.1 branches, then the full test and benchmark runs again. Not done. |
| 26.2 – 26.3 | Minecraft itself (Mojang's native Vulkan backend) | Upstream Iris (`26.3-new` branch) already has its own Vulkan backend for these versions. Use upstream Iris there. A Vulkan Iris build would only make sense if it measurably beats upstream, and that is unmeasured. |

**Without Polydium on Vulkan** (Polydium absent, or set to OpenGL), this jar is Iris 1.10.8 for 1.21.11 and
renders on OpenGL exactly as Iris does. That holds on 1.21.11 only, because the jar is built against 1.21.11.

The `fabric.mod.json` therefore declares `minecraft: 1.21.11`, so launchers refuse other versions. A wrong-version
jar would otherwise crash at startup.
