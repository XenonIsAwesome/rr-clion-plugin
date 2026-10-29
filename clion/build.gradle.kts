plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

val platformType: String by project
val platformVersion: String by project

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
