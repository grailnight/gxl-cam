import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// libjxl (reference JPEG XL implementation) is not vendored into this repo.
// It is fetched once from the official repository, pinned to an exact commit,
// into <repo>/.deps/ and then built by CMake together with the JNI bridge.
// To use an existing checkout instead, pass -Pgxl.libjxl.dir=/path/to/libjxl
// (it must have third_party/highway, third_party/brotli, third_party/skcms).
// ---------------------------------------------------------------------------
val libjxlTag = "v0.12.0"
val libjxlCommit = "a7a9c787341cf703dede03c2009fa460cae5e5df"
val libjxlSubmodules = listOf("third_party/highway", "third_party/brotli", "third_party/skcms")

fun runGit(workDir: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        throw GradleException("git ${args.joinToString(" ")} failed:\n$output")
    }
    return output.trim()
}

val libjxlDir: File = (findProperty("gxl.libjxl.dir") as String?)?.let { File(it) }
    ?: rootProject.file(".deps/libjxl-$libjxlTag").also { dir ->
        val marker = File(dir, ".gxl-fetched")
        if (!marker.isFile || marker.readText().trim() != libjxlCommit) {
            logger.lifecycle("Fetching libjxl $libjxlTag into $dir ...")
            dir.deleteRecursively()
            dir.parentFile.mkdirs()
            runGit(
                dir.parentFile, "-c", "advice.detachedHead=false", "clone", "--depth", "1",
                "--branch", libjxlTag, "https://github.com/libjxl/libjxl.git", dir.absolutePath,
            )
            val head = runGit(dir, "rev-parse", "HEAD")
            if (head != libjxlCommit) {
                dir.deleteRecursively()
                throw GradleException("libjxl $libjxlTag resolved to $head, expected $libjxlCommit")
            }
            runGit(dir, "submodule", "update", "--init", "--depth", "1", "--", *libjxlSubmodules.toTypedArray())
            marker.writeText(libjxlCommit)
        }
    }

android {
    namespace = "io.github.grailnight.gxlcam"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.grailnight.gxlcam"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Pixel 9 (Tensor G4) is arm64 only.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DGXL_LIBJXL_DIR=${libjxlDir.absolutePath}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK is installable as-is.
            // Replace with your own signing config for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
