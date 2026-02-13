import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        jetbrainsIdeInstallers()
        androidStudioInstallers()
        releases()
        intellijDependencies()
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    gradlePluginPortal()
    maven { setUrl("https://maven.aliyun.com/repository/central") }
    maven { setUrl("https://maven.aliyun.com/repository/public") }
    maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { setUrl("https://maven.aliyun.com/repository/apache-snapshots") }

    maven { setUrl("https://jitpack.io") }
    maven { setUrl("https://jcenter.bintray.com/") }
    maven { setUrl("https://repo1.maven.org/maven2/") }
    maven { setUrl("https://oss.sonatype.org/content/repositories/snapshots/") }
    google()

}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
//    api (libs.kotlincompiler)
//    api (libs.kotlinreflect)
//    api (libs.kotlinstdlib)
    intellijPlatform {
        androidStudio( providers.gradleProperty("AndroidStudioVersion"))
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)

    }
}
kotlin {
    jvmToolchain(17)
}
intellijPlatformTesting {
    runIde
    testIde
    testIdeUi
    testIdePerformance
}
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xwhen-guards"))
}