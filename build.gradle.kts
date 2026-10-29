plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.xenonisawesome"
version = "0.1.0"

val platformType: String by project
val platformVersion: String by project
val pluginSinceBuild: String by project
val pluginUntilBuild: String by project

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
        }
    }
}


kotlin {
    jvmToolchain(21)
}
