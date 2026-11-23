// Vulkan Iris — native Vulkan backend.
//
// This module deliberately has NO Minecraft / Fabric dependency. It is plain Java 21 + LWJGL, so it can be
// compiled, unit-tested and run (`gradlew :vulkan:probe`) directly against the real GPU without launching the game.
// The `common` module wires it into Iris; the `fabric` module jar-in-jars it together with lwjgl-vulkan.

plugins {
    id("java-library")
}

// Must match the LWJGL version Minecraft 1.21.11 ships (3.3.3). VulkanMod pins the same version, so when both
// mods are installed Fabric's jar-in-jar resolution loads a single lwjgl-vulkan.
val LWJGL_VERSION = "3.3.3"

val lwjglNatives = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "natives-macos"
    else -> "natives-linux"
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    // Provided at runtime by Minecraft.
    compileOnly("org.lwjgl:lwjgl:$LWJGL_VERSION")
    // Not shipped by Minecraft: bundled into the mod jar by :fabric (jar-in-jar).
    // On Windows/Linux lwjgl-vulkan has no natives — it loads the system Vulkan loader (vulkan-1.dll / libvulkan.so.1).
    api("org.lwjgl:lwjgl-vulkan:$LWJGL_VERSION")
    // GLSL -> SPIR-V. Natives are bundled by :fabric (Polydium and VulkanMod bundle the same 3.3.3 build).
    api("org.lwjgl:lwjgl-shaderc:$LWJGL_VERSION")
    // Vulkan Memory Allocator. In-game we use the owner's allocator handle (Polydium/VulkanMod both run VMA 3.3.3).
    api("org.lwjgl:lwjgl-vma:$LWJGL_VERSION")

    testImplementation("org.lwjgl:lwjgl:$LWJGL_VERSION")
    testRuntimeOnly("org.lwjgl:lwjgl:$LWJGL_VERSION:$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-shaderc:$LWJGL_VERSION:$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-vma:$LWJGL_VERSION:$lwjglNatives")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    // Same LWJGL stack size VulkanMod uses; 64 KiB default overflows while loading NVIDIA's 1.4 instance function table.
    systemProperty("org.lwjgl.system.stackSize", "256")
    // Tests talk to the real GPU. Opt out on machines without Vulkan with -Pvulkaniris.skipGpuTests
    if (project.hasProperty("vulkaniris.skipGpuTests")) {
        systemProperty("vulkaniris.skipGpuTests", "true")
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// gradlew :vulkan:probe  — initialises Vulkan on this machine, prints the full diagnostics report, shuts down.
// Add -Pvalidation to request VK_LAYER_KHRONOS_validation (needs the Vulkan SDK installed).
tasks.register<JavaExec>("probe") {
    group = "vulkan iris"
    description = "Initialise Vulkan on this machine and print the Vulkan Iris diagnostics report"
    mainClass = "dev.vulkaniris.vk.VulkanProbe"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    systemProperty("org.lwjgl.system.stackSize", "256")
    args(layout.buildDirectory.file("vulkaniris-diagnostics.txt").get().asFile.absolutePath)
    if (project.hasProperty("validation")) systemProperty("vulkaniris.validation", "true")
}
