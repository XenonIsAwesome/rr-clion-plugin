plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

val platformType = providers.gradleProperty("platformType").get()
val platformVersion = providers.gradleProperty("platformVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(platformType, platformVersion)
        bundledPlugins("com.intellij.clion", "com.intellij.cmake", "com.intellij.nativeDebug")
    }
    implementation(project(":core"))

}

kotlin {
    jvmToolchain(21)
}
