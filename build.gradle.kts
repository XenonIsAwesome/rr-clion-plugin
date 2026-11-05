plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.xenonisawesome"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

val platformType = providers.gradleProperty("platformType").get()
val platformVersion = providers.gradleProperty("platformVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(platformType, platformVersion)
        // The two modules are bundled into the main plugin jar set.
        pluginComposedModule(implementation(project(":core")))
        pluginComposedModule(implementation(project(":clion")))
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
            // No upper bound: the plugin only uses stable platform APIs, and the verifier
            // (verifyPlugin) is the safety net for newer builds.
            untilBuild = provider { null }
        }
        changeNotes = "Initial version: record with rr, replay in the CLion debugger, reverse execution."
    }
    pluginVerification {
        ides {
            // Verify against every released CLion build from since-build onwards (incl. EAPs),
            // so new IDE releases are checked automatically without editing this file.
            select {
                types = listOf(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.CLion)
                channels = listOf(org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RELEASE)
                sinceBuild = pluginSinceBuild
            }
        }
    }
}


kotlin {
    jvmToolchain(21)
}
