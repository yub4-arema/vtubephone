import java.util.Properties

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "vtubephone"
include(":core")

// Keep JVM tests usable on machines with no Android SDK (including a fresh checkout).
val local = Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val sdkCandidates = listOfNotNull(
    local.getProperty("sdk.dir"), System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
    System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" }
)
val sdk = sdkCandidates.map(::File).firstOrNull { it.resolve("platforms").isDirectory }
val coreOnly = providers.gradleProperty("coreOnly").orNull == "true"
if (!coreOnly && sdk != null) {
    // Mirror Studio's local SDK configuration for command-line use of the Windows default.
    if (local.getProperty("sdk.dir") == null && System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null) {
        local.setProperty("sdk.dir", sdk.absolutePath)
        file("local.properties").outputStream().use { local.store(it, "Local Android SDK (not committed)") }
    }
    include(":app")
} else {
    logger.lifecycle("Android :app excluded: ${if (coreOnly) "-PcoreOnly=true" else "SDK not found"}. :core:test is available.")
}
