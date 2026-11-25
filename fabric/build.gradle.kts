plugins {
    id("java")
    id("idea")
    id("fabric-loom") version("1.19.4")
}

val MINECRAFT_VERSION: String by rootProject.extra
val PARCHMENT_VERSION: String? by rootProject.extra
val FABRIC_LOADER_VERSION: String by rootProject.extra
val FABRIC_API_VERSION: String by rootProject.extra
val SODIUM_DEPENDENCY_FABRIC: Any by rootProject.extra
val MOD_VERSION: String by rootProject.extra

repositories {
    mavenLocal()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

base {
    archivesName.set("iris-fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${MINECRAFT_VERSION}")
    mappings(loom.layered {
        officialMojangMappings()
        if (PARCHMENT_VERSION != null) {
            parchment("org.parchmentmc.data:parchment-${MINECRAFT_VERSION}:${PARCHMENT_VERSION}@zip")
        }
    })
    modImplementation("net.fabricmc:fabric-loader:$FABRIC_LOADER_VERSION")

    fun addRuntimeFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        modRuntimeOnly(module)
    }

    fun addEmbeddedFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        modImplementation(module)
        include(module)
    }

    fun implementAndInclude(name: String) {
        modImplementation(name)
        include(name)
    }

    // Fabric API modules
    addEmbeddedFabricModule("fabric-api-base")
    addEmbeddedFabricModule("fabric-key-binding-api-v1")
    addRuntimeFabricModule("fabric-block-view-api-v2")
    addRuntimeFabricModule("fabric-rendering-fluids-v1")
    addRuntimeFabricModule("fabric-resource-loader-v0")
    addRuntimeFabricModule("fabric-lifecycle-events-v1")
    addRuntimeFabricModule("fabric-renderer-api-v1")

    modImplementation(SODIUM_DEPENDENCY_FABRIC)
    implementAndInclude("org.antlr:antlr4-runtime:4.13.1")
    implementAndInclude("io.github.douira:glsl-transformer:3.0.0-pre3")
    implementAndInclude("org.anarres:jcpp:1.4.14")

    implementation(project.project(":common").sourceSets.getByName("vendored").output)
    implementation(project.project(":common").sourceSets.getByName("api").output)
    compileOnly(project.project(":common").sourceSets.getByName("headers").output)
    implementation(project.project(":common").sourceSets.getByName("main").output)

    compileOnly(files(rootDir.resolve("DHApi.jar")))

    // ---- Vulkan Iris ------------------------------------------------------------------------------------------
    // Vulkan core (dev.vulkaniris.vk) + the LWJGL modules Minecraft doesn't ship. Same 3.3.3 builds VulkanMod and
    // Polydium bundle, so jar-in-jar resolution loads a single copy whichever mods are installed.
    implementation(project(":vulkan"))
    include(project(":vulkan"))
    include("org.lwjgl:lwjgl-vulkan:3.3.3")
    for (module in listOf("shaderc", "vma")) {
        include("org.lwjgl:lwjgl-$module:3.3.3")
        for (os in listOf("windows", "windows-arm64", "linux", "macos", "macos-arm64")) {
            include("org.lwjgl:lwjgl-$module:3.3.3:natives-$os")
        }
    }

    // Polydium's Vulkan backport is used through its public classes at runtime only (PolyForm Shield: never copy
    // its code, never bundle it). The jar comes from the user's own install, see run-deps/README.txt.
    val polydiumDir = rootDir.resolve("run-deps/polydium")
    modCompileOnly(files(polydiumDir.resolve("polydium-1.4.21+1.21.11.jar")))
    if (project.hasProperty("polydium")) {
        modLocalRuntime(fileTree(polydiumDir) { include("*.jar") })
    }
}

tasks.named("compileTestJava").configure {
    enabled = false
}

tasks.named("test").configure {
    enabled = false
}

loom {
    if (project(":common").file("src/main/resources/iris.accesswidener").exists())
        accessWidenerPath.set(project(":common").file("src/main/resources/iris.accesswidener"))

    @Suppress("UnstableApiUsage")
    mixin {
        defaultRefmapName.set("iris-fabric.refmap.json")
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
            // LWJGL's 64 KiB default stack overflows on large Vulkan drivers (VulkanMod uses 256 KiB too)
            vmArg("-Dorg.lwjgl.system.stackSize=256")
            if (project.hasProperty("firstlight")) vmArg("-Dvulkaniris.firstlight.selftest=true")
           // vmArgs("-Dmixin.debug.export=true")
           // vmArg("-XX:+AllowEnhancedClassRedefinition")
        }
        create("clientWithRenderdoc") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
            environmentVariable("LD_PRELOAD", "/home/ims/renderdoc/build/lib/librenderdoc.so")
            vmArgs("-DMC_DEBUG_ENABLED=true", "-DMC_DEBUG_DUMP_TEXTURE_ATLAS=true")
            programArgs("--renderDebugLabels")
        }
    }
}

tasks {
    processResources {
        from(project.project(":common").sourceSets.main.get().resources)
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(zipTree(project.project(":common").tasks.jar.get().archiveFile))

        manifest.attributes["Main-Class"] = "net.irisshaders.iris.LaunchWarn"
    }

    remapJar.get().destinationDirectory = rootDir.resolve("build").resolve("libs")
}

// gradlew :fabric:prodFirstLight — launches a *production* Minecraft 1.21.11 (real mods folder, like a launcher)
// with the built Vulkan Iris jar + Sodium + Polydium and its deps, and runs the Phase 3 first-light self-test.
// Production layout is required: PolyCompose nests Compose UI two jar levels deep, which Loom's dev runtime
// does not unpack.
fun registerProdSelfTest(name: String, flag: String) =
    tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>(name) {
        group = "vulkan iris"
        mods.from(tasks.named("remapJar"))
        mods.from(SODIUM_DEPENDENCY_FABRIC)
        mods.from(fileTree(rootDir.resolve("run-deps/polydium")) { include("*.jar") })
        jvmArgs.addAll("-Dorg.lwjgl.system.stackSize=256", "-D$flag=true")
        // e.g. gradlew :fabric:prodPhase6 -Dvulkaniris.phase6.packs=bsl,complementary
        System.getProperty("vulkaniris.phase6.packs")?.let { jvmArgs.add("-Dvulkaniris.phase6.packs=$it") }
        // A/B: gradlew :fabric:prodPhase7 -Dvulkaniris.phase7.noshadow=true skips the shadow draws
        System.getProperty("vulkaniris.phase7.noshadow")?.let { jvmArgs.add("-Dvulkaniris.phase7.noshadow=$it") }
        System.getProperty("vulkaniris.phase7.noentityshadow")?.let { jvmArgs.add("-Dvulkaniris.phase7.noentityshadow=$it") }
        runDir.set(project.layout.projectDirectory.dir("run-prod"))
    }

registerProdSelfTest("prodFirstLight", "vulkaniris.firstlight.selftest")
// gradlew :fabric:prodPhase4 — Phase 4 resource layer (Polydium's VMA, colortex, barriers, descriptors, resize) in-game
registerProdSelfTest("prodPhase4", "vulkaniris.phase4.selftest")
// gradlew :fabric:prodPhase5 — every pack in fabric/run-prod/shaderpacks: Iris patcher → Vulkan GLSL → SPIR-V → driver
registerProdSelfTest("prodPhase5", "vulkaniris.phase5.selftest")
// gradlew :fabric:prodPhase6 — each pack's begin/prepare/deferred/composite/final chain on Polydium's live frame, PNGs + report
registerProdSelfTest("prodPhase6", "vulkaniris.phase6.selftest")
// gradlew :fabric:prodPhase7 — Phase 6 test with terrain drawn by each pack's gbuffers_terrain/_cutout/_water programs
registerProdSelfTest("prodPhase7", "vulkaniris.phase7.selftest")
