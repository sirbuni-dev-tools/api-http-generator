import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Fixed fake values; the same provider names as the HTTP Client's $random (which uses Java Faker).
    implementation("net.datafaker:datafaker:2.7.0")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Java PSI: reads controllers, SPQR resolvers and request/response classes.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    // No GUI forms or @NotNull instrumentation needed.
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // Local IDE installs to check against, e.g. -PverifyIdePaths="/path/A.app,/path/B.app".
    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdePaths").orNull
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { local(file(it)) }
        }
    }
}
