import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
sourceSets.test { resources.srcDir("../app/src/main/assets") }
tasks.test { useJUnit(); testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
