import java.security.MessageDigest
import java.util.concurrent.TimeUnit

val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val decoderFromSource = providers.gradleProperty("questium.decoder.fromSource")
    .map { it.toBooleanStrict() }.getOrElse(false)
val explicitPrebuiltArm64 = providers.gradleProperty("imagedecoder.prebuiltArm64").orNull
require(!decoderFromSource || explicitPrebuiltArm64 == null) {
    "Choose questium.decoder.fromSource=true or imagedecoder.prebuiltArm64, not both"
}
val prebuiltArm64 = explicitPrebuiltArm64 ?: if (hostIsWindows && !decoderFromSource) {
    rootProject.file("../../native/decoder/arm64-v8a/libimagedecoder2.so").absolutePath
} else {
    null
}
val usePrebuiltArm64 = prebuiltArm64 != null
val prebuiltLibrary = prebuiltArm64?.let { file(it) }
if (usePrebuiltArm64) {
    val library = prebuiltLibrary!!
    require(library.isFile && library.name == "libimagedecoder2.so" && library.parentFile.name == "arm64-v8a") {
        "ARM64 decoder missing or misplaced: $library. Restore native/decoder or explicitly build from source."
    }
    val bytes = library.readBytes()
    require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() &&
        bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[4] == 2.toByte() && bytes[5] == 1.toByte() &&
        bytes[18] == 0xb7.toByte() && bytes[19] == 0.toByte()
    ) {
        "Decoder must be a 64-bit little-endian AArch64 ELF library: $library"
    }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    require(sha256 == "ff5eb9760fbd9c349875768ff7c610fd691d1d55f63f005b8b5e9b6f212db632") {
        "Decoder SHA-256 mismatch for $library: $sha256. Use the documented bundled artifact."
    }
    for (property in listOf("android.injected.build.abi", "questium.abis")) {
        val requested = providers.gradleProperty(property).orNull
            ?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        require(requested.all { it == "arm64-v8a" }) {
            "Bundled decoder supports only arm64-v8a; $property requested $requested. " +
                "Use -Pquestium.decoder.fromSource=true for other ABIs."
        }
    }
}

plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "ca.mpreg"
version = "0.0.0"

val tag = if (System.getenv("GITHUB_REF_TYPE") == "tag") {
    System.getenv("GITHUB_REF_NAME")
} else {
    val baseVersion = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.map { it.trim() }.getOrElse("unknown")
    "$baseVersion-SNAPSHOT"
}

android {
    namespace = "ca.mpreg.imagedecoder"
    compileSdk = 37

    (sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.gradle.api.AndroidSourceSet>).named("main") {
        // Explicit inputs only; ignore legacy cached binaries in src/main/jniLibs.
        jniLibs.setSrcDirs(if (usePrebuiltArm64) listOf(prebuiltLibrary!!.parentFile.parentFile) else emptyList<File>())
    }

    defaultConfig {
        minSdk = 24

        if (usePrebuiltArm64) ndk {
            abiFilters += "arm64-v8a"
        }

        if (!usePrebuiltArm64) externalNativeBuild {
            cmake {
                cppFlags("-O3 -flto")
                targets("ep_imagedecoder")
                // WSL is the SINGLE path for Windows native builds. No MSYS2/Git Bash fallback.
                // CMakeLists.txt (WIN32) will FATAL_ERROR if WSL is missing, so we only verify
                // here with a clear Gradle error before CMake configuration starts.
                if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                    val wslCandidates = listOf(
                        File("C:/Windows/System32/wsl.exe"),
                        File("C:/Windows/Sysnative/wsl.exe"),
                    )
                    val wslFoundViaFile = wslCandidates.any { it.exists() }
                    val wslFoundViaExec = try {
                        val proc = ProcessBuilder("wsl", "--status")
                            .redirectErrorStream(true).start()
                        proc.waitFor(8, TimeUnit.SECONDS)
                        wslFoundViaFile || proc.exitValue() == 0
                    } catch (_: Exception) {
                        wslFoundViaFile
                    }
                    if (!wslFoundViaExec) {
                        throw org.gradle.api.GradleException(
                            "WSL is required on Windows for imagedecoder native builds. " +
                                "WSL not found (checked 'wsl --status' and ${wslCandidates.joinToString()} ). " +
                                "Install WSL from https://aka.ms/wsl (run 'wsl --install' in elevated PowerShell), " +
                                "ensure 'wsl --status' works, then: pacman -S base-devel autoconf automake libtool pkg-config meson ninja cmake  inside WSL, " +
                                "and re-sync Gradle. MSYS2/Git Bash is NOT supported — WSL is the single only path.",
                        )
                    }
                    // Do NOT pass BASH_EXECUTABLE/Make_EXECUTABLE/Meson_EXECUTABLE for Windows:
                    // CMakeLists.txt detects WIN32+WSL and forces 'wsl bash' / 'wsl meson' / 'wsl make' etc.
                    // Passing MSYS Windows paths would override that and reintroduce C:/ vs /c/ bugs.
                    logger.lifecycle("Windows detected: delegating all native builds to WSL (single path).")
                }
            }
        }
    }

    if (!usePrebuiltArm64) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Enable Gradle build cache for native CMake tasks — :imagedecoder:buildCMakeDebug
// is otherwise always UP-TO-DATE= false due to absolute NDK paths. Combined with
// ccache/sccache (auto-detected in CMakeLists.txt), this cuts rebuilds from 3–5 min to ~15s.
// ccache env (CCACHE_SLOPPINESS etc.) is set inside CMakeLists.txt via set(ENV{}) so
// we don't need per-task environment() here — which would fail compilation for generic Task.
tasks.configureEach {
    if (name.contains("buildCMake") || name.contains("externalNativeBuild")) {
        outputs.cacheIf { true }
    }
}

dependencies {}

afterEvaluate {
    mavenPublishing {
        coordinates("ca.mpreg", "imagedecoder", tag)

        pom {
            name.set("imagedecoder")
            description.set("imagedecoder")
            inceptionYear.set("2026")
            url.set("https://github.com/mpreg-ca/imagedecoder")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("wwww-wwww")
                    name.set("w")
                    url.set("https://github.com/wwww-wwww/")
                }
            }
            scm {
                url.set("https://github.com/mpreg-ca/imagedecoder/")
                connection.set("scm:git:git://github.com/mpreg-ca/imagedecoder.git")
                developerConnection.set("scm:git:ssh://git@github.com/mpreg-ca/imagedecoder.git")
            }
        }

        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }
}
