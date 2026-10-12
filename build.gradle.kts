import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.zf1234d"
version = "0.1.0"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    android {
        namespace = "io.github.zf1234d.avif"
        compileSdk = 37
        minSdk = 21
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dokka {
    dokkaPublications.html {
        moduleName.set("kavif-kmp")
    }
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        perPackageOption {
            matchingRegex.set("org\\.glavo\\.avif\\.internal(\\..*)?")
            suppress.set(true)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("kavif-kmp")
        description.set("Pure Kotlin AV1/AVIF decoder for Kotlin Multiplatform")
        inceptionYear.set("2026")
        url.set("https://github.com/zf1234d/kavif-kmp")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zf1234d")
                name.set("没空的人")
                url.set("https://github.com/zf1234d")
            }
        }
        scm {
            url.set("https://github.com/zf1234d/kavif-kmp")
            connection.set("scm:git:https://github.com/zf1234d/kavif-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/zf1234d/kavif-kmp.git")
        }
    }
}
