import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}
// AGP 9 supplies Kotlin; root KGP pins its compiler version for both modules.
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun buildConfigString(value: String): String = "\"" + buildString {
    value.forEach { c ->
        append(when (c) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
        })
    }
} + "\""
android {
    namespace = "com.vtubephone.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.vtubephone.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GEMINI_API_KEY", buildConfigString(localSecrets.getProperty("GEMINI_API_KEY", "")))
        buildConfigField("String", "VOICEVOX_API_KEY", buildConfigString(localSecrets.getProperty("VOICEVOX_API_KEY", "")))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
abstract class Live2dAssets : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val prepareLive2dAssets = tasks.register<Live2dAssets>("prepareLive2dAssets") {
    outputDirectory.set(layout.buildDirectory.dir("generated/live2d/assets"))
}
apply(from = "live2d-assets.gradle.kts")
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLive2dAssets, Live2dAssets::outputDirectory)
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.webkit)
}
