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
val pluginUntilBuild = providers.gradleProperty("pluginUntilBuild").get()

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
            untilBuild = pluginUntilBuild
        }
        changeNotes = "Initial version: record with rr, replay in the CLion debugger, reverse execution."
    }
    pluginVerification {
        ides {
            create(platformType, platformVersion)
            // Oldest supported release line (since-build 261).
            create(platformType, "2026.1.5.1")
        }
    }
}


kotlin {
    jvmToolchain(21)
}
